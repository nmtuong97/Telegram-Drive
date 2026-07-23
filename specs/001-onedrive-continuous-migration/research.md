# Nghiên cứu — OneDrive Continuous Migration

> **Ngày:** 2026-07-23  
> **Tài liệu:** Phase 0 output của `/speckit.plan`  
> **Mục đích:** Giải quyết mọi NEEDS CLARIFICATION và xác nhận quyết định kỹ thuật

---

## 1. Kiến trúc Worker

### Quyết định: In-Process Tokio Worker

Migration chạy như các tokio tasks trong Tauri process hiện tại, không phải binary riêng.

### Lý do

- **Chia sẻ session**: Grammers session dùng SQLite WAL với file lock. Hai process không thể chia sẻ session an toàn. In-process worker dùng chung `TelegramState` qua Tauri state mà không cần IPC.
- **Constitution compliance**: Constitution v1.1.0 (Amendment) cho phép Tokio tasks cho background orchestration trong khi Actix Web vẫn là framework bắt buộc cho HTTP server/API routes.
- **Thay đổi code tối thiểu**: Thêm 9 files trong module `migration/`, sửa setup `lib.rs` (~30 dòng), thêm tray (~50 dòng). Không cần Cargo workspace mới hay protocol IPC.
- **Đóng gói đơn giản**: Một binary duy nhất, không sidecar, không cấu hình phức tạp.
- **Cập nhật**: Updater hiện tại hoạt động không thay đổi. Migration checkpoint vào DB trước khi update.

### Hạn chế của In-Process Worker

- Process crash = worker dừng. Autostart là cơ chế phục hồi chính.
- Worker không chạy khi toàn bộ Tauri process bị terminate.
- OS supervisor (launchd/systemd) là hướng hardening tương lai, không thuộc MVP.
- Cần system tray + close-to-tray + autostart + single-instance để worker chạy bền.

### Phương án thay thế đã đánh giá

| Phương án | Ưu điểm | Nhược điểm | Lý do từ chối |
|---|---|---|---|
| Tauri Sidecar Worker | Worker độc lập với UI, sống sót khi UI crash | Không chia sẻ được session, cần custom IPC, đóng gói phức tạp | Session sharing là vấn đề chặn |
| OS Service/Supervisor | OS restart khi crash, hoàn toàn độc lập | Cần admin/sudo, cài đặt phức tạp, IPC xác thực | Quá phức tạp cho MVP |

### Tài liệu tham khảo

- `docs/onedrive-migration/ARCHITECTURE_DECISION.md` — ADR chi tiết
- `docs/onedrive-migration/CODEBASE_MAP.md` — Ánh xạ code hiện tại

---

## 2. Database Strategy

### Quyết định: `migration.db` riêng với WAL mode

### Lý do

- `shares.db` hiện tại lưu share links và folder metadata — không liên quan đến migration
- Migration DB có vòng đời khác (có thể reset độc lập, xóa khi không cần)
- Migration DB cần WAL mode, synchronous=FULL, foreign_keys=ON — thêm vào shares.db sẽ làm phức tạp nó
- Nếu sau này migration được trích xuất thành sidecar, ranh giới DB đã sạch

### Thiết lập

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = FULL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 10000;
```

### Pattern kết nối

Cùng pattern với `shares.db`: `Arc<Mutex<sqlite::Connection>>` dùng crate `sqlite` 0.37.0.

### Tài liệu tham khảo

- `docs/onedrive-migration/DATABASE_DESIGN.md` — Schema đầy đủ
- `app/src-tauri/src/db.rs` — Pattern DB hiện tại

---

## 3. Hàng đợi Upload & Chống Flood

### Quyết định: `TelegramMutationScheduler` với channel `tokio::mpsc`

### Lý do

- **Một điểm kiểm soát**: Mọi upload (thủ công + migration) qua cùng channel, đảm bảo đồng thời = 1
- **Chống flood đồng nhất**: Pacing và flood guard áp dụng cho mọi upload như nhau
- **Cooldown persistence**: Thời gian FLOOD_WAIT được lưu vào `rate_limit_state`, tồn tại qua restart
- **Ưu tiên**: Upload thủ công (`MutationSource::Manual`) được ưu tiên hơn migration

### Cơ chế Pacing

| Kích thước file | Delay giữa các upload |
|---|---|
| ≤ 1 MB | 45-90 giây (ngẫu nhiên) |
| 1-10 MB | 30-60 giây (ngẫu nhiên) |
| > 10 MB | 15-30 giây (ngẫu nhiên) |

### Burst Guard

- 10 file nhỏ liên tiếp → tạm dừng 10 phút
- 20 file trong 1 giờ → tạm dừng 5 phút

### Cấp độ An toàn Thích nghi

```
NORMAL → (FLOOD_WAIT) COOLDOWN → (hết hạn) RESTRICTED
                                           │
                              100 upload thành công liên tiếp
                                           │
                                           ▼
                                     CONSERVATIVE
                                           │
                              500 upload thành công hoặc 24h không flood
                                           │
                                           ▼
                                        NORMAL
```

### Tài liệu tham khảo

- `docs/onedrive-migration/TELEGRAM_FLOOD_GUARD.md` — Thiết kế chi tiết
- `app/src-tauri/src/commands/utils.rs:85-98` — `map_error()` hiện tại

---

## 4. Microsoft Graph Integration

### Quyết định: REST trực tiếp qua `reqwest`, không dùng SDK

### Lý do

- `reqwest` 0.12 đã có sẵn trong `Cargo.toml` với features `stream` và `rustls-tls`
- Microsoft Graph SDK cho Rust (`graph-rs-sdk`) ít trưởng thành, thêm dependency tree nặng
- Delta API, download URLs và DELETE là các REST calls đơn giản
- Phong cách phù hợp với codebase (Grammers cũng được dùng trực tiếp, không qua wrapper)

### Xác thực

- **Primary**: OAuth 2.0 Authorization Code + PKCE với loopback redirect `http://127.0.0.1:{port}`
- **Fallback**: Device Code Flow khi loopback redirect không khả dụng
- **Lưu trữ token**: AES-256-GCM, khóa từ `SHA-256(hostname + machine_id + "telegram-drive-migration")`, file permissions 0600

### Token Refresh

- Tự động refresh trước khi hết hạn (5 phút trước `expires_at`)
- Microsoft xoay refresh token — lưu token mới sau mỗi lần refresh
- Khi refresh token hết hạn → chuyển job sang `auth_required`, thông báo UI

### Delta API

- Initial delta: `GET /drives/{id}/root:/{path}:/delta` với pagination
- Incremental delta: Dùng `@odata.deltaLink` từ lần quét trước
- Stability window: File phải ổn định (không thay đổi) trong N phút trước khi vào queue

### Tài liệu tham khảo

- `docs/onedrive-migration/MICROSOFT_GRAPH_DESIGN.md` — Thiết kế chi tiết

---

## 5. State Machine & Phục hồi

### Quyết định: SQLite-first checkpoint với reconcile khi khởi động

### Lý do

- **Write-ahead logging**: Trạng thái được commit vào SQLite TRƯỚC KHI hành động được thực hiện
- **Idempotency key**: `migration_key = SHA-256(account + drive + item + etag)` đảm bảo không upload trùng
- **Tên file xác định**: `tdm_{hash}__{original}` cho phép tìm kiếm message trên Telegram khi phục hồi
- **UNIQUE constraint**: `UNIQUE(job_id, drive_item_id, source_etag)` ngăn đưa lại cùng phiên bản file

### Ma trận Phục hồi

| Trạng thái lúc crash | Hành động phục hồi |
|---|---|
| `downloading` | Range resume từ `.part` file |
| `downloaded` | Kiểm tra kích thước file, tiếp tục upload |
| `uploading` | Đặt `upload_unknown`, tìm message trong Telegram đích |
| `upload_unknown` (0 match) | Retry upload khi pacing/cooldown cho phép |
| `upload_unknown` (1 match) | Lưu message ID, tiếp tục verification |
| `upload_unknown` (2+ match) | Chuyển `quarantined`, không tự chọn, hiển thị conflict UI |
| `uploaded` | Tiếp tục verify (không upload lại) |
| `verified` | Tiếp tục DELETE nguồn |
| `deleting_source` | GET DriveItem, kiểm tra tồn tại, retry hoặc hoàn thành |
| `delete_failed` | Retry DELETE với backoff, không download/upload lại |

### Tài liệu tham khảo

- `docs/onedrive-migration/STATE_MACHINE.md` — State machine đầy đủ
- `docs/onedrive-migration/FAILURE_RECOVERY.md` — Ma trận phục hồi

---

## 6. Frontend Architecture

### Quyết định: Wizard 5 bước + Dashboard real-time

### Công nghệ

- React 19 + TypeScript
- Tailwind CSS 4 (cùng với codebase hiện tại)
- Framer Motion cho animation
- Lucide React cho icons
- `@tanstack/react-query` cho data fetching
- `sonner` cho toast notifications

### Cấu trúc Component

- `MigrationWizard`: Stepper 5 bước (Kết nối MS → Thư mục → Đích → Chính sách → Preflight)
- `MigrationDashboard`: Grid layout với stats cards, progress bars, event log
- `MigrationSettings`: Cấu hình sau khi đã tạo job
- `MigrationStatusCard`: Card nhỏ hiển thị trong sidebar/tray

### Giao tiếp Backend

- Hook `useMigration` dùng `invoke()` cho commands và `listen()` cho events
- Events từ backend đẩy real-time qua `emit()`

### i18n

- Tất cả UI text được đặt trong `app/src/i18n/locales/vi/migration.json` và `en/migration.json`

### Tài liệu tham khảo

- `docs/onedrive-migration/UI_UX_SPEC.md` — Đặc tả giao diện

---

## 7. System Tray & Autostart

### Quyết định: Tauri tray API + `tauri-plugin-autostart`

### Lý do

- Tauri cung cấp sẵn `tray-icon` feature và API cho system tray
- `tauri-plugin-autostart` hỗ trợ macOS (LaunchAgent), Windows (Registry), Linux (autostart .desktop)
- Close-to-tray: `on_window_event` handler chặn sự kiện đóng, `window.hide()` thay vì thoát
- Menu tray: "Mở Dashboard", "Tạm dừng sau file hiện tại", "Trạng thái migration", "Thoát"

---

## 8. Dependencies Mới

### Cargo.toml (thêm vào)

```toml
sha2 = "0.10"              # SHA-256 cho migration key + token encryption
aes-gcm = "0.10"           # AES-256-GCM cho mã hóa token Microsoft
hostname = "0.4"           # Lấy hostname cho key derivation
tauri-plugin-autostart = "2"  # Tự động khởi động cùng OS
```

### Không cần thêm

- `reqwest` — đã có sẵn (0.12, features: stream, rustls-tls)
- `sqlite` — đã có sẵn (0.37.0)
- `tokio` — đã có sẵn (qua Tauri)
- `serde` / `serde_json` — đã có sẵn

---

## 9. Tích hợp BandwidthManager & NetworkConfig

### BandwidthManager: Bắt buộc reuse

**Quyết định**: Migration upload PHẢI tuân thủ `BandwidthManager` hiện tại.

**Bằng chứng code**:
- `BandwidthManager` tại `app/src-tauri/src/bandwidth.rs:28` — quản lý giới hạn băng thông hàng ngày (250 GB mặc định).
- `cmd_upload_file_inner` tại `app/src-tauri/src/commands/fs.rs:805`: gọi `bw_state.try_reserve_up(size)` và `bw_state.release_up(size)`.
- Migration upload phải gọi cùng `try_reserve_up`/`release_up` để không vượt quota.

**Implementation**: Migration upload adapter gọi `BandwidthManager` trước và sau upload, giống như manual upload hiện tại. Không tạo bandwidth limiter thứ hai.

### NetworkConfig: Không phải dependency trực tiếp

**Quyết định**: Migration không phụ thuộc trực tiếp vào `NetworkConfig`.

**Bằng chứng code**:
- `NetworkConfig` tại `app/src-tauri/src/vpn_optimizer.rs` — chứa cài đặt proxy, VPN, retry_attempts, flood respect flag.
- Telegram client (`Grammers`) đã được cấu hình với proxy/network settings khi kết nối. Migration dùng chung client này.
- `map_error()` tại `commands/utils.rs:85` đã xử lý FLOOD_WAIT parse — migration có thể reuse.
- **Không tạo coupling giả tạo** chỉ để thỏa coverage. Migration flood guard là module riêng, không cần `NetworkConfig`.

---

## 10. File Filtering & Disk Guard

### File Filtering (D05)

**Pattern type**: Glob (không phải regex), dùng `globset` hoặc crate tương đương.
**Case sensitivity**: Case-insensitive để hành vi nhất quán cross-platform.
**Path separator**: `/` (forward slash).
**Default exclusions**: `~$*`, `*.tmp`, `*.partial`, `*.crdownload`, `desktop.ini`, `Thumbs.db`, `.DS_Store`.

### Disk Guard (D06)

**Công thức**:
```
required_payload_space = file_size + max(512 MiB, file_size × 5%)
minimum_global_free_space = 2 GiB
```

**Điều kiện download**: `free_space ≥ required_payload_space` **và** `free_space_after_allocation ≥ minimum_global_free_space`.

**Low disk behavior**: Job → `low_disk`, tự kiểm tra mỗi 5 phút, tự resume khi đủ dung lượng.

---

## 11. Test Infrastructure (D15)

**Hiện trạng**: `cargo test` có 0 test cases. Frontend chưa có test framework.

**Quyết định**:
- Không thêm frontend test framework chỉ để hoàn thành checklist.
- Rust migration core phải được thiết kế testable ngay từ PR đầu tiên.
- Bổ sung test infrastructure tối thiểu: unit tests cho state machine, mock Graph HTTP server, Telegram upload mock, failpoint hooks.

**Test categories tối thiểu**:
- Unit tests: state machine transitions, DB CRUD, pacing calculation, flood guard logic
- Integration tests: mock Graph server (delta, download, delete, 429/503), mock mutation scheduler
- Crash injection: failpoint hooks cho recovery scenarios
- Soak test: 72 giờ hoặc accelerated equivalent

**CI tasks**: `cargo fmt --check`, `cargo check`, `cargo clippy`, `cargo test`, frontend `tsc --noEmit`, frontend `vite build`.

---

## Tổng kết

Tất cả các điểm NEEDS CLARIFICATION đã được giải quyết. Các quyết định kiến trúc dựa trên phân tích codebase thực tế tại commit `f8ff3ce5`, không có giả định nào được đưa ra mà không có bằng chứng code.

**Số module dự kiến**: 9 files trong `app/src-tauri/src/migration/` (mod.rs, db.rs, state_machine.rs, graph.rs, downloader.rs, upload_adapter.rs, flood_guard.rs, scheduler.rs, reconciler.rs).

**Bước tiếp theo**: Phase 1 — đồng bộ `data-model.md`, `contracts/`, `tasks.md` với các quyết định trên.
