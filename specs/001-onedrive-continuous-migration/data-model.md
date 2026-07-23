# Mô hình Dữ liệu — OneDrive Continuous Migration

> **Database:** `{app_data}/migration/migration.db`  
> **Engine:** SQLite qua crate `sqlite` 0.37.0  
> **Kiểu kết nối:** `Arc<Mutex<sqlite::Connection>>`

---

## Thiết lập Database

Chạy tại `migration::db::init_migration_db()`:

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = FULL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 10000;
```

## Schema Migrations

Hệ thống migration dựa trên version:

```rust
const MIGRATIONS: &[(i32, &str)] = &[
    (1, include_str!("migrations/001_initial.sql")),
];
```

Bảng `schema_version` theo dõi phiên bản đã áp dụng. Trước mỗi migration, database được backup.

---

## Entity 1: MigrationJob

Đại diện cho một tác vụ migration từ thư mục OneDrive đến đích Telegram.

| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| `id` | INTEGER | PK, AUTOINCREMENT | ID job |
| `name` | TEXT | NOT NULL | Tên thân thiện |
| `microsoft_account_id` | TEXT | NOT NULL | Tài khoản Microsoft |
| `drive_id` | TEXT | NOT NULL | OneDrive drive ID |
| `source_folder_path` | TEXT | NOT NULL | Đường dẫn thư mục nguồn |
| `destination_peer_id` | INTEGER | NOT NULL | Telegram peer ID đích |
| `destination_folder_id` | INTEGER | NULLABLE | Telegram folder ID (NULL = Saved Messages) |
| `status` | TEXT | NOT NULL, DEFAULT 'created' | Trạng thái job |
| `delete_after_upload` | INTEGER | NOT NULL, DEFAULT 0 | 1 = xóa nguồn, 0 = giữ |
| `stability_window_seconds` | INTEGER | NOT NULL, DEFAULT 600 | Thời gian ổn định (giây) |
| `scan_interval_seconds` | INTEGER | NOT NULL, DEFAULT 600 | Chu kỳ quét (giây) |
| `delta_link` | TEXT | NULLABLE | Microsoft Graph deltaLink |
| `graph_cooldown_until` | TEXT | NULLABLE | ISO 8601 |
| `telegram_cooldown_until` | TEXT | NULLABLE | ISO 8601 |
| `created_at` | TEXT | NOT NULL, DEFAULT (datetime('now')) | ISO 8601 |
| `started_at` | TEXT | NULLABLE | ISO 8601 |
| `completed_at` | TEXT | NULLABLE | ISO 8601 |
| `last_error` | TEXT | NULLABLE | Lỗi cuối cùng ở cấp job |

### Trạng thái Job

```
created → preflight → running ⇄ paused
                ↓         ↓
               fatal    waiting
                        graph_cooldown
                        telegram_cooldown
                        low_disk
                        auth_required
                        stopping
                           ↓
                      completed | cancelled | fatal
```

### Chuyển đổi hợp lệ

```rust
(Created, Preflight) | (Created, Cancelled) |
(Preflight, Running) | (Preflight, Fatal) |
(Running, Paused) | (Running, Waiting) |
(Running, GraphCooldown) | (Running, TelegramCooldown) |
(Running, LowDisk) | (Running, AuthRequired) | (Running, Stopping) |
(Running, Completed) | (Running, Cancelled) | (Running, Fatal) |
(Paused, Running) | (Paused, Cancelled) | (Paused, Stopping) |
(Waiting, Running) |
(GraphCooldown, Running) | (TelegramCooldown, Running) |
(LowDisk, Running) | (AuthRequired, Running) |
(Stopping, Cancelled) | (Stopping, Completed)
```

---

## Entity 2: MigrationItem

Đại diện cho một file cần xử lý trong job.

| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| `id` | INTEGER | PK, AUTOINCREMENT | ID item |
| `job_id` | INTEGER | FK → migration_jobs.id, NOT NULL | Job sở hữu |
| `drive_item_id` | TEXT | NOT NULL | OneDrive item ID |
| `relative_path` | TEXT | NOT NULL | Đường dẫn tương đối |
| `original_name` | TEXT | NOT NULL | Tên file gốc |
| `telegram_physical_name` | TEXT | NOT NULL | `tdm_{hash}__{original}` |
| `migration_key` | TEXT | NOT NULL | SHA-256(account+drive+item+etag) |
| `file_size` | INTEGER | NOT NULL | Kích thước (bytes) |
| `source_etag` | TEXT | NOT NULL | OneDrive eTag lúc phát hiện |
| `source_last_modified` | TEXT | NOT NULL | ISO 8601 |
| `stable_first_seen_at` | TEXT | NOT NULL | Lần đầu phát hiện |
| `stable_observation_count` | INTEGER | NOT NULL, DEFAULT 1 | Số lần quan sát ổn định |
| `status` | TEXT | NOT NULL, DEFAULT 'discovered' | Trạng thái item |
| `local_path` | TEXT | NULLABLE | Đường dẫn file `.ready` |
| `downloaded_bytes` | INTEGER | NULLABLE | Byte đã tải (checkpoint) |
| `download_started_at` | TEXT | NULLABLE | ISO 8601 |
| `download_completed_at` | TEXT | NULLABLE | ISO 8601 |
| `upload_started_at` | TEXT | NULLABLE | ISO 8601 |
| `telegram_message_id` | INTEGER | NULLABLE | Telegram message ID |
| `upload_completed_at` | TEXT | NULLABLE | ISO 8601 |
| `verified_at` | TEXT | NULLABLE | ISO 8601 |
| `source_deleted_at` | TEXT | NULLABLE | ISO 8601 |
| `attempt_count` | INTEGER | NOT NULL, DEFAULT 0 | Số lần thử |
| `next_retry_at` | TEXT | NULLABLE | ISO 8601 |
| `last_error_class` | TEXT | NULLABLE | retryable / cooldown / auth / permanent / conflict |
| `last_error_message` | TEXT | NULLABLE | Mô tả lỗi |
| `created_at` | TEXT | NOT NULL, DEFAULT (datetime('now')) | ISO 8601 |
| `updated_at` | TEXT | NOT NULL, DEFAULT (datetime('now')) | ISO 8601 |

### Ràng buộc UNIQUE

```sql
UNIQUE(job_id, drive_item_id, source_etag)
```

Ngăn việc đưa lại cùng phiên bản file vào queue. Khi file thay đổi, eTag mới tạo hàng mới.

### Trạng thái Item

```
discovered → stabilizing → queued → downloading → downloaded →
upload_intent → uploading → uploaded → verifying → verified →
delete_pending → deleting_source → completed

Nhánh lỗi/phục hồi:
  uploading → upload_unknown → uploaded (1 match) | uploading (0 match, retry) | quarantined (2+ matches)
  stabilizing → source_changed
  deleting_source → source_changed | delete_failed → deleting_source (retry DELETE only)
  * → skipped (thủ công)
  * → quarantined (lỗi vĩnh viễn hoặc multi-match conflict)
```

> **Ghi chú (D10)**: Với MVP single-process + single-instance, item không cần `leased`/distributed locking. Có thể dùng `processing_owner` marker nhẹ nếu cần, nhưng không xây distributed lease.

### Chuyển đổi hợp lệ

```rust
// Happy path
(Discovered, Stabilizing) |
(Stabilizing, Queued) | (Stabilizing, SourceChanged) |
(Queued, Downloading) |
(Downloading, Downloaded) |
(Downloaded, UploadIntent) |
(UploadIntent, Uploading) |
(Uploading, Uploaded) |
(Uploaded, Verifying) |
(Verifying, Verified) |
(Verified, DeletePending) |
(DeletePending, DeletingSource) |
(DeletingSource, Completed) |

// Recovery
(Uploading, UploadUnknown) |
(UploadUnknown, Uploaded) | (UploadUnknown, Uploading) | (UploadUnknown, Quarantined) |

// Error paths
(Downloading, Queued) |        // Retry
(DeletingSource, DeleteFailed) |
(DeleteFailed, DeletingSource) | // Retry DELETE only, no re-download
(DeletingSource, SourceChanged) |
(Verifying, UploadUnknown) |

// Terminal / manual
(Queued, Skipped) | (Discovered, Skipped) |
(Stabilizing, Skipped) |
(Quarantined, Queued) |       // Manual retry
(SourceChanged, Queued)       // User chooses to migrate new version
```

---

## Entity 3: MigrationEvent

Nhật ký kiểm toán cho mọi sự kiện quan trọng.

| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| `id` | INTEGER | PK, AUTOINCREMENT | ID event |
| `job_id` | INTEGER | FK → migration_jobs.id, NOT NULL | Job liên quan |
| `item_id` | INTEGER | FK → migration_items.id, NULLABLE | Item liên quan (NULL = event cấp job) |
| `severity` | TEXT | NOT NULL | info / warn / error |
| `category` | TEXT | NOT NULL | state_transition / download / upload / verify / delete / system |
| `event_code` | TEXT | NOT NULL | Mã sự kiện, VD: `item_queued`, `upload_completed`, `delete_failed` |
| `message` | TEXT | NOT NULL | Mô tả |
| `details_json` | TEXT | NULLABLE | Metadata JSON |
| `created_at` | TEXT | NOT NULL, DEFAULT (datetime('now')) | ISO 8601 |

### Mã sự kiện tiêu biểu

| Mã | Category | Mô tả |
|---|---|---|
| `job_created` | system | Job được tạo |
| `job_started` | state_transition | Job bắt đầu chạy |
| `job_paused` | state_transition | Job tạm dừng |
| `job_resumed` | state_transition | Job tiếp tục |
| `job_completed` | state_transition | Job hoàn thành |
| `item_discovered` | system | File mới được phát hiện |
| `item_queued` | state_transition | File vào queue |
| `download_started` | download | Bắt đầu tải |
| `download_completed` | download | Tải hoàn tất |
| `download_resumed` | download | Resume download |
| `upload_started` | upload | Bắt đầu upload |
| `upload_completed` | upload | Upload hoàn tất |
| `verify_completed` | verify | Xác minh hoàn tất |
| `source_deleted` | delete | Xóa nguồn thành công |
| `delete_failed` | delete | Xóa nguồn thất bại |
| `flood_wait` | system | Telegram FLOOD_WAIT |
| `graph_cooldown` | system | Graph rate limit |
| `auth_required` | system | Cần xác thực lại |

### Chính sách lưu giữ

- Xóa events cũ hơn 90 ngày
- Giới hạn tối đa 100,000 events mỗi job
- Chạy cleanup mỗi 24 giờ

---

## Entity 4: RateLimitState

Hàng singleton cho mỗi dịch vụ bên ngoài.

| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| `service` | TEXT | PK | 'telegram' hoặc 'graph' |
| `cooldown_until` | TEXT | NULLABLE | ISO 8601 |
| `safety_level` | INTEGER | NOT NULL, DEFAULT 0 | 0=Normal, 1=Conservative, 2=Restricted, 3=Cooldown |
| `success_streak` | INTEGER | NOT NULL, DEFAULT 0 | Số lần thành công liên tiếp |
| `flood_count` | INTEGER | NOT NULL, DEFAULT 0 | Tổng số lần flood |
| `last_flood_at` | TEXT | NULLABLE | ISO 8601 |
| `updated_at` | TEXT | NOT NULL, DEFAULT (datetime('now')) | ISO 8601 |

### Cấp độ an toàn

| Cấp độ | Pacing delay multiplier | Mô tả |
|---|---|---|
| 0 (Normal) | 1.0× | Hoạt động bình thường |
| 1 (Conservative) | 1.5× | Sau 1 lần FLOOD_WAIT |
| 2 (Restricted) | 2.0× | Sau 2+ lần FLOOD_WAIT |
| 3 (Cooldown) | ∞ (chặn hoàn toàn) | Đang trong FLOOD_WAIT |

Giảm cấp sau 50 lần thành công liên tiếp.

---

## Entity 5: WorkerHeartbeat

Hàng singleton (CHECK constraint). Theo dõi trạng thái worker.

| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| `singleton_id` | INTEGER | PK, CHECK(singleton_id = 1) | Luôn là 1 |
| `worker_id` | TEXT | NOT NULL | UUID sinh khi worker khởi động |
| `pid` | INTEGER | NOT NULL | Process ID |
| `started_at` | TEXT | NOT NULL | ISO 8601 |
| `heartbeat_at` | TEXT | NOT NULL | ISO 8601, cập nhật mỗi 5s |
| `current_job_id` | INTEGER | FK → migration_jobs.id, NULLABLE | Job đang xử lý |
| `current_item_id` | INTEGER | FK → migration_items.id, NULLABLE | Item đang xử lý |
| `current_phase` | TEXT | NOT NULL | scanning / downloading / uploading / idle |

---

## Mẫu Truy vấn

### Dashboard Stats

```sql
-- Tổng quan job
SELECT 
    status,
    COUNT(*) as count
FROM migration_items 
WHERE job_id = ? 
GROUP BY status;

-- File đang xử lý
SELECT * FROM migration_items 
WHERE job_id = ? AND status IN ('downloading', 'uploading', 'verifying', 'deleting_source')
ORDER BY updated_at DESC;

-- File lỗi cần chú ý
SELECT * FROM migration_items 
WHERE job_id = ? AND last_error_class IN ('permanent', 'conflict')
ORDER BY updated_at DESC;

-- Tiến độ dung lượng
SELECT 
    COUNT(CASE WHEN status = 'completed' THEN 1 END) as completed_count,
    SUM(CASE WHEN status = 'completed' THEN file_size ELSE 0 END) as completed_bytes,
    COUNT(*) as total_count,
    SUM(file_size) as total_bytes
FROM migration_items 
WHERE job_id = ?;
```

### Queue

```sql
-- Item sẵn sàng xử lý (đã sắp xếp ưu tiên)
SELECT * FROM migration_items 
WHERE job_id = ? AND status = 'queued' 
ORDER BY file_size ASC, created_at ASC 
LIMIT 1;

-- Item cần retry
SELECT * FROM migration_items 
WHERE job_id = ? 
  AND status = 'queued' 
  AND next_retry_at <= datetime('now')
ORDER BY attempt_count ASC, next_retry_at ASC;
```

### Phục hồi

```sql
-- Tất cả item non-terminal cần reconcile
SELECT * FROM migration_items 
WHERE job_id = ? 
  AND status NOT IN ('completed', 'skipped', 'quarantined', 'source_changed', 'delete_failed')
ORDER BY updated_at ASC;

-- Item cần tìm message đã upload
SELECT * FROM migration_items 
WHERE job_id = ? AND status = 'upload_unknown';
```
