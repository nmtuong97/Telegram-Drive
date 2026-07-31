## Ngôn ngữ giao tiếp (Communication Language)

**QUAN TRỌNG**: Toàn bộ giao tiếp giữa AI agent (bao gồm tất cả spec-kit commands) và người dùng PHẢI sử dụng Tiếng Việt.

- Khi chạy `/speckit.specify`, `/speckit.plan`, `/speckit.tasks`, `/speckit.implement`, `/speckit.constitution`, `/speckit.clarify`, `/speckit.checklist`, `/speckit.analyze`, `/speckit.converge` và tất cả các spec-kit commands khác — **luôn trả lời và tạo nội dung bằng Tiếng Việt**
- Tất cả specification documents, plan documents, tasks, code review comments, analysis reports đều viết bằng Tiếng Việt
- Tên biến, hàm, lớp, module trong code vẫn giữ nguyên bằng Tiếng Anh
- Commit messages viết bằng Tiếng Việt

**Ngoại lệ duy nhất**: Nếu người dùng yêu cầu cụ thể sử dụng ngôn ngữ khác, hãy tuân theo yêu cầu đó.

## Phát triển ứng dụng Android độc lập (`android-app/`) & Sử dụng Android CLI

Khi làm việc trong dự án Android (`android-app/`), GitHub Copilot và các AI agents phải tuân thủ các nguyên tắc sau:

1. **Kiến trúc & Công nghệ**:
   - Sử dụng Kotlin, Jetpack Compose, Kotlin DSL, target minSdk 26, package `com.nmtuong.telegramdrive`.
   - Giữ kiến trúc Clean Architecture: UI/feature -> Repository -> Gateway. Không import TDLib vào UI/Domain layer.

2. **Tận dụng sức mạnh của Android CLI (`android-cli`)**:
   - **Doc Lookup (`android docs search "<query>"`)**: Sử dụng lệnh `android docs search` để tra cứu tài liệu chính thức từ Android Knowledge Base trước khi triển khai các API Jetpack Compose, Android Security, hoặc Coroutines.
   - **UI Hierarchy Inspection (`android layout -p`)**: Khi ứng dụng chạy trên emulator/thiết bị, dùng `android layout -p` lấy JSON cây giao diện để kiểm tra tính đúng đắn của layout thay vì chỉ dựa vào ảnh chụp.
   - **Visual Verification (`android screen capture`)**: Sử dụng `android screen capture` hoặc `android screenshot` để chụp lại ảnh màn hình thiết bị thực tế sau khi chỉnh sửa giao diện.
   - **Deploy & Run (`android run --debug`)**: Triển khai trực tiếp ứng dụng lên emulator hoặc thiết bị thử nghiệm bằng `android run`.
   - **SDK & Emulator Management (`android sdk`, `android emulator`)**: Quản lý SDK packages và virtual devices trực tiếp từ CLI.

3. **Quy trình kiểm tra trước khi hoàn tất (Verification Workflow)**:
   - Luôn chạy `./gradlew testDebugUnitTest lintDebug assembleDebug` trong thư mục `android-app/` để đảm bảo unit tests pass và không có lỗi build/lint.

