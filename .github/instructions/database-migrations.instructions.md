---
description: "Áp dụng khi làm việc với cơ sở dữ liệu SQLite, thay đổi schema, hoặc di chuyển dữ liệu. Bao gồm pattern raw SQL, quản lý kết nối và chiến lược migration."
applyTo: "app/src-tauri/src/db.rs"
---

# Quy ước Database & Migration

## Kiến trúc kết nối

- **SQLite** qua crate `sqlite` — không dùng ORM (không Diesel/Diesel).
- Kết nối bọc trong `DbConnection = tokio::sync::Mutex<sqlite::Connection>`.
- Thử lại kết nối với exponential backoff: `100ms * 2^attempt`, tối đa 5 lần thử lại.

## Pattern Raw SQL

```rust
use sqlite::Connection;

async fn query_folders(db: &DbConnection) -> Result<Vec<Folder>, String> {
    let conn = db.lock().await;
    let mut stmt = conn.prepare("SELECT id, name, parent_id FROM folders WHERE parent_id IS NULL ORDER BY display_order")
        .map_err(|e| e.to_string())?;

    let mut folders = Vec::new();
    while let Ok(true) = stmt.next() {
        folders.push(Folder {
            id: stmt.read::<i64>(0).map_err(|e| e.to_string())?,
            name: stmt.read::<String>(1).map_err(|e| e.to_string())?,
            parent_id: stmt.read::<Option<i64>>(2).map_err(|e| e.to_string())?,
        });
    }
    Ok(folders)
}
```

### Quy tắc
- `bind()` dùng **vị trí tham số tính từ 1**, không phải tên.
- Đọc cột dùng vị trí tính từ 0: `stmt.read::<T>(0)`.
- Luôn dùng `map_err(|e| e.to_string())` để chuyển đổi lỗi.
- Dùng `conn.execute()` cho INSERT/UPDATE/DELETE không có kết quả.

## Chiến lược Migration

- **Không dùng framework migration** — chỉ dùng `CREATE TABLE IF NOT EXISTS` thủ công.
- Các bảng hiện có: `shared_links`, `groups`, `folder_metadata`.
- Thêm bảng mới: thêm `CREATE TABLE IF NOT EXISTS` vào khối migration trong `db.rs`.
- Thay đổi schema: dùng `ALTER TABLE` trong khối migration nếu tương thích ngược.
- Thay đổi phá vỡ: triển khai migration theo phiên bản (chưa thực hiện — thêm nếu cần).

## Pattern Transaction

```rust
let conn = db.lock().await;
conn.execute("BEGIN IMMEDIATE").map_err(|e| e.to_string())?;
// ... thao tác ...
conn.execute("COMMIT").map_err(|e| e.to_string())?;
// Khi lỗi: conn.execute("ROLLBACK")
```

- Dùng chế độ transaction `IMMEDIATE` để tránh deadlock với truy cập đồng thời.
- Luôn xử lý rollback khi lỗi.

## Mẫu thêm bảng mới

```rust
conn.execute(
    "CREATE TABLE IF NOT EXISTS new_table (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        created_at TEXT DEFAULT (datetime('now'))
    )"
).map_err(|e| e.to_string())?;
```

## Kiểu dữ liệu

| Kiểu SQLite | Kiểu Rust |
|-------------|-----------|
| `INTEGER` | `i64` hoặc `Option<i64>` |
| `REAL` | `f64` |
| `TEXT` | `String` hoặc `Option<String>` |
| `BLOB` | `Vec<u8>` |
