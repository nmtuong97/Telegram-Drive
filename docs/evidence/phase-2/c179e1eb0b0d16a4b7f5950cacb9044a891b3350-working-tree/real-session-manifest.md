# Real-account Android smoke evidence

- Repository: `nmtuong97/Telegram-Drive`
- Branch: `agent/android-phase-2`
- Device: `Pixel_9_Pro` (`emulator-5554`)
- APK: `/Users/manhtuong/Documents/GitHub/Telegram-Drive/android-app/app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256: `bf4e9932eb5916f06d70e08eccfe5c37668a62ba1fca21efb11572c9808b5bba`
- Data source: real TDLib source; local `telegram-api.properties` was present but its values were not printed or added to source/artifact.

## Results

- Existing real session was visible before install and remained visible after `android run`; no uninstall, clear-data, logout, or reset was performed.
- Force-stop/relaunch session-restore smoke passed: after relaunch, the real source chips and Saved Messages MP4 list returned without re-authentication.
- Saved Messages loaded five real MP4 items, including a 103.0 MB file; source chips showed `Matchic Coder` and `Matchic Notifier`.
- Source switching was exercised; the two auxiliary real sources showed empty supported-media states with refresh controls.
- The first real MP4 became `Preview` after its local file was available, remained available after reinstall, and opened the in-app video player with controls.
- The mapping fix covers TDLib `messageDocument` objects whose MIME is blank/incomplete but whose filename identifies video/audio/GIF/PDF content.
- Final file-browser metadata smoke showed real file name, type, size, message date, explicit duration-unavailable state for generic document-backed MP4s, and Remote status.
- Final force-stop/relaunch smoke returned to the real Saved Messages library without asking for credentials again.
- Logout/reset was intentionally not run because it would destroy the user's active test session and real data.

## Captures

- `real-preflight-layout.json`
- `real-post-install-layout.json`, `real-post-install.png`
- `real-matchic-coder-layout.json`, `real-matchic-notifier-layout.json`
- `real-video-final-library-layout.json`, `real-video-final-preview.png`
- `final-real-post-install-layout.json`, `final-real-post-install.png`
- `final-real-session-restore-settled-layout.json`, `final-real-session-restore-settled.png`
- `final-real-paging-scroll-1-layout.json`, `final-real-paging-scroll-3-layout.json`, `final-real-paging-scroll-11-layout.json`
- `final-real-phase2-layout.json`, `final-real-phase2.png`
- `final-real-phase2-session-restore-layout.json`, `final-real-phase2-session-restore.png`
