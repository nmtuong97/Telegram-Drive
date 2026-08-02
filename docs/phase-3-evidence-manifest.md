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
| Final implementation commit | `3329d13` (`feat(android): implement phase 3 saved media gallery`). |
| Test coverage commit | `ace373a` (`test(android): add phase 3 media and streaming coverage`). |
| Final evidence/documentation commit | `9fd8b0f`; this handoff update is committed separately. |

## Commands and exit codes

All Gradle invocations were run one at a time through
`android-app/scripts/run-gradle-single-flight.sh` with `--no-daemon --no-configuration-cache
--no-parallel --max-workers=1 --console=plain --stacktrace`.

| Command | Exit code | Notes |
| --- | ---: | --- |
| `:app:testDebugUnitTest -PtelegramDataSource=fake` | 0 | Full unit suite passed after the final streaming predicate fix. |
| `:app:lintDebug -PtelegramDataSource=fake` | 0 | Lint passed after explicit Media3 `androidx.annotation.OptIn`. |
| `:app:assembleDebug -PtelegramDataSource=fake` | 0 | Final APK produced after fake-image patch. |
| `:app:assembleDebug -PtelegramDataSource=real` | 0 | Real APK assembled; launch requires Telegram sign-in. |
| `:app:connectedDebugAndroidTest -PtelegramDataSource=fake` | 0 | 9 tests on `Pixel_9_Pro` API 36. |

After the final Gradle gates, the workspace was checked for Gradle daemon/test-worker
processes; a stale daemon was terminated and no test worker remained.

## Android CLI/device evidence

Device: `Pixel_9_Pro` AVD, serial `emulator-5554`, API 36.

- `android docs search "Room Paging Compose"` completed.
- `android docs fetch "kb://android/topic/libraries/architecture/paging/v3-overview"` completed.
- `android emulator list` and `adb devices` confirmed the emulator.
- `android run --apks=<debug APK> --device=emulator-5554 --activity=com.nmtuong.telegramdrive.MainActivity` installed and launched the fake APK.
- `android layout --pretty --output=docs/evidence/phase-3-gallery-final-layout.json` captured the gallery hierarchy.
- `android screen capture --output=docs/evidence/phase-3-gallery-final.png` captured the Room-backed gallery.
- Real preflight launch reached Telegram sign-in; hierarchy is in
  `phase-3-real-preflight-layout.json`. No OTP/2FA or account data was entered.
- Fake login layouts: `phase-3-fake-code-layout.json`, `phase-3-fake-password-layout.json`.
- Earlier gallery capture: `phase-3-gallery-fake-clean.png` and matching layout JSON.
- Image viewer: `phase-3-image-viewer-final.png` and matching layout JSON show the fake
  image viewer after local-file validation and decode.
- Video preview: `phase-3-video-preview-final.png` shows the fake Media3 player with a
  loaded timeline and controls; it does not count as real progressive-streaming proof.

## Architecture evidence

- Room entities/DAOs/database: `android-app/app/src/main/java/com/nmtuong/telegramdrive/data/local/`.
- Sync and Paging repository: `data/SavedMediaRepository.kt`.
- TDLib update/file snapshot bridge: `telegram/TdLibJsonGateway.kt`.
- Range coordinator and Media3 data source: `data/video/`.
- Thumbnail/original/cache coordinator: `data/MediaAccessCoordinator.kt`.
- Gallery UI/ViewModel: `feature/gallery/`.
- Backup exclusions: `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml`.

## Test evidence

- Unit message mapping: `PhaseThreeMessageMappingTest.kt` plus existing mapper tests.
- Unit progressive range/seek/cancel cleanup: `VideoStreamingCoordinatorTest.kt`.
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
- Final SHA-256: `098e2c5b2250dd7b80cc52668b364429c1dde930d5962b99d3a98f0e74ec663d`.
