# Agent Session

Agent-Session: <session-id>
Execution-Mode: codex-orchestrated
Execution-Role: primary-executor
Review-Owner: Codex

# Objective

<Mục tiêu duy nhất>

# Context

<Context cần thiết và source of truth>

# Scope

- <In scope>

# Non-goals

- <Out of scope>

# Acceptance criteria

1. <Criteria 1>
2. <Criteria 2>

# Constraints

- <Ràng buộc về thời gian, resource>

# Required verification

- <Test/Evidence cần thiết>

# Git protocol

- Làm việc trên branch và worktree hiện tại.
- Không push, merge, rebase, reset, amend hoặc cherry-pick.
- Commit theo từng thay đổi logic.
- Review finding phải được sửa bằng commit mới.
- Mỗi commit phải có Agent-Session trailer.
- Kết thúc với working tree sạch.

# Forbidden external side effects

- Không push, publish, deploy, distribute hoặc thay đổi tài nguyên từ xa.
- Không sửa hoặc xóa credential.
- Không thực hiện hành động phá hủy emulator/device nếu chưa được yêu cầu rõ ràng.
