# Phase 2 implementation and verification status

## Scope and source of truth

- Repository: `nmtuong97/Telegram-Drive`
- Canonical branch: `main`
- Main merge baseline for this goal: `b41b7e9f302e0336a7eb16565f636a98fc6223fd`
- Phase 2 implementation branch and commits are historical lineage; Phase 2 is merged into `main`.
- Canonical scope: `android-app/MASTER_PLAN.md`, Final Phase 2 section.
- `specs/006-android-phase-1/spec.md` is historical Phase 1 material; it is not silently treated as a Phase 2 specification.
- This report is historical evidence and does not claim real logout/reset or release acceptance.

## Implemented in the current worktree

| Area | Implementation status |
| --- | --- |
| Real session and account identity | Preserved and reloaded on `Pixel_9_Pro` with the existing real TDLib account; no credential/session data added. |
| Source browser | Saved Messages plus eligible private/basic-group/supergroup chats from TDLib main chat list. |
| Paging | Raw-message cursor, boundary deduplication, bounded empty-page scanning, cursor-advance guard, deleted/unavailable media filtering. |
| Transfers | Session identity and generation correlation, progress, cancel, retry, dedup, max concurrency, valid local-file reuse, stale terminal-event protection. |
| Previews | Sampled image with zoom/pan, local video, animation, audio/voice, lazy page-at-a-time PDF, bounded UTF-8 text, secure unsupported-file external open. |
| File sharing | Non-exported FileProvider; only cache and `tdlib/files` are allowlisted, with temporary read permission. |
| File-browser metadata | Real TDLib cards show icon badge, name, type, size, message date, duration/unavailable, and Remote/Local/error/unavailable transfer status. |
| Offline/error UX | Sanitized network messages, visible source errors, retry controls, explicit paging error states. |
| Logout/reset | Existing owner-controlled close/reset path retained; transfer and local media state are cleared on reset. |
| Backup/device transfer | Existing disabled/excluded policy retained and FileProvider does not expose database/session/key paths. |

## Verification ledger

The following entries are evidence only when the command has completed successfully at the final worktree state. Android Gradle commands must run one at a time through `scripts/run-gradle-single-flight.sh`.

| Gate | Current evidence |
| --- | --- |
| Focused Kotlin compilation | Passed during implementation. |
| Focused feature tests | Passed for `LibraryViewModelTest`, `TextPreviewTest`, and related fake/transfer groups. |
| Full `testDebugUnitTest` | Passed at final worktree: 117 tests, 0 failures, 0 errors. The prior virtual-time hang was traced to test identity/close sequencing; a real late-`updateFile`-after-cancel regression was fixed and covered. |
| `lintDebug` | Passed at final worktree; 0 errors, existing warnings only. |
| Fake debug APK | Passed at final worktree; SHA-256 `ea769994954aa70daac65bb40b8d444dfd3fea70d3ccff01ca59c23f38e7ef0b`. |
| Real debug APK | Passed at final worktree; SHA-256 `bf4e9932eb5916f06d70e08eccfe5c37668a62ba1fca21efb11572c9808b5bba`. |
| Small-screen runtime | Passed on `TelegramDrive_Small` (API 35, 1080x1920): fake auth, source browser, metadata states, download to Local, and cache-file verification. |
| Android CLI deploy/layout/screenshot | Final real APK installed over the existing package on `Pixel_9_Pro` without clearing data; final real metadata, session-restore, source, and paging captures recorded. |
| Real-account session/data smoke verification | Historical evidence showed Saved Messages and private-source data from TDLib, real MP4 items classified as `video`, cards with size/date/Remote and explicit duration-unavailable metadata, and session return after force-stop/relaunch. Private source names are intentionally not repeated here. |
| External open / no-compatible-app fallback | PASS for the handler-missing path on `TelegramDrive_Small`: fake `archive.zip` opened the secure external-preview screen and displayed the localized no-compatible-app fallback. A handler-present app was not installed for this test. |
| Offline runtime | Unit/error-path coverage passes; the emulator network toggle was not conclusive and is not counted as a passing offline device gate. |
| Logout/reset on real account | Not run; it is destructive and would remove the user's real TDLib session/data. Automated logout/reset tests pass. |

## Handoff status

`PHASE_2_NOT_FULLY_VERIFIED — DEVICE_OR_USER_INTERACTION_REQUIRED`

The canonical branch is now `main`; this file does not promote historical Phase 2
evidence to final Phase 3 acceptance. Historical real-account evidence directories
were audited during the Phase 3 goal and contain account-specific layout/text
artifacts; they must be treated as non-sanitized historical evidence until redacted
or replaced.

Remaining acceptance gaps are environmental/destructive or not independently reproducible here: no physical device is attached; real-account logout/reset is intentionally not run because it would destroy the user's active test session/data; external-open fallback and offline device behavior lack conclusive runtime captures. Full automated tests, lint, fake/real debug builds, small-screen runtime, real session restore, source browser, and paging smoke are green/passing.

Older evidence directories under `docs/evidence/phase-2/` belong to earlier commits and are not evidence for this worktree. No current evidence manifest is marked as passed.
