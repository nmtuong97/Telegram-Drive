---
description: "Áp dụng khi làm việc với tối ưu VPN, cài đặt proxy, cầu nối SOCKS5, hoặc điều chỉnh mạng. Bao gồm pattern cấu hình proxy và tham số tối ưu VPN."
applyTo: "app/src-tauri/src/vpn_optimizer.rs, app/src-tauri/src/socks5_bridge.rs, app/src-tauri/src/commands/network.rs, app/src-tauri/src/commands/settings.rs"
---

# Quy ước VPN/Proxy & Tối ưu Mạng

## Kiến trúc

- `NetworkConfig` chứa cả `ProxyConfig` và `VpnConfig` dưới `Arc<RwLock<T>>`.
- `NetworkConfigSnapshot` dùng để tuần tự hóa/lưu trữ.
- Cầu nối SOCKS5 chạy như proxy cục bộ cho upstream HTTP/HTTPS.

## Cấu hình Proxy

```rust
struct ProxyConfig {
    enabled: bool,
    proxy_type: ProxyType,       // Socks5 | MtProto
    address: String,
    port: u16,
    username: Option<String>,
    password: Option<String>,
}

enum ProxyType {
    Socks5,
    MtProto,
}
```

## Cầu nối SOCKS5 (`socks5_bridge.rs`)

- Bind vào `127.0.0.1:0` (cổng ngẫu nhiên khả dụng).
- Chuyển tiếp lưu lượng qua proxy đã cấu hình.
- Dùng khi upstream yêu cầu proxy HTTP/HTTPS (cầu nối SOCKS5 chuyển đổi).

## Tham số Tối ưu VPN

```rust
struct VpnConfig {
    timeout_multiplier: f64,       // Tỷ lệ timeout TCP (mặc định: 2.0)
    retry_backoff_base_ms: u64,    // Cơ sở thời gian chờ retry (mặc định: 1000)
    retry_backoff_max_ms: u64,     // Thời gian chờ retry tối đa (mặc định: 30000)
    dc_fallback_enabled: bool,     // Thử DC thay thế khi lỗi
    keep_alive_secs: u64,          // Khoảng thời gian keep-alive
    adaptive_chunk_size: bool,     // Điều chỉnh kích thước chunk động
    bandwidth_throttle_kbps: u64,  // Giới hạn băng thông tối đa
}
```

## Hàm Tiện ích

- `backoff_ms(attempt: u32) -> u64` — tính thời gian chờ retry với exponential backoff.

## Command Trạng thái Proxy

- `cmd_get_proxy_status` — Kiểm tra ping TCP đến proxy server.
- `cmd_test_proxy_traffic` — Gọi API Telegram thực qua proxy để xác minh kết nối.

## Lưu trữ

- `cmd_apply_proxy_settings` lưu cấu hình và áp dụng ngay lập tức.
- `cmd_reset_proxy_settings` khôi phục mặc định (không proxy).
- Thay đổi cấu hình có thể kích hoạt khởi động lại cầu nối SOCKS5.
