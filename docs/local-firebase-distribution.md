# Local Firebase App Distribution

This workflow builds the real Android debug APK locally and uploads it with Firebase App Distribution. It does not use GitHub Actions, service-account JSON, or remote CI/CD. The agent should run it automatically after implementation work is ready for device verification.

1. Create a Firebase Android app with package `com.nmtuong.telegramdrive` and enable Firebase App Distribution.
2. Add the tester email in Firebase App Distribution.
3. Install the Firebase CLI, or use the script's `npx firebase-tools` fallback.
4. Authenticate locally:

   ```bash
   firebase login
   ```

5. Create local configuration:

   ```bash
   cp .firebase-distribution.local.example .firebase-distribution.local
   ```

6. Set `FIREBASE_APP_ID` and `FIREBASE_TESTER_EMAIL` in `.firebase-distribution.local`. Environment variables take precedence. If present, `google-services.json` can supply the App ID.
7. Ensure `android-app/telegram-api.properties` contains the real Telegram `apiId` and `apiHash`. This file is local-only and must never be committed.
8. Run the full real workflow:

   ```bash
   TELEGRAM_DATA_SOURCE=real ./scripts/distribute-local.sh "Initial Firebase distribution"
   ```

   For a small UI iteration, skip tests and lint:

   ```bash
   TELEGRAM_DATA_SOURCE=real ./scripts/distribute-local.sh "Small UI adjustment" --fast
   ```

The script prints `READY_FOR_DEVICE_VERIFICATION` only after the real APK has been uploaded. This does not confirm feature behavior until a tester installs and verifies it on a device.

Common failures:

- Not logged in: run `firebase login`.
- Wrong App ID or package: verify the Firebase Android app uses `com.nmtuong.telegramdrive`.
- APK cannot be installed over an existing app: the debug signing key differs; uninstall the old app first.
- APK not found: inspect the Gradle build output and fix the local build failure.
- Tester has not accepted the invitation: accept the Firebase App Distribution invite before testing.
