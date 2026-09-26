---
name: lockfree-ring-stress-tests
description: How to make a torn-read stress test for a lock-free DSP ring actually race (writer speed, index-encoded samples, teeth check)
metadata:
  type: reference
---

Found writing `liborpheus_dsp/test/test_scope.cpp` (Task 24A, 2026-09-24).

- A writer that calls `sin()` per sample is too slow to lap a 32k ring during a ~45 us read: the
  "stress" passed with 0 laps, proving nothing. Use a table-driven writer (a tone with an integer
  period, e.g. 120 Hz = 400 samples at 48 kHz) and print lapped counts per phase.
- Index-encoded samples (`float(i & 0xFFFFF)`, exact in float) make any torn copy a visible break.
- Always run the teeth check once: force the validation to `true`, confirm the stress fails, revert.
- Under Debug+ASan the reader is ~10x slower: assert "some intact reads" summed across phases, not per
  phase, or the forced-lap phase fails spuriously.

Related: [[ios-audio-verification-tasks]]
