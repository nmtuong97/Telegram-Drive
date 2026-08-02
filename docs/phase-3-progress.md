# Phase 3 progress — Saved Messages Local Media Gallery

Status: `BLOCKED — PHASE_3_NOT_COMPLETE`

This report records only evidence produced in the current `agent/android-phase-3`
worktree. It does not upgrade implementation evidence into real-account evidence.

## Baseline and source of truth

- Repository: `nmtuong97/Telegram-Drive`
- Baseline branch: `agent/android-phase-2`
- Baseline commit: `72325e101f201a8ce5a4c7786142f91c8ac00783`
- Working branch: `agent/android-phase-3`
- Official Master Plan: [`android-app/MASTER_PLAN.md`](../android-app/MASTER_PLAN.md)
- Implementation plan: [`docs/phase-3-plan.md`](phase-3-plan.md)
- Initial implementation commit: `3329d13` (`feat(android): implement phase 3 saved media gallery`).
- Test coverage commit: `ace373a` (`test(android): add phase 3 media and streaming coverage`).
- Prior evidence/documentation commits: `9fd8b0f`, `e3c0a4c`, `bea623f`.
- Latest lifecycle hardening commit: `3d0a15a` (`fix(android): harden phase 3 account and media lifecycle`).
- Latest account-isolation/gallery-flow commit: `730e2b1` (`fix(android): scope gallery flows to account identity`).
- Latest implementation commit: `3466de3` (`fix(android): cancel stale video range waits`).

## Implemented

- Master Plan roadmap now makes Phase 3 the Room-backed Saved Messages image/video
  gallery with TDLib progressive partial/range video streaming; hardening is Phase 4.
- Room schema and account/generation keys for `saved_media`, `cached_file`, and
  `sync_state`; database is created below `noBackupFilesDir` and has no destructive
  migration.
- Full-history sync flow with head watermark, resumable backfill cursor, per-page
  transaction checkpoints, listener UPSERTs, bounded catch-up, and new/edit/delete
  handling.
- Room Paging gallery with local search, media filter, local-file filter, sort toggle,
  month label, minithumbnail/placeholder rendering, and sync state/error UI.
- Bounded and deduplicated thumbnail access, cache eviction limit of 200 thumbnail
  entries, original-file reconciliation against TDLib plus readable filesystem bytes,
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
- Thumbnail/video deduplication and cleanup are keyed by account identity plus stable
  remote file identity; Paging and sync-state observation rebind on identity changes,
  preventing stale gallery rows or callbacks from crossing account generations.
- Gallery sync status exposes phase, checkpoint/head watermark, last successful catch-up,
  and retryable errors; image-open failures expose retry.
- Existing source browser, document/audio/PDF/animation/external preview, transfer,
  auth, session, reset, and fake runtime paths remain available.
- Android Auto Backup/device transfer remains disabled/excluded for database, TDLib
  session, key, cache, and downloaded media paths.

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
  seek supersedes stale requests, and the final datasource close releases Media3,
  cancels unneeded work, and asks TDLib to remove temporary video data.
- Cache lifecycle is minithumbnail/placeholder during indexing, bounded lazy thumbnail
  requests near the viewport, reconciled original on image open, and temporary partial
  video data during playback. Logout/reset cancels account work before deleting the
  account generation; Room, TDLib snapshots, and filesystem paths are reconciled on
  restart and Android cache eviction.

## Verification evidence

| Gate | Result | Evidence |
| --- | --- | --- |
| `:app:testDebugUnitTest -PtelegramDataSource=fake` | PASS, exit 0 | 126 tests completed, 0 failures/errors/skips; includes complete-file-size validation and seek supersession without waiting for the timeout. |
| `:app:lintDebug` | PASS, exit 0 | No lint errors; warnings only. |
| `:app:assembleDebug -PtelegramDataSource=fake` | PASS, exit 0 | Final APK is 70,112,344 bytes; SHA-256: `4147ea63f73434d8fd0196e7b41df5b4d85ddd9290d8de0d5740fc9abcda1f89`. |
| `:app:assembleDebug` (real default) | PASS, exit 0 | Current source also assembles real APK; launch stopped at Telegram sign-in and requires user credentials/OTP/2FA. |
| `:app:connectedDebugAndroidTest -PtelegramDataSource=fake` | PASS, exit 0 | 12 tests on `TelegramDrive_Small` API 36, 0 failures/errors/skips, including account-scoped Paging rebinding, repository crash-resume/update, and shared-video release tests. |
| Fake runtime | PASS for fake scope | Current Room-backed gallery is captured in [`phase-3-current-gallery.png`](evidence/phase-3-current-gallery.png) with hierarchy in [`phase-3-current-gallery-layout.json`](evidence/phase-3-current-gallery-layout.json); fake Media3 video preview is captured in [`phase-3-current-video.png`](evidence/phase-3-current-video.png). Resumed fake APK sign-in state is captured in [`phase-3-resumed-runtime.png`](evidence/phase-3-resumed-runtime.png) with hierarchy in [`phase-3-resumed-runtime-layout.json`](evidence/phase-3-resumed-runtime-layout.json). Earlier image-viewer evidence remains valid. These are not real-TDLib progressive-streaming evidence. |
| Android CLI | PASS | Final fake APK deployed with `android run`; current sign-in layout and annotated screenshot are [`phase-3-final-layout.json`](evidence/phase-3-final-layout.json) and [`phase-3-final-runtime.png`](evidence/phase-3-final-runtime.png). |
| Real Telegram account | NOT VERIFIED | Real preflight reaches sign-in; progressive spike, large-video seek, network recovery, and account logout remain `USER_INTERACTION_REQUIRED` / pending authorized real-account run. |

## Acceptance status

Implemented in code: roadmap, Room model, checkpointed sync, incremental update
contract, Room Paging/search/filter/sort, month labels, thumbnail/original lifecycle,
TDLib range coordinator, shared-file serialization, account-scoped metadata cleanup,
fake/instrumented/unit coverage, lint, and debug build.

Not accepted yet: proof on a real Telegram account that TDLib partial/range download
starts a large video before completion, seek works across unavailable ranges, network
loss/recovery works, storage is measured before/during/after playback, real edit/delete
events are observed, and real logout/session restore completes without user action.

No fallback to full-download playback was used to mark these criteria complete.

## Known limitations and blocker

- The real-account feasibility spike must be run against an authorized account with a
  sufficiently large Telegram video and explicit user permission for read-only media
  verification. OTP/2FA or session authorization is `USER_INTERACTION_REQUIRED`.
- Latest preflight evidence is [`phase-3-real-final-preflight-layout.json`](evidence/phase-3-real-final-preflight-layout.json)
  and [`phase-3-real-final-preflight.png`](evidence/phase-3-real-final-preflight.png):
  the real build requires Telegram sign-in before any account data can be inspected.
- The current fake-device preflight is [`phase-3-final-layout.json`](evidence/phase-3-final-layout.json)
  with screenshot [`phase-3-final-runtime.png`](evidence/phase-3-final-runtime.png); it is
  explicitly fake-runtime sign-in evidence and does not satisfy the real-account gate.
- Codec/container support is delegated to Media3; unsupported media reports a player
  error through the existing preview surface.
- The fake gallery dataset is intentionally small at runtime; the unit fake dataset
  contains 3,000 multi-year image/video/document-video-classified records and duplicate
  file identities. Room instrumentation covers 2,000 rows without collecting all rows.
