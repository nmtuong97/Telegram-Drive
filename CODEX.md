# CODEX — Project Instructions & Android CLI Guidelines

Refer to [.codex/instructions.md](file://.codex/instructions.md) and [AGENTS.md](file://AGENTS.md) for full AI agent guidelines.

## Quick Summary for Codex:
- **Language**: All output, docs, comments to user, and commit messages in Vietnamese. Code in English.
- **Android Path**: `android-app/` (Clean Architecture with Jetpack Compose + Kotlin DSL).
- **Android CLI Usage**:
  - `android docs search "<query>"` for official API lookup.
  - `android layout -p` for inspecting UI layout hierarchy.
  - `android screen capture` for visual screenshot verification.
  - `android run --debug` for deploying app.
- **Verification**: Run `./gradlew testDebugUnitTest lintDebug assembleDebug` before handoff.
