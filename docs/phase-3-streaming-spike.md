# Phase 3 TDLib progressive-streaming feasibility spike

Status: `BLOCKED — DEPLOYMENT_APPROVAL_REQUIRED`

The new real build assembled successfully but was not installed. The already-installed
package can relaunch into the real Saved Media gallery without OTP; that is not
evidence for the new source revision. No phone, OTP, 2FA password, or Telegram data
mutation was performed.

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

The real-device diagnostic tag is `TelegramDrive.Streaming`. It records only file
identity/range/size/state values and player lifecycle events, never message content,
phone numbers, or filesystem paths. For an authorized run, capture it with:

```sh
adb logcat -c
adb logcat -v threadtime -s TelegramDrive.Streaming:I '*:S' > phase-3-real-streaming-log.txt
```

No real-account log has been captured yet; this instrumentation is preparation for the
required user-authorized run, not evidence that the run passed.

## What is locally proven

- `VideoStreamingCoordinatorTest` uses a 2 MiB fake file, requests a 4 KiB initial
  range, reads before completion, seeks to byte 1,048,576, reads that range, and
  verifies temporary cleanup on close.
- The coordinator tests also reject a TDLib-complete snapshot whose local file is
  shorter than the expected size, and prove that a seek supersedes a waiting range
  request immediately rather than waiting for the range timeout.
- A shared stable-file coordinator now keeps independent playback cursors for
  multiple Media3 datasources while serializing their TDLib range transfers.
- The fake Room/runtime wiring reaches the gallery and uses a Media3 factory for
  video items; it is not evidence of TDLib behavior.
- Historical connected instrumentation covered repository crash-resume/incremental
  updates and shared `TdLibVideoDataSource` release behavior; current additions
  compile but were not run, and all remain fake or injected-gateway evidence rather
  than proof of a real Telegram partial download.
- The latest fake-device gallery/video captures are [`phase-3-current-gallery.png`](evidence/phase-3-current-gallery.png)
  and [`phase-3-current-video.png`](evidence/phase-3-current-video.png); they remain
  explicitly non-evidence for the real TDLib progressive-streaming gate.
- The current source adds Media3-style reopen cancellation and late-update tests;
  instrumented tests compile but were not run because only the real-session emulator
  is available.

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
unverified. Deployment is blocked by
`DEPLOYMENT_APPROVAL_REQUIRED — SESSION_PRESERVING_UPDATE`; real logout/reset remains
`NOT_EXECUTED — USER_POLICY_SESSION_PRESERVATION`.
