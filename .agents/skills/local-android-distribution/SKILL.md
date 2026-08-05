---
name: local-android-distribution
description: Build, validate, and upload the real Android debug APK to Firebase App Distribution for Telegram-Drive handoff tasks. Use after implementation work is ready for device verification, when the user asks to distribute a build, or when a real Telegram login build is required.
---

# Local Android Distribution

Use the repository-owned distribution script as the single handoff path. The script runs from any working directory, validates local Firebase and Telegram configuration, runs Gradle through the single-flight guard, creates release notes, uploads the real APK, and prints `READY_FOR_DEVICE_VERIFICATION` only after Firebase distribution succeeds.

## Handoff workflow

1. Treat read-only investigation and an explicit user request not to upload as exceptions; otherwise run distribution after implementation changes are ready for device verification.
2. Check that no Gradle or distribution process is active before starting.
3. Run the full real workflow from the repository root:

   ```bash
   TELEGRAM_DATA_SOURCE=real ./scripts/distribute-local.sh "<task name>"
   ```

4. Use `--fast` only for an explicitly requested small UI iteration; keep `TELEGRAM_DATA_SOURCE=real`:

   ```bash
   TELEGRAM_DATA_SOURCE=real ./scripts/distribute-local.sh "<task name>" --fast
   ```

5. Do not use `fake` for a tester-facing release and do not silently fall back to it when real configuration or Firebase authentication is missing.

## Preconditions and safety

- Keep `.firebase-distribution.local` local and ignored; never print or commit its values.
- For real builds, require `android-app/telegram-api.properties` with a valid positive `apiId` and 32-character hexadecimal `apiHash`; never print the hash.
- Keep Firebase App ID/tester email in environment variables or `.firebase-distribution.local`; never hard-code them.
- Preserve Gradle single-flight, sequential test/lint/build execution, bounded timeouts, and process checks.
- If configuration, authentication, tests, lint, build, or upload fails, stop and report `BLOCKED`; do not report device readiness.

## Handoff report

Record the command result, build mode, data source, APK path, Firebase release/tester links, branch, commit, working-tree state, checks, manual device verification steps, and known limitations. `READY_FOR_DEVICE_VERIFICATION` means upload completed; it does not mean the feature was confirmed on a device.
