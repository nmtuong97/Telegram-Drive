# Video Streaming Goal Progress

## Status

`BLOCKED` for the goal’s final completion rule. The streaming-first implementation, automated tests, Android gates, and real-source launch are complete. The authenticated real Telegram runtime matrix cannot be completed because `emulator-5554` has no Ready session; no credentials, OTP, logout, app-data reset, or session mutation was performed.

## Baseline and scope

- Branch: `agent/android-streaming-video-goal`
- Required baseline: `8104b30f334c82bc1871f581583d5fe1eeed3c33`
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
- Gallery anchor index and pixel offset are captured synchronously before opening media, persisted outside the player branch, and replayed after the paging source has enough items. Authorization/account identity changes clear the player request and close the active video coordinator.
- `VideoPlayerViewModel` owns a small Media3 engine boundary and account-scoped gateway, preserving the production Media3/TDLib path while allowing deterministic lifecycle, seek, retry, and stale-callback tests without a fake player in production.
- Room version 4 adds `expectedSizeBytes` with a non-destructive 3→4 migration. The migration test now verifies the complete 1→2→3→4 path and preserves sync state.
- Error classification inspects a bounded cause chain and ignores expected reader/navigation cancellation without exposing raw exception text or private paths.
- PlayerView is detached on AndroidView release; poster decoding runs on `Dispatchers.IO`; controls are disabled until the player is usable.

## Automated evidence

| Gate | Result | Evidence |
|---|---|---|
| Focused JVM tests | PASS | Playback rules, position writer, release guard, media access, and range coordinator tests |
| Full JVM unit suite | PASS | `:app:testDebugUnitTest -PtelegramDataSource=fake` |
| Connected Android tests | PASS | 26/26 on `Pixel_9_Pro` (API 36), including virtual-time physical controls, error actions, 120-item Gallery rotation/Back, Room migration, and five reopen/release sessions |
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

## Current hardening round — 8104b30

Status: `BLOCKED — AUTHENTICATED_SESSION_REQUIRED`.

This round started clean on `agent/android-streaming-video-goal` at
`8104b30f334c82bc1871f581583d5fe1eeed3c33`. The earlier entries above are
historical evidence and are not used as results for this round.

## Final hardening fixes

| Finding | Root cause | Fix | Automated test | Real evidence |
|---------|------------|-----|----------------|---------------|
| Controls did not reliably reappear after auto-hide | The long-lived gesture detector captured a delegated Boolean instead of reading its current state | The handler reads `rememberUpdatedState(...).value` at tap time while preserving its active pointer input | Virtual-time physical-tap test covers ten hide/show cycles; recoverable Retry and Back actions are also exercised | Blocked: no Ready session |
| Invalid overlays accepted player gestures | Gesture surface was installed independently of playback phase | Explicit playback/seek gesture policies install the surface only for valid phases | Playback-policy tests reject Opening, buffering, error, and closed seek gestures | Blocked: no Ready session |
| Retry could be invoked from a stale error surface | Retry did not validate its phase | Retry now accepts only `RecoverableError` | Playback-policy test | Blocked: no Ready session |
| Transient identity resolution discarded a valid restored request | `null` identity was treated as a confirmed mismatch | Route holds the request while identity is unresolved and restores only after a matching identity is resolved | `PlaybackRestorationPolicyTest` covers match, temporary null, mismatch, and non-Ready authorization | Blocked: no Ready session |
| Failed position write could leave later snapshots stranded | Writer drain exited on a persistence exception | Drain absorbs one non-cancellation failure and continues without an unbounded retry loop | `PlaybackPositionWriterTest` | Not applicable to sign-in screen |
| Deep Gallery anchor reset after player rotation | Paging's initial loaded window did not always include the saved grid anchor | Navigation persists index/offset and explicitly restores once the paged item count reaches the anchor | `FakeVideoPlayerUiTest` traverses to `fixture-video-120.mp4`, opens, rotates, backs out, and confirms the same anchor | Blocked: no Ready session |

## Streaming metrics

| Scenario | First frame | Bytes | Ranges | Seek latency | Rebuffers | Position writes |
|----------|-------------|-------|--------|--------------|-----------|-----------------|
| RT-01–RT-10 real Telegram | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED |

Debug/test diagnostics now expose only opaque session and aggregate player, coordinator, reader, seek, range, byte, first-frame, rebuffer, and position-write counters. They never retain filenames, captions, paths, chat IDs, stable identity values, tokens, or session data.

## Resource lifecycle

| Scenario | Players after | Readers after | Coordinators after |
|----------|---------------|---------------|--------------------|
| Deterministic range-read cleanup | N/A | 0 | 0 |
| Real Back/reopen matrix | BLOCKED | BLOCKED | BLOCKED |

## Controls verification

| Scenario | Expected | Actual | Result |
|----------|----------|--------|--------|
| Playing policy | Auto-hide eligible; tap policy enabled | Virtual-time physical taps restore then hide controls across ten cycles | PASS |
| Paused policy | Does not auto-hide | Verified by deterministic policy tests | PASS |
| Opening/Fatal error policy | No seek gesture surface; Retry/Back accessible for recoverable errors | Verified by deterministic policy and Compose action tests | PASS |
| Real hide/show and playback controls | Full interactive matrix | Requires Ready Telegram session | BLOCKED |

## Gallery continuity

| Query/filter | Anchor before | Anchor after | Result |
|--------------|---------------|--------------|--------|
| Fake fixture catalog | `fixture-video-120.mp4` after an actual deep grid traversal | Same fixture is visible after player rotation and Back | PASS (connected test) |
| Real Gallery | BLOCKED | BLOCKED | AUTHENTICATED_SESSION_REQUIRED |

## Current-round automated evidence

| Command | Result | Duration |
|---------|--------|----------|
| `:app:testDebugUnitTest -PtelegramDataSource=fake` | PASS | 7s |
| `:app:connectedDebugAndroidTest -PtelegramDataSource=fake` | PASS, 26/26 | 44s |
| `:app:lintDebug -PtelegramDataSource=fake` | PASS | 33s |
| `:app:assembleDebug -PtelegramDataSource=fake` | PASS | 7s |
| `:app:assembleDebug` | PASS | 14s |

## Current hard blocker

- Device: `emulator-5554`, online.
- Real-source package was absent, so the real-source debug APK was installed and launched without clearing data, logging out, or resetting the emulator.
- `android layout --pretty` reported `Telegram sign in`, `Phone number`, and `Continue`; the matching sanitized screenshot contains no entered value or private account data.
- Required user action: authenticate the app to Telegram on `emulator-5554`, then return to this goal to run RT-01–RT-10. Do not provide credentials to the agent.
