---
name: antigravity-executor
description: Hướng dẫn Codex điều phối Antigravity làm primary executor thông qua agent-session.
---

# Antigravity Executor (Codex-orchestrated mode)

Codex có thể sử dụng Antigravity làm primary executor thay vì tự thực hiện thay đổi code. Trong mô hình này, Codex đóng vai trò thiết kế (owner) và review (reviewer), trong khi Antigravity đảm nhận việc đọc code, thực thi, test và debug (executor).

## Workflow

1. **Chuẩn bị Task Contract**
   Codex tạo một file `task.md` dựa trên template tại `.agents/templates/antigravity-task-contract.md`. 
   Trong template này:
   - `Execution-Mode: codex-orchestrated`
   - `Review-Owner: Codex`

2. **Bắt đầu Session**
   Codex gọi Python wrapper để khởi động Antigravity:
   ```bash
   python3 scripts/agent-session.py start --session <session-id> --task-file <path-to-task.md>
   ```
   *Quá trình này sẽ block và stream JSON logs ra stdout để Codex có thể theo dõi tiến độ.*

3. **Theo dõi Trạng thái**
   Trong trường hợp cần thiết, Codex có thể xem trạng thái:
   ```bash
   python3 scripts/agent-session.py status --session <session-id>
   ```

4. **Review (Nếu Antigravity báo cáo hoàn thành)**
   Sau khi `start` hoặc `continue` hoàn tất với status `completed`:
   - Codex đọc raw logs tại `.git/agent-sessions/<session-id>/antigravity.jsonl`.
   - Codex sử dụng Git để review thay đổi:
     - `git log --reverse --format=fuller BASE..RESULT` (Provenance)
     - `git show <commit>` (Per-commit)
     - `git diff BASE..RESULT` (Aggregate)
   - Codex đối chiếu với yêu cầu, check GitNexus `detect_changes` và test evidence.

5. **Yêu cầu Sửa lỗi (Nếu Review Fail)**
   - Codex tạo file `review.md` chứa các finding cần sửa.
   - Gọi Antigravity để sửa lỗi:
     ```bash
     python3 scripts/agent-session.py continue --session <session-id> --review-file <path-to-review.md>
     ```

6. **Accept và Handoff (Nếu Review Pass)**
   - Khi không còn finding nào, Codex đánh dấu session là accepted:
     ```bash
     python3 scripts/agent-session.py accept --session <session-id>
     ```
   - Chạy handoff (Firebase distribution) nếu yêu cầu:
     ```bash
     python3 scripts/agent-session.py handoff --session <session-id>
     ```

## Fallback

Nếu lệnh `start` thất bại do lỗi cấu hình, quá giới hạn quota, hoặc CLI không khả dụng (ví dụ lỗi `cli_unavailable`), Codex sẽ thông báo cho người dùng và có thể đề xuất tự tiếp quản (fallback executor) nếu được phép.

## Quan trọng

- KHÔNG để Codex tự commit nếu đang dùng Antigravity.
- Đảm bảo working tree phải sạch (clean) trước khi gọi `start`, `continue`, `accept` hoặc `handoff`.
- Luôn tuân thủ nguồn sự thật duy nhất tại `.agents/orchestration_workflow.md`.
