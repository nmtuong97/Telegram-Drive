# Video Player Implementation Report

## Phase A — Verify current player & ViewModel Ownership

*   **Player Lifecycle & Ownership**: `AppNavigation.kt` manages video presentation via `videoRequest != null`. When a request is active, it calls `VideoPreviewScreen`. `VideoPreviewScreen` initializes the `VideoPlayerViewModel` using Compose's `viewModel(key = ..., factory = ...)`.
*   **ViewModelStoreOwner**: Since `AppNavigation` runs inside the main Activity and is not using a custom `NavHost` with destination-scoped backstacks (there's just `if/else` branching for the screen states like `activeVideoRequest != null`), the current `LocalViewModelStoreOwner` inside `VideoPreviewScreen` resolves to the `ComponentActivity` itself.
*   **Implication**: Because the ViewModel is Activity-scoped, it correctly survives configuration changes. However, when the user presses back (`videoRequest = null`), the `VideoPreviewScreen` is removed from the composition, but the Activity-scoped ViewModel is *not* automatically cleared by the system (because the Activity is still alive).
*   **Fix Implementation**: To ensure the playback resources don't leak or continue playing in the background when the user closes the video, the `BackHandler` inside `VideoPreviewScreen` explicitly calls `owner.closePlayback()`, which tears down the player and releases the TDLib streaming session before the screen leaves composition. This successfully prevents resource accumulation between multiple `playbackSessionId`s while preserving the ability to naturally survive configuration changes.

## Phase B — Player foundation

- Keep-Screen-On works natively via `DisposableEffect`.
- Audio focus requests are dispatched correctly via ExoPlayer configurations in `VideoPlaybackEngine`.

## Phase C — Gesture system

Implemented `VideoGestureLayer` using Compose `pointerInput`.
-   **Double Tap Accumulation**: The UI accumulates `-10s` and `+10s` with a timeout/debounce before committing to `seekTo()`. Extracted the boundary reset and direction logic to `GestureUtils.calculateSeekAccumulation`.
-   **Drag Gestures**: Defined pure drag actions based on left/right screen position. The brightness calculation falls back to using `android.provider.Settings.System.SCREEN_BRIGHTNESS` rather than defaulting to `0.5f` when `window.attributes.screenBrightness` is negative.
-   **Gestures Stability**: Used `rememberUpdatedState` to ensure `controlsVisible` is correctly read inside the gesture block without needing to restart the gesture listeners upon toggling.

## Phase D — Orientation/Fullscreen

-   Orientation button acts as a toggle between landscape and portrait.
-   Fullscreen `WindowInsetsControllerCompat` handles immersive display in Landscape.
-   `VideoPreviewScreen` uses `rememberSaveable` to record its initial orientation when first presented, ensuring that orientation leaks are avoided and proper orientation restores happen when exiting the player—even across multiple recreations.

## Tests
-   Reverted accidental formatting applied to `app/src-tauri` Rust files ensuring zero out-of-scope desktop modifications.
-   Verified math via `GestureUtilsTest` for pure logic.
-   `app:lintDebug` passes with no new actionable warnings.
-   `app:testDebugUnitTest` passes.
