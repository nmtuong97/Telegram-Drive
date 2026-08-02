# Phase 2 working-tree evidence manifest

- Repository: `nmtuong97/Telegram-Drive`
- Branch: `agent/android-phase-2`
- Base HEAD: `39552247e10055549ed2793d6ac1188de04d900e`
- Worktree: uncommitted Phase 2 changes plus final regression fix; no commit/push/PR performed.
- Date: 2026-08-02
- Devices: `Pixel_9_Pro` (`emulator-5554`, API 36, 1280x2856) and `TelegramDrive_Small` (API 35, 1080x1920)
- APK: `/Users/manhtuong/Documents/GitHub/Telegram-Drive/android-app/app/build/outputs/apk/debug/app-debug.apk`
- Real-source APK SHA-256: `bf4e9932eb5916f06d70e08eccfe5c37668a62ba1fca21efb11572c9808b5bba`
- Fake-source APK SHA-256: `ea769994954aa70daac65bb40b8d444dfd3fea70d3ccff01ca59c23f38e7ef0b`

## Commands completed

| Check | Result |
| --- | --- |
| Fake debug APK assembly via `run-gradle-single-flight.sh :app:assembleDebug -PtelegramDataSource=fake` | PASS |
| `run-gradle-single-flight.sh :app:lintDebug` | PASS; 0 errors, existing warnings only |
| Android CLI deploy and launch | PASS; fake sign-in screen shown |
| Fake phone/code/password journey | PASS; Saved Messages library shown |
| Small-screen metadata and download smoke | PASS on `TelegramDrive_Small`: badge/type/name, `Size unavailable`, `Date unavailable`, `Duration unavailable` for fake media, Remote/Local status, Download → Local, and `cache/fake-media/mountain.jpg` verified with `run-as` |
| Source browser | PASS; Saved Messages, Design Assets, Project Documents shown and selectable |
| Image download/preview | PASS; image preview screen captured |
| Audio download/preview | PASS; Media3 controls screen captured |
| PDF download/preview | PASS after removing unsafe Compose bitmap recycling; visible `Page 1 of 1` screen captured and no new app crash |
| Real APK install over existing package | PASS; `android run` installed and launched without uninstall/clear-data, preserving the existing real TDLib session |
| Final real metadata/session-restore smoke | PASS; real cards showed type, size, `2026-08-02`, `Duration unavailable`, Remote; force-stop/relaunch returned to Saved Messages without re-authentication |
| External open / missing-app fallback | PASS for handler-missing path on `TelegramDrive_Small`: fake `archive.zip` opened the secure external-preview screen and showed `No compatible app is installed for this file.` Handler-present app was not installed |
| Offline device behavior | Not counted as device acceptance; the emulator network toggle was inconclusive; unit/error-path coverage remains passing |
| Real source browser | PASS; Saved Messages, Matchic Coder, and Matchic Notifier were loaded from the real account; source switching was exercised |
| Real MP4 mapping/download/preview | PASS after fixing document filename/MIME fallback and persisted local-path preview; real 103.0 MB MP4 showed `video`, `Preview`, and opened the in-app video player |
| Real account logout/reset | Not run; destructive operation intentionally left untouched |
| Full unit-test gate | PASS; sequential `:app:testDebugUnitTest` completed with 117 tests, 0 failures, 0 errors |
| Late update after cancel regression | PASS; stale `updateFile` events no longer overwrite a canceled transfer |

## Captured files

- `app-launched.png`, `layout.json`
- `library.png`, `library-layout.json`
- `image-preview.png`, `image-preview-layout.json`
- `audio-screen.png`
- `pdf-fix-preview.png`, `pdf-fix-preview-layout.json`
- `real-preflight-layout.json`, `real-post-install-layout.json`, `real-post-install.png`
- `real-matchic-coder-layout.json`, `real-matchic-notifier-layout.json`
- `real-video-final-library-layout.json`, `real-video-final-preview.png`
- `final-real-post-install-layout.json`, `final-real-post-install.png`
- `final-real-session-restore-settled-layout.json`, `final-real-session-restore-settled.png`
- `final-real-paging-scroll-1-layout.json`, `final-real-paging-scroll-3-layout.json`, `final-real-paging-scroll-11-layout.json`
- `final-real-phase2-layout.json`, `final-real-phase2.png`
- `final-real-phase2-session-restore-layout.json`, `final-real-phase2-session-restore.png`
- `small-fake-final2-library-layout.json`, `small-fake-final2-library.png`
- `small-fake-final2-downloaded-layout.json`
- `small-fake-external-source-layout.json`, `small-fake-external-downloaded-layout.json`
- `small-fake-external-preview-layout.json`, `small-fake-external-fallback-layout.json`, `small-fake-external-fallback.png`
- `final-real-after-external-restore-layout.json`
- `final-real-offline-layout.json`, `final-real-offline.png` (network toggle was restored; not treated as a conclusive offline gate)
- intermediate phone/code/source layouts for the fake journey

Handoff status: `PHASE_2_NOT_FULLY_VERIFIED — DEVICE_OR_USER_INTERACTION_REQUIRED`. Full automated gates, small-screen fake runtime, and real session/source/paging/metadata smoke pass; real logout/reset remains intentionally unrun and no physical device is available.
