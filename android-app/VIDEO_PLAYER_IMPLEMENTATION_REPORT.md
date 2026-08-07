# Video Player Implementation Report

## Phase A — Verify current player

*   **Player Lifecycle & Ownership**: `VideoPreviewScreen` manages the `VideoPlayerViewModel` instance using `remember(request, playbackSessionId)`. To survive configuration changes (e.g., orientation), `viewModel()` was used instead of `remember`, retaining the `VideoPlayerViewModel` when rotating.
*   **Keep Screen On**: Implemented `KeepScreenOn` effect in `VideoPreviewScreen` that watches the playing state.
*   **Media3 Audio Focus**: Updated `Media3VideoPlayerEngine` to set `AudioAttributes` asking for audio focus.

## Phase B — Player foundation

Removed the `DisposableEffect` that blindly closed playback on `onDispose` (which fired during orientation changes). Replaced with a targeted `BackHandler` logic and relying on ViewModel's `onCleared()` for resource disposal, which aligns with standard Android lifecycle and ownership.

## Phase C — Gesture system

Implemented `VideoGestureLayer` using Compose `pointerInput`.
-   **Double Tap Accumulation**: Uses a timeout/debounce mechanic to sum `-10s` and `+10s` requests before hitting the player `seekBy()`. The logic for the accumulation boundary was extracted to `GestureUtils.kt` and has full unit test coverage.
-   **Drag Gestures**: Dragging the left side alters screen brightness and dragging the right side alters media volume. Volume and brightness updates apply localized changes (current window or stream) to avoid breaking system-level states.
-   **Gestures Stability**: Fixed `controlsVisible` staleness using `rememberUpdatedState` to ensure reliable single-tap hiding.

## Phase D — Orientation/Fullscreen

-   Added an orientation toggle button to `TopControls`.
-   Orientation changes properly flip the requested orientation using `Activity.requestedOrientation`.
-   Fullscreen `WindowInsetsControllerCompat` API hides/shows system bars conditionally.
-   `VideoPreviewScreen` records its initial orientation when launched. Upon exiting the screen (via `BackHandler`), it restores the Activity to that original orientation to avoid leaking orientation state to the rest of the application.

## Tests
-   Executed `app:lintDebug` with zero actionable warnings.
-   Executed `app:testDebugUnitTest`, all passing, including the new `GestureUtilsTest` for boundary edge cases in accumulation logic.
