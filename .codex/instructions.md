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

2. **Android CLI Utilization (`android-cli`)**:
   - **Documentation Lookup**: Use `android docs search "<query>"` to look up official Android & Jetpack Compose guidelines, best practices, and code examples from Google's Android Knowledge Base.
   - **Layout Hierarchy Inspection**: Use `android layout -p` when app is running on emulator/device to inspect the JSON tree structure of UI components.
   - **Visual Capture**: Use `android screen capture` to verify UI visually on a running device/emulator.
   - **Deploy & Execution**: Use `android run --debug` to build and launch APKs.
   - **SDK & AVD Management**: Use `android sdk` and `android emulator` commands to list/manage SDK components and virtual devices.

3. **Mandatory Verification Workflow**:
   - Before completing tasks, always run in `android-app/`:
     `./gradlew testDebugUnitTest lintDebug assembleDebug`
   - When runtime is available, verify app launch using `android run`, inspect tree with `android layout -p`, and capture visual proof via `android screen capture`.
