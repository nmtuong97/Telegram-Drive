# Video Player Implementation Report

## Phase A — Verify current player

*   **Player Lifecycle & Ownership**: `VideoPreviewScreen` manages the `VideoPlayerViewModel` instance using `remember(request, playbackSessionId)`. The ViewModel is *not* stored in the Activity's `ViewModelStore`. This means on configuration change (like screen rotation without `configChanges`), the `AppNavigation` will recompose, the `remember` block will re-evaluate, but since `request` and `playbackSessionId` are `rememberSaveable` in `AppNavigation`, they *survive*. However, the *ViewModel instance itself* (created by `remember`) will be destroyed and recreated because it's not a `rememberSaveable` or a true `viewModel()` bound to the Activity/NavBackStackEntry. Wait, actually, `VideoPreviewScreen` currently does:
    ```kotlin
    val owner = viewModel ?: remember(request, playbackSessionId) {
      VideoPlayerViewModel(request, mediaAccess)
    }
    ```
    If the screen rotates and the Activity is recreated, the `remember` block runs again, creating a *new* `VideoPlayerViewModel`. This will cause the player to re-initialize and lose its current playing state (it will resume from the last saved position which might be up to 5s old).
    *Decision*: To allow orientation changes without interrupting playback, we have two options:
    1.  Use `androidx.lifecycle.viewmodel.compose.viewModel` in `VideoPreviewScreen` with a custom key (like `playbackSessionId`), so the ViewModel survives Activity recreation.
    2.  Use `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"` in `AndroidManifest.xml` so the Activity is not recreated.
    Given the constraints to use the smallest possible architectural change and the explicit instruction "Trước tiên hãy kiểm tra lifecycle/player ownership hiện tại. Player phải có thể survive configuration changes nhờ lifecycle/ViewModel/engine architecture đúng nếu architecture hiện tại cho phép", option 1 is the most "Android-idiomatic" way for Compose. If we change `remember` to `viewModel(key = playbackSessionId.toString(), factory = VideoPlayerViewModel.Factory(...))`, the ViewModel will survive configuration changes automatically.
*   **Keep Screen On**: Currently not implemented. `VideoPreviewScreen` has no `DisposableEffect` manipulating window flags.
*   **Media3 Audio Focus**: `Media3VideoPlayerEngine` builds `ExoPlayer` via `ExoPlayer.Builder(context).build()`. It does not call `setAudioAttributes(..., true)`.
*   **Current Gestures**: Only single tap (toggle controls) and double tap (seek +- 10s via `onSeekBy`) are implemented in `VideoGestureLayer` using `detectTapGestures`. There is no visual feedback, no brightness/volume controls, and no gesture conflict resolution beyond what `detectTapGestures` provides.

## Phase B — Player foundation

I will update `VideoPlayerViewModel` creation to use the `viewModel()` composable. I will add `setAudioAttributes(AudioAttributes.DEFAULT, true)` to ExoPlayer. I will implement `KeepScreenOn` effect in `VideoPreviewScreen`. I will improve the buffering/loading UX.

## Phase C — Gesture system

I will replace `VideoGestureLayer` with a more robust implementation that handles:
-   Double tap accumulation state (using a local Compose state and a debounce `LaunchedEffect` that calls `viewModel.seekBy`).
-   Drag detection for brightness/volume using `detectDragGestures` with appropriate thresholds.
-   Visual overlays for double-tap seek, brightness, and volume.

## Phase D — Orientation/fullscreen

I will add an orientation toggle button to `TopControls`. I will use `Activity.requestedOrientation` to lock/unlock orientation. Since the ViewModel will now survive config changes, the player will not be interrupted.


## Phase C — Gesture system
Implemented a comprehensive gesture layer:
- Double tap accumulates seek state (in 10s increments) with visual feedback.
- Vertical swipe controls brightness (left half) and volume (right half).
- Swipe changes are localized to the app's window (brightness) and music stream (volume).
- Tap to toggle controls is preserved and robust against conflicting drags (using a threshold).

## Phase D — Orientation/Fullscreen
- Added a toggle orientation button in `TopControls`.
- Handled rotation without resetting playback because `VideoPlayerViewModel` is retained across configuration changes via `viewModel()`.
