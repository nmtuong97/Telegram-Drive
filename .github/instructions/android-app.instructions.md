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

## 2. Tận dụng sức mạnh Android CLI (`android-cli`)

Tất cả AI assistants & agents khi thực hiện công việc trên `android-app/` PHẢI ưu tiên sử dụng `android` CLI:

- **Tra cứu tài liệu Android chính thống (`android docs`)**:
  Dùng `android docs search "<keyword>"` để tìm ví dụ, API guide, migration pattern trực tiếp từ Android Knowledge Base thay vì tự suy đoán signature.
  ```bash
  android docs search "Jetpack Compose LazyColumn animation"
  ```

- **Phân tích Cấu trúc Layout UI (`android layout`)**:
  Khi ứng dụng đang chạy trên Emulator/thiết bị thật, dùng `android layout -p` để xuất cây UI dưới dạng JSON. Dùng `android layout -d` để theo dõi sự thay đổi layout giữa các thao tác.
  ```bash
  android layout -p
  ```

- **Chụp ảnh màn hình trực quan (`android screen capture`)**:
  Dùng `android screen capture` hoặc `android screenshot` để lưu ảnh giao diện sau khi điều chỉnh thiết kế.

- **Chạy & Triển khai nhanh (`android run`)**:
  Triển khai ứng dụng và khởi chạy activity trực tiếp qua CLI:
  ```bash
  android run --debug
  ```

- **Quản lý Emulator & SDK (`android emulator`, `android sdk`)**:
  ```bash
  android emulator list
  android emulator start <avd-name>
  android sdk list
  ```

## 3. Quy trình Kiểm thử & Verification

Trước khi hoàn tất bất kỳ task nào trong `android-app/`:
1. Chạy unit tests: `./gradlew testDebugUnitTest`
2. Chạy lint check: `./gradlew lintDebug`
3. Build assembly: `./gradlew assembleDebug`
4. Nếu có emulator/device đang hoạt động: Chạy ứng dụng bằng `android run`, kiểm tra layout bằng `android layout -p` và chụp màn hình xác minh qua `android screen capture`.
