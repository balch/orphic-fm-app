---
name: engine-slot-and-fastmath-nan
description: Two traps found in Task 24A fix round 1: a single reader counter livelocks an engine destroy, and -ffast-math folds NaN/inf checks even written as bit tests
metadata:
  type: reference
---

**Reader-count guard livelocks.** Guarding an engine destroy with one `readers` counter that the
destroyer waits to hit zero hung forever under three constantly-reading threads (seen under ASan,
2026-09-24). Use two epoch sides: the swap flips the epoch so new borrows count on the other side,
and the drained side only falls. That is `liborpheus_dsp/include/orpheus_engine_slot.h`
(`OrpheusEngineSlot`), which the Android `OboeEngine` uses for every JNI call. The audio thread stays
out of it: hosts destroy only after the stream is closed.

**-ffast-math eats NaN checks.** The library builds with `-ffast-math` in Release/RelWithDebInfo
(including Android). `!(x > 0)`, `std::isfinite`, and even a `memcpy` bit test
`(bits & 0x7f800000) == 0x7f800000` got folded (clang turns the bit test back into an FP-class test).
What works: an empty `__asm__ volatile("" : "+r"(bits))` between the memcpy and the test
(`scope_is_finite` in `orpheus_scope.h`). Always test argument checks in the Release build, not only Debug.

**Lifetime tests need a detectable destroy.** A stress over the real engine only proves anything under
ASan. `EngineSlot` is templated on the destroy function so tests retire a fake that sets `alive = false`
and keeps its memory. Teeth-check every such test with the wait removed: "saw the swap, then read
`replaced`" missed a no-wait slot (the replacer was still busy destroying); watch for 50 ms instead.

**Scope trigger scan must hold the repeat.** A chord's common period plus one crossing spacing must
fit in the scan, or reads that see only a partial repeat start on whichever crossing is newest and the
trace flips. A fifth needs three root periods; `kScanWindows` went 3 -> 4 (160 ms) for 20 Hz fifths.
A reviewer's "fake first slope" finding was real but unreachable (the oldest crossing is never a
steepest-pick candidate). Run the requested test before the fix to find the actual cause.

Related: [[lockfree-ring-stress-tests]]
