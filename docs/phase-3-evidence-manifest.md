# Phase 3 evidence manifest

Status: `BLOCKED — PHASE_3_NOT_COMPLETE`

Evidence is split between implementation/fake/device checks and the mandatory
real-account gate. A file is not described as passing unless it was actually produced.

## Repository

| Item | Value |
| --- | --- |
| Baseline branch | `agent/android-phase-2` |
| Baseline commit | `72325e101f201a8ce5a4c7786142f91c8ac00783` |
| Working branch | `agent/android-phase-3` |
| Official plan | [`android-app/MASTER_PLAN.md`](../android-app/MASTER_PLAN.md) |
| Detailed plan | [`docs/phase-3-plan.md`](phase-3-plan.md) |
| Initial implementation commit | `3329d13` (`feat(android): implement phase 3 saved media gallery`). |
| Test coverage commit | `ace373a` (`test(android): add phase 3 media and streaming coverage`). |
| Evidence/documentation commit | `9fd8b0f`, `e3c0a4c`, `bea623f`. |
| Latest lifecycle hardening commit | `3d0a15a` (`fix(android): harden phase 3 account and media lifecycle`). |
| Latest account-isolation/gallery-flow commit | `730e2b1` (`fix(android): scope gallery flows to account identity`). |
| Previous range-cancellation hardening commit | `3466de3` (`fix(android): cancel stale video range waits`). |
| Previous media-cache/reader hardening commit | `305b309` (`fix(android): harden phase 3 media cache and readers`). |
| Latest implementation commit | `1224709` (`fix(android): add non-destructive media database migration`). |

## Commands and exit codes

All Gradle invocations were run one at a time through
`android-app/scripts/run-gradle-single-flight.sh` with `--no-daemon --no-configuration-cache
--no-parallel --max-workers=1 --console=plain --stacktrace`.

| Command | Exit code | Notes |
| --- | ---: | --- |
| `:app:testDebugUnitTest -PtelegramDataSource=fake` | 0 | 129 tests passed, 0 failures/errors/skips; includes account-generation, range-prefix, LRU eviction, complete-file-size, independent-reader, cancellation, and late-update coverage. |
| `:app:lintDebug` | 0 | Lint passed; warnings only. |
| `:app:assembleDebug -PtelegramDataSource=fake` | 0 | Final fake APK produced; SHA-256 is recorded below. |
| `:app:assembleDebug` (real default) | 0 | Real APK assembled; launch requires Telegram sign-in. |
| `:app:connectedDebugAndroidTest -PtelegramDataSource=fake` | 0 | 13 tests on `TelegramDrive_Small` API 36, 0 failures/errors/skips; includes Room 1→2 migration, account-scoped Paging rebinding, repository crash-resume/update, and shared-video release. |

After the final Gradle gates, the workspace was checked for Gradle daemon/test-worker
processes; a stale daemon was terminated and no test worker remained.

## Android CLI/device evidence

Device: `TelegramDrive_Small` AVD, serial `emulator-5554`, API 36.

- `android docs search "Room Paging Compose"` completed.
- `android docs fetch "kb://android/topic/libraries/architecture/paging/v3-overview"` completed.
- `android emulator list` and `adb devices` confirmed the emulator.
- `android run --apks=<debug APK> --device=emulator-5554 --activity=com.nmtuong.telegramdrive.MainActivity` installed and launched the fake APK.
- `android layout --pretty --output=docs/evidence/phase-3-final-layout.json` captured the current fake hierarchy.
- `android screen capture --output=docs/evidence/phase-3-final-runtime.png --annotate` captured the current fake preflight.
- Current fake-device preflight hierarchy and screenshot are [`phase-3-final-layout.json`](evidence/phase-3-final-layout.json) and [`phase-3-final-runtime.png`](evidence/phase-3-final-runtime.png). The captured screen is sign-in because no fake session was seeded; authenticated gallery/video flows remain covered by connected tests and earlier fake gallery/video captures.
- A later emulator session check also remained at Telegram sign-in: [`phase-3-current-session-layout.json`](evidence/phase-3-current-session-layout.json) and [`phase-3-current-session.png`](evidence/phase-3-current-session.png).
- Resumed fake-device sign-in hierarchy and screenshot are [`phase-3-resumed-runtime-layout.json`](evidence/phase-3-resumed-runtime-layout.json) and [`phase-3-resumed-runtime.png`](evidence/phase-3-resumed-runtime.png); no Telegram credentials were entered.
- Latest fake-device Media3 video preview is [`phase-3-current-video.png`](evidence/phase-3-current-video.png).
- Real preflight launch reached Telegram sign-in; hierarchy and screenshot are
  [`phase-3-real-final-preflight-layout.json`](evidence/phase-3-real-final-preflight-layout.json) and [`phase-3-real-final-preflight.png`](evidence/phase-3-real-final-preflight.png). No OTP/2FA or account data was entered.
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
- TDLib update/file snapshot bridge: `telegram/TdLibJsonGateway.kt`.
- Range coordinator and Media3 data source: `data/video/`.
- Thumbnail/original/cache coordinator: `data/MediaAccessCoordinator.kt`.
- Gallery UI/ViewModel: `feature/gallery/`.
- Backup exclusions: `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml`.

## Test evidence

- Unit message mapping: `PhaseThreeMessageMappingTest.kt` plus existing mapper tests.
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

Blocker: `USER_INTERACTION_REQUIRED` for an authorized Telegram account and any OTP/2FA
entry. The scope is not silently downgraded to full-download playback.

## APK

- Expected final path: `android-app/app/build/outputs/apk/debug/app-debug.apk`.
- Final fake APK size: `70,146,979` bytes.
- Final SHA-256: `94d880fd64497d7fb4a3d38d8c956bbacfa0f3e5396b18bde123a66341389894`.
