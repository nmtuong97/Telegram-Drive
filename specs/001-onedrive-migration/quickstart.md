# Quickstart: OneDrive Migration

**Feature**: 001-onedrive-migration | **Ngày**: 2026-07-23

## Mục đích

Tài liệu này mô tả 7 kịch bản xác minh end-to-end cho feature OneDrive Migration MVP.

## Điều kiện tiên quyết

- Telegram-Drive desktop app đã build và chạy được
- Đã đăng nhập Telegram trong app
- Có tài khoản Microsoft với OneDrive (có thể dùng tài khoản test)
- Có thư mục OneDrive chứa file test
- Working directory có đủ dung lượng cho các test files

---

## A. Connect, Select and Scan

**Setup**: App đang mở, chưa kết nối Microsoft.

| # | Hành động | Kết quả mong đợi |
|---|----------|-----------------|
| 1 | Click "OneDrive Migration" trong sidebar | Hiển thị trang migration |
| 2 | Click **Kết nối Microsoft** | Browser mở, redirect đến Microsoft login |
| 3 | Đăng nhập tài khoản Microsoft test | Browser redirect về loopback callback |
| 4 | Đợi callback hoàn tất | UI hiển thị "Đã kết nối: user@example.com" |
| 5 | Click **Chọn thư mục OneDrive** | Browser/folder picker hiển thị thư mục |
| 6 | Chọn thư mục có ~5 file test | Đường dẫn hiển thị trên UI |
| 7 | Click **Chọn Telegram destination** | Hiển thị picker |
| 8 | Chọn "Saved Messages" | Hiển thị "Saved Messages" |
| 9 | Click **Chọn thư mục local** | Native folder picker mở ra |
| 10 | Chọn thư mục local trống | Đường dẫn hiển thị |
| 11 | Click **Quét** | Loading, sau đó hiển thị danh sách file + thống kê |
| 12 | Xác nhận số file và tổng dung lượng | Khớp với OneDrive |

**Requirement**: FR-001, FR-004–010, US1

---

## B. Migrate Normal Files

**Setup**: Sau scenario A (đã scan, có file pending).

| # | Hành động | Kết quả mong đợi |
|---|----------|-----------------|
| 1 | Click **Bắt đầu** | Job state → `running`, file đầu tiên bắt đầu xử lý |
| 2 | Theo dõi progress | Hiển thị tên file, % download, % upload |
| 3 | Đợi tất cả file hoàn tất | Tất cả → `completed`, job → `completed` |
| 4 | Kiểm tra Telegram Saved Messages | File đã xuất hiện |
| 5 | Kiểm tra thư mục local | Không còn file tạm `.part` |
| 6 | Kiểm tra OneDrive nguồn | File không bị xóa hoặc thay đổi |

**Requirement**: FR-011, FR-016, FR-020–021, US2

---

## C. Duplicate Detected Before Download

**Setup**: File đã được upload thành công từ job trước. Tạo job mới với cùng file.

| # | Hành động | Kết quả mong đợi |
|---|----------|-----------------|
| 1 | Scan thư mục chứa file đã migrate trước đó | File xuất hiện trong danh sách pending |
| 2 | Click **Bắt đầu** | File có OneDrive QuickXorHash trùng → `skipped_duplicate` ngay |
| 3 | Kiểm tra | File KHÔNG được download, không upload lại |

**Requirement**: FR-014–015, US3

---

## D. Duplicate Detected After Download

**Setup**: File không có OneDrive hash nhưng đã từng upload.

| # | Hành động | Kết quả mong đợi |
|---|----------|-----------------|
| 1 | Scan thư mục chứa file không có provider hash | File pending |
| 2 | Click **Bắt đầu** | File được download, SHA-256 tính toán |
| 3 | Kiểm tra | SHA-256 trùng → `skipped_duplicate`, file tạm bị xóa |
| 4 | Kiểm tra Telegram | Không upload lại |

**Requirement**: FR-014–015, FR-020, US3

---

## E. Pause and Resume

**Setup**: Migration đang chạy với nhiều file.

| # | Hành động | Kết quả mong đợi |
|---|----------|-----------------|
| 1 | Đợi file hiện tại đang xử lý | Progress hiển thị downloading/uploading |
| 2 | Click **Tạm dừng** | File hiện tại hoàn tất, sau đó job → `paused` |
| 3 | Kiểm tra file đã completed | Giữ nguyên trạng thái |
| 4 | Click **Tiếp tục** | Job → `running`, tiếp tục file pending tiếp theo |

**Requirement**: FR-018, US2

---

## F. Restart, Reconnect and Manual Resume

**Setup**: Migration đang chạy với file đã completed + pending.

| # | Hành động | Kết quả mong đợi |
|---|----------|-----------------|
| 1 | Đóng ứng dụng hoàn toàn | App terminated |
| 2 | Mở lại ứng dụng | Job vẫn hiển thị, file completed giữ nguyên |
| 3 | Kiểm tra trạng thái Microsoft | "Chưa kết nối" (token in-memory đã mất) |
| 4 | Click **Kết nối Microsoft** và đăng nhập lại | "Đã kết nối" |
| 5 | Click **Tiếp tục** | Job → `running`, xử lý file pending tiếp theo |
| 6 | Kiểm tra file completed cũ | KHÔNG bị upload lại |

**Requirement**: FR-012–013, US3

**Known limitation**: File đang upload dở lúc đóng app → `pending + recovery_interrupted` → sẽ được upload lại khi Resume (at-least-once).

---

## G. Retry, Cooldown and Common Errors

**Setup**: Có file failed trong job (lỗi mạng, file quá lớn, hoặc cooldown).

| # | Hành động | Kết quả mong đợi |
|---|----------|-----------------|
| 1 | Kiểm tra file failed | Hiển thị `last_error_code` và `last_error_message` |
| 2 | Click **Retry** trên một file | File → `pending`, attempt_count reset |
| 3 | Click **Retry tất cả file lỗi** | Tất cả failed → `pending` |
| 4 | Nếu gặp Telegram cooldown | Hiển thị "Đang chờ Telegram: X phút Y giây", không upload mới |
| 5 | Đợi hết cooldown | Tự động tiếp tục upload |
| 6 | File vượt giới hạn Telegram | `failed` với `telegram_file_too_large`, không auto-retry |
| 7 | File thay đổi sau scan | `failed` với `source_changed` |
| 8 | Thư mục local không writable | Job dừng, hiển thị lỗi, cho phép chọn lại |

**Requirement**: FR-017, FR-019, FR-023–025, US3
