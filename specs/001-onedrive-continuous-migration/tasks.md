# Tasks: OneDrive Continuous Migration

**Branch**: `001-onedrive-continuous-migration` | **Ngày**: 2026-07-23

**Ngôn ngữ**: Toàn bộ nội dung task list này được viết bằng **Tiếng Việt**. Chỉ giữ tên công nghệ, thư viện, biến/hàm/lớp bằng Tiếng Anh.

**Input**: Design documents từ `specs/001-onedrive-continuous-migration/`
- `plan.md` — Kế hoạch triển khai & PR sequence
- `spec.md` — Đặc tả tính năng & User Stories
- `research.md` — Quyết định kiến trúc
- `data-model.md` — Entities & Schema
- `contracts/` — Tauri commands & events
- `quickstart.md` — Kịch bản kiểm thử

**Tests**: Test tasks được bao gồm vì `quickstart.md` và spec đã định nghĩa các kịch bản kiểm thử end-to-end chi tiết.

**Tổ chức**: Tasks được nhóm theo user story để cho phép triển khai và kiểm thử độc lập từng story.

---

## Đồ thị Phụ thuộc Tổng thể

```
Phase 1: Setup (PR 0)
    ↓
Phase 2: Foundational (PR 1, 2, 3, 4, 5)
    ↓
┌───────────────────────────────────────────────────────┐
│  Phase 3: US1 - Thiết lập & chạy migration (P1) 🎯 MVP │
│  (PR 6: Scheduler + PR 8: Frontend Wizard/Dashboard)   │
└───────────────────────────────────────────────────────┘
    ↓
┌───────────────────────────────────────────────────────┐
│  Phase 4: US2 - Chạy nền (P2)                          │
│  (PR 7: System Tray + Autostart)                       │
└───────────────────────────────────────────────────────┘
    ↓
┌───────────────────────────────────────────────────────┐
│  Phase 5: US3 - Phục hồi sau sự cố (P2)               │
│  (Reconciler + Recovery logic)                         │
└───────────────────────────────────────────────────────┘
    ↓
┌───────────────────────────────────────────────────────┐
│  Phase 6: US4 - Giám sát & quản lý (P3)               │
│  (Dashboard nâng cao + quản lý items)                  │
└───────────────────────────────────────────────────────┘
    ↓
┌───────────────────────────────────────────────────────┐
│  Phase 7: US5 - Xóa nguồn (P3)                        │
│  (Source deletion flow)                                │
└───────────────────────────────────────────────────────┘
    ↓
Phase 8: Polish (PR 9: Tests + i18n + cleanup)
    ↓
Phase 9: Metrics, CI & Soak (PR 10)
```

---

## Phase 1: Setup — Sửa nền tảng hiện có (PR 0)

**Mục đích**: Sửa lỗi upload hiện tại không trả `message_id`, chuẩn bị dependencies. Không có task nào trong phase này phụ thuộc vào code migration mới.

### Kiểm thử cho Phase 1

- [ ] T001 [P] Viết unit test cho `cmd_upload_file` xác nhận trả về `message_id` trong `app/src-tauri/src/commands/fs.rs`

### Triển khai cho Phase 1

- [ ] T002 Sửa `cmd_upload_file` trong `app/src-tauri/src/commands/fs.rs`: thêm `message_id`, `peer_id`, `file_name`, `file_size` vào kiểu trả về `UploadResult`, đảm bảo `send_message()` trả về ID message sau upload
- [ ] T003 [P] Cập nhật frontend `useFileUpload.ts` để nhận `UploadResult` đầy đủ từ kết quả upload trong `app/src/hooks/useFileUpload.ts`
- [ ] T004a [P] Tích hợp `BandwidthManager` vào upload adapter: gọi `try_reserve_up(size)` trước upload và `release_up(size)` sau upload trong `app/src-tauri/src/migration/upload_adapter.rs` [NEW]
- [ ] T004 [P] Thêm dependencies mới vào `app/src-tauri/Cargo.toml`: `sha2 = "0.10"`, `aes-gcm = "0.10"`, `hostname = "0.4"`, `tauri-plugin-autostart = "2"`, `uuid = { version = "1", features = ["v4"] }`
- [ ] T005 [P] Cập nhật Tauri permissions trong `app/src-tauri/capabilities/default.json`: thêm quyền `migration:*` và `autostart:default`

**Checkpoint**: Upload thủ công hiện tại vẫn hoạt động bình thường, giờ có trả `message_id`. Sẵn sàng build foundational.

---

## Phase 2: Foundational — Cơ sở hạ tầng chung (PR 1, 2, 3, 4, 5)

**Mục đích**: Xây dựng tất cả module độc lập cần thiết trước khi lắp ráp scheduler. Các module này không phụ thuộc lẫn nhau về mặt code, nhưng phụ thuộc tuần tự về mặt dữ liệu (DB → Auth → Download → Upload → Scheduler).

**⚠️ QUAN TRỌNG**: Không user story nào có thể bắt đầu cho đến khi Phase 2 hoàn tất.

---

### Sub-Phase 2.1: Database + State Machine (PR 1)

**Goal**: Khởi tạo `migration.db` với schema đầy đủ, state machines, và CRUD operations.

**Independent Test**: Chạy Rust unit test xác nhận DB được tạo, schema migration hoạt động, state transitions hợp lệ.

- [ ] T006 Tạo module `app/src-tauri/src/migration/mod.rs`: khai báo tất cả sub-modules (`db`, `state_machine`, `graph`, `downloader`, `upload_adapter`, `flood_guard`, `scheduler`, `reconciler`), public API
- [ ] T007 Triển khai `app/src-tauri/src/migration/db.rs`: hàm `init_migration_db()` với `PRAGMA journal_mode=WAL`, `synchronous=FULL`, `foreign_keys=ON`, `busy_timeout=10000`, tạo thư mục `{app_data}/migration/`
- [ ] T008 [P] Tạo file SQL migration `app/src-tauri/src/migration/migrations/001_initial.sql`: định nghĩa 5 bảng (`migration_jobs`, `migration_items`, `migration_events`, `rate_limit_state`, `worker_heartbeat`) + `schema_version`
- [ ] T009 Triển khai `MigrationDb` struct với `Arc<Mutex<sqlite::Connection>>` và `run_migrations()` áp dụng migration SQL theo version trong `app/src-tauri/src/migration/db.rs`
- [ ] T010 [P] Triển khai `app/src-tauri/src/migration/state_machine.rs`: định nghĩa `JobStatus` enum (created/preflight/running/paused/stopping/completed/cancelled/fatal + waiting/graph_cooldown/telegram_cooldown/low_disk/auth_required) và `ItemStatus` enum (discovered/stabilizing/queued/downloading/downloaded/upload_intent/uploading/upload_unknown/uploaded/verifying/verified/delete_pending/deleting_source/delete_failed/completed/source_changed/skipped/quarantined)
- [ ] T011 Triển khai `validate_job_transition(from, to) -> Result<(), String>` trong `app/src-tauri/src/migration/state_machine.rs`: kiểm tra tất cả chuyển đổi job hợp lệ theo data-model
- [ ] T012 Triển khai `validate_item_transition(from, to) -> Result<(), String>` trong `app/src-tauri/src/migration/state_machine.rs`: kiểm tra tất cả chuyển đổi item hợp lệ theo data-model
- [ ] T013 Triển khai CRUD cho `migration_jobs` trong `app/src-tauri/src/migration/db.rs`: `create_job()`, `get_job()`, `get_all_jobs()`, `update_job_status()`, `update_job_cooldown()`
- [ ] T014 [P] Triển khai CRUD cho `migration_items` trong `app/src-tauri/src/migration/db.rs`: `create_item()`, `get_items_by_job()`, `get_next_queued_item()`, `update_item_status()`, `update_item_progress()`, `get_items_for_recovery()`
- [ ] T015 [P] Triển khai CRUD cho `migration_events` trong `app/src-tauri/src/migration/db.rs`: `insert_event()`, `get_events_by_job()` với phân trang, `cleanup_old_events()` (xóa > 90 ngày)
- [ ] T016 Triển khai CRUD cho `rate_limit_state` trong `app/src-tauri/src/migration/db.rs`: `get_rate_limit()`, `update_rate_limit()`, `reset_rate_limit()`
- [ ] T017 [P] Triển khai `WorkerHeartbeat` trong `app/src-tauri/src/migration/db.rs`: `update_heartbeat()` (gọi mỗi 5s), `get_heartbeat()`
- [ ] T018 Tích hợp `MigrationDb` vào Tauri state trong `app/src-tauri/src/lib.rs`: khởi tạo `init_migration_db()` lúc app setup, bọc trong `Arc<Mutex<>>`, đăng ký qua `app.manage()`

---

### Sub-Phase 2.2: Microsoft Auth + Graph Client (PR 2)

**Goal**: OAuth 2.0 PKCE flow + Microsoft Graph REST client qua `reqwest`.

**Independent Test**: Có thể xác thực Microsoft, lấy token, gọi Graph API `/me` và nhận về thông tin người dùng.

- [ ] T019 Tạo `app/src-tauri/src/migration/graph.rs`: struct `GraphClient` với `client_id: String`, `reqwest::Client`, token cache
- [ ] T020 Triển khai OAuth 2.0 PKCE flow trong `app/src-tauri/src/migration/graph.rs`: `generate_pkce_challenge()` → SHA-256 + base64url, `build_auth_url()` trả về URL `login.microsoftonline.com`, `listen_for_callback(port)` với tiny HTTP server trên `127.0.0.1:{port}`
- [ ] T021 Triển khai Device Code flow fallback trong `app/src-tauri/src/migration/graph.rs`: `start_device_code_flow()` gửi request đến `/devicecode`, poll `/token` mỗi 5s
- [ ] T022 Triển khai token exchange trong `app/src-tauri/src/migration/graph.rs`: `exchange_code_for_token(code, code_verifier)` → lưu access_token + refresh_token, tính `expires_at`
- [ ] T023 Triển khai mã hóa/giải mã token trong `app/src-tauri/src/migration/graph.rs`: `derive_encryption_key()` từ `SHA-256(hostname + machine_id + "telegram-drive-migration")`, `encrypt_token()` với AES-256-GCM, `decrypt_token()`, `save_encrypted_token(path)` với file permissions `0600`
- [ ] T024 Triển khai token refresh trong `app/src-tauri/src/migration/graph.rs`: `ensure_valid_token()` kiểm tra `expires_at`, tự động refresh 5 phút trước khi hết hạn, xoay refresh token mới từ Microsoft
- [ ] T025 Triển khai Microsoft Graph REST calls trong `app/src-tauri/src/migration/graph.rs`: `get_drives()` → `GET /me/drives`, `list_folders(drive_id, path)` → `GET /drives/{id}/root:/{path}:/children?$filter=folder ne null`
- [ ] T026 Triển khai Delta API trong `app/src-tauri/src/migration/graph.rs`: `initial_delta(drive_id, path)` → `GET /drives/{id}/root:/{path}:/delta`, xử lý `@odata.nextLink` và `@odata.deltaLink` pagination
- [ ] T027 [P] Triển khai `incremental_delta(delta_link)` trong `app/src-tauri/src/migration/graph.rs`: dùng `@odata.deltaLink` lưu từ lần quét trước, parse response thành `Vec<DriveItem>`
- [ ] T028 Triển khai `get_download_url(item_id)` trong `app/src-tauri/src/migration/graph.rs`: `GET /drives/{id}/items/{item_id}?select=@microsoft.graph.downloadUrl`, trả về URL tạm
- [ ] T029 Triển khai `delete_item(item_id, etag)` trong `app/src-tauri/src/migration/graph.rs`: `DELETE /drives/{id}/items/{item_id}` với header `If-Match: {etag}`, xử lý HTTP 412
- [ ] T030 [P] Tích hợp `GraphClient` vào Tauri state trong `app/src-tauri/src/lib.rs`: khởi tạo client, đăng ký `app.manage()`

---

### Sub-Phase 2.3: Trích xuất Upload Adapter (PR 4)

**Goal**: Tách logic upload khỏi `cmd_upload_file` thành hàm tái sử dụng cho cả thủ công và migration.

**Independent Test**: Gọi `upload_adapter::upload_file()` với file test nhỏ, xác nhận file xuất hiện trong Telegram và trả về `message_id`.

- [ ] T031 Tạo `app/src-tauri/src/migration/upload_adapter.rs`: hàm `pub async fn upload_file(telegram: &Arc<TelegramState>, path: &Path, folder_id: Option<i64>) -> Result<UploadResult, String>`
- [ ] T032 Trích xuất logic upload từ `app/src-tauri/src/commands/fs.rs::cmd_upload_file` vào `upload_adapter::upload_file()`: gọi `client.upload_stream()`, theo dõi tiến trình, gọi `client.send_message()`, trả về `UploadResult { message_id, file_name, size }`
- [ ] T033 Sửa `cmd_upload_file` trong `app/src-tauri/src/commands/fs.rs` để gọi `upload_adapter::upload_file()` thay vì code nội tuyến — đảm bảo upload thủ công hiện tại không bị hỏng
- [ ] T034 [P] Thêm callback `on_progress` vào `upload_file()`: `Fn(uploaded_bytes, total_bytes)` để hỗ trợ báo cáo tiến trình real-time

---

### Sub-Phase 2.4: OneDrive Downloader (PR 3)

**Goal**: HTTP download với Range request resume, lưu `.part` file atomic rename.

**Independent Test**: Download file từ URL test, kill process giữa chừng, resume và xác nhận file hoàn chỉnh khớp checksum.

- [ ] T035 Tạo `app/src-tauri/src/migration/downloader.rs`: struct `Downloader` với `reqwest::Client`, `cache_dir: PathBuf`
- [ ] T036 Triển khai `download_file(download_url, local_path, item_id, on_progress)` trong `app/src-tauri/src/migration/downloader.rs`: tải file HTTP với streaming, ghi vào `{local_path}.part`, progress callback mỗi 250ms
- [ ] T037 Triển khai Range request resume trong `app/src-tauri/src/migration/downloader.rs`: `resume_download()` kiểm tra kích thước file `.part` hiện có → `HEAD` request lấy `Content-Length` → `GET` với `Range: bytes={offset}-`
- [ ] T038 Triển khai atomic rename trong `app/src-tauri/src/migration/downloader.rs`: sau khi download hoàn tất, rename `{name}.part` → `{name}.ready`, đảm bảo không để file hỏng được coi là hoàn chỉnh
- [ ] T039 Triển khai cleanup trong `app/src-tauri/src/migration/downloader.rs`: `cleanup_orphan_parts()` quét `cache_dir`, xóa file `.part` không có item tương ứng trong DB (gọi khi khởi động)
- [ ] T040 Triển khai kiểm tra dung lượng đĩa trong `app/src-tauri/src/migration/downloader.rs`: `check_disk_space(file_size)` → tính `required_payload_space = file_size + max(512 MiB, file_size × 5%)`, `minimum_global_free_space = 2 GiB`; kiểm tra `free_space >= required_payload_space` VÀ `free_space_after_allocation >= minimum_global_free_space`; trả về `Err` nếu không đủ

---

### Sub-Phase 2.5: Telegram Mutation Scheduler + Flood Guard (PR 5)

**Goal**: Hàng đợi upload duy nhất qua `tokio::mpsc` channel với pacing và flood guard.

**Independent Test**: Enqueue 5 file vào scheduler, xác nhận upload tuần tự với delay pacing, kiểm tra `rate_limit_state` được cập nhật đúng.

- [ ] T041 Tạo `app/src-tauri/src/migration/flood_guard.rs`: struct `TelegramMutationScheduler` với `tx: mpsc::Sender<MutationRequest>`, `rx: mpsc::Receiver<MutationRequest>`
- [ ] T042 Triển khai `MutationRequest` enum trong `app/src-tauri/src/migration/flood_guard.rs`: `UploadFile { path, folder_id, reply_to: oneshot::Sender, source: MutationSource }` với `MutationSource::Manual` và `MutationSource::Migration(u64)`
- [ ] T043 Triển khai `MutationPriority` ordering trong `app/src-tauri/src/migration/flood_guard.rs`: `Manual` luôn có priority cao hơn `Migration`, migration requests được xử lý FIFO
- [ ] T044 Triển khai vòng lặp xử lý `run_scheduler(db, telegram)` trong `app/src-tauri/src/migration/flood_guard.rs`: tokio task lắng nghe `rx`, dequeue request, kiểm tra cooldown trước khi upload, gọi `upload_adapter::upload_file()`
- [ ] T045 Triển khai pacing logic trong `app/src-tauri/src/migration/flood_guard.rs`: `calculate_delay(file_size, safety_level)` → file ≤ 1MB: 45-90s, 1-10MB: 30-60s, > 10MB: 15-30s, nhân với `safety_level` multiplier
- [ ] T046 Triển khai burst guard trong `app/src-tauri/src/migration/flood_guard.rs`: đếm số file nhỏ liên tiếp (≤ 1MB) → nếu ≥ 10, tạm dừng 10 phút; nếu ≥ 20 file trong 1 giờ, tạm dừng 5 phút
- [ ] T047 Triển khai flood guard thích nghi trong `app/src-tauri/src/migration/flood_guard.rs`: bắt `FLOOD_WAIT` error từ upload → parse số giây → lưu `cooldown_until` vào `rate_limit_state` → tăng `flood_count` → nâng `safety_level` (Normal→Conservative→Restricted→Cooldown)
- [ ] T048 Triển khai safety level downgrade trong `app/src-tauri/src/migration/flood_guard.rs`: sau 100 lần upload thành công liên tiếp → RESTRICTED→CONSERVATIVE; sau 500 lần hoặc 24h không flood → CONSERVATIVE→NORMAL; reset `success_streak` sau mỗi lần nâng cấp
- [ ] T049 Triển khai cooldown persistence trong `app/src-tauri/src/migration/flood_guard.rs`: `check_cooldown()` đọc `rate_limit_state` từ DB, block upload nếu `cooldown_until > now()`, auto-resume khi hết cooldown
- [ ] T050 [P] Sửa `cmd_upload_file` trong `app/src-tauri/src/commands/fs.rs` để route qua `TelegramMutationScheduler` thay vì gọi `upload_adapter` trực tiếp — upload thủ công hưởng cùng pacing/flood guard
- [ ] T051 Tích hợp `TelegramMutationScheduler` vào Tauri state trong `app/src-tauri/src/lib.rs`: spawn tokio task `run_scheduler()`, đăng ký sender channel qua `app.manage()`

**Checkpoint**: Tất cả module foundational đã sẵn sàng. Có thể upload file qua scheduler, download qua downloader, xác thực Microsoft, truy vấn DB. Sẵn sàng lắp ráp Migration Scheduler.

---

## Phase 3: User Story 1 — Thiết lập và chạy migration lần đầu (P1) 🎯 MVP

**Goal**: Người dùng có thể làm theo wizard 5 bước, kết nối Microsoft, chọn thư mục, khởi động migration, và thấy file được chuyển thành công sang Telegram.

**Independent Test**: Tạo thư mục OneDrive với 3-5 file nhỏ, chạy wizard, khởi động migration, xác nhận tất cả file xuất hiện trong Telegram đích.

**PR tương ứng**: PR 6 (Scheduler + Reconciler) + PR 8 (Frontend UI)

---

### Sub-Phase 3.1: Migration Scheduler Core (PR 6 — phần scheduler)

- [ ] T052 Tạo `app/src-tauri/src/migration/scheduler.rs`: struct `MigrationScheduler` với `db: MigrationDb`, `telegram: Arc<TelegramState>`, `graph: Arc<GraphClient>`, `downloader: Downloader`, `scheduler_tx: mpsc::Sender<MutationRequest>`, `app: AppHandle`
- [ ] T053 Triển khai `run_scheduler(job_id)` trong `app/src-tauri/src/migration/scheduler.rs`: vòng lặp chính — (1) quét file mới qua Delta API, (2) xử lý stability window, (3) dequeue item tiếp theo, (4) download → upload → verify → (tùy chọn delete), (5) emit events
- [ ] T054 Triển khai delta scan cycle trong `app/src-tauri/src/migration/scheduler.rs`: `scan_cycle()` gọi `initial_delta()` hoặc `incremental_delta()`, lọc file theo `include_patterns`/`exclude_patterns`/`min_size_bytes` (glob, case-insensitive, trên relative path, exclude thắng include, default exclusions: `~$*`, `*.tmp`, `*.partial`, `*.crdownload`, `desktop.ini`, `Thumbs.db`, `.DS_Store`), tạo `MigrationItem` với status `discovered`
- [ ] T055 Triển khai stability window trong `app/src-tauri/src/migration/scheduler.rs`: `process_stability()` — item `discovered` chuyển sang `stabilizing`, lưu `stable_first_seen_at`; mỗi lần quét tăng `stable_observation_count`; khi `(now - stable_first_seen_at) >= stability_window_seconds` → chuyển sang `queued`
- [ ] T056 Triển khai download phase trong `app/src-tauri/src/migration/scheduler.rs`: `process_download(item)` — chuyển status `downloading`, kiểm tra disk space, gọi `downloader.download_file()` với Range resume nếu có `.part`, emit `migration-download-progress`, chuyển `downloaded`
- [ ] T057 Triển khai upload phase trong `app/src-tauri/src/migration/scheduler.rs`: `process_upload(item)` — chuyển status `uploading`, gọi `scheduler_tx.send(MutationRequest::UploadFile{...})` với `source: MutationSource::Migration(job_id)`, đợi `oneshot::Receiver`, lưu `telegram_message_id`, chuyển `uploaded`, emit `migration-upload-progress`
- [ ] T058 Triển khai verify phase trong `app/src-tauri/src/migration/scheduler.rs`: `process_verify(item)` — chuyển `verifying`, gọi `client.get_messages_by_id(destination, message_id)`, kiểm tra file tồn tại + khớp tên + khớp kích thước, chuyển `verified`
- [ ] T059 Triển khai error handling trong `app/src-tauri/src/migration/scheduler.rs`: `handle_item_error(item, error)` — phân loại lỗi (`retryable`, `cooldown`, `auth`, `permanent`, `conflict`), tăng `attempt_count`, set `next_retry_at` với backoff, nếu `permanent` sau 3 lần → chuyển `quarantined`
- [ ] T060 Triển khai job status management trong `app/src-tauri/src/migration/scheduler.rs`: `update_job_status()` theo dõi tất cả items — nếu tất cả terminal → `completed`; nếu có lỗi fatal → `fatal`; emit `migration-job-updated`
- [ ] T061 Triển khai command handlers trong `app/src-tauri/src/migration/scheduler.rs`: `handle_pause()` — set flag, đợi item hiện tại hoàn tất, chuyển job `paused`; `handle_resume()` — xóa flag, chuyển job `running`; `handle_cancel()` — dừng vòng lặp, chuyển tất cả items chưa terminal sang `skipped`

---

### Sub-Phase 3.2: Reconciler (PR 6 — phần phục hồi cơ bản)

- [ ] T062 Tạo `app/src-tauri/src/migration/reconciler.rs`: hàm `pub async fn reconcile_on_startup(db, telegram, graph, downloader, scheduler_tx) -> Result<(), String>` — bản nền tảng MVP
- [ ] T063 Triển khai phục hồi `downloading` items trong `app/src-tauri/src/migration/reconciler.rs`: kiểm tra file `.part` tồn tại → resume download từ byte đã tải (Range request)
- [ ] T064 Triển khai phục hồi `downloaded` items trong `app/src-tauri/src/migration/reconciler.rs`: kiểm tra file `.ready` tồn tại + kích thước khớp `file_size` trong DB → chuyển `uploading` hoặc re-enqueue nếu file hỏng
- [ ] T065 Triển khai phục hồi `uploading` items trong `app/src-tauri/src/migration/reconciler.rs`: chuyển sang `upload_unknown`, gọi `client.iter_messages(destination)` tìm message có tên `tdm_{hash}__{original}` → nếu tìm thấy: chuyển `uploaded` + lưu `message_id`; nếu không: chuyển lại `queued` để upload lại

---

### Sub-Phase 3.3: Tauri Commands (PR 6 + PR 8 backend)

- [ ] T066 Triển khai `migration_create_job` command trong `app/src-tauri/src/migration/scheduler.rs`: validate config, tạo `MigrationJob` trong DB với status `created`, chạy validation state machine
- [ ] T067 [P] Triển khai `migration_start_job` command trong `app/src-tauri/src/migration/scheduler.rs`: kiểm tra preflight đã pass, chuyển job `running`, spawn `run_scheduler(job_id)`
- [ ] T068 [P] Triển khai `migration_pause_job`, `migration_resume_job`, `migration_cancel_job` commands trong `app/src-tauri/src/migration/scheduler.rs`
- [ ] T069 [P] Triển khai `migration_get_jobs`, `migration_get_job_detail` (với stats + phân trang items) commands trong `app/src-tauri/src/migration/db.rs`
- [ ] T070 [P] Triển khai `migration_get_events` command trong `app/src-tauri/src/migration/db.rs`: hỗ trợ lọc `severity`, phân trang `limit`/`offset`
- [ ] T071 Triển khai `microsoft_connect`, `microsoft_complete_auth`, `microsoft_disconnect`, `microsoft_status` commands trong `app/src-tauri/src/migration/graph.rs`
- [ ] T072 [P] Triển khai `microsoft_list_folders`, `microsoft_get_drives` commands trong `app/src-tauri/src/migration/graph.rs`
- [ ] T073 Triển khai `migration_run_preflight` command trong `app/src-tauri/src/migration/scheduler.rs`: kiểm tra Microsoft connected, Telegram connected, folder tồn tại, đếm file → trả về `PreflightReport`
- [ ] T074 Đăng ký tất cả commands trong `app/src-tauri/src/lib.rs` qua `app.invoke_handler(tauri::generate_handler![...])`

---

### Sub-Phase 3.4: Backend Event Emitting

- [ ] T075 Triển khai event emitting trong `app/src-tauri/src/migration/scheduler.rs`: `app.emit("migration-job-updated", &job)` khi status job thay đổi, `app.emit("migration-item-updated", &item)` khi status item thay đổi
- [ ] T076 [P] Triển khai emit `migration-download-progress` mỗi 250ms trong `app/src-tauri/src/migration/downloader.rs`
- [ ] T077 [P] Triển khai emit `migration-upload-progress` mỗi 250ms qua callback trong `app/src-tauri/src/migration/upload_adapter.rs`
- [ ] T078 [P] Triển khai emit `migration-cooldown-update` khi flood guard thay đổi trạng thái trong `app/src-tauri/src/migration/flood_guard.rs`
- [ ] T079 Triển khai emit `migration-disk-warning` khi dung lượng đĩa thấp trong `app/src-tauri/src/migration/downloader.rs`
- [ ] T080 Triển khai emit `migration-auth-required` khi token Microsoft hết hạn trong `app/src-tauri/src/migration/graph.rs`

---

### Sub-Phase 3.5: Frontend — i18n Skeleton (PR 8)

> **Ghi chú (D17)**: i18n phải có ngay từ PR UI đầu tiên. Tạo file skeleton trước khi code component.

- [ ] T080a [P] Tạo `app/src/i18n/locales/vi/migration.json`: skeleton với tất cả UI text keys tiếng Việt cho wizard, dashboard, tray menu, error messages [NEW]
- [ ] T080b [P] Tạo `app/src/i18n/locales/en/migration.json`: skeleton với tất cả UI text keys tiếng Anh [NEW]
- [ ] T080c Đăng ký locale files trong `app/src/i18n/index.ts`: import migration.json cho cả vi và en [NEW]

### Sub-Phase 3.6: Frontend — Wizard Thiết lập (PR 8)

- [ ] T081 Tạo `app/src/components/shared/MigrationWizard.tsx`: component chính với stepper 5 bước (Kết nối MS → Thư mục → Đích → Chính sách → Preflight), dùng Framer Motion cho chuyển động, tất cả UI text qua `useTranslation()`
- [ ] T082 Triển khai Bước 1 — Kết nối Microsoft trong `app/src/components/shared/MigrationWizard.tsx`: gọi `microsoft_connect()`, mở auth URL trong browser, hiển thị trạng thái kết nối, gọi `microsoft_status()`; tất cả UI text qua `useTranslation()`
- [ ] T083 Triển khai Bước 2 — Chọn thư mục nguồn trong `app/src/components/shared/MigrationWizard.tsx`: gọi `microsoft_get_drives()` → `microsoft_list_folders()`, hiển thị cây thư mục với Lucide icons, hiển thị số file phát hiện; tất cả UI text qua `useTranslation()`
- [ ] T084 Triển khai Bước 3 — Chọn đích Telegram trong `app/src/components/shared/MigrationWizard.tsx`: hiển thị danh sách kênh/nhóm/Saved Messages, lưu `destination_peer_id` + `destination_folder_id`; tất cả UI text qua `useTranslation()`
- [ ] T085 Triển khai Bước 4 — Cấu hình chính sách trong `app/src/components/shared/MigrationWizard.tsx`: toggle xóa nguồn, slider stability window (1-60 phút), slider scan interval (1-60 phút), input include/exclude glob patterns (case-insensitive), input min size; pattern validation với error hiển thị; tất cả UI text qua `useTranslation()`
- [ ] T086 Triển khai Bước 5 — Preflight & Khởi động trong `app/src/components/shared/MigrationWizard.tsx`: gọi `migration_run_preflight()`, hiển thị report với ✓/✗ icons, warnings list, ước lượng thời gian, nút "Bắt đầu Continuous Migration"; tất cả UI text qua `useTranslation()`

---

### Sub-Phase 3.6: Frontend — Hook & Types

- [ ] T087 Tạo `app/src/hooks/useMigration.ts`: custom hook với `invoke()` cho tất cả commands, `listen()` cho tất cả events, state management cho jobs/items/events
- [ ] T088 [P] Thêm TypeScript types vào `app/src/types.ts`: `MigrationJob`, `MigrationItem`, `MigrationEvent`, `MigrationJobConfig`, `PreflightReport`, `JobStatus`, `ItemStatus`, `MicrosoftAccount`, `OneDriveFolder`

---

### Sub-Phase 3.7: Frontend — Dashboard Cơ bản

- [ ] T089 Tạo `app/src/components/shared/MigrationDashboard.tsx`: grid layout với stats cards (tổng file, hoàn thành, đang xử lý, lỗi), progress bar tổng, danh sách items với status badge
- [ ] T090 Triển khai real-time updates trong `app/src/components/shared/MigrationDashboard.tsx`: lắng nghe `migration-job-updated`, `migration-item-updated`, `migration-download-progress`, `migration-upload-progress` → cập nhật UI qua React state
- [ ] T091 [P] Tạo `app/src/components/shared/MigrationStatusCard.tsx`: card nhỏ gọn hiển thị trạng thái migration hiện tại (tên job, % hoàn thành, file đang xử lý) — dùng trong sidebar

---

### Sub-Phase 3.8: Frontend — Tích hợp

- [ ] T092 Sửa `app/src/App.tsx`: thêm route/mục "OneDrive Migration" vào navigation, hiển thị `MigrationWizard` nếu chưa có job, `MigrationDashboard` nếu đã có job đang chạy
- [ ] T093 Sửa `app/src/components/desktop/DesktopDashboard.tsx`: thêm mục sidebar "OneDrive Migration" với icon, hiển thị `MigrationStatusCard` nếu có job active
- [ ] T094 Sửa `app/src/context/SettingsContext.tsx`: thêm migration settings (không cần migration-specific context riêng)

**Checkpoint**: US1 hoàn chỉnh — người dùng có thể thiết lập và chạy migration từ đầu đến cuối. File được download từ OneDrive, upload lên Telegram, xác minh và hiển thị trên dashboard.

---

## Phase 4: User Story 2 — Migration chạy nền khi đóng cửa sổ (P2)

**Goal**: Khi người dùng đóng cửa sổ, app thu nhỏ xuống system tray và migration tiếp tục chạy. Có thể mở lại dashboard từ tray. App tự động khởi động cùng OS.

**Independent Test**: Khởi động migration, đóng cửa sổ, đợi 5 phút, mở lại từ tray, xác nhận migration đã xử lý thêm file.

**PR tương ứng**: PR 7

- [ ] T095 [P] [US2] Cấu hình `tauri-plugin-autostart` trong `app/src-tauri/tauri.conf.json`: thêm plugin, cấu hình app name cho LaunchAgent (macOS) / Registry (Windows) / .desktop (Linux)
- [ ] T096 [P] [US2] Cấu hình system tray trong `app/src-tauri/tauri.conf.json`: thêm `tray-icon` feature, trỏ đến icon file
- [ ] T096a [P] [US2] Triển khai single-instance guard trong `app/src-tauri/src/lib.rs`: dùng `tauri-plugin-single-instance` hoặc file lock (`{app_data}/.app.lock`) để ngăn nhiều Tauri process chạy migration worker song song [NEW]
- [ ] T097 [US2] Triển khai tray setup trong `app/src-tauri/src/lib.rs`: `app.setup()` tạo `SystemTrayBuilder::new()` với icon, tooltip "Telegram-Drive", menu items
- [ ] T098 [US2] Triển khai tray menu trong `app/src-tauri/src/lib.rs`: "Mở Dashboard" → `window.show()`, "Trạng thái: Đang chạy (X/Y files)" → cập nhật động, "Tạm dừng sau file hiện tại" → gọi `migration_pause_job`, "Thoát" → `app.exit()`
- [ ] T099 [US2] Triển khai close-to-tray trong `app/src-tauri/src/lib.rs`: `on_window_event(|event| { if let CloseRequested = event { window.hide(); api.prevent_close(); } })` — chặn đóng cửa sổ, ẩn thay vì thoát
- [ ] T100 [US2] Triển khai tray status update trong `app/src-tauri/src/migration/scheduler.rs`: mỗi khi job status thay đổi, cập nhật tray tooltip và menu item text qua `tray_handle.set_tooltip()` và `tray_handle.get_item().set_title()`
- [ ] T101 [US2] Triển khai autostart toggle trong frontend: gọi `tauri-plugin-autostart` API `is_enabled()` / `enable()` / `disable()` qua `invoke()`, thêm toggle vào `MigrationSettings`
- [ ] T102 [US2] Kiểm thử thủ công close-to-tray trên macOS (menu bar) và Windows/Linux (system tray) theo `quickstart.md` Kịch bản 2

**Checkpoint**: US2 hoàn chỉnh — người dùng có thể đóng cửa sổ, migration chạy nền, mở lại từ tray. App tự động khởi động cùng OS.

---

## Phase 5: User Story 3 — Phục hồi sau sự cố (P2)

**Goal**: Sau khi app crash hoặc bị kill, migration tự động phục hồi từ trạng thái đã lưu — không upload trùng, không mất dữ liệu, resume download từ checkpoint.

**Independent Test**: Đang upload file 100MB, kill process, khởi động lại, xác nhận file được phát hiện và upload lại hoặc resume.

**PR tương ứng**: Phần recovery trong PR 6

- [ ] T103 [US3] Extend `reconcile_on_startup()` trong `app/src-tauri/src/migration/reconciler.rs`: thêm các đường dẫn phục hồi cho `delete_failed`, `source_changed`, `quarantined`; gọi từ `lib.rs` khi app khởi động
- [ ] T104 [US3] Triển khai phục hồi `upload_unknown` với 3 nhánh: (a) 0 match → retry upload; (b) 1 match → lưu message ID, chuyển `uploaded`; (c) 2+ match → chuyển `quarantined`, hiển thị conflict UI
- [ ] T105 [US3] Triển khai phục hồi `verified` items: tiếp tục sang `delete_pending` → `deleting_source` nếu `delete_after_upload = true`, hoặc `completed` nếu không
- [ ] T106 [US3] Triển khai phục hồi `deleting_source` / `delete_failed`: kiểm tra file nguồn có còn tồn tại trên OneDrive không → nếu không: `completed`; nếu có: retry delete với eTag
- [ ] T107 [US3] Triển khai idempotency guarantee: trước khi tạo `MigrationItem`, kiểm tra `UNIQUE(job_id, drive_item_id, source_etag)` → nếu đã tồn tại, bỏ qua
- [ ] T108 [US3] Triển khai phục hồi cooldown: `reconcile_on_startup()` đọc `rate_limit_state` → nếu `cooldown_until > now()`, đặt job vào trạng thái `telegram_cooldown` hoặc `graph_cooldown`
- [ ] T109 [US3] Kiểm thử thủ công phục hồi theo `quickstart.md` Kịch bản 3: kill process khi đang download, đang upload, đang verify — xác nhận mọi case phục hồi đúng

**Checkpoint**: US3 hoàn chỉnh — migration phục hồi an toàn sau mọi loại crash.

---

## Phase 6: User Story 4 — Giám sát và quản lý migration đang chạy (P3)

**Goal**: Dashboard hiển thị chi tiết: số file hoàn thành/chờ/lỗi, progress bars, event log real-time. Người dùng có thể tạm dừng, tiếp tục, bỏ qua file lỗi, hủy migration.

**Independent Test**: Migration 20 file với 2 file lỗi, xác nhận dashboard hiển thị đúng số liệu, có thể bỏ qua file lỗi.

**PR tương ứng**: Phần dashboard nâng cao trong PR 8

- [ ] T110 [P] [US4] Triển khai `MigrationDashboard` nâng cao trong `app/src/components/shared/MigrationDashboard.tsx`: thêm tab "Tổng quan" (stats + progress), tab "Danh sách File" (bảng với filter status, sort), tab "Nhật ký" (event log với filter severity)
- [ ] T111 [P] [US4] Triển khai file list view trong `app/src/components/shared/MigrationDashboard.tsx`: hiển thị tất cả items dạng bảng với cột: tên file, kích thước, trạng thái, tiến trình (%), số lần thử, lỗi cuối — hỗ trợ lọc theo status
- [ ] T112 [US4] Triển khai event log view trong `app/src/components/shared/MigrationDashboard.tsx`: gọi `migration_get_events()` với phân trang, hiển thị timeline với color-coded severity, auto-scroll đến event mới nhất
- [ ] T113 [US4] Triển khai action buttons trong `app/src/components/shared/MigrationDashboard.tsx`: nút "Tạm dừng" / "Tiếp tục" (gọi `migration_pause_job` / `migration_resume_job`), nút "Hủy Migration" (có confirm dialog)
- [ ] T114 [US4] Triển khai item-level actions trong `app/src/components/shared/MigrationDashboard.tsx`: nút "Bỏ qua" (gọi `migration_skip_item`), nút "Thử lại" (gọi `migration_retry_item`) trên từng hàng file lỗi
- [ ] T115 [US4] Triển khai cooldown indicator trong `app/src/components/shared/MigrationDashboard.tsx`: hiển thị countdown khi đang trong cooldown, thông báo "Telegram đang tạm nghỉ: X giây"
- [ ] T116 [US4] Triển khai disk space indicator trong `app/src/components/shared/MigrationDashboard.tsx`: hiển thị dung lượng trống, cảnh báo khi dưới ngưỡng
- [ ] T117 [US4] Triển khai connection status trong `app/src/components/shared/MigrationDashboard.tsx`: indicator xanh/đỏ cho Microsoft + Telegram, nút "Kết nối lại" khi auth required

**Checkpoint**: US4 hoàn chỉnh — dashboard cung cấp đầy đủ công cụ giám sát và quản lý.

---

## Phase 7: User Story 5 — Xóa file nguồn sau khi upload thành công (P3)

**Goal**: Khi tùy chọn "Xóa khỏi OneDrive" được bật, file nguồn được xóa an toàn sau khi xác minh upload thành công.

**Independent Test**: Migration 3 file với tùy chọn xóa nguồn, xác nhận cả 3 bị xóa khỏi OneDrive và tồn tại trên Telegram.

**PR tương ứng**: Phần delete trong PR 6

- [ ] T118 [US5] Triển khai `process_delete_source(item)` trong `app/src-tauri/src/migration/scheduler.rs`: gọi `graph.delete_item(item.drive_item_id, item.source_etag)` với `If-Match` header
- [ ] T119 [US5] Triển khai xử lý HTTP 412 (Precondition Failed) trong `app/src-tauri/src/migration/scheduler.rs`: đánh dấu `source_changed` — file nguồn đã bị thay đổi từ khi phát hiện, không xóa
- [ ] T120 [US5] Triển khai retry logic cho delete: nếu lỗi mạng hoặc 5xx → retry với backoff (tối đa 3 lần), nếu vẫn thất bại → `delete_failed`
- [ ] T121 [US5] Triển khai conditional flow: chỉ gọi `process_delete_source` nếu `job.delete_after_upload == true`, nếu không → chuyển thẳng `completed` sau `verified`
- [ ] T122 [US5] Kiểm thử thủ công theo `quickstart.md` Kịch bản 5: thiết lập job với xóa nguồn, chạy migration, xác nhận file bị xóa khỏi OneDrive

**Checkpoint**: US5 hoàn chỉnh — tính năng xóa nguồn hoạt động an toàn.

---

## Phase 8: Polish & Cross-Cutting Concerns (PR 9)

**Mục đích**: Hoàn thiện i18n, kiểm thử tích hợp, tối ưu, và dọn dẹp.

---

### Sub-Phase 8.1: i18n (Hoàn thiện)

> **Ghi chú (D17)**: UI skeleton i18n đã có từ PR 8 (T080a-T080c). Phase 8 chỉ hoàn thiện bản dịch và kiểm tra.

- [ ] T123 [P] Hoàn thiện `app/src/i18n/locales/vi/migration.json`: kiểm tra và cập nhật tất cả UI text tiếng Việt cho wizard, dashboard, tray menu, error messages
- [ ] T124 [P] Hoàn thiện `app/src/i18n/locales/en/migration.json`: kiểm tra và cập nhật tất cả UI text tiếng Anh
- [ ] T125 Xác nhận locale files đã được đăng ký trong `app/src/i18n/index.ts` (từ T080c)

---

### Sub-Phase 8.2: Integration Tests (PR 9)

- [ ] T126 [P] Viết integration test cho DB schema trong `app/src-tauri/tests/migration_db_test.rs`: tạo DB in-memory, chạy migrations, kiểm tra tất cả bảng tồn tại, foreign keys hoạt động
- [ ] T127 [P] Viết integration test cho state machine trong `app/src-tauri/tests/migration_state_machine_test.rs`: kiểm tra tất cả valid transitions được chấp nhận, tất cả invalid transitions bị từ chối
- [ ] T128 [P] Viết integration test cho flood guard trong `app/src-tauri/tests/migration_flood_guard_test.rs`: mock FLOOD_WAIT response, kiểm tra cooldown persistence, safety level transitions (NORMAL→COOLDOWN→RESTRICTED→CONSERVATIVE→NORMAL)
- [ ] T129 [P] Viết integration test cho downloader resume trong `app/src-tauri/tests/migration_downloader_test.rs`: mock HTTP server với Range support (206 Partial Content), kill mid-download, resume; test Range ignored (200 OK → restart); test expired download URL
- [ ] T130 Viết integration test cho scheduler pipeline trong `app/src-tauri/tests/migration_scheduler_test.rs`: mock Graph API + Telegram, chạy pipeline đầy đủ discover → download → upload → verify → complete
- [ ] T131 [P] Viết integration test cho reconciler trong `app/src-tauri/tests/migration_reconciler_test.rs`: mô phỏng crash ở mỗi phase (downloading, download_complete, upload_started, upload_complete_before_commit, verify_complete, delete_pending), khởi động lại, xác nhận phục hồi đúng. Bao gồm: crash sau Telegram success trước DB commit, crash sau DB commit trước DELETE, 0/1/2+ message match.

---

### Sub-Phase 8.3: E2E Validation

- [ ] T132 Chạy `quickstart.md` Kịch bản 1 end-to-end: wizard → migration cơ bản → xác nhận file trong Telegram
- [ ] T133 [P] Chạy `quickstart.md` Kịch bản 2: close-to-tray → đợi → mở lại dashboard
- [ ] T134 [P] Chạy `quickstart.md` Kịch bản 3: crash recovery ở download, upload, verify phases (bao gồm multi-match conflict)
- [ ] T135 [P] Chạy `quickstart.md` Kịch bản 4: tạm dừng/tiếp tục, bỏ qua file lỗi, hủy migration
- [ ] T136 [P] Chạy `quickstart.md` Kịch bản 5: xóa nguồn sau upload (bao gồm eTag mismatch → source_changed, 404 → completed)

---

### Sub-Phase 8.4: Code Cleanup & Documentation

- [ ] T137 Dọn dẹp unused imports, dead code, warnings trong toàn bộ module `migration/`
- [ ] T138 [P] Chạy `cargo clippy` và `cargo fmt --check` trên toàn bộ `app/src-tauri/`, sửa tất cả warnings trong module migration (không bắt buộc sửa warnings cũ)
- [ ] T139 [P] Chạy `npx tsc --noEmit` trên `app/`, sửa tất cả TypeScript errors
- [ ] T140 [P] Cập nhật `CHANGELOG.md`: thêm mục "OneDrive Continuous Migration" với mô tả tính năng
- [ ] T141 Cập nhật `REST_API_Documentation.md` (nếu cần): thêm endpoint migration nếu Actix routes bị ảnh hưởng

**Checkpoint**: Polish hoàn chỉnh.

---

## Phase 9: Metrics, CI & Soak Test (PR 10)

**Mục đích**: Instrumentation cho metrics, CI pipeline, soak test gate. Tất cả tasks đều là [P] và độc lập.

### Sub-Phase 9.1: Metrics Instrumentation

- [ ] T142 [P] Triển khai metrics collection trong `app/src-tauri/src/migration/scheduler.rs`: thu thập `first_attempt_success_count`, `total_upload_attempts`, `FLOOD_WAIT_count` từ `rate_limit_state`; ghi vào structured logs hoặc SQLite aggregate view [NEW]
- [ ] T143 [P] Triển khai recovery time benchmark: đo `reconciler_complete_time - backend_init_complete_time` trong `app/src-tauri/tests/migration_reconciler_test.rs`; xác nhận ≤ 30 giây (không tính FLOOD_WAIT/Retry-After) [NEW]
- [ ] T144 [P] Triển khai dashboard latency measurement: so sánh timestamp `backend_emit_ts` với `UI_render_ts` qua event payload `migration-job-updated`; xác nhận < 2 giây [NEW]
- [ ] T145 [P] Triển khai CPU/memory tracking: dùng `sysinfo` crate (đã có) để đo baseline vs migration, ghi vào structured logs; xác nhận RAM ≤ 200MB extra, CPU ≤ 2% idle [NEW]

### Sub-Phase 9.2: Crash Injection & Edge Case Tests

- [ ] T146 [P] Viết test cho Graph 429/503 với `Retry-After` header trong `app/src-tauri/tests/migration_graph_test.rs`: mock `429 Too Many Requests` + `Retry-After: 120`; xác nhận graph_cooldown, auto-resume [NEW]
- [ ] T147 [P] Viết test cho FLOOD_WAIT persistence qua restart: mock FLOOD_WAIT_300 → kill process → restart → xác nhận cooldown vẫn active, không gửi mutation [NEW]
- [ ] T148 [P] Viết test cho disk full/low disk: mock `available_space < required` → xác nhận job `low_disk` → mock `available_space` đủ → xác nhận auto-resume sau 5 phút [NEW]
- [ ] T149 [P] Viết test cho source eTag changed: mock Graph DELETE trả về 412 → xác nhận item `source_changed`, không xóa [NEW]
- [ ] T150 [P] Viết test cho multiple Telegram message matches: tạo 2+ message khớp → xác nhận item `quarantined`, conflict hiển thị [NEW]
- [ ] T151 [P] Viết test cho single-instance behavior: mở app thứ hai → xác nhận bị chặn hoặc focus window hiện có [NEW]

### Sub-Phase 9.3: CI Pipeline

- [ ] T152 [P] Tạo `.github/workflows/test.yml`: CI job chạy `cargo fmt --check`, `cargo check`, `cargo clippy`, `cargo test`, `npx tsc --noEmit`, `npx vite build` trên matrix `[ubuntu-latest, windows-latest, macos-latest]` [NEW]
- [ ] T153 [P] Thêm failpoint/crash-injection hooks: conditional `#[cfg(feature = \"test-hooks\")]` compile-time gates cho crash injection tests [NEW]

### Sub-Phase 9.4: Soak Test Gate

- [ ] T154 Triển khai soak test scenario: 10,000 mock files, random network errors (10%), Graph 429 (5%), FLOOD_WAIT (2%), kill process mỗi 6h; chạy 72h hoặc accelerated equivalent. Tiêu chí: mọi item đạt terminal state, zero upload trùng, DB integrity pass `PRAGMA integrity_check`, memory ổn định. [NEW] [MANUAL GATE]

**Checkpoint**: Metrics, CI, và soak test hoàn chỉnh.

---

## Dependencies & Execution Order

### Phase Dependencies

| Phase | Phụ thuộc | Có thể chạy song song |
|---|---|---|
| Phase 1: Setup | Không | — |
| Phase 2: Foundational | Phase 1 | Các sub-phase tuần tự: 2.1 → 2.2 → 2.3 → 2.4 → 2.5 |
| Phase 3: US1 (MVP) | Phase 2 | — |
| Phase 4: US2 | Phase 3 | — |
| Phase 5: US3 | Phase 3 | Có thể song song với Phase 4 |
| Phase 6: US4 | Phase 3 | Có thể song song với Phase 4, 5 |
| Phase 7: US5 | Phase 3 | Có thể song song với Phase 4, 5, 6 |
| Phase 8: Polish | Phase 3-7 | — |
| Phase 9: Metrics & CI | Phase 3-8 | Tất cả sub-phases song song |

### Trong mỗi Phase

- Sub-phase 2.2 phụ thuộc 2.1 (cần DB để lưu token)
- Sub-phase 2.4 phụ thuộc 2.2 (cần Graph để lấy download URL)
- Sub-phase 2.5 phụ thuộc 2.1 + 2.3 (cần DB + adapter)
- Sub-phase 3.1-3.2 (backend scheduler) trước sub-phase 3.3-3.8 (commands + frontend)
- Frontend có thể phát triển song song với backend nếu dùng mock data

### Cơ hội Song song

```
Phase 2 nội bộ:
  T007-T008 (migration SQL) ‖ T010 (state machine enum)
  T013 (job CRUD) ‖ T014 (item CRUD) ‖ T015 (event CRUD) ‖ T017 (heartbeat)
  T020 (PKCE) ‖ T026 (Delta API) ‖ T029 (delete item) — trong sub-phase 2.2
  T036 (download) ‖ T040 (disk space check) — trong sub-phase 2.4
  T042 (enum) ‖ T046 (burst guard) — trong sub-phase 2.5

Phase 3 nội bộ:
  T066-T074 (commands) có thể làm song song từng phần
  T075-T080 (events) có thể làm song song với commands
  T082 (Bước 1) ‖ T083 (Bước 2) ‖ T084 (Bước 3) ‖ T085 (Bước 4) — các bước wizard độc lập
  T088 (types) ‖ T087 (hook) — frontend foundational

Phase 8 nội bộ:
  T123 (vi i18n) ‖ T124 (en i18n)
  T126-T131 (tests) — tất cả có [P], chạy song song
  T132-T136 (E2E) — chạy song song nếu có nhiều môi trường test
```

---

## Chiến lược Triển khai

### MVP Scope (Minimum Viable Product)

**Chỉ Phase 1 + 2 + 3** = Người dùng có thể thiết lập và chạy migration lần đầu thành công.

- ✅ Kết nối Microsoft qua OAuth PKCE
- ✅ Chọn thư mục OneDrive, chọn đích Telegram
- ✅ Cấu hình chính sách migration cơ bản
- ✅ Preflight check trước khi chạy
- ✅ Download → Upload → Verify pipeline
- ✅ Dashboard cơ bản với tiến trình real-time
- ✅ Phục hồi cơ bản khi khởi động lại

### Incremental Delivery

1. **MVP (Phase 1-3)**: Migration hoạt động khi app đang mở
2. **+ Phase 4**: Chạy nền + system tray → migration không bị gián đoạn
3. **+ Phase 5**: Phục hồi toàn diện → tin cậy hơn
4. **+ Phase 6**: Dashboard nâng cao → quản lý tốt hơn
5. **+ Phase 7**: Xóa nguồn → giải phóng OneDrive
6. **+ Phase 8**: Tests + i18n hoàn chỉnh → sẵn sàng release
7. **+ Phase 9**: Metrics + CI + Soak test → production-grade

### Tổng số Task

| Phase | Số task | ID range |
|---|---|---|
| Phase 1: Setup | 6 | T001-T005, T004a |
| Phase 2: Foundational | 46 | T006-T051 |
| Phase 3: US1 (MVP) | 43 | T052-T094 |
| Phase 4: US2 | 9 | T095-T102, T096a |
| Phase 5: US3 | 7 | T103-T109 |
| Phase 6: US4 | 8 | T110-T117 |
| Phase 7: US5 | 5 | T118-T122 |
| Phase 8: Polish | 19 | T123-T141 |
| Phase 9: Metrics & CI | 13 | T142-T154 |
| **Tổng** | **156** | |

---

## Định dạng Xác thực

✅ Tất cả tasks tuân theo format: `- [ ] [TaskID] [P?] [Story?] Description with file path`
✅ Task IDs từ T001 đến T154 (bao gồm T004a, T096a)
✅ [P] marker cho tasks có thể chạy song song
✅ [US1]-[US5] labels cho tasks thuộc user story
✅ [NEW] marker cho tasks được thêm bởi remediation
✅ Tất cả tasks có file path cụ thể hoặc đánh dấu [NEW]
✅ Mỗi phase có checkpoint với tiêu chí xác minh độc lập
✅ i18n bắt đầu từ PR 8 (Frontend UI), hoàn thiện trong Phase 8
✅ Reconciler không bị duplicate: T062 (nền tảng), T103 (extend)
✅ Không có mobile tasks — desktop-only MVP
✅ BandwidthManager được tích hợp (T004a)
✅ Single-instance guard được thêm (T096a)
✅ Metrics & CI tasks đầy đủ (Phase 9)
✅ Soak test gate có task riêng (T154)
