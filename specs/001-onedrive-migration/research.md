# Research: OneDrive Migration (MVP)

**Feature**: 001-onedrive-migration | **Phase**: 0 — Outline & Research | **Ngày**: 2026-07-23

## Mục tiêu

Giải quyết tất cả NEEDS CLARIFICATION từ Technical Context trong plan.md, đưa ra quyết định kiến trúc có căn cứ cho từng lựa chọn công nghệ và pattern tích hợp.

---

## R01 — Microsoft Graph API Integration

### Decision
Dùng **Microsoft Graph REST API v1.0** qua `reqwest` HTTP client (đã có sẵn trong project với `rustls-tls` và `socks` features). Không dùng Microsoft Graph SDK cho Rust (chưa có SDK chính thức, chỉ có community crate không ổn định).

### Rationale
- `reqwest` đã được dùng trong project cho HTTP requests, có sẵn SOCKS proxy support.
- Microsoft Graph REST API ổn định, documented đầy đủ.
- MVP chỉ cần 3 endpoints: list children (`/me/drive/root:/path:/children`), download file (`@microsoft.graph.downloadUrl`), user profile (`/me`).
- Không dùng Delta API trong MVP — scan là snapshot cố định, không cần delta tracking.

### Alternatives Considered
- **graph-rs**: Community crate, không chính thức, ít maintained. Rủi ro breaking changes.
- **Tạo SDK wrapper riêng**: Over-engineering cho MVP, chỉ cần vài endpoint.

### Implementation Notes
- Base URL: `https://graph.microsoft.com/v1.0`
- Auth header: `Authorization: Bearer {access_token}`
- Pagination: `$top` và `@odata.nextLink` cho thư mục lớn
- Retry: Dùng `reqwest::RequestBuilder::try_clone()` cho retry logic

---

## R02 — OAuth 2.0 Flow cho Microsoft

### Decision
Dùng **OAuth Authorization Code Flow với PKCE** cho public-client application, mở system browser qua `tauri-plugin-opener` (đã có sẵn). Redirect URI dùng **loopback redirect** được đăng ký trong Microsoft app registration, mặc định:

```
http://localhost
```

Local callback server bind loopback interface (127.0.0.1). Port lấy từ cấu hình app registration hoặc dùng port động trong phạm vi ephemeral nếu implementation yêu cầu — nhưng phải chứng minh Microsoft app registration redirect matching cho phép. Authorization request và token exchange phải dùng cùng redirect URI.

### Rationale
- PKCE là best practice cho desktop apps (public clients), được Microsoft khuyến nghị.
- Loopback redirect (`http://localhost`) là redirect URI chuẩn cho desktop app registration theo Microsoft identity platform.
- `tauri-plugin-opener` đã có sẵn trong project.
- Local callback server bind `127.0.0.1`, không bind `0.0.0.0`.

### Official Evidence
- Microsoft identity platform: Authorization Code Flow with PKCE cho public client apps.
- Microsoft docs: Desktop app registration dùng `http://localhost` làm redirect URI.

### Security Requirements
- CSRF `state` parameter.
- PKCE `code_verifier` / `code_challenge` (S256).
- Callback listener chỉ bind loopback.
- Không log authorization code hoặc token.
- Timeout cho callback listener.

### Alternatives Considered
- **Device Code Flow**: Đơn giản hơn kỹ thuật nhưng UX kém (phải mở browser, nhập code).
- **Custom URI scheme**: Phức tạp cross-platform, dễ hijack.

### Implementation Notes
- Scopes: `Files.Read`, `offline_access`, `user.read`.
- Token refresh: Dùng refresh token, gọi `POST /common/oauth2/v2.0/token`.
- Token chỉ trong Rust process memory; không persist ra disk.
- Timeout: 120 giây cho toàn bộ flow.

---

## R03 — Upload Integration: Shared Core vs Adapters

### Decision
Tạo **shared internal upload function** (`upload_core`) trong `commands/fs.rs` nhận raw dependencies (`Arc<Mutex<Option<Client>>>`, `Arc<RwLock<HashMap<i64, Peer>>>`, ...) và trả về `Result<UploadResult, UploadError>`. Function này **không** tự quyết định retry/sleep/cooldown policy.

Hai adapter dùng chung core:

1. **Manual-upload adapter** (`cmd_upload_file`): Giữ nguyên policy retry/sleep hiện tại để tránh regression cho người dùng upload thủ công.

2. **Migration adapter** (`migration/upload_adapter.rs`): Nhận `UploadError::FloodWait{seconds}`, persist `cooldown_until`, không chọn upload mới trước cooldown, emit cooldown event, tự tiếp tục khi hết cooldown.

### Rationale
- Manual upload command không bị ảnh hưởng — policy hiện tại được giữ nguyên.
- Migration worker có policy riêng phù hợp (persist cooldown, không sleep).
- Shared core tránh code duplication.

### UploadResult / UploadError Structure
```rust
pub struct UploadResult {
    pub message_id: Option<i32>,  // Nullable — chỉ capture nếu Grammers trả về trực tiếp
    pub file_name: String,
    pub file_size: i64,
}

pub enum UploadError {
    FloodWait { seconds: i64 },
    TelegramLimit { reason: String },
    Network(String),
    Auth(String),
    Cancelled,
    Unknown(String),
}
```

### Alternatives Considered
- **REST localhost**: Thêm latency, phức tạp port management.
- **Copy-paste upload code**: Vi phạm DRY.
- **Parse string result**: Không phân biệt được FLOOD_WAIT với lỗi khác.

### Implementation Notes
- Extract core upload logic từ `cmd_upload_file_inner` (phần `client.upload_stream()` + `client.send_message()`).
- Extract `parse_flood_wait_seconds()` từ `map_error()`.
- Không refactor toàn bộ manual-upload flow — chỉ extract core function.
- Message ID: nullable — chỉ capture nếu exact return type của Grammers `send_message()` cung cấp trực tiếp tại call site. Không mở rộng refactor chỉ để lấy message ID.

---

## R04 — FLOOD_WAIT / Cooldown Handling

### Decision
Worker **không sleep** khi gặp FLOOD_WAIT. Thay vào đó:
1. Parse số giây từ error string
2. Trả lỗi `UploadError::FloodWait { seconds }` cho orchestrator
3. Orchestrator persist `cooldown_until = now + seconds + safety_buffer` vào DB
4. Worker check `cooldown_until` trước mỗi file, skip nếu đang trong cooldown
5. Tự động resume khi hết cooldown (không cần user action)

### Rationale
- Hiện tại `cmd_upload_file_inner` sleep trong retry loop → block Tauri command thread.
- MVP spec yêu cầu persist cooldown state để không mất khi restart.
- Safety buffer 10% (tối thiểu 60s) để tránh bị flood liên tục.

### Alternatives Considered
- **Giữ sleep trong upload function**: Block worker thread, không thể persist, restart mất cooldown state.
- **Adaptive safety levels (NORMAL/CONSERVATIVE/RESTRICTED/COOLDOWN)**: Complex cho MVP. Spec chỉ yêu cầu xử lý cooldown cơ bản.

### Implementation Notes
- `parse_flood_wait_seconds()` extract từ error string format `FLOOD_WAIT_{seconds}`.
- Safety buffer: `max(60, seconds * 0.10)`.
- Cooldown display: "Đang chờ Telegram: X phút Y giây còn lại".

---

## R05 — Duplicate Detection Strategy

### Decision
Hai tầng kiểm tra, dùng fingerprint types khác nhau cho provider và canonical:

1. **Pre-download check**: Nếu OneDrive API trả về `file.hashes.quickXorHash` (base64-encoded) → lưu với `fingerprint_type = "onedrive_quickxor"`, check `migrated_fingerprints` table với composite key `(fingerprint_type, fingerprint_value, file_size)`. Match → `skipped_duplicate`, không download. Nếu metadata có `sha1Hash`, có thể lưu với type `onedrive_sha1` riêng.

2. **Post-download check**: Sau khi download file về local, luôn tính SHA-256 từ file content → lưu với `fingerprint_type = "sha256"`, check `migrated_fingerprints`. Match → `skipped_duplicate`, xóa file tạm.

Chỉ fingerprint của file **upload thành công** mới được thêm vào `migrated_fingerprints`. Dùng chung giữa tất cả job.

### Rationale
- OneDrive cung cấp `quickXorHash` trong metadata `file.hashes` — đây là hash được Microsoft thiết kế cho duplicate detection. Không phải lúc nào cũng có (SharePoint, file lớn, file đang upload).
- `sha256Hash` từ OneDrive metadata không được hỗ trợ rộng rãi và không được dùng làm provider fingerprint trong plan này.
- SHA-256 canonical từ local content là phương án chắc chắn nhất cho post-download.
- Fingerprint-based (content) — không dùng tên file — theo đúng spec.

### Official Evidence
- Microsoft Graph API documentation: `file.hashes` object chứa `quickXorHash`, `sha1Hash`, `crc32Hash`. `sha256Hash` không được liệt kê trong tài liệu chính thức cho `driveItem.file.hashes`.
- QuickXorHash được Microsoft thiết kế riêng cho OneDrive duplicate detection.

### Alternatives Considered
- **sha256Hash từ OneDrive**: Không được hỗ trợ chính thức trong Graph API `file.hashes`.
- **Chỉ dùng OneDrive hash**: Không khả thi vì hash không phải lúc nào cũng có.
- **Dùng tên file + kích thước**: Không phát hiện được file cùng nội dung khác tên.

### Implementation Notes
- SHA-256 compute trong streaming download — vừa ghi file vừa hash.
- KHÔNG so sánh QuickXorHash với SHA-256 — mỗi fingerprint type chỉ so sánh trong cùng type.
- KHÔNG skip chỉ dựa trên filename, relative path, modified time, hoặc file size.
- Fingerprint composite uniqueness: `(fingerprint_type, fingerprint_value, file_size)`. File size phải khớp.

---

## R06 — OneDrive eTag cho Source Change Detection

### Decision
Dùng `eTag` từ OneDrive metadata nếu có, fallback `size + lastModifiedDateTime`. Khi download bắt đầu, so sánh eTag/size+lastModified hiện tại với giá trị đã lưu trong snapshot. Khác → `source_changed`.

### Rationale
- eTag thay đổi mỗi khi nội dung file hoặc metadata thay đổi.
- Không phải lúc nào eTag cũng có sẵn trong response.
- Fallback size+lastModified là phương án thực tế cho mọi trường hợp.

### Implementation Notes
- Lưu `source_etag` và `source_last_modified` trong `migration_items` khi scan.
- Trước khi download, gọi `GET /me/drive/items/{item-id}` để lấy eTag/size/lastModified hiện tại.
- Nếu eTag khác (hoặc size/lastModified khác khi không có eTag) → `source_changed`.

---

## R07 — Download Strategy

### Decision
Stream download từ OneDrive `@microsoft.graph.downloadUrl` vào file `.part` trong thư mục local của job. Compute SHA-256 trong quá trình stream. Không hỗ trợ resume download (HTTP Range) cho MVP.

### Rationale
- Download URL từ Microsoft Graph là pre-signed URL, có thể hết hạn.
- Stream tránh load toàn bộ file vào RAM.
- `.part` extension phân biệt file đang download với file hoàn chỉnh.
- HTTP Range resume không được yêu cầu trong MVP spec, tăng độ phức tạp.

### Alternatives Considered
- **Download vào memory rồi ghi disk**: Vi phạm NFR-001 (memory).
- **HTTP Range resume**: Không cần cho MVP, download URL có thể hết hạn.

### Implementation Notes
- File path: `{local_dir}/.migration/{job_id}/{item_id}.part`
- Sau download thành công: rename `.part` → giữ nguyên tên gốc để upload
- Error handling: retry 3 lần cho lỗi mạng, exponential backoff

---

## R08 — Disk Space Check

### Decision
Trước mỗi lần download, kiểm tra dung lượng trống của thư mục local. Nếu `free_space < file_size + 100 MiB` → dừng job, hiển thị lỗi.

### Rationale
- 100 MiB safety margin đủ cho OS operations và overhead.
- Dừng job (không skip file) để người dùng nhận biết và xử lý.

### Implementation Notes
- Dùng `std::fs` hoặc platform-specific API để query free space.
- Fallback: nếu không query được free space, vẫn thử download và để OS báo lỗi.

---

## R09 — Frontend Architecture

### Decision
Single page `OneDriveMigrationPage` với state trong React hook `useMigration`. Tích hợp vào sidebar như một nav item mới (dưới dạng "OneDrive Migration" bên cạnh các folder group tabs).

### Rationale
- Project không dùng router — tất cả UI được quản lý qua state machine.
- Sidebar đã có pattern nav items (folder group tabs), thêm item mới đơn giản.
- Hook pattern nhất quán với các hook hiện có (`useFileUpload`, `useFileDownload`).

### Alternatives Considered
- **Multi-page với internal routing**: Over-engineering, không có router trong project.
- **Modal/Sheet**: Không phù hợp cho tính năng cần hiển thị nhiều thông tin (bảng file, thống kê).

### Implementation Notes
- `useMigration` hook quản lý: current job, file list, stats, progress, controls.
- Dùng `invoke` cho commands, `listen` cho events.
- Toast notifications qua `sonner` — pattern giống các hook hiện có.
- Platform-aware: chỉ hiển thị trên desktop (`usePlatform().isDesktop`).

---

## R10 — Event System

### Decision
5 events từ backend → frontend:

| Event | Payload | Khi nào emit |
|-------|---------|-------------|
| `migration:job-state` | `{ job_id, state }` | Job chuyển trạng thái |
| `migration:item-progress` | `{ job_id, item_id, state, percent, bytes_done, bytes_total }` | Tiến độ download/upload |
| `migration:item-complete` | `{ job_id, item_id, status, error? }` | File hoàn thành (success/fail/skip) |
| `migration:stats` | `{ job_id, stats: MigrationStats }` | Thống kê thay đổi |
| `migration:cooldown` | `{ job_id, cooldown_until, seconds_remaining }` | Bắt đầu/kết thúc cooldown |

### Rationale
- Pattern `emit`/`listen` giống `upload-progress` hiện có.
- Tách biệt job state, item progress, stats để frontend update từng phần UI độc lập.
- 5 events là đủ cho MVP, không over-engineer.

---

## R11 — Authentication Persistence

### Decision
**KHÔNG persist Microsoft access token hoặc refresh token ra disk.** Token chỉ nằm trong Rust process memory. Sau khi app đóng/restart, token bị mất.

### Rationale
- MVP không có secure storage (không Tauri Stronghold, không OS keychain).
- Tránh thêm dependency và complexity cho token encryption.
- Restart behavior: người dùng phải Connect Microsoft lại, sau đó nhấn Resume. Migration job, snapshot và completed progress vẫn được giữ trong migration.db.
- Tuân thủ NFR-005: không log token.

### Restart Flow
```
App restart → job & progress vẫn tồn tại
→ Microsoft session không còn (token in-memory lost)
→ UI hiển thị "Chưa kết nối Microsoft"
→ Người dùng Connect Microsoft lại
→ Người dùng nhấn Resume
→ Worker tiếp tục từ file pending tiếp theo
```

### Alternatives Considered
- **Token trong SQLite (plaintext)**: Vi phạm security best practice.
- **Token encrypted trong SQLite**: Thêm complexity (key derivation, encryption), không cần cho MVP.
- **Tauri Stronghold**: Chưa tích hợp, thêm dependency.
- **OS keychain (keyring crate)**: Tốt hơn nhưng thêm dependency. Có thể upgrade trong phiên bản sau.

### Future Enhancement (rejected for MVP)
OS keychain hoặc Tauri Stronghold để persist token an toàn giữa các lần restart.

---

## R12 — Message ID Strategy

### Decision
`telegram_message_id` là **nullable** (`Option<i32>`).

### Rationale
- Capture message ID nếu exact return type của `grammers_client::Client::send_message()` tại call site cung cấp trực tiếp (không cần parse hay extra RPC call).
- Nếu không thể lấy message ID mà không cần refactor, upload success boolean/result vẫn đủ cho MVP.
- Duplicate history không phụ thuộc vào message ID — dùng content fingerprint.
- Không mở rộng refactor chỉ để lấy message ID.

### Implementation Notes
- `UploadResult.message_id: Option<i32>`.
- Contracts phản ánh nullable.
- Migration items table: `telegram_message_id INTEGER` (nullable).

---

## R13 — Test Doubles Strategy

### Decision
Định nghĩa interface/trait nhỏ cho cả Microsoft và Telegram boundaries để hỗ trợ unit/integration testing:

**Microsoft boundary trait:**
```rust
trait OneDriveClient {
    async fn list_children(&self, folder_id: &str) -> Result<Vec<DriveItem>, Error>;
    async fn get_item_metadata(&self, item_id: &str) -> Result<DriveItemMetadata, Error>;
    async fn download_item(&self, item_id: &str, dest: &Path) -> Result<(u64, String), Error>;
}
```
Production: `reqwest` + Microsoft Graph. Test: in-memory fake với file list được setup trước.

**Telegram boundary trait:**
```rust
trait UploadAdapter {
    async fn upload(&self, path: &str, folder_id: Option<i64>) -> Result<UploadResult, UploadError>;
}
```
Production: gọi `upload_core()` shared function. Test: fake trả về success/error theo cấu hình.

### Database Tests
Dùng temporary SQLite database (in-memory hoặc temp file). Tests tối thiểu: schema creation, one active job, snapshot batch insert, provider duplicate, SHA-256 duplicate, upload success transaction, startup recovery mapping, retry limit, cooldown gate.

### Rationale
- Trait-based test doubles là pattern Rust chuẩn, không cần thêm dependency.
- Không cần HTTP mock-server dependency.
- Temporary SQLite cho DB tests là pattern phổ biến.

### Explicitly Not Required
- HTTP mock-server (wiremock, mockito).
- Frontend test framework (Jest, Vitest, Playwright).

---

## R12 — File Size Limit

### Decision
Không hardcode file size limit. Không dùng một con số cố định (2GB, 4GB, v.v.) làm giới hạn Telegram.

### Rationale
- Telegram limit có thể thay đổi theo thời gian và theo loại tài khoản.
- Hardcode limit dẫn đến false positive hoặc false negative.
- Để upload adapter trả về `UploadError::TelegramLimit` khi Telegram/client từ chối file.

### Implementation Notes
- KHÔNG pre-reject dựa trên số hardcoded.
- Nếu codebase có runtime/config limit đáng tin cậy từ Telegram API, dùng giá trị đó.
- Nếu không có, upload adapter trả `telegram_file_too_large` khi upload bị từ chối.
- Lỗi này không auto-retry.
- Cleanup file tạm theo policy (giữ nếu cần retry thủ công, xóa nếu không).

---

## R13 — Migration Snapshot

### Decision
Snapshot là danh sách file cố định, lưu trong `migration_items`. Rescan chỉ khi job ở trạng thái `draft` hoặc `ready` (chưa running). Rescan tạo snapshot mới, thay thế snapshot cũ.

### Rationale
- Spec yêu cầu snapshot cố định (không tự động thêm file mới sau scan).
- Rescan là hành động chủ động của người dùng.

### Implementation Notes
- Scan đệ quy qua Microsoft Graph API, dùng `$top=1000` và `@odata.nextLink`.
- Mỗi item lưu: tên, relative path, size, source_etag (nếu có), source_last_modified, source_fingerprint_type, source_fingerprint_value.
- Thư mục được lưu trong `migration_items` với type `folder` để hiển thị tree.

---

## Tóm tắt Quyết định

| ID | Chủ đề | Quyết định |
|----|--------|-----------|
| R01 | MS Graph API | REST API qua reqwest |
| R02 | OAuth Flow | Auth Code + PKCE, loopback redirect |
| R03 | Upload integration | Shared core + manual/migration adapters |
| R04 | FLOOD_WAIT | Parse seconds, persist cooldown, không sleep |
| R05 | Duplicate detection | Pre: OneDrive QuickXorHash; Post: SHA-256 |
| R06 | Source change | eTag ưu tiên, fallback size+lastModified |
| R07 | Download | Stream vào .part, SHA-256 trong stream, không Range |
| R08 | Disk check | free_space >= file_size + 100 MiB |
| R09 | Frontend | Single page + useMigration hook, sidebar nav |
| R10 | Events | 5 events |
| R11 | Auth persistence | Token in-memory only, không persist |
| R12 | File size limit | Không hardcode, để upload trả telegram_file_too_large |
| R13 | Snapshot | Cố định, rescan thủ công khi job chưa running |
| R14 | Message ID | Nullable, chỉ capture nếu Grammers trả về trực tiếp |
| R15 | Test doubles | Trait-based fake cho Microsoft + Telegram boundaries |
