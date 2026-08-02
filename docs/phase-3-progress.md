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
- Final implementation commit: `3329d13` (`feat(android): implement phase 3 saved media gallery`).
- Final evidence/documentation commit: recorded after this evidence update.

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
  entries, original-file reconciliation, and TDLib-mediated temporary-file cleanup.
- Media3 bridge: `TdLibVideoDataSource` → `VideoStreamingCoordinator` → TDLib
  `downloadFile(offset, limit)` / `updateFile`; range reads are serialized by stable
  Telegram file identity and shared until the last player datasource closes.
- Existing source browser, document/audio/PDF/animation/external preview, transfer,
  auth, session, reset, and fake runtime paths remain available.
- Android Auto Backup/device transfer remains disabled/excluded for database, TDLib
  session, key, cache, and downloaded media paths.

## Verification evidence

| Gate | Result | Evidence |
| --- | --- | --- |
| `:app:testDebugUnitTest` | PASS, exit 0 | 120 tests completed before final cleanup; latest final run is recorded in command log and passed. |
| `:app:lintDebug` | PASS, exit 0 | No lint errors; warnings only. |
| `:app:assembleDebug -PtelegramDataSource=fake` | PASS, exit 0 | Final APK SHA-256: `098e2c5b2250dd7b80cc52668b364429c1dde930d5962b99d3a98f0e74ec663d`. |
| `:app:assembleDebug -PtelegramDataSource=real` | PASS, exit 0 | Real APK build is valid; launch stopped at Telegram sign-in and requires user credentials/OTP/2FA. |
| `:app:connectedDebugAndroidTest -PtelegramDataSource=fake` | PASS, exit 0 | 9 tests on `Pixel_9_Pro` API 36, including Room paging and existing Keystore coverage. |
| Fake runtime | PASS for fake scope | Gallery sync and Room-backed cards captured in [`phase-3-gallery-final.png`](evidence/phase-3-gallery-final.png); image viewer passes with `content-desc="Image preview"`; fake Media3 video preview opens with timeline/controls in [`phase-3-video-preview-final.png`](evidence/phase-3-video-preview-final.png). These are not real-TDLib progressive-streaming evidence. |
| Android CLI | PASS | Deploy, layout, and screen capture completed; commands and paths are in the evidence manifest. |
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
- Current preflight evidence is [`phase-3-real-preflight-layout.json`](evidence/phase-3-real-preflight-layout.json):
  the real build requires Telegram sign-in before any account data can be inspected.
- Codec/container support is delegated to Media3; unsupported media reports a player
  error through the existing preview surface.
- The fake gallery dataset is intentionally small at runtime; the unit fake dataset
  contains 3,000 multi-year image/video/document-video-classified records and duplicate
  file identities. Room instrumentation covers 2,000 rows without collecting all rows.
