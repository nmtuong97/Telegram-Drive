package com.nmtuong.telegramdrive.telegram

import android.content.Context
import com.nmtuong.telegramdrive.domain.*
import com.nmtuong.telegramdrive.security.SensitiveDataRedactor
import com.nmtuong.telegramdrive.security.TelegramApiConfiguration
import java.io.File
import com.nmtuong.telegramdrive.security.DatabaseEncryptionManager
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

/**
 * Safety limit for filtered empty page scanning.
 * Prevents infinite scan when all history is text-only content.
 */
private const val EMPTY_PAGE_SCAN_LIMIT = 10

class TdLibJsonGateway internal constructor(
    context: Context? = null,
    private val configuration: TelegramApiConfiguration = TelegramApiConfiguration(0, ""),
    private val native: TdLibNative = JsonTdLibNative,
    private val libraryLoader: NativeLibraryLoader = NativeLibraryLoader { System.loadLibrary("tdjsonjava") },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val closeTimeoutMs: Long = CLOSE_TIMEOUT_MS,
    private val logoutTimeoutMs: Long = LOGOUT_TIMEOUT_MS,
    /** CP7: Provides current account identity; injected to remove hardcoded (1L,1L). */
    private val currentAccountId: () -> Long = { 0L },
    private val currentDatabaseGeneration: () -> Long = { 1L },
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
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    // Legacy fields kept for backward compatibility
    private var pendingParametersRequest: String? = null
    private var pendingLibraryRequest: String? = null
    private var pendingHistoryLimit = 50
    private val pendingDownloads = mutableSetOf<Int>()
    private val pendingCancellations = mutableSetOf<Int>()
    private val pendingDownloadRequests = mutableMapOf<Int, String>()
    private val pendingCancelRequests = mutableMapOf<Int, String>()
    private val requestSequence = AtomicLong(0)

    // Account reset operation state — only one can run at a time
    private var resetJob: Job? = null
    private val mutableResetResult = MutableStateFlow<AccountResetResult?>(null)

    private val mutableState = MutableStateFlow(DiagnosticsState(dataSource = DataSourceMode.REAL))
    override val state: StateFlow<DiagnosticsState> = mutableState.asStateFlow()
    private val mutableAuthorization = MutableStateFlow(AuthorizationSession())
    override val authorization: StateFlow<AuthorizationSession> = mutableAuthorization.asStateFlow()
    private val mutableLibrary = MutableStateFlow<LibraryState>(LibraryState.Idle)
    override val library: StateFlow<LibraryState> = mutableLibrary.asStateFlow()
    private val mutableTransferUpdates = kotlinx.coroutines.flow.MutableSharedFlow<TransferUpdate>(extraBufferCapacity = 64)
    override val transferUpdates: kotlinx.coroutines.flow.Flow<TransferUpdate> = mutableTransferUpdates.asSharedFlow()

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
            "authorizationStateReady", "authorizationStateLoggingOut", "authorizationStateClosing",
            "authorizationStateClosed" -> handleAuthorization(root)
            "updateFile" -> root.obj("file")?.let(::handleFile)
            "file" -> {
                val fileId = root.int("id")
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

    private fun handleAuthorization(value: JsonObject) {
        val mapped = TdLibStateMapper.authorizationState(value.toString())
            ?: AuthorizationState.Other(value.string("@type") ?: "unknown")
        synchronized(lock) {
            pendingAuthAction = false
            pendingAuthRequest = null
            mutableAuthorization.value = AuthorizationSession(state = mapped)
            mutableState.value = mutableState.value.copy(authorizationState = mapped)
        }
        when (mapped) {
            AuthorizationState.WaitingForTdlibParameters -> sendTdlibParametersOrExposeMissingConfig()
            AuthorizationState.Ready -> Unit
            // authorizationStateClosed — TDLib confirmed full close. Now finalize.
            AuthorizationState.Closed -> finalizeClose()
            else -> Unit
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
            pendingParametersRequest = null
            pendingLibraryRequest = null
        }
        // Complete pending requests — do this outside synchronized to avoid deadlock
        cancelledRequests.forEach {
            it.completeExceptionally(CancellationException("Gateway closed"))
        }
        // Stop the receive loop
        worker?.cancel()
        worker = null
    }

    private fun sendTdlibParametersOrExposeMissingConfig() {
        if (!configuration.configured) {
            mutableAuthorization.value = AuthorizationSession(state = AuthorizationState.MissingConfiguration)
            return
        }
        val dbExists = databaseDirectory.exists() && (databaseDirectory.list()?.isNotEmpty() == true)
        val key = encryptionManager?.getOrGenerateKey(
            com.nmtuong.telegramdrive.security.DatabaseState(exists = dbExists)
        ) ?: ""
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
        send(request)
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
                AuthorizationAction.Logout -> if (current == AuthorizationState.Ready) buildJsonObject { put("@type", "logOut") } else null
                AuthorizationAction.Reset -> null // Delegated above
            } ?: return ActionResult.INVALID_STATE
            pendingAuthAction = true
            val enveloped = requestEnvelope("auth", built)
            pendingAuthRequest = enveloped.string("@extra")
            mutableAuthorization.value = mutableAuthorization.value.copy(actionPending = true, safeError = null)
            enveloped
        }
        send(request)
        return ActionResult.ACCEPTED
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
                return AccountResetResult.InvalidState
            }
            if (resetJob?.isActive == true) return AccountResetResult.AlreadyRunning

            val launchedJob = scope.launch {
                try {
                    // Step 1: Cancel active TDLib downloads
                    val activeFileIds = synchronized(lock) {
                        val ids = pendingDownloads.toList()
                        pendingDownloads.clear()
                        pendingCancellations.clear()
                        pendingDownloadRequests.clear()
                        pendingCancelRequests.clear()
                        ids
                    }
                    activeFileIds.forEach { fileId ->
                        runCatching { cancelDownload(fileId) }
                    }

                    // Step 2: Send logOut (network required)
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
                        mutableResetResult.value = AccountResetResult.Failed(reason)
                        return@launch
                    }

                    val response = logoutResult.getOrNull()
                    if (response?.string("@type") == "error") {
                        val err = SensitiveDataRedactor.redact(response.string("message") ?: "logOut error")
                        // Fail immediately on TDLib logOut error — do NOT wait for close timeout
                        mutableResetResult.value = AccountResetResult.Failed("Logout failed: $err")
                        return@launch
                    }

                    // Step 3: Wait for TDLib to confirm authorizationStateClosed
                    val closeResult = runCatching {
                        withTimeout(closeTimeoutMs) {
                            authorization.first { it.state == AuthorizationState.Closed }
                        }
                    }

                    if (closeResult.isFailure) {
                        val reason = "TDLib did not confirm close within timeout"
                        mutableResetResult.value = AccountResetResult.Failed(reason)
                        return@launch
                    }

                    // Step 4: TDLib confirmed Closed — safe to delete local data
                    val dbDeleted = if (databaseDirectory.exists()) databaseDirectory.deleteRecursively() else true
                    val filesDeleted = if (filesDirectory.exists()) filesDirectory.deleteRecursively() else true

                    if (dbDeleted && filesDeleted) {
                        encryptionManager?.clearKey()
                        mutableResetResult.value = AccountResetResult.Completed
                    } else {
                        mutableResetResult.value = AccountResetResult.Failed("Local file deletion failed. Encryption key preserved.")
                    }
                } catch (e: CancellationException) {
                    mutableResetResult.value = AccountResetResult.Cancelled
                    throw e
                } catch (e: Exception) {
                    mutableResetResult.value = AccountResetResult.Failed(
                        SensitiveDataRedactor.redact(e.message ?: "Reset failed")
                    )
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

            val response = execute(request)
            if (response.string("@type") == "error") {
                return HistoryPage.error(
                    SensitiveDataRedactor.redact(response.string("message") ?: "Unknown error")
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

    override fun downloadPagingItem(fileId: Int): ActionResult {
        val request = synchronized(lock) {
            if (fileId in pendingCancellations || !pendingDownloads.add(fileId)) return ActionResult.DUPLICATE
            requestEnvelope("download-$fileId", buildJsonObject {
                put("@type", "downloadFile")
                put("file_id", fileId)
                put("priority", 16)
                put("synchronous", false)
            }).also { pendingDownloadRequests[fileId] = it.string("@extra").orEmpty() }
        }
        send(request)
        return ActionResult.ACCEPTED
    }

    override fun download(fileId: Int): ActionResult {
        return downloadPagingItem(fileId)
    }

    override fun cancelDownload(fileId: Int): ActionResult {
        val request = synchronized(lock) {
            if (fileId !in pendingDownloads || !pendingCancellations.add(fileId)) return ActionResult.INVALID_STATE
            requestEnvelope("cancel-$fileId", buildJsonObject {
                put("@type", "cancelDownloadFile")
                put("file_id", fileId)
                put("only_if_pending", false)
            }).also { pendingCancelRequests[fileId] = it.string("@extra").orEmpty() }
        }
        send(request)
        // CP7: Use injected account identity
        val identity = TransferIdentity(currentAccountId(), currentDatabaseGeneration(), fileId)
        mutableTransferUpdates.tryEmit(
            TransferUpdate(identity = identity, state = TransferState.TransferCancelled)
        )
        updateItem(fileId) { it.copy(downloadState = DownloadState.Canceled, localPath = null) }
        return ActionResult.ACCEPTED
    }

    private fun handleFile(file: JsonObject) {
        val fileId = file.int("id")
        if (synchronized(lock) { fileId !in pendingDownloads || fileId in pendingCancellations }) return
        val local = file.obj("local") ?: return
        val complete = local.bool("is_downloading_completed")
        val total = file.long("expected_size").takeIf { it > 0 } ?: file.long("size")
        val downloaded = local.long("downloaded_size")
        val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        val path = local.string("path").orEmpty()
        if (complete && path.isNotBlank() && File(path).isFile) synchronized(lock) {
            pendingDownloads.remove(fileId)
            pendingDownloadRequests.remove(fileId)
        }
        // CP7: Use injected account identity instead of hardcoded (1L, 1L)
        val identity = TransferIdentity(currentAccountId(), currentDatabaseGeneration(), fileId)
        val state = if (complete && path.isNotBlank() && File(path).isFile) {
            // CP4: Completed carries localPath
            TransferState.Completed(path)
        } else {
            TransferState.InProgress(percent)
        }
        mutableTransferUpdates.tryEmit(
            TransferUpdate(
                identity = identity,
                state = state,
                percent = percent,
                localPath = path.takeIf { complete && File(it).isFile },
            )
        )
        updateItem(fileId) {
            if (complete && path.isNotBlank() && File(path).isFile) it.copy(downloadState = DownloadState.Complete, localPath = path)
            else it.copy(downloadState = DownloadState.Downloading(percent))
        }
    }

    override fun preview(itemId: Long): PreviewTarget? {
        val item = (mutableLibrary.value as? LibraryState.Content)?.items?.firstOrNull { it.id == itemId } ?: return null
        val path = item.localPath?.takeIf { File(it).isFile } ?: return null
        return when (item.kind) {
            MediaKind.IMAGE -> PreviewTarget.Image(item.id, path)
            MediaKind.VIDEO -> PreviewTarget.Video(item.id, path)
            else -> null
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

    override suspend fun getAvailableSources(): List<FileSource> {
        val savedChatId = getSavedMessagesChatId() ?: return emptyList()
        return listOf(
            FileSource(id = savedChatId, title = "Saved Messages", savedMessages = true)
        )
    }

    private fun updateItem(fileId: Int, transform: (MediaItem) -> MediaItem) {
        val content = mutableLibrary.value as? LibraryState.Content ?: return
        mutableLibrary.value = LibraryState.Content(content.items.map { if (it.fileId == fileId) transform(it) else it })
    }

    private fun handleOk(root: JsonObject) {
        val request = root.string("@extra").orEmpty().substringBefore(':')
        if (!request.startsWith("cancel-")) return
        request.removePrefix("cancel-").toIntOrNull()?.let { fileId ->
            synchronized(lock) {
                if (pendingCancelRequests[fileId] != root.string("@extra")) return
                pendingCancelRequests.remove(fileId)
                pendingDownloadRequests.remove(fileId)
                pendingCancellations.remove(fileId)
                pendingDownloads.remove(fileId)
            }
        }
    }

    private fun handleError(root: JsonObject) {
        val message = SensitiveDataRedactor.redact(root.string("message") ?: "Telegram request failed")
        val extra = root.string("@extra").orEmpty()
        val request = extra.substringBefore(':')
        when {
            request == "auth" && synchronized(lock) { pendingAuthRequest == extra } -> synchronized(lock) {
                pendingAuthAction = false
                pendingAuthRequest = null
                mutableAuthorization.value = mutableAuthorization.value.copy(actionPending = false, safeError = message)
            }
            (request == "getMe" || request == "chat" || request == "history") && synchronized(lock) { pendingLibraryRequest == extra } -> {
                synchronized(lock) { pendingLibraryRequest = null }
                mutableLibrary.value = LibraryState.Error(message)
            }
            request == "parameters" && synchronized(lock) { pendingParametersRequest == extra } -> {
                synchronized(lock) { pendingParametersRequest = null }
                mutableAuthorization.value = mutableAuthorization.value.copy(actionPending = false, safeError = message)
            }
            request.startsWith("download-") -> request.removePrefix("download-").toIntOrNull()?.let { fileId ->
                if (synchronized(lock) { pendingDownloadRequests[fileId] != extra }) return
                synchronized(lock) {
                    pendingDownloadRequests.remove(fileId)
                    pendingDownloads.remove(fileId)
                }
                updateItem(fileId) { it.copy(downloadState = DownloadState.Failed(message), localPath = null) }
            }
            request.startsWith("cancel-") -> request.removePrefix("cancel-").toIntOrNull()?.let { fileId ->
                if (synchronized(lock) { pendingCancelRequests[fileId] != extra }) return
                synchronized(lock) {
                    pendingCancelRequests.remove(fileId)
                    pendingDownloadRequests.remove(fileId)
                    pendingCancellations.remove(fileId)
                    pendingDownloads.remove(fileId)
                }
                updateItem(fileId) { it.copy(downloadState = DownloadState.Failed(message), localPath = null) }
            }
        }
    }

    private fun sendRequest(prefix: String, body: JsonObject) {
        send(requestEnvelope(prefix, body))
    }

    private fun requestEnvelope(prefix: String, body: JsonObject): JsonObject =
        JsonObject(body + ("@extra" to JsonPrimitive("$prefix:${requestSequence.incrementAndGet()}")))

    private fun send(body: JsonObject) {
        val id = synchronized(lock) { clientId } ?: return
        native.send(id, body.toString())
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
            pendingParametersRequest = null
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
