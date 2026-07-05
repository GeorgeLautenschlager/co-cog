# BRIEF: Bramble B1 — Capture Service & Mute Surfaces

**Name:** B1 — Capture Service & Mute Surfaces
**Status:** Approved v1 — 2026-07-04
**Origin:** chat session, 2026-07-04 (George + Claude Fable 5)

---

## One-liner

An Android foreground service that owns the Pixel's microphone all day and distills the stream into VAD-gated, RAM-only speech segments for the rest of Bramble, with mute controls that provably work. It optimizes for one thing: capture you can trust — always on, never persisted.

## Context & Problem

B1 is the root of the Phase 1 (Second Ear) pipeline. Everything downstream — ASR routing (B2), the memory spine (B3), the cue engine (B5) — consumes what this service emits; if capture is unreliable, everything above it is fiction. Nothing exists today: the phone is a brick with a microphone.

Two failure modes would poison the whole project, not just this component. A capture layer that quietly persists raw audio violates Invariant #1 (ephemeral raw, persistent distilled) and turns a cognitive prosthesis into a wiretap with better marketing. And a mute that merely *discards* audio while keeping the mic hot is a mute that lies — wearer trust, once spent there, doesn't come back.

## Goals

1. Hold the microphone across a ≥ 8 h workday with zero service deaths (soak-verified).
2. Emit VAD-gated speech segments with pre-roll such that utterance-initial words are never clipped.
3. Prove ephemerality: after a 24 h soak, on-disk growth is metrics/config only — no raw audio at rest, ever.
4. Provide mute that releases the microphone (OS indicator disappears) in under 500 ms and survives reboot.
5. Emit metrics sufficient for experiment E2 to render battery/thermal verdicts without extra instrumentation.
6. Suspend and auto-resume cleanly around OS microphone contention (telephony) with the state visible to the wearer.

## Non-Goals

- No ASR, no transcription, no text of any kind — B2's jurisdiction begins where the segment flow ends.
- No persistence of audio or derived audio artifacts, including "temporary" files and crash dumps containing PCM.
- No hotword/wake-word detection.
- No diarization or speaker identity.
- No geofenced or scheduled auto-mute — B1 only exposes the `MuteController` API such features will call later.
- No telephony capture: modern Android silences third-party mic clients during calls; Bramble is deaf during calls by platform decree, and we do not fight the platform.
- No cross-device behaviour, no Wear OS, no UI beyond the QS tile, notification, and a minimal debug screen.

## Architecture / Approach

A single Kotlin foreground service (type `microphone`) drives a linear pipeline: `AudioRecord` → 90 s RAM ring buffer → continuous VAD → segmenter (debounce, hangover, pre/post-roll, cap-and-split) → bounded in-process delivery bus. Alongside the audio path: an `AmbientState` flow (is anyone speaking; are we muted) for future consumers, a `MuteController` API, a metrics emitter, and a state machine — CAPTURING / MUTED / SUSPENDED / THROTTLED / STOPPED — whose current state the persistent notification always reflects. Verification is its own component: a soak/audit/honesty test suite gates the brief.

Component boundaries are chosen so decomposition falls out naturally: service lifecycle, audio engine, VAD+segmentation, contracts+bus, mute surfaces, guardrails, metrics, verification.

## Decisions & Defaults

- **D1.** Implementation is native Kotlin on Android; no cross-platform framework. Audio plumbing and foreground-service rules punish abstraction layers.
- **D2.** Audio format is 16 kHz mono 16-bit PCM from `AudioSource.VOICE_RECOGNITION`; the source is a hot-reloadable config enum (`VOICE_RECOGNITION | MIC | UNPROCESSED`) so mic experiments can compare without rebuilds.
- **D3.** Raw PCM exists only in a 90 s RAM ring buffer and in RAM-resident segments. No code path may write PCM to disk, logs, crash reports, or persistent IPC. Enforced by review rule and by a storage-audit test that fails the whole build on violation.
- **D4.** VAD is Silero-class, running continuously on 30–60 ms frames. Speech-start requires a debounce window of consecutive speech frames; speech-end fires after a 1.0–1.2 s silence hangover. All thresholds are hot-reloadable config.
- **D5.** Segments carry 1.5 s pre-roll (from the ring buffer) and 0.5 s post-roll. Hard cap 30 s per segment; at cap, split at the nearest intra-speech pause and mark the successor `splitContinuation = true`.
- **D6.** Delivery is in-process via Kotlin `Flow`, bounded at 10 MB of queued PCM. On overflow, drop the OLDEST segments, count every drop in metrics, and show a notification badge when drops persist. Blocking the pipeline or failing silently is forbidden; audible, counted dropping is acceptable.
- **D7.** Mute releases the microphone entirely — the OS mic indicator must disappear — clears the ring buffer and all queued segments, takes effect in < 500 ms, and persists across process restart and reboot. Timed mutes auto-expire and announce resume with a soft earcon.
- **D8.** Mute surfaces: Quick Settings tile and notification actions ("Mute", "Mute 1 h") are guaranteed paths; an earbud media-button gesture is best-effort and hardware-dependent; `MuteController` (`mute(duration?)`, `unmute()`, `observeMuteState()`) is the programmatic path for future automation.
- **D9.** Detect OS mic contention via `AudioRecordingCallback` client-silencing; enter SUSPENDED within 1 s and auto-resume when the microphone is returned. Never contend with the OS or another app for the mic.
- **D10.** Service is `START_STICKY` with a WorkManager watchdog. In-flight RAM segments are lost on crash by design; that loss is acceptable and unlogged beyond a counter.
- **D11.** At thermal status SEVERE, suspend capture and notify the wearer; resume only after recovery plus a hysteresis interval. Do not degrade quality silently.
- **D12.** Boot: do not silently auto-start a microphone FGS (restricted since Android 15). Post a one-tap "Resume capture?" notification on `BOOT_COMPLETED`. If the installed OS version offers a compliant silent path, prefer it and record the choice in DECISIONS.md.
- **D13.** Every segment carries wall-clock epoch ms and monotonic `elapsedRealtime` ms for both start and end, plus mean VAD confidence, applied pre/post-roll, audio source, and a monotonic id.
- **D14.** `SpeechSegment.pcm` is reference-counted; consumers must `release()`. The 10 MB ceiling from D6 is enforced against un-released segments.
- **D15.** `AmbientState` flow exposes `{ speechActive, lastSpeechEndedAt, muteState (UNMUTED | MUTED_UNTIL(t) | MUTED_INDEFINITE), serviceState }` — consumers learn about quiet windows from this, never from audio.
- **D16.** Metrics are JSONL, metrics-only — never audio, never text: uptime, capture seconds, speech seconds, segments emitted/dropped, VAD duty cycle, state transitions, battery charge-counter snapshots, thermal transitions. Rotated daily, 10 MB total cap.
- **D17.** The persistent notification reflects the true service state within 1 s of any transition. The notification must never lie.
- **D18.** Onboarding requires the wearer to grant `RECORD_AUDIO` and unrestricted battery; the service declares `FOREGROUND_SERVICE_MICROPHONE` and refuses to start half-permissioned.
- **D19.** VAD runtime is sherpa-onnx unless it adds > 20 MB to the APK, in which case fall back to raw ONNX Runtime; measure the delta at build time and record the choice in DECISIONS.md.
- **D20.** VAD thresholds ship at Silero community defaults (~250 ms speech-start debounce, 1.0 s silence hangover); tune during experiment E1 and amend this brief with the tuned values.
- **D21.** Earbud mute gesture intercepts the media play/pause button only while no other MediaSession is active; if Pixel Buds Pro 2 gestures prove unworkable, ship QS-tile-and-notification-only without blocking release.
- **D22.** The debug screen is a single Activity showing live config values, current state, and a last-24 h metrics summary. No charts, no history browser.

## Open Questions

- None. Q1–Q4 were blessed at approval on 2026-07-04 and promoted to D19–D22.

## Acceptance Criteria

1. **Soak (G1):** 8 h continuous run in mixed real-world audio, zero service deaths; 24 h soak shows stable heap with no leak trend.
2. **Ephemerality audit (G3):** post-24 h-soak storage diff shows growth only in metrics and config. This criterion is pass/fail for the entire brief.
3. **Mute honesty (G4):** QS mute → OS mic indicator gone in < 500 ms, buffers cleared, muted state survives reboot; timed mute auto-resumes with earcon.
4. **First-word integrity (G2):** 20/20 scripted utterances beginning from silence are captured with intact onsets.
5. **Suspension (G6):** incoming call → SUSPENDED within 1 s; auto-resume after hangup; both transitions present in metrics and notification.
6. **Backpressure (G1):** artificially stalled consumer produces oldest-first drops, an accurate drop counter, a badge after sustained drops, and no OOM.
7. **Boot (G1/G4):** after reboot, the resume notification appears; one tap restores CAPTURING.
8. **Metrics sufficiency (G5):** E2's idle-draw and 25 %-speech-duty-draw numbers are computable from B1's metrics stream alone.

## Out of Scope (parking lot)

Geofenced mute zones (consume `MuteController` later). Hotword wake. Call capture via call-screening or accessibility APIs. Multi-mic beamforming. Adaptive VAD sensitivity by location or time. Wear OS companion controls. Any second user.
