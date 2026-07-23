# Telegram-Drive Constitution

## Ngôn ngữ giao tiếp (BẮT BUỘC)

### I. Tiếng Việt là ngôn ngữ chính thức
Toàn bộ giao tiếp giữa AI agent và con người trong dự án này **PHẢI** sử dụng **Tiếng Việt**.
- Tất cả specification, plan, tasks, comments, code review đều viết bằng Tiếng Việt
- Tên biến, hàm, lớp, module trong code vẫn giữ nguyên bằng Tiếng Anh
- Tài liệu kỹ thuật (docs) viết bằng Tiếng Việt
- Commit messages viết bằng Tiếng Việt

## Core Principles

### I. Rust Backend — Actix Web (HTTP Server & API Routes)
Backend HTTP server và REST API routes **PHẢI** dùng Actix-web framework. Tất cả streaming, REST API, share routes đều tuân theo kiến trúc Actix. Xử lý range request, CORS, và xác thực đúng pattern.

**Phạm vi và ngoại lệ (Amendment 1.1.0)**:
- Actix Web là framework bắt buộc cho HTTP server và HTTP API route trong Rust backend.
- Background orchestration, scheduler, downloader, timer và long-running internal jobs được phép dùng Tokio task trực tiếp trong Tauri runtime.
- Long-running migration worker phải nằm ở Rust backend, không nằm trong React/JavaScript.
- Tauri IPC vẫn là giao tiếp chuẩn giữa frontend và backend desktop.

### II. Tauri IPC + React Frontend
Frontend dùng React/TypeScript với Tailwind CSS v4. Giao tiếp với backend qua Tauri IPC commands (`invoke`/`emit`). State management dùng React Context.

### III. Telegram MTProto làm storage layer
Sử dụng Telegram như storage backend. Upload/download file qua MTProto. Queue-based transfer với khả năng hủy và theo dõi tiến trình. Background worker và UI dùng chung `TelegramState` — không tạo client thứ hai.

### IV. SQLite Database
Lưu trữ metadata local bằng SQLite. Raw SQL queries, migration thủ công. Background worker có thể dùng database riêng (`migration.db`) với WAL mode, synchronous=FULL để đảm bảo durability.

### V. Spec-Driven Development
Tuân theo quy trình SDD: Constitution → Specify → Plan → Tasks → Implement → Converge. Mọi tính năng đều phải có spec trước khi implement.

### VI. Background Processing (Amendment 1.2.0)
Background workers chạy trong Tauri process (in-process), dùng Tokio task. Long-running work phải nằm trong Rust backend; React frontend không được sở hữu worker dài hạn. Phải có persistent checkpoints khi feature requirement yêu cầu resume sau restart. Tauri IPC là boundary giữa frontend và backend.

**Yêu cầu bắt buộc khi feature cam kết unattended/background operation** (sau khi đóng cửa sổ hoặc đăng nhập OS):
- System tray để giữ process sống khi đóng cửa sổ
- Close-to-tray behavior (đóng cửa sổ = ẩn, không thoát)
- Tự động khởi động cùng OS (autostart)
- Single-instance guard để ngăn nhiều worker

**Đối với feature chỉ cam kết chạy khi Tauri process đang mở**:
- Không bắt buộc system tray.
- Không bắt buộc autostart.
- Không bắt buộc sidecar hoặc OS service.
- Manual Resume sau khi mở lại app là hợp constitution.
- Tự động phục hồi trạng thái từ SQLite khi process khởi động lại.

Worker không chạy khi toàn bộ Tauri process bị terminate. Đối với feature yêu cầu unattended operation, autostart là cơ chế phục hồi chính. OS supervisor (launchd/systemd) là hướng hardening tương lai.

## Công nghệ chính

- **Frontend**: React + TypeScript + Tailwind CSS v4
- **Backend**: Rust (Tauri v2) + Actix-web (HTTP) + Tokio (background workers)
- **Storage**: Telegram MTProto (Telegram Drive)
- **Database**: SQLite
- **Streaming**: MP4 (FMP4 remux), HLS (qua quality selector)
- **Auth**: Telegram MTProto authentication

## Quy tắc phát triển

### Quality Gates
1. Mọi tính năng phải có spec trước khi implement
2. Kiểm tra cross-artifact consistency trước khi implement
3. Xử lý lỗi đúng pattern (Rust Result<T, E>, frontend error boundaries)
4. i18n cho mọi UI text (hỗ trợ Tiếng Việt và Tiếng Anh) — phải có ngay khi component UI được thêm, không trì hoãn đến phase cuối

## Governance

- Constitution này supersedes mọi practices khác
- Amendments cần documentation, approval, migration plan
- Mọi PR/review phải verify compliance với constitution
- Ngôn ngữ giao tiếp Tiếng Việt là NON-NEGOTIABLE

## Sync Impact Report (v1.1.0 → v1.2.0)

**Loại amendment**: MINOR — làm rõ phạm vi Principle VI, phân biệt giữa feature yêu cầu unattended operation và feature chỉ chạy khi app mở.

**Nguyên tắc thay đổi**:
- **Principle VI (Background Processing)**: Làm rõ system tray, close-to-tray, autostart, single-instance guard chỉ bắt buộc khi feature specification cam kết unattended/background operation sau khi đóng cửa sổ hoặc đăng nhập OS. Feature chỉ cam kết chạy khi Tauri process mở không bắt buộc các cơ chế này; Manual Resume là hợp constitution.

**Lý do**: Feature MVP OneDrive Migration chỉ cam kết chạy khi Tauri process đang mở, không yêu cầu unattended operation. Amendment này cho phép MVP không cần system tray/autostart mà không vi phạm constitution.

**Ảnh hưởng đến artifacts hiện có**:
- Không làm suy yếu các nguyên tắc khác.
- Feature 001-onedrive-migration plan đã được cập nhật để phản ánh amendment này.
- Các template không cần thay đổi.

**Phiên bản trước**: 1.1.0

**Version**: 1.2.0 | **Ratified**: 2026-07-23 | **Last Amended**: 2026-07-23
