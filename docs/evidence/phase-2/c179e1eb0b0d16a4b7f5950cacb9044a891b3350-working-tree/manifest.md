# Phase 2 working-tree evidence manifest

- Repository: `nmtuong97/Telegram-Drive`
- Branch: `agent/android-phase-2`
- Code baseline: `c179e1eb0b0d16a4b7f5950cacb9044a891b3350`
- Worktree: uncommitted Phase 2 changes; no commit/push/PR performed.
- Date: 2026-08-02
- Device: `Pixel_9_Pro` (`emulator-5554`)
- APK: `/Users/manhtuong/Documents/GitHub/Telegram-Drive/android-app/app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256: `8027cb5ae699ae54b7391f73f387162c302d03c2a732ba5a59869321a7e75442`

## Commands completed

| Check | Result |
| --- | --- |
| Fake debug APK assembly via `run-gradle-single-flight.sh :app:assembleDebug -PtelegramDataSource=fake` | PASS |
| `run-gradle-single-flight.sh :app:lintDebug` | PASS; 0 errors, existing warnings only |
| Android CLI deploy and launch | PASS; fake sign-in screen shown |
| Fake phone/code/password journey | PASS; Saved Messages library shown |
| Source browser | PASS; Saved Messages, Design Assets, Project Documents shown and selectable |
| Image download/preview | PASS; image preview screen captured |
| Audio download/preview | PASS; Media3 controls screen captured |
| PDF download/preview | PASS after removing unsafe Compose bitmap recycling; visible `Page 1 of 1` screen captured and no new app crash |
| Real account login/session restore/logout | Not run; requires user-owned Telegram credentials and interaction |
| Full unit-test gate | Not accepted; final sequential run timed out after 150s in the legacy TDLib gateway virtual-time loop |

## Captured files

- `app-launched.png`, `layout.json`
- `library.png`, `library-layout.json`
- `image-preview.png`, `image-preview-layout.json`
- `audio-screen.png`
- `pdf-fix-preview.png`, `pdf-fix-preview-layout.json`
- intermediate phone/code/source layouts for the fake journey

Handoff status: `BLOCKED — PHASE_2_NOT_COMPLETE`. The manifest deliberately does not mark Phase 2 complete: the final full unit-test task timed out in the legacy TDLib virtual-time harness, and real-account login/session restore/logout still requires user-owned Telegram credentials and interaction.
