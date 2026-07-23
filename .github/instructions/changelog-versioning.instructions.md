---
description: "Áp dụng khi cập nhật số phiên bản, viết mục changelog, hoặc chuẩn bị phát hành. Bao gồm định dạng phiên bản và cấu trúc changelog."
applyTo: "CHANGELOG.md, app/package.json, app/src-tauri/Cargo.toml"
---

# Quy ước CHANGELOG & Phiên bản

## Định dạng Phiên bản

- Định dạng: `[MAJOR.MINOR.PATCH]` — theo semantic versioning.
- Phiên bản đồng bộ trên ba file:
  - `app/package.json` — `"version": "1.9.9"`
  - `app/src-tauri/Cargo.toml` — `version = "1.9.9"`
  - Git tag — `v1.9.9`

## Cấu trúc CHANGELOG

```markdown
# Changelog

## [1.9.9] - 2026-07-13

### Bug Fixes & UI Enhancements

- **Tiêu đề danh mục**
  - Mô tả sửa lỗi 1.
  - Mô tả sửa lỗi 2.

### Features

- **Tiêu đề tính năng**
  - Mô tả tính năng 1.

### Security

- **Tiêu đề bảo mật**
  - Mô tả sửa lỗi bảo mật.
```

### Các phần (theo thứ tự)
1. `### Features` — Tính năng mới
2. `### Bug Fixes & UI Enhancements` — Sửa lỗi và cải tiến UI
3. `### Security` — Thay đổi liên quan bảo mật
4. `### Performance` — Cải thiện hiệu năng
5. `### Localization & Internationalisation` — Thay đổi i18n

### Nguyên tắc mục

- Mỗi phiên bản có ngày theo định dạng `YYYY-MM-DD`.
- Nhóm thay đổi liên quan dưới tiêu đề danh mục in đậm.
- Dùng thì quá khứ cho mô tả.
- Giữ mô tả ngắn gọn nhưng đầy đủ thông tin.
- Tham chiếu issue/PR khi có thể.

## Quy trình Bump Phiên bản

1. Cập nhật `version` trong `app/package.json`.
2. Cập nhật `version` trong `app/src-tauri/Cargo.toml`.
3. Thêm mục changelog với phiên bản và ngày mới.
4. Commit với message: `chore: bump version to 1.x.x`.
5. Tag: `git tag v1.x.x`.
6. Push: `git push && git push --tags`.

## Lịch sử Phiên bản

- Phiên bản mới nhất luôn ở đầu CHANGELOG.md.
- Các mục cũ được giữ lại để tham khảo lịch sử.
