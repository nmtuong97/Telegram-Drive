package com.nmtuong.telegramdrive.telegram

import android.content.Context
import com.nmtuong.telegramdrive.data.AccountSessionIdentityProvider
import com.nmtuong.telegramdrive.domain.*

import com.nmtuong.telegramdrive.security.SensitiveDataRedactor
import com.nmtuong.telegramdrive.security.TelegramApiConfiguration
import java.io.File
import com.nmtuong.telegramdrive.security.DatabaseEncryptionManager
import com.nmtuong.telegramdrive.security.DatabaseKeyException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/**
 * Maximum wait time for TDLib to confirm authorizationStateClosed after sending close().
 * After this, gateway transitions to FAILED_CLOSE and resources are released.
 */
private const val CLOSE_TIMEOUT_MS = 20_000L

/**
 * Maximum wait time for TDLib logOut to succeed during account reset.
 * logOut requires network; if this times out, reset returns a recoverable error.
 */
private const val LOGOUT_TIMEOUT_MS = 30_000L
private const val AUTH_ACTION_TIMEOUT_MS = 15_000L
private const val MAX_PARAMETER_ATTEMPTS = 2

/**
 * Safety limit for filtered empty page scanning.
 * Prevents infinite scan when all history is text-only content.
 */
private const val EMPTY_PAGE_SCAN_LIMIT = 10
private const val SOURCE_CHAT_LIMIT = 50
private val ELIGIBLE_SOURCE_CHAT_TYPES = setOf(
    "chatTypePrivate",
    "chatTypeBasicGroup",
    "chatTypeSupergroup",
)
private val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "json", "xml", "log")

private fun mimeTypeForName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "txt", "md", "csv", "json", "xml", "log" -> "text/plain"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "mp3" -> "audio/mpeg"
    "ogg" -> "audio/ogg"
    "wav" -> "audio/wav"
    "gif" -> "image/gif"
    "mp4" -> "video/mp4"
    else -> null
}

private fun isNetworkFailure(message: String): Boolean {
    val upper = message.uppercase()
    return upper.contains("NETWORK") ||
        upper.contains("TIMEOUT") ||
        upper.contains("CONNECTION") ||
        upper.contains("OFFLINE") ||
        upper.contains("UNAVAILABLE") ||
        upper.contains("HOST")
}

private fun safeNetworkMessage(message: String): String {
    if (isNetworkFailure(message)) return "Network unavailable. Check your connection and retry."
    return SensitiveDataRedactor.redact(message).takeIf { it.isNotBlank() }
        ?: "Telegram request failed"
}

private data class PendingTransferContext(
    val operationId: TransferOperationId,
    val identity: TransferIdentity,
    val messageId: Long,
    val sourceId: Long,
    val mediaKind: MediaKind,
    val downloadExtra: String,
    var cancelExtra: String? = null,
    val expectedSizeBytes: Long = 0L,
    val knownLocalPath: String? = null,
)

class TdLibJsonGateway internal constructor(
    context: Context? = null,
    private val configuration: TelegramApiConfiguration = TelegramApiConfiguration(0, ""),
    private val native: TdLibNative = JsonTdLibNative,
    private val libraryLoader: NativeLibraryLoader = NativeLibraryLoader { System.loadLibrary("tdjsonjava") },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val closeTimeoutMs: Long = CLOSE_TIMEOUT_MS,
    private val logoutTimeoutMs: Long = LOGOUT_TIMEOUT_MS,
    private val authActionTimeoutMs: Long = AUTH_ACTION_TIMEOUT_MS,
    private val identityProvider: AccountSessionIdentityProvider? = null,
    /** CP7: Provides current account identity; injected to remove hardcoded (1L,1L). */
    private val currentAccountId: () -> Long = { identityProvider?.accountId ?: 0L },
    private val currentDatabaseGeneration: () -> Long = { identityProvider?.databaseGeneration ?: 1L },
) : TdLibGateway {

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val json = Json { ignoreUnknownKeys = true }
    private val databaseDirectory = context?.filesDir?.resolve("tdlib/database") ?: File("tdlib-test/database")
    private val filesDirectory = context?.filesDir?.resolve("tdlib/files") ?: File("tdlib-test/files")
    private val encryptionManager = context?.let { DatabaseEncryptionManager(it) }
    private var lifecycle = GatewayLifecycle.NEW
    private var worker: Job? = null
    private var clientId: Int? = null
    private var countedClient = false
    private var pendingAuthAction = false
    private var pendingAuthRequest: String? = null
    private var authActionTimeoutJob: Job? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    // Legacy fields kept for backward compatibility
    private var pendingParametersRequest: String? = null
    private var parametersTimeoutJob: Job? = null
    private var parameterAttempts = 0
    private var authResetting = false
    private var pendingLibraryRequest: String? = null
    private var pendingHistoryLimit = 50
    private val pendingDownloads = mutableSetOf<Int>()
    private val pendingCancellations = mutableSetOf<Int>()
    private val pendingDownloadRequests = mutableMapOf<Int, String>()
    private val pendingCancelRequests = mutableMapOf<Int, String>()
    private val pendingTransferContexts = ConcurrentHashMap<Int, PendingTransferContext>()
    private val fileSnapshots = ConcurrentHashMap<Int, TdLibFileSnapshot>()
    private val deletedTemporaryFileIds = ConcurrentHashMap.newKeySet<Int>()
    /** File IDs observed before logout/reset must not be resurrected by late updateFile events. */
    private val staleFileIds = ConcurrentHashMap.newKeySet<Int>()
    /** After an account boundary, only an explicit request may re-enable a file ID. */
    private val allowedFileIds = ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var fileObservationBlocked = false
    private val attemptSequenceMap = ConcurrentHashMap<Int, AtomicLong>()
    private var resolveIdentityJob: Job? = null
    private val requestSequence = AtomicLong(0)

    // Account reset operation state — only one can run at a time
    private var resetJob: Job? = null
    private val mutableResetResult = MutableStateFlow<AccountResetResult?>(null)
    private val mutableResetProgress = MutableStateFlow<ResetProgress>(ResetProgress.Idle)
    override val resetProgress: StateFlow<ResetProgress> = mutableResetProgress.asStateFlow()

    private val mutableState = MutableStateFlow(DiagnosticsState(dataSource = DataSourceMode.REAL))
    override val state: StateFlow<DiagnosticsState> = mutableState.asStateFlow()
    private val mutableAuthorization = MutableStateFlow(AuthorizationSession())
    override val authorization: StateFlow<AuthorizationSession> = mutableAuthorization.asStateFlow()
    private val mutableLibrary = MutableStateFlow<LibraryState>(LibraryState.Idle)
    override val library: StateFlow<LibraryState> = mutableLibrary.asStateFlow()
    private val mutableTransferUpdates = kotlinx.coroutines.flow.MutableSharedFlow<TransferUpdate>(extraBufferCapacity = 64)
    override val transferUpdates: kotlinx.coroutines.flow.Flow<TransferUpdate> = mutableTransferUpdates.asSharedFlow()
    private val mutableSavedMessageUpdates = kotlinx.coroutines.flow.MutableSharedFlow<SavedMessageUpdate>(extraBufferCapacity = 128)
    override val savedMessageUpdates: kotlinx.coroutines.flow.Flow<SavedMessageUpdate> = mutableSavedMessageUpdates.asSharedFlow()
    private val mutableFileUpdates = kotlinx.coroutines.flow.MutableSharedFlow<TdLibFileSnapshot>(extraBufferCapacity = 128)
    override val fileUpdates: kotlinx.coroutines.flow.Flow<TdLibFileSnapshot> = mutableFileUpdates.asSharedFlow()

    override fun start() {
        synchronized(lock) {
            if (lifecycle != GatewayLifecycle.NEW) return
            transitionLocked(GatewayLifecycle.STARTING)
            worker = scope.launch { initializeAndReceive() }
        }
    }

    private suspend fun initializeAndReceive() {
        try {
            libraryLoader.load()
            synchronized(lock) {
                if (lifecycle != GatewayLifecycle.STARTING) return
                mutableState.value = mutableState.value.copy(nativeLibraryLoaded = true)
                clientId = native.createClientId()
                countedClient = true
                mutableState.value = mutableState.value.copy(
                    clientCreated = true,
                    clientInstanceCount = instances.incrementAndGet()
                )
                transitionLocked(GatewayLifecycle.RUNNING)
            }
            sendRequest("verbosity", buildJsonObject {
                put("@type", "setLogVerbosityLevel"); put("new_verbosity_level", 0)
            })
            sendRequest("startup", buildJsonObject { put("@type", "getAuthorizationState") })

            // Receive loop: stays alive through CLOSING until authorizationStateClosed
            while (currentCoroutineContext().isActive) {
                val currentLifecycle = synchronized(lock) { lifecycle }
                if (currentLifecycle == GatewayLifecycle.CLOSED) break

                val response = native.receive(0.25)
                if (response == null) {
                    delay(1)
                    continue
                }
                handleResponse(response)
            }
        } catch (error: Throwable) {
            synchronized(lock) {
                if (lifecycle != GatewayLifecycle.CLOSING && lifecycle != GatewayLifecycle.CLOSED && lifecycle != GatewayLifecycle.ABORTED) {
                    transitionLocked(GatewayLifecycle.FAILED, safeMessage(error))
                    mutableAuthorization.value = AuthorizationSession(
                        state = AuthorizationState.Other("failed"),
                        safeError = safeMessage(error),
                    )
                }
            }
        }
    }

    private fun handleResponse(raw: String) {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { return }
        val type = root.string("@type") ?: return
        val extra = root.string("@extra")

        // Resolve any pending async execute() call
        if (extra != null) {
            val deferred = pendingRequests.remove(extra)
            if (deferred != null) {
                if (type == "error") {
                    val sanitized = SensitiveDataRedactor.redact(root.string("message") ?: "TDLib Error")
                    deferred.completeExceptionally(TdLibErrorException(sanitized))
                } else {
                    deferred.complete(root)
                }
            }
        }

        // Do not handle authorization/file updates after CLOSED or ABORTED
        val currentLifecycle = synchronized(lock) { lifecycle }
        if (currentLifecycle == GatewayLifecycle.CLOSED || currentLifecycle == GatewayLifecycle.ABORTED) return

        when (type) {
            "updateAuthorizationState" -> handleAuthorization(root.obj("authorization_state") ?: return)
            "authorizationStateWaitTdlibParameters", "authorizationStateWaitPhoneNumber",
            "authorizationStateWaitCode", "authorizationStateWaitPassword", "authorizationStateWaitEmailAddress",
            "authorizationStateWaitEmailCode", "authorizationStateWaitOtherDeviceConfirmation",
            "authorizationStateWaitRegistration", "authorizationStateWaitPremiumPurchase",
            "authorizationStateReady", "authorizationStateLoggingOut", "authorizationStateClosing",
            "authorizationStateClosed" -> handleAuthorization(root)
            "updateFile" -> root.obj("file")?.let { file ->
                observeFileSnapshot(file)
                handleFile(file)
            }
            "updateNewMessage" -> root.obj("message")?.let(::handleNewMessage)
            "updateMessageContent", "updateMessageEdited" -> handleMessageChanged(root)
            "updateDeleteMessages" -> handleDeletedMessages(root)
            "file" -> {
                val fileId = root.int("id")
                observeFileSnapshot(root)
                if (synchronized(lock) { pendingDownloadRequests[fileId] == root.string("@extra") }) handleFile(root)
            }
            "user" -> if (synchronized(lock) { pendingLibraryRequest == root.string("@extra") }) requestPrivateChat(root.long("id"))
            "chat" -> if (synchronized(lock) { pendingLibraryRequest == root.string("@extra") }) requestSavedHistory(root.long("id"))
            "messages" -> if (synchronized(lock) { pendingLibraryRequest == root.string("@extra") }) {
                synchronized(lock) { pendingLibraryRequest = null }
                handleMessages(root)
            }
            "ok" -> handleOk(root)
            "error" -> handleError(root)
        }
    }

    private fun handleNewMessage(message: JsonObject) {
        val mapped = MessageMapper.mapMessage(message) ?: return
        mutableSavedMessageUpdates.tryEmit(SavedMessageUpdate.Upsert(mapped.sourceId, mapped))
    }

    private fun handleMessageChanged(root: JsonObject) {
        val chatId = root.long("chat_id")
        val messageId = root.long("message_id")
        if (chatId == 0L || messageId == 0L) return
        scope.launch {
            val response = runCatching {
                execute(buildJsonObject {
                    put("@type", "getMessage")
                    put("chat_id", chatId)
                    put("message_id", messageId)
                })
            }.getOrNull() ?: return@launch
            if (response.string("@type") == "error") return@launch
            mutableSavedMessageUpdates.tryEmit(
                SavedMessageUpdate.Changed(chatId, messageId, MessageMapper.mapMessage(response))
            )
        }
    }

    private fun handleDeletedMessages(root: JsonObject) {
        val chatId = root.long("chat_id")
        if (chatId == 0L) return
        root["message_ids"]?.jsonArray.orEmpty().forEach { element ->
            element.jsonPrimitive.longOrNull?.takeIf { it != 0L }?.let { messageId ->
                mutableSavedMessageUpdates.tryEmit(SavedMessageUpdate.Deleted(chatId, messageId))
            }
        }
    }

    private fun handleAuthorization(value: JsonObject) {
        val snapshot = TdLibStateMapper.authorizationSnapshot(value.toString())
            ?: AuthorizationStateSnapshot(AuthorizationState.Other(value.string("@type") ?: "unknown"))
        val mapped = snapshot.state
        synchronized(lock) {
            if (authResetting && mapped !in setOf(
                    AuthorizationState.LoggingOut,
                    AuthorizationState.Closing,
                    AuthorizationState.Closed,
                )
            ) {
                return
            }
            pendingAuthAction = false
            pendingAuthRequest = null
            authActionTimeoutJob?.cancel()
            authActionTimeoutJob = null
            if (mapped !is AuthorizationState.WaitingForTdlibParameters) {
                parametersTimeoutJob?.cancel()
                parametersTimeoutJob = null
                pendingParametersRequest = null
                parameterAttempts = 0
            }
            mutableAuthorization.value = AuthorizationSession(
                state = mapped,
                codeInfo = snapshot.codeInfo,
                emailCodeInfo = snapshot.emailCodeInfo,
            )
            mutableState.value = mutableState.value.copy(authorizationState = mapped)
        }
        when (mapped) {
            AuthorizationState.WaitingForTdlibParameters -> sendTdlibParametersOrExposeMissingConfig()
            AuthorizationState.WaitingForPhoneNumber -> {
                invalidateFileSnapshotState()
                identityProvider?.clear()
            }
            AuthorizationState.Ready -> resolveAccountIdentity()
            // authorizationStateClosed — TDLib confirmed full close. Now finalize.
            AuthorizationState.Closed -> finalizeClose()
            else -> Unit
        }
    }

    private fun resolveAccountIdentity() {
        synchronized(lock) {
            if (resolveIdentityJob?.isActive == true) return
            resolveIdentityJob = scope.launch {
                val meResponse = execute(buildJsonObject { put("@type", "getMe") })
                if (meResponse.string("@type") != "error") {
                    val userId = meResponse.long("id")
                    if (userId != 0L) {
                        if (identityProvider?.currentIdentity?.value?.accountId?.let { it != userId } == true) {
                            invalidateFileSnapshotState()
                        }
                        identityProvider?.updateAccount(userId)
                    }
                }
            }
        }
    }


    /**
     * Called when TDLib sends authorizationStateClosed.
     * Only then do we: stop receive loop, clear clientId, decrement counter, complete pending requests.
     * This is the ONLY path to GatewayLifecycle.CLOSED.
     */
    private fun finalizeClose() {
        val cancelledRequests = mutableListOf<CompletableDeferred<JsonObject>>()
        synchronized(lock) {
            if (lifecycle == GatewayLifecycle.CLOSED) return
            transitionLocked(GatewayLifecycle.CLOSED)
            mutableAuthorization.value = AuthorizationSession(AuthorizationState.Closed)
            if (countedClient) {
                countedClient = false
                instances.updateAndGet { if (it > 0) it - 1 else 0 }
            }
            clientId = null
            invalidateFileSnapshotState()
            mutableState.value = mutableState.value.copy(
                clientCreated = false,
                clientInstanceCount = instances.get()
            )
            cancelledRequests.addAll(pendingRequests.values)
            pendingRequests.clear()
            pendingDownloads.clear()
            pendingCancellations.clear()
            pendingDownloadRequests.clear()
            pendingCancelRequests.clear()
            pendingAuthRequest = null
            authActionTimeoutJob?.cancel()
            authActionTimeoutJob = null
            pendingParametersRequest = null
            parametersTimeoutJob?.cancel()
            parametersTimeoutJob = null
            pendingLibraryRequest = null
        }
        // Complete pending requests — do this outside synchronized to avoid deadlock
        cancelledRequests.forEach {
            it.completeExceptionally(CancellationException("Gateway closed"))
        }
        // Stop the receive loop
        worker?.cancel()
        worker = null
        // The gateway owns all jobs in this scope; terminal TDLib close ends that ownership.
        scope.cancel()
    }

    private fun sendTdlibParametersOrExposeMissingConfig() {
        synchronized(lock) {
            if (pendingParametersRequest != null) return
            if (!configuration.configured) {
                val error = AuthorizationError(
                    kind = AuthorizationErrorKind.CONFIGURATION,
                    message = "Telegram API ID and hash are required in telegram-api.properties.",
                    retryable = false,
                )
                mutableAuthorization.value = AuthorizationSession(
                    state = AuthorizationState.MissingConfiguration,
                    safeError = error.message,
                    error = error,
                )
                mutableState.value = mutableState.value.copy(
                    authorizationState = AuthorizationState.MissingConfiguration,
                    safeError = error.message,
                )
                return
            }
            parameterAttempts += 1
        }
        val dbExists = databaseDirectory.exists() && (databaseDirectory.list()?.isNotEmpty() == true)
        val key = try {
            encryptionManager?.getOrGenerateKey(
                com.nmtuong.telegramdrive.security.DatabaseState(exists = dbExists)
            ) ?: ""
        } catch (error: DatabaseKeyException) {
            val authError = AuthorizationError(
                kind = AuthorizationErrorKind.DATABASE,
                message = error.message ?: "Telegram database encryption is unavailable. Account reset is required.",
                retryable = false,
            )
            synchronized(lock) { setAuthorizationErrorLocked(authError, AuthorizationState.Other("database")) }
            return
        }
        val request = requestEnvelope("parameters", buildJsonObject {
            put("@type", "setTdlibParameters")
            put("use_test_dc", false)
            put("database_directory", databaseDirectory.absolutePath)
            put("files_directory", filesDirectory.absolutePath)
            put("database_encryption_key", key)
            put("use_file_database", true)
            put("use_chat_info_database", true)
            put("use_message_database", true)
            put("use_secret_chats", false)
            put("api_id", configuration.apiId)
            put("api_hash", configuration.apiHash)
            put("system_language_code", "en")
            put("device_model", "Android")
            put("system_version", android.os.Build.VERSION.RELEASE)
            put("application_version", "1.0")
        })
        synchronized(lock) { pendingParametersRequest = request.string("@extra") }
        if (!sendOrFail(request)) {
            synchronized(lock) {
                if (pendingParametersRequest == request.string("@extra")) {
                    pendingParametersRequest = null
                    val error = AuthorizationError(
                        kind = AuthorizationErrorKind.INITIALIZATION,
                        message = "Telegram initialization could not start. Check the local configuration and retry.",
                    )
                    setAuthorizationErrorLocked(error, AuthorizationState.Other("initialization"))
                }
            }
            return
        }
        parametersTimeoutJob?.cancel()
        parametersTimeoutJob = scope.launch {
            delay(authActionTimeoutMs)
            synchronized(lock) {
                if (pendingParametersRequest != request.string("@extra")) return@synchronized
                pendingParametersRequest = null
                val error = AuthorizationError(
                    kind = AuthorizationErrorKind.NETWORK,
                    message = "Telegram initialization timed out. Check the network and retry.",
                )
                setAuthorizationErrorLocked(error, AuthorizationState.Other("initialization_timeout"))
            }
        }
    }

    private fun setAuthorizationErrorLocked(
        error: AuthorizationError,
        state: AuthorizationState = mutableAuthorization.value.state,
    ) {
        mutableAuthorization.value = mutableAuthorization.value.copy(
            state = state,
            actionPending = false,
            safeError = error.message,
            error = error,
        )
        mutableState.value = mutableState.value.copy(
            authorizationState = state,
            safeError = error.message,
        )
    }

    override fun submit(action: AuthorizationAction): ActionResult {
        if (action == AuthorizationAction.Reset) {
            // Reset is now handled by logoutAndReset(); keep backward compat but delegate
            scope.launch { logoutAndReset() }
            return ActionResult.ACCEPTED
        }

        val request = synchronized(lock) {
            if (!configuration.configured) return ActionResult.MISSING_CONFIGURATION
            if (pendingAuthAction) return ActionResult.DUPLICATE
            val current = mutableAuthorization.value.state
            val built = when (action) {
                is AuthorizationAction.SubmitPhone -> if (current == AuthorizationState.WaitingForPhoneNumber) buildJsonObject {
                    put("@type", "setAuthenticationPhoneNumber")
                    put("phone_number", action.phone)
                    put("settings", JsonNull)
                } else null
                is AuthorizationAction.SubmitCode -> if (current == AuthorizationState.WaitingForCode) request("checkAuthenticationCode", "code" to action.code) else null
                is AuthorizationAction.SubmitPassword -> if (current is AuthorizationState.WaitingForPassword) request("checkAuthenticationPassword", "password" to action.password) else null
                is AuthorizationAction.SubmitEmailAddress -> if (current == AuthorizationState.WaitingForEmailAddress) request("setAuthenticationEmailAddress", "email_address" to action.email) else null
                is AuthorizationAction.SubmitEmailCode -> if (current == AuthorizationState.WaitingForEmailCode) request("checkAuthenticationEmailCode", "code" to buildJsonObject { put("@type", "emailAddressAuthenticationCode"); put("code", action.code) }) else null
                AuthorizationAction.ResetEmailAddress -> if (current == AuthorizationState.WaitingForEmailCode && mutableAuthorization.value.emailCodeInfo?.canResetEmailAddress == true) buildJsonObject {
                    put("@type", "resetAuthenticationEmailAddress")
                } else null
                AuthorizationAction.RequestQrCode -> if (current == AuthorizationState.WaitingForPhoneNumber) buildJsonObject {
                    put("@type", "requestQrCodeAuthentication")
                    put("other_user_ids", buildJsonArray {})
                } else null
                is AuthorizationAction.ChangePhone -> if (canChangePhone(current)) buildJsonObject {
                    put("@type", "setAuthenticationPhoneNumber")
                    put("phone_number", action.phone)
                    put("settings", JsonNull)
                } else null
                AuthorizationAction.ResendCode -> when (current) {
                    AuthorizationState.WaitingForCode -> if (mutableAuthorization.value.codeInfo?.canResend != false) {
                        buildJsonObject { put("@type", "resendAuthenticationCode"); put("reason", JsonNull) }
                    } else null
                    AuthorizationState.WaitingForEmailCode -> buildJsonObject {
                        put("@type", "resendAuthenticationCode")
                        put("reason", JsonNull)
                    }
                    else -> null
                }
                is AuthorizationAction.SubmitRegistration -> if (
                    current is AuthorizationState.WaitingForRegistration &&
                        action.acceptedTerms &&
                        action.firstName.trim().isNotEmpty()
                ) buildJsonObject {
                    put("@type", "registerUser")
                    put("first_name", action.firstName.trim())
                    put("last_name", action.lastName.trim())
                    put("disable_notification", false)
                } else null
                AuthorizationAction.Logout -> if (current == AuthorizationState.Ready) buildJsonObject { put("@type", "logOut") } else null
                AuthorizationAction.Reset -> null // Delegated above
            } ?: return ActionResult.INVALID_STATE
            pendingAuthAction = true
            val enveloped = requestEnvelope("auth", built)
            pendingAuthRequest = enveloped.string("@extra")
            mutableAuthorization.value = mutableAuthorization.value.copy(actionPending = true, safeError = null)
            enveloped
        }
        val extra = request.string("@extra").orEmpty()
        if (!sendOrFail(request)) {
            synchronized(lock) {
                if (pendingAuthRequest == extra) {
                    pendingAuthAction = false
                    pendingAuthRequest = null
                    val error = AuthorizationError(
                        kind = AuthorizationErrorKind.NETWORK,
                        message = "Telegram did not accept the request. Check the network and retry.",
                    )
                    setAuthorizationErrorLocked(error)
                }
            }
            return ActionResult.INVALID_STATE
        }
        armAuthActionTimeout(extra)
        return ActionResult.ACCEPTED
    }

    private fun canChangePhone(state: AuthorizationState): Boolean = when (state) {
        AuthorizationState.WaitingForPhoneNumber,
        AuthorizationState.WaitingForCode,
        is AuthorizationState.WaitingForPassword,
        AuthorizationState.WaitingForEmailAddress,
        AuthorizationState.WaitingForEmailCode,
        is AuthorizationState.WaitingForOtherDevice,
        is AuthorizationState.WaitingForRegistration,
        is AuthorizationState.WaitingForPremiumPurchase -> true
        else -> false
    }

    private fun armAuthActionTimeout(extra: String) {
        authActionTimeoutJob?.cancel()
        authActionTimeoutJob = scope.launch {
            delay(authActionTimeoutMs)
            synchronized(lock) {
                if (pendingAuthRequest != extra) return@synchronized
                pendingAuthAction = false
                pendingAuthRequest = null
                val error = AuthorizationError(
                    kind = AuthorizationErrorKind.NETWORK,
                    message = "Telegram request timed out. Check the network and retry.",
                )
                setAuthorizationErrorLocked(error)
            }
        }
    }

    /**
     * Explicit asynchronous account reset with proper lifecycle semantics.
     *
     * Order:
     * 1. Atomically mark RESETTING (via existing lifecycle check).
     * 2. Cancel active downloads.
     * 3. Send TDLib logOut.
     * 4. Wait for authorizationStateClosed (network-dependent).
     * 5. Only after TDLib confirms Closed: delete local dirs, clear encryption key.
     * 6. Mark reset Completed.
     *
     * If logOut times out: returns Failed (recoverable). Local data is NOT deleted.
     */
    override suspend fun logoutAndReset(): AccountResetResult {
        val job = synchronized(lock) {
            if (lifecycle != GatewayLifecycle.RUNNING) {
                mutableResetProgress.value = ResetProgress.Failed("Gateway is not running", retryable = false)
                return AccountResetResult.InvalidState
            }
            if (resetJob?.isActive == true) return AccountResetResult.AlreadyRunning

            val launchedJob = scope.launch {
                try {
                    // Step 1: BlockingTransfers
                    mutableResetProgress.value = ResetProgress.BlockingTransfers

                    // Step 2: CancellingTransfers — capture active contexts and cancel
                    mutableResetProgress.value = ResetProgress.CancellingTransfers
                    val activeContexts = synchronized(lock) {
                        pendingTransferContexts.values.toList()
                    }
                    activeContexts.forEach { context ->
                        runCatching { cancel(context.identity) }
                    }

                    // Step 3: InvalidatingGeneration
                    mutableResetProgress.value = ResetProgress.InvalidatingGeneration
                    identityProvider?.invalidateGeneration()
                    synchronized(lock) { authResetting = true }

                    // Step 4: Clear tracking maps & tombstone old contexts
                    synchronized(lock) {
                        pendingTransferContexts.clear()
                        pendingDownloads.clear()
                        pendingCancellations.clear()
                        pendingDownloadRequests.clear()
                        pendingCancelRequests.clear()
                    }

                    // Step 5: LoggingOut (network required)
                    mutableResetProgress.value = ResetProgress.LoggingOut
                    val logoutResult = runCatching {
                        withTimeout(logoutTimeoutMs) {
                            execute(buildJsonObject { put("@type", "logOut") })
                        }
                    }

                    if (logoutResult.isFailure) {
                        val cause = logoutResult.exceptionOrNull()
                        val reason = when (cause) {
                            is TimeoutCancellationException -> "Logout timed out. Network may be unavailable."
                            else -> SensitiveDataRedactor.redact(cause?.message ?: "Logout failed")
                        }
                        // Do NOT delete local data on logout failure
                        mutableResetProgress.value = ResetProgress.Failed(reason)
                        mutableResetResult.value = AccountResetResult.Failed(reason)
                        return@launch
                    }

                    val response = logoutResult.getOrNull()
                    if (response?.string("@type") == "error") {
                        val err = SensitiveDataRedactor.redact(response.string("message") ?: "logOut error")
                        // Fail immediately on TDLib logOut error
                        mutableResetProgress.value = ResetProgress.Failed("Logout failed: $err")
                        mutableResetResult.value = AccountResetResult.Failed("Logout failed: $err")
                        return@launch
                    }

                    // Step 6: WaitingForClosed
                    mutableResetProgress.value = ResetProgress.WaitingForClosed
                    val closeResult = runCatching {
                        withTimeout(closeTimeoutMs) {
                            authorization.first { it.state == AuthorizationState.Closed }
                        }
                    }

                    if (closeResult.isFailure) {
                        val reason = "TDLib did not confirm close within timeout"
                        mutableResetProgress.value = ResetProgress.Failed(reason)
                        mutableResetResult.value = AccountResetResult.Failed(reason)
                        return@launch
                    }

                    // Step 7: DeletingDatabase & DeletingFiles
                    mutableResetProgress.value = ResetProgress.DeletingDatabase
                    val dbDeleted = if (databaseDirectory.exists()) databaseDirectory.deleteRecursively() else true

                    mutableResetProgress.value = ResetProgress.DeletingFiles
                    val filesDeleted = if (filesDirectory.exists()) filesDirectory.deleteRecursively() else true

                    if (!dbDeleted || !filesDeleted) {
                        val reason = "Local file deletion failed. Encryption key preserved."
                        mutableResetProgress.value = ResetProgress.Failed(reason)
                        mutableResetResult.value = AccountResetResult.Failed(reason)
                        return@launch
                    }

                    // Step 8: DeletingKey
                    mutableResetProgress.value = ResetProgress.DeletingKey
                    val keyCleared = runCatching {
                        encryptionManager?.clearKey()
                        true
                    }.getOrDefault(false)

                    if (!keyCleared) {
                        val reason = "Encryption key clear failed."
                        mutableResetProgress.value = ResetProgress.Failed(reason)
                        mutableResetResult.value = AccountResetResult.Failed(reason)
                        return@launch
                    }

                    // Step 9: ClearingIdentity
                    mutableResetProgress.value = ResetProgress.ClearingIdentity
                    identityProvider?.clear()

                    // Step 10: Completed
                    mutableResetProgress.value = ResetProgress.Completed
                    mutableResetResult.value = AccountResetResult.Completed

                } catch (e: CancellationException) {
                    mutableResetProgress.value = ResetProgress.Failed("Reset cancelled")
                    mutableResetResult.value = AccountResetResult.Cancelled
                    throw e
                } catch (e: Exception) {
                    val errMsg = SensitiveDataRedactor.redact(e.message ?: "Reset failed")
                    mutableResetProgress.value = ResetProgress.Failed(errMsg)
                    mutableResetResult.value = AccountResetResult.Failed(errMsg)
                } finally {
                    synchronized(lock) {
                        if (lifecycle != GatewayLifecycle.CLOSED) authResetting = false
                    }
                }
            }
            resetJob = launchedJob
            launchedJob
        }

        job.join()
        return mutableResetResult.value ?: AccountResetResult.Failed("Reset did not complete")
    }

    override fun loadSavedMessages(limit: Int): ActionResult {
        if (mutableAuthorization.value.state != AuthorizationState.Ready) return ActionResult.INVALID_STATE
        if (mutableLibrary.value == LibraryState.Loading) return ActionResult.DUPLICATE
        pendingHistoryLimit = limit.coerceIn(1, 50)
        mutableLibrary.value = LibraryState.Loading
        val request = requestEnvelope("getMe", buildJsonObject { put("@type", "getMe") })
        synchronized(lock) { pendingLibraryRequest = request.string("@extra") }
        send(request)
        return ActionResult.ACCEPTED
    }

    private fun requestPrivateChat(userId: Long) {
        val request = requestEnvelope("chat", buildJsonObject {
            put("@type", "createPrivateChat")
            put("user_id", userId)
            put("force", true)
        })
        synchronized(lock) { pendingLibraryRequest = request.string("@extra") }
        send(request)
    }

    private fun requestSavedHistory(chatId: Long) {
        val request = requestEnvelope("history", buildJsonObject {
            put("@type", "getChatHistory")
            put("chat_id", chatId)
            put("from_message_id", 0)
            put("offset", 0)
            put("limit", pendingHistoryLimit)
            put("only_local", false)
        })
        synchronized(lock) { pendingLibraryRequest = request.string("@extra") }
        send(request)
    }

    /**
     * Execute an arbitrary TDLib request and await its response.
     *
     * Contract:
     * - Register pending deferred BEFORE sending (prevents lost response race).
     * - Validate lifecycle atomically with registration.
     * - Cleanup in finally (timeout, cancellation, close).
     * - Raw TDLib errors are sanitized before propagating.
     * - Unknown @extra responses are silently ignored (do not mutate state).
     */
    suspend fun execute(request: JsonObject): JsonObject {
        val envelope = requestEnvelope(request.string("@type") ?: "unknown", request)
        val extra = envelope.string("@extra")
            ?: return buildJsonObject { put("@type", "error"); put("message", "Invalid request") }

        val deferred = CompletableDeferred<JsonObject>()

        // Atomically: validate lifecycle AND register deferred before sending
        val lifecycleOk = synchronized(lock) {
            if (lifecycle != GatewayLifecycle.RUNNING && lifecycle != GatewayLifecycle.STARTING) {
                false
            } else {
                pendingRequests[extra] = deferred
                true
            }
        }

        if (!lifecycleOk) {
            return buildJsonObject { put("@type", "error"); put("message", "Gateway is not running") }
        }

        // Verify clientId still valid (could be null if closed between lifecycle check and send)
        val id = synchronized(lock) { clientId }
        if (id == null) {
            pendingRequests.remove(extra)
            return buildJsonObject { put("@type", "error"); put("message", "No active client") }
        }

        native.send(id, envelope.toString())

        return try {
            withTimeout(15_000) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(extra)
            buildJsonObject { put("@type", "error"); put("message", "Request timed out") }
        } catch (e: CancellationException) {
            pendingRequests.remove(extra)
            throw e
        } catch (e: TdLibErrorException) {
            buildJsonObject { put("@type", "error"); put("message", e.message ?: "TDLib error") }
        } catch (e: Exception) {
            pendingRequests.remove(extra)
            buildJsonObject {
                put("@type", "error")
                put("message", SensitiveDataRedactor.redact(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Load a page of chat history. Returns a [HistoryPage] with:
     * - Mapped media items (text/unsupported messages are filtered).
     * - Raw last message ID as cursor (not mapped item ID).
     * - End-of-history signal when TDLib returns fewer messages than requested.
     *
     * Filters empty raw pages up to [EMPTY_PAGE_SCAN_LIMIT] iterations.
     *
     * TDLib rules:
     * - fromMessageId=0 for first page.
     * - limit must be in 1..100 (never exceed).
     * - Boundary message deduplication by raw message ID.
     */
    override suspend fun loadHistoryPage(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
    ): HistoryPage {
        val safeLimit = limit.coerceIn(1, 100)
        var cursor = fromMessageId
        var scanCount = 0

        while (scanCount < EMPTY_PAGE_SCAN_LIMIT) {
            val request = buildJsonObject {
                put("@type", "getChatHistory")
                put("chat_id", chatId)
                put("from_message_id", cursor)
                put("offset", 0)
                put("limit", safeLimit)
                put("only_local", false)
            }

            val response = try {
                execute(request)
            } catch (error: TdLibErrorException) {
                val message = error.message.orEmpty()
                return HistoryPage.error(safeNetworkMessage(message), isNetworkFailure(message))
            }
            if (response.string("@type") == "error") {
                val message = response.string("message") ?: "Unknown error"
                return HistoryPage.error(
                    safeNetworkMessage(message),
                    isNetworkFailure(message),
                )
            }

            val rawMessages = response["messages"]?.jsonArray.orEmpty()

            // End of history is true strictly when TDLib returns 0 raw messages
            val endOfHistory = rawMessages.isEmpty()

            // Get raw message IDs for cursor tracking (before filtering)
            val rawMessageIds = rawMessages.mapNotNull { it.jsonObject.long("id").takeIf { id -> id != 0L } }

            // Filter boundary message (same raw message ID as cursor)
            val filteredRaw = if (cursor != 0L) {
                rawMessages.filter { it.jsonObject.long("id") != cursor }
            } else {
                rawMessages.toList()
            }

            // Map to media items (drops text/unsupported)
            val items = MessageMapper.mapMessages(filteredRaw)

            // Raw cursor is the last raw message ID (not last mapped item)
            val rawLastMessageId = rawMessageIds.lastOrNull()

            // If cursor didn't advance, break out safely to prevent infinite loop
            if (rawLastMessageId == cursor && cursor != 0L) {
                return HistoryPage.error("Cursor failed to advance beyond $cursor")
            }

            if (items.isNotEmpty()) {
                return HistoryPage(
                    items = items,
                    rawLastMessageId = rawLastMessageId,
                    endOfHistory = endOfHistory,
                )
            }

            // Items empty but not end of history — continue scanning
            if (endOfHistory || rawLastMessageId == null) {
                return HistoryPage.empty()
            }

            cursor = rawLastMessageId
            scanCount++
        }

        // Safety bound reached — return empty with valid cursor to allow Paging to continue
        return HistoryPage(
            items = emptyList(),
            rawLastMessageId = cursor.takeIf { it != fromMessageId },
            endOfHistory = false,
        )
    }

    private fun handleMessages(root: JsonObject) {
        val items = root["messages"]?.jsonArray.orEmpty()
            .mapNotNull { MessageMapper.mapMessage(it.jsonObject) }
            .distinctBy { it.id } // Use message ID for dedup, not file ID
        mutableLibrary.value = if (items.isEmpty()) LibraryState.Empty else LibraryState.Content(items)
    }

    internal fun handleResponseForTest(raw: String) = handleResponse(raw)

    internal fun cachedFileSnapshotForTest(fileId: Int): TdLibFileSnapshot? = fileSnapshots[fileId]

    override fun download(request: TransferRequest): ActionResult {
        val fileId = request.fileId
        val activeIdentity = identityProvider?.currentIdentity?.value
        if (activeIdentity != null && (
                activeIdentity.accountId != request.identity.accountId ||
                    activeIdentity.databaseGeneration != request.identity.databaseGeneration
                )
        ) return ActionResult.INVALID_STATE
        if (mutableResetProgress.value != ResetProgress.Idle) {
            mutableTransferUpdates.tryEmit(
                TransferUpdate(
                    identity = request.identity,
                    state = TransferState.TransferFailed("Account reset in progress"),
                    safeError = "Account reset in progress",
                )
            )
            return ActionResult.INVALID_STATE
        }

        val currentLifecycle = synchronized(lock) { lifecycle }
        if (currentLifecycle != GatewayLifecycle.RUNNING) {
            mutableTransferUpdates.tryEmit(
                TransferUpdate(
                    identity = request.identity,
                    state = TransferState.TransferFailed("Gateway is not running"),
                    safeError = "Gateway is not running",
                )
            )
            return ActionResult.INVALID_STATE
        }

        if (request.identity.accountId == 0L) {
            mutableTransferUpdates.tryEmit(
                TransferUpdate(
                    identity = request.identity,
                    state = TransferState.TransferFailed("Invalid account identity (accountId is 0)"),
                    safeError = "Invalid account identity",
                )
            )
            return ActionResult.INVALID_STATE
        }

        val reqEnvelope = synchronized(lock) {
            if (fileId in pendingCancellations || pendingTransferContexts.containsKey(fileId)) return ActionResult.DUPLICATE
            val attemptSeq = attemptSequenceMap.computeIfAbsent(fileId) { AtomicLong(0) }
            val attemptId = attemptSeq.incrementAndGet()
            val operationId = TransferOperationId(request.identity.accountId, request.identity.databaseGeneration, fileId, attemptId)
            val envelope = requestEnvelope("download-$fileId", buildJsonObject {
                put("@type", "downloadFile")
                put("file_id", fileId)
                put("priority", 16)
                put("synchronous", false)
            })
            val extra = envelope.string("@extra").orEmpty()
            val context = PendingTransferContext(
                operationId = operationId,
                identity = request.identity,
                messageId = request.messageId,
                sourceId = request.sourceId,
                mediaKind = request.mediaKind,
                downloadExtra = extra,
                expectedSizeBytes = request.expectedSizeBytes,
                knownLocalPath = request.knownLocalPath,
            )
            pendingTransferContexts[fileId] = context
            pendingDownloads.add(fileId)
            pendingDownloadRequests[fileId] = extra
            deletedTemporaryFileIds.remove(fileId)
            staleFileIds.remove(fileId)
            allowedFileIds.add(fileId)
            envelope
        }

        val sent = sendOrFail(reqEnvelope)
        if (!sent) {
            synchronized(lock) {
                pendingTransferContexts.remove(fileId)
                pendingDownloads.remove(fileId)
                pendingDownloadRequests.remove(fileId)
            }
            mutableTransferUpdates.tryEmit(
                TransferUpdate(
                    identity = request.identity,
                    state = TransferState.TransferFailed("Failed to send TDLib request"),
                    safeError = "Failed to send TDLib request",
                )
            )
            return ActionResult.INVALID_STATE
        }

        return ActionResult.ACCEPTED
    }

    override fun download(fileId: Int): ActionResult {
        val identity = TransferIdentity(currentAccountId(), currentDatabaseGeneration(), fileId)
        return download(TransferRequest(identity, fileId.toLong(), 0L, fileId, MediaKind.DOCUMENT))
    }

    override fun downloadPagingItem(fileId: Int): ActionResult {
        return download(fileId)
    }

    override fun cancel(identity: TransferIdentity): ActionResult {
        val fileId = identity.fileId
        val reqEnvelope = synchronized(lock) {
            val context = pendingTransferContexts[fileId] ?: return ActionResult.INVALID_STATE
            if (context.identity != identity) return ActionResult.INVALID_STATE
            if (!pendingCancellations.add(fileId)) return ActionResult.INVALID_STATE

            val envelope = requestEnvelope("cancel-$fileId", buildJsonObject {
                put("@type", "cancelDownloadFile")
                put("file_id", fileId)
                put("only_if_pending", false)
            })
            val extra = envelope.string("@extra").orEmpty()
            context.cancelExtra = extra
            pendingCancelRequests[fileId] = extra
            envelope
        }

        val sent = sendOrFail(reqEnvelope)
        if (!sent) {
            synchronized(lock) {
                pendingCancellations.remove(fileId)
                pendingCancelRequests.remove(fileId)
            }
            return ActionResult.INVALID_STATE
        }

        return ActionResult.ACCEPTED
    }

    override fun cancelDownload(fileId: Int): ActionResult {
        val identity = TransferIdentity(currentAccountId(), currentDatabaseGeneration(), fileId)
        return cancel(identity)
    }

    private fun observeFileSnapshot(file: JsonObject) {
        val fileId = file.int("id")
        if (fileId == 0) return
        if (deletedTemporaryFileIds.contains(fileId) || staleFileIds.contains(fileId)) return
        if (fileObservationBlocked && !allowedFileIds.contains(fileId)) return
        val local = file.obj("local")
        val path = local?.string("path")?.takeIf { it.isNotBlank() }
        val expected = local?.long("expected_size")?.takeIf { it > 0L }
            ?: file.long("expected_size").takeIf { it > 0L }
            ?: file.long("size")
        val downloaded = local?.long("downloaded_size") ?: 0L
        // A zero downloaded_prefix_size is meaningful: TDLib may have a
        // downloaded range at a non-zero offset while byte zero is absent.
        // Falling back to downloaded_size would make a seek range look like a
        // contiguous prefix and could expose sparse/unavailable bytes to Media3.
        val prefix = local?.long("downloaded_prefix_size") ?: 0L
        val offset = local?.long("download_offset") ?: 0L
        val complete = local?.bool("is_downloading_completed") == true
        val snapshot = TdLibFileSnapshot(
            fileId = fileId,
            stableFileIdentity = file.obj("remote")?.string("unique_id")?.takeIf { it.isNotBlank() }
                ?.let { "remote-unique:$it" }
                ?: file.obj("remote")?.string("id")?.takeIf { it.isNotBlank() }?.let { "remote:$it" },
            localPath = path,
            expectedSizeBytes = expected,
            downloadedSizeBytes = downloaded,
            downloadedPrefixSizeBytes = prefix,
            downloadOffsetBytes = offset,
            isDownloadingCompleted = complete,
            isReadable = path != null && File(path).isFile && (complete || downloaded > 0L),
        )
        fileSnapshots[fileId] = snapshot
        mutableFileUpdates.tryEmit(snapshot)
    }

    private fun invalidateFileSnapshotState() {
        staleFileIds.addAll(fileSnapshots.keys)
        allowedFileIds.clear()
        fileObservationBlocked = true
        fileSnapshots.clear()
        deletedTemporaryFileIds.clear()
    }

    private fun handleFile(file: JsonObject) {
        val fileId = file.int("id")
        val context = synchronized(lock) { pendingTransferContexts[fileId] } ?: return
        if (synchronized(lock) { fileId in pendingCancellations }) {
            updateItem(fileId) { it.copy(downloadState = DownloadState.Canceled, localPath = null) }
            return
        }
        val local = file.obj("local") ?: return
        val complete = local.bool("is_downloading_completed")
        val total = file.long("expected_size").takeIf { it > 0 } ?: file.long("size")
        val downloaded = local.long("downloaded_size")
        val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        val path = local.string("path").orEmpty()

        if (complete) {
            val isValidFile = path.isNotBlank() && File(path).isFile
            synchronized(lock) {
                pendingTransferContexts.remove(fileId)
                pendingDownloads.remove(fileId)
                pendingDownloadRequests.remove(fileId)
            }

            val state = if (isValidFile) {
                TransferState.Completed(path)
            } else {
                TransferState.TransferFailed("Downloaded file path missing or invalid")
            }

            mutableTransferUpdates.tryEmit(
                TransferUpdate(
                    identity = context.identity,
                    state = state,
                    percent = if (isValidFile) 100 else percent,
                    localPath = path.takeIf { isValidFile },
                    safeError = (state as? TransferState.TransferFailed)?.reason,
                    attemptId = context.operationId.attemptId,
                )
            )

            updateItem(fileId) {
                if (isValidFile) it.copy(downloadState = DownloadState.Complete, localPath = path)
                else it.copy(downloadState = DownloadState.Failed("Downloaded file path missing or invalid"), localPath = null)
            }
        } else {
            val state = TransferState.InProgress(percent)
            mutableTransferUpdates.tryEmit(
                TransferUpdate(
                    identity = context.identity,
                    state = state,
                    percent = percent,
                    attemptId = context.operationId.attemptId,
                )
            )
            updateItem(fileId) {
                it.copy(downloadState = DownloadState.Downloading(percent))
            }
        }
    }

    override fun preview(itemId: Long): PreviewTarget? {
        val item = (mutableLibrary.value as? LibraryState.Content)?.items?.firstOrNull { it.id == itemId } ?: return null
        val path = item.localPath?.takeIf { File(it).isFile } ?: return null
        val mimeType = item.mimeType?.takeIf { it.isNotBlank() } ?: mimeTypeForName(path)
        return when (item.kind) {
            MediaKind.IMAGE -> PreviewTarget.Image(item.id, path)
            MediaKind.VIDEO -> PreviewTarget.Video(item.id, path)
            MediaKind.ANIMATION -> PreviewTarget.Animation(item.id, path, mimeType)
            MediaKind.AUDIO -> PreviewTarget.Audio(item.id, path, mimeType)
            MediaKind.PDF -> PreviewTarget.Pdf(item.id, path)
            MediaKind.DOCUMENT -> if (mimeType?.startsWith("text/") == true || path.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS) {
                PreviewTarget.Text(item.id, path, mimeType)
            } else {
                PreviewTarget.External(item.id, path, mimeType)
            }
        }
    }

    override suspend fun getSavedMessagesChatId(): Long? {
        val meRequest = buildJsonObject { put("@type", "getMe") }
        val meResponse = execute(meRequest)
        if (meResponse.string("@type") == "error") return null
        val userId = meResponse.long("id")
        if (userId == 0L) return null

        val chatRequest = buildJsonObject {
            put("@type", "createPrivateChat")
            put("user_id", userId)
            put("force", true)
        }
        val chatResponse = execute(chatRequest)
        if (chatResponse.string("@type") == "error") return null
        val chatId = chatResponse.long("id")
        return if (chatId == 0L) null else chatId
    }

    override suspend fun getSavedMessagesHead(chatId: Long): Long? {
        val response = execute(buildJsonObject {
            put("@type", "getChatHistory")
            put("chat_id", chatId)
            put("from_message_id", 0L)
            put("offset", 0)
            put("limit", 1)
            put("only_local", false)
        })
        if (response.string("@type") == "error") return null
        return response["messages"]?.jsonArray?.firstOrNull()?.jsonObject?.long("id")?.takeIf { it != 0L }
    }

    override suspend fun getFileSnapshot(fileId: Int): TdLibFileSnapshot? {
        if (deletedTemporaryFileIds.contains(fileId) || staleFileIds.contains(fileId)) return null
        allowedFileIds.add(fileId)
        fileSnapshots[fileId]?.let { return it }
        val response = runCatching {
            execute(buildJsonObject {
                put("@type", "getFile")
                put("file_id", fileId)
            })
        }.getOrNull() ?: return null
        if (response.string("@type") == "error") return null
        observeFileSnapshot(response)
        return fileSnapshots[fileId]
    }

    override fun requestFileRange(fileId: Int, offsetBytes: Long, limitBytes: Long, priority: Int): ActionResult {
        if (synchronized(lock) { lifecycle } != GatewayLifecycle.RUNNING) return ActionResult.INVALID_STATE
        deletedTemporaryFileIds.remove(fileId)
        staleFileIds.remove(fileId)
        allowedFileIds.add(fileId)
        val request = requestEnvelope("range-$fileId", buildJsonObject {
            put("@type", "downloadFile")
            put("file_id", fileId)
            put("priority", priority.coerceIn(1, 32))
            put("offset", offsetBytes.coerceAtLeast(0L))
            put("limit", limitBytes.coerceAtLeast(0L))
            put("synchronous", false)
        })
        return if (sendOrFail(request)) ActionResult.ACCEPTED else ActionResult.INVALID_STATE
    }

    override fun cancelFileRange(fileId: Int): ActionResult {
        if (synchronized(lock) { lifecycle } != GatewayLifecycle.RUNNING) return ActionResult.INVALID_STATE
        val request = requestEnvelope("range-cancel-$fileId", buildJsonObject {
            put("@type", "cancelDownloadFile")
            put("file_id", fileId)
            put("only_if_pending", false)
        })
        return if (sendOrFail(request)) ActionResult.ACCEPTED else ActionResult.INVALID_STATE
    }

    override fun deleteTemporaryFile(fileId: Int): ActionResult {
        if (synchronized(lock) { lifecycle } != GatewayLifecycle.RUNNING) return ActionResult.INVALID_STATE
        deletedTemporaryFileIds.add(fileId)
        allowedFileIds.remove(fileId)
        staleFileIds.add(fileId)
        fileSnapshots.remove(fileId)
        val request = requestEnvelope("delete-file-$fileId", buildJsonObject {
            put("@type", "deleteFile")
            put("file_id", fileId)
        })
        return if (sendOrFail(request)) ActionResult.ACCEPTED else ActionResult.INVALID_STATE
    }

    override suspend fun getAvailableSources(): List<FileSource> {
        val meResponse = runCatching { execute(buildJsonObject { put("@type", "getMe") }) }.getOrElse {
            throw IllegalStateException(safeNetworkMessage(it.message.orEmpty()))
        }
        if (meResponse.string("@type") == "error") {
            throw IllegalStateException(safeNetworkMessage(meResponse.string("message").orEmpty()))
        }
        val selfUserId = meResponse.long("id")
        if (selfUserId == 0L) throw IllegalStateException("Telegram account is not available")

        val selfChat = runCatching {
            execute(buildJsonObject {
                put("@type", "createPrivateChat")
                put("user_id", selfUserId)
                put("force", true)
            })
        }.getOrNull()
        val savedChatId = selfChat?.long("id")?.takeIf { it != 0L }
            ?: throw IllegalStateException("Saved Messages is not available")
        val sources = linkedMapOf(savedChatId to FileSource(savedChatId, "Saved Messages", true))

        val chatsResponse = runCatching {
            execute(buildJsonObject {
                put("@type", "getChats")
                put("chat_list", buildJsonObject { put("@type", "chatListMain") })
                put("limit", SOURCE_CHAT_LIMIT)
            })
        }.getOrNull()
        val chatIds = chatsResponse?.get("chat_ids")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.longOrNull }
            .filter { it != savedChatId }
            .distinct()

        for (chatId in chatIds) {
            val chat = runCatching {
                execute(buildJsonObject {
                    put("@type", "getChat")
                    put("chat_id", chatId)
                })
            }.getOrNull() ?: continue
            if (chat.string("@type") == "error") continue
            val type = chat.obj("type") ?: continue
            val typeName = type.string("@type") ?: continue
            if (typeName !in ELIGIBLE_SOURCE_CHAT_TYPES) continue
            val title = chat.string("title")?.takeIf { it.isNotBlank() } ?: "Telegram chat"
            sources.putIfAbsent(chatId, FileSource(chatId, title, savedMessages = false))
        }
        return sources.values.toList()
    }

    private fun updateItem(fileId: Int, transform: (MediaItem) -> MediaItem) {
        val content = mutableLibrary.value as? LibraryState.Content ?: return
        mutableLibrary.value = LibraryState.Content(content.items.map { if (it.fileId == fileId) transform(it) else it })
    }

    private fun handleOk(root: JsonObject) {
        val extra = root.string("@extra").orEmpty()
        val request = extra.substringBefore(':')
        if (request == "auth" && synchronized(lock) { pendingAuthRequest == extra }) {
            synchronized(lock) {
                authActionTimeoutJob?.cancel()
                authActionTimeoutJob = null
                pendingAuthAction = false
                pendingAuthRequest = null
                mutableAuthorization.value = mutableAuthorization.value.copy(actionPending = false)
            }
            return
        }
        if (!request.startsWith("cancel-")) return
        request.removePrefix("cancel-").toIntOrNull()?.let { fileId ->
            val context = synchronized(lock) {
                if (pendingCancelRequests[fileId] != extra) return
                pendingCancelRequests.remove(fileId)
                pendingDownloadRequests.remove(fileId)
                pendingCancellations.remove(fileId)
                pendingDownloads.remove(fileId)
                pendingTransferContexts.remove(fileId)
            } ?: return
            mutableTransferUpdates.tryEmit(
                TransferUpdate(
                    identity = context.identity,
                    state = TransferState.TransferCancelled,
                    attemptId = context.operationId.attemptId,
                )
            )
            updateItem(fileId) { it.copy(downloadState = DownloadState.Canceled, localPath = null) }
        }
    }

    private fun handleError(root: JsonObject) {
        val rawMessage = root.string("message")
        val message = SensitiveDataRedactor.redact(rawMessage ?: "Telegram request failed")
        val code = root.int("code")
        val extra = root.string("@extra").orEmpty()
        val request = extra.substringBefore(':')
        when {
            request == "auth" && synchronized(lock) { pendingAuthRequest == extra } -> synchronized(lock) {
                pendingAuthAction = false
                pendingAuthRequest = null
                authActionTimeoutJob?.cancel()
                authActionTimeoutJob = null
                val error = classifyAuthError(
                    request = request,
                    code = code,
                    rawMessage = rawMessage,
                    currentState = mutableAuthorization.value.state,
                )
                setAuthorizationErrorLocked(error)
            }
            (request == "getMe" || request == "chat" || request == "history") && synchronized(lock) { pendingLibraryRequest == extra } -> {
                synchronized(lock) { pendingLibraryRequest = null }
                mutableLibrary.value = LibraryState.Error(safeNetworkMessage(rawMessage ?: "Telegram request failed"))
            }
            request == "parameters" && synchronized(lock) { pendingParametersRequest == extra } -> {
                val retry = synchronized(lock) {
                    pendingParametersRequest = null
                    parametersTimeoutJob?.cancel()
                    parametersTimeoutJob = null
                    isRetryableParameterError(code, rawMessage) && parameterAttempts < MAX_PARAMETER_ATTEMPTS
                }
                if (retry) {
                    sendTdlibParametersOrExposeMissingConfig()
                } else {
                    val error = classifyAuthError(
                        request = request,
                        code = code,
                        rawMessage = rawMessage,
                        currentState = mutableAuthorization.value.state,
                    )
                    synchronized(lock) {
                        setAuthorizationErrorLocked(
                            error,
                            if (error.kind == AuthorizationErrorKind.CONFIGURATION) {
                                AuthorizationState.MissingConfiguration
                            } else {
                                AuthorizationState.Other("initialization")
                            },
                        )
                    }
                }
            }
            request.startsWith("download-") -> request.removePrefix("download-").toIntOrNull()?.let { fileId ->
                val context = synchronized(lock) {
                    if (pendingDownloadRequests[fileId] != extra) return@let
                    pendingDownloadRequests.remove(fileId)
                    pendingDownloads.remove(fileId)
                    pendingTransferContexts.remove(fileId)
                } ?: return@let

                mutableTransferUpdates.tryEmit(
                    TransferUpdate(
                        identity = context.identity,
                        state = TransferState.TransferFailed(message),
                        safeError = message,
                        attemptId = context.operationId.attemptId,
                    )
                )
                updateItem(fileId) { it.copy(downloadState = DownloadState.Failed(message), localPath = null) }
            }
            request.startsWith("cancel-") -> request.removePrefix("cancel-").toIntOrNull()?.let { fileId ->
                val context = synchronized(lock) {
                    if (pendingCancelRequests[fileId] != extra) return@let
                    pendingCancelRequests.remove(fileId)
                    pendingDownloadRequests.remove(fileId)
                    pendingCancellations.remove(fileId)
                    pendingDownloads.remove(fileId)
                    pendingTransferContexts.remove(fileId)
                } ?: return@let

                mutableTransferUpdates.tryEmit(
                    TransferUpdate(
                        identity = context.identity,
                        state = TransferState.TransferFailed("Cancel failed: $message"),
                        safeError = message,
                        attemptId = context.operationId.attemptId,
                    )
                )
                updateItem(fileId) { it.copy(downloadState = DownloadState.Failed(message), localPath = null) }
            }
        }
    }

    private fun isRetryableParameterError(code: Int, message: String?): Boolean {
        val upper = message.orEmpty().uppercase()
        return code >= 500 || upper.contains("AUTH_RESTART") || upper.contains("TIMEOUT")
    }

    private fun classifyAuthError(
        request: String,
        code: Int,
        rawMessage: String?,
        currentState: AuthorizationState,
    ): AuthorizationError {
        val upper = rawMessage.orEmpty().uppercase()
        return when {
            code == 406 -> AuthorizationError(
                AuthorizationErrorKind.INTERNAL,
                "Telegram rejected this request. Try again or reset the sign-in flow.",
                retryable = false,
            )
            upper.contains("API_ID_INVALID") || upper.contains("API_HASH_INVALID") -> AuthorizationError(
                AuthorizationErrorKind.CONFIGURATION,
                "Telegram API ID or hash is invalid. Update telegram-api.properties.",
                retryable = false,
            )
            upper.contains("FLOOD_WAIT") -> AuthorizationError(
                AuthorizationErrorKind.FLOOD_WAIT,
                "Telegram asked you to wait before trying again.",
            )
            upper.contains("PHONE_NUMBER_INVALID") -> AuthorizationError(
                AuthorizationErrorKind.INVALID_PHONE,
                "That phone number is not valid. Enter it in international format.",
            )
            upper.contains("PHONE_CODE_EXPIRED") -> AuthorizationError(
                AuthorizationErrorKind.CODE_EXPIRED,
                "That authentication code expired. Request a new code.",
            )
            upper.contains("PHONE_CODE_INVALID") || upper.contains("CODE_INVALID") -> AuthorizationError(
                if (currentState == AuthorizationState.WaitingForEmailCode) AuthorizationErrorKind.EMAIL_CODE_INVALID else AuthorizationErrorKind.CODE_INVALID,
                "That authentication code is not valid. Check it and try again.",
            )
            upper.contains("PASSWORD_HASH_INVALID") || upper.contains("PASSWORD_INVALID") -> AuthorizationError(
                AuthorizationErrorKind.PASSWORD_INVALID,
                "That two-step verification password is not valid.",
            )
            currentState is AuthorizationState.WaitingForRegistration -> AuthorizationError(
                AuthorizationErrorKind.REGISTRATION_INVALID,
                "Telegram rejected the registration details. Check the names and try again.",
            )
            request == "parameters" -> AuthorizationError(
                AuthorizationErrorKind.INITIALIZATION,
                "Telegram initialization failed. Check the API configuration and network.",
            )
            upper.contains("NETWORK") || upper.contains("TIMEOUT") || code >= 500 -> AuthorizationError(
                AuthorizationErrorKind.NETWORK,
                "Telegram is temporarily unavailable. Check the network and retry.",
            )
            else -> AuthorizationError(
                AuthorizationErrorKind.INTERNAL,
                "Telegram sign-in failed. Check the details and retry.",
            )
        }
    }

    private fun sendRequest(prefix: String, body: JsonObject) {
        send(requestEnvelope(prefix, body))
    }

    private fun requestEnvelope(prefix: String, body: JsonObject): JsonObject =
        JsonObject(body + ("@extra" to JsonPrimitive("$prefix:${requestSequence.incrementAndGet()}")))

    private fun sendOrFail(body: JsonObject): Boolean {
        val id = synchronized(lock) { clientId } ?: return false
        return runCatching { native.send(id, body.toString()); true }.getOrDefault(false)
    }

    private fun send(body: JsonObject) {
        sendOrFail(body)
    }

    /**
     * Best-effort local resource detachment when TDLib close times out or fails natively.
     * Transitions state to ABORTED (never CLOSED), records diagnostics, and releases local holders.
     */
    private fun abandonClientLocalResources(reason: String) {
        val cancelledRequests = mutableListOf<CompletableDeferred<JsonObject>>()
        synchronized(lock) {
            if (lifecycle == GatewayLifecycle.CLOSED || lifecycle == GatewayLifecycle.ABORTED) return
            transitionLocked(GatewayLifecycle.ABORTED, reason)
            mutableAuthorization.value = AuthorizationSession(
                state = AuthorizationState.Other("aborted_timeout"),
                safeError = SensitiveDataRedactor.redact(reason),
            )
            if (countedClient) {
                countedClient = false
                instances.updateAndGet { if (it > 0) it - 1 else 0 }
            }
            clientId = null
            mutableState.value = mutableState.value.copy(
                clientCreated = false,
                clientInstanceCount = instances.get(),
            )
            cancelledRequests.addAll(pendingRequests.values)
            pendingRequests.clear()
            pendingDownloads.clear()
            pendingCancellations.clear()
            pendingDownloadRequests.clear()
            pendingCancelRequests.clear()
            pendingAuthRequest = null
            authActionTimeoutJob?.cancel()
            authActionTimeoutJob = null
            pendingParametersRequest = null
            parametersTimeoutJob?.cancel()
            parametersTimeoutJob = null
            pendingLibraryRequest = null
        }
        cancelledRequests.forEach {
            it.completeExceptionally(CancellationException("Gateway aborted: $reason"))
        }
        worker?.cancel()
        worker = null
    }

    /**
     * Initiates gateway close:
     * 1. Transition to CLOSING (idempotent — second call returns immediately).
     * 2. Send TDLib close request.
     * 3. Receive loop continues in CLOSING state, waiting for authorizationStateClosed.
     * 4. [finalizeClose] is called ONLY when TDLib sends the terminal state.
     * 5. If timeout occurs, [abandonClientLocalResources] transitions state to ABORTED (not CLOSED).
     */
    override fun close() {
        synchronized(lock) {
            if (lifecycle == GatewayLifecycle.CLOSING || lifecycle == GatewayLifecycle.CLOSED || lifecycle == GatewayLifecycle.ABORTED) return
            if (lifecycle == GatewayLifecycle.NEW || lifecycle == GatewayLifecycle.FAILED) {
                // Never started or failed before client created — go straight to CLOSED
                transitionLocked(GatewayLifecycle.CLOSED)
                mutableAuthorization.value = AuthorizationSession(AuthorizationState.Closed)
                return
            }
            transitionLocked(GatewayLifecycle.CLOSING)
            mutableAuthorization.value = AuthorizationSession(AuthorizationState.Closing)
        }

        val id = synchronized(lock) { clientId }
        if (id != null) {
            scope.launch {
                val sendResult = runCatching { native.send(id, "{\"@type\":\"close\"}") }
                if (sendResult.isFailure) {
                    abandonClientLocalResources("Native send close failed: ${sendResult.exceptionOrNull()?.message}")
                    scope.cancel()
                    return@launch
                }

                val completed = withTimeoutOrNull(closeTimeoutMs) {
                    while (synchronized(lock) { lifecycle != GatewayLifecycle.CLOSED }) {
                        delay(50)
                    }
                    true
                }

                if (completed == null) {
                    // Timeout: transition to ABORTED (do NOT call finalizeClose or claim CLOSED)
                    abandonClientLocalResources("TDLib close timed out after ${closeTimeoutMs}ms")
                }
                scope.cancel()
            }
        } else {
            finalizeClose()
            scope.cancel()
        }
    }

    private fun transitionLocked(next: GatewayLifecycle, error: String? = null) {
        lifecycle = next
        mutableState.value = mutableState.value.copy(lifecycle = next, safeError = error)
    }

    private fun safeMessage(error: Throwable) =
        SensitiveDataRedactor.redact("${error::class.java.simpleName}: TDLib initialization failed")

    internal companion object {
        private val instances = AtomicInteger(0)
        fun activeClientCountForTest(): Int = instances.get()
        fun resetClientCountForTest() = instances.set(0)
    }
}

/** Signals a sanitized TDLib error (message is already redacted). */
internal class TdLibErrorException(message: String) : Exception(message)

private fun request(type: String, pair: Pair<String, Any>): JsonObject = buildJsonObject {
    put("@type", type)
    when (val value = pair.second) {
        is String -> put(pair.first, value)
        is JsonElement -> put(pair.first, value)
    }
}
