---
description: "Áp dụng khi viết hoặc chỉnh sửa tài liệu trong dự án. Bao gồm quy tắc ngôn ngữ, phong cách viết và cấu trúc tài liệu."
applyTo: "**/*.md, docs/**"
---

# Quy tắc Viết Tài liệu

## Ngôn ngữ

- **Toàn bộ tài liệu phải được viết bằng Tiếng Việt.**
- Không viết tài liệu bằng tiếng Anh trừ khi có yêu cầu đặc biệt từ người dùng.
- Giữ nguyên tên tiếng Anh của:
  - Tên công nghệ, thư viện, framework (ví dụ: Tauri, React, Rust, TypeScript, Actix-web)
  - Tên biến, hàm, class, module trong mã nguồn
  - Tên API endpoint, tên command, tên file
  - Thuật ngữ kỹ thuật phổ biến không có bản dịch chính xác (ví dụ: streaming, caching, migration, queue)

## Thuật ngữ Kỹ thuật

- Khi xuất hiện lần đầu trong tài liệu, có thể kèm theo thuật ngữ tiếng Anh trong ngoặc đơn, ví dụ:
  - "hàng đợi (queue)"
  - "di chuyển (migration)"
  - "truyền phát (streaming)"
- Ưu tiên dùng từ thuần Việt nếu đã phổ biến trong cộng đồng kỹ thuật Việt Nam.

## Cấu trúc Tài liệu

- Dùng `#`, `##`, `###` cho tiêu đề (Markdown headings).
- Tiêu đề bằng Tiếng Việt, viết hoa chữ cái đầu.
- Dùng `-` cho danh sách không thứ tự.
- Dùng `1.` cho danh sách có thứ tự.
- Code block dùng ```kèm tên ngôn ngữ.
- Bảng biểu dùng cú pháp Markdown chuẩn.
- Sơ đồ dùng Mermaid (```mermaid) nếu cần trực quan hóa luồng xử lý.

## Phong cách Viết

- **Rõ ràng, súc tích**: Tránh dài dòng, đi thẳng vào vấn đề.
- **Chính xác**: Dùng từ ngữ kỹ thuật chính xác, không mơ hồ.
- **Nhất quán**: Dùng cùng một thuật ngữ cho cùng một khái niệm trong toàn bộ tài liệu.
- **Ví dụ**: Kèm ví dụ cụ thể khi mô tả API, cấu hình, hoặc luồng xử lý.
- **Cảnh báo/Ghi chú**: Dùng `> [!NOTE]`, `> [!WARNING]`, `> [!TIP]` cho các lưu ý quan trọng.

## Nội dung Cần Có trong Tài liệu Kỹ thuật

1. **README**: Giới thiệu dự án, tính năng, hướng dẫn cài đặt nhanh, cách sử dụng cơ bản.
2. **Tài liệu API**: Mô tả endpoint, tham số, request/response mẫu, mã lỗi.
3. **Tài liệu Kiến trúc**: Mô tả tổng quan kiến trúc, sơ đồ luồng, quyết định thiết kế.
4. **Tài liệu Hướng dẫn Phát triển**: Cách thiết lập môi trường, chạy project, đóng góp.
5. **CHANGELOG**: Ghi lại thay đổi theo phiên bản (đã có quy tắc riêng trong `changelog-versioning.instructions.md`).

## Ví dụ

### ✅ Đúng (Tiếng Việt)

```markdown
# API Truyền phát

## Giới thiệu

API truyền phát (streaming) cho phép người dùng xem video trực tiếp từ Telegram
mà không cần tải toàn bộ file về thiết bị.

## Endpoint

### `GET /stream/{file_id}`

Trả về luồng dữ liệu video với hỗ trợ range request (phạm vi byte).

**Tham số:**

| Tham số   | Kiểu   | Bắt buộc | Mô tả                     |
|-----------|--------|----------|---------------------------|
| `file_id` | string | Có       | ID của file trên Telegram |
```

### ❌ Sai (Tiếng Anh)

```markdown
# Streaming API

## Introduction

This API allows users to stream video directly from Telegram...
```

## Xử lý Tài liệu Có Sẵn

- Khi sửa đổi tài liệu đã tồn tại bằng tiếng Anh, **phải viết lại nội dung sửa đổi bằng Tiếng Việt**.
- Không có ngoại lệ — mọi tài liệu trong dự án đều phải bằng Tiếng Việt, bất kể mục đích hay đối tượng người đọc.
