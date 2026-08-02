# Phase 3 evidence manifest

Status: `BLOCKED — PHASE_3_NOT_COMPLETE`

Evidence is split between implementation/fake/device checks and the mandatory
real-account gate. A file is not described as passing unless it was actually produced.

## Repository

| Item | Value |
| --- | --- |
| Canonical branch | `main` |
| Starting/final checked-out main HEAD | `b41b7e9f302e0336a7eb16565f636a98fc6223fd` (uncommitted fixes in worktree) |
| Phase 2 merge parent | `4b85d69074772f076ffdd11e2fafe546b027981c` |
| Phase 3 merge commit | `b41b7e9f302e0336a7eb16565f636a98fc6223fd` (historical implementation tip `26891616cd745862d3a7149ee80f5eea41fb38d3`) |
| Official plan | [`android-app/MASTER_PLAN.md`](../android-app/MASTER_PLAN.md) |
| Detailed plan | [`docs/phase-3-plan.md`](phase-3-plan.md) |
| Initial implementation commit | `3329d13` (`feat(android): implement phase 3 saved media gallery`). |
| Test coverage commit | `ace373a` (`test(android): add phase 3 media and streaming coverage`). |
| Evidence/documentation commit | `9fd8b0f`, `e3c0a4c`, `bea623f`. |
| Latest lifecycle hardening commit | `3d0a15a` (`fix(android): harden phase 3 account and media lifecycle`). |
| Latest account-isolation/gallery-flow commit | `730e2b1` (`fix(android): scope gallery flows to account identity`). |
| Previous range-cancellation hardening commit | `3466de3` (`fix(android): cancel stale video range waits`). |
| Previous media-cache/reader hardening commit | `305b309` (`fix(android): harden phase 3 media cache and readers`). |
| Previous implementation commit | `1224709` (`fix(android): add non-destructive media database migration`). |
| Previous update-delivery commit | `995cf5b` (`fix(android): prevent dropped saved message updates`). |
| Previous implementation commit | `ad07249` (`fix(android): add retryable video buffering state`). |
| Latest implementation commit | `f217e53` (`feat(android): add streaming spike diagnostics`). |

## Commands and exit codes

All Gradle invocations were run one at a time through
`android-app/scripts/run-gradle-single-flight.sh` with `--no-daemon --no-configuration-cache
--no-parallel --max-workers=1 --console=plain --stacktrace`.

| Command | Exit code | Notes |
| --- | ---: | --- |
| `:app:testDebugUnitTest -PtelegramDataSource=fake` | 0 | ~14.0s; 132 tests, 0 failures/errors/skips. |
| `:app:compileDebugAndroidTestKotlin -PtelegramDataSource=fake` | 0 | ~10.5s; new instrumented tests compile, but no device run was permitted. |
| `:app:lintDebug` | 0 | ~9.0s; lint passed with existing warnings only. |
| `:app:assembleDebug -PtelegramDataSource=fake` | 0 | ~10.1s; APK assembled and not deployed. |
| `:app:assembleDebug` | 0 | ~4.3s; real APK assembled and not deployed. |
| `:app:connectedDebugAndroidTest -PtelegramDataSource=fake` | NOT RUN | Only the real-session emulator is available. |

After the final Gradle gates, the workspace was checked for Gradle daemon/test-worker
processes; a stale daemon was terminated and no test worker remained.

## Android CLI/device evidence

Device inventory: `android emulator list` returned only `Pixel_9_Pro`; `adb devices -l`
identified `emulator-5554`, reserved for the real Telegram session.

- `android docs search "Room Paging Compose"` completed.
- `android docs fetch "kb://android/topic/libraries/architecture/paging/v3-overview"` completed.
- `android emulator list` and `adb devices` confirmed the emulator.
- `android run --apks=...` was intentionally not run; no APK replacement or install occurred.
- Read-only package inspection found installed package `com.nmtuong.telegramdrive`, version `1.0`.
- Force-stop/relaunch of the installed package returned to Saved Media without OTP; this is installed-runtime evidence only.
- A temporary layout was captured under `/tmp` and was not added to repository evidence.
- Current fake-device preflight hierarchy and screenshot are [`phase-3-final-layout.json`](evidence/phase-3-final-layout.json) and [`phase-3-final-runtime.png`](evidence/phase-3-final-runtime.png). The captured screen is sign-in because no fake session was seeded; authenticated gallery/video flows remain covered by connected tests and earlier fake gallery/video captures.
- A later emulator session check also remained at Telegram sign-in: [`phase-3-current-session-layout.json`](evidence/phase-3-current-session-layout.json) and [`phase-3-current-session.png`](evidence/phase-3-current-session.png).
- Resumed fake-device sign-in hierarchy and screenshot are [`phase-3-resumed-runtime-layout.json`](evidence/phase-3-resumed-runtime-layout.json) and [`phase-3-resumed-runtime.png`](evidence/phase-3-resumed-runtime.png); no Telegram credentials were entered.
- Existing fake gallery/video captures remain fake/injected evidence only; no new fake-device capture was added.
- No new real-account screenshot or log was committed for this goal; the installed-package relaunch is documented as installed-runtime evidence only.
- Fake login layouts: `phase-3-fake-code-layout.json`, `phase-3-fake-password-layout.json`.
- Earlier gallery capture: `phase-3-gallery-fake-clean.png` and matching layout JSON.
- Image viewer: `phase-3-image-viewer-final.png` and matching layout JSON show the fake
  image viewer after local-file validation and decode.
- Video preview: `phase-3-video-preview-final.png` shows the fake Media3 player with a
  loaded timeline and controls; it does not count as real progressive-streaming proof.

## Architecture evidence

- Room entities/DAOs/database: `android-app/app/src/main/java/com/nmtuong/telegramdrive/data/local/`.
- Room schema artifact: `android-app/app/schemas/com.nmtuong.telegramdrive.data.local.MediaDatabase/2.json`; `MIGRATION_1_2` adds catch-up completion time without destructive reset.
- Sync and Paging repository: `data/SavedMediaRepository.kt`.
- TDLib update/file snapshot bridge: `telegram/TdLibJsonGateway.kt`; saved-message updates use
  an unbounded channel and account-boundary draining to preserve listener bursts without
  allowing queued updates from the prior generation through reset.
- Range coordinator and Media3 data source: `data/video/`.
- Video preview: `feature/preview/VideoPreviewScreen.kt` reports buffering and retries
  after Media3/TDLib errors, including when a prior partial path was cleaned up.
- Real-account streaming diagnostics use logcat tag `TelegramDrive.Streaming` for
  `downloadFile` range requests, `updateFile` prefix/offset/size snapshots, reads,
  seeks, cancellation, and temporary cleanup. No real-account log capture is claimed yet.
- Thumbnail/original/cache coordinator: `data/MediaAccessCoordinator.kt`.
- Gallery UI/ViewModel: `feature/gallery/`.
- Backup exclusions: `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml`.

## Test evidence

- Unit message mapping: `PhaseThreeMessageMappingTest.kt` plus existing mapper tests.
- Unit saved-message update delivery: `TdLibSavedMessageUpdatesTest.kt` covers a 256-event
  listener burst and queued-update draining at an account boundary.
- Unit progressive range/seek/cancel cleanup: `VideoStreamingCoordinatorTest.kt`.
- Unit LRU thumbnail eviction: `MediaAccessCoordinatorTest.kt`.
- Instrumented Room migration: `MediaDatabaseMigrationTest.migratesVersionOneWithoutDroppingSyncState`.
- TDLib logout/reset stale-file invalidation: `TdLibJsonGatewayTest.logoutInvalidatesFileSnapshotsAndBlocksLateUpdates`.
- Unit 3,000-item fake history with duplicate stable file identities and video document
  metadata: `FakeSavedMediaGatewayTest.kt`.
- Instrumented Room 2,000-row Paging/shared-file test:
  `SavedMediaRoomPagingTest.kt`.
- Existing Phase 2 instrumented Keystore test method names were made DEX-safe without
  changing assertions; this was a packaging prerequisite fix.

## Real-account evidence

No Phase 3 real-account evidence is claimed yet. Required pending captures/results:

- authorized login/session restore and Saved Messages head/backfill;
- real incremental new/edit/delete event handling;
- real image/original reconcile and re-download;
- real Telegram video and document-video classification;
- sufficiently large video: `downloadFile` request/response, `updateFile`, prefix/range
  sizes, start-before-complete, seek near beginning/middle/end;
- network loss/recovery, player close cleanup, filesystem usage before/during/after;
- logout/reset and account-isolation verification.

Blockers: `DEPLOYMENT_APPROVAL_REQUIRED — SESSION_PRESERVING_UPDATE` for validating
the new APK; `USER_INTERACTION_REQUIRED — TEST_MESSAGE_MUTATION` for new/edit/delete;
and `NOT_EXECUTED — USER_POLICY_SESSION_PRESERVATION` for real logout/reset. The
scope is not silently downgraded to full-download playback.

## Privacy audit

- No new raw account evidence was added by this goal.
- Historical Phase 2 layout/manifests and screenshots include account-specific source
  names, filenames, and other private media; they are not sanitized evidence.
- The historical artifacts were visually inspected and remain flagged for redaction
  or replacement before any claim of privacy-clean evidence.
- No credential, OTP, session payload, or new account filesystem path was captured.

## APK

- Expected final path: `android-app/app/build/outputs/apk/debug/app-debug.apk`.
- Final real debug APK path: `android-app/app/build/outputs/apk/debug/app-debug.apk`.
- Final real debug APK size: `70,069,252` bytes.
- Final real debug APK SHA-256: `8bd793964165f342d28987398c8c02bd92991caf3602480d0eaa7abd311aa4af`.
