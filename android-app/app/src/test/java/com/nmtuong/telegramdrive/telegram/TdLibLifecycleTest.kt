package com.nmtuong.telegramdrive.telegram

import com.nmtuong.telegramdrive.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic tests for TdLib gateway lifecycle under Checkpoint 2 rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TdLibLifecycleTest {

    @After
    fun resetCounter() = TdLibJsonGateway.resetClientCountForTest()

    // ── Close before initialization ──────────────────────────────────────────

    @Test
    fun `close before start transitions to CLOSED without client creation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val native = TrackingNative()
        val gateway = TdLibJsonGateway(native = native, libraryLoader = NativeLibraryLoader {}, dispatcher = dispatcher)

        gateway.close()
        runCurrent()

        assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)
        assertEquals(0, native.createCalls)
        assertEquals(0, TdLibJsonGateway.activeClientCountForTest())
    }

    // ── Close while running ──────────────────────────────────────────────────

    @Test
    fun `close while running sends TDLib close and strictly transitions to CLOSING`() = withGateway { gateway, native ->
        gateway.start()
        runCurrent()

        assertEquals(GatewayLifecycle.RUNNING, gateway.state.value.lifecycle)
        gateway.close()
        runCurrent()

        // Must strictly be CLOSING (never CLOSED before authorizationStateClosed)
        assertEquals(GatewayLifecycle.CLOSING, gateway.state.value.lifecycle)
        assertTrue("Should have sent close request", native.closeRequestsSent > 0)
    }

    @Test
    fun `close while running — finalizes to CLOSED only after authorizationStateClosed`() = withGateway { gateway, native ->
        gateway.start()
        runCurrent()

        gateway.close()
        runCurrent()

        assertEquals(GatewayLifecycle.CLOSING, gateway.state.value.lifecycle)

        // Simulate TDLib responding with authorizationStateClosed
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()

        assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)
        assertEquals(0, TdLibJsonGateway.activeClientCountForTest())
        assertEquals(AuthorizationState.Closed, gateway.authorization.value.state)
    }

    // ── Close twice ──────────────────────────────────────────────────────────

    @Test
    fun `close twice is idempotent — counter never becomes negative`() = withGateway { gateway, native ->
        gateway.start()
        runCurrent()

        gateway.close()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()
        gateway.close() // Second close
        runCurrent()

        assertEquals(0, TdLibJsonGateway.activeClientCountForTest())
        assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)
        assertEquals(1, native.closeRequestsSent)
    }

    // ── authorizationStateClosing ────────────────────────────────────────────

    @Test
    fun `authorizationStateClosing is received before authorizationStateClosed`() = withGateway { gateway, _ ->
        gateway.start()
        runCurrent()

        gateway.close()
        runCurrent()

        gateway.handleResponseForTest("""{"@type":"authorizationStateClosing"}""")
        runCurrent()

        assertEquals(GatewayLifecycle.CLOSING, gateway.state.value.lifecycle)
        val authState = gateway.authorization.value.state
        assertTrue(
            "Expected Closing or LoggingOut auth state, was $authState",
            authState == AuthorizationState.Closing || authState == AuthorizationState.LoggingOut
        )
    }

    // ── authorizationStateClosed ─────────────────────────────────────────────

    @Test
    fun `authorizationStateClosed triggers finalizeClose and decrements counter`() = withGateway { gateway, _ ->
        gateway.start()
        runCurrent()

        assertEquals(1, TdLibJsonGateway.activeClientCountForTest())

        gateway.close()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()

        assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)
        assertEquals(0, TdLibJsonGateway.activeClientCountForTest())
    }

    // ── Timeout path ─────────────────────────────────────────────────────────

    @Test
    fun `close timeout transitions to ABORTED and does not fake CLOSED state`() = withGateway { gateway, _ ->
        gateway.start()
        runCurrent()

        gateway.close()
        runCurrent()

        assertEquals(GatewayLifecycle.CLOSING, gateway.state.value.lifecycle)

        // Advance time beyond CLOSE_TIMEOUT_MS (5000ms) without sending authorizationStateClosed
        testScheduler.advanceTimeBy(6000)
        runCurrent()

        // Must be ABORTED, NOT CLOSED!
        assertEquals(GatewayLifecycle.ABORTED, gateway.state.value.lifecycle)
        assertNotEquals(AuthorizationState.Closed, gateway.authorization.value.state)
        assertEquals(0, TdLibJsonGateway.activeClientCountForTest())
    }

    // ── Native close send failure ─────────────────────────────────────────────

    @Test
    fun `native send close failure transitions to ABORTED`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val native = FailingSendNative()
        val gateway = TdLibJsonGateway(
            native = native,
            libraryLoader = NativeLibraryLoader {},
            dispatcher = dispatcher,
        )
        gateway.start()
        runCurrent()

        gateway.close()
        runCurrent()

        assertEquals(GatewayLifecycle.ABORTED, gateway.state.value.lifecycle)
        assertEquals(0, TdLibJsonGateway.activeClientCountForTest())
    }

    // ── No updates after Closed or Aborted ───────────────────────────────────

    @Test
    fun `no state updates processed after authorizationStateClosed`() = withGateway { gateway, _ ->
        gateway.start()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateReady"}""")
        runCurrent()

        gateway.close()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()

        assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)

        // Send an update after closed — should not change state
        gateway.handleResponseForTest("""{"@type":"authorizationStateWaitPhoneNumber"}""")
        runCurrent()

        assertEquals(AuthorizationState.Closed, gateway.authorization.value.state)
    }

    // ── Client count decremented only after terminal close ───────────────────

    @Test
    fun `client count decremented only after authorizationStateClosed not during CLOSING`() = withGateway { gateway, _ ->
        gateway.start()
        runCurrent()

        assertEquals(1, TdLibJsonGateway.activeClientCountForTest())

        gateway.close()
        runCurrent()

        assertEquals("Count should not drop during CLOSING", 1, TdLibJsonGateway.activeClientCountForTest())

        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()

        assertEquals("Count should drop to 0 after Closed", 0, TdLibJsonGateway.activeClientCountForTest())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun withGateway(
        block: suspend TestScope.(TdLibJsonGateway, TrackingNative) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val native = TrackingNative()
        val gateway = TdLibJsonGateway(
            native = native,
            libraryLoader = NativeLibraryLoader {},
            dispatcher = dispatcher,
            closeTimeoutMs = 5000L,
        )
        block(gateway, native)
        TdLibJsonGateway.resetClientCountForTest()
    }
}

// ── Test doubles ─────────────────────────────────────────────────────────────

internal class TrackingNative(private val failCreate: Boolean = false) : TdLibNative {
    var createCalls = 0
    var closeRequestsSent = 0
    val sentRequests = mutableListOf<String>()
    private var firstReceive = true

    override fun createClientId(): Int {
        createCalls++
        if (failCreate) error("create failed")
        return 42
    }

    override fun send(clientId: Int, request: String) {
        sentRequests += request
        if (request.contains("\"close\"")) closeRequestsSent++
    }

    override fun receive(timeoutSeconds: Double): String? {
        if (firstReceive) {
            firstReceive = false
            return """{"@type":"authorizationStateWaitTdlibParameters"}"""
        }
        return null
    }
}

internal class FailingSendNative : TdLibNative {
    private var clientCreated = false
    private var responseSent = false

    override fun createClientId(): Int {
        clientCreated = true
        return 99
    }

    override fun send(clientId: Int, request: String) {
        if (request.contains("\"close\"")) {
            error("Native socket broken on close")
        }
    }

    override fun receive(timeoutSeconds: Double): String? {
        if (clientCreated && !responseSent) {
            responseSent = true
            return """{"@type":"authorizationStateWaitTdlibParameters"}"""
        }
        return null
    }
}
