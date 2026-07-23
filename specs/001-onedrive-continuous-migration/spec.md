# Đặc tả Tính năng: OneDrive Continuous Migration

**Feature Branch**: `001-onedrive-continuous-migration`

**Ngày tạo**: 2026-07-23

**Trạng thái**: Đã duyệt

**Đầu vào**: Yêu cầu từ người dùng: "Tạo specification cho tính năng OneDrive Continuous Migration của dự án Telegram-Drive. Tính năng này cho phép người dùng tự động đồng bộ/migrate file từ OneDrive sang Telegram một cách liên tục."

## Ngôn ngữ

**QUAN TRỌNG**: Toàn bộ nội dung specification này được viết bằng **Tiếng Việt**. Chỉ giữ tên công nghệ, thư viện, biến/hàm/lớp bằng tiếng Anh.

## Tổng quan

OneDrive Continuous Migration cho phép người dùng tự động đồng bộ file từ thư mục OneDrive sang Telegram. Hệ thống chạy liên tục trong nền, tự động phát hiện file mới/thay đổi, tải về, upload lên Telegram, xác minh và (tùy chọn) xóa file gốc khỏi OneDrive. Người dùng có thể đóng cửa sổ ứng dụng mà migration vẫn tiếp tục chạy qua system tray.

## User Scenarios & Testing

### User Story 1 - Thiết lập và chạy migration lần đầu (Priority: P1)

Người dùng có một thư mục trên OneDrive chứa nhiều file cần chuyển sang Telegram. Họ mở ứng dụng Telegram-Drive, vào tính năng OneDrive Migration, làm theo wizard 5 bước để kết nối tài khoản Microsoft, chọn thư mục nguồn, chọn đích đến Telegram, cấu hình chính sách migration, xem báo cáo preflight và khởi động migration. Hệ thống tự động quét toàn bộ file trong thư mục, tải từng file về, upload lên Telegram, xác minh và hoàn tất.

**Tại sao ưu tiên này**: Đây là luồng cốt lõi — không có nó, tính năng không tồn tại. Người dùng phải thiết lập được migration và thấy file đầu tiên được chuyển thành công.

**Kiểm thử độc lập**: Có thể kiểm thử bằng cách tạo một thư mục OneDrive với 3-5 file nhỏ, chạy wizard, khởi động migration và xác nhận tất cả file xuất hiện trong Telegram đích với nội dung chính xác.

**Kịch bản chấp nhận**:

1. **Given** người dùng đã đăng nhập Telegram trong Telegram-Drive, **When** họ mở giao diện Migration lần đầu, **Then** hệ thống hiển thị wizard thiết lập 5 bước với bước 1 (Kết nối Microsoft) đang active.
2. **Given** người dùng chưa kết nối Microsoft, **When** họ nhấn "Kết nối Tài khoản Microsoft", **Then** trình duyệt hệ thống mở ra trang đăng nhập Microsoft, sau khi đăng nhập thành công, token được lưu an toàn và trạng thái hiển thị "Đã kết nối".
3. **Given** đã kết nối Microsoft, **When** người dùng chọn thư mục nguồn trên OneDrive qua trình duyệt thư mục, **Then** hệ thống hiển thị đường dẫn thư mục đã chọn và số lượng file phát hiện được.
4. **Given** đã chọn thư mục nguồn, **When** người dùng chọn đích Telegram (kênh/nhóm/Saved Messages), **Then** hệ thống lưu đích đến và hiển thị tên đích đã chọn.
5. **Given** đã hoàn tất cấu hình, **When** người dùng đến bước Preflight và nhấn "Bắt đầu Continuous Migration", **Then** hệ thống bắt đầu quét file, tải về, upload và hiển thị tiến trình real-time trên dashboard.

---

### User Story 2 - Migration chạy nền khi đóng cửa sổ (Priority: P2)

Người dùng đã khởi động migration và muốn đóng cửa sổ ứng dụng để làm việc khác. Migration vẫn tiếp tục chạy trong nền. Người dùng có thể mở lại dashboard bất cứ lúc nào từ system tray để xem tiến trình.

**Tại sao ưu tiên này**: Migration có thể mất nhiều ngày với lượng file lớn. Nếu bắt buộc phải mở cửa sổ, người dùng sẽ không thể sử dụng tính năng này một cách thực tế.

**Kiểm thử độc lập**: Khởi động một migration, đóng cửa sổ ứng dụng, đợi 5 phút, mở lại từ system tray và xác nhận migration đã xử lý thêm file trong thời gian đó.

**Kịch bản chấp nhận**:

1. **Given** migration đang chạy, **When** người dùng đóng cửa sổ ứng dụng, **Then** ứng dụng thu nhỏ xuống system tray thay vì thoát, migration tiếp tục chạy.
2. **Given** ứng dụng đang ở system tray với migration đang chạy, **When** người dùng nhấp vào biểu tượng tray, **Then** menu hiển thị trạng thái migration hiện tại và tùy chọn "Mở Dashboard".
3. **Given** ứng dụng đang ở system tray, **When** người dùng chọn "Mở Dashboard", **Then** cửa sổ chính hiển thị lại với dashboard migration đang active.
4. **Given** ứng dụng được cấu hình autostart, **When** người dùng đăng nhập vào hệ điều hành, **Then** ứng dụng tự động khởi động ở system tray và migration resume từ trạng thái trước đó.

---

### User Story 3 - Phục hồi sau sự cố (Priority: P2)

Trong quá trình migration, máy tính bị mất điện đột ngột hoặc ứng dụng bị crash. Khi khởi động lại, migration tự động phục hồi từ trạng thái đã lưu, không upload trùng file, không mất dữ liệu, tiếp tục từ điểm bị gián đoạn.

**Tại sao ưu tiên này**: Migration xử lý lượng file lớn trong thời gian dài. Nếu mỗi lần crash đều phải bắt đầu lại từ đầu, tính năng sẽ không đáng tin cậy và gây lãng phí băng thông, thời gian.

**Kiểm thử độc lập**: Đang migration một file 100MB, kill process khi upload được 50%, khởi động lại ứng dụng và xác nhận file được upload lại (không bị trùng) hoặc resume từ checkpoint.

**Kịch bản chấp nhận**:

1. **Given** file đang được download từ OneDrive và ứng dụng crash, **When** ứng dụng khởi động lại, **Then** hệ thống kiểm tra file `.part` hiện có, resume download từ byte đã tải (Range request) và tiếp tục.
2. **Given** file đã upload lên Telegram thành công nhưng chưa kịp lưu trạng thái, **When** ứng dụng khởi động lại, **Then** hệ thống dò tìm message trong Telegram đích qua tên file, xác nhận file đã upload và không upload lại.
3. **Given** file đã được xác minh upload thành công, **When** ứng dụng khởi động lại, **Then** hệ thống tiếp tục bước xóa nguồn (nếu được cấu hình) mà không upload lại.
4. **Given** migration đang trong trạng thái cooldown Telegram (FLOOD_WAIT), **When** ứng dụng khởi động lại, **Then** hệ thống đọc thời gian cooldown còn lại từ database và tiếp tục chờ thay vì gửi request mới ngay lập tức.

---

### User Story 4 - Giám sát và quản lý migration đang chạy (Priority: P3)

Người dùng muốn xem trạng thái chi tiết của migration: có bao nhiêu file đã hoàn thành, bao nhiêu đang chờ, file nào bị lỗi, thời gian cooldown còn lại. Họ có thể tạm dừng, tiếp tục, bỏ qua file lỗi, hoặc hủy toàn bộ migration.

**Tại sao ưu tiên này**: Sau khi migration chạy, người dùng cần công cụ để theo dõi và can thiệp khi cần. Đây là tính năng hoàn thiện trải nghiệm.

**Kiểm thử độc lập**: Khởi động migration với 20 file, trong đó cố tình có 2 file lỗi (file quá lớn), xác nhận dashboard hiển thị đúng số liệu và có thể bỏ qua file lỗi.

**Kịch bản chấp nhận**:

1. **Given** migration đang chạy với 50 file, **When** người dùng xem dashboard, **Then** hệ thống hiển thị: số file đã hoàn thành, đang xử lý, đang chờ, bị lỗi; dung lượng đã chuyển; dung lượng còn lại trên đĩa; trạng thái kết nối Microsoft và Telegram.
2. **Given** migration đang chạy, **When** người dùng nhấn "Tạm dừng", **Then** hệ thống hoàn tất file đang xử lý rồi dừng, trạng thái chuyển thành "Đã tạm dừng".
3. **Given** migration đang tạm dừng, **When** người dùng nhấn "Tiếp tục", **Then** hệ thống resume từ file tiếp theo trong hàng đợi.
4. **Given** một file bị lỗi 3 lần liên tiếp, **When** người dùng xem danh sách lỗi và nhấn "Bỏ qua", **Then** file đó được đánh dấu "đã bỏ qua" và hệ thống chuyển sang file tiếp theo.
5. **Given** migration đang chạy, **When** người dùng nhấn "Hủy Migration", **Then** hệ thống hiển thị xác nhận, sau khi xác nhận, dừng mọi hoạt động và chuyển trạng thái job thành "đã hủy".

---

### User Story 5 - Xóa file nguồn sau khi upload thành công (Priority: P3)

Người dùng muốn giải phóng dung lượng OneDrive bằng cách tự động xóa file gốc sau khi đã upload và xác minh thành công trên Telegram. Họ bật tùy chọn này trong cấu hình migration.

**Tại sao ưu tiên này**: Đây là tính năng giá trị gia tăng giúp người dùng thực sự "di cư" dữ liệu thay vì chỉ "sao chép". Tuy nhiên migration vẫn hoạt động tốt nếu không có nó.

**Kiểm thử độc lập**: Thiết lập migration với tùy chọn "xóa sau upload", upload 3 file, xác nhận cả 3 đã bị xóa khỏi OneDrive và vẫn tồn tại trên Telegram.

**Kịch bản chấp nhận**:

1. **Given** tùy chọn "Xóa khỏi OneDrive sau khi upload" được bật, **When** một file được upload và xác minh thành công, **Then** hệ thống gọi Microsoft Graph API để xóa file nguồn và cập nhật trạng thái thành "đã xóa nguồn".
2. **Given** tùy chọn xóa nguồn được bật nhưng file nguồn đã bị thay đổi (eTag không khớp), **When** hệ thống cố gắng xóa, **Then** hệ thống giữ nguyên file nguồn, đánh dấu trạng thái "source_changed" để người dùng xem xét thủ công.
3. **Given** tùy chọn "Xóa khỏi OneDrive" không được bật, **When** file được upload và xác minh thành công, **Then** hệ thống đánh dấu "đã hoàn thành" và giữ nguyên file nguồn trên OneDrive.

---

### Edge Cases

- Khi người dùng ngắt kết nối mạng trong lúc đang download: hệ thống phát hiện mất mạng, chuyển item về trạng thái `queued`, chờ mạng khôi phục và thử lại với backoff.
- Khi token Microsoft hết hạn: hệ thống tự động refresh token. Nếu refresh token cũng hết hạn, hệ thống tạm dừng migration và hiển thị yêu cầu người dùng xác thực lại qua UI.
- Khi ổ đĩa gần đầy: `required_payload_space = file_size + max(512 MiB, file_size × 5%)`, `minimum_global_free_space = 2 GiB`. Hệ thống tạm dừng download, chuyển job `low_disk`, tự kiểm tra lại mỗi 5 phút, tự resume khi đủ dung lượng.
- Khi file trên OneDrive bị thay đổi trong lúc đang upload: hệ thống phát hiện eTag thay đổi khi xóa nguồn, đánh dấu `source_changed` để người dùng quyết định.
- Khi Telegram trả về FLOOD_WAIT hoặc FLOOD_PREMIUM_WAIT: hệ thống persist `cooldown_until = now + X + max(60s, X × 10%)` vào database, chặn mọi upload (thủ công + migration), tự động resume sau khi hết cooldown. Không có nút bypass.
- Khi recovery tìm thấy 2+ Telegram message khớp cùng item: chuyển item `quarantined`, không tự chọn message, không xóa source, hiển thị conflict trong UI.
- Khi người dùng logout Telegram hoặc session hết hạn trong lúc migration đang chạy: hệ thống phát hiện mất kết nối, tạm dừng upload, chờ người dùng đăng nhập lại, resume từ trạng thái đã lưu.
- Khi cùng một file được phát hiện lại qua Delta API nhưng eTag giống hệt: hệ thống bỏ qua nhờ ràng buộc UNIQUE(job_id, drive_item_id, source_etag).
- Khi file trên OneDrive bị xóa bởi người dùng hoặc ứng dụng khác: Delta API trả về sự kiện "deleted", hệ thống bỏ qua item đó.
- Khi nhiều job migration được tạo: mỗi job chạy độc lập với scheduler riêng, chia sẻ chung `TelegramMutationScheduler` để tránh flood.
- Khi ứng dụng đang chạy migration và có bản cập nhật mới: hệ thống tạm dừng migration, lưu checkpoint, cho phép updater tiến hành.

## Requirements

### Functional Requirements

**Kết nối & Xác thực**

- **FR-001**: Hệ thống PHẢI hỗ trợ xác thực Microsoft qua OAuth 2.0 Authorization Code + PKCE, với redirect loopback về `http://127.0.0.1:{port}`.
- **FR-002**: Hệ thống PHẢI có phương án dự phòng Device Code Flow khi loopback redirect không khả dụng.
- **FR-003**: Hệ thống PHẢI lưu refresh token đã mã hóa (AES-256) trên đĩa với quyền truy cập hạn chế (0600 trên Unix), khóa mã hóa được sinh từ hostname và machine UUID.
- **FR-004**: Hệ thống PHẢI tự động refresh access token trước khi hết hạn và xử lý trường hợp refresh token hết hạn bằng cách yêu cầu người dùng xác thực lại.

**Quét & Phát hiện File**

- **FR-005**: Hệ thống PHẢI dùng Microsoft Graph Delta API để quét file trong thư mục nguồn, bao gồm quét lần đầu (initial) và quét tăng dần (incremental) theo lịch định kỳ.
- **FR-006**: Hệ thống PHẢI áp dụng "stability window" (mặc định 10 phút) trước khi đưa file vào hàng đợi xử lý, để tránh upload file đang được chỉnh sửa dở.
- **FR-007**: Hệ thống PHẢI hỗ trợ lọc file theo định dạng (bao gồm/loại trừ) và kích thước tối thiểu.
- **FR-008**: Hệ thống PHẢI phát hiện file đã bị xóa trên OneDrive qua Delta API và bỏ qua chúng.

**Download**

- **FR-009**: Hệ thống PHẢI tải file từ OneDrive qua `@microsoft.graph.downloadUrl` với hỗ trợ HTTP Range request để resume download sau gián đoạn.
- **FR-010**: Hệ thống PHẢI lưu file đang download dưới dạng `.part` và đổi tên atomic thành `.ready` khi hoàn tất.
- **FR-011**: Hệ thống PHẢI kiểm tra dung lượng đĩa trống trước mỗi lần download và từ chối download nếu không đủ dung lượng.
- **FR-012**: Hệ thống PHẢI dọn dẹp file `.part` mồ côi khi khởi động.

**Upload**

- **FR-013**: Hệ thống PHẢI upload file lên Telegram qua `TelegramMutationScheduler` — một hàng đợi duy nhất cho mọi upload (thủ công + migration) với cơ chế chống flood thích nghi.
- **FR-014**: Hệ thống PHẢI áp dụng pacing giữa các lần upload (45-90 giây cho file nhỏ, 15-30 giây cho file lớn) để tránh bị Telegram giới hạn tốc độ.
- **FR-015**: Hệ thống PHẢI xử lý FLOOD_WAIT bằng cách persist thời gian cooldown vào database, chặn mọi upload trong thời gian chờ, và tự động resume sau đó.
- **FR-016**: Upload thủ công từ người dùng PHẢI được ưu tiên cao hơn upload migration trong hàng đợi.
- **FR-017**: Hệ thống PHẢI trả về Telegram message ID sau mỗi lần upload thành công và lưu vào database để phục vụ xác minh và phục hồi.

**Xác minh**

- **FR-018**: Hệ thống PHẢI xác minh mỗi file sau khi upload bằng cách kiểm tra message tồn tại trong Telegram đích, khớp tên file và kích thước.

**Xóa nguồn (tùy chọn)**

- **FR-019**: Hệ thống PHẢI hỗ trợ tùy chọn xóa file nguồn khỏi OneDrive sau khi upload và xác minh thành công, dùng header `If-Match` với eTag để tránh xung đột.
- **FR-020**: Khi xóa nguồn thất bại do eTag không khớp (HTTP 412), hệ thống PHẢI đánh dấu `source_changed` thay vì cố xóa.

**Chạy nền**

- **FR-021**: Ứng dụng PHẢI thu nhỏ xuống system tray khi người dùng đóng cửa sổ, với menu tray hiển thị trạng thái migration và tùy chọn mở dashboard.
- **FR-022**: Ứng dụng PHẢI hỗ trợ tự động khởi động cùng hệ điều hành (autostart) để migration có thể resume sau khi restart máy.

**Phục hồi**

- **FR-023**: Hệ thống PHẢI commit mọi chuyển đổi trạng thái vào SQLite TRƯỚC KHI thực hiện hành động tương ứng (download, upload, verify, delete).
- **FR-024**: Khi khởi động lại, hệ thống PHẢI đối chiếu tất cả item ở trạng thái non-terminal và phục hồi về trạng thái đúng (resume download, tìm message đã upload, retry delete).
- **FR-025**: Hệ thống PHẢI dùng `migration_key` (SHA-256 của account+drive+item+etag) và `telegram_physical_name` (định dạng `tdm_{hash}__{original}`) để đảm bảo idempotency khi phục hồi.

**Giao diện người dùng**

- **FR-026**: Hệ thống PHẢI cung cấp wizard thiết lập 5 bước: Kết nối Microsoft → Chọn thư mục nguồn → Chọn đích Telegram → Cấu hình chính sách → Xem báo cáo Preflight & khởi động.
- **FR-027**: Hệ thống PHẢI cung cấp dashboard real-time hiển thị: số file hoàn thành/đang chờ/lỗi, tiến trình download/upload hiện tại, trạng thái kết nối, dung lượng đĩa, thời gian cooldown.
- **FR-028**: Hệ thống PHẢI hỗ trợ các hành động quản lý: tạm dừng/tiếp tục migration, bỏ qua file lỗi, thử lại file lỗi, hủy toàn bộ migration.
- **FR-029**: Hệ thống PHẢI hiển thị nhật ký sự kiện migration theo thời gian thực, bao gồm chuyển đổi trạng thái, lỗi và cảnh báo.

**State Machine**

- **FR-030**: Hệ thống PHẢI quản lý trạng thái job theo các trạng thái: `created → preflight → running ⇄ paused`, các trạng thái chờ (sub-states của `running`): `waiting`, `graph_cooldown`, `telegram_cooldown`, `low_disk`, `auth_required`; trạng thái chuyển tiếp: `stopping`; trạng thái kết thúc: `completed`, `cancelled`, `fatal`.
- **FR-031**: Hệ thống PHẢI quản lý trạng thái item theo các trạng thái: `discovered → stabilizing → queued → downloading → downloaded → upload_intent → uploading → uploaded → verifying → verified → delete_pending → deleting_source → completed`, với các trạng thái phụ: `upload_unknown` (phục hồi), `source_changed`, `delete_failed`, `skipped`, `quarantined`.
- **FR-032**: Mọi chuyển đổi trạng thái PHẢI được xác thực — chỉ các chuyển đổi được định nghĩa sẵn mới được phép thực hiện.

### Key Entities

- **Migration Job**: Đại diện cho một tác vụ migration từ một thư mục OneDrive đến một đích Telegram. Thuộc tính chính: tên, tài khoản Microsoft, drive ID, đường dẫn nguồn, đích Telegram, trạng thái, cấu hình (xóa nguồn, stability window, chu kỳ quét, bộ lọc file), delta link, thời gian cooldown.
- **Migration Item**: Đại diện cho một file cần xử lý trong job. Thuộc tính chính: drive item ID, đường dẫn tương đối, tên gốc, tên vật lý trên Telegram (`tdm_{hash}__{original}`), migration key, kích thước, eTag nguồn, trạng thái, đường dẫn local, message ID Telegram, số lần thử, thông tin lỗi.
- **Migration Event**: Nhật ký kiểm toán cho mọi sự kiện quan trọng. Thuộc tính: job ID, item ID, mức độ, danh mục, mã sự kiện, thông điệp, chi tiết (JSON), thời gian.
- **Rate Limit State**: Trạng thái giới hạn tốc độ cho các dịch vụ bên ngoài (Telegram, Microsoft Graph). Thuộc tính: dịch vụ, thời gian cooldown, mức độ an toàn, số lần thành công liên tiếp, số lần flood.
- **Worker Heartbeat**: Trạng thái hoạt động của worker migration. Thuộc tính: worker ID, PID, thời gian bắt đầu, heartbeat cuối, job/item hiện tại, pha hiện tại.

## Success Criteria

### Measurable Outcomes

- **SC-001**: Người dùng có thể hoàn tất thiết lập migration (từ lúc mở wizard đến lúc nhấn "Bắt đầu") trong dưới 5 phút, với điều kiện đã có sẵn tài khoản Microsoft và Telegram.
- **SC-002**: 95% file được migration thành công trong lần thử đầu tiên (không cần retry) với điều kiện mạng ổn định.
- **SC-003**: Sau khi ứng dụng crash hoặc bị tắt đột ngột, migration resume đúng trạng thái trong vòng 30 giây kể từ khi khởi động lại, không có file nào bị upload trùng hoặc mất dữ liệu.
- **SC-004**: Hệ thống duy trì tốc độ upload trung bình tối thiểu 10 file/giờ cho file nhỏ (< 10MB) và 2 file/giờ cho file lớn (> 100MB) trong điều kiện mạng bình thường, không bị Telegram FLOOD_WAIT quá 1 lần mỗi 24 giờ.
- **SC-005**: Người dùng có thể đóng cửa sổ ứng dụng và migration vẫn tiếp tục chạy — khi mở lại dashboard sau 1 giờ, ít nhất 80% file dự kiến trong khoảng thời gian đó đã được xử lý.
- **SC-006**: Dashboard hiển thị trạng thái migration với độ trễ dưới 2 giây so với trạng thái thực tế trong backend.

## Assumptions

- Người dùng đã có tài khoản Microsoft với OneDrive và đã đăng nhập Telegram trong Telegram-Drive trước khi thiết lập migration.
- Người dùng có kết nối Internet ổn định (không yêu cầu băng thông tối thiểu cụ thể, nhưng migration sẽ chậm hơn với kết nối kém).
- MVP chỉ hỗ trợ nền tảng desktop (Windows, macOS, Linux), không hỗ trợ Android.
- Người dùng tự đăng ký ứng dụng Azure AD riêng (client ID) để xác thực Microsoft Graph — ứng dụng không cung cấp client ID dùng chung.
- File trên OneDrive không bị giới hạn bởi chính sách quản trị doanh nghiệp (như Conditional Access, DLP) ngăn chặn download qua API.
- Telegram session hiện tại của người dùng được dùng chung cho cả UI và migration worker — không tạo session riêng.
- Migration worker chạy trong cùng process Tauri, không phải process riêng — nếu Tauri process bị kill, migration dừng cho đến khi ứng dụng được khởi động lại (tự động qua autostart hoặc thủ công).
- Dung lượng lưu trữ local cache cho file download phụ thuộc vào ổ đĩa của người dùng — ứng dụng không giới hạn cache nhưng cảnh báo khi dung lượng thấp.
- Cấu trúc thư mục trên OneDrive được giữ nguyên khi migration — mỗi file được upload với tên vật lý chứa hash để đảm bảo tính duy nhất, nhưng không tạo cấu trúc thư mục trên Telegram.

## Phạm vi (Scope)

### Trong phạm vi (In Scope)

- Kết nối tài khoản Microsoft cá nhân và OneDrive for Business qua OAuth 2.0 PKCE
- Quét và phát hiện file mới/thay đổi qua Microsoft Graph Delta API
- Download file từ OneDrive với khả năng resume sau gián đoạn
- Upload file lên Telegram qua cơ chế hàng đợi chống flood
- Xác minh upload thành công trước khi đánh dấu hoàn tất
- Tùy chọn xóa file nguồn khỏi OneDrive sau khi xác minh
- Chạy nền qua system tray khi đóng cửa sổ ứng dụng
- Tự động khởi động cùng hệ điều hành (autostart)
- Phục hồi trạng thái migration sau khi crash hoặc tắt đột ngột
- Dashboard giám sát thời gian thực với khả năng tạm dừng/tiếp tục/hủy
- Lọc file theo định dạng và kích thước tối thiểu
- Hỗ trợ đa nền tảng desktop: Windows, macOS, Linux

### Ngoài phạm vi (Out of Scope)

- Hỗ trợ nền tảng di động (Android/iOS) — desktop only cho MVP
- Hỗ trợ SharePoint Online hoặc Teams files (chỉ OneDrive cá nhân và Business)
- Đồng bộ hai chiều (two-way sync) — chỉ migration một chiều OneDrive → Telegram
- Giữ nguyên cấu trúc thư mục trên Telegram (upload phẳng, không tạo folder)
- Hỗ trợ nhiều tài khoản Microsoft cùng lúc
- Hỗ trợ nhiều tài khoản Telegram cùng lúc cho migration
- Migration file lớn hơn 2GB (giới hạn Telegram API)
- Hỗ trợ Google Drive, Dropbox hoặc các cloud storage khác
- Tự động phát hiện và migrate file từ thư mục con của thư mục được chọn (chỉ file trong thư mục gốc được chọn)
- Giao diện quản lý migration qua REST API (chỉ qua UI desktop)
- Chạy migration như service hệ thống độc lập (systemd/launchd) — thuộc Phase 2
- Mã hóa file trước khi upload lên Telegram

## Yêu cầu Phi Chức năng (Non-Functional Requirements)

### Hiệu năng (Performance)

- **NFR-P01**: Ứng dụng không tiêu thụ quá 200MB RAM bổ sung khi migration đang chạy (ngoài mức tiêu thụ cơ bản của Tauri app).
- **NFR-P02**: CPU usage khi idle (giữa các chu kỳ quét) không vượt quá 2% trên máy trung bình (4-core).
- **NFR-P03**: Thời gian phục hồi trạng thái migration (từ lúc Tauri backend hoàn tất startup đến khi worker tiếp tục xử lý item hợp lệ hoặc vào trạng thái chờ ổn định) không quá 30 giây. Không tính thời gian OS login, user nhập credential, hoặc bắt buộc chờ FLOOD_WAIT/Graph Retry-After.

### Độ tin cậy (Reliability)

- **NFR-R01**: Mọi chuyển đổi trạng thái phải được ghi vào SQLite với `synchronous=FULL` để đảm bảo không mất dữ liệu khi mất điện đột ngột.
- **NFR-R02**: Không được upload trùng file trong mọi tình huống, kể cả crash ở bất kỳ thời điểm nào trong pipeline.
- **NFR-R03**: Token Microsoft phải được tự động refresh. Nếu refresh thất bại, migration phải tạm dừng an toàn (không crash, không mất trạng thái).

### Bảo mật (Security)

- **NFR-S01**: Refresh token Microsoft phải được mã hóa AES-256-GCM trước khi lưu xuống đĩa. Khóa mã hóa không được lưu cùng file token.
- **NFR-S02**: File token phải có permission `0600` (chỉ owner đọc/ghi) trên Unix.
- **NFR-S03**: Không được log refresh token, access token, hoặc auth code ra console hoặc file log.
- **NFR-S04**: Microsoft Graph API calls phải dùng HTTPS (được đảm bảo bởi `reqwest` với `rustls-tls`).

### Khả năng Bảo trì (Maintainability)

- **NFR-M01**: Module migration (`app/src-tauri/src/migration/`) phải độc lập — có thể bị xóa hoặc disable mà không ảnh hưởng đến chức năng hiện tại của app.
- **NFR-M02**: Tất cả migration events phải được ghi vào `migration_events` table để hỗ trợ debug và audit.
- **NFR-M03**: Code migration phải tuân theo convention hiện tại của dự án: raw SQL, `Result<T, String>`, Tauri state pattern.

### Tương thích (Compatibility)

- **NFR-C01**: Migration không được làm thay đổi hành vi upload thủ công hiện tại. Upload từ UI và REST API phải hoạt động giống hệt trước khi có migration.
- **NFR-C02**: `migration.db` phải tương thích ngược — schema migrations phải chạy được từ version 0 (file chưa tồn tại).

## Phụ thuộc (Dependencies)

### Phụ thuộc ngoài

| Thành phần | Mô tả | Rủi ro nếu không có |
|---|---|---|
| Microsoft Graph API | Delta API, download URL, delete item | Toàn bộ tính năng không hoạt động |
| Microsoft OAuth 2.0 endpoint | `login.microsoftonline.com` | Không thể xác thực |
| Telegram MTProto API | Upload file, send message, verify | Không thể upload file lên Telegram |
| `reqwest` crate 0.12 | HTTP client cho Graph API calls | Không thể gọi Microsoft API |
| `sqlite` crate 0.37.0 | Database cho trạng thái migration | Không thể lưu/phục hồi trạng thái |
| `tauri-plugin-autostart` | Tự động khởi động cùng OS | Người dùng phải mở app thủ công sau restart |
| `sha2`, `aes-gcm`, `hostname` | Mã hóa token, migration key | Token lưu không an toàn, không idempotency |

### Phụ thuộc trong

| Thành phần | Mô tả |
|---|---|
| `TelegramState` | Migration dùng chung Telegram client với UI |
| `BandwidthManager` | Migration upload PHẢI tuân thủ bandwidth limit hiện tại, không tạo limiter thứ hai |
| `SettingsContext` (React) | Migration settings được lưu trong context hiện tại |

> **Ghi chú (D04)**: `NetworkConfig` không phải là dependency trực tiếp của migration. Migration dùng Telegram client hiện có — client đã được cấu hình với `NetworkConfig` sẵn. Không tạo coupling giả tạo.

## Phiên bản Tài liệu

| Phiên bản | Ngày | Thay đổi | Tác giả |
|---|---|---|---|
| 1.0 | 2026-07-23 | Tạo lần đầu từ speckit.specify | AI Agent |
| 1.1 | 2026-07-23 | Thêm Out of Scope, NFR, Dependencies; đổi trạng thái → Đã duyệt | AI Agent (review) |
| 1.2 | 2026-07-23 | Đồng bộ D05-D12, D18: filter syntax, disk formula, pacing 3 levels, safety transitions 100/500, job/item states, multi-match recovery, metrics definition, NetworkConfig removal, BandwidthManager mandatory | Remediation Agent |
