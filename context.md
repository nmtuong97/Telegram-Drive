````markdown
# CONTEXT NÉN — TELEGRAM-DRIVE / ONEDRIVE MIGRATION MVP

## 1. Vai trò và cách phối hợp

- Người dùng đang phát triển fork `nmtuong97/Telegram-Drive`, upstream ban đầu là `caamer20`.
- Quy trình sử dụng GitHub Spec Kit.
- Người dùng chạy prompt trong AI coding agent rồi gửi lại `COORDINATION REPORT`.
- Trợ lý đóng vai trò điều phối:
  - kiểm tra report;
  - quyết định gate;
  - đưa đúng một lệnh/prompt tiếp theo;
  - không cho implement trước khi spec/plan/tasks/analyze sạch.
- Ngôn ngữ làm việc: tiếng Việt.
- Không chạy `/speckit.converge` trước khi implementation hoàn tất.
- Trình tự mong muốn:
  `specify → clarify → plan → tasks → analyze → checkpoint → implement từng phase → converge`.

---

## 2. Codebase Telegram-Drive đã biết

### Stack

- Frontend:
  - React 19;
  - TypeScript;
  - Tailwind;
  - Vite.
- Desktop backend:
  - Tauri 2;
  - Rust;
  - Tokio.
- Telegram:
  - Grammers/MTProto.
- HTTP:
  - Reqwest.
- Persistence:
  - SQLite.
- Desktop app hiện tại chưa có OneDrive migration production code.

### Các symbol/path quan trọng đã audit

- `TelegramState`:
  - `app/src-tauri/src/commands/mod.rs`
  - giữ Telegram client, peer cache và transfer cancellation state.
- Upload command:
  - `cmd_upload_file`;
  - `cmd_upload_file_inner`;
  - trong `app/src-tauri/src/commands/fs.rs`.
- Upload hiện tại:
  - stream từ local path;
  - emit progress;
  - trả chuỗi success tĩnh;
  - chưa có upload result chuẩn;
  - message ID chưa được chứng minh chắc chắn là lấy được dễ dàng.
- FLOOD_WAIT:
  - hiện được parse và sleep bên trong upload flow;
  - chưa persist cooldown.
- `BandwidthManager`:
  - tồn tại trong `app/src-tauri/src/bandwidth.rs`;
  - có thể reuse nếu cần.
- SQLite hiện tại:
  - `Arc<Mutex<sqlite::Connection>>`;
  - database đang dùng cho chức năng khác;
  - chưa có schema migration versioning đầy đủ.
- Frontend:
  - không có router phức tạp;
  - `DesktopDashboard` và `Sidebar` điều khiển view;
  - `sonner` dùng toast;
  - `tauri-plugin-dialog` đã có native folder picker;
  - invoke/listen dùng trực tiếp từ Tauri API;
  - i18n có `vi.json`, `en.json`.
- Chưa có frontend test framework rõ ràng.
- Rust có thể dùng `#[cfg(test)]`; baseline trước đây có rất ít hoặc không có test feature-level.
- Không có secure credential storage rõ ràng trong codebase.
- Không có system tray/autostart/single-instance flow hoàn chỉnh cho feature này.
- MVP hiện đã loại các yêu cầu tray/autostart.

---

## 3. Feature cũ đã bị loại bỏ

Feature cũ:

`specs/001-onedrive-continuous-migration`

Feature này từng có phạm vi rất lớn:

- continuous monitoring;
- crash-safe effectively-once;
- reconciliation;
- local backup;
- source deletion;
- tray/autostart;
- adaptive flood guard;
- nhiều state và hơn 150 tasks.

Nó đã trải qua nhiều vòng Analyze nhưng liên tục phát sinh:

- duplicate task IDs;
- suffix IDs;
- sai phase count;
- contract mismatch;
- state-machine gap;
- quickstart reference sai;
- constitution tension;
- unsupported assumptions;
- complexity quá lớn.

Người dùng quyết định không archive vì đang ở giai đoạn phát triển và cho phép xóa trực tiếp.

Feature cũ cùng `.specify/feature.json` cũ đã được xóa.

---

## 4. Feature MVP mới

Active feature hiện tại:

`specs/001-onedrive-migration`

Các file đã có:

- `spec.md`
- `checklists/requirements.md`
- `plan.md`
- `research.md`
- `data-model.md`
- `quickstart.md`
- `contracts/ipc-contracts.md`

Chưa có:

- `tasks.md`
- production implementation.

Working tree dự kiến vẫn có:

- `.specify/feature.json` modified;
- feature cũ deleted;
- feature mới untracked/modified;
- chưa commit.

---

## 5. Product scope MVP đã chốt

### Mục tiêu

Tích hợp một tính năng gốc tên:

`OneDrive Migration`

vào Telegram-Drive để:

1. Kết nối Microsoft.
2. Chọn một thư mục OneDrive.
3. Chọn Telegram destination.
4. Chọn local working directory.
5. Scan recursive một snapshot.
6. Hiển thị danh sách thư mục/file và tổng dung lượng.
7. Migrate tuần tự từng file.
8. Theo dõi tiến trình.
9. Skip file trùng nội dung.
10. Pause/Resume/Cancel.
11. Retry file lỗi.
12. Resume thủ công sau restart.

### Ưu tiên

- chạy được;
- ổn định;
- ít thay đổi code hiện tại;
- reuse Telegram upload hiện có;
- dễ kiểm thử;
- hoàn thành nhanh.

### Có trong MVP

- Một Microsoft account active.
- Một source folder cho mỗi job.
- Một Telegram destination cho mỗi job.
- Một local working directory cho mỗi job.
- Recursive snapshot scan.
- Folder list, file list, totals.
- Một active job tại một thời điểm.
- Một file tại một thời điểm.
- Download local rồi upload Telegram.
- Persistent job/item progress.
- Manual Resume sau restart.
- Duplicate detection theo content.
- Basic retry tối đa ba lần cho lỗi tạm thời.
- Basic Telegram cooldown.
- Pause after current file.
- Cancel tại ranh giới file an toàn.
- Temp cleanup.
- Desktop UI tích hợp navigation hiện tại.

### Không có trong MVP

- Xóa hoặc sửa source OneDrive.
- OneDrive Recycle Bin.
- Continuous monitoring.
- Delta polling/cursor.
- Tự thêm file mới sau khi job đã bắt đầu.
- Local backup lâu dài.
- System tray.
- Close-to-tray.
- Autostart.
- OS service.
- Sidecar.
- Nhiều active jobs.
- Download/upload song song.
- Telegram reconciliation.
- Exactly-once guarantee.
- Quarantine workflow.
- Adaptive flood safety nhiều cấp.
- External volume identity/auto-remount.
- Advanced analytics.
- Event sourcing.
- Worker lease/heartbeat.
- Mobile.
- SharePoint/Teams.

---

## 6. User Stories và specification hiện tại

Specification sau clarification có:

- 3 User Stories;
- 25 Functional Requirements;
- 7 Non-Functional Requirements;
- 10 Success Criteria;
- 14 Edge Cases;
- khoảng 25 acceptance scenarios;
- 0 `[NEEDS CLARIFICATION]`.

### User Stories

1. P1 — Thiết lập và scan.
2. P1 — Chạy migration.
3. P2 — Resume, retry và duplicate.

### NFR tối thiểu

1. Không đọc toàn bộ file lớn vào RAM.
2. Một file migration tại một thời điểm.
3. Job, item, progress và duplicate history survive restart.
4. Completed/skipped duplicate không được tự xử lý lại.
5. Không log Microsoft token hoặc Telegram session/auth key.
6. Scan/download/upload không khóa UI thread.
7. Desktop-only.

---

## 7. Clarification decisions đã chốt

### Snapshot

- Job dùng snapshot tại thời điểm scan.
- Sau khi job bắt đầu:
  - không tự thêm file mới;
  - không tự thêm version mới;
  - muốn cập nhật phải scan lại trước khi chạy hoặc tạo job mới.

### Source changed

Nếu file thay đổi sau scan:

- không upload version không khớp;
- item thành `failed`;
- error code `source_changed`;
- người dùng scan lại để lấy version mới.

### Duplicate

- Duplicate xác định theo nội dung.
- File đầu tiên upload thành công được ghi vào duplicate history.
- File sau trùng fingerprint được `skipped_duplicate`.
- Duplicate history dùng giữa các jobs.
- File failed không được ghi vào history.
- Hai file cùng tên nhưng nội dung khác không trùng.
- Hai file khác tên/path nhưng cùng nội dung là duplicate.

### Duplicate trước/sau download

- Nếu provider fingerprint đã có và khớp history:
  - skip trước download.
- Nếu không:
  - download;
  - tính SHA-256;
  - check history;
  - nếu trùng thì skip upload và xóa temp.

### Upload success

- MVP coi upload thành công khi upload core trả success.
- Message ID là optional.
- Không mở rộng reconciliation để bảo đảm exactly-once.
- Chỉ ghi duplicate history sau khi upload success đã được persist.

### Pause

- `pause after current file`.
- Không pause giữa download/upload.

### Cancel

- Không bắt đầu file mới.
- Giữ completed history.
- Không xóa Telegram file.
- Không thay đổi OneDrive source.
- Chuyển job sang `cancelled`.

### Restart

- Persist job và item.
- `completed` và `skipped_duplicate` giữ nguyên.
- `downloading`/`uploading` được reset về retryable/pending.
- Không auto-start.
- Người dùng nhấn Resume.
- Crash trong upload có thể dẫn đến upload lại.
- MVP là `at-least-once attempt`, không exactly-once.

### Retry

- Auto retry tối đa ba lần cho lỗi tạm thời.
- Sau đó file `failed`, job tiếp tục.
- Manual retry reset attempt của thao tác mới.
- Không retry vô hạn.

### Telegram cooldown

- Tôn trọng thời gian chờ.
- Không bypass.
- Persist `cooldown_until`.
- Không bắt đầu upload mới trước expiry.
- Có thể tự tiếp tục nếu job vẫn running.
- Không có adaptive safety levels.

### Local working directory

- Một working directory cho mỗi job.
- Chọn bằng native folder picker.
- Nếu không tồn tại, không writable, bị tháo hoặc thiếu dung lượng:
  - không bắt đầu file mới;
  - hiển thị lỗi;
  - cho chọn lại;
  - không fallback tự động.

### Local temp

- Upload thành công + persist completed → xóa temp.
- Duplicate sau download + persist skip → xóa temp.
- Lỗi có thể giữ file để retry nếu hợp lệ.
- Không local backup.

### Một active job

- Có thể lưu nhiều history jobs.
- Chỉ một job `running`.

### Folder hierarchy

- Không tái tạo tree OneDrive trên Telegram.
- Tất cả file vào destination đã chọn.
- Giữ `source relative path` trong history.

---

## 8. Plan đã tạo và các quyết định ban đầu

Plan/research/data-model/contracts/quickstart đã được sinh từ codebase audit.

Plan ban đầu chọn:

- In-process Tokio worker trong Tauri backend.
- Một active job.
- Sequential pipeline.
- SQLite riêng `migration.db`.
- Một page React.
- Khoảng 15 Tauri commands.
- Tối đa 5 events.
- Extract shared Telegram upload seam.
- Microsoft Graph OAuth + recursive listing/download.
- Duplicate provider fingerprint + SHA-256.
- Accepted crash window sau Telegram success trước local COMMIT.
- 5 implementation phases.

Tuy nhiên Report 09 chưa được chấp nhận do còn blocker.

---

## 9. Blocker hiện tại của Plan

### Blocker 1 — OneDrive hash sai

Plan/research đã dùng:

`file.hashes.sha256Hash`

như provider fingerprint.

Điều này không được dùng làm assumption canonical.

Quyết định sửa:

- Provider fingerprint ưu tiên:
  - `quickXorHash`, type `onedrive_quickxor`;
  - `sha1Hash` chỉ nếu metadata thực sự trả về, type riêng.
- Local canonical hash:
  - luôn tính SHA-256 trong lúc download.
- Không so sánh hash khác thuật toán.
- Duplicate key luôn gồm:
  - fingerprint type;
  - exact value;
  - file size.

### Blocker 2 — Hardcoded Telegram limit

Plan có chỗ dùng mốc 2 GB.

Phải xóa mọi active decision dùng:

- 2 GB;
- 4 GB;
- hoặc số Telegram limit cố định.

Canonical:

1. Dùng runtime/config limit nếu codebase có nguồn đáng tin cậy.
2. Nếu không có, để upload adapter trả:
   `telegram_file_too_large`.
3. Không auto retry lỗi này.
4. Không pre-reject bằng số hardcoded.

### Blocker 3 — OAuth redirect tự phát minh port range

Plan dùng random port `18000–18999`.

Phải sửa thành:

- Authorization Code + PKCE.
- System browser.
- Public-client app registration.
- Redirect URI lấy từ app configuration.
- Loopback redirect đã đăng ký, ví dụ `http://localhost`.
- Nếu dùng dynamic port, phải có evidence chính thức.
- Không bind `0.0.0.0`.
- Có `state`, verifier/challenge, timeout.
- Không log code/token.

### Blocker 4 — Token persistence không an toàn

Plan dùng bảng `microsoft_auth`, machine-derived key, thậm chí đề cập XOR.

MVP phải bỏ persistence token vào SQLite.

Canonical:

- Access/refresh token chỉ nằm trong Rust process memory.
- Sau app restart, người dùng có thể phải Connect Microsoft lại.
- Job/snapshot/progress vẫn persist.
- Sau reconnect, người dùng nhấn Resume.
- Frontend không nhận refresh token.
- Không log token.

Data model còn tối đa ba bảng:

1. `migration_jobs`
2. `migration_items`
3. `migrated_fingerprints`

### Blocker 5 — Constitution tension

Constitution 1.1.0 yêu cầu tray/autostart cho background processing, trong khi MVP loại các tính năng này.

Cần amendment MINOR:

`1.1.0 → 1.2.0`

Semantic mới:

- Long-running work vẫn phải chạy trong Rust backend.
- React không sở hữu worker.
- Persistent checkpoints khi spec yêu cầu.
- Tauri IPC vẫn là boundary.
- Tray/close-to-tray/autostart/single-instance chỉ bắt buộc nếu feature cam kết unattended operation khi đóng cửa sổ hoặc sau OS login.
- Feature chỉ chạy khi Tauri process mở có thể dùng manual Resume và không cần tray/autostart.

Sau amendment:

- Plan constitution compliance phải PASS.
- Không còn `TENSION`.
- Không có “accepted violation”.

### Blocker 6 — Architecture quá lớn

Report 09 dự kiến:

- 4 tables;
- 11 Rust modules;
- 6 frontend supporting components.

Cần thu gọn.

Rust target tối đa 7 modules:

```text
migration/
├── mod.rs
├── models.rs
├── db.rs
├── microsoft.rs
├── worker.rs
├── upload_adapter.rs
└── commands.rs
````

Frontend:

* 1 page;
* tối đa khoảng 4 supporting components.

Có thể gộp:

* Folder summary vào setup/summary;
* Controls vào current progress.

### Blocker 7 — Upload seam/FLOOD_WAIT

Phải phân biệt:

#### Shared upload core

* Nhận raw dependencies.
* Không nhận Tauri `State<>`.
* Trả `UploadResult` hoặc `UploadError`.
* Không quyết định sleep/retry policy của caller.

#### Manual upload adapter

* Giữ retry/sleep policy hiện tại để tránh regression.

#### Migration adapter

* Nhận `FloodWait { seconds }`;
* persist `cooldown_until`;
* không upload mới trước expiry;
* emit cooldown;
* tự tiếp tục nếu job vẫn running.

Không cần global mutation scheduler trong MVP.

### Blocker 8 — Message ID

`telegram_message_id` phải nullable.

* Capture nếu exact Grammers return type tại call site cung cấp dễ dàng.
* Nếu không, upload success vẫn đủ.
* Không refactor lớn chỉ để lấy ID.
* Duplicate history không phụ thuộc message ID.

### Blocker 9 — Recovery mapping phải thống nhất

Canonical:

```text
pending            → pending
downloading        → pending + recovery_interrupted
uploading          → pending + recovery_interrupted
completed          → completed
skipped_duplicate  → skipped_duplicate
failed             → failed
```

* Không tăng attempt chỉ vì restart.
* Cleanup `.part` không hợp lệ.
* Không auto-start.
* Reconnect Microsoft nếu cần rồi Resume.

### Blocker 10 — Phases phải nằm trong plan.md

Report ngoài không đủ.

`plan.md` phải có section `Implementation Phases` với đúng 5 phase:

1. Foundation and seams.
2. Snapshot scan.
3. Sequential worker.
4. Tauri IPC and UI.
5. Tests and MVP hardening.

Mỗi phase có:

* Goal;
* code areas;
* dependencies;
* independent validation;
* exit criteria.

### Blocker 11 — Test doubles

Plan phải có strategy rõ:

#### Microsoft boundary

Fakeable interface cho:

* list folders;
* scan snapshot;
* get current metadata;
* download item.

#### Telegram boundary

Fakeable upload adapter.

#### Database tests

Temporary SQLite.

Không phụ thuộc manual test cho core business rules.

### Blocker 12 — Quickstart count

Quickstart phải đúng 7 scenario:

A. Connect, select and scan
B. Migrate normal files
C. Duplicate before download
D. Duplicate after download
E. Pause and Resume
F. Restart, reconnect Microsoft and manual Resume
G. Retry, cooldown and common errors

Phải ghi known limitation:

* Crash sau Telegram success nhưng trước local persistence có thể tạo upload lặp.

---

## 10. Data model canonical sau sửa

### `migration_jobs`

Tối thiểu:

* id;
* source drive/folder/path;
* Telegram destination;
* working directory;
* status;
* control request nullable;
* blocking error nullable;
* cooldown_until nullable;
* timestamps;
* totals/counters.

### `migration_items`

Tối thiểu:

* id;
* job_id;
* drive_item_id;
* original_name;
* relative_path;
* size;
* source eTag/version;
* provider fingerprint fields;
* computed SHA-256;
* status;
* attempt_count;
* last error;
* nullable Telegram message ID;
* deterministic temp filename;
* timestamps.

### `migrated_fingerprints`

* fingerprint_type;
* fingerprint_value;
* file_size;
* first job/item;
* Telegram destination;
* nullable message ID;
* completed_at.

Composite uniqueness:

`fingerprint_type + fingerprint_value + file_size`

Không có:

* `microsoft_auth`;
* folder table;
* event table;
* heartbeat;
* lease;
* backup/delete tables.

---

## 11. Success transaction canonical

Sau upload success:

```text
BEGIN
  mark item completed
  persist upload result
  insert provider fingerprint nếu có
  insert SHA-256 fingerprint
  update job counters
COMMIT
```

Chỉ xóa temp sau COMMIT thành công.

Nếu COMMIT fail:

* giữ temp nếu có thể;
* item chưa completed;
* không lưu fingerprint rời rạc.

Known limitation:

* Telegram upload thành công;
* app crash trước COMMIT;
* Resume có thể upload lại;
* chấp nhận cho MVP;
* không reconciliation.

---

## 12. Job và item states tối giản

### Job

* `draft`
* `ready`
* `running`
* `paused`
* `completed`
* `failed`
* `cancelled`

Dùng thêm field:

* `control_request`
* `cooldown_until`
* `blocking_error_code`

thay vì thêm nhiều state.

### Item

* `pending`
* `downloading`
* `uploading`
* `completed`
* `skipped_duplicate`
* `failed`

Các lỗi chi tiết nằm ở error code:

* `microsoft_auth_required`
* `source_folder_unavailable`
* `source_changed`
* `working_directory_unavailable`
* `insufficient_disk_space`
* `telegram_not_connected`
* `telegram_file_too_large`
* `telegram_cooldown`
* `download_failed`
* `upload_failed`
* `cancelled`
* `recovery_interrupted`
* `unknown`

---

## 13. Pipeline canonical

```text
select pending item
→ validate job/working directory/cooldown
→ provider-fingerprint duplicate check
→ validate source metadata against snapshot
→ disk check
→ stream download to deterministic .part
→ compute SHA-256 during stream
→ post-download SHA-256 duplicate check
→ upload through shared Telegram core
→ COMMIT item + fingerprints + counters
→ cleanup temp
→ check pause/cancel
→ next item
```

Không:

* prefetch;
* Range resume mặc định;
* parallel upload;
* source delete;
* reconciliation.

Download crash/restart:

* tải lại file hiện tại từ đầu;
* đây là lựa chọn MVP mặc định.

Disk policy:

`free space >= current file size + 100 MiB safety margin`

Đây là local app policy, không phải Telegram limit.

---

## 14. Command/event budget

### Commands

Tối đa khoảng 15.

Các nhóm tối thiểu:

* Microsoft connect/disconnect/status.
* List OneDrive folders.
* Create/scan/list/get job/items.
* Start/pause/resume/cancel.
* Retry item/retry failed.
* Update working directory.

Native folder picker có thể gọi trực tiếp frontend qua plugin.

Mỗi command contract cần:

* input;
* output;
* read/mutation;
* allowed states;
* structured errors;
* UI consumer.

### Events

Tối đa 5:

* job updated;
* item updated;
* download progress;
* upload progress;
* cooldown.

Mỗi event cần:

* producer;
* consumer;
* payload;
* throttling.

Không emit mỗi byte.

---

## 15. Frontend target

Một page:

`OneDriveMigrationPage`

Tối đa khoảng 4 supporting components, ví dụ:

* SetupAndSummary;
* CurrentProgressAndControls;
* FileTable;
* Destination/Source picker section.

UI gồm:

* Microsoft connect;
* source folder;
* Telegram destination;
* working directory;
* scan;
* start/pause/resume/cancel;
* retry failed;
* summary;
* current progress;
* file list.

Không advanced dashboard.

---

## 16. Testing target

### Unit

* State/control transitions.
* One active job.
* Retry max 3.
* Manual retry reset.
* Completed/duplicate not reselected.
* Provider fingerprint exact-type matching.
* Hash type mismatch not duplicate.
* Same content/different path duplicate.
* Same name/different content not duplicate.
* Post-download SHA-256 duplicate.
* Temp cleanup.
* Recovery mapping.

### Integration

* Recursive scan with pagination.
* Empty source.
* Snapshot totals.
* Source changed.
* Download stream `.part`.
* SHA-256 during stream.
* Duplicate before download.
* Duplicate after download.
* Upload success transaction.
* Cooldown gate.
* File too large no retry.
* Working directory unavailable.
* Retry failed.
* One active job.

### Frontend

Nếu chưa có framework:

* Type-check.
* Production build.
* Manual quickstart.

Không thêm frontend test framework chỉ cho MVP.

---

## 17. Trạng thái gate hiện tại

Report 09 tự tuyên bố PASS nhưng điều phối đã bác bỏ.

Gate hiện tại:

`FAIL — plan correction required`

Chưa được:

* chạy `/speckit.tasks`;
* chạy `/speckit.analyze`;
* commit;
* implement.

---

## 18. Lệnh tiếp theo đã được phát hành

Tên:

`Lệnh 10 — Sửa Plan MVP trước khi tạo Tasks`

Mục tiêu:

1. Amendment constitution 1.2.0.
2. Sửa QuickXorHash/SHA-256 strategy.
3. Xóa hardcoded Telegram limit.
4. Sửa OAuth redirect.
5. Bỏ token persistence khỏi SQLite.
6. Giảm còn 3 business tables.
7. Giảm Rust modules ≤ 7.
8. Giảm frontend supporting components ≤ 4.
9. Làm rõ upload seam/FLOOD_WAIT.
10. Để message ID nullable.
11. Chuẩn hóa recovery mapping.
12. Đưa 5 implementation phases trực tiếp vào `plan.md`.
13. Thêm fake Microsoft/Telegram và temporary DB strategy.
14. Thu gọn quickstart còn 7 scenarios.
15. Chạy scope scan và constitution re-check.
16. Không tạo tasks.

Report cần trả:

`COORDINATION REPORT 10 — MVP Plan Correction`

Gate chỉ PASS khi:

* constitution 1.2.0;
* không còn tension;
* provider hash đúng;
* OAuth/token handling an toàn;
* không hardcoded Telegram limit;
* 3 tables;
* ≤7 Rust modules;
* 1 page;
* ≤4 supporting components;
* ≤15 commands;
* ≤5 events;
* 5 phases;
* 7 quickstart scenarios;
* tasks.md chưa tồn tại;
* production source/dependencies/lockfiles không đổi.

---

## 19. Nguyên tắc điều phối tiếp theo

Sau khi nhận Report 10:

1. Kiểm tra không có sửa sau final verification.
2. Kiểm tra constitution amendment hợp lệ.
3. Kiểm tra research dùng official Microsoft evidence.
4. Kiểm tra plan và supporting artifacts nhất quán.
5. Nếu còn blocker:

   * phát hành một correction prompt nhỏ;
   * không chạy tasks.
6. Nếu PASS:

   * lệnh tiếp theo là `/speckit.tasks`;
   * task list phải nhỏ, khoảng 35–60 tasks tối đa;
   * task IDs numeric-only, contiguous;
   * 5 phase;
   * không mở rộng scope.
7. Sau tasks:

   * chạy `/speckit.analyze`;
   * chỉ sau Analyze sạch mới checkpoint;
   * rồi implement theo từng phase/PR nhỏ.
8. `/speckit.converge` chỉ sau khi implementation hoàn tất.

---

## 20. Invariants tuyệt đối của MVP

* Không xóa hoặc sửa OneDrive source.
* Không continuous monitoring.
* Không local backup.
* Chỉ một active job.
* Chỉ một file tại một thời điểm.
* File completed không được chọn lại.
* Duplicate history chỉ ghi sau upload success được persist.
* Provider hash khác loại không được so sánh.
* SHA-256 được tính trong download stream.
* Không hardcode Telegram file limit.
* Cooldown không bypass.
* Crash-after-upload-before-COMMIT có thể upload lặp và được chấp nhận.
* Không tuyên bố exactly-once.
* Token Microsoft không persist trong SQLite ở MVP.
* Sau restart có thể yêu cầu reconnect Microsoft rồi manual Resume.
* Worker chỉ chạy khi Tauri process đang mở.
* Không tray/autostart/sidecar/service.
* Không tasks hoặc implementation trước khi Plan Correction Gate PASS.

```
```
