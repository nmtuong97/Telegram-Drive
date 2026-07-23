# Tauri Events — OneDrive Migration

> **Loại:** Interface Contract (Backend → Frontend)  
> **Cơ chế:** `emit()` từ Rust backend, `listen()` ở React frontend  
> **Module backend:** `app/src-tauri/src/migration/scheduler.rs` & `downloader.rs`

---

## Quy ước

- Event name dùng kebab-case
- Payload được serialize qua serde JSON
- Frontend đăng ký listener qua `listen<T>(eventName, callback)`

---

## Job Events

### `migration-job-updated`

Phát ra khi trạng thái job thay đổi.

**Payload:** `MigrationJob` (đầy đủ)

```typescript
interface MigrationJob {
  id: number;
  name: string;
  status: JobStatus;
  microsoft_account_id: string;
  drive_id: string;
  source_folder_path: string;
  destination_peer_id: number;
  destination_folder_id: number | null;
  delete_after_upload: boolean;
  stability_window_seconds: number;
  scan_interval_seconds: number;
  graph_cooldown_until: string | null;
  telegram_cooldown_until: string | null;
  created_at: string;
  started_at: string | null;
  completed_at: string | null;
  last_error: string | null;
}
```

---

## Item Events

### `migration-item-updated`

Phát ra khi trạng thái item thay đổi.

**Payload:** `MigrationItem` (đầy đủ)

```typescript
interface MigrationItem {
  id: number;
  job_id: number;
  drive_item_id: string;
  relative_path: string;
  original_name: string;
  telegram_physical_name: string;
  file_size: number;
  source_etag: string;
  status: ItemStatus;
  local_path: string | null;
  downloaded_bytes: number | null;
  telegram_message_id: number | null;
  attempt_count: number;
  last_error_class: string | null;
  last_error_message: string | null;
  created_at: string;
  updated_at: string;
}

type ItemStatus =
  | 'discovered' | 'stabilizing' | 'queued'
  | 'downloading' | 'downloaded'
  | 'upload_intent'
  | 'uploading' | 'upload_unknown' | 'uploaded'
  | 'verifying' | 'verified'
  | 'delete_pending' | 'deleting_source' | 'delete_failed'
  | 'completed' | 'source_changed' | 'skipped' | 'quarantined';
```

---

## Event Log Events

### `migration-event`

Phát ra khi có sự kiện mới trong nhật ký.

**Payload:** `MigrationEvent`

```typescript
interface MigrationEvent {
  id: number;
  job_id: number;
  item_id: number | null;
  severity: 'info' | 'warn' | 'error';
  category: 'state_transition' | 'download' | 'upload' | 'verify' | 'delete' | 'system';
  event_code: string;
  message: string;
  details_json: string | null;    // JSON string, parse để lấy chi tiết
  created_at: string;
}
```

---

## Progress Events

### `migration-download-progress`

Phát ra định kỳ trong quá trình download file (mỗi 250ms).

**Payload:**

```typescript
interface MigrationDownloadProgress {
  item_id: number;
  job_id: number;
  file_name: string;
  bytes_downloaded: number;
  total_bytes: number;
  speed_bytes_per_sec: number;
  percentage: number;         // 0-100
}
```

---

### `migration-upload-progress`

Phát ra định kỳ trong quá trình upload file (mỗi 250ms).

**Payload:**

```typescript
interface MigrationUploadProgress {
  item_id: number;
  job_id: number;
  file_name: string;
  bytes_uploaded: number;
  total_bytes: number;
  speed_bytes_per_sec: number;
  percentage: number;         // 0-100
}
```

---

## System Events

### `migration-cooldown-update`

Phát ra khi trạng thái cooldown thay đổi (FLOOD_WAIT hoặc Graph rate limit).

**Payload:**

```typescript
interface CooldownUpdate {
  service: 'telegram' | 'graph';
  cooldown_until: string;       // ISO 8601
  safety_level: number;         // 0-3
  remaining_seconds: number;
}
```

---

### `migration-disk-warning`

Phát ra khi dung lượng đĩa thấp.

**Payload:**

```typescript
interface DiskWarning {
  job_id: number;
  free_bytes: number;
  required_bytes: number;
  message: string;
}
```

---

### `migration-auth-required`

Phát ra khi cần người dùng xác thực lại.

**Payload:**

```typescript
interface AuthRequired {
  service: 'microsoft';
  job_id: number;
  message: string;
}
```

---

## Scan Events

### `migration-scan-progress`

Phát ra trong quá trình quét delta.

**Payload:**

```typescript
interface ScanProgress {
  job_id: number;
  phase: 'initial' | 'incremental';
  items_found: number;
  items_added: number;
}
```

---

## Frontend Usage Pattern

```typescript
// app/src/hooks/useMigration.ts

import { listen } from '@tauri-apps/api/event';
import { useEffect } from 'react';

export function useMigrationEvents(jobId: number) {
  useEffect(() => {
    const unlisteners: (() => void)[] = [];

    // Đăng ký tất cả listeners
    const setup = async () => {
      unlisteners.push(
        await listen<MigrationJob>('migration-job-updated', (event) => {
          if (event.payload.id === jobId) {
            // Cập nhật job state
          }
        })
      );

      unlisteners.push(
        await listen<MigrationItem>('migration-item-updated', (event) => {
          if (event.payload.job_id === jobId) {
            // Cập nhật item trong danh sách
          }
        })
      );

      unlisteners.push(
        await listen<MigrationDownloadProgress>('migration-download-progress', (event) => {
          if (event.payload.job_id === jobId) {
            // Cập nhật progress bar
          }
        })
      );

      unlisteners.push(
        await listen<MigrationUploadProgress>('migration-upload-progress', (event) => {
          if (event.payload.job_id === jobId) {
            // Cập nhật progress bar
          }
        })
      );

      unlisteners.push(
        await listen<CooldownUpdate>('migration-cooldown-update', (event) => {
          // Hiển thị countdown cooldown
        })
      );
    };

    setup();

    // Cleanup
    return () => {
      unlisteners.forEach(fn => fn());
    };
  }, [jobId]);
}
```

---

## Tần suất Event

| Event | Tần suất | Ghi chú |
|---|---|---|
| `migration-job-updated` | Khi trạng thái thay đổi | Không spam |
| `migration-item-updated` | Khi trạng thái thay đổi | ~1-3 lần/item |
| `migration-event` | Mỗi sự kiện quan trọng | 5-10 lần/item |
| `migration-download-progress` | Mỗi 250ms | Chỉ trong lúc download |
| `migration-upload-progress` | Mỗi 250ms | Chỉ trong lúc upload |
| `migration-cooldown-update` | Mỗi giây trong cooldown | Đếm ngược |
| `migration-disk-warning` | Khi phát hiện | Hiếm |
| `migration-auth-required` | Khi token hết hạn | Hiếm |
| `migration-scan-progress` | Mỗi page delta | 1-10 lần/lần quét |
