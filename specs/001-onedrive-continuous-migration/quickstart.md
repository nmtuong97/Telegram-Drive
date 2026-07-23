# Hướng dẫn Kiểm thử Nhanh — OneDrive Migration

> **Mục đích:** Hướng dẫn xác minh tính năng hoạt động end-to-end  
> **Đối tượng:** Developer kiểm thử tính năng sau khi triển khai

---

## Điều kiện Tiên quyết

1. **Telegram-Drive** đã build thành công từ nhánh `001-onedrive-continuous-migration`
2. Đã đăng nhập Telegram trong app
3. Có tài khoản Microsoft với OneDrive (cá nhân hoặc business)
4. Có ít nhất 3-5 file trong thư mục OneDrive để test
5. Đã đăng ký Azure AD application:
   - Platform: "Mobile and desktop applications"
   - Redirect URI: `http://127.0.0.1:{port}` (hoặc `http://localhost`)

---

## Kịch bản A: Setup, Preflight & Khởi động (US1, FR-001–FR-007)

**Tasks**: T081–T086, T073, T066–T067

### Bước 1: Mở Wizard Migration

1. Trong sidebar desktop, nhấn **OneDrive Migration**
2. Xác nhận wizard thiết lập 5 bước hiển thị

**Xác minh:** Wizard hiển thị bước 1/5 "Kết nối Microsoft" đang active.

### Bước 2: Kết nối Microsoft

1. Nhấn **Kết nối Tài khoản Microsoft**
2. Browser mở ra trang `login.microsoftonline.com`
3. Đăng nhập, chấp nhận quyền `Files.ReadWrite`
4. Browser redirect về app

**Xác minh:** Trạng thái "Đã kết nối" + email tài khoản.

### Bước 3: Chọn Thư mục Nguồn

1. Nhấn **Tiếp** sang bước 2
2. Nhấn **Duyệt OneDrive** → chọn thư mục chứa file test

**Xác minh:** Đường dẫn + số file > 0.

### Bước 4: Chọn Đích Telegram

1. Nhấn **Tiếp** sang bước 3
2. Chọn đích (nên dùng **Saved Messages** để test)

**Xác minh:** Tên đích hiển thị đúng.

### Bước 5: Cấu hình Chính sách

1. Nhấn **Tiếp** sang bước 4
2. Để mặc định; nhập filter patterns nếu cần (glob, case-insensitive)

**Xác minh:** Validation error hiển thị với pattern không hợp lệ.

### Bước 6: Preflight & Khởi động

1. Nhấn **Tiếp** sang bước 5
2. Xem báo cáo preflight:
   - ✓ Microsoft connected
   - ✓ Telegram connected
   - ✓ Source folder exists
   - ✓ Destination valid
   - ✓ Free disk ≥ `required_payload_space`
   - ✓ Free disk after allocation ≥ 2 GiB
   - ⚠ Files exceeding Telegram limit
   - ⚠ Filter validation result
   - ⚠ Single-instance/background readiness

3. Nếu có blocking error → không cho phép start
4. Nhấn **Bắt đầu Continuous Migration**

**Xác minh:** Dashboard hiển thị, job `running`, file đầu tiên bắt đầu xử lý.

---

## Kịch bản B: Continuous Migration (US1, FR-005–FR-020)

**Tasks**: T053–T061

### Flow tự động (quan sát, không thao tác)

1. **File mới xuất hiện** trên OneDrive → Delta API phát hiện
2. **Stability window**: File phải ổn định ≥ N phút (default 10 phút) → `stabilizing` → `queued`
3. **Download**: `queued` → `downloading` → HTTP GET với Range → `.part` → atomic rename `.ready` → `downloaded`
4. **Upload intent**: `downloaded` → `upload_intent` (commit trước upload) → `uploading` → enqueue vào `TelegramMutationScheduler`
5. **Upload**: `uploading` → `uploaded` (lưu `message_id`)
6. **Verify**: `uploaded` → `verifying` → `verified` (metadata-only: peer + filename + size)
7. **Delete source** (nếu bật): `verified` → `delete_pending` → `deleting_source` → `completed`
8. **Tiếp tục tự động**: File tiếp theo trong queue

**Xác minh:**
- File đầu tiên xuất hiện trong Telegram với tên `tdm_{hash}__{original_name}`
- Nội dung file chính xác
- Dashboard hiển thị tiến trình real-time

---

## Kịch bản C: Crash Recovery (US3, FR-023–FR-025)

**Tasks**: T062–T065, T103–T109

### C1: Crash khi download

1. Migration đang download file → kill process (`kill -9 <PID>`)
2. Khởi động lại app
3. Mở dashboard migration

**Xác minh:**
- Reconciler phát hiện item `downloading` + file `.part`
- Resume download từ byte đã tải (Range request)
- Tiếp tục pipeline bình thường

### C2: Crash trong trạng thái upload unknown

1. Migration đang upload → kill process sau `send_message()` trước DB commit
2. Khởi động lại app

**Xác minh:**
- Item chuyển `upload_unknown`
- Reconciler tìm message trong Telegram đích qua `telegram_physical_name`
- 1 message khớp → lưu `message_id`, chuyển `uploaded`, **không upload lại**
- 0 message khớp → retry upload khi pacing/cooldown cho phép

### C3: Recovery time

**Xác minh:** Từ lúc Tauri backend hoàn tất startup → reconciler hoàn tất đối chiếu tất cả non-terminal items ≤ 30 giây (không tính FLOOD_WAIT/Retry-After).

---

## Kịch bản D: Telegram Cooldown (FR-015, D08)

**Tasks**: T047–T049, T108, T128

### Bước 1: Kích hoạt FLOOD_WAIT

1. Upload liên tục không pacing (test mode) → Telegram trả `FLOOD_WAIT_X`
2. Xác nhận UI hiển thị cooldown countdown

**Xác minh:**
- `cooldown_until = now + X + max(60s, X × 10%)` được persist vào `rate_limit_state`
- Job chuyển `telegram_cooldown`
- Mọi mutation bị chặn (thủ công + migration)
- **Không có nút bypass cooldown**

### Bước 2: Restart trong cooldown

1. Kill process trong lúc đang cooldown
2. Khởi động lại app

**Xác minh:**
- Cooldown vẫn active (đọc từ `rate_limit_state`)
- Countdown vẫn đúng
- Không gửi mutation trước hạn
- Tự resume khi cooldown hết hạn, safety level → RESTRICTED

---

## Kịch bản E: Delete Failure & Retry (US5, FR-019–FR-020)

**Tasks**: T118–T122

### Bước 1: Upload và verify thành công

1. File upload + verify thành công (`verified`)

### Bước 2: DELETE lỗi

1. Graph trả về lỗi mạng hoặc 5xx
2. Item → `delete_failed`

**Xác minh:**
- `delete_failed` retry DELETE với backoff (tối đa 3 lần)
- **Không download/upload lại**
- Nếu vẫn thất bại → giữ nguyên `delete_failed`, hiển thị trong UI
- `attempt_count` tăng mỗi lần retry

---

## Kịch bản F: Source Changed (FR-020)

**Tasks**: T119, T149

### Bước 1: eTag thay đổi

1. File nguồn bị sửa đổi sau khi đã upload lên Telegram
2. Graph DELETE trả về HTTP 412 (Precondition Failed)

**Xác minh:**
- Item → `source_changed`
- Source mới **KHÔNG bị xóa**
- Phiên bản đã upload được giữ nguyên trên Telegram
- UI hiển thị cảnh báo để người dùng xem xét

---

## Kịch bản G: Multiple Telegram Matches (US3, D11)

**Tasks**: T104, T150

### Bước 1: Reconciliation tìm ≥2 message

1. File `upload_unknown` → reconciler tìm trong Telegram đích
2. Có 2+ message khớp `telegram_physical_name` + size

**Xác minh:**
- Item → `quarantined` (không `uploaded`, không `upload_unknown`)
- **Không tự chọn message**
- **Không xóa source**
- UI hiển thị conflict với danh sách message khớp
- Người dùng phải giải quyết thủ công

---

## Kịch bản H: Low Disk (FR-011, D06)

**Tasks**: T040, T148

### Bước 1: Dung lượng thấp

1. Mô phỏng `free_space < file_size + max(512 MiB, file_size × 5%)` hoặc `free_space_after_allocation < 2 GiB`

**Xác minh:**
- Job → `low_disk`
- Không bắt đầu file mới
- File đang download được hoàn tất (nếu đã bắt đầu)

### Bước 2: Tự phục hồi

1. Giải phóng dung lượng (hoặc mock `available_space` đủ)
2. Đợi tối đa 5 phút

**Xác minh:**
- Tự kiểm tra lại mỗi 5 phút
- Tự resume khi `free_space ≥ required_payload_space` VÀ `free_space_after_allocation ≥ 2 GiB`
- Không yêu cầu người dùng nhấn Resume

---

## Kịch bản I: Background Lifecycle (US2, FR-021–FR-022, D02)

**Tasks**: T095–T102, T096a, T099, T101

### I1: Close-to-tray

1. Migration đang chạy → đóng cửa sổ (X / Cmd+W)

**Xác minh:**
- Cửa sổ ẩn, app không thoát
- System tray icon xuất hiện
- Migration tiếp tục chạy trong nền

### I2: Mở lại dashboard

1. Nhấp tray icon → **Mở Dashboard**

**Xác minh:** Dashboard hiển thị với trạng thái cập nhật.

### I3: App restart

1. Thoát app hoàn toàn (tray → Thoát)
2. Mở lại app (thủ công hoặc autostart)

**Xác minh:** Migration resume từ SQLite state.

### I4: Autostart

1. Bật autostart trong settings
2. Logout/login OS

**Xác minh:** App tự khởi động, migration resume.

### I5: Single-instance

1. Mở app thứ hai khi app đầu đang chạy migration

**Xác minh:** Bị chặn hoặc focus window hiện có — không có 2 worker.

---

## Kiểm tra Logs

```bash
RUST_LOG=migration=debug npm run tauri dev
```

Các log quan trọng:
- `[migration::scheduler] Starting migration job {id}`
- `[migration::downloader] Downloading {name} ({size} bytes)`
- `[migration::downloader] Resuming from byte {offset}`
- `[migration::flood_guard] FLOOD_WAIT_{secs} — cooldown until {time}`
- `[migration::reconciler] Reconciling {count} non-terminal items`

### Database Inspection

```bash
sqlite3 ~/Library/Application\ Support/com.telegram-drive.app/migration/migration.db

.tables
.schema migration_jobs
.schema migration_items

SELECT id, name, status, created_at FROM migration_jobs;
SELECT id, original_name, status, attempt_count, last_error_class
FROM migration_items WHERE job_id = 1;
SELECT severity, category, event_code, message, created_at
FROM migration_events WHERE job_id = 1 ORDER BY id DESC LIMIT 20;
```

---

## Tiêu chí Thành công

| # | Tiêu chí | Kịch bản | Tasks |
|---|---|---|---|
| SC-001 | Thiết lập migration < 5 phút | A: Setup & Preflight | T132 |
| SC-002 | 95% file thành công lần đầu | B: Continuous Migration | T142 |
| SC-003 | Resume ≤ 30s sau crash | C: Crash Recovery | T131, T143 |
| SC-004 | ≤ 1 FLOOD_WAIT/24h | D: Telegram Cooldown | T128, T142 |
| SC-005 | 80% file trong 1h nền | I: Background Lifecycle | T142 |
| SC-006 | Dashboard < 2s latency | B: Continuous Migration | T144 |

---

## Gỡ lỗi Thường gặp

| Vấn đề | Nguyên nhân có thể | Cách kiểm tra |
|---|---|---|
| Không kết nối được Microsoft | Chưa đăng ký Azure AD app | Kiểm tra CLIENT_ID |
| Loopback redirect không hoạt động | Port bị chiếm hoặc firewall | Device code flow fallback |
| Download treo | `@microsoft.graph.downloadUrl` hết hạn | Lấy URL mới từ Graph |
| Upload thất bại với FLOOD_WAIT | Pacing chưa đủ | Kiểm tra `rate_limit_state` |
| Resume không hoạt động | File `.part` hỏng | Xóa `.part`, bắt đầu lại |
| Token Microsoft hết hạn | Refresh token hết hạn | Xác thực lại qua UI |
| Nhiều message khớp | Conflict recovery | Xem item `quarantined` trong UI |
