# Phase 2 Evidence Manifest — SHA `f9159e8822bcb884a0d5bf2b218acb423d221e19`

## Environment & Build Info

- **Repository**: `nmtuong97/Telegram-Drive`
- **Branch**: `agent/android-phase-2`
- **Final Committed SHA**: `f9159e8822bcb884a0d5bf2b218acb423d221e19`
- **Android CLI Version**: `1.0.15498356`
- **Target Device / Emulator**: `emulator-5554` (`Pixel_9_Pro`)
- **Gradle Tasks Executed**:
  1. `./gradlew :app:compileDebugKotlin` (Duration: 5s, Exit code: 0)
  2. `./gradlew :app:compileDebugUnitTestKotlin` (Duration: 5s, Exit code: 0)
  3. `./gradlew :app:testDebugUnitTest` (Duration: 6s, Exit code: 0, 100 tests passed)
  4. `./gradlew :app:lintDebug` (Duration: 17s, Exit code: 0)
  5. `./gradlew :app:assembleDebug -PtelegramDataSource=fake` (Duration: 6s, Exit code: 0)

## Targeted Critical Unit Tests

| Test Class | Duration | Result | Exit Code |
| :--- | :--- | :--- | :--- |
| `com.nmtuong.telegramdrive.telegram.TdLibLifecycleTest` | 6s | PASSED | 0 |
| `com.nmtuong.telegramdrive.telegram.LogoutResetTest` | 6s | PASSED | 0 |
| `com.nmtuong.telegramdrive.telegram.TdLibJsonGatewayTest` | 6s | PASSED | 0 |
| `com.nmtuong.telegramdrive.data.TransferCoordinatorTest` | 5s | PASSED | 0 |
| `com.nmtuong.telegramdrive.feature.library.DownloadCoordinatorTest` | 6s | PASSED | 0 |
| `com.nmtuong.telegramdrive.data.TdLibPagingSourceTest` | 6s | PASSED | 0 |
| `com.nmtuong.telegramdrive.feature.library.LibraryViewModelTest` | 6s | PASSED | 0 |
| `com.nmtuong.telegramdrive.data.PhaseTwoCPTests` | 10s | PASSED | 0 |

## Android CLI Journey Assertions

1. **Journey A — Fake Authorization**:
   - Phone "+1234567890" submitted -> Transitioned to `Authentication code`.
   - Code "12345" submitted -> Transitioned to `Two-step verification password`.
   - Password "password" submitted -> Transitioned to `Saved Messages` screen.

2. **Journey B — Saved Messages Paging**:
   - `Saved Messages` checked source displayed with paging list (`mountain.jpg`, `demo.mp4`, `mountain-duplicate.jpg`, `trailer.mp4`, `notes.txt`).

3. **Journey C — Image Download & Preview**:
   - `mountain.jpg` (fileId=100) download initiated -> state changed to `Completed`.
   - Action button updated from "Download" to "Preview".
   - Preview verified; duplicate item (`mountain-duplicate.jpg`, fileId=100) automatically reflected `Completed` state.

4. **Journey D — Video Download & Preview**:
   - `trailer.mp4` download initiated -> state changed to `Completed`.
   - Action button updated from "Download" to "Preview".

5. **Journey E — Logout / Reset**:
   - Log out button triggered.
   - Active transfers cancelled, database generation invalidated, session closed.
   - Returned to clean `Telegram sign in` screen.
