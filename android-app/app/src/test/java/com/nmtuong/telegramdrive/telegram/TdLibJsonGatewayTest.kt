package com.nmtuong.telegramdrive.telegram

import com.nmtuong.telegramdrive.data.AccountSessionIdentityProvider
import com.nmtuong.telegramdrive.domain.*
import com.nmtuong.telegramdrive.security.TelegramApiConfiguration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TdLibJsonGatewayTest {
    @After fun resetCounter() = TdLibJsonGateway.resetClientCountForTest()

    @Test fun startTwiceCreatesOneNativeClient() = withGateway { gateway, native ->
        gateway.start()
        gateway.start()
        runCurrent()
        assertEquals(1, native.createCalls)
        assertEquals(true, native.requests.first().contains("setLogVerbosityLevel"))
        assertEquals(true, native.requests.first().contains("new_verbosity_level\":0"))
        assertEquals(1, TdLibJsonGateway.activeClientCountForTest())
        gateway.close()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()
    }

    @Test fun closeTwiceIsIdempotentAndCounterNeverBecomesNegative() = withGateway { gateway, native ->
        gateway.start()
        runCurrent()
        gateway.close()
        // Simulate TDLib confirming close
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()
        gateway.close()
        runCurrent()
        assertEquals(0, TdLibJsonGateway.activeClientCountForTest())
        assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)
    }

    @Test fun closeDuringLibraryInitializationPreventsClientCreation() {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val native = RecordingNative()
            lateinit var gateway: TdLibJsonGateway
            gateway = TdLibJsonGateway(
                native = native,
                libraryLoader = NativeLibraryLoader { gateway.close() },
                dispatcher = dispatcher,
            )
            gateway.start()
            runCurrent()
            assertEquals(0, native.createCalls)
            assertEquals(0, TdLibJsonGateway.activeClientCountForTest())
            assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)
        }
    }

    @Test fun closeAfterLibraryLoadFailureIsSafe() {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val gateway = TdLibJsonGateway(
                native = RecordingNative(),
                libraryLoader = NativeLibraryLoader { error("load failed") },
                dispatcher = dispatcher,
            )
            gateway.start()
            runCurrent()
            assertEquals(GatewayLifecycle.FAILED, gateway.state.value.lifecycle)
            gateway.close()
            runCurrent()
            assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)
            assertEquals(0, TdLibJsonGateway.activeClientCountForTest())
        }
    }

    @Test fun closeAfterCreateFailureIsSafe() {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val native = RecordingNative(failCreate = true)
            val gateway = TdLibJsonGateway(
                native = native,
                libraryLoader = NativeLibraryLoader {},
                dispatcher = dispatcher,
            )
            gateway.start()
            runCurrent()
            assertEquals(GatewayLifecycle.FAILED, gateway.state.value.lifecycle)
            assertFalse(gateway.state.value.clientCreated)
            gateway.close()
            runCurrent()
            assertEquals(0, TdLibJsonGateway.activeClientCountForTest())
        }
    }

    @Test fun immediateStartThenCloseDoesNotCreateClient() = withGateway { gateway, native ->
        gateway.start()
        gateway.close()
        runCurrent()
        assertEquals(0, native.createCalls)
        assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)
    }

    @Test fun mapsSupportedSavedMessageAndFiltersUnsupportedContent() {
        // Use MessageMapper directly instead of internal mapMessageForTest
        val photo = """{"id":42,"chat_id":7,"content":{"@type":"messagePhoto","photo":{"sizes":[{"photo":{"id":99,"size":123,"local":{"path":"","is_downloading_completed":false}}}]}}}"""
        val text = """{"id":43,"chat_id":7,"content":{"@type":"messageText","text":{"text":"ignored"}}}"""
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val mapped = MessageMapper.mapMessage(json.parseToJsonElement(photo).jsonObject)
        assertEquals(42L, mapped?.id)
        assertEquals(99, mapped?.fileId)
        assertEquals(MediaKind.IMAGE, mapped?.kind)
        assertNull(MessageMapper.mapMessage(json.parseToJsonElement(text).jsonObject))
    }

    @Test fun cancelAcknowledgementBlocksRetryAndIgnoresLateFileUpdate() = runTest {
        val native = RecordingNative()
        val identityProvider = AccountSessionIdentityProvider().also { it.initializeFake(7L) }
        val gateway = TdLibJsonGateway(
            configuration = TelegramApiConfiguration(1, "configured-placeholder"),
            native = native,
            libraryLoader = NativeLibraryLoader {},
            dispatcher = StandardTestDispatcher(testScheduler),
            identityProvider = identityProvider,
        )
        gateway.start()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateReady"}""")
        assertEquals(ActionResult.ACCEPTED, gateway.loadSavedMessages(50))
        val getMeExtra = requestExtra(native.requests.last())
        gateway.handleResponseForTest("""{"@type":"user","@extra":"$getMeExtra","id":7}""")
        val historyExtra = requestExtra(native.requests.last())
        gateway.handleResponseForTest(
            """{"@type":"messages","@extra":"$historyExtra","messages":[{"id":42,"chat_id":7,"content":{"@type":"messagePhoto","photo":{"sizes":[{"photo":{"id":99,"size":123,"local":{"path":"","is_downloading_completed":false}}}]}}}]}""",
        )
        assertEquals(ActionResult.ACCEPTED, gateway.download(99))
        assertEquals(ActionResult.ACCEPTED, gateway.cancelDownload(99))
        val cancelExtra = requestExtra(native.requests.last())
        assertEquals(ActionResult.DUPLICATE, gateway.download(99))
        gateway.handleResponseForTest(
            """{"@type":"updateFile","file":{"id":99,"size":123,"local":{"path":"","downloaded_size":80,"is_downloading_completed":false}}}""",
        )
        val canceled = (gateway.library.value as LibraryState.Content).items.single()
        assertEquals(DownloadState.Canceled, canceled.downloadState)
        gateway.handleResponseForTest("""{"@type":"ok","@extra":"$cancelExtra"}""")
        assertEquals(ActionResult.ACCEPTED, gateway.download(99))
        gateway.close()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(GatewayLifecycle.CLOSED, gateway.state.value.lifecycle)
    }

    @Test fun authSubmitIsStateGatedDeduplicatedAndErrorIsRedacted() = runTest {
        val native = RecordingNative()
        val gateway = TdLibJsonGateway(
            configuration = TelegramApiConfiguration(1, "configured-placeholder"),
            native = native,
            libraryLoader = NativeLibraryLoader {},
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        gateway.start()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateWaitPhoneNumber"}""")
        assertEquals(ActionResult.INVALID_STATE, gateway.submit(AuthorizationAction.SubmitCode("input")))
        assertEquals(ActionResult.ACCEPTED, gateway.submit(AuthorizationAction.SubmitPhone("+000000000")))
        assertEquals(ActionResult.DUPLICATE, gateway.submit(AuthorizationAction.SubmitPhone("+000000000")))
        assertEquals(true, native.requests.last().contains("\"settings\":null"))
        gateway.handleResponseForTest(
            """{"@type":"error","@extra":"auth:stale","code":400,"message":"phone_number=+000000000"}""",
        )
        assertEquals(true, gateway.authorization.value.actionPending)
        val authExtra = requestExtra(native.requests.last())
        gateway.handleResponseForTest(
            """{"@type":"error","@extra":"$authExtra","code":400,"message":"phone_number=+000000000 code=value-c"}""",
        )
        assertFalse(gateway.authorization.value.actionPending)
        assertFalse(gateway.authorization.value.safeError.orEmpty().contains("000000000"))
        assertFalse(gateway.authorization.value.safeError.orEmpty().contains("value-c"))
        gateway.close()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()
    }

    @Test fun missingApiConfigurationIsActionableInsteadOfLoadingForever() = runTest {
        val gateway = TdLibJsonGateway(
            native = RecordingNative(),
            libraryLoader = NativeLibraryLoader {},
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        gateway.start()
        runCurrent()

        assertEquals(AuthorizationState.MissingConfiguration, gateway.authorization.value.state)
        assertEquals(AuthorizationErrorKind.CONFIGURATION, gateway.authorization.value.error?.kind)
        assertFalse(gateway.authorization.value.actionPending)
        gateway.close()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()
    }

    @Test fun codeMetadataEnablesResendAndChangingPhone() = runTest {
        val native = RecordingNative()
        val gateway = TdLibJsonGateway(
            configuration = TelegramApiConfiguration(1, "configured-placeholder"),
            native = native,
            libraryLoader = NativeLibraryLoader {},
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        gateway.start()
        runCurrent()
        gateway.handleResponseForTest(
            """{"@type":"authorizationStateWaitCode","code_info":{"phone_number":"+10000000000","type":{"@type":"authenticationCodeTypeSmsWord","first_letter":"A"},"next_type":{"@type":"authenticationCodeTypeSms","length":5},"timeout":0}}""",
        )

        assertEquals(ActionResult.ACCEPTED, gateway.submit(AuthorizationAction.ResendCode))
        assertTrue(native.requests.last().contains("resendAuthenticationCode"))
        val resendExtra = requestExtra(native.requests.last())
        gateway.handleResponseForTest("""{"@type":"error","@extra":"$resendExtra","code":400,"message":"PHONE_CODE_INVALID"}""")

        assertEquals(ActionResult.ACCEPTED, gateway.submit(AuthorizationAction.ChangePhone("+19999999999")))
        assertTrue(native.requests.last().contains("setAuthenticationPhoneNumber"))
        assertTrue(native.requests.last().contains("+19999999999"))
        gateway.close()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()
    }

    @Test fun qrActionUsesOfficialRequestBeforeOtherDeviceState() = runTest {
        val native = RecordingNative()
        val gateway = TdLibJsonGateway(
            configuration = TelegramApiConfiguration(1, "configured-placeholder"),
            native = native,
            libraryLoader = NativeLibraryLoader {},
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        gateway.start()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateWaitPhoneNumber"}""")

        assertEquals(ActionResult.ACCEPTED, gateway.submit(AuthorizationAction.RequestQrCode))
        assertTrue(native.requests.last().contains("requestQrCodeAuthentication"))
        val qrExtra = requestExtra(native.requests.last())
        gateway.handleResponseForTest("""{"@type":"ok","@extra":"$qrExtra"}""")
        gateway.handleResponseForTest("""{"@type":"authorizationStateWaitOtherDeviceConfirmation","link":"tg://login?token=safe-placeholder"}""")
        assertEquals(
            AuthorizationState.WaitingForOtherDevice("tg://login?token=safe-placeholder"),
            gateway.authorization.value.state,
        )
        gateway.close()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()
    }

    @Test fun registrationExplicitlyAcceptsTermsThenSubmitsNames() = runTest {
        val native = RecordingNative()
        val gateway = TdLibJsonGateway(
            configuration = TelegramApiConfiguration(1, "configured-placeholder"),
            native = native,
            libraryLoader = NativeLibraryLoader {},
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        gateway.start()
        runCurrent()
        gateway.handleResponseForTest(
            """{"@type":"authorizationStateWaitRegistration","terms_of_service":{"id":"terms-v1","text":{"@type":"formattedText","text":"Terms"}}}""",
        )
        assertEquals(
            ActionResult.INVALID_STATE,
            gateway.submit(AuthorizationAction.SubmitRegistration("Ada", "Lovelace", false)),
        )
        assertEquals(
            ActionResult.ACCEPTED,
            gateway.submit(AuthorizationAction.SubmitRegistration("Ada", "Lovelace", true)),
        )
        assertTrue(native.requests.last().contains("registerUser"))
        assertTrue(native.requests.last().contains("Ada"))
        val registrationExtra = requestExtra(native.requests.last())
        gateway.handleResponseForTest("""{"@type":"ok","@extra":"$registrationExtra"}""")
        gateway.handleResponseForTest("""{"@type":"authorizationStateReady"}""")
        assertEquals(AuthorizationState.Ready, gateway.authorization.value.state)
        gateway.close()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()
    }

    @Test fun authRequestTimeoutClearsPendingState() = runTest {
        val gateway = TdLibJsonGateway(
            configuration = TelegramApiConfiguration(1, "configured-placeholder"),
            native = RecordingNative(),
            libraryLoader = NativeLibraryLoader {},
            dispatcher = StandardTestDispatcher(testScheduler),
            authActionTimeoutMs = 100,
        )
        gateway.start()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateWaitPhoneNumber"}""")
        assertEquals(ActionResult.ACCEPTED, gateway.submit(AuthorizationAction.SubmitPhone("+10000000000")))
        advanceTimeBy(101)
        runCurrent()

        assertFalse(gateway.authorization.value.actionPending)
        assertEquals(AuthorizationErrorKind.NETWORK, gateway.authorization.value.error?.kind)
        gateway.close()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()
    }

    @Test fun nativeAuthSendFailureClearsPendingState() = runTest {
        val gateway = TdLibJsonGateway(
            configuration = TelegramApiConfiguration(1, "configured-placeholder"),
            native = FailingAuthSendNative(),
            libraryLoader = NativeLibraryLoader {},
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        gateway.start()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateWaitPhoneNumber"}""")

        assertEquals(ActionResult.INVALID_STATE, gateway.submit(AuthorizationAction.SubmitPhone("+10000000000")))
        assertFalse(gateway.authorization.value.actionPending)
        assertEquals(AuthorizationErrorKind.NETWORK, gateway.authorization.value.error?.kind)
        gateway.close()
        runCurrent()
        gateway.handleResponseForTest("""{"@type":"authorizationStateClosed"}""")
        runCurrent()
    }

    private fun withGateway(
        block: suspend TestScope.(TdLibJsonGateway, RecordingNative) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val native = RecordingNative()
        val gateway = TdLibJsonGateway(native = native, libraryLoader = NativeLibraryLoader {}, dispatcher = dispatcher)
        block(gateway, native)
    }

    private fun requestExtra(request: String): String =
        Regex(""""@extra":"([^"]+)"""").find(request)!!.groupValues[1]
}

private class RecordingNative(private val failCreate: Boolean = false) : TdLibNative {
    var createCalls = 0
    var closeRequests = 0
    val requests = mutableListOf<String>()
    private var responseDelivered = false
    private var closeResponseDelivered = false

    override fun createClientId(): Int {
        createCalls++
        if (failCreate) error("create failed")
        return 7
    }

    override fun send(clientId: Int, request: String) {
        requests += request
        if (request.contains("\"close\"")) closeRequests++
    }

    override fun receive(timeoutSeconds: Double): String? {
        if (closeRequests > 0 && !closeResponseDelivered) {
            closeResponseDelivered = true
            return """{"@type":"authorizationStateClosed"}"""
        }
        if (responseDelivered) return null
        responseDelivered = true
        return """{"@type":"authorizationStateWaitTdlibParameters"}"""
    }
}

private class FailingAuthSendNative : TdLibNative {
    private var firstReceive = true

    override fun createClientId(): Int = 7

    override fun send(clientId: Int, request: String) {
        if (request.contains("setAuthenticationPhoneNumber")) error("network unavailable")
    }

    override fun receive(timeoutSeconds: Double): String? {
        if (firstReceive) {
            firstReceive = false
            return """{"@type":"authorizationStateWaitTdlibParameters"}"""
        }
        return null
    }
}
