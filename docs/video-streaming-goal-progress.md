# Video Streaming Goal Progress

## Status

`BLOCKED` for the goal’s final completion rule. The streaming-first implementation, automated tests, Android gates, and real-source launch are complete. The authenticated real Telegram runtime matrix cannot be completed because `emulator-5554` has no Ready session; no credentials, OTP, logout, app-data reset, or session mutation was performed.

## Baseline and scope

- Branch: `agent/android-streaming-video-goal`
- Required baseline: `b1514931390f0002cbafd1b83f33ab54f913cfc0`
- Implementation commit: current feature-branch commit `Harden Android streaming video playback lifecycle`
- Baseline was clean and matched the required commit.
- Generated Tauri Android output was not edited.
- The report and implementation remain scoped to core Saved Messages video playback: streaming-first open, local-preferred selection, seeking, retry, lifecycle ownership, persistence, and Gallery continuity.

## Implementation decisions

- Each Gallery→Player open gets a unique `playbackSessionId`; `VideoPreviewScreen` explicitly remembers and disposes a `VideoPlayerViewModel` for that one composition. The stable file identity remains the key for playback-position persistence.
- Video open is immediate. `MediaAccessCoordinator` selects a verified complete local file only when identity and size checks pass; otherwise Media3 uses the TDLib range data source.
- Slider drags update local scrub preview state. Exactly one seek is committed from `onValueChangeFinished`; the time label follows the preview while dragging.
- Buffered seeks resolve directly to `Playing`; remote unbuffered seeks remain pending through `Seeking`/`Rebuffering` until playback resumes; paused seeks remain `Paused`.
- UI position updates run at 250 ms, while persistence is coalesced through `PlaybackPositionWriter` with a 5-second cadence and immediate snapshots on pause, seek completion, stop, Back, retry, authorization invalidation, and release.
- Gallery `LazyGridState` is owned by `AppNavigation` and saved outside the conditional player branch. Authorization/account identity changes clear the player request and close the active video coordinator.
- Room version 4 adds `expectedSizeBytes` with a non-destructive 3→4 migration. The migration test now verifies the complete 1→2→3→4 path and preserves sync state.
- Error classification inspects a bounded cause chain and ignores expected reader/navigation cancellation without exposing raw exception text or private paths.
- PlayerView is detached on AndroidView release; poster decoding runs on `Dispatchers.IO`; controls are disabled until the player is usable.

## Automated evidence

| Gate | Result | Evidence |
|---|---|---|
| Focused JVM tests | PASS | Playback rules, position writer, release guard, media access, and range coordinator tests |
| Full JVM unit suite | PASS | `:app:testDebugUnitTest -PtelegramDataSource=fake` |
| Connected Android tests | PASS | 22/22 on `Pixel_9_Pro` (API 36), including Room migration, rotation/Back, and five reopen/release sessions |
| Lint | PASS | `:app:lintDebug -PtelegramDataSource=fake`, zero lint errors |
| Fake debug APK | PASS | `:app:assembleDebug -PtelegramDataSource=fake` |
| Real-source debug APK | PASS | `:app:assembleDebug` without the fake-source property |
| Static diff checks | PASS | `git diff --check` |

## Real-device evidence

- Device: `emulator-5554` / `Pixel_9_Pro` API 36, online.
- The default real-source APK installed with `android run` using the existing package and launched `com.nmtuong.telegramdrive.MainActivity` successfully.
- `android layout --pretty` and `android screen capture` show the expected `Telegram sign in` / `Phone number` screen.
- Remote first frame, near-end seek, rapid seek, retry, background/foreground, rotation during real playback, local-preferred playback, and authenticated Gallery continuity remain `BLOCKED` until the user provides a Ready Telegram session.
- No private identity, file path, credential, OTP, token, or session data was logged.

## Remaining work required to mark COMPLETE

With a Ready session on `emulator-5554`, rerun the RT-01–RT-10 matrix from the objective and record sanitized first-frame/range/seek/retry/lifecycle/Gallery-continuity evidence. In particular, the real run must prove that remote range requests start before full download, the final rapid seek wins, released players/readers/coordinators return to zero, and a completed local file avoids TDLib range requests.

Until that user-controlled authentication step is available, the correct status is `BLOCKED`, not `PASS` or `COMPLETE`.
