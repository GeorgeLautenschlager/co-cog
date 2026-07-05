# DECOMPOSITION: Bramble B1 — Capture Service & Mute Surfaces

**Brief:** BRIEF-b1-capture.md (Approved v1 — 2026-07-04)
**Status:** Approved — 2026-07-04
**Sizing rule:** one concern per task, ≤ 3 files touched, mechanically checkable done-condition.
**Tiering:** default implementer is the local model (dispatched via steward/Blueberry) with Opus oversight-by-verification. Exceptions annotated per task. T10 and T11 are Opus-implemented; T18 is local-implemented with Opus reviewing the audit logic line-by-line.
**Cross-cutting note:** a `MetricsSink` interface is defined in T1 with a logcat stub; all tasks emit through it; T16 replaces the stub. No task blocks on T16.

---

## Wave 1 — Skeleton

**T1 — App shell & permission gate** · carries D1, D18
Project skeleton, onboarding gate for `RECORD_AUDIO` + unrestricted battery, `MetricsSink` interface with logcat stub.
*Done when:* fresh install without grants shows a blocking onboarding screen; with grants, a start affordance appears; instrumented test proves the service refuses to start half-permissioned. · *Tier: local*

**T2 — Minimal microphone FGS** · carries D1, D18
Foreground service of type `microphone`, holds an open `AudioRecord` (no processing), static persistent notification.
*Done when:* `dumpsys` shows the FGS with microphone type; a 1 h idle run ends with the service alive and the notification present. · *Tier: local*

**T3 — State machine (pure module)** · carries D9, D10, D11, D12 semantics
CAPTURING / MUTED / SUSPENDED / THROTTLED / STOPPED as a transition table in plain Kotlin — zero `android.*` imports (lint-enforced).
*Done when:* unit tests cover every legal transition, reject every illegal one, and prove the MUTED-beats-everything property; branch coverage 100 % on the module. · *Tier: local*

**T4 — Notification truth-binding** · carries D17
Bind notification content to state-machine output.
*Done when:* instrumented test drives all states and asserts notification text reflects each within 1 s. · *Tier: local*

**T5 — Watchdog & sticky restart** · carries D10
`START_STICKY` + WorkManager watchdog + restart counter via MetricsSink.
*Done when:* scripted `kill -9` × 5 yields 5/5 restarts within 60 s, counter increments each time. · *Tier: local*

**T6 — Boot-resume flow** · carries D12
`BOOT_COMPLETED` receiver posting the one-tap "Resume capture?" notification; no silent mic-FGS start attempts.
*Done when:* device reboot produces the notification; tapping it reaches CAPTURING; log shows no restricted-start exception. · *Tier: local*

---

## Wave 2 — Audio path

**T7 — AudioRecord engine & ring buffer** · carries D2, D3, D13
16 kHz mono 16-bit capture from configurable source into a 90 s RAM ring buffer; wall + monotonic timestamps on reads.
*Done when:* ring-buffer unit tests pass including wrap-around; a 5-min instrumented capture shows zero app-storage growth (StrictMode + storage diff). · *Tier: local*

**T8 — VAD integration** · carries D4, D19, D20
Silero VAD via chosen runtime, continuous frames, speech/silence event stream.
*Done when:* the golden-WAV suite (hand-labelled clips) produces expected event sequences within ±150 ms; APK size delta recorded per D19. · *Tier: local*

**T9 — Segmenter (pure logic)** · carries D5
Debounce, hangover, 1.5 s pre-roll, 0.5 s post-roll, 30 s cap with pause-split and `splitContinuation` flag — plain Kotlin, no `android.*` imports.
*Done when:* property + regression tests pass, including cough-rejection, onset-integrity (first 100 ms of speech always present), and cap-split correctness. · *Tier: local*

**T10 — Segment contract & delivery bus** · carries D6, D13, D14, D15
`SpeechSegment` with ref-counted PCM, `AmbientState` flow, bounded delivery with oldest-drop backpressure at the 10 MB ceiling.
*Done when:* concurrent stress test (fast producer, stalled consumer) shows oldest-first drops with exact counts, no OOM; leak test proves unreleased segments trip the ceiling and released ones recycle. · *Tier: **Opus-implemented** — concurrency + ref-counting is not local-model territory.*

---

## Wave 3 — Controls

**T11 — Mute semantics core** · carries D7
Atomic mute: release AudioRecord (OS mic indicator gone), clear ring buffer and queued segments, persist state; timed-mute expiry with resume earcon.
*Done when:* mute latency measured < 500 ms; buffers provably empty post-mute; kill/restart and full reboot both preserve MUTED; crash injected mid-mute recovers to MUTED, never a half-state. · *Tier: **Opus-implemented** — half a mute is a lie, and atomicity bugs are how you tell it.*

**T12 — Mute surfaces: QS tile, notification, API** · carries D8
Quick Settings tile, "Mute"/"Mute 1 h" notification actions, `MuteController` — all through one shared code path.
*Done when:* all three surfaces produce identical state transitions (asserted shared-path test); tile reflects external state changes; timed mute auto-expires with earcon. · *Tier: local*

**T13 — Earbud gesture (best-effort)** · carries D21
Media play/pause interception only while no other MediaSession is active; clean feature-flag off if hardware won't cooperate.
*Done when:* with no media playing, tap toggles mute; with music playing, music is untouched; documented manual test matrix on Pixel Buds Pro 2. · *Tier: local*

**T14 — Contention suspend/resume** · carries D9
`AudioRecordingCallback` client-silencing detection → SUSPENDED ≤ 1 s → auto-resume on mic return.
*Done when:* real incoming call produces SUSPENDED within 1 s and auto-resume after hangup, both visible in state history and notification. · *Tier: local*

**T15 — Thermal policy** · carries D11
Suspend + notify at SEVERE; resume only after recovery + hysteresis. Policy as pure logic, thin Android binding.
*Done when:* unit tests on policy logic pass; instrumented smoke with mocked thermal service shows suspend/notify/resume sequence. · *Tier: local*

---

## Wave 4 — Observability & gate

**T16 — Metrics emitter** · carries D16
JSONL sink replacing the T1 stub: schema per D16, daily rotation, 10 MB cap, schema lint proving no audio/text fields can exist.
*Done when:* simulated 24 h run rotates at the day boundary and respects the cap; schema validation test passes. · *Tier: local*

**T17 — Debug screen** · carries D22
One Activity: live config values, current state, last-24 h metrics summary.
*Done when:* renders against the live service; contains no charts. · *Tier: local*

**T18 — Integration gate** · carries AC1–AC8
Automate/script all eight acceptance criteria: 8 h + 24 h soaks, storage audit (pass/fail for the brief), mute honesty, first-word integrity, call suspension, backpressure, boot, metrics sufficiency for E2.
*Done when:* a green run of the full suite is recorded and archived; AC2's audit logic has passed line-by-line Opus review. · *Tier: local-implemented, **Opus-reviewed** — the gate itself must not rubber-stamp.*

---

## Dependencies & dispatch order

T1 → T2 → {T3, T7} in sequence to start; T3 unblocks T4, T11, T14, T15; T7 → T8 → T9 → T10 is the audio chain; T11 needs T3 + T7; T12/T13 need T11; T16/T17 anytime after T1; T18 runs continuously and holds the exit door. Parallel-safe lanes for dispatch: {T4, T5, T6} after T3, {T14, T15} after T3+T2, {T12, T13} after T11.
