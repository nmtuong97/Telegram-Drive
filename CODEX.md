# CODEX — Project Instructions & Android CLI Guidelines

Refer to [.codex/instructions.md](file://.codex/instructions.md) and [AGENTS.md](file://AGENTS.md) for full AI agent guidelines.

## Quick Summary for Codex:
- **Language**: All output, docs, comments to user, and commit messages in Vietnamese. Code in English.
- **Android Path**: `android-app/` (Clean Architecture with Jetpack Compose + Kotlin DSL).
- **Android CLI Usage**:
  - `android docs search "<query>"` and `android docs fetch "kb://..."` for official API lookup.
  - `android layout --pretty --output=<file.json>` for inspecting UI layout hierarchy.
  - `android screen capture --output=<file.png>` for visual screenshot verification (do not use `android screenshot`).
  - `android run --apks=<path>` after building APK for deploying app.
- **Verification**: Run bounded per-task commands (`testDebugUnitTest`, `lintDebug`, `assembleDebug`) before handoff.
