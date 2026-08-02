# Telegram Drive Android

Ứng dụng Android độc lập Kotlin/Jetpack Compose. Phase 2 mở rộng vertical slice đăng nhập và Saved Messages của Phase 1 thành source browser, paging theo cursor TDLib, download có progress/cancel/retry/dedup, preview media cục bộ và logout/reset xoá dữ liệu tài khoản.

## Dependency rule

`feature/UI → data.TelegramRepository → telegram.TdLibGateway`. UI và domain không được import API generated/JNI của TDLib. `TelegramDriveApplication` sở hữu một `AppContainer`; Activity recreation không tạo client mới.

## Build và kiểm tra

Mọi Gradle invocation phải chạy tuần tự qua single-flight guard:

```bash
cd android-app
./scripts/run-gradle-single-flight.sh :app:testDebugUnitTest --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace
./scripts/run-gradle-single-flight.sh :app:lintDebug --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace
./scripts/run-gradle-single-flight.sh :app:assembleDebug -PtelegramDataSource=fake --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace
```

Mặc định debug dùng real TDLib. Chọn fake source ổn định bằng `-PtelegramDataSource=fake`. Real source cần local credential: copy `telegram-api.properties.example` thành `telegram-api.properties`, điền `apiId`/`apiHash` từ `my.telegram.org`; file thật đã bị ignore. Không đưa file này, phone, OTP, password hoặc nội dung BuildConfig/APK cá nhân vào commit/evidence.

Build TDLib từ repository root, dùng source chính thức đã pin và OpenSSL 3.5.7 LTS:

```bash
cd ..
scripts/build-tdlib-android.sh
```

Kết quả truy xuất nằm ở `android-app/tdlib-build-metadata.txt`; hash trong file phải khớp `shasum -a 256 android-app/app/src/main/jniLibs/*/libtdjsonjava.so`.

## Android runtime

```bash
android describe --project_dir=android-app
android emulator list
android run --apks=<resolved-apk-path> --activity=<resolved-launcher-activity>
android layout --pretty --output=/tmp/telegram-drive-layout.json
android screen capture --output=/tmp/telegram-drive-screen.png
```

Backup và device transfer bị tắt mặc định. FileProvider chỉ cấp temporary read-only content URI cho cache hoặc `tdlib/files`; database, session, key và các thư mục khác không được external open. Audio, PDF và unsupported-document flows cần kiểm tra trên thiết bị có file tương ứng.

Không commit API ID/hash, phone, OTP, password, session, local.properties, keystore, database TDLib hoặc dữ liệu tài khoản. Room/global index/background transfer/streaming/release và CI/CD vẫn ngoài scope Phase 2.
