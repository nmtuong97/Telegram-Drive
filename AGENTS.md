<!-- orchestration_router:start -->
# Antigravity Workflow Router
**MANDATORY:** Mọi Antigravity agent (đặc biệt khi chạy `/goal`) PHẢI đọc và tuân thủ Canonical Orchestration Workflow trước khi lập kế hoạch hay thực thi lệnh.
👉 **Đọc file:** `.agents/orchestration_workflow.md`
<!-- orchestration_router:end -->

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **Telegram-Drive** (7354 symbols, 14767 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

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
- Phase 2 tập trung vào vertical slice Saved Messages Paging → Download → Preview → Logout/Reset; không mở rộng sang Audio, PDF, External Open, Room, global gallery, background transfer, streaming, release, CI/CD, MCP hoặc Lightbuild.

### Tận dụng Android CLI trong phát triển Android (`android-cli`)

Tất cả AI Agent (Antigravity, Codex, Copilot, Subagents) khi làm việc trong `android-app/` PHẢI tận dụng sức mạnh của `android` CLI:

1. **Tra cứu tài liệu chuẩn (Android Documentation Lookup)**:
   - Dùng `android docs search "<keyword>"` để tìm tài liệu chính thức từ Android Knowledge Base.
   - Dùng `android docs fetch "kb://..."` với URL `kb://` được trả về từ lệnh search để xem chi tiết. Không truyền arbitrary web URL cho `android docs fetch`.

2. **Kiểm tra UI Layout tự động (Layout Hierarchy Inspection)**:
   - Khi ứng dụng đang chạy trên Emulator hoặc thiết bị thật, dùng `android layout --pretty --output=<file.json>` để lấy cây giao diện dưới dạng JSON. Dùng `android layout --diff` để so sánh layout.

3. **Xác minh trực quan (Visual Screenshots)**:
   - Chỉ sử dụng `android screen capture --output=<file.png>` (kèm `--annotate` nếu cần) để chụp ảnh màn hình thiết bị. Không sử dụng command `android screenshot`.

4. **Triển khai & Chạy ứng dụng (Deploy & Run)**:
   - `android run` không tự động build APK.
   - Bước 1: Build APK bằng bounded Gradle task: `./gradlew :app:assembleDebug -PtelegramDataSource=fake --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`.
   - Bước 2: Xác định APK path từ project metadata: `android describe --project_dir=android-app`.
   - Bước 3: Deploy: `android run --apks=<resolved-apk-path> --device=<serial-if-needed> --activity=<resolved-launcher-activity>`.

5. **Quản lý SDK & Emulator (SDK & AVD Management)**:
   - Sử dụng `android sdk list`, `android sdk install <package>`, `android emulator list`, `android emulator start <name>` để kiểm tra và quản lý môi trường Android SDK/AVD khi cần thiết.

6. **Xác minh bắt buộc trước handoff (Mandatory Handoff Verification)**:
   - Tại một thời điểm chỉ được có một Gradle invocation cho `android-app`.
   - Agent phải kiểm tra process trước khi chạy Gradle và không được tự retry nếu invocation trước chưa terminate.
   - Agent không được chạy biến thể `--info` và non-`--info` song song.
   - Full test, lint và build phải chạy tuần tự qua script single-flight guard `./scripts/run-gradle-single-flight.sh`.
   - Không chạy lệnh gộp không giới hạn `./gradlew testDebugUnitTest lintDebug assembleDebug`.
   - Luôn chạy từng Gradle task riêng biệt có timeout và flags diagnostic:
     - `./scripts/run-gradle-single-flight.sh :app:testDebugUnitTest --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
     - `./scripts/run-gradle-single-flight.sh :app:lintDebug --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
     - `./scripts/run-gradle-single-flight.sh :app:assembleDebug -PtelegramDataSource=fake --no-daemon --no-configuration-cache --no-parallel --max-workers=1 --console=plain --stacktrace`
   - Dùng Android CLI/adb để install, launch, dump layout hoặc chụp screenshot trước khi báo hoàn tất công việc.
