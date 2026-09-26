# DSP Bridge Agent Memory

## iOS Audio Host
- [iOS audio watchdog](project_ios_audio_watchdog.md) — closed-loop recovery invariant ("never stop polling"); nothing device-verified yet.
- [iOS audio verification tasks](reference_ios_audio_verification_tasks.md) — right Gradle link task names; djapp needs its own link; AVFAudio cinterop import rules.

## C++ Tests
- [Lock-free ring stress tests](reference_lockfree_ring_stress_tests.md) — sin() writers never lap; table-driven writer, index-encoded samples, teeth check.
- [Engine slot + fast-math NaN](reference_engine_slot_and_fastmath_nan.md) — single reader counter livelocks; -ffast-math folds NaN bit tests.
