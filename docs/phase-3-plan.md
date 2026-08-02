# Phase 3 Implementation Plan — Saved Messages Local Media Gallery

## Baseline and scope

- Baseline branch: `agent/android-phase-2`.
- Baseline commit: `72325e101f201a8ce5a4c7786142f91c8ac00783`.
- Working branch: `agent/android-phase-3`.
- Official product specification: [`android-app/MASTER_PLAN.md`](../android-app/MASTER_PLAN.md).
- Existing Phase 2 changes/evidence in the working tree are preserved and are not part
  of the Phase 3 implementation diff.

Phase 3 extends the existing Kotlin/Compose application without replacing the source
browser, TDLib paging used by Phase 2, transfer behavior, document/audio/PDF/video
previews, secure sharing, authentication, session restore or logout/reset. The new
gallery indexes only Saved Messages images and videos.

## Current code seams verified at baseline

- `TdLibGateway`/`TdLibJsonGateway` expose domain-facing Saved Messages discovery,
  history paging, file downloads and file state mapping.
- `RealTelegramRepository` and `FakeTelegramRepository` are the app-facing source
  boundary; `TdLibPagingSource` is retained for the existing browser.
- `TransferCoordinator` and `DownloadCoordinator` own Phase 2 transfer deduplication
  and are reused through adapters rather than bypassed by UI.
- `MessageMapper` and `Models` are the TDLib-to-domain mapping boundary.
- `AppContainer` owns real/fake wiring and `AppNavigation`/feature screens own UI flow.
- Existing image/video preview screens remain backward compatible; the gallery adds a
  separate viewer/streaming entry point where necessary.

## Dependency direction

```text
Compose gallery/viewer
  → GalleryViewModel / ImageViewerViewModel / VideoPlayerViewModel
  → SavedMediaRepository / MediaCacheRepository / SyncRepository
  → Room DAOs + TransferCoordinator + TdLibVideoDataSource
  → TdLibGateway / TDLib
```

UI and domain code do not import `org.drinkless.tdlib`. Real and fake sources implement
the same repository contracts. A single account-generation owner scopes Room queries,
cache paths, transfer keys and callbacks.

## Delivery sequence

1. **Persistence foundation:** add Room dependency/configuration, entities for
   `saved_media`, `cached_file`, `sync_state`, DAOs, indexes, migration and account
   generation predicates. Add deterministic mapping and stable remote-file identity.
2. **TDLib event/sync boundary:** extend the gateway with message update registration,
   Saved Messages head discovery/history pages, delete/update events and range-capable
   file requests. Implement head-watermark → checkpointed backfill → listener UPSERT →
   catch-up state machine with restart-safe cancellation and retry metadata.
3. **Room gallery:** expose Paging 3 flows from Room with search/filter/sort/month
   grouping, partial-sync state and an adaptive Compose grid. Keep existing TDLib paging
   for the Phase 2 browser.
4. **Thumbnail/image lifecycle:** persist minithumbnails, add bounded/deduplicated
   thumbnail requests, cache reconciliation and eviction, then implement a reconciled
   full-quality image viewer with retry.
5. **Streaming feasibility spike:** on the real TDLib build, capture request/response,
   `updateFile`, offsets/prefix sizes, readable partial bytes and a large-video
   start-before-complete/seek trace. A failed spike is recorded as a blocker; it is not
   silently converted into full-download playback.
6. **Progressive player:** implement `TdLibVideoDataSource` and a per-stable-file
   `VideoStreamingCoordinator` with contiguous buffering, bounded range requests,
   cancellation/seek generations, network recovery, player release and temporary
   partial-cache cleanup. Reconcile TDLib + filesystem + Room on every open/restart.
7. **Reset/backup safety:** wire account generation invalidation through scanner,
   transfers, player callbacks and Room/cache deletion; verify Auto Backup exclusions.
8. **Verification:** add unit/fake integration tests, run Phase 2 regression gates, build
   and lint sequentially, deploy the fake APK with Android CLI, capture layout/screens,
   and complete real-account verification only with user-authorized test data.

## Test design

- Pure tests cover message classification/mapping, stable identity, idempotent upsert,
  cursor/watermark/catch-up, crash resume, Room query ordering/grouping, reconciliation,
  shared-file deduplication, transfer serialization, seek/cancel/late-update races and
  account-generation isolation.
- Fake integration data contains thousands of records across months/years, image/video,
  document-video, duplicate remote identity, edit/delete/new-during-backfill, crash
  checkpoints, missing cache files, cancellation and late updates.
- Every async test has a finite termination condition; no unbounded virtual-time loop.
- Paging tests assert that the complete dataset is not materialized in memory.

## Verification gates

All Android Gradle invocations are single-flight and sequential through
`scripts/run-gradle-single-flight.sh`:

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug -PtelegramDataSource=fake
```

Before each invocation, inspect test/Gradle processes; after each, record the actual exit
code and confirm no worker remains. Resolve APK metadata with `android describe`, deploy
with `android run`, inspect with `android layout` and capture with `android screen capture`.
Real-account OTP/2FA or explicit Telegram test-data changes are `USER_INTERACTION_REQUIRED`
and must never be simulated in evidence.

## Non-goals

No adaptive bitrate, HLS, DASH, offline-save feature, background transfer service, global
gallery outside Saved Messages, upload, Telegram message mutation, or Phase 4 release
hardening is introduced by this plan.
