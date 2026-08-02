# MASTER PLAN — TELEGRAM DRIVE ANDROID

**Phiên bản:** Final
**Trạng thái:** Ready to implement
**Mục đích:** Nguồn spec duy nhất cho người triển khai và AI agent

---

## 1. Mục tiêu sản phẩm

Xây dựng một ứng dụng Android độc lập bằng Kotlin và Jetpack Compose, cho phép người dùng:

* Đăng nhập Telegram.
* Khôi phục phiên đăng nhập.
* Truy cập Saved Messages và các channel/chat phù hợp.
* Duyệt danh sách file và media.
* Tải file về vùng lưu trữ do ứng dụng quản lý.
* Preview ảnh, video, audio và PDF.
* Mở các định dạng không được hỗ trợ bằng ứng dụng Android bên ngoài.
* Đăng xuất và xóa toàn bộ dữ liệu liên quan đến tài khoản.

Ứng dụng giao tiếp trực tiếp với Telegram thông qua TDLib.

Ứng dụng không phụ thuộc vào:

* Tauri.
* WebView.
* Rust backend.
* Local HTTP server.
* Backend hoặc session protocol riêng.
* Ứng dụng Telegram Drive hiện tại trong quá trình runtime.

---

## 2. Bối cảnh và giả định sản phẩm

### 2.1. Distribution mode

Phạm vi hiện tại là:

* Ứng dụng personal/internal.
* Một người phát triển hoặc một nhóm nhỏ sử dụng.
* Chưa phải sản phẩm phân phối công khai trên Play Store.

Nếu chuyển sang public distribution, phải mở lại các quyết định:

* API ID ownership.
* Cách cung cấp API credentials.
* Tên và branding.
* Disclosure về Telegram API.
* Privacy policy.
* Monetization.
* Sponsored-message obligations nếu có.
* Store compliance.

Public distribution là release gate riêng, không chặn các phase kỹ thuật hiện tại.

### 2.2. Account scope

MVP chỉ hỗ trợ:

* Một tài khoản tại một thời điểm.
* Một active TDLib client cho tài khoản đó trong mỗi process.

Multi-account nằm ngoài MVP.

Kiến trúc không được tạo ra ràng buộc khiến multi-account trở thành bất khả thi trong tương lai, nhưng không xây subsystem multi-account trước khi có nhu cầu.

### 2.3. Android support matrix

Support matrix chính thức được xác định trong Phase 0 dựa trên:

* Thiết bị cần hỗ trợ.
* TDLib và native toolchain.
* Android API behavior.
* Chi phí tương thích.
* Khả năng kiểm thử.

Working assumption ban đầu:

* Minimum SDK không cao hơn mức cần thiết.
* Có thể bắt đầu đánh giá từ API 26.
* Không mặc định nâng lên API 31+ nếu chưa có quyết định và bằng chứng.

Quyết định cuối phải được ghi vào tài liệu kiến trúc và known-good toolchain matrix.

---

## 3. Phạm vi MVP

### 3.1. Trong phạm vi Phase 0–2 — MVP Android hiện tại

* Android application độc lập.
* Kotlin và Jetpack Compose.
* TDLib Java/JNI.
* API credentials setup phù hợp với personal/internal app.
* Đăng nhập bằng số điện thoại.
* Authentication code.
* Password 2FA.
* Các authorization state bổ sung xuất hiện trong TDLib version được pin.
* Session persistence và restore.
* Saved Messages.
* Danh sách channel/chat phù hợp.
* Phân trang lịch sử.
* Lọc message chứa file hoặc media.
* Download progress.
* Cancel và retry download.
* Preview ảnh.
* Preview video local.
* Preview audio và voice.
* Preview PDF cơ bản.
* Preview text có giới hạn.
* Mở file không hỗ trợ bằng ứng dụng bên ngoài.
* Cache và local preferences cơ bản.
* Logout/reset toàn bộ dữ liệu tài khoản.
* Offline/error behavior cơ bản.
* Validation local bằng Gradle, Android CLI, emulator và thiết bị thật.

### 3.2. Ngoài phạm vi Phase 0–2

Các mục dưới đây không thuộc MVP Phase 0–2 nhưng đã được phân công chính thức sang
phase sau; không được hiểu là bị loại khỏi product roadmap:

* Upload file.
* Tạo, đổi tên hoặc xóa channel.
* Global gallery xuyên toàn bộ tài khoản (Phase 5 / Backlog).
* Full media index bằng Room cho Saved Messages (Phase 3, phạm vi chỉ ảnh và video).
* Search/filter/sort local trên media index (Phase 3).
* Video streaming khi file chưa tải hoàn tất (Phase 3, progressive TDLib partial/range download).
* Background download phức tạp.
* Picture-in-Picture.
* Background audio service.
* Multi-account.
* Persistent export hàng loạt ra shared storage.
* Hardening, release-ready, device matrix và performance budgets (Phase 4).
* CI/CD.
* MCP server.
* Lightbuild.
* Cloud build.
* Automated release pipeline.
* Play Store distribution.

### 3.3. Roadmap chính thức sau Phase 2

| Phase | Phạm vi |
| --- | --- |
| Phase 0–2 | MVP Android hiện tại |
| Phase 3 | Saved Messages Local Media Gallery với TDLib progressive video streaming |
| Phase 4 | Hardening và release-ready |
| Phase 5 / Backlog | Các tính năng nâng cao còn lại |

Phase 3 là feature/architecture mới kế thừa Phase 2. Phase 4 nhận toàn bộ acceptance
criteria hardening/release-ready trước đây đặt ở Phase 3; Phase 5/Backlog nhận các
advanced feature chưa thuộc Phase 3.

---

## 4. Nguyên tắc của master plan

Master plan chỉ quy định:

* Outcome.
* Boundary.
* Behavior.
* Constraint.
* Deliverable.
* Evidence.
* Acceptance criteria.
* Risk.
* Exit condition.

Master plan không khóa cứng:

* Package tree.
* Tên class hoặc method.
* Code snippet.
* Concurrency primitive.
* Flow, Channel hoặc loại state container cụ thể.
* API ordering chi tiết.
* Dependency version cụ thể trước khi khảo sát.
* Chi tiết implementation thuộc từng task.

Người triển khai hoặc AI agent tự lựa chọn implementation, miễn đáp ứng đầy đủ spec.

---

## 5. Yêu cầu kiến trúc

### 5.1. Dependency direction

Kiến trúc phải duy trì logical boundaries giữa:

* Compose UI.
* State holder hoặc ViewModel.
* Repository contract.
* TDLib infrastructure.
* Real source.
* Fake source.

Yêu cầu:

* UI không gọi TDLib trực tiếp.
* UI không phụ thuộc generated model của TDLib.
* ViewModel không phụ thuộc chi tiết JNI.
* Domain/app-facing model độc lập với TDLib model.
* Real và fake source tuân thủ cùng behavioral contract.
* Dependency chỉ đi theo hướng vào infrastructure, không đi ngược từ TDLib lên UI.
* TDLib client có owner và lifecycle rõ ràng.

MVP có thể sử dụng một Android application module.

Single module không có nghĩa bỏ logical separation giữa UI, repository và infrastructure.

Chỉ modularize khi có bằng chứng rõ ràng về nhu cầu build, ownership, reuse hoặc isolation.

### 5.2. TDLib lifecycle

Yêu cầu cuối cùng:

> Tối đa một active TDLib client cho mỗi account trong một process. Configuration change, Activity recreation, navigation recreation hoặc Compose recomposition không tạo client trùng. Sau process death, process mới khởi tạo client đúng một lần và restore session từ persistent state.

Lifecycle phải xử lý rõ:

* Start.
* Initialization.
* Running.
* Failure.
* Closing.
* Closed.
* Explicit logout.
* Account reset.
* Test teardown.
* Process termination đột ngột.

Không sử dụng `Application.onTerminate()` như production cleanup guarantee.

### 5.3. Authorization và session

Telegram authorization và session được quản lý thông qua TDLib.

Ứng dụng:

* Không xây authentication backend riêng.
* Không xây session protocol riêng.
* Quản lý UI state, local configuration và lifecycle cần thiết để tương tác an toàn với TDLib.
* Không hardcode một chuỗi auth cố định.
* Hỗ trợ các authorization state xuất hiện trong TDLib version được pin.
* State chưa hỗ trợ phải fail safely.
* Unknown state không được làm crash hoặc bị bỏ qua âm thầm.
* Không gửi action không hợp lệ với state hiện tại.
* Không gửi request trùng do recomposition hoặc repeated click.

### 5.4. Dependency version policy

* Toolchain và production dependencies phải được pin hoặc quản lý tập trung.
* Không sử dụng dynamic version.
* Version catalog hoặc cơ chế tương đương là nguồn sự thật duy nhất.
* Các dependency có compatibility coupling phải được nâng cấp cùng nhau.
* Mỗi major upgrade phải có build, test và runtime validation.
* Không nâng dependency không liên quan trong feature task.
* Dependency update phải có diff riêng hoặc justification rõ ràng.
* TDLib, NDK, crypto dependency và Android toolchain phải có compatibility record.
* Project phải duy trì known-good toolchain matrix.

Known-good matrix tối thiểu gồm:

* JDK.
* Gradle wrapper.
* Android Gradle Plugin.
* Kotlin.
* Compose plugin/compiler strategy.
* Compile SDK.
* Target SDK.
* Minimum SDK.
* NDK.
* CMake.
* TDLib revision.
* Crypto dependency.
* Các production dependency quan trọng.

### 5.5. TDLib source và binary provenance

Baseline bắt buộc:

* Pin TDLib source chính thức bằng tag, version hoặc commit bất biến.
* Có quy trình build có thể tái tạo.
* Pin native toolchain liên quan.
* Kiểm tra integrity của source.
* Ghi lại provenance.
* Ghi checksum của output.
* Phân biệt rõ ABI đã build, đã package và đã runtime-test.
* Không dùng binary không rõ nguồn.

#### Community binary exception

Community-maintained binary chỉ được đánh giá khi reproducible build từ official source bị block dài hạn.

Exception phải có:

* Repository và maintainer xác định.
* Version hoặc commit được pin.
* Source hoặc build process công khai.
* Checksum.
* License.
* Lịch sử phát hành có thể truy xuất.
* Supply-chain risk được ghi rõ.
* Không được mô tả là official.

Việc một project công khai sử dụng cùng artifact chỉ là bằng chứng hỗ trợ, không thay thế provenance và integrity verification.

Nếu không thể xác minh artifact tương ứng với source/build process đã công bố, exception không đạt acceptance criteria.

### 5.6. Secret và encryption material

Master plan không pin một storage library cụ thể.

Yêu cầu outcome:

* API credentials và TDLib encryption material được bảo vệ theo threat model.
* Sử dụng device-bound secure storage khi khả thi.
* Không log.
* Không commit.
* Không xuất hiện trong screenshot hoặc test fixture.
* Không backup ngoài ý muốn.
* Có thể xóa hoàn toàn.
* Không tự xây cryptographic primitive.
* Có behavior rõ ràng khi key mất, bị hỏng hoặc không thể giải mã.

Ưu tiên API và thư viện stable, được khuyến nghị hiện tại.

Nếu không có lựa chọn stable đáp ứng yêu cầu và một giải pháp deprecated vẫn functional, quyết định phải được đánh giá theo:

* Threat model.
* Android support scope.
* Maintenance risk.
* Known vulnerability.
* Độ phức tạp và rủi ro của phương án thay thế.

### 5.7. TDLib database encryption lifecycle

Phase 0 phải xác định và kiểm chứng:

* Khởi tạo encryption material.
* Lưu trữ.
* Truy xuất.
* Session restore.
* Explicit logout.
* Account reset.
* Key loss.
* Corrupt state.
* Backup exclusion.
* Data deletion.

Không được đánh dấu session restore hoàn thành nếu encryption lifecycle chưa được chứng minh.

### 5.8. Backup và data extraction

Phải ngăn backup hoặc device transfer ngoài ý muốn đối với:

* TDLib database.
* Telegram session.
* API credentials.
* Encryption material.
* Account data.
* Downloaded media nhạy cảm.
* Debug artifacts.

Phase 0 phải có:

* Backup policy rõ ràng.
* Manifest và data-extraction rules phù hợp.
* Validation trên merged manifest.
* Regression protection.

Không chờ Phase 3 mới xử lý backup.

### 5.9. Storage và external open

Phân biệt ba loại hành vi:

#### Working storage

* App-private hoặc TDLib-managed.
* Dùng cho download, cache, preview và playback.
* Không mặc định xuất hiện trong file manager.
* Có thể bị xóa khi logout hoặc uninstall.

#### External open

* Cho phép mở file bằng ứng dụng khác.
* Không chia sẻ raw filesystem path.
* Sử dụng secure Android content-sharing mechanism.
* Chỉ cấp quyền truy cập tạm thời.
* Không biến working file thành persistent user export.

#### Persistent user export

* Nằm ngoài MVP.
* Nếu được bổ sung sau, phải dùng shared-storage mechanism phù hợp.
* Phải được kích hoạt bởi hành động rõ ràng của người dùng.

### 5.10. Saved Messages

* Nhận diện Saved Messages theo semantics chính thức của TDLib version được pin.
* Không dựa vào title hoặc localized string.
* Phase 0–2 hỗ trợ lịch sử Saved Messages cơ bản qua paging trực tiếp TDLib.
* Phase 0–2 không quét toàn bộ history và không tạo global index.
* Phase 3 kế thừa flow này để quét toàn bộ Saved Messages, nhưng chỉ index ảnh và video
  vào Room theo account identity/generation.
* Không làm mất khả năng mở rộng sang Saved Messages topics.
* Có test hoặc runtime evidence với self-chat thật.

---

## 6. Offline và retry policy

Read operation có thể được thực hiện lại khi kết nối phục hồi nếu operation trước đã kết thúc bằng lỗi mạng hoặc lỗi server được xác định là có thể retry.

Yêu cầu:

* Không retry operation vẫn đang được TDLib quản lý.
* Không xây retry subsystem riêng.
* Không tạo retry loop vô hạn.
* Download chỉ retry theo ý định người dùng sau terminal failure.
* Login, logout và credential operation không tự retry.
* Luôn tôn trọng retry-after hoặc flood-wait do TDLib trả về.
* Retryability phải dựa trên semantics đã kiểm chứng, không mặc định mọi lỗi server đều retryable.
* Cached/local data vẫn có thể được hiển thị khi phù hợp.
* File unavailable không tự động bị xóa khỏi UI nếu metadata vẫn còn giá trị.

---

## 7. Transfer state contract

Contract được xác định trong Phase 1 và enforce đầy đủ trong Phase 2.

Yêu cầu:

* Mỗi transfer có một nguồn sự thật duy nhất.
* Transfer được nhận diện bằng file identity ổn định trong phạm vi account và TDLib session/database generation tương ứng.
* Không giả định raw TDLib file identifier có giá trị toàn cục giữa nhiều account, sau account reset hoặc sau khi TDLib database được tái tạo.
* Nhiều UI observer phải thấy trạng thái nhất quán.
* Recomposition, navigation và repeated click không tạo duplicate transfer.
* Progress update không block main thread.
* Transfer state không phụ thuộc lifetime của một Composable hoặc screen.
* Item rời màn hình không làm mất transfer state đang hoạt động.
* Fake source tuân thủ cùng behavioral contract với real source.
* Các terminal state phải explicit:

    * Completed.
    * Failed.
    * Cancelled.
    * Unavailable.
* Local file phải được xác minh hợp lệ trước khi trở thành previewable.
* Logout hoặc account reset phải vô hiệu hóa toàn bộ transfer identity của account.
* Late update từ generation cũ không được phục hồi state đã cancel hoặc đã xóa.
* Observer và progress history không được tạo memory growth không giới hạn.

Acceptance criteria tối thiểu:

* Hai request download cùng file identity, trong cùng account và cùng database generation, chỉ tạo tối đa một active transfer.
* Recomposition, navigation và repeated click không tạo transfer mới.
* Account reset hoặc thay database generation làm transfer identity cũ mất hiệu lực.
* Late update từ generation cũ không phục hồi transfer cũ.
* Re-login không tự động được xem là generation mới nếu database thực tế vẫn được giữ nguyên.

---

## 8. Testing strategy

Không đặt coverage percentage tùy ý.

Testing được ưu tiên theo rủi ro.

### 8.1. Unit test bắt buộc

* Authorization state mapping.
* Authorization transition.
* Invalid action trong auth state hiện tại.
* Duplicate submit guard.
* Secret redaction.
* Session state decision.
* Message/media mapping.
* Download state transition.
* Download deduplication.
* Storage/export decision.
* Logout/reset state.
* Các pure rule có thể kiểm thử deterministic.

### 8.2. Integration/runtime validation bắt buộc

* TDLib native load và client initialization.
* Authorization vertical slice.
* Session restore.
* Saved Messages retrieval.
* Download end-to-end.
* Image preview.
* Video player lifecycle.
* Backup và data-extraction configuration.
* Release hoặc minified smoke build và launch.

### 8.3. Có thể kiểm tra thủ công

* Visual polish.
* Animation.
* Low-risk UI edge cases.
* Layout trên thiết bị ngoài support matrix.
* Hành vi phụ thuộc OTP thật không thể tự động hóa an toàn.

### 8.4. Coverage principles

* Bug nghiêm trọng phải có regression test nếu có thể tái hiện deterministic.
* Security, lifecycle, concurrency và state machine được ưu tiên hơn UI snapshot.
* Không mock toàn bộ đến mức test chỉ chứng minh mock được gọi.
* Không commit credential, OTP hoặc session thật vào test artifact.
* Không yêu cầu automated test dựa vào Telegram account thật nếu không kiểm soát được tính ổn định.

---

## 9. Performance policy

### 9.1. Sanity floors — Phase 0 đến Phase 2

* Không ANR trong các user journey đã xác định.
* Không OOM với test dataset đã xác định.
* Không thực hiện tác vụ dài làm block main thread.
* Không có memory growth không giới hạn trong paging, preview hoặc transfer flow.
* Cold start không vượt quá 15 giây trên reference setup đã ghi nhận.

Cold start được đo từ lúc process được khởi động đến khi:

* Nội dung chính của màn hình đầu tiên được render.
* Màn hình có thể nhận input.
* Ứng dụng đã hiển thị trạng thái có ý nghĩa.
* Người dùng có thể thực hiện hành động hợp lệ tiếp theo.

Splash screen hoặc loading placeholder không được xem là hoàn thành cold start.

Ngưỡng 15 giây là sanity ceiling tạm thời, không phải performance target hoặc cam kết cho mọi thiết bị.

### 9.2. Performance budgets — Phase 3

Chỉ đặt budget sau khi xác định:

* Reference device.
* Android version.
* Build type.
* Cache state.
* Test dataset.
* Network condition.
* User journey.
* Baseline measurement.

Chỉ tối ưu khi:

* Vượt budget.
* Có regression.
* Có bằng chứng ảnh hưởng UX.

---

# PHASE 0 — FOUNDATION VÀ TDLIB SPIKE

## 10. Mục tiêu

Thiết lập nền tảng Android ổn định và loại bỏ rủi ro kỹ thuật chính.

Chuỗi cần chứng minh:

Project Android hợp lệ
→ local build thành công
→ app được cài và chạy
→ TDLib native library được nạp
→ TDLib client được tạo
→ nhận authorization state đầu tiên
→ security và backup foundation hợp lệ
→ fake source hoạt động
→ UI tách khỏi TDLib.

Phase 0 không xây login hoàn chỉnh, không lấy lịch sử Telegram thật và không xây preview.

## 11. Phạm vi

### 11.1. Environment và support matrix

Xác minh:

* Repository hiện tại.
* Android CLI.
* JDK.
* Android SDK.
* Build Tools.
* NDK.
* CMake.
* Gradle.
* Android Gradle Plugin.
* Emulator.
* Thiết bị thật.
* ABI.
* Dung lượng build.
* Android support matrix.

Kết quả phải được ghi thành environment report.

### 11.2. Project foundation

Đảm bảo có:

* Android application độc lập.
* Kotlin.
* Jetpack Compose.
* Kotlin DSL.
* Version catalog hoặc dependency source of truth tương đương.
* Theme cơ bản.
* Debug runtime.
* `.gitignore` phù hợp.
* Không có dependency hoặc template code không cần thiết.

### 11.3. Logical architecture boundaries

Thiết lập boundary cần thiết giữa:

* Bootstrap.
* UI.
* State.
* Repository.
* TDLib infrastructure.
* Real source.
* Fake source.

Chỉ tạo boundary cần thiết cho Phase 0.

### 11.4. TDLib spike

Phải chứng minh:

* Official source được pin.
* Build provenance rõ ràng.
* Integrity được kiểm tra.
* Native artifact được tạo cho ABI trong scope.
* Native library được package.
* Native library load thành công.
* Client được khởi tạo.
* Authorization state đầu tiên được nhận.
* Initialization không block main thread.
* Client không tạo trùng.
* Failure được biểu diễn an toàn.
* Lifecycle có thể đóng và khởi tạo lại đúng spec.

### 11.5. Security foundation

Phải xác định:

* API credential policy.
* Encryption material lifecycle.
* Backup exclusion.
* Data extraction policy.
* Account data directory.
* Key loss behavior.
* Corrupt state behavior.
* Logout/reset deletion boundary.

### 11.6. Fake source

Fake source phải có dữ liệu đại diện cho:

* Account.
* Saved Messages.
* Channel/chat source.
* Image.
* Video.
* Audio.
* PDF.
* Unsupported document.
* Download states.
* Error states.

Fake source phải có thể hoạt động mà không khởi tạo native TDLib.

### 11.7. Diagnostics

Development diagnostics phải cung cấp đủ bằng chứng về:

* Native library state.
* TDLib client state.
* Authorization state gần nhất.
* Real/fake source.
* Runtime ABI.
* Android API level.
* TDLib revision.
* Initialization failure an toàn.

Diagnostics phải có khả năng bị loại hoặc vô hiệu hóa trong release.

### 11.8. Documentation

Phase 0 phải tạo hoặc cập nhật:

* Project overview.
* Local setup.
* Architecture requirements.
* Known-good toolchain matrix.
* TDLib build provenance.
* Security và backup policy.
* AI agent rules.
* Phase progress.
* Phase report.

## 12. Phase 0 validation

* Unit test liên quan.
* Android lint.
* Debug build.
* Real-source build.
* Fake-source build.
* APK install bằng Android CLI.
* App launch.
* Layout hierarchy.
* Runtime screenshot.
* Native library inspection.
* ABI package inspection.
* TDLib initialization.
* Authorization state đầu tiên.
* Activity recreation.
* Force-stop và reopen.
* Không duplicate client.
* Fake source không load TDLib.
* Backup và data-extraction rules.
* Merged manifest.
* Git diff không chứa credential.

## 13. Phase 0 done when

* Android app build và chạy.
* Support matrix được ghi nhận.
* Known-good toolchain matrix tồn tại.
* TDLib source và binary provenance rõ ràng.
* ABI trong scope được build và package.
* Runtime ABI có bằng chứng.
* TDLib native load thành công.
* Client được tạo.
* Authorization state đầu tiên được nhận.
* Một active client/account/process được bảo đảm.
* Encryption lifecycle được xác định.
* Backup policy được kiểm chứng.
* Fake source hoạt động độc lập.
* Diagnostics hoạt động.
* Test, lint và build pass.
* Không có credential trong repository.
* Không có MCP, Lightbuild hoặc CI/CD.
* Report phản ánh đúng phần đã và chưa kiểm chứng.

---

# PHASE 1 — VERTICAL-SLICE PROOF OF CONCEPT

## 14. Mục tiêu

Chứng minh flow xuyên tầng:

Mở ứng dụng
→ TDLib khởi tạo
→ người dùng đăng nhập
→ session được lưu và khôi phục
→ mở Saved Messages
→ hiển thị file/media gần nhất
→ download ảnh hoặc video
→ preview ảnh
→ phát video local.

Phase 1 là proof of concept, chưa phải MVP hoàn chỉnh.

## 15. Phạm vi

### Bao gồm

* Authorization state machine.
* API ID/API hash setup cho local environment.
* Phone login.
* Authentication code.
* 2FA.
* Authorization state bổ sung khi xuất hiện.
* Session persistence.
* Session restore.
* Saved Messages.
* Tập message gần nhất có giới hạn.
* Media mapping cơ bản.
* Download cơ bản.
* Transfer state contract.
* Cancel và retry cơ bản.
* Image preview.
* Video download hoàn tất rồi playback local.
* Fake P1 flow.
* Release/minified smoke test.

### Không bao gồm

* Source browser đầy đủ.
* Multi-source paging.
* Global gallery.
* Full history scan.
* Room media index.
* Audio player hoàn chỉnh.
* PDF preview.
* Background download.
* Video streaming.
* Upload.
* Production release.

## 16. Phase 1 requirements

### 16.1. Authorization

* UI phản ánh authorization state thực tế.
* Không giả định sequence duy nhất.
* Unknown state fail safely.
* Không lưu OTP hoặc password.
* Không log credential.
* Không duplicate request.
* Flood wait được hiển thị phù hợp.
* Error được sanitize.

### 16.2. Session restore

Phải chứng minh:

* Login thành công.
* Force-stop.
* Mở lại.
* Không yêu cầu OTP nếu session còn hợp lệ.
* Không nhảy sai về login trong lúc initialization.
* Session lỗi hoặc hết hạn quay về auth an toàn.
* Backup policy vẫn bảo vệ session.

### 16.3. Saved Messages

* Xác định đúng Saved Messages.
* Tải một tập message gần nhất có giới hạn.
* Không quét toàn bộ history.
* Không tạo global index.
* Lọc image, video và document cơ bản.
* Không trả item trùng.
* Có loading, empty và error state.
* Fake source phản ánh cùng behavior.

### 16.4. Download PoC

* Start.
* Progress.
* Complete.
* Failure.
* Cancel.
* Retry theo user intent.
* Deduplicate.
* Local file validity.
* Không block main thread.
* Không tiếp tục sau process death.
* Tuân thủ transfer state contract.

### 16.5. Image preview

* Download nếu cần.
* Loading.
* Progress.
* Error.
* Back.
* Dedicated preview.
* Memory handling phù hợp.
* Không giữ reference sau khi đóng.
* Fake source testable.

### 16.6. Video local playback

* Download hoàn tất trước khi phát.
* Local file được xác minh.
* Play.
* Pause.
* Seek.
* Error.
* Back.
* Player lifecycle đúng.
* Không duplicate player.
* Không playback ngoài ý muốn khi rời screen.
* Không streaming.
* Không local HTTP server.

## 17. Phase 1 validation

* Authorization unit tests.
* Session state tests.
* Credential redaction tests.
* Saved Messages mapping tests.
* Download transition tests.
* Transfer deduplication tests.
* Image preview validation.
* Video player lifecycle validation.
* Fake runtime flow.
* Real authorization flow.
* Session restore.
* Saved Messages retrieval.
* Image download và preview.
* Video download và playback.
* Activity recreation.
* Mất mạng giữa download.
* Error state.
* Credential không xuất hiện trong log, screenshot hoặc diff.
* Debug build.
* Release/minified smoke build và launch.
* Android lint.

## 18. Phase 1 done when

* Phase 0 vẫn pass.
* Authorization flow hoạt động.
* Phone/code login hoạt động.
* 2FA hoạt động khi tài khoản test yêu cầu.
* Session restore hoạt động.
* Saved Messages được xác định đúng.
* File/media gần nhất được hiển thị.
* Image download và preview hoạt động.
* Video download và local playback hoạt động.
* Không duplicate client.
* Không duplicate transfer.
* Không leak player.
* Không lộ credential.
* Backup policy vẫn đúng.
* Test, lint, debug build và release smoke pass.
* Không triển khai ngoài scope Phase 1.

---

# PHASE 2 — MVP COMPLETE

## 19. Mục tiêu

Mở rộng PoC thành ứng dụng sử dụng hàng ngày:

Login
→ chọn Saved Messages hoặc source
→ duyệt file theo trang
→ tải file
→ preview
→ mở file ngoài ứng dụng khi cần
→ logout/reset.

## 20. Phạm vi

### 20.1. Source browser

* Saved Messages luôn có thể truy cập.
* Danh sách channel/chat phù hợp.
* Hiển thị source metadata cơ bản.
* Không gọi mọi source là folder.
* Không quét toàn bộ lịch sử để tính media count nếu không cần.

### 20.2. Paging

* Tải lịch sử theo trang.
* Không trùng message.
* Không mất item.
* Giữ đúng thứ tự.
* Dừng khi hết history.
* Xử lý message bị xóa hoặc file unavailable.
* Không full scan.
* Stable identity trong phạm vi source/account.

### 20.3. File browser

Mỗi item thể hiện khi có dữ liệu:

* Thumbnail hoặc icon.
* File name.
* File type.
* Size.
* Date.
* Duration.
* Local/remote state.
* Transfer progress.
* Error/unavailable state.

### 20.4. Download coordinator

* Start.
* Progress.
* Complete.
* Failure.
* Cancel.
* Retry.
* Deduplicate.
* Concurrency được giới hạn phù hợp.
* Local file được kiểm tra trước reuse.
* Không leak observer.
* Không tải lại file hợp lệ.
* Tuân thủ đầy đủ transfer state contract.

### 20.5. Preview matrix

#### Image

* Full-screen.
* Zoom/pan.
* Loading.
* Error.
* Memory-safe behavior.

#### Video

* Local playback.
* Play/pause/seek.
* Full-screen.
* Lifecycle ổn định.

#### Audio và voice

* Play.
* Pause.
* Seek.
* Duration.
* Lifecycle phù hợp.

#### Animation

* Preview theo MIME/format được hỗ trợ.

#### PDF

* Render theo trang.
* Lazy loading.
* Không load toàn bộ document vào memory.
* Không yêu cầu annotation hoặc editing.

#### Text

* Preview có giới hạn dung lượng.
* Không đọc file quá lớn trực tiếp vào memory.

#### Unsupported file

* Hiển thị metadata.
* Mở bằng ứng dụng bên ngoài qua secure temporary content sharing.
* Có fallback khi không có ứng dụng hỗ trợ.

### 20.6. Cache và persistence

DataStore hoặc cơ chế tương đương dùng cho preference phù hợp.

Room chỉ được dùng cho dữ liệu riêng của app khi có nhu cầu rõ ràng, ví dụ:

* Playback position.
* Recent preview.
* Pinned source.
* App-owned metadata.

Không xây full Telegram media index trong MVP.

### 20.7. Offline và error behavior

* Cached/local data vẫn có thể sử dụng khi phù hợp.
* Offline state được thể hiện rõ.
* Read retry tuân thủ policy chung.
* Download retry theo user intent.
* Không auto-retry auth/logout.
* Không hiển thị raw TDLib object hoặc stack trace.
* File unavailable giữ metadata nếu vẫn có giá trị.
* Không retry loop vô hạn.

### 20.8. Logout/reset

MVP chỉ hỗ trợ một hành vi:

* Kết thúc Telegram session.
* Dừng player.
* Dừng hoặc hủy transfer.
* Thực hiện TDLib logout theo lifecycle hợp lệ.
* Đóng resource/database cần thiết.
* Xóa toàn bộ account-related application data.
* Xóa encryption material theo policy.
* Xóa transfer identity của account.
* Quay về trạng thái khởi tạo sạch.

Không hỗ trợ:

* Partial logout.
* Giữ account cache.
* Giữ downloaded working files.
* Giữ một phần session data.

## 21. Phase 2 validation

* Repository tests.
* Paging tests.
* Transfer coordinator tests.
* Cache tests.
* Logout/reset tests.
* Preview routing tests.
* Source list runtime.
* Paging với dataset lớn.
* Image preview.
* Video preview.
* Audio preview.
* PDF preview.
* External file open.
* Missing external app fallback.
* Multiple download.
* Cancel và retry.
* Offline behavior.
* Session restore.
* Logout cleanup.
* Backup regression.
* Android lint.
* Debug build.
* Emulator standard.
* Emulator màn hình nhỏ.
* Ít nhất một thiết bị thật.

## 22. Phase 2 done when

* Saved Messages và source browser hoạt động.
* Paging ổn định.
* Không trùng hoặc mất file.
* Download coordinator ổn định.
* Image/video/audio/PDF preview hoạt động.
* Unsupported file mở ngoài app an toàn.
* Offline/error state rõ ràng.
* Session restore ổn định.
* Logout/reset xóa đúng dữ liệu.
* Không lộ credential.
* Không ANR hoặc OOM trong test dataset.
* Test, lint và build pass.
* Không triển khai Phase 4 feature.

---

# PHASE 3 — SAVED MESSAGES LOCAL MEDIA GALLERY VÀ TDLIB PROGRESSIVE VIDEO STREAMING

## 23. Mục tiêu và ranh giới

Phase 3 là feature/architecture mới kế thừa Phase 2, không thay thế hoặc âm thầm loại
bỏ source browser, chat/channel browsing, document/audio/PDF preview, secure sharing,
transfer, login/session restore hay logout/reset hiện có.

* Chỉ index ảnh và video từ Saved Messages.
* TDLib/Telegram là nguồn dữ liệu gốc; Room là derived local source cho gallery, search,
  filter, sort và paging.
* Không thay đổi hoặc xóa message trên Telegram.
* Dữ liệu và cache phải được cô lập theo account identity và database generation.

## 24. Persistence và synchronization

Room phải có migration không phá dữ liệu người dùng với các nhóm dữ liệu tương đương:
`saved_media` cho message ảnh/video, `cached_file` cho file vật lý dùng chung và
`sync_state` cho phase/cursor/watermark/checkpoint/error. `saved_media` lưu caption,
stable display name, MIME, kích thước, duration, Telegram file ID, stable remote file
identity và thumbnail/minithumbnail metadata. `cached_file` lưu identity account,
stable remote identity, TDLib file ID hiện tại, path quan sát được, loại thumbnail/original/
partial, size, access time và trạng thái none/partial/complete.

Flow bắt buộc là đăng ký update listener → chốt một head watermark → backfill giảm dần
theo page và checkpoint sau commit → listener UPSERT song song → catch-up từ watermark
đến head hiện tại → chỉ COMPLETED sau catch-up thành công. Phải resume được sau crash,
idempotent khi backfill/listener/catch-up gặp cùng message, và xử lý new/edit/delete;
message bị edit thành loại khác phải bị loại khỏi gallery. Runtime state không được thay
thế persisted checkpoint. UI phải hiển thị phase, progress, partial-sync và retry/error.

## 25. Room-backed gallery và image lifecycle

Gallery đọc bằng Paging từ Room, không paging trực tiếp TDLib history. Gallery phải có
adaptive/staggered grid, placeholder/minithumbnail/thumbnail, lazy thumbnail request có
deduplication và concurrency limit, cache eviction/reload policy, group theo tháng,
search local theo filename/caption, filter all/image/video/local-file và sort mới/cũ.
Room change phải tự phản ánh; không load toàn bộ dataset vào memory.

Image viewer phải reconcile TDLib state, filesystem existence và readability/validity trước
khi mở; thiếu hoặc stale path thì tải lại qua TDLib, hiển thị loading/progress/error/retry,
và xóa cache ảnh không được xóa message hoặc metadata.

## 26. Progressive video streaming

Trước production player phải có feasibility spike trên tài khoản Telegram thật với video
đủ lớn để chứng minh `downloadFile(fileId, offset, limit, ...)`, partial byte reads,
`updateFile`, `downloadOffset` và `downloadedPrefixSize`, playback trước full download,
range download khi seek và resume. Nếu không chứng minh được, phải ghi request/response,
file state, DataSource/player failure và giới hạn stack; không tự đổi thành full-download.

Kiến trúc mục tiêu là `Media3/ExoPlayer → TdLibVideoDataSource →
VideoStreamingCoordinator → TDLib downloadFile(offset, limit) → partial local file`.
Partial file là cache tạm trên thiết bị, không phải phát trực tiếp không dùng bộ nhớ.
Không hỗ trợ adaptive bitrate, HLS hoặc DASH trong Phase 3. File complete hợp lệ được
phát local; file chưa complete phải buffer vùng liên tục cần thiết, phát trước khi tải
xong, prefetch theo nhu cầu và phân biệt playback/buffer progress. Stable file identity
chỉ có một coordinator/transfer; seek hủy/chuyển request an toàn; mất mạng chuyển sang
buffering/error có retry; đóng player release Media3, cancel transfer không cần thiết và
dọn partial/full video bằng lifecycle/API TDLib phù hợp. Không thêm Save offline.

## 27. Account isolation, cache và bảo toàn Phase 2

Startup reconcile phải đồng thời dựa trên TDLib state, filesystem existence và file
readability/validity; không coi enum Room là sự thật tuyệt đối. Phải xử lý shared-file
deduplication, cache eviction, Android cache eviction, late update, reset/logout trong
khi transfer/player đang chạy, và không cho callback generation cũ ghi lại sau reset.
Logout xóa scanner, transfer/player, metadata và cache đúng account policy; Auto Backup
không được restore nhầm Room/cache/session/download.

Mọi refactor Phase 2 phải giữ backward compatibility và regression tests cho source
browser, document/audio/PDF/video preview, secure sharing, existing paging/transfer,
fake runtime, login/session restore và logout/reset.

## 28. Phase 3 validation và acceptance

Phase 3 phải có unit tests cho mapping/identity/classification/idempotency/cursor/watermark/
catch-up/crash-resume/update/cache/Room query/grouping/transfer races và account isolation;
fake dataset hàng nghìn item nhiều tháng/năm với duplicate/edit/delete/cancel/late update;
emulator/device evidence cho sync/gallery/thumbnail/image/video/seek/network/logout/regression;
và real-account evidence cho Saved Messages, full scan, incremental updates, ảnh, video
Telegram, document-video, video lớn, progressive start-before-complete, seek, storage và
logout. Test async phải có termination condition rõ ràng.

## 29. Phase 3 done when

Room schema/migration/isolation, full backfill/checkpoint/watermark/catch-up, incremental
new/edit/delete, Room Paging gallery, local search/filter/sort/month grouping, lazy thumbnail
cache, reconciled image viewer, progressive video start-before-complete/seek/recovery,
player cleanup/cache policy, logout isolation, Phase 2 regression, unit tests/lint/build,
fake runtime, emulator/device verification, real-account verification, documentation,
evidence và APK SHA-256 đều đạt. Nếu một mục chưa có bằng chứng thì Phase 3 chưa hoàn tất.

---

# PHASE 4 — HARDENING VÀ RELEASE-READY THỬ NGHIỆM

## 30. Mục tiêu

Ổn định MVP và Phase 3, không mở rộng tính năng lớn. Các acceptance criteria hardening
trước đây đặt ở Phase 3 được chuyển nguyên vẹn sang Phase 4:

* Lifecycle, concurrency, security, performance, release build, device coverage, cleanup và regression.
* Background/foreground, recreation, process kill/restore, logout khi preview/download,
  mất mạng, session hết hạn, TDLib close bất ngờ, player lifecycle, late update và reset.
* Release/minified, R8/JNI smoke, backup/security checklist, account cleanup, content
  sharing, dependency provenance và không có credential trong history.
* Reference setup, baseline/budgets, startup/paging/memory/ANR/OOM và device matrix.

## 31. Phase 4 done when

Lifecycle matrix pass, không duplicate client hoặc leak player/observer, không ANR/OOM,
release/minified build và R8/JNI smoke pass, security/cleanup pass, budgets được đáp ứng
và ứng dụng ổn định trên device matrix đã định nghĩa.

---

# PHASE 5 / BACKLOG — ADVANCED FEATURES

## 32. Nguyên tắc

Phase 5 chỉ bắt đầu khi Phase 4 có bằng chứng nhu cầu; mỗi feature có spec và estimate
riêng. Các candidate còn lại là global gallery xuyên tài khoản, full media index ngoài
Saved Messages, search toàn cục, offline metadata index, background transfer, Picture-in-
Picture, background audio, persistent export và multi-account.

Không đưa Phase 5 feature ngược vào Phase 0–4 nếu chưa cập nhật scope và estimate.

---

## 33. Workflow với Codex và AI agent

### 33.1. Nguyên tắc session

* Một session cho một kết quả rõ ràng.
* Dùng `/goal` cho một phase hoặc milestone dài.
* Dùng planning trước các task nhiều module hoặc rủi ro cao.
* Không dùng một chat duy nhất cho toàn bộ project.

### 33.2. Prompt contract

Mỗi task phải có:

* Goal.
* Context.
* Constraints.
* Done when.

### 33.3. Task workflow

1. Đọc `AGENTS.md`.
2. Đọc spec của phase/task.
3. Khảo sát file liên quan.
4. Phân biệt dữ kiện, giả thuyết và phần chưa xác minh.
5. Lập kế hoạch khi cần.
6. Triển khai trong phạm vi.
7. Viết hoặc cập nhật test.
8. Chạy validation.
9. Kiểm tra runtime khi cần.
10. Review toàn bộ diff.
11. Review regression, lifecycle, concurrency, security và test gap.
12. Sửa finding hợp lệ.
13. Chạy lại validation.
14. Báo cáo kết quả và rủi ro còn lại.

### 33.4. `AGENTS.md`

Phải chứa các quy tắc dài hạn:

* Architecture boundaries.
* Dependency direction.
* Security rules.
* Build/test/lint commands.
* Scope limits theo phase.
* Không chỉnh generated file.
* Không thêm dependency không có justification.
* Không gọi TDLib từ UI.
* Không log credential.
* Review diff trước khi kết thúc.
* Báo cáo validation không chạy được.
* Không tuyên bố hoàn thành nếu chưa có evidence.

---

## 34. Deliverable chung

Project phải duy trì:

* Master plan này.
* `AGENTS.md`.
* Architecture documentation.
* Known-good toolchain matrix.
* Environment report.
* TDLib provenance/build documentation.
* Security và backup policy.
* Phase progress document.
* Phase report.
* Runtime evidence.
* Validation result.
* Risk và unresolved blocker list.

Tài liệu chỉ mô tả spec, decision và evidence; không trở thành hướng dẫn code dài dòng.

---

## 35. Estimate cuối cùng

| Phạm vi                             |               Estimate |
| ----------------------------------- | ---------------------: |
| Phase 0–2: MVP                      |    **25–44 ngày công** |
| Phase 0–4: release-ready thử nghiệm |    **31–56 ngày công** |
| Phase 5 / Backlog                   | Spec và estimate riêng |

Giả định:

* Một developer có kinh nghiệm Kotlin và Jetpack Compose.
* Estimate bao gồm test, debug, validation và iteration.
* Không bao gồm thời gian chờ OTP hoặc thao tác thủ công.
* Không bao gồm blocker toolchain kéo dài ngoài phạm vi kiểm soát.
* TDLib reproducible build được giải quyết trong risk range của Phase 0.
* Không thay đổi product direction hoặc feature scope giữa chừng.

Nếu người triển khai chưa có kinh nghiệm TDLib/JNI, thêm contingency khoảng 15–25%.

Estimate phải được xem xét lại nếu thay đổi:

* Android support matrix.
* Distribution mode.
* Feature scope.
* Phase ownership.
* Storage/export behavior.
* TDLib build policy.
* Multi-account requirement.

---

## 36. MVP completion criteria

MVP chỉ hoàn thành khi:

* Android application độc lập hoạt động.
* TDLib provenance và lifecycle hợp lệ.
* Login Telegram hoạt động.
* 2FA hoạt động khi cần.
* Session restore hoạt động.
* Saved Messages hoạt động.
* Source browser hoạt động.
* Paging ổn định.
* Download progress/cancel/retry hoạt động.
* Transfer deduplication hoạt động.
* Image preview hoạt động.
* Video preview hoạt động.
* Audio preview hoạt động.
* PDF preview hoạt động.
* Unsupported file mở ngoài app an toàn.
* Offline/error behavior rõ ràng.
* Logout/reset xóa toàn bộ account-related data.
* Backup policy bảo vệ dữ liệu.
* Không lộ credential.
* Không ANR hoặc OOM trong test profile.
* Unit test, runtime validation, lint và build pass.
* Debug build, test và lint pass.
* Documentation đầy đủ.
* Không có Phase 3–5 feature bị triển khai trong MVP ngoài scope.

---

## 37. Final consistency rules

Bản kế hoạch và các spec con phải luôn bảo đảm:

* Mỗi requirement chỉ có một nguồn sự thật.
* Không có feature thuộc hai phase khác nhau.
* Estimate chỉ xuất hiện theo range đã chốt.
* Không có acceptance criterion không thể kiểm chứng.
* Không trình bày implementation preference như architecture requirement.
* Không đưa quyết định mới vào mục “đã thống nhất” nếu chưa có evidence.
* Không mở rộng personal/internal app thành enterprise system không cần thiết.
* Không đánh đổi security hoặc data integrity để giảm thời gian triển khai.
