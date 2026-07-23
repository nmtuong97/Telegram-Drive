---
description: "Áp dụng khi đưa ra quyết định kiến trúc cho toàn bộ cấu trúc dự án. Bao gồm Tauri IPC patterns, quản lý state backend, kiến trúc hướng sự kiện và phân tách mối quan tâm."
---

# Nguyên tắc Kiến trúc

## Cấu trúc Dự án

```
Telegram-Drive/
├── app/                          # Frontend (React/TypeScript + Tauri)
│   ├── src/                      # Mã nguồn React
│   │   ├── components/
│   │   │   ├── desktop/          # Component dành riêng cho desktop
│   │   │   │   └── dashboard/    # Component con của dashboard
│   │   │   ├── mobile/           # Component dành riêng cho mobile
│   │   │   └── shared/           # Component đa nền tảng
│   │   ├── hooks/                # Custom React hooks
│   │   ├── context/              # React Context providers
│   │   ├── i18n/                 # Đa ngôn ngữ
│   │   └── theme/                # Theme engine
│   └── src-tauri/                # Backend (Rust + Tauri)
│       ├── src/
│       │   ├── commands/         # Bộ xử lý Tauri command
│       │   └── ...               # Module service
│       └── capabilities/         # Quyền bảo mật Tauri
└── .github/
    └── instructions/             # Hướng dẫn Copilot
```

## Kiến trúc Tauri IPC

```text
React Component
    │
    │ invoke('cmd_xxx', { ... })
    ▼
Rust Command (#[tauri::command])
    │
    │ Xử lý, truy cập state, tương tác với Telegram
    ▼
Trả về Result<T, String>
    │
    │ Phản hồi qua IPC
    ▼
React Component (try/catch)
```

### Quy tắc chính
- **Frontend không bao giờ truy cập trực tiếp Telegram API** — mọi tương tác Telegram đều qua Rust commands.
- **Rust phát sự kiện tiến trình** qua `app_handle.emit()`, frontend lắng nghe qua `listen()`.
- **Các command là async** (`pub async fn`) trừ khi đồng bộ đơn giản.
- **Kiểu lỗi luôn là `String`** — `Result<T, String>` cho tất cả Tauri commands.

## Quản lý State Backend

```rust
// State dùng chung được quản lý qua hệ thống state của Tauri:
app.manage(Arc::new(Mutex::new(state)));
app.manage(Arc::new(RwLock::new(config)));

// Truy cập trong commands:
async fn cmd_example(state: State<'_, Arc<Mutex<TelegramState>>>) { ... }
```

- Dùng `Arc<Mutex<T>>` cho state có thể thay đổi dùng chung.
- Dùng `Arc<RwLock<T>>` cho state đọc nhiều (cấu hình, cache).
- Dùng `tokio::sync::Mutex` trong ngữ cảnh async, `std::sync::Mutex` cho đồng bộ.

## Tiến trình Hướng sự kiện

Backend phát → Frontend lắng nghe:

```rust
// Rust
app_handle.emit("upload-progress", serde_json::json!({
    "id": upload_id,
    "progress": 45,
    "uploadedBytes": 1024000,
    "totalBytes": 2048000,
    "speedBytesPerSec": 512000
}))?;

// Frontend
useEffect(() => {
    const unlisten = await listen('upload-progress', (event) => {
        // Cập nhật UI
    });
    return () => unlisten();
}, []);
```

## Phát hiện Nền tảng

- Phát hiện runtime qua hook `usePlatform()` (kiểm tra `navigator.userAgent` / Tauri OS plugin).
- Cây component riêng biệt cho desktop và mobile.
- `React.lazy()` + `Suspense` cho code-splitting theo nền tảng.
- Rust: `#[cfg(target_os = "...")]` cho mã backend theo nền tảng.
