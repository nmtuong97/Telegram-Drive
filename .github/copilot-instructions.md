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
   - Scope Phase 2: Saved Messages Paging → Download → Preview → Logout/Reset.

2. **Tận dụng sức mạnh của Android CLI (`android-cli`)**:
   - **Doc Lookup (`android docs search "<query>"`)**: Sử dụng lệnh `android docs search` và `android docs fetch "kb://..."` để tra cứu tài liệu chính thức từ Android Knowledge Base.
   - **UI Hierarchy Inspection (`android layout`)**: Dùng `android layout --pretty --output=<file.json>` lấy JSON cây giao diện để kiểm tra tính đúng đắn của layout.
   - **Visual Verification (`android screen capture`)**: Sử dụng `android screen capture --output=<file.png>` để chụp lại ảnh màn hình (không dùng `android screenshot`).
   - **Deploy & Run (`android run --apks=<path>`)**: Build APK qua Gradle, lấy path từ `android describe --project_dir=android-app`, rồi deploy bằng `android run --apks=<path>`. `android run` không tự build APK.
   - **SDK & Emulator Management (`android sdk`, `android emulator`)**: Quản lý SDK packages và virtual devices trực tiếp từ CLI.

3. **Quy trình kiểm tra trước khi hoàn tất (Verification Workflow)**:
   - Chạy từng Gradle task riêng biệt có timeout:
     - `./gradlew :app:testDebugUnitTest --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
     - `./gradlew :app:lintDebug --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
     - `./gradlew :app:assembleDebug -PtelegramDataSource=fake --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
