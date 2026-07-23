---
description: "Áp dụng khi triển khai hoặc sửa đổi tính năng OneDrive Continuous Migration. Bao gồm kiến trúc migration, state machine, chống flood Telegram, tích hợp Microsoft Graph và pattern phục hồi sau crash."
applyTo: "app/src-tauri/src/migration/**, app/src/hooks/useMigration/**"
---

# Quy ước OneDrive Migration

> Tài liệu tham khảo: Xem `dac-ta/base.md` cho đặc tả đầy đủ.

## Kiến trúc

### Tách Worker
- Migration chạy trong **Rust worker nền** (`telegram-drive-worker`), không phải trong React hooks.
- Worker tiếp tục chạy khi UI bị đóng, reload, hoặc crash.
- Worker là **chủ sở hữu duy nhất** của Telegram client cho thao tác migration.

### Luồng Dữ liệu
```text
OneDrive → Delta scan → Queue → Download → Telegram upload → Verify → Xóa nguồn → Tiếp theo
```

### Quyết định Kiến trúc Chính
1. **Không dùng REST API cho migration nội bộ** — worker gọi trực tiếp Rust upload service.
2. **Backend là nguồn sự thật duy nhất** — `migration.sqlite` lưu toàn bộ state, React chỉ render.
3. **Shared mutation scheduler** — mọi mutation Telegram đi qua `GlobalTelegramMutationScheduler`.

## State Machine

### Trạng thái Job
```text
created → preflight → running → completed | cancelled | fatal
Running có thể chuyển sang: waiting, graph_cooldown, telegram_cooldown, low_disk, auth_required, paused, stopping
```

### Trạng thái Item
```text
discovered → stabilizing → queued → leased → downloading → downloaded → upload_intent → uploading → uploaded → verifying → verified → delete_pending → deleting_source → completed
```

### Checkpoint Quan trọng (phải commit vào DB)
1. Item được claim bằng lease
2. Download hoàn thành
3. Upload intent (trước khi gọi Telegram)
4. Nhận được Telegram message ID
5. Xác minh thành công
6. Delete intent (trước khi Graph DELETE)

## Phục hồi sau Crash

### Tự động khởi động
- Windows: Per-user Scheduled Task, restart on failure.
- macOS: LaunchAgent với `RunAtLoad=true`, `KeepAlive=true`.
- Linux: `systemd --user` service với `Restart=always`.

### Phục hồi khi Khởi động lại
```text
1. Mở database
2. Chạy schema migrations
3. Kiểm tra tính toàn vẹn SQLite
4. Lấy khóa singleton
5. Đọc cooldown Telegram
6. Khôi phục Microsoft token
7. Kết nối Telegram
8. Reconcile các item đang dang dở
9. Dọn file cache mồ côi
10. Khởi động scheduler
```

### Heartbeat
- Worker cập nhật heartbeat mỗi 5 giây trong DB.
- UI coi worker không khỏe nếu `now - heartbeat > 20s`.
- UI kiểm tra process, yêu cầu supervisor restart, hiển thị "Worker restarting".

## Chống Flood Telegram

### Pacing Mặc định
| Kích thước file | Độ trễ sau Upload |
|----------------|-------------------|
| ≤ 1 MiB | 45–90s |
| 1–10 MiB | 30–60s |
| > 10 MiB | 15–30s |

### Cấp độ An toàn
```text
0 NORMAL → 1 CONSERVATIVE → 2 RESTRICTED → 3 COOLDOWN
```

- `FLOOD_WAIT` → ngay lập tức chuyển sang COOLDOWN.
- COOLDOWN hết hạn → RESTRICTED.
- 100 lần thành công không flood → CONSERVATIVE.
- 500 lần thành công hoặc 24h không flood → NORMAL.
- Khoảng cách tối thiểu: 15 giây.

### Xử lý FLOOD_WAIT
1. Dừng tất cả mutation Telegram.
2. Lưu `cooldown_until = now + X + safety_buffer`.
3. Safety buffer: `max(60s, X × 10%)`.
4. Tự động resume khi cooldown hết — không cần can thiệp người dùng.

## Tích hợp Microsoft Graph

### Luồng Xác thực
- OAuth Authorization Code Flow với PKCE.
- Fallback: Device Code Flow.
- Scopes: `openid`, `profile`, `offline_access`, `Files.ReadWrite`.
- Token lưu trong Tauri Stronghold (không phải SQLite).

### Delta API
- Lần đầu: crawl đầy đủ qua `GET /me/drive/root/delta`.
- Lần sau: dùng `@odata.deltaLink` đã lưu.
- Khoảng quét: 5–10 phút với jitter.
- Delta token không hợp lệ → re-crawl đầy đủ (giữ queue hiện tại).

### Stability Window
- File chỉ đủ điều kiện sau `stability_window` (mặc định 10 phút) với `required_stable_observations` (mặc định 2).
- Ngăn migrate file đang được ghi/upload.

### An toàn Xóa Nguồn
- Chỉ xóa sau trạng thái `verified`.
- Dùng header `If-Match: <etag>` — `412 PreconditionFailed` nghĩa là nguồn đã thay đổi.
- Item `source_changed` giữ phiên bản đã upload, phiên bản OneDrive mới được giữ lại.

## Download Engine

- Đường dẫn cache: `<AppData>/migration/cache/<job-id>/<item-id>.part`
- Resume download qua header `Range`.
- Đổi tên nguyên tử `.part` → `.ready` khi hoàn thành.
- Disk guard: yêu cầu `file_size + 512 MiB` dung lượng trống, tối thiểu 2 GiB.
- Download đồng thời: 1 (tuần tự).

## Tính Idempotent

- Migration key: `SHA-256(account_id + drive_id + item_id + source_etag)`.
- Tiền tố tên file Telegram: `tdm_<first-16-base32>__<tên-gốc>`.
- UI loại bỏ tiền tố khi hiển thị tên file.
- Effectively-once upload, at-least-once reconciliation.
- Chỉ xóa nguồn sau khi upload đã được xác minh.
