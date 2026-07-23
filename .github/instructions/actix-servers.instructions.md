---
description: "Áp dụng khi làm việc với server Actix-web (streaming, REST API, share routes). Bao gồm vòng đời server, xác thực, CORS và xử lý range request."
applyTo: "app/src-tauri/src/server.rs, app/src-tauri/src/api_routes.rs, app/src-tauri/src/share_routes.rs"
---

# Quy ước Actix Servers

## Kiến trúc Server

Ba server Actix-web được quản lý bởi Tauri:

| Server | Cổng | Mục đích |
|--------|------|---------|
| Streaming | `14201` (`STREAM_PORT`) | Phát trực tuyến media, banner quảng cáo, tải xuống share |
| REST API | Có thể cấu hình (mặc định `8550`) | Giao diện cloud drive lập trình được |
| Share | Dùng chung cổng streaming | Link tải xuống bảo vệ bằng mật khẩu |

## Vòng đời Server

- Server được khởi tạo trong `lib.rs` bên trong `tauri::Builder::setup()`.
- Vòng đời quản lý qua cờ `ApiServerHandle` / `ApiServerRunning` nguyên tử.
- `restart_api_server()` dừng server cũ và chạy server mới khi cấu hình thay đổi.
- Server chạy trên `127.0.0.1` (localhost).

## Streaming Server

```rust
// Khởi động streaming server
let stream_handle = actix_web::rt::spawn(async move {
    HttpServer::new(move || {
        App::new()
            .app_data(web::Data::new(state.clone()))
            .route("/stream/{folder_id}/{file_name}", web::get().to(stream_file))
            .route("/ad-banner", web::get().to(ad_banner))
            .route("/share/{share_id}", web::get().to(share_download))
    })
    .bind(("127.0.0.1", STREAM_PORT))?
    .run()
    .await
});
```

### Xác thực bằng Token

```rust
#[derive(Deserialize)]
struct StreamQuery {
    token: String,
}

// Xác minh token khớp với phiên hiện tại
if query.token != current_token {
    return HttpResponse::Unauthorized().body("Token không hợp lệ");
}
```

## REST API Server

- Xác thực bằng API key: SHA-256 hash key với `constant_time_eq`.
- CORS cấu hình cho origin `tauri://` và localhost.
- Hỗ trợ: upload (multipart), đổi tên, di chuyển, sao chép, xóa, CRUD thư mục, thống kê, tạo ZIP.

## Share Routes

- Bảo vệ bằng mật khẩu dùng bcrypt (cost 12).
- Xác thực dựa trên session cookie sau khi nhập mật khẩu.
- Tạo token: 16 byte ngẫu nhiên → chuỗi hex.

## Xử lý Range Request

```rust
fn parse_range_header(range: &str, file_size: u64) -> Option<(u64, u64)> {
    // Phân tích định dạng "bytes=start-end"
    // Trả về (start, end) bao gồm cả đầu và cuối
}
```

- Dùng để seek media và tải xuống một phần.
- Trả về `206 Partial Content` với header `Content-Range`.
- Range không hợp lệ trả về `416 Range Not Satisfiable`.
