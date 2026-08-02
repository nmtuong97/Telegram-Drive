# Phase 3 TDLib progressive-streaming feasibility spike

Status: `PENDING — USER_INTERACTION_REQUIRED`

Preflight evidence: the real build assembled successfully, but the emulator is at
Telegram sign-in in `docs/evidence/phase-3-real-preflight-layout.json`; no phone,
OTP, 2FA password, or Telegram data mutation was performed.

The production path is implemented as:

```text
Media3/ExoPlayer
  -> TdLibVideoDataSource
  -> VideoStreamingCoordinator
  -> TDLib downloadFile(file_id, offset, limit, synchronous=false)
  -> updateFile / partial local file
```

The coordinator serializes a stable remote file identity, waits for a contiguous
prefix or a requested `downloadOffset` range, and releases Media3/TDLib temporary
state on the last player close. It does not use HLS, DASH, adaptive bitrate, or a
full-download fallback to satisfy Phase 3.

## What is locally proven

- `VideoStreamingCoordinatorTest` uses a 2 MiB fake file, requests a 4 KiB initial
  range, reads before completion, seeks to byte 1,048,576, reads that range, and
  verifies temporary cleanup on close.
- The fake Room/runtime wiring reaches the gallery and uses a Media3 factory for
  video items; it is not evidence of TDLib behavior.
- The latest fake-device gallery/video captures are [`phase-3-current-gallery.png`](evidence/phase-3-current-gallery.png)
  and [`phase-3-current-video.png`](evidence/phase-3-current-video.png); they remain
  explicitly non-evidence for the real TDLib progressive-streaming gate.

## Required real Telegram run

On an authorized account, capture the TDLib request/updates and filesystem values for
a video large enough that the initial request cannot complete the file:

1. record file ID, stable remote identity, expected size;
2. request initial offset 0 / finite limit and record `updateFile` values;
3. prove playback starts while `is_downloading_completed=false`;
4. seek to beginning, middle, and near end, recording range requests and readable bytes;
5. toggle network loss/recovery, retry/resume, then close player;
6. compare filesystem usage and TDLib/Room state before, during, and after cleanup;
7. repeat after process restart and logout/reset checks.

Until this run is completed, the progressive-streaming acceptance criterion remains
unverified. OTP/2FA or an explicit user-authorized session is `USER_INTERACTION_REQUIRED`.
