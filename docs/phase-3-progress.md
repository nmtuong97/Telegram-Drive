# Phase 3 progress — Saved Messages Local Media Gallery

Status: `BLOCKED — PHASE_3_NOT_COMPLETE`

This report records evidence from the canonical `main` checkout and the current
uncommitted defect-closure worktree. It does not upgrade implementation evidence
into real-account evidence.

## Baseline and source of truth

- Repository: `nmtuong97/Telegram-Drive`
- Canonical branch: `main`
- Starting/final checked-out main HEAD for this goal: `b41b7e9f302e0336a7eb16565f636a98fc6223fd`
- Phase 2 merge parent: `4b85d69074772f076ffdd11e2fafe546b027981c`
- Phase 3 implementation tip merged into main: `26891616cd745862d3a7149ee80f5eea41fb38d3`
- Phase 2/3 branch commits listed below are historical implementation lineage.
- Official Master Plan: [`android-app/MASTER_PLAN.md`](../android-app/MASTER_PLAN.md)
- Implementation plan: [`docs/phase-3-plan.md`](phase-3-plan.md)
- Initial implementation commit: `3329d13` (`feat(android): implement phase 3 saved media gallery`).
- Test coverage commit: `ace373a` (`test(android): add phase 3 media and streaming coverage`).
- Prior evidence/documentation commits: `9fd8b0f`, `e3c0a4c`, `bea623f`.
- Latest lifecycle hardening commit: `3d0a15a` (`fix(android): harden phase 3 account and media lifecycle`).
- Latest account-isolation/gallery-flow commit: `730e2b1` (`fix(android): scope gallery flows to account identity`).
- Previous range-cancellation hardening commit: `3466de3` (`fix(android): cancel stale video range waits`).
- Previous media-cache/reader hardening commit: `305b309` (`fix(android): harden phase 3 media cache and readers`).
- Previous implementation commit: `1224709` (`fix(android): add non-destructive media database migration`).
- Previous update-delivery commit: `995cf5b` (`fix(android): prevent dropped saved message updates`).
- Previous implementation commit: `ad07249` (`fix(android): add retryable video buffering state`).
- Latest implementation commit: `f217e53` (`feat(android): add streaming spike diagnostics`).

## Implemented

- Master Plan roadmap now makes Phase 3 the Room-backed Saved Messages image/video
  gallery with TDLib progressive partial/range video streaming; hardening is Phase 4.
- Room schema and account/generation keys for `saved_media`, `cached_file`, and
  `sync_state`; database is created below `noBackupFilesDir`, exports schema version 2,
  and migrates version 1 non-destructively while preserving sync state.
- Full-history sync flow with head watermark, resumable backfill cursor, per-page
  transaction checkpoints, listener UPSERTs, bounded catch-up, and new/edit/delete
  handling.
- Room Paging gallery with local search, media filter, local-file filter, sort toggle,
  month label, minithumbnail/placeholder rendering, and sync state/error UI.
- Bounded and deduplicated thumbnail access, cache eviction limit of 200 thumbnail
  entries with oldest-first eviction, original-file reconciliation against TDLib plus readable filesystem bytes,
  stale-thumbnail reload, and TDLib-mediated temporary-file cleanup.
- Media3 bridge: `TdLibVideoDataSource` → `VideoStreamingCoordinator` → TDLib
  `downloadFile(offset, limit)` / `updateFile`; range reads are serialized by stable
  Telegram file identity and shared until the last player datasource closes.
- Logout/reset now cancel account-scoped thumbnail/original/range work before metadata
  cleanup; late `updateFile` events from the prior account generation are ignored.
- Shared video data sources now release a stable-file coordinator by reference count;
  closing one player cannot close a transfer still used by another player.
- Cache reconciliation now rejects mismatched stable identities, preserves shared-file
  metadata, clears all message references when a shared video cache is removed, and
  evicts orphan cached-file rows safely.
- Account mutation/reconciliation is serialized across scanner callbacks, logout/reset,
  and late TDLib file updates. Unknown post-boundary file IDs stay blocked until an
  explicit request; non-zero range downloads do not masquerade as contiguous prefixes.
- Saved-message TDLib updates use an unbounded channel with suspending-flow delivery,
  so listener bursts are not silently dropped; account-boundary invalidation drains
  queued updates before the next generation can consume them.
- Video preview reports buffering state and exposes retry after a Media3/TDLib error;
  retry recreates the player and permits a missing stale partial path to be reconciled
  and requested again by the TDLib data source.
- The real-streaming path emits account-safe logcat diagnostics tagged
  `TelegramDrive.Streaming` for range requests, `updateFile` prefix/offset/size state,
  Media3 reads/seeks, and temporary-file cleanup; no user content or filesystem path is logged.
- Thumbnail/video deduplication and cleanup are keyed by account identity plus stable
  remote file identity; Paging and sync-state observation rebind on identity changes,
  preventing stale gallery rows or callbacks from crossing account generations.
- Gallery sync status exposes phase, checkpoint/head watermark, last successful catch-up,
  and retryable errors; image-open failures expose retry.
- Existing source browser, document/audio/PDF/animation/external preview, transfer,
  auth, session, reset, and fake runtime paths remain available.
- Android Auto Backup/device transfer remains disabled/excluded for database, TDLib
  session, key, cache, and downloaded media paths.

## Defect-closure work in this goal

- Catch-up now captures a target head per pass, commits `(previousTarget, target]`,
  advances the lower bound only after that interval is checkpointed, and resumes from
  the committed target after an interruption.
- `TdLibVideoDataSource.read()` returns Media3 EOF before changing position,
  remaining bytes, or transfer metrics; non-positive non-EOF results fail safely.
- Shared video readers now have per-datasource cancellation handles, so closing A
  cancels A's pending range while B keeps the stable-file coordinator alive.
- Regression tests cover one/multiple head increases, bounded instability,
  crash-between-pass resume, EOF, and Media3 reopen/late-update behavior. The
  instrumented tests compile but were not run because only the real-session emulator
  is available.

## Actual architecture recorded for handoff

- Room tables are `saved_media` (account ID/generation, chat/message identity, image
  or video classification, date/caption/display name/MIME/dimensions/duration, TDLib
  file IDs and stable remote identities, minithumbnail and local-path references),
  `cached_file` (one physical thumbnail/original/partial file per account-scoped
  stable identity, observed TDLib file ID/path/size/state/access time), and
  `sync_state` (phase, head watermark, raw-history cursor, checkpoint time,
  last-successful catch-up, error/retry metadata). Shared stable identities are
  reference-counted by `saved_media`; Room cache state is reconciled with TDLib and
  readable filesystem state before opening a file.
- Sync state is `IDLE → DISCOVERING_HEAD → BACKFILLING → CATCHING_UP → COMPLETED`, with
  `ERROR`/retry metadata at any resumable stage. TDLib listeners UPSERT while the
  checkpointed backfill runs; the original head watermark is retained until catch-up
  commits, so a crash cannot discard the interval discovered during backfill.
- Streaming is `Media3 → TdLibVideoDataSource → VideoStreamingCoordinator → TDLib
  downloadFile(offset, limit) / updateFile → partial local file`. Buffer progress is
  tracked separately from playback progress; stable-file transfers serialize ranges,
  each Media3 datasource owns its playback cursor, seek supersedes stale requests,
  and the final datasource close releases Media3, cancels unneeded work, and asks TDLib
  to remove temporary video data.
- Cache lifecycle is minithumbnail/placeholder during indexing, bounded lazy thumbnail
  requests near the viewport, reconciled original on image open, and temporary partial
  video data during playback. Logout/reset cancels account work before deleting the
  account generation; Room, TDLib snapshots, and filesystem paths are reconciled on
  restart and Android cache eviction.

## Verification evidence

| Gate | Result | Evidence |
| --- | --- | --- |
| `:app:testDebugUnitTest -PtelegramDataSource=fake` | PASS, exit 0, ~14.0s | 132 tests, 0 failures/errors/skips. |
| `:app:compileDebugAndroidTestKotlin -PtelegramDataSource=fake` | PASS, exit 0, ~10.5s | New repository and Media3 lifecycle tests compile; instrumentation was not run. |
| `:app:lintDebug` | PASS, exit 0, ~9.0s | No lint errors; existing warnings only. |
| `:app:assembleDebug -PtelegramDataSource=fake` | PASS, exit 0, ~10.1s | Fake debug APK assembled; it was not deployed. |
| `:app:assembleDebug` | PASS, exit 0, ~4.3s | Real debug APK assembled; it was not deployed. |
| `:app:connectedDebugAndroidTest -PtelegramDataSource=fake` | NOT RUN | Only `Pixel_9_Pro` / `emulator-5554` is available and is reserved for the real Telegram session. |
| Fake runtime | PASS for fake scope | Current Room-backed gallery is captured in [`phase-3-current-gallery.png`](evidence/phase-3-current-gallery.png) with hierarchy in [`phase-3-current-gallery-layout.json`](evidence/phase-3-current-gallery-layout.json); fake Media3 video preview is captured in [`phase-3-current-video.png`](evidence/phase-3-current-video.png). Resumed fake APK sign-in state is captured in [`phase-3-resumed-runtime.png`](evidence/phase-3-resumed-runtime.png) with hierarchy in [`phase-3-resumed-runtime-layout.json`](evidence/phase-3-resumed-runtime-layout.json). Earlier image-viewer evidence remains valid. These are not real-TDLib progressive-streaming evidence. |
| Android CLI / installed runtime | LIMITED PASS | Read-only package inspection plus force-stop/relaunch of installed version `1.0` returned to Saved Media without OTP; no new APK was installed. Layout was captured only to `/tmp` and is not evidence for this source revision. |
| Real Telegram account | NOT VERIFIED | New-source gallery sync, image/video lifecycle, progressive streaming, seek, network recovery, storage, and incremental mutation remain unverified. |

## Acceptance status

Implemented in code and locally checked: the existing Phase 3 slice plus the three
P0 defect closures described above; unit tests, Android-test compilation, lint, and
fake/real debug builds pass.

Not accepted yet: proof on a real Telegram account that TDLib partial/range download
starts a large video before completion, seek works across unavailable ranges, network
loss/recovery works, storage is measured before/during/after playback, real edit/delete
events are observed, and real logout/session restore completes without user action.

No fallback to full-download playback was used to mark these criteria complete.

## Known limitations and blocker

- The real-account feasibility spike must be run against an authorized account with a
  sufficiently large Telegram video and explicit user permission for read-only media
  verification. OTP/2FA or session authorization is `USER_INTERACTION_REQUIRED`.
- The installed package relaunch reached the real Saved Media gallery, but this is
  evidence for the installed package, not the newly assembled APK.
- New-source device validation is blocked by
  `DEPLOYMENT_APPROVAL_REQUIRED — SESSION_PRESERVING_UPDATE`; no install or replace
  command was run.
- `NOT_EXECUTED — USER_POLICY_SESSION_PRESERVATION` applies to real logout/reset.
- `USER_INTERACTION_REQUIRED — TEST_MESSAGE_MUTATION` applies to new/edit/delete
  Telegram message verification.
- The current fake-device preflight is [`phase-3-final-layout.json`](evidence/phase-3-final-layout.json)
  with screenshot [`phase-3-final-runtime.png`](evidence/phase-3-final-runtime.png); it is
  explicitly fake-runtime sign-in evidence and does not satisfy the real-account gate.
- Codec/container support is delegated to Media3; unsupported media reports a player
  error through the existing preview surface.
- The fake gallery dataset is intentionally small at runtime; the unit fake dataset
  contains 3,000 multi-year image/video/document-video-classified records and duplicate
  file identities. Room instrumentation covers 2,000 rows without collecting all rows.
- Historical Phase 2 evidence was privacy-audited and contains account-specific text
  in tracked layout/manifests; it is not reclassified as sanitized by this goal.
