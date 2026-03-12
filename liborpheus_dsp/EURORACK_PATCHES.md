# Eurorack Source Patches

Local patches applied to the sibling `eurorack` repo (`pichenettes/eurorack`).
These must be reapplied if the upstream source is ever re-cloned or updated.

**To apply all patches:**
```bash
cd /path/to/eurorack
git apply /path/to/orphic-fm-app/liborpheus_dsp/patches/*.patch
```

## 1. Clouds WSOLA Window::Start() — missing done_ reset

**File:** `clouds/dsp/window.h` — `Window::Start()`
**Date:** 2026-03-11
**Why:** `Window::Init()` sets `done_ = true`, but `Start()` never resets it.
`OverlapAdd()` checks `if (done_) return;` at the top, so started windows
never produce output — WSOLA Stretch mode is permanently silent.
On STM32 hardware this likely worked due to uninitialized memory state.

**Patch:**
```diff
 void Start(
     int32_t buffer_size,
     int32_t start,
     int32_t width,
     int32_t phase_increment) {
   first_sample_ = (start + buffer_size) % buffer_size;
   phase_increment_ = phase_increment;
   phase_ = 0;
+  done_ = false;
+  half_ = false;
   regenerated_ = false;
   envelope_phase_increment_ = 2.0f / static_cast<float>(width);
 }
```
