---
description: "Áp dụng khi làm việc với mã backend Rust trong app/src-tauri/. Bao gồm quy ước Tauri commands, xử lý lỗi, quản lý state, cấu trúc module và Telegram MTProto."
applyTo: "app/src-tauri/**"
---

# Quy ước Backend Rust

## Cấu trúc Module

```
src-tauri/src/
├── main.rs              # Entry point, khởi tạo theo nền tảng
├── lib.rs               # Tauri builder, khai báo module, state dùng chung, vòng đời server
├── models.rs            # Kiểu dữ liệu dùng chung (Serialize/Deserialize)
├── db.rs                # Pool kết nối SQLite
├── commands/
│   ├── mod.rs           # TelegramState struct, re-export các submodule
│   ├── auth.rs          # Xác thực Telegram
│   ├── fs.rs            # Thao tác file
│   ├── utils.rs         # Tiện ích dùng chung (resolve_peer, map_error)
│   └── ...
└── server.rs, api_routes.rs, ...  # Các module service độc lập
```

- Logic nghiệp vụ được tổ chức vào các file riêng (ví dụ: `upload_service.rs`, `transcode.rs`).
- Tauri commands nằm trong thư mục `commands/`, phân chia theo domain.

## Quy tắc Tauri Command

### Chữ ký
- Mỗi command được đánh dấu `#[tauri::command]`.
- Ưu tiên `pub async fn` (chỉ dùng sync cho thao tác đơn giản).
- Trả về `Result<T, String>` (Tauri yêu cầu `String` làm kiểu lỗi).
- Truy cập state dùng chung qua tham số `State<'_, T>`.

### Đặt tên
- Tất cả command có tiền tố `cmd_`: `cmd_connect`, `cmd_get_files`, `cmd_delete_file`.
- Dùng `snake_case` cho tên hàm và biến.

### Mẫu template
```rust
#[tauri::command]
pub async fn cmd_get_files(
    state: State<'_, TelegramState>,
    folder_id: Option<i64>,
) -> Result<Vec<FileMetadata>, String> {
    let client = state.client.lock().await;
    let client = client.as_ref().ok_or("Chưa kết nối")?;
    // ... triển khai
    Ok(files)
}
```

## Xử lý lỗi

- Tất cả command trả về `Result<T, String>` — không dùng crate `thiserror` hay `anyhow`.
- Dùng tiện ích `map_error()` từ `commands/utils.rs` để chuyển đổi lỗi `Display`:
  ```rust
  .map_err(|e| map_error(e))
  ```
- Xử lý đặc biệt cho lỗi `FLOOD_WAIT` từ Telegram API.
- Dùng `.map_err(|e| e.to_string())` cho chuyển đổi đơn giản.
- Dùng `.ok()` hoặc `let _ = ...` cho thao tác best-effort.
- Dùng macro `log::info!`, `log::warn!`, `log::error!` cho logging.

## Kiến trúc State dùng chung

Tất cả state được quản lý qua hệ thống state của Tauri (`app.manage()`):

```rust
Arc<Mutex<Option<Client>>>         // Telegram client
Arc<NetworkConfig>                  // Cấu hình VPN/proxy
Arc<BandwidthManager>               // Theo dõi băng thông
DbConnection                        // SQLite (tokio::sync::Mutex<sqlite::Connection>)
Arc<RwLock<HashMap<i64, Peer>>>     // Peer cache
Arc<RwLock<HashSet<String>>>        // Bộ hủy transfer
```

- Struct **`TelegramState`** trong `commands/mod.rs` gộp client, tokens, peer cache và transfer state.
- Dùng `Arc<Mutex<T>>` cho state có thể thay đổi dùng chung, `Arc<RwLock<T>>` cho state đọc nhiều.
- Dùng `tokio::sync::Mutex` trong ngữ cảnh async, `std::sync::Mutex` cho ngữ cảnh đồng bộ.

## Cơ sở dữ liệu (SQLite)

- Raw SQL qua crate `sqlite` — không dùng ORM (không Diesel/Diesel).
- Dùng pattern `prepare`/`bind`/`next`:
  ```rust
  let mut stmt = conn.prepare("SELECT id, name FROM folders WHERE parent_id = ?")?;
  stmt.bind(1, parent_id)?;
  while let Ok(true) = stmt.next() {
      let id: i64 = stmt.read::<i64>(0)?;
  }
  ```
- Kết nối được bọc trong `tokio::sync::Mutex<sqlite::Connection>` để truy cập async.

## Giao tiếp qua sự kiện

- Dùng `app_handle.emit("event_name", payload)` để cập nhật realtime đến frontend.
- Các sự kiện thường gặp: cập nhật tiến trình, chunk tải thư mục, thay đổi trạng thái transfer.
- Frontend lắng nghe qua `listen()` từ `@tauri-apps/api/event`.

## Quy tắc đặt tên

| Phần tử | Quy ước | Ví dụ |
|---------|---------|-------|
| Hàm/biến | `snake_case` | `resolve_peer`, `clear_peer_cache` |
| Struct/kiểu | `PascalCase` | `TelegramState`, `NetworkConfig`, `FileMetadata` |
| Tauri command | Tiền tố `cmd_` + snake_case | `cmd_get_files`, `cmd_apply_proxy_settings` |
| Tên file | `snake_case` | `vpn_optimizer.rs`, `fmp4_remux.rs` |
| Hằng số | `SCREAMING_CASE` | `STREAM_PORT`, `BUNDLE_ID` |
| Module | `snake_case` | `commands/`, `upload_service` |

## Crate & thư viện chính

| Crate | Mục đích |
|-------|---------|
| `grammers-client` | Telegram MTProto API client |
| `actix-web` | Streaming server + REST API |
| `tauri` v2 | Desktop framework, IPC, state |
| `sqlite` | Cơ sở dữ liệu cục bộ (raw SQL) |
| `serde` / `serde_json` | Tuần tự hóa |
| `tokio` | Async runtime |
| `reqwest` | HTTP client (hỗ trợ SOCKS5) |
| `zip` / `unrar` / `sevenz-rust2` | Giải nén file |
| `bcrypt` / `sha2` | Mã hóa mật khẩu link chia sẻ |

## Mã dành riêng cho nền tảng

- Dùng `#[cfg(target_os = "android")]`, `#[cfg(not(target_os = "android"))]`, v.v. để gating theo nền tảng.
- Tích hợp JNI qua crate `jni` + `ndk_context` — tham chiếu class được cache trong `jni_cache.rs`.
- Nhúng WebView2 trên Windows qua `webview-install-mode = "embeddir"` trong `Cargo.toml`.

## Serde Derives

- Tất cả kiểu dữ liệu chia sẻ với frontend phải derive `Serialize`/`Deserialize`.
- Dùng `#[serde(rename_all = "camelCase")]` cho struct mà frontend TypeScript sẽ dùng.
- Ghi chú trường bằng comment để rõ ràng (chúng sẽ trở thành kiểu TypeScript).

## Trách nhiệm của `lib.rs`

- Khai báo tất cả module với `mod`.
- Build và trả về Tauri app qua `tauri::Builder::default()`.
- Đăng ký tất cả command với `.invoke_handler(tauri::generate_handler![...])`.
- Quản lý state dùng chung `.manage(...)`.
- Khởi tạo vòng đời server (Actix streaming server, REST API).
- Đăng ký Tauri plugins (store, updater, shell, dialog, v.v.).
