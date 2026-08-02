# Codex System Instructions — Telegram Drive

## Communication Language
All responses, documentation, analysis reports, and commit messages MUST be in Vietnamese.
Code symbols (variables, functions, classes, file names) must remain in English.

## Android App Development (`android-app/`) & Android CLI Integration

When working on the standalone Android app located in `android-app/`:

1. **Architecture & Stack**:
   - Language/Framework: Kotlin, Jetpack Compose, Kotlin DSL, minSdk 26.
   - Package: `com.nmtuong.telegramdrive`.
   - Clean Architecture: UI/Feature -> Repository -> Gateway.
   - Do NOT import `org.drinkless.tdlib` into UI or Domain layers.
   - Keep backup & device transfer disabled for account, TDLib session, database, and downloaded files.
   - Phase 2 scope: Saved Messages Paging → Download → Preview → Logout/Reset.

2. **Android CLI Utilization (`android-cli`)**:
   - **Documentation Lookup**: Use `android docs search "<query>"` to search official guidelines. Use `android docs fetch "kb://..."` to read results. Do not pass arbitrary URLs.
   - **Layout Hierarchy Inspection**: Use `android layout --pretty --output=<file.json>` when app is running on emulator/device to inspect tree structure.
   - **Visual Capture**: Use `android screen capture --output=<file.png>` to verify UI visually (do not use `android screenshot`).
   - **Deploy & Execution**: Build APK using `./gradlew :app:assembleDebug -PtelegramDataSource=fake`, get path via `android describe --project_dir=android-app`, then deploy using `android run --apks=<path>`.
   - **SDK & AVD Management**: Use `android sdk` and `android emulator` commands to list/manage SDK components and virtual devices.

3. **Mandatory Verification Workflow**:
   - Do not run unbounded combined commands.
   - Run bounded per-task execution in `android-app/`:
     - `./gradlew :app:testDebugUnitTest --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
     - `./gradlew :app:lintDebug --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
     - `./gradlew :app:assembleDebug -PtelegramDataSource=fake --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
   - When runtime is available, verify app launch using `android run --apks=...`, inspect tree with `android layout`, and capture visual proof via `android screen capture`.
