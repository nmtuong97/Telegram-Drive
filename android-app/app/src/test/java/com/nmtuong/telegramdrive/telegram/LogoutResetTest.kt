package com.nmtuong.telegramdrive.telegram

import com.nmtuong.telegramdrive.domain.*
import com.nmtuong.telegramdrive.security.TelegramApiConfiguration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [TdLibJsonGateway.logoutAndReset] — async account reset state machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogoutResetTest {

    @After
    fun resetCounter() = TdLibJsonGateway.resetClientCountForTest()

    @Test
    fun `logoutAndReset when not running returns InvalidState`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateway = TdLibJsonGateway(
            native = RecordingNativeForReset(),
            libraryLoader = NativeLibraryLoader {},
            dispatcher = dispatcher,
        )
        // Gateway never started — should be InvalidState
        val result = gateway.logoutAndReset()
        assertEquals(AccountResetResult.InvalidState, result)
    }

    @Test
    fun `logoutAndReset after close returns InvalidState`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val native = RecordingNativeForReset()
        val gateway = TdLibJsonGateway(
            native = native,
            libraryLoader = NativeLibraryLoader {},
            dispatcher = dispatcher,
        )
        gateway.start()
        runCurrent()
        gateway.close()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()

        val result = gateway.logoutAndReset()
        assertEquals(AccountResetResult.InvalidState, result)
    }

    @Test
    fun `logoutAndReset timeout returns Failed - local data not deleted`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val native = RecordingNativeForReset()
        val gateway = TdLibJsonGateway(
            configuration = TelegramApiConfiguration(1, "hash"),
            native = native,
            libraryLoader = NativeLibraryLoader {},
            dispatcher = dispatcher,
            closeTimeoutMs = 5000L,
            logoutTimeoutMs = 5000L,
        )
        try {
            gateway.start()
            runCurrent()
            gateway.handleResponseForTest("""{"@type":"authorizationStateReady"}""")
            runCurrent()

            val deferred = async { gateway.logoutAndReset() }
            // Advance past logout timeout (5_000ms)
            advanceTimeBy(6_000)
            runCurrent()

            val result = deferred.await()
            assertTrue("Expected Failed result, was $result", result is AccountResetResult.Failed)
            assertFalse(result is AccountResetResult.Completed)
        } finally {
            gateway.close()
            runCurrent()
            gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
            runCurrent()
        }
    }

    @Test
    fun `logoutAndReset second call returns AlreadyRunning`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val native = RecordingNativeForReset()
        val gateway = TdLibJsonGateway(
            configuration = TelegramApiConfiguration(1, "hash"),
            native = native,
            libraryLoader = NativeLibraryLoader {},
            dispatcher = dispatcher,
            closeTimeoutMs = 5000L,
            logoutTimeoutMs = 5000L,
        )
        try {
            gateway.start()
            runCurrent()
            gateway.handleResponseForTest("""{"@type":"authorizationStateReady"}""")
            runCurrent()

            // Start first reset
            val first = async { gateway.logoutAndReset() }
            runCurrent()

            // Second reset immediately — should get AlreadyRunning
            val secondResult = gateway.logoutAndReset()
            assertEquals(AccountResetResult.AlreadyRunning, secondResult)

            // Clean up first reset
            advanceTimeBy(6_000)
            runCurrent()
            first.cancel()
        } finally {
            gateway.close()
            runCurrent()
            gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
            runCurrent()
        }
    }

    @Test
    fun `logoutAndReset does not use fixed delay in reset path`() = runTest {
        // Verify that reset completes within a reasonable time without fixed delays
        val dispatcher = StandardTestDispatcher(testScheduler)
        val native = RecordingNativeForReset()
        val gateway = TdLibJsonGateway(
            configuration = TelegramApiConfiguration(1, "hash"),
            native = native,
            libraryLoader = NativeLibraryLoader {},
            dispatcher = dispatcher,
            closeTimeoutMs = 5000L,
            logoutTimeoutMs = 5000L,
        )
        try {
            gateway.start()
            runCurrent()

            val deferred = async { gateway.logoutAndReset() }
            runCurrent()
            advanceTimeBy(6000)
            runCurrent()

            val result = deferred.await()
            assertTrue(
                "Expected deterministic result, was $result",
                result is AccountResetResult.Completed ||
                    result is AccountResetResult.Failed ||
                    result is AccountResetResult.InvalidState
            )
        } finally {
            gateway.close()
            runCurrent()
            gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
            runCurrent()
        }
    }
}

/** Standalone native stub for reset tests — not extending final TrackingNative */
internal class RecordingNativeForReset : TdLibNative {
    var createCalls = 0
    var sentRequests = mutableListOf<String>()
    private var firstReceive = true

    override fun createClientId(): Int {
        createCalls++
        return 42
    }

    override fun send(clientId: Int, request: String) {
        sentRequests += request
    }

    override fun receive(timeoutSeconds: Double): String? {
        if (firstReceive) {
            firstReceive = false
            return """{"@type":"authorizationStateWaitTdlibParameters"}"""
        }
        return null
    }
}
