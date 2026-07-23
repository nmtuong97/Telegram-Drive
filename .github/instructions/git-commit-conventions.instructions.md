---
description: "Áp dụng khi viết commit message, tạo nhánh, hoặc quản lý Git workflow. Bao gồm định dạng commit, chiến lược branching và quy ước PR."
---

# Quy ước Git & Commit

## Định dạng Commit Message

Tuân thủ conventional commits với scope:

```
<type>(<scope>): <mô tả ngắn>

<optional body chi tiết>
```

### Types
| Type | Mục đích |
|------|---------|
| `feat` | Tính năng mới |
| `fix` | Sửa lỗi |
| `refactor` | Thay đổi mã không phải feature/fix |
| `perf` | Cải thiện hiệu năng |
| `i18n` | Thay đổi bản địa hóa |
| `ui` | Thay đổi UI/UX (không phải feature) |
| `docs` | Tài liệu |
| `chore` | Build/tooling/phụ thuộc |
| `security` | Sửa lỗi bảo mật |

### Scopes
| Scope | Khu vực |
|-------|---------|
| `frontend` | Mã React/TypeScript trong `app/src/` |
| `backend` | Mã Rust trong `app/src-tauri/` |
| `streaming` | Media streaming server |
| `api` | REST API |
| `proxy` | VPN/proxy/SOCKS5 |
| `android` | Mã dành riêng cho Android |
| `ci` | CI/CD workflows |
| `i18n` | Bản dịch |
| `theme` | Theme engine |
| `migration` | Tính năng OneDrive migration |

### Ví dụ
```
feat(frontend): thêm nút chuyển đổi chế độ xem grid

fix(backend): xử lý FLOOD_WAIT trong retry upload

i18n(frontend): thêm hỗ trợ tiếng Thái

refactor(streaming): tách range parser thành module chung

chore(ci): cập nhật tauri-action lên v0
```

## Đặt tên nhánh

```
<type>/<mô-tả-ngắn>
```

Ví dụ:
- `feat/grid-view-toggle`
- `fix/flood-wait-handling`
- `i18n/thai-language`
- `refactor/streaming-range-parser`

## Tích hợp CHANGELOG

- Sau khi merge feature/fix, thêm mục vào `CHANGELOG.md` dưới phần thích hợp.
- Nhóm thay đổi theo loại: `Features`, `Bug Fixes & UI Enhancements`, `Security`, v.v.
- Bump version theo semver: `[MAJOR.MINOR.PATCH]`.
