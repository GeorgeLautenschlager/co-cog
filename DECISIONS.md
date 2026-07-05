# Decisions

Standing decisions for co-cog / Bramble B1. Format: `date — decision — rationale`.
Grown by promotion of blessed ledger assumptions at review close.

- 2026-07-05 — CaptureService refuses a half-permissioned start via a plain `startService()`: it records the refusal (MetricsSink event + a test-visible flag) and `stopSelf()`s *before* any `startForeground()` or microphone access. Callers must not launch it via `startForegroundService()` while half-permissioned — a microphone-typed `startForeground()` itself requires `RECORD_AUDIO`, so no valid foreground call exists then; hardening that path is T2's when it wires the real start. — Establishes the D18 refusal contract the rest of the capture service builds on (promoted from T1 ledger L5).
