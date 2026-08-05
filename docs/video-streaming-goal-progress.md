# Video Streaming Goal Progress

## Status

`BLOCKED` for the goal’s final completion rule. The streaming-first implementation, automated tests, Android gates, and real-source launch are complete. On 2026-08-04, `emulator-5554` had a Ready session and a populated real Gallery. The source now contains a debug-only, aggregate-only ADB diagnostics receiver, but that new APK has not been installed under the instruction to preserve the existing session. The RT-01–RT-10 matrix cannot be completed or marked PASS honestly until an in-place update is explicitly authorized.

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
- The debug source set declares a broadcast receiver that logs a fixed-schema, aggregate numeric snapshot only. It has Reset and Dump actions for scenario boundaries; it is absent from release source sets and never serializes media, account, identity, caption, filename, path, token, or session fields.
- Rebuffer telemetry records one start-to-ready/error/stop/release interval at a time, so repeated buffering callbacks cannot inflate count or duration. Seek telemetry retains only the latest seek-to-resume latency and abandons it on error, stop, or release.

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
- Historical sign-in evidence above applied before 2026-08-04; the current app session is Ready and the real Gallery is populated.
- A first selected item reached the app's recoverable error UI; one Retry was invoked and returned to the same recoverable error state.
- A different selected item rendered video and accepted slider and separate-seek input. A 60-second observation window completed with the app process responsive.
- A Home/launcher re-entry returned to Gallery rather than attesting that the player route and position resumed. Temporary rotation settings were restored to their original values after the check.
- These observations are intentionally not reported as RT passes: the installed build did not have a metric export. The source patch now supplies one, but it remains uninstalled.
- No private identity, file path, credential, OTP, token, session data, filename, caption, chat title, or authenticated screenshot/layout dump is retained in the repository.

## Remaining work required to mark COMPLETE

Install the built debug APK only with the user's explicit confirmation that an in-place update preserving app data/session is authorized. Then invoke the debug-only Reset and Dump broadcast actions through ADB and capture only `VideoStreamDiagnostics` aggregate lines. Rerun RT-01–RT-10 and record sanitized first-frame/range/seek/retry/lifecycle/Gallery-continuity evidence. In particular, the real run must prove that remote range requests start before full download, the final rapid seek wins, released players/readers/coordinators return to zero, and a completed local file avoids TDLib range requests.

Until that diagnostic surface is available on the running app, the correct status is `BLOCKED`, not `PASS` or `COMPLETE`.

## Current hardening round — 8104b30

Status at the start of this hardening round: `BLOCKED — AUTHENTICATED_SESSION_REQUIRED`. This historical auth blocker was cleared on 2026-08-04; the current blocker is the lack of an installed-build diagnostics surface.

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
| RT-01 — selected recoverable-error item | Not available | Not available | Not available | Not available | Not available | Not available |
| RT-02 — playable item, 60-second observation | Not captured | Not available | Not available | Not available | Not available | Not available |
| RT-03–RT-06 — seek/control interactions | Not applicable | Not available | Not available | Not available | Not available | Not available |
| RT-07–RT-10 — retry/lifecycle/Gallery matrix | Not available | Not available | Not available | Not available | Not available | Not available |

Debug/test diagnostics now expose only opaque session and aggregate player, coordinator, reader, seek count/latest seek-to-resume latency, range, byte, first-frame, rebuffer count/duration, and position-write counters. In debug builds, `VideoStreamDiagnostics` emits the same fixed-schema numeric aggregate line on an explicit ADB Reset or Dump action. They never retain filenames, captions, paths, chat IDs, stable identity values, tokens, or session data.

## Resource lifecycle

| Scenario | Players after | Readers after | Coordinators after |
|----------|---------------|---------------|--------------------|
| Deterministic range-read cleanup | N/A | 0 | 0 |
| Real Back/reopen matrix | Not available | Not available | Not available |

## Controls verification

| Scenario | Expected | Actual | Result |
|----------|----------|--------|--------|
| Playing policy | Auto-hide eligible; tap policy enabled | Virtual-time physical taps restore then hide controls across ten cycles | PASS |
| Paused policy | Does not auto-hide | Verified by deterministic policy tests | PASS |
| Opening/Fatal error policy | No seek gesture surface; Retry/Back accessible for recoverable errors | Verified by deterministic policy and Compose action tests | PASS |
| Real hide/show and playback controls | Full interactive matrix | Interactions were attempted; source has an aggregate receiver but the session-preserving update is not authorized yet | NOT ATTESTED |

## Gallery continuity

| Query/filter | Anchor before | Anchor after | Result |
|--------------|---------------|--------------|--------|
| Fake fixture catalog | `fixture-video-120.mp4` after an actual deep grid traversal | Same fixture is visible after player rotation and Back | PASS (connected test) |
| Real Gallery | Real data present; no private anchor retained | Not attested | DIAGNOSTICS / PRIVACY EVIDENCE GAP |

## Current-round automated evidence

| Command | Result | Duration |
|---------|--------|----------|
| `:app:testDebugUnitTest -PtelegramDataSource=fake` | PASS | 7s |
| `:app:connectedDebugAndroidTest -PtelegramDataSource=fake` | PASS, 26/26 | 44s |
| `:app:lintDebug -PtelegramDataSource=fake` | PASS | 33s |
| `:app:assembleDebug -PtelegramDataSource=fake` | PASS | 7s |
| `:app:assembleDebug` | PASS | 14s |

## Post-diagnostics source validation — 2026-08-04

| Command / check | Result | Duration |
|---|---|---|
| `:app:testDebugUnitTest -PtelegramDataSource=fake` | PASS, 162 tests / 0 failures / 0 errors; includes aggregate payload, rebuffer duration, and seek-to-resume tests | 19s |
| `:app:lintDebug -PtelegramDataSource=fake` | PASS, zero lint issues | 26s |
| `:app:assembleDebug -PtelegramDataSource=fake` | PASS | 7s |
| `:app:assembleDebug` | PASS | 13s |
| Debug/release merged manifest inspection | PASS, receiver present in debug and absent from release | 5s release-manifest task |
| `:app:connectedDebugAndroidTest -PtelegramDataSource=fake` | NOT RUN in this round: it may replace the authenticated real app on the only scoped emulator | N/A |

## Current hard blocker

- Device: `emulator-5554`, online; the existing real app session is Ready and its Gallery has real data.
- The new source exposes `VideoStreamingDiagnostics` only through a debug-source-set receiver, emitting fixed-schema aggregate metrics to the `VideoStreamDiagnostics` log tag. Its Reset and Dump actions are `com.nmtuong.telegramdrive.debug.action.RESET_VIDEO_STREAMING_DIAGNOSTICS` and `com.nmtuong.telegramdrive.debug.action.DUMP_VIDEO_STREAMING_DIAGNOSTICS`.
- The receiver is not installed on `emulator-5554`; installing it without explicit approval could endanger the authenticated session. Therefore the real matrix still cannot prove remote-vs-local selection, first-frame timing, ranges/bytes, retry release/rebind, seek coalescing, or zero active player/reader/coordinator counts.
- Required user action: explicitly authorize an in-place update that preserves the app data/session. Do not provide credentials to the agent.
