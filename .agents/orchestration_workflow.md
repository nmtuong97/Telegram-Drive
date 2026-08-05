# Antigravity Orchestration Workflow

Tài liệu này là Canonical Orchestration Workflow dành cho Antigravity Agent trong dự án Telegram-Drive. Mọi Agent (đặc biệt khi chạy `/goal`) PHẢI đọc và tuân thủ các quy tắc trong tài liệu này TRƯỚC KHI thực hiện bất kỳ hành động nào.

## 1. Task Contract (Hợp đồng thực thi cho mọi Goal)

Trước khi hành động, Agent phải xác định rõ:
1. **Mục tiêu duy nhất:** Không thay đổi Master Plan, product requirement hoặc feature scope nếu không được yêu cầu.
2. **Acceptance criteria:** Các tiêu chí có thể kiểm chứng để xác nhận hoàn thành.
3. **In scope và Out of scope:** Xác định giới hạn hành động.
4. **Nguồn sự thật áp dụng:**
   - *Product requirement:* "MASTER PLAN — TELEGRAM DRIVE ANDROID" bản Final, active spec hợp lệ (không mâu thuẫn).
   - *Implementation:* Source code và diff tại HEAD hiện tại (sau khi kiểm tra GitNexus graph/index, Ripgrep search).
   - *Runtime:* Process, thread dump, exit code, test/device evidence của lần chạy hiện hành.
   - *Reasoning:* Sequential Thinking, search output chỉ là giả thuyết, không phải bằng chứng runtime. Mọi báo cáo phải tách biệt: Fact đã xác minh, Inference, Assumption, Unknown.
5. **Ownership:** File hoặc subsystem nào được phép sửa.
6. **Công cụ cần dùng và lý do (Tool Routing).**
7. **Công cụ KHÔNG cần dùng:** Không gọi công cụ chỉ để chứng minh nó đã được dùng.
8. **Mutation được phép:** Các thay đổi được ủy quyền.
9. **Concurrency invariants:** Đảm bảo không vi phạm quy tắc Gradle single-flight.
10. **Hard timeout và Stop policy:** Dừng khi gặp lỗi block hoặc timeout.
11. **Bằng chứng cần thu:** Test output, device evidence, ...
12. **Điều kiện cấm:** Không tuyên bố hoàn tất nếu thiếu bằng chứng.

## 2. Phân loại Task và Lựa chọn Công cụ (Tool Routing)

Tùy thuộc vào loại task, Agent PHẢI sử dụng công cụ ưu tiên tương ứng.

| Loại task | Công cụ ưu tiên |
| --- | --- |
| Antigravity capability/config | `antigravity-guide` + live help/config |
| Exact file/symbol/call-site inventory | Ripgrep MCP hoặc native `rg` |
| Architecture, dependency, flow, blast radius | GitNexus (sau freshness check) |
| Đọc source có chọn lọc, quản lý context | LeanCTX |
| Giả thuyết phức tạp, phản chứng | Sequential Thinking MCP |
| Android official knowledge | Android CLI docs (`android docs search/fetch`) |
| APK/device/UI evidence | Android CLI (sau khi build Gradle thành công) |
| Spec lifecycle | Spec Kit (khi active feature hợp lệ) |
| Process/thread/test/build evidence | Raw shell, tuần tự, hard timeout |
| Product requirement | Master Plan Final |

### 2.1 `/goal` và `/grill-me`
- `/goal` là một quá trình thực thi liên tục đến khi hoàn tất, không phải chat chung chung. Nếu có quyết định sản phẩm/kiến trúc cần người dùng quyết định, hãy đề xuất `/grill-me` hoặc hỏi rõ ràng trước khi chạy `/goal`.
- Nếu gặp ambiguity không thể giải quyết trong quá trình chạy `/goal`: DỪNG AN TOÀN, báo `BLOCKED` và hỏi người dùng. Không tự chọn phương án làm thay đổi scope.

### 2.2 `antigravity-guide` (Skill)
- Dùng để kiểm tra khả năng, slash command, cấu hình của Antigravity.
- Không dùng nó để ghi đè User instruction, `AGENTS.md`, Master Plan, hay source evidence.
- Khi một skill được yêu cầu, phải dùng Progressive Disclosure (đọc `SKILL.md` của skill đó trước khi hành động).

### 2.3 Android CLI (`android`)
- **Dùng cho:** `android docs search/fetch`, `android describe` (tìm metadata), deploy APK (`android run --apks=...`), và chụp UI/layout (`android layout`, `android screen capture`).
- **Không dùng cho:** Chạy build. Build phải dùng Gradle wrapper (`./gradlew` hoặc `run-gradle-single-flight.sh`).
- Không mô tả `android run` là lệnh build.
- Chỉ chạy device journey nếu thực sự có sửa đổi production/runtime/UI code và cần device evidence.

### 2.4 Spec Kit
- Workflow: `constitution` → `specify` → `clarify` → `plan` → `tasks` → `analyze` → `implement`. Tên command live (skill names) ưu tiên hơn ví dụ.
- Yêu cầu kiểm tra active feature, constitution, và artifacts có đúng project Android không. Nếu sai (stale/sai project): KHÔNG chạy mutation như `speckit-implement`, không tự sửa artifact trong một goal ngoài phạm vi. Báo blocker/finding.
- Plan do agent tạo phải ở mức spec, không nhồi nhét code detail trừ khi được yêu cầu.

### 2.5 LeanCTX
- Dùng cho repository overview, context management, decision record, đọc file có chọn lọc.
- **Không** coi LeanCTX memory là bằng chứng runtime.
- Đối với raw output (thread dump, process inventory, exit code, test failure), phải dùng raw mode của shell/tool (ví dụ tắt compression/thêm `raw=true` nếu integration hỗ trợ) để có nội dung nguyên văn.

### 2.6 Ripgrep MCP
- Trước hết, xác định live schema.
- **Quy trình:** filenames-only → targeted path/glob → fixed-string search → đọc file đầy đủ → đối chiếu source/diff.
- **Guardrails:** Khóa ở repo root, thu hẹp glob (ví dụ `*.kt`), dùng fixed string khi có thể. Không tìm toàn máy, không dùng kết quả search để chứng minh runtime behavior. Không dùng thay shell native (ví dụ `pgrep`, `jcmd`).

### 2.7 Sequential Thinking MCP
- Dùng ở các decision gate: sau baseline (lập giả thuyết), sau evidence mới, và trước completion.
- Decision record nên theo dạng bảng: `| Hypothesis | Evidence for | Evidence against | Next discriminating check | Status |`
- Không được viết Sequential Thinking "xác nhận" nguyên nhân (nó chỉ là framework suy luận).
- Không xuất raw thought history trong report. Chỉ xuất decision record. Fallback: bảng thủ công nếu MCP hỏng.

### 2.8 GitNexus
- **Preflight:** Lấy tên tool từ live MCP registry. Gọi `list_repos` và chọn đúng `Telegram-Drive`. Kiểm tra index freshness (staleness, PDG). Không tự refresh index.
- **Ưu tiên:** `query` → `context/trace` → Ripgrep inventory → Đọc source → `impact` (TRƯỚC KHI EDIT) → minimal edit → `detect_changes` (SAU KHI EDIT) → targeted validation.
- Tool có điều kiện (`cypher`, `pdg_query`, `explain`, `check`, `rename`, `route_map`...): Chỉ dùng khi thực sự cần.
- **Không dùng GitNexus để kết luận** trạng thái runtime (Gradle đã thoát, test pass, coroutine leak...).
- Nếu index lock/busy, không retry liên tục. Fallback về Ripgrep + Source.

## 3. Android/Gradle Execution Invariants

Quy tắc BẮT BUỘC cho mọi task liên quan Gradle:
1. **Chỉ một Gradle invocation** cho `android-app` tại một thời điểm. Không retry nếu tiến trình cũ còn sống.
2. **Không chạy song song** bản `--info` và không `--info`. Không để subagent chạy Gradle độc lập.
3. Test, lint, assemble phải chạy **tuần tự**. Mỗi lệnh phải có **hard timeout**.
4. Trước khi chạy, kiểm kê process thật (raw shell, `jcmd`, `pgrep`). Không suy ra số process từ UI.
5. Nếu process treo, lấy thread dump TRƯỚC KHI terminate. Phân biệt rõ: cache lock, worker wait, coroutine wait, deadlock.
6. Việc dọn dẹp: Cancel task → `TERM` → `KILL` (chỉ khi cần).
7. **KHÔNG chạy `clean`**, không xóa `.gradle/` hoặc `build/` để lấp liếm lỗi.
8. Dùng script single-flight (nếu dự án đã có). Wrapper phải giữ lock nguyên tử, ghi nhận PID, thời gian, exit code.
9. Output test thành công chưa đủ chứng minh nếu Gradle Worker chưa thoát hoàn toàn.

### 3.1 Local Android distribution handoff gate

Khi implementation task có thay đổi repository và đã sẵn sàng để người dùng kiểm tra trên thiết bị, agent phải chạy handoff local đầy đủ:

```bash
TELEGRAM_DATA_SOURCE=real ./scripts/distribute-local.sh "<task name>"
```

- Chỉ bỏ qua gate cho task read-only hoặc khi user yêu cầu rõ ràng không upload.
- Chỉ dùng `--fast` cho vòng lặp UI nhỏ được yêu cầu rõ ràng; không đổi data source sang `fake` cho tester-facing release.
- Script phải thực hiện tuần tự test → lint → build debug real → Firebase App Distribution; không chạy thêm Gradle invocation song song.
- Nếu thiếu `.firebase-distribution.local`, `android-app/telegram-api.properties`, Firebase authentication, test, lint, build hoặc upload: dừng, báo `BLOCKED`, và không báo `READY_FOR_DEVICE_VERIFICATION`.
- `READY_FOR_DEVICE_VERIFICATION` chỉ chứng minh APK đã upload; agent vẫn phải tách riêng manual device verification và không tuyên bố feature đã confirmed.
- Release report phải ghi data source, build mode, APK path, Firebase result/links, branch, commit, working-tree state, checks, manual verification và known limitations.

## 4. Subagent và Concurrency Policy
- Subagent không có đầy đủ lịch sử của parent. Parent phải quản lý evidence.
- Chỉ dùng subagent cho task read-only, độc lập, có prompt đủ ngữ cảnh, không đụng chung file.
- **KHÔNG GIAO CHO SUBAGENT:** Gradle invocation, mutation index GitNexus, mutation cùng một file source, thay đổi product scope, hay ra quyết định completion của goal cha.

## 5. Fallback Policy
- `antigravity-guide` không khả dụng → dùng built-in help, project instruction.
- GitNexus stale/unavailable → native source + native `rg` / Ripgrep MCP.
- Ripgrep MCP unavailable → native `rg`.
- LeanCTX unavailable → `rg`, `tree`, direct read.
- Sequential Thinking unavailable → decision table thủ công.
- Android CLI unavailable nhưng cần evidence → báo BLOCKER, không giả lập (fake) evidence.
- MCP lỗi kết nối → retry an toàn 1 lần, sau đó fallback. Không tự reconfigure tool làm hỏng setup global.

## 6. Vòng lặp tự cập nhật Workflow có kiểm soát (Self-Learning Gate)
Trước khi kết thúc bất kỳ future goal nào:
1. Thu thập "candidate operational learnings".
2. Chỉ lưu lại (persist) learning vào tài liệu workflow này (hoặc `AGENTS.md`) nếu:
   - Có bằng chứng trực tiếp, tái sử dụng được, không phải do một bug cục bộ.
   - Không xung đột User instruction / Master Plan.
3. Deduplicate (chống trùng lặp) với rule cũ. Nếu thay thế, sửa rule hiện hành chứ không thêm bản sao.
4. Dùng `git diff` để review workflow mutation trước khi persist.
5. Khai báo rõ trong final report workflow đã tự cập nhật như thế nào.

---
**Tài liệu này là Canonical Orchestration Workflow.**
Mọi router từ `AGENTS.md` đều trỏ về đây. Đừng ghi đè tài liệu này bằng các kết luận tạm thời của một issue.
