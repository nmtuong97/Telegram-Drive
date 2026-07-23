---
description: "Áp dụng khi cấu hình Tauri plugins, quyền, capabilities, hoặc cài đặt bảo mật. Bao gồm CSP, đăng ký plugin, và file capability."
applyTo: "app/src-tauri/capabilities/**, app/src-tauri/tauri.conf.json"
---

# Quy ước Tauri Capabilities & Bảo mật

## File Capability

Hai file capability:

| File | Phạm vi |
|------|---------|
| `capabilities/default.json` | Cửa sổ chính (desktop + quyền mặc định) |
| `capabilities/mobile.json` | Quyền dành riêng cho Android |

Định danh quyền theo pattern: `"<plugin>:<action>"`.

Ví dụ:
```json
{
  "identifier": "default",
  "windows": ["main"],
  "permissions": [
    "core:default",
    "shell:allow-open",
    "dialog:default",
    "store:default"
  ]
}
```

## Thêm Plugin mới

1. Thêm crate vào `Cargo.toml`.
2. Đăng ký trong `lib.rs` builder: `.plugin(tauri_plugin_xxx::init())`.
3. Thêm quyền cần thiết vào `capabilities/default.json`.
4. Nếu plugin có API frontend, import từ `@tauri-apps/plugin-xxx`.

## Cấu hình CSP

CSP trong `tauri.conf.json` kiểm soát origin nào được phép cho các loại tài nguyên:

| Chỉ thị | Origin được phép |
|---------|-----------------|
| `default-src` | `'self'` |
| `connect-src` | `'self' https: http://localhost:* https://asset.localhost` |
| `media-src` | `'self' blob: http://localhost:*` |
| `img-src` | `'self' data: blob: asset: https: https://asset.localhost` |
| `style-src` | `'self' 'unsafe-inline'` |
| `script-src` | `'self' 'unsafe-inline' 'unsafe-eval' blob: https:` |
| `frame-src` | `'self' blob: https: https://www.cameronamer.com http://127.0.0.1:*` |
| `worker-src` | `'self' blob:` |

## macOS Entitlements

Nằm trong `entitlements.plist`. Cần thiết cho:
- Truy cập JNI trên macOS (cầu nối Android emulator).
- Truy cập mạng (ngoại lệ sandbox).

## Cấu hình Bundle

```json
{
  "bundle": {
    "active": true,
    "targets": "all",
    "icon": ["icons/32x32.png", "icons/128x128.png", "icons/icon.icns", "icons/icon.ico"],
    "macOS": { "entitlements": "entitlements.plist" },
    "android": { "minSdkVersion": 24 }
  }
}
```

- Windows: `webview-install-mode = "embeddir"` cho hệ thống không có WebView2.
- Updater: endpoint trỏ đến GitHub releases, pubkey để xác minh chữ ký.
