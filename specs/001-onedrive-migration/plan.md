# Implementation Plan: OneDrive Migration (MVP)

**Branch**: `001-onedrive-migration` | **Ngày**: 2026-07-23 | **Spec**: [spec.md](./spec.md) | **Constitution**: v1.2.0

**Input**: Feature specification từ `/specs/001-onedrive-migration/spec.md`

## Ngôn ngữ

**QUAN TRỌNG**: Toàn bộ nội dung plan này được viết bằng **Tiếng Việt**. Chỉ giữ tên công nghệ, thư viện, biến/hàm/lớp bằng Tiếng Anh.

## Summary

Tích hợp tính năng OneDrive Migration vào Telegram-Drive desktop, cho phép người dùng chọn một thư mục OneDrive làm nguồn, quét danh sách file (snapshot cố định), rồi download tuần tự từng file về local và upload lên Telegram destination. Worker chạy in-process trong Tauri Rust backend dưới dạng Tokio task. Tái sử dụng logic upload hiện có qua shared internal function — manual upload command giữ nguyên policy hiện tại, migration worker có policy riêng. UI là một trang đơn (`OneDriveMigrationPage`) tích hợp vào sidebar navigation.

## Technical Context

**Language/Version**: Rust 1.75+ (backend), TypeScript 5.x (frontend)

**Primary Dependencies**:
- **Backend**: Tauri v2, grammers-client (Telegram MTProto), reqwest v0.12 (HTTP + SOCKS cho Microsoft Graph), serde (serialization), sha2 (SHA-256), sqlite (migration.db), tokio (async runtime), log + env_logger (logging)
- **Frontend**: React 18, TypeScript, Tailwind CSS v4, @tanstack/react-query, sonner (toast), @tauri-apps/api/core, @tauri-apps/api/event, @tauri-apps/plugin-dialog, react-i18next

**Storage**: SQLite (`migration.db`), tách biệt với `shares.db` hiện có. WAL mode, synchronous=FULL. 3 business tables: `migration_jobs`, `migration_items`, `migrated_fingerprints`.

**Testing**: Backend unit/integration tests dùng temporary SQLite + fake Microsoft/Telegram adapters. Frontend validation: type-check + production build + manual quickstart.

**Target Platform**: Desktop — Windows, macOS, Linux (Tauri desktop). Không hỗ trợ Android/iOS trong MVP.

**Project Type**: Desktop application — React frontend + Tauri Rust backend, in-process background worker.

**Performance Goals**:
- UI không bị đơ > 2 giây trong suốt quá trình migration
- Xử lý ít nhất 100 file tuần tự không crash, không mất trạng thái
- File tạm local bị xóa trong vòng 5 giây sau upload thành công

**Constraints**:
- Chỉ 1 migration job `running` tại một thời điểm
- Chỉ 1 file được xử lý tại một thời điểm (tuần tự)
- Không đọc toàn bộ file vào RAM (stream download/upload)
- Không được xóa/thay đổi file nguồn trên OneDrive
- Không log Microsoft access token, refresh token, Telegram session
- Microsoft token chỉ trong Rust process memory, không persist ra disk

**Scale/Scope**:
- Hỗ trợ thư mục OneDrive với hàng trăm đến hàng nghìn file
- Một tài khoản Microsoft, một Telegram destination mỗi job
- 15 Tauri commands, 5 events, 1 frontend page, 3 business tables
- 5 phases implementation

## Constitution Check

| Nguyên tắc | Trạng thái | Ghi chú |
|-----------|-----------|--------|
| **I. Actix Web** | ✅ PASS | Worker dùng Tokio task. Được phép theo Amendment 1.2.0. |
| **II. Tauri IPC + React** | ✅ PASS | Frontend `invoke`/`listen`, React Context. |
| **III. Telegram MTProto** | ✅ PASS | Dùng chung `TelegramState`, shared internal upload function. |
| **IV. SQLite** | ✅ PASS | `migration.db` riêng, WAL+FULL, raw SQL. 3 business tables. |
| **V. Spec-Driven** | ✅ PASS | Spec đã clarify và hoàn thiện. |
| **VI. Background Processing (v1.2.0)** | ✅ PASS | MVP chỉ chạy khi Tauri process mở. Amendment 1.2.0: tray/autostart không bắt buộc. Manual Resume hợp constitution. |
| **i18n** | ✅ PASS | UI text qua i18n keys. |
| **Error handling** | ✅ PASS | Enum error cho migration module; map về String ở IPC boundary. |

**Gate Evaluation**: Tất cả nguyên tắc PASS. Không TENSION, không accepted violation.

## Project Structure

### Source Code

```text
app/
├── src/
│   ├── components/
│   │   ├── desktop/dashboard/
│   │   │   └── Sidebar.tsx           # ← Thêm nav item "OneDrive Migration"
│   │   └── migration/                # ← THƯ MỤC MỚI (4 components)
│   │       ├── OneDriveMigrationPage.tsx
│   │       ├── SetupSection.tsx       # Connect + chọn folder + scan
│   │       ├── ProgressPanel.tsx      # Summary + current progress + controls
│   │       └── FileTable.tsx          # Danh sách file + status + retry
│   ├── hooks/
│   │   └── useMigration.ts
│   ├── i18n/locales/{vi,en}.json
│   └── types.ts
│
└── src-tauri/
    ├── Cargo.toml                    # ← Thêm dep: sha2
    └── src/
        ├── lib.rs                    # ← Đăng ký MigrationState
        ├── commands/
        │   ├── fs.rs                 # ← Extract shared internal upload function
        │   └── utils.rs              # ← Extract parse_flood_wait_seconds()
        └── migration/                # ← THƯ MỤC MỚI (7 modules)
            ├── mod.rs                # MigrationState + orchestrator registration
            ├── models.rs             # Structs, enums, states, errors
            ├── db.rs                 # Schema, repos, transactions, recovery
            ├── microsoft.rs          # OAuth session, Graph API, scan, download
            ├── worker.rs             # Pipeline: validate→download→upload→persist→next
            ├── upload_adapter.rs     # Shared internal upload function seam
            └── commands.rs           # 15 Tauri commands + 5 event emitters
```

**Structure Decision**: Backend 7 modules, frontend 1 page + 3 components.

## Implementation Phases

| Phase | Goal | Code areas | Dependencies | Independent validation | Exit criteria |
|---|---|---|---|---|---|
| **Phase 1 — Foundation & Seams** | Models, DB schema, error types, upload adapter trait | `migration/models.rs`, `migration/db.rs`, `migration/upload_adapter.rs`, `commands/fs.rs` (extract), `commands/utils.rs` (extract) | `sqlite`, `sha2` | Unit: schema creation, one-active-job guard, structured error types, adapter interface | migration.db created, upload internal function compiles, models valid |
| **Phase 2 — Snapshot Scan** | Microsoft OAuth, recursive scan, snapshot persistence, folder browsing | `migration/microsoft.rs`, `migration/commands.rs` (connect/scan/list), `migration/db.rs` (batch insert) | Phase 1, `reqwest` | Manual: connect MS, scan folder, verify file count & total size match OneDrive | Scan totals chính xác, pagination hoạt động, snapshot persisted |
| **Phase 3 — Sequential Worker** | Download, upload, duplicate check, retry, cooldown, pause/cancel | `migration/worker.rs`, `migration/microsoft.rs` (download), `migration/upload_adapter.rs` | Phase 2 | Unit: duplicate logic, retry max 3, cooldown gate. Integration: mock upload 5 files tuần tự | 5 files migrated, duplicate detected, cooldown respected, pause/cancel hoạt động |
| **Phase 4 — IPC & UI** | Commands, events, React page, sidebar integration | `migration/commands.rs`, `migration/mod.rs`, `lib.rs` (register), `components/migration/*`, `hooks/useMigration.ts`, `Sidebar.tsx`, i18n | Phase 3 | Manual: full UI flow, progress bars, all controls | UI hoàn chỉnh: setup → scan → migrate → complete, progress visible |
| **Phase 5 — Tests & Hardening** | Unit tests, integration tests, quickstart verification | All `#[cfg(test)]` modules, fake adapters, quickstart.md | Phase 4 | All 7 quickstart scenarios pass, unit tests green, type-check + build pass | Gate: PASS cho `/speckit.converge` |

## Complexity Tracking

| Vi phạm | Lý do cần | Tại sao không dùng cách đơn giản hơn |
|----------|-----------|-------------------------------------|
| Database riêng | Tránh rủi ro corruption cho shares.db | Merge tăng phức tạp migration script, risk dữ liệu hiện có |
| Worker ownership (Arc<AtomicBool>) | Ngăn multiple concurrent workers | DB guard là lớp thứ hai; atomic bool đơn giản nhất |
| Extract internal upload function | Worker cần gọi upload không qua Tauri IPC | REST localhost thêm latency, phức tạp port; copy-paste vi phạm DRY |
