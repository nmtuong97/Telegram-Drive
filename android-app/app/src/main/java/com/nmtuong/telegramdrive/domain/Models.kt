package com.nmtuong.telegramdrive.domain

enum class DataSourceMode(val id: String) { REAL("real"), FAKE("fake") }

enum class GatewayLifecycle { NEW, STARTING, RUNNING, CLOSING, CLOSED, ABORTED, FAILED }

sealed interface AuthorizationState {
  data object Unknown : AuthorizationState
  data object MissingConfiguration : AuthorizationState
  data object WaitingForTdlibParameters : AuthorizationState
  data object WaitingForPhoneNumber : AuthorizationState
  data object WaitingForCode : AuthorizationState
  data class WaitingForPassword(val hint: String = "") : AuthorizationState
  data object WaitingForEmailAddress : AuthorizationState
  data object WaitingForEmailCode : AuthorizationState
  data class WaitingForOtherDevice(val link: String) : AuthorizationState
  data object Ready : AuthorizationState
  data object LoggingOut : AuthorizationState
  data object Closing : AuthorizationState
  data object Closed : AuthorizationState
  data class Other(val name: String) : AuthorizationState
}

sealed interface AuthorizationAction {
  data class SubmitPhone(val phone: String) : AuthorizationAction
  data class SubmitCode(val code: String) : AuthorizationAction
  data class SubmitPassword(val password: String) : AuthorizationAction
  data class SubmitEmailAddress(val email: String) : AuthorizationAction
  data class SubmitEmailCode(val code: String) : AuthorizationAction
  data object Logout : AuthorizationAction
  data object Reset : AuthorizationAction
}

enum class ActionResult { ACCEPTED, INVALID_STATE, DUPLICATE, MISSING_CONFIGURATION }

data class AuthorizationSession(
  val state: AuthorizationState = AuthorizationState.Unknown,
  val actionPending: Boolean = false,
  val safeError: String? = null,
)

data class DiagnosticsState(
  val dataSource: DataSourceMode,
  val lifecycle: GatewayLifecycle = GatewayLifecycle.NEW,
  val nativeLibraryLoaded: Boolean = false,
  val clientCreated: Boolean = false,
  val authorizationState: AuthorizationState = AuthorizationState.Unknown,
  val safeError: String? = null,
  val clientInstanceCount: Int = 0,
)

data class Account(val id: Long, val displayName: String)
data class FileSource(val id: Long, val title: String, val savedMessages: Boolean)
enum class MediaKind { IMAGE, VIDEO, ANIMATION, AUDIO, PDF, DOCUMENT }
sealed interface DownloadState {
  data object NotDownloaded : DownloadState
  data class Downloading(val percent: Int) : DownloadState
  data object Complete : DownloadState
  data object Canceled : DownloadState
  data class Failed(val reason: String) : DownloadState
  data object Unavailable : DownloadState
}
data class MediaItem(
  val id: Long,
  val sourceId: Long,
  val name: String,
  val kind: MediaKind,
  val downloadState: DownloadState,
  val fileId: Int = id.toInt(),
  val sizeBytes: Long = 0,
  val durationSeconds: Int = 0,
  val localPath: String? = null,
)

sealed interface LibraryState {
  data object Idle : LibraryState
  data object Loading : LibraryState
  data class Content(val items: List<MediaItem>) : LibraryState
  data object Empty : LibraryState
  data class Error(val message: String) : LibraryState
}

sealed interface PreviewTarget {
  data class Image(val itemId: Long, val path: String) : PreviewTarget
  data class Video(val itemId: Long, val path: String) : PreviewTarget
  data class Audio(val itemId: Long, val path: String) : PreviewTarget
  data class Pdf(val itemId: Long, val path: String) : PreviewTarget
}

/**
 * Raw paging result returned by infrastructure layer.
 * Repository creates PagingSource from this; UI never sees infrastructure directly.
 */
data class HistoryPage(
  val items: List<MediaItem>,
  /** Raw TDLib last message ID — used as cursor for next page. Null when end of history. */
  val rawLastMessageId: Long?,
  /** True when TDLib confirmed no older messages exist in this history. */
  val endOfHistory: Boolean,
  val error: String? = null,
) {
  companion object {
    fun error(message: String) = HistoryPage(emptyList(), null, true, message)
    fun empty() = HistoryPage(emptyList(), null, true)
  }
}

/**
 * Identity of an active account session and database generation.
 */
data class AccountSessionIdentity(
  val accountId: Long,
  val databaseGeneration: Long,
)

/**
 * Identifies a transfer within an account/session scope.
 * raw TDLib file ID alone is not a global identity.
 */
data class TransferIdentity(
  val accountId: Long,
  val databaseGeneration: Long,
  val fileId: Int,
)

/**
 * Context-rich transfer request replacing the ambiguous fileId-only API.
 *
 * CP3: UI/ViewModel assembles this and passes to coordinator.
 * Repository/gateway does not need item to be in legacy LibraryState.
 */
data class TransferRequest(
  val identity: TransferIdentity,
  val messageId: Long,
  val sourceId: Long,
  val fileId: Int,
  val mediaKind: MediaKind,
  val expectedSizeBytes: Long = 0L,
  val knownLocalPath: String? = null,
)

/** Terminal and non-terminal states for a coordinated transfer. */
sealed interface TransferState {
  data object NotStarted : TransferState
  data object Queued : TransferState
  data class InProgress(val percent: Int) : TransferState
  /** CP4: Completed always carries a valid localPath. */
  data class Completed(val localPath: String) : TransferState
  data class TransferFailed(val reason: String) : TransferState
  data object TransferCancelled : TransferState
  /** Terminal — file permanently unavailable (deleted, unsupported, etc.) */
  data object Unavailable : TransferState

  val isTerminal: Boolean
    get() = this is Completed || this is TransferFailed || this is TransferCancelled || this is Unavailable
}

/**
 * CP4: TransferSnapshot is the single source of truth for coordinator state.
 *
 * - Completed always has a non-null localPath.
 * - Failed always has a safeError.
 * - attemptId distinguishes retry attempts; retention timer checks this before clearing.
 */
data class TransferSnapshot(
  val identity: TransferIdentity,
  val state: TransferState,
  val progress: Int = 0,
  val localPath: String? = null,
  val safeError: String? = null,
  val attemptId: Long = 0L,
) {
  val isTerminal: Boolean get() = state.isTerminal
}

data class TransferOperationId(
  val accountId: Long,
  val databaseGeneration: Long,
  val fileId: Int,
  val attemptId: Long,
)

/**
 * Dedicated event for transfer updates across gateway/repository boundaries.
 */
data class TransferUpdate(
  val identity: TransferIdentity,
  val state: TransferState,
  val percent: Int = 0,
  val localPath: String? = null,
  val safeError: String? = null,
  val attemptId: Long = 0L,
)

/** Result of an explicit account reset/logout operation. */
sealed interface AccountResetResult {
  data object Completed : AccountResetResult
  data class Failed(val reason: String) : AccountResetResult
  data object Cancelled : AccountResetResult
  data object AlreadyRunning : AccountResetResult
  data object InvalidState : AccountResetResult
}

/**
 * CP10: Observable reset progress states.
 * UI shows progress bar and failure/retry instead of silently blocking.
 */
sealed interface ResetProgress {
  data object Idle : ResetProgress
  data object BlockingTransfers : ResetProgress
  data object CancellingTransfers : ResetProgress
  data object InvalidatingGeneration : ResetProgress
  data object LoggingOut : ResetProgress
  data object WaitingForClosed : ResetProgress
  data object DeletingDatabase : ResetProgress
  data object DeletingFiles : ResetProgress
  data object DeletingKey : ResetProgress
  data object ClearingIdentity : ResetProgress
  data object Completed : ResetProgress
  data class Failed(val reason: String, val retryable: Boolean = true) : ResetProgress
}

