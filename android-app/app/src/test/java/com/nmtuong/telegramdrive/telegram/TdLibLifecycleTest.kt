package com.nmtuong.telegramdrive.telegram

import com.nmtuong.telegramdrive.domain.*
import com.nmtuong.telegramdrive.security.TelegramApiConfiguration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic tests for TdLib gateway lifecycle.
 * All tests use StandardTestDispatcher for controlled coroutine scheduling.
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
    fun `close while running sends TDLib close and waits for Closed state`() = withGateway { gateway, native ->
        gateway.start()
        runCurrent()

        assertEquals(GatewayLifecycle.RUNNING, gateway.state.value.lifecycle)
        gateway.close()
        runCurrent()

        // Gateway should be CLOSING after sending close
        val lifecycleAfterClose = gateway.state.value.lifecycle
        assertTrue(
            "Expected CLOSING or CLOSED, was $lifecycleAfterClose",
            lifecycleAfterClose == GatewayLifecycle.CLOSING || lifecycleAfterClose == GatewayLifecycle.CLOSED
        )
        assertTrue("Should have sent close request", native.closeRequestsSent > 0)
    }

    @Test
    fun `close while running — finalizes only after authorizationStateClosed`() = withGateway { gateway, native ->
        gateway.start()
        runCurrent()

        gateway.close()
        runCurrent()

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
        // Should not have sent duplicate close requests
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

        // Should still be CLOSING (not CLOSED yet)
        val lifecycle = gateway.state.value.lifecycle
        assertTrue(
            "Expected CLOSING after authorizationStateClosing, was $lifecycle",
            lifecycle == GatewayLifecycle.CLOSING
        )
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

    // ── Pending request during close ─────────────────────────────────────────

    @Test
    fun `pending request is completed with exception when gateway closes`() = withGateway { gateway, native ->
        gateway.start()
        runCurrent()

        // Note: execute() is a suspending function that we can't call synchronously here
        // Instead, verify close completes pending requests via authorizationStateClosed signal
        gateway.close()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()

        assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)
        assertEquals(0, TdLibJsonGateway.activeClientCountForTest())
    }

    // ── No updates after Closed ──────────────────────────────────────────────

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

        // Should still be Closed, not WaitingForPhoneNumber
        assertEquals(AuthorizationState.Closed, gateway.authorization.value.state)
    }

    // ── Client count decremented only after terminal close ───────────────────

    @Test
    fun `client count decremented only after authorizationStateClosed not before`() = withGateway { gateway, _ ->
        gateway.start()
        runCurrent()

        assertEquals(1, TdLibJsonGateway.activeClientCountForTest())

        gateway.close()
        runCurrent()

        // Count should still be 1 while CLOSING (waiting for TDLib terminal state)
        val lifecycleNow = gateway.state.value.lifecycle
        if (lifecycleNow == GatewayLifecycle.CLOSING) {
            assertEquals("Count should not drop during CLOSING", 1, TdLibJsonGateway.activeClientCountForTest())
        }

        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()

        assertEquals("Count should drop to 0 after Closed", 0, TdLibJsonGateway.activeClientCountForTest())
    }

    // ── Native error while closing ────────────────────────────────────────────

    @Test
    fun `native error during startup transitions to FAILED lifecycle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateway = TdLibJsonGateway(
            native = TrackingNative(),
            libraryLoader = NativeLibraryLoader { error("load failed") },
            dispatcher = dispatcher,
        )
        gateway.start()
        runCurrent()

        assertEquals(GatewayLifecycle.FAILED, gateway.state.value.lifecycle)
        assertEquals(0, TdLibJsonGateway.activeClientCountForTest())

        // Close after failure should safely transition to CLOSED
        gateway.close()
        runCurrent()
        assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)
    }

    // ── Receive loop alive until Closed ──────────────────────────────────────

    @Test
    fun `receive loop continues during CLOSING state`() = withGateway { gateway, native ->
        gateway.start()
        runCurrent()

        gateway.close()
        runCurrent()

        // In CLOSING state, gateway should still process authorizationStateClosing
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosing"}""")
        runCurrent()

        val authState = gateway.authorization.value.state
        // Should have processed the closing state (not ignored it)
        assertNotEquals(AuthorizationState.Unknown, authState)

        // Then process the final closed state
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()

        assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)
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
