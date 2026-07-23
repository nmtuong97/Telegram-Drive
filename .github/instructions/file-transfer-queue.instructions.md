---
description: "Áp dụng khi làm việc với hook upload/download queue, sự kiện tiến trình, hủy transfer, hoặc lưu trữ queue. Bao gồm pattern lắng nghe sự kiện Tauri và hủy hợp tác."
applyTo: "app/src/hooks/useFileUpload.ts, app/src/hooks/useFileDownload.ts, app/src/hooks/useFileOperations.ts"
---

# Quy ước File Transfer Queue

## Kiến trúc Queue

- Queue upload và download được quản lý trong React hook, gọi backend Rust qua `invoke()`.
- Trạng thái queue được lưu vào Tauri Store (key `uploadQueue`, `downloadQueue`).
- Sự kiện tiến trình được phát từ backend Rust, frontend hook tiêu thụ.

## Lắng nghe sự kiện Tauri

```tsx
import { listen } from '@tauri-apps/api/event';

useEffect(() => {
  const unlisten = await listen<ProgressPayload>('upload-progress', (event) => {
    // Cập nhật tiến trình item trong queue
  });
  return () => { unlisten(); };
}, []);
```

### Payload sự kiện

- `'upload-progress'` — `ProgressPayload { id, progress, uploadedBytes, totalBytes, speedBytesPerSec }`
- `'download-progress'` — `ProgressPayload { id, progress, downloadedBytes, totalBytes, speedBytesPerSec }`
- `'remote-upload-progress'` — `RemoteProgressPayload { id, progress, status, error? }`

## Hủy hợp tác (Cooperative Cancellation)

```tsx
const cancelledRef = useRef<Set<string>>(new Set());

const cancelItem = (id: string) => {
  cancelledRef.current.add(id);
  invoke('cmd_cancel_upload', { uploadId: id });
};

// Kiểm tra trong callback tiến trình:
if (cancelledRef.current.has(item.id)) return;
```

## Kiểm soát đồng thời

- Pattern `activeCountRef` để theo dõi transfer đang chạy.
- Tuân thủ cài đặt `maxConcurrentUploads` / `maxConcurrentDownloads`.
- Pattern xử lý khi có slot trống trong `useEffect` theo dõi queue + maxConcurrent.

## useFileOperations — Pattern Callback ổn định

Dùng ref cho tham chiếu hàm ổn định:

```tsx
const selectedIdsRef = useRef(selectedIds);
selectedIdsRef.current = selectedIds;

const displayedFilesRef = useRef(displayedFiles);
displayedFilesRef.current = displayedFiles;
```

## Thao tác hàng loạt

- Duyệt tuần tự với bộ đếm thành công/thất bại.
- Cleanup best-effort: `invoke('cmd_cleanup').catch(() => {})`.
- Toast thông báo cho mỗi thao tác hoàn thành/bị hủy.
