<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **Telegram-Drive** (5042 symbols, 9000 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/Telegram-Drive/context` | Codebase overview, check index freshness |
| `gitnexus://repo/Telegram-Drive/clusters` | All functional areas |
| `gitnexus://repo/Telegram-Drive/processes` | All execution flows |
| `gitnexus://repo/Telegram-Drive/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->

## Ứng dụng Android độc lập & Quy trình Android CLI

- Project nằm tại `android-app/`; không sửa `app/src-tauri/gen/android` vì đó là output generated của Tauri.
- Dùng Kotlin, Compose, Kotlin DSL, minSdk 26 và application ID `com.nmtuong.telegramdrive`.
- Dependency hướng vào trong: UI/feature → repository → gateway; UI/domain không import `org.drinkless.tdlib`.
- TDLib chỉ lấy từ source Telegram chính thức và build bằng `scripts/build-tdlib-android.sh`; không thêm binary bên thứ ba.
- TDLib pin commit `022d60202e446ad1287b9fb68e687c8a0760788b`; crypto pin OpenSSL 3.5.7 LTS với checksum trong script; metadata/hash binary ở `android-app/tdlib-build-metadata.txt`.
- Real/fake source chọn bằng Gradle property `-PtelegramDataSource=real|fake`; không đưa credential/session thật vào source hoặc artifact.
- Android backup và device transfer phải giữ trạng thái disabled/excluded cho toàn bộ account, TDLib database/session/key, cache và downloaded media.
- TDLib gateway có lifecycle state machine application-owned; không dùng `Application.onTerminate()` làm cleanup production. Logout/reset/test teardown là explicit close owners; process kill được xem là abrupt.
- Phase 1 chỉ là vertical slice auth/session/Saved Messages/download/preview; không mở rộng sang Room, global gallery, background transfer, streaming, release, CI/CD, MCP hoặc Lightbuild.

### Tận dụng Android CLI trong phát triển Android (`android-cli`)

Tất cả AI Agent (Antigravity, Codex, Copilot, Subagents) khi làm việc trong `android-app/` PHẢI tận dụng sức mạnh của `android` CLI:

1. **Tra cứu tài liệu chuẩn (Android Documentation Lookup)**:
   - Dùng `android docs search "<keyword>"` hoặc `android docs fetch "<url_or_topic>"` để tra cứu API Android, Jetpack Compose, Coroutines, Android Security best practices chính thống từ Android Knowledge Base trước khi viết code cho các tính năng mới hoặc API chưa quen thuộc.

2. **Kiểm tra UI Layout tự động (Layout Hierarchy Inspection)**:
   - Khi ứng dụng đang chạy trên Emulator hoặc thiết bị thật, dùng `android layout -p` để lấy cây giao diện dưới dạng JSON.
   - Ưu tiên dùng `android layout` để kiểm tra node UI, text, visibility và bounds nhanh chóng và chính xác.

3. **Xác minh trực quan (Visual Screenshots)**:
   - Sử dụng `android screen capture` hoặc `android screenshot` để chụp ảnh màn hình thiết bị khi hoàn tất chỉnh sửa UI hoặc cần cung cấp bằng chứng chạy runtime.

4. **Triển khai & Chạy ứng dụng (Deploy & Run)**:
   - Dùng `android run --debug` hoặc `android run --apks=<path>` để nạp và khởi chạy APK trực tiếp lên thiết bị/emulator.

5. **Quản lý SDK & Emulator (SDK & AVD Management)**:
   - Sử dụng `android sdk list`, `android sdk install <package>`, `android emulator list`, `android emulator start` để kiểm tra và quản lý môi trường Android SDK/AVD khi cần thiết.

6. **Xác minh bắt buộc trước handoff (Mandatory Handoff Verification)**:
   - Luôn chạy `./gradlew testDebugUnitTest lintDebug assembleDebug` trong thư mục `android-app/` và dùng `android` CLI/adb để install, launch, dump layout hoặc chụp screenshot trước khi báo hoàn tất công việc.

