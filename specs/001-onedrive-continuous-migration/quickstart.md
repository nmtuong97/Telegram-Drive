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

## Kịch bản 1: Thiết lập và Chạy Migration Cơ bản

### Bước 1: Mở Wizard Migration

```bash
# Build và chạy app
cd app
npm run tauri dev
```

1. Trong sidebar desktop, nhấn **OneDrive Migration**
2. Xác nhận wizard thiết lập 5 bước hiển thị

**Xác minh:** Wizard hiển thị bước 1/5 "Kết nối Microsoft" đang active.

### Bước 2: Kết nối Microsoft

1. Nhấn **Kết nối Tài khoản Microsoft**
2. Browser mở ra trang `login.microsoftonline.com`
3. Đăng nhập với tài khoản Microsoft
4. Chấp nhận quyền `Files.ReadWrite`
5. Browser redirect về app

**Xác minh:** Trạng thái hiển thị "Đã kết nối" với email tài khoản.

### Bước 3: Chọn Thư mục Nguồn

1. Nhấn **Tiếp** sang bước 2
2. Nhấn **Duyệt OneDrive**
3. Chọn thư mục chứa file test

**Xác minh:** 
- Đường dẫn thư mục hiển thị đúng
- Số lượng file phát hiện được > 0
- Chỉ file (không folder) được đếm

### Bước 4: Chọn Đích Telegram

1. Nhấn **Tiếp** sang bước 3
2. Chọn đích đến (nên chọn **Saved Messages** để test)

**Xác minh:** Tên đích hiển thị đúng.

### Bước 5: Cấu hình Chính sách

1. Nhấn **Tiếp** sang bước 4
2. Để mặc định: KHÔNG xóa nguồn, stability window 10 phút, scan interval 10 phút

**Xác minh:** Các giá trị mặc định hiển thị đúng.

### Bước 6: Preflight & Khởi động

1. Nhấn **Tiếp** sang bước 5
2. Xem báo cáo preflight

**Xác minh:**
- Tất cả mục ✓ (Microsoft, Telegram, Nguồn, Đích)
- Số file hiển thị khớp với thực tế

3. Nhấn **Bắt đầu Continuous Migration**

**Xác minh:**
- Dashboard migration hiển thị
- Trạng thái job chuyển sang `running`
- File đầu tiên bắt đầu được xử lý (download → upload)
- Progress bar hiển thị

### Bước 7: Xác minh File đã Upload

1. Đợi migration hoàn tất ít nhất 1 file
2. Mở Telegram, kiểm tra đích đến (Saved Messages)
3. Tìm file với tên `tdm_{hash}__{original_name}`

**Xác minh:**
- File xuất hiện trong Telegram
- Tên file chứa `tdm_` prefix và tên gốc
- Nội dung file chính xác (so sánh checksum nếu cần)

---

## Kịch bản 2: Close-to-Tray và Resume

### Bước 1: Khởi động Migration

Làm theo Kịch bản 1 để có migration đang chạy.

### Bước 2: Đóng Cửa sổ

1. Nhấn nút đóng (X) trên cửa sổ app
2. **Hoặc** nhấn Cmd+W / Ctrl+W

**Xác minh:**
- Cửa sổ ẩn đi (không thoát app)
- System tray icon xuất hiện (macOS: menu bar, Windows: system tray, Linux: indicator)
- Migration vẫn đang chạy (kiểm tra logs nếu cần)

### Bước 3: Mở lại Dashboard

1. Nhấp vào system tray icon
2. Chọn **Mở Dashboard**

**Xác minh:**
- Cửa sổ chính hiển thị lại
- Dashboard migration vẫn active
- Trạng thái cập nhật (có thêm file đã xử lý)

### Bước 4: Kiểm tra Menu Tray

1. Nhấp phải vào tray icon
2. Xem menu

**Xác minh:**
- Hiển thị trạng thái migration (VD: "Đang xử lý: 3/10 files")
- Có mục "Mở Dashboard"
- Có mục "Tạm dừng sau file hiện tại"
- Có mục "Thoát" (thoát thật sự)

---

## Kịch bản 3: Phục hồi Sau Crash

### Bước 1: Đang Migration Thì Crash

1. Khởi động migration với ít nhất 3 file
2. Đợi 1 file đang ở trạng thái `downloading` hoặc `uploading`
3. Kill process:
   ```bash
   # Tìm PID
   ps aux | grep telegram-drive
   kill -9 <PID>
   ```

### Bước 2: Khởi động lại

1. Mở lại app (từ terminal hoặc click icon)
2. Đăng nhập Telegram nếu cần
3. Mở dashboard migration

**Xác minh:**
- Dashboard hiển thị trạng thái trước crash
- File đang download → resume từ `.part` (Range request)
- File đang upload → tìm message trong Telegram hoặc upload lại
- Không có file nào bị upload trùng

---

## Kịch bản 4: Quản lý Migration

### Bước 1: Tạm dừng & Tiếp tục

1. Trong dashboard, nhấn **Tạm dừng**

**Xác minh:**
- Job chuyển sang `paused`
- Item đang xử lý hoàn tất rồi mới dừng hẳn

2. Nhấn **Tiếp tục**

**Xác minh:**
- Job chuyển sang `running`
- Item tiếp theo trong queue được xử lý

### Bước 2: Bỏ qua File Lỗi

1. Tạo tình huống lỗi: đặt file > 2GB (vượt Telegram limit) vào thư mục nguồn
2. Đợi file đó bị lỗi 3 lần

**Xác minh:**
- File hiển thị trong danh sách lỗi
- `attempt_count = 3`, `last_error_class = 'permanent'`

3. Nhấn **Bỏ qua** trên file lỗi

**Xác minh:**
- File chuyển sang `skipped`
- Migration tiếp tục với file tiếp theo

### Bước 3: Hủy Migration

1. Nhấn **Hủy Migration**
2. Xác nhận trong dialog

**Xác minh:**
- Job chuyển sang `cancelled`
- Item đang xử lý dừng lại
- Không còn hoạt động migration nào

---

## Kịch bản 5: Xóa Nguồn (Tùy chọn)

### Bước 1: Thiết lập với Xóa Nguồn

1. Tạo job mới với tùy chọn **Xóa khỏi OneDrive sau khi upload** = BẬT
2. Chọn thư mục có 2-3 file nhỏ

### Bước 2: Xác minh Xóa

1. Khởi động migration
2. Đợi tất cả file hoàn thành

**Xác minh:**
- Tất cả file xuất hiện trong Telegram
- Kiểm tra thư mục OneDrive: các file đã bị xóa
- Trạng thái item = `completed` với `source_deleted_at` có giá trị

---

## Kiểm tra Logs

### Backend Logs

```bash
# Xem logs migration trong console
# Filter: RUST_LOG=migration=debug
cd app
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
# Mở migration.db với sqlite3 CLI
sqlite3 ~/Library/Application\ Support/com.telegram-drive.app/migration/migration.db

# Kiểm tra schema
.tables
.schema migration_jobs
.schema migration_items

# Xem trạng thái job
SELECT id, name, status, created_at FROM migration_jobs;

# Xem items
SELECT id, original_name, status, attempt_count, last_error_class 
FROM migration_items WHERE job_id = 1;

# Xem events
SELECT severity, category, event_code, message, created_at 
FROM migration_events WHERE job_id = 1 ORDER BY id DESC LIMIT 20;
```

---

## Tiêu chí Thành công

| # | Tiêu chí | Kịch bản |
|---|---|---|
| SC-001 | Thiết lập migration < 5 phút | Kịch bản 1 |
| SC-002 | 95% file thành công lần đầu | Kịch bản 1 (3-5 file nhỏ) |
| SC-003 | Resume đúng sau crash < 30s | Kịch bản 3 |
| SC-004 | ≥ 10 file nhỏ/giờ, không FLOOD_WAIT | Chạy dài với 20+ file |

---

## Gỡ lỗi Thường gặp

| Vấn đề | Nguyên nhân có thể | Cách kiểm tra |
|---|---|---|
| Không kết nối được Microsoft | Chưa đăng ký Azure AD app | Kiểm tra CLIENT_ID trong code |
| Loopback redirect không hoạt động | Port bị chiếm hoặc firewall | Thử device code flow fallback |
| Download treo | `@microsoft.graph.downloadUrl` hết hạn | Lấy URL mới từ Graph |
| Upload thất bại với FLOOD_WAIT | Pacing chưa đủ | Kiểm tra `rate_limit_state` table |
| Resume không hoạt động | File `.part` hỏng | Xóa `.part`, bắt đầu lại |
| Token Microsoft hết hạn | Refresh token hết hạn | Xác thực lại qua UI |
