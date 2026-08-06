## Ngôn ngữ giao tiếp (Communication Language)

**QUAN TRỌNG**: Toàn bộ giao tiếp giữa AI agent (bao gồm tất cả spec-kit commands) và người dùng PHẢI sử dụng Tiếng Việt.

- Khi chạy `/speckit.specify`, `/speckit.plan`, `/speckit.tasks`, `/speckit.implement`, `/speckit.constitution`, `/speckit.clarify`, `/speckit.checklist`, `/speckit.analyze`, `/speckit.converge` và tất cả các spec-kit commands khác — **luôn trả lời và tạo nội dung bằng Tiếng Việt**
- Tất cả specification documents, plan documents, tasks, code review comments, analysis reports đều viết bằng Tiếng Việt
- Tên biến, hàm, lớp, module trong code vẫn giữ nguyên bằng Tiếng Anh
- Commit messages viết bằng Tiếng Việt

**Ngoại lệ duy nhất**: Nếu người dùng yêu cầu cụ thể sử dụng ngôn ngữ khác, hãy tuân theo yêu cầu đó.

## Phát triển ứng dụng Android độc lập & Quy trình Android CLI

**QUAN TRỌNG:** Toàn bộ hướng dẫn về kiến trúc Android, sử dụng Android CLI, và quy trình Gradle đã được chuẩn hóa tại Canonical Orchestration Workflow.

👉 **Mọi AI Agent PHẢI tuân thủ nguồn sự thật duy nhất tại:** `.agents/orchestration_workflow.md`

Không sử dụng các quy tắc tự diễn giải ngoài tài liệu trên.
