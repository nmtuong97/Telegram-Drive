# Specification Quality Checklist: OneDrive Migration

**Purpose**: Xác nhận tính đầy đủ và chất lượng của specification trước khi chuyển sang giai đoạn lập kế hoạch.

**Created**: 2026-07-23

**Updated**: 2026-07-23 (post-clarification)

**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] Không chứa chi tiết triển khai (ngôn ngữ, framework, API, tên file Rust, database schema)
- [x] Tập trung vào giá trị người dùng và nhu cầu nghiệp vụ
- [x] Có thể đọc hiểu bởi người không chuyên kỹ thuật
- [x] Tất cả các phần bắt buộc đã được hoàn thành

## Requirement Completeness

- [x] Không còn marker [NEEDS CLARIFICATION]
- [x] Requirements có thể kiểm tra và không mơ hồ
- [x] Success criteria có thể đo lường được
- [x] Success criteria không chứa chi tiết công nghệ (technology-agnostic)
- [x] Tất cả acceptance scenarios đã được định nghĩa (25 scenarios trên 3 User Stories: US1=5, US2=12, US3=8)
- [x] Edge cases đã được xác định (14 edge cases)
- [x] Phạm vi được phân định rõ ràng (In Scope / Out of Scope)
- [x] Dependencies và assumptions đã được xác định (11 assumptions)

## Feature Readiness

- [x] Tất cả functional requirements có acceptance criteria rõ ràng
- [x] User scenarios bao phủ các luồng chính
- [x] Feature đáp ứng các measurable outcomes đã định nghĩa trong Success Criteria
- [x] Không có chi tiết triển khai lọt vào specification

## MVP Scope Validation — Cấu trúc

- [x] Tối đa 3 User Stories (chính xác: 3 — 2×P1, 1×P2)
- [x] Không quá 25 Functional Requirements (chính xác: 25 — FR-001 đến FR-025)
- [x] Có section Non-Functional Requirements riêng (7 NFR)
- [x] Có đúng các NFR tối thiểu: memory, concurrency, persistence, completed-file safety, security, UI responsiveness, platform

## MVP Scope Validation — Hành vi

- [x] Snapshot migration được mô tả rõ (thuật ngữ "migration snapshot" nhất quán)
- [x] Có scan folder/file và summary totals
- [x] Có local working folder (một directory cho mỗi job, không tự fallback)
- [x] Có duplicate detection theo content fingerprint (OneDrive fingerprint ưu tiên, fallback SHA-256)
- [x] Duplicate history dùng chung giữa các job
- [x] Duplicate chỉ được ghi sau upload success (không ghi file failed)
- [x] Có persistence và resume thủ công (không auto-start)
- [x] Pause-after-current-file được mô tả rõ ràng
- [x] Manual resume sau restart được mô tả rõ ràng
- [x] Giới hạn at-least-once của crash-during-upload được ghi rõ (không exactly-once)
- [x] Retry tối đa 3 lần tự động cho lỗi tạm thời; manual retry reset counter
- [x] Telegram cooldown: không bypass, persist, tự tiếp tục sau cooldown
- [x] Một active job (chỉ một job running, có thể lưu nhiều job history)
- [x] Có progress cơ bản
- [x] Có retry cơ bản

## MVP Scope Validation — Những thứ bị loại trừ

- [x] Không xóa OneDrive source (FR-021)
- [x] Không continuous monitoring (Out of Scope)
- [x] Không local backup lâu dài (Out of Scope)
- [x] Không system tray / autostart (Out of Scope)
- [x] Không sidecar / service (Out of Scope)
- [x] Không Telegram reconciliation (Out of Scope)
- [x] Không exactly-once claim (at-least-once được ghi rõ trong spec)
- [x] Không multiple active jobs (FR-022)
- [x] Không tái tạo cây thư mục OneDrive trên Telegram (FR-005)
- [x] Không mobile (NFR-007, Out of Scope)

## Acceptance Scenarios Coverage

- [x] Setup và scan thành công (US1-S3)
- [x] Source folder rỗng (US1-S4)
- [x] Migration tuần tự (US2-S1)
- [x] Duplicate phát hiện trước download (US2-S7, US3-S7)
- [x] Duplicate phát hiện sau download (US2-S8, US3-S8)
- [x] File cùng tên nhưng khác nội dung vẫn upload (US3-S5)
- [x] Pause sau file hiện tại (US2-S3)
- [x] Resume sau restart (US3-S1)
- [x] Retry file failed (US3-S2)
- [x] Telegram cooldown (US2-S9)
- [x] Working directory không writable (US3-S6)
- [x] File vượt giới hạn Telegram (US2-S6)
- [x] File nguồn thay đổi sau scan (US2-S10)
- [x] Temporary file cleanup (US2-S11)
- [x] Không xóa hoặc sửa source OneDrive (US2-S12)

## Forbidden Content Check

- [x] Không chứa "Recycle Bin"
- [x] Không chứa "delete source" (trừ FR-021 khẳng định KHÔNG xóa)
- [x] Không chứa "continuous monitoring"
- [x] Không chứa "system tray"
- [x] Không chứa "autostart"
- [x] Không chứa "sidecar"
- [x] Không chứa "exactly-once" (trừ ngữ cảnh phủ định trong at-least-once)
- [x] Không chứa "local backup"
- [x] Không chứa [NEEDS CLARIFICATION]
- [x] Không chứa tên file Rust, struct, crate, function
- [x] Không chứa task IDs
- [x] Không chứa database schema
- [x] Không chứa plan triển khai
- [x] Không chứa state machine phức tạp

## Notes

- Tất cả 15 clarification decisions (C01–C15) đã được tích hợp vào spec.
- FR-026 ban đầu đã được gộp vào FR-005 để giữ tổng số FR = 25.
- Section Non-Functional Requirements đã được thêm với 7 NFR.
