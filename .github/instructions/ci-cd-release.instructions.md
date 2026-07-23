---
description: "Áp dụng khi sửa đổi CI/CD workflows hoặc phát hành phiên bản mới. Bao gồm GitHub Actions workflow, build matrix, ký số và quy trình release."
applyTo: ".github/workflows/release.yml"
---

# Quy ước CI/CD & Release

## Release Workflow (`.github/workflows/release.yml`)

### Kích hoạt
- Push tag khớp `v*` (ví dụ: `v1.9.9`).

### Jobs

**1. `create-release`**
- Chạy trên `ubuntu-latest`.
- Trích xuất nội dung changelog cho phiên bản (giữa heading `##` đầu tiên và thứ hai).
- Tạo hoặc cập nhật GitHub Release (draft).
- Xuất `release_id` cho job build.

**2. `build-tauri`**
- Matrix build trên 4 nền tảng:

| Nền tảng | Runner | Target |
|----------|--------|--------|
| Windows | `windows-latest` | Mặc định |
| Linux | `ubuntu-22.04` | Mặc định |
| macOS Intel | `macos-latest` | `x86_64-apple-darwin` |
| macOS ARM | `macos-latest` | `aarch64-apple-darwin` |

### Khóa ký Tauri

```javascript
// Chuẩn bị khóa ký Tauri trong bước script node
const fs = require('fs');
const key = process.env.KEY.trim();
const b64 = key.startsWith('untrusted comment:')
  ? Buffer.from(key).toString('base64')
  : key;
fs.appendFileSync(process.env.GITHUB_ENV, `TAURI_SIGNING_PRIVATE_KEY=${b64}\n`);
```

- Secret được lưu là `TAURI_SIGNING_PRIVATE_KEY` trong GitHub Secrets.
- Văn bản khóa được mã hóa base64 trước khi truyền cho Tauri.

### Lưu ý Build Linux
- Gói cần thiết: `libwebkit2gtk-4.1-dev`, `build-essential`, `libssl-dev`, `libgtk-3-dev`, `libayatana-appindicator3-dev`, `librsvg2-dev`, `libfuse2`.
- Build AppImage loại bỏ thư viện EGL/GL để tương thích đa distro.

### Nhất quán phiên bản
- `app/package.json` phải khớp với `Cargo.toml`.
- Git tag format: `v<version>` (ví dụ: `v1.9.9`).
- CHANGELOG phải có mục cho phiên bản phát hành.
