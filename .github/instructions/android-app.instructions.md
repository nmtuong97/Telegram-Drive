---
applyTo: "android-app/**"
description: "Quy tắc phát triển ứng dụng Android độc lập, kiến trúc Clean Architecture, quy trình test và tận dụng Android CLI (android-cli)."
---

# Hướng dẫn phát triển Android (`android-app/`) & Android CLI

## 1. Kiến trúc & Chuẩn mã nguồn
- Thư mục dự án: `android-app/`. Không sửa `app/src-tauri/gen/android` (đây là tauri generated code).
- Công nghệ: Kotlin, Jetpack Compose, Kotlin DSL, minSdk 26, Application ID: `com.nmtuong.telegramdrive`.
- Kiến trúc Clean Architecture (hướng vào trong): UI/feature → repository → gateway.
- Không bao giờ import `org.drinkless.tdlib` trực tiếp vào UI/Domain layers.
- Real/Fake datasource switch bằng Gradle property `-PtelegramDataSource=real|fake`. Không commit credential thật.
- Vấn đề bảo mật & backup: Tắt Android auto-backup và device transfer cho toàn bộ TDLib session, database, keys, cache và downloaded media.
- Scope Phase 2: Vertical slice Saved Messages Paging → Download → Preview → Logout/Reset.

## 2. Tận dụng sức mạnh Android CLI (`android-cli`)

Tất cả AI assistants & agents khi thực hiện công việc trên `android-app/` PHẢI ưu tiên sử dụng `android` CLI:

- **Tra cứu tài liệu Android chính thống (`android docs`)**:
  Dùng `android docs search "<keyword>"` và `android docs fetch "kb://..."` để xem tài liệu từ Android Knowledge Base.
  ```bash
  android docs search "Jetpack Compose LazyColumn"
  android docs fetch "kb://..."
  ```

- **Phân tích Cấu trúc Layout UI (`android layout`)**:
  Khi ứng dụng đang chạy trên Emulator/thiết bị thật, dùng `android layout --pretty --output=<file.json>` để xuất cây UI dưới dạng JSON. Dùng `android layout --diff` để so sánh layout.

- **Chụp ảnh màn hình trực quan (`android screen capture`)**:
  Chỉ dùng `android screen capture --output=<file.png>` để lưu ảnh giao diện (không dùng `android screenshot`).

- **Chạy & Triển khai nhanh (`android run`)**:
  1. Build APK bằng Gradle task bounded: `./gradlew :app:assembleDebug -PtelegramDataSource=fake --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
  2. Đọc path APK: `android describe --project_dir=android-app`
  3. Deploy: `android run --apks=<actual-apk-path>`

- **Quản lý Emulator & SDK (`android emulator`, `android sdk`)**:
  ```bash
  android emulator list
  android emulator start <avd-name>
  android sdk list
  ```

## 3. Quy trình Kiểm thử & Verification

Không chạy lệnh gộp không giới hạn `./gradlew testDebugUnitTest lintDebug assembleDebug`. Run bounded tasks:
1. Unit tests: `./gradlew :app:testDebugUnitTest --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
2. Lint check: `./gradlew :app:lintDebug --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
3. Build assembly: `./gradlew :app:assembleDebug -PtelegramDataSource=fake --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
4. Nếu có emulator/device: Deploy bằng `android run --apks=...`, kiểm tra layout qua `android layout --pretty`, chụp ảnh bằng `android screen capture`.
