# Video Streaming Goal Progress

## Baseline
- Branch: `agent/android-streaming-video-goal`
- Commit: `98880194b2a904cd56ecc7e5081bc209dab5e1d7` (`main` was clean at start)
- Device: `emulator-5554`; fake runtime evidence collected, real-session runtime blocked at sign-in
- Existing behavior: Gallery waited for a fixed 512 KiB video prefix via `MediaAccessCoordinator.prepareVideo()` before opening `VideoPreviewScreen`; preview did not explicitly autoplay.
- Known failures: Player UI was a `Column` with inline status text; retry/recomposition did not reliably rebind `PlayerView`; navigation, Gallery grid state, and playback position were not lifecycle-owned persistence.
- Tooling note: GitNexus was 29 commits behind the baseline and indexed another branch; current-source impact checks were supplemented with codebase-memory graph results.

## Decisions
- Decision: Use a stable `VideoPlaybackRequest`, navigate immediately, and let the Player owner choose a verified complete local file or TDLib range streaming.
- Alternatives considered: Keep the 512 KiB prefetch gate; use path-only routing; upgrade Media3 dependencies.
- Evidence: `GalleryViewModel.openMedia()` now opens videos synchronously into a saved request; `VideoPlayerViewModel` validates source selection before Media3 preparation.
- Trade-offs: Adds request/state plumbing, but removes fixed startup gating and makes retry, rotation, and position restoration lifecycle-aware.
- Decision: Persist playback position in Room under account, database-generation, and stable-file identity.
- Alternatives considered: Process-local map; path-only preferences; another storage dependency.
- Evidence: `MediaDatabase` version 3 adds `playback_position` with a 2→3 migration; account cleanup deletes these rows.
- Trade-offs: Durable across recreation while remaining isolated from another Telegram account/session generation.

## Acceptance Criteria
| ID | Status | Evidence | Notes |
|----|--------|----------|-------|
| AC-01 | PASS (code) | Immediate video handoff in `GalleryViewModel`; fake Gallery→Player evidence | Real remote runtime blocked by auth state |
| AC-02 | PARTIAL | Poster path/minithumbnail code and opening screenshot | Fake catalog has no thumbnail metadata, so screenshot is black poster |
| AC-03 | PASS (code/fake) | `playWhenReady = true`; fake Player reaches ready/ended state | Real remote playback not verified |
| AC-04 | PASS (code) | Loading/Opened guard in `GalleryViewModel` | No dedicated double-tap runtime trace |
| AC-05 | PASS (code) | Stable request route replaces prior request | No dedicated replacement runtime trace |
| AC-06–AC-15 | PARTIAL | Existing range/seek/EOF/shared-reader tests PASS; local-source rules and main-thread guard tests PASS | Remote, near-end, rapid-seek and real network runtime remain blocked |
| AC-16–AC-24 | PARTIAL | ViewModel owns player/retry/release/lifecycle/Room position; error classification and retryability tests PASS | Retry, background and position restoration need an authenticated runtime session |
| AC-25–AC-28 | PARTIAL | `LazyGridState.Saver`, saved request route, and new fake Compose UI Back/rotation test PASS | Deep-scroll/search/filter continuity not fully exercised |
| AC-29–AC-35 | PASS (code/fake) | Edge-to-edge black overlay, controls, semantics, localized strings, fake portrait/landscape layouts | Raw exception/private data not rendered; real media not available |
| AC-36–AC-40 | PASS | Unit tests, 21 instrumentation tests, lint, and debug APK build PASS | First lint run found and was fixed for opt-in/translations |
| AC-41 | BLOCKED | Final real APK launch evidence shows `Telegram sign in` / `Phone number` | Emulator has no Ready real TDLib session; credentials must not be entered by agent |
| AC-42 | PASS | Only `install -r`/`android run`; no logout, app-data clear, uninstall, reset, or session mutation | Fake auth used only on fake build with non-real values |
| AC-43 | PARTIAL | Self-review and `git diff --check` clean; no new P0/P1 code issue found | Mandatory real-session matrix is still incomplete |

## Test Runs
| Command | Result | Duration | Notes |
|---------|--------|----------|-------|
| `./scripts/run-gradle-single-flight.sh :app:testDebugUnitTest --tests com.nmtuong.telegramdrive.data.video.VideoStreamingCoordinatorTest --tests com.nmtuong.telegramdrive.data.MediaAccessCoordinatorTest --tests com.nmtuong.telegramdrive.VideoPlayerReleaseGuardTest --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace` | PASS | 18s | Baseline focused suites |
| `./scripts/run-gradle-single-flight.sh :app:compileDebugKotlin -PtelegramDataSource=fake --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace` | PASS | 23s | Post-change compile |
| `./scripts/run-gradle-single-flight.sh :app:testDebugUnitTest ... --tests com.nmtuong.telegramdrive.feature.preview.VideoPlaybackRulesTest ...` | PASS | 17s | Focused streaming/release/media-access/resume tests |
| `./scripts/run-gradle-single-flight.sh :app:testDebugUnitTest -PtelegramDataSource=fake --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace` | PASS | 11s | Final full unit suite |
| `./scripts/run-gradle-single-flight.sh :app:connectedDebugAndroidTest -PtelegramDataSource=fake --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace` | PASS | 32s | 21 instrumentation tests passed, including fake Player semantics/rotation/Back UI test |
| `./scripts/run-gradle-single-flight.sh :app:lintDebug -PtelegramDataSource=fake --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace` | PASS | 41s | Final lint; 34 pre-existing warnings remain, zero lint errors |
| `./scripts/run-gradle-single-flight.sh :app:assembleDebug --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace` | PASS | 23s | Final default/real-source debug APK |
| Same full unit command after final local-candidate guard | PASS | 18s | Final source-selection guard regression check |
| Same lint command after final local-candidate guard | PASS | 34s | Final lint after last source-selection change |
| Same assemble command after final local-candidate guard | PASS | 16s | APK reinstalled on emulator via `android run`; installed build is real/default source |
| Focused tests after error/source-selection rules | PASS | 16s | Range, MediaAccessCoordinator, and playback-rule tests |
| Final full unit suite after error/source-selection rules | PASS | 8s | All JVM unit tests |
| First run with new Compose UI test | FAIL then fixed | 37s | Test exposed wrong-thread Back invocation; test now dispatches Back on UI thread |
| Final connected instrumentation after UI-test fixes | PASS | 32s | 21/21 tests on `emulator-5554` |
| Final lint after UI-test addition | PASS | 16s | Zero lint errors |
| Final default assemble after UI-test addition | PASS | 8s | Real/default APK restored on emulator |
| First post-change lint run | FAIL then fixed | 46s | Found Media3 opt-in and missing Vietnamese player translations; no failure hidden |
| Final full unit suite after pause persistence and UI polish | PASS | 18s | All JVM unit tests after the last source/UI changes |
| Final connected instrumentation after pause persistence and UI polish | PASS | 32s | 21/21 tests on `emulator-5554` |
| Final lint after pause persistence and UI polish | PASS | 34s | No warnings in the changed player files; remaining warnings are pre-existing/unrelated |
| Final default assemble after pause persistence and UI polish | PASS | 15s | Real/default APK restored on emulator after the last source/UI changes |

## Runtime Evidence
| Scenario | Result | Evidence | Metrics |
|----------|--------|----------|---------|
| Fake auth → Gallery | PASS (supplementary) | `docs/evidence/video-streaming-goal-fake-gallery.png`, matching layout JSON | UI/layout only |
| Fake Gallery → Player opening/ready | PASS (supplementary) | `video-streaming-goal-player-opening.png`, `video-streaming-goal-player-ready.png`, layout JSON | 0:01/0:01 fake asset; no real remote latency metric |
| Fake portrait/landscape Player | PASS (supplementary) | `video-streaming-goal-player-landscape.png`, layout JSON | Route/control semantics retained |
| Fake Player Back → Gallery | PASS (supplementary) | `video-streaming-goal-fake-gallery-after-back.png`, layout JSON | Gallery title/items restored |
| Fake Compose UI Player semantics/rotation/Back | PASS (supplementary) | `FakeVideoPlayerUiTest` instrumentation result | 21/21 instrumentation tests passed |
| Final real-source APK launch | BLOCKED | `docs/evidence/video-streaming-goal-real-launch-final.png`, matching layout JSON | Freshly reinstalled default APK; `Telegram sign in`/`Phone number`; no authenticated Ready session |
| RT-01–RT-10 real matrix | BLOCKED/PENDING | Cannot open a real video without the existing session becoming Ready | Tap-to-first-frame, seek latency, range offsets and retry metrics not measured |

## Remaining Risks
- Real remote playback, near-end/rapid seek, network interruption/retry, background resume, completed-local fallback, and full Gallery continuity still require a Ready session on `emulator-5554`.
- `TdLibVideoDataSource.read()` remains a blocking Media3 `DataSource` API bridge, but it now rejects main-thread calls and the Android lifecycle test proves the guard; production remote timing is not measured here.
- No performance baseline/after numbers were collected because real media could not be opened; no private file paths, titles, credentials, or session data were logged.
