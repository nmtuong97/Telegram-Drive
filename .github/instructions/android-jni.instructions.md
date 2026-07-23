---
description: "Áp dụng khi làm việc với mã dành riêng cho Android, tích hợp JNI, hoặc foreground service. Bao gồm pattern JNI, gating nền tảng và xử lý file Android."
applyTo: "app/src-tauri/src/jni_cache.rs, app/src-tauri/src/lib.rs, app/src-tauri/src/upload_service.rs"
---

# Quy ước Android & JNI

## Gating nền tảng

Tất cả mã dành riêng cho Android phải được gating:

```rust
#[cfg(target_os = "android")]
fn function_chi_danh_cho_android() { ... }

#[cfg(not(target_os = "android"))]
fn function_desktop() { ... }
```

Dùng `#[cfg(target_os = "android")]`, `#[cfg(not(target_os = "android"))]`, `#[cfg(target_os = "windows")]`, `#[cfg(target_os = "linux")]` khi thích hợp.

## JNI Cache (`jni_cache.rs`)

- Dùng `OnceLock<GlobalRef>` để khởi tạo lazy tham chiếu class JNI.
- `get_main_activity_jclass()` dùng `transmute_copy` để ép kiểu an toàn — cực kỳ cẩn thận khi thay đổi.
- Tham chiếu được cache:
  - Class loader `GlobalRef`
  - MainActivity `GlobalRef`
- `ndk_context::android_context()` cung cấp môi trường JNI.

## Xử lý file JNI

Trên Android, đường dẫn file có thể là URL với scheme:
- `raw://` — đường dẫn tài nguyên thô
- `content://` — URI content provider

```rust
// Pattern để phân giải đường dẫn file Android
let path = if cfg!(target_os = "android") {
    let cleaned = path.strip_prefix("raw://").unwrap_or(path);
    // Sao chép vào app cache qua JNI
    copy_to_cache_via_jni(cleaned)?
} else {
    path.to_string()
};
```

## Foreground Service

- `UploadForegroundService` là service Kotlin được khởi động/dừng qua JNI.
- Pattern `start_foreground_service()` / `stop_foreground_service()` trong Rust.
- Ngăn Android giết ứng dụng khi upload/download dài.

## Xử lý ngoại lệ JNI

```rust
fn safe_jni_call(env: &mut JNIEnv) -> Result<(), String> {
    let result = env.call_method(...);
    if env.exception_check().map_err(|e| e.to_string())? {
        env.exception_describe().ok();
        env.exception_clear().map_err(|e| e.to_string())?;
        return Err("Đã xảy ra ngoại lệ JNI".to_string());
    }
    result.map_err(|e| e.to_string())?;
    Ok(())
}
```

Luôn kiểm tra → mô tả → xóa khi xử lý ngoại lệ JNI. Không bao giờ để lại ngoại lệ đang chờ trong môi trường JNI.
