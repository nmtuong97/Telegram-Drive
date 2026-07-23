# Tauri Commands — OneDrive Migration

> **Loại:** Interface Contract (Backend → Frontend)  
> **Cơ chế:** `invoke()` từ frontend React  
> **Module backend:** `app/src-tauri/src/migration/`

---

## Quy ước

- Mọi command trả về `Result<T, String>` (Tauri serializes thành JSON)
- Tham số dùng camelCase (Tauri convention)
- Command name dùng snake_case (Rust convention), frontend gọi với camelCase

---

## Job Management Commands

### `migration_create_job`

Tạo job migration mới từ cấu hình wizard.

```rust
#[tauri::command]
async fn migration_create_job(
    db: State<'_, MigrationDb>,
    config: MigrationJobConfig,
) -> Result<MigrationJob, String>;
```

**Input (`MigrationJobConfig`):**

```typescript
interface MigrationJobConfig {
  name: string;
  microsoft_account_id: string;
  drive_id: string;
  source_folder_path: string;
  destination_peer_id: number;
  destination_folder_id?: number | null;
  delete_after_upload: boolean;
  stability_window_seconds: number;
  scan_interval_seconds: number;
  include_patterns?: string;    // comma-separated extensions
  exclude_patterns?: string;    // comma-separated extensions
  min_size_bytes?: number;
}
```

**Output (`MigrationJob`):**

```typescript
interface MigrationJob {
  id: number;
  name: string;
  status: JobStatus;
  source_folder_path: string;
  delete_after_upload: boolean;
  created_at: string;  // ISO 8601
  // ... đầy đủ các trường từ data-model
}

type JobStatus = 
  | 'created' | 'preflight' | 'running' | 'paused' | 'waiting'
  | 'graph_cooldown' | 'telegram_cooldown' | 'low_disk' | 'auth_required'
  | 'stopping'
  | 'completed' | 'cancelled' | 'fatal';
```

---

### `migration_start_job`

Bắt đầu migration sau khi preflight thành công.

```rust
#[tauri::command]
async fn migration_start_job(
    db: State<'_, MigrationDb>,
    scheduler: State<'_, MigrationSchedulerHandle>,
    job_id: i64,
) -> Result<(), String>;
```

---

### `migration_pause_job`

Tạm dừng migration (hoàn tất item đang xử lý rồi dừng).

```rust
#[tauri::command]
async fn migration_pause_job(
    db: State<'_, MigrationDb>,
    scheduler: State<'_, MigrationSchedulerHandle>,
    job_id: i64,
) -> Result<(), String>;
```

---

### `migration_resume_job`

Tiếp tục migration đã tạm dừng.

```rust
#[tauri::command]
async fn migration_resume_job(
    db: State<'_, MigrationDb>,
    scheduler: State<'_, MigrationSchedulerHandle>,
    job_id: i64,
) -> Result<(), String>;
```

---

### `migration_cancel_job`

Hủy toàn bộ migration.

```rust
#[tauri::command]
async fn migration_cancel_job(
    db: State<'_, MigrationDb>,
    scheduler: State<'_, MigrationSchedulerHandle>,
    job_id: i64,
) -> Result<(), String>;
```

---

### `migration_skip_item`

Bỏ qua file lỗi, đánh dấu `skipped`.

```rust
#[tauri::command]
async fn migration_skip_item(
    db: State<'_, MigrationDb>,
    job_id: i64,
    item_id: i64,
) -> Result<(), String>;
```

---

### `migration_retry_item`

Thử lại file lỗi, đặt lại về `queued`.

```rust
#[tauri::command]
async fn migration_retry_item(
    db: State<'_, MigrationDb>,
    job_id: i64,
    item_id: i64,
) -> Result<(), String>;
```

---

## Query Commands

### `migration_get_jobs`

Lấy danh sách tất cả job migration.

```rust
#[tauri::command]
async fn migration_get_jobs(
    db: State<'_, MigrationDb>,
) -> Result<Vec<MigrationJobSummary>, String>;
```

**Output:**

```typescript
interface MigrationJobSummary {
  id: number;
  name: string;
  status: JobStatus;
  source_folder_path: string;
  completed_items: number;
  total_items: number;
  completed_bytes: number;
  total_bytes: number;
  error_items: number;
  created_at: string;
}
```

---

### `migration_get_job_detail`

Lấy chi tiết job + danh sách items (có phân trang).

```rust
#[tauri::command]
async fn migration_get_job_detail(
    db: State<'_, MigrationDb>,
    job_id: i64,
    status_filter: Option<String>,  // Lọc theo trạng thái item
    page: Option<u32>,
    page_size: Option<u32>,
) -> Result<MigrationJobDetail, String>;
```

**Output:**

```typescript
interface MigrationJobDetail {
  job: MigrationJob;
  stats: {
    completed: number;
    queued: number;
    downloading: number;
    uploading: number;
    verifying: number;
    error: number;
    skipped: number;
    total: number;
    completed_bytes: number;
    total_bytes: number;
  };
  items: MigrationItem[];       // Trang hiện tại
  total_items: number;          // Tổng số item (để phân trang)
}
```

---

### `migration_get_events`

Lấy nhật ký sự kiện (có phân trang).

```rust
#[tauri::command]
async fn migration_get_events(
    db: State<'_, MigrationDb>,
    job_id: i64,
    severity: Option<String>,   // 'info' | 'warn' | 'error'
    limit: Option<u32>,         // Default: 50
    offset: Option<u32>,        // Default: 0
) -> Result<Vec<MigrationEvent>, String>;
```

---

## Microsoft Auth Commands

### `microsoft_connect`

Bắt đầu OAuth PKCE flow, trả về auth URL để mở browser.

```rust
#[tauri::command]
async fn microsoft_connect(
    graph: State<'_, GraphClient>,
) -> Result<MicrosoftConnectResult, String>;
```

**Output:**

```typescript
interface MicrosoftConnectResult {
  auth_url: string;              // Mở trong browser
  state: string;                 // State để xác minh callback
  port: number;                  // Loopback port đang listen
}
```

---

### `microsoft_complete_auth`

Hoàn thành OAuth sau khi user đăng nhập trên browser.

```rust
#[tauri::command]
async fn microsoft_complete_auth(
    graph: State<'_, GraphClient>,
    db: State<'_, MigrationDb>,
    code: String,
    state: String,
) -> Result<MicrosoftAccount, String>;
```

**Output:**

```typescript
interface MicrosoftAccount {
  account_id: string;
  display_name: string;
  email: string;
}
```

---

### `microsoft_disconnect`

Ngắt kết nối Microsoft, xóa token đã lưu.

```rust
#[tauri::command]
async fn microsoft_disconnect(
    graph: State<'_, GraphClient>,
) -> Result<(), String>;
```

---

### `microsoft_status`

Kiểm tra trạng thái kết nối Microsoft.

```rust
#[tauri::command]
async fn microsoft_status(
    graph: State<'_, GraphClient>,
) -> Result<MicrosoftStatus, String>;
```

**Output:**

```typescript
interface MicrosoftStatus {
  connected: boolean;
  account?: {
    account_id: string;
    display_name: string;
    email: string;
  };
  token_expires_at?: string;      // ISO 8601
}
```

---

### `microsoft_list_folders`

Duyệt thư mục OneDrive.

```rust
#[tauri::command]
async fn microsoft_list_folders(
    graph: State<'_, GraphClient>,
    drive_id: String,
    parent_path: Option<String>,   // NULL = root
) -> Result<Vec<OneDriveFolder>, String>;
```

**Output:**

```typescript
interface OneDriveFolder {
  id: string;
  name: string;
  path: string;
  child_count: number;
}
```

---

### `microsoft_get_drives`

Lấy danh sách drive khả dụng.

```rust
#[tauri::command]
async fn microsoft_get_drives(
    graph: State<'_, GraphClient>,
) -> Result<Vec<OneDriveInfo>, String>;
```

**Output:**

```typescript
interface OneDriveInfo {
  id: string;
  name: string;
  type: string;         // 'personal' | 'business' | 'sharepoint'
  owner: string;
}
```

---

## Preflight Command

### `migration_run_preflight`

Chạy kiểm tra trước khi khởi động migration.

```rust
#[tauri::command]
async fn migration_run_preflight(
    graph: State<'_, GraphClient>,
    config: MigrationJobConfig,
) -> Result<PreflightReport, String>;
```

**Output:**

```typescript
interface PreflightReport {
  microsoft_connected: boolean;
  microsoft_account?: string;
  telegram_connected: boolean;
  source_folder_exists: boolean;
  source_folder_path: string;
  destination_name: string;
  total_files_found: number;
  eligible_files: number;
  skipped_files: number;
  total_size_bytes: number;
  estimated_duration: string;       // VD: "~3 giờ"
  warnings: PreflightWarning[];
}

interface PreflightWarning {
  severity: 'info' | 'warn' | 'error';
  code: string;
  message: string;
}
```
