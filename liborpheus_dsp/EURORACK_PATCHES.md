# Eurorack Source Patches

Local patches applied to the sibling `eurorack` repo (`pichenettes/eurorack`).
These must be reapplied if the upstream source is ever re-cloned or updated.

> Note: `stmlib` is a git submodule of eurorack — after a fresh clone run
> `git submodule update --init stmlib` or the CMake configure fails on
> `stmlib/dsp/atan.cc`.

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

## 2. Plaits user_data.h — missing <cstdio> under TEST

**File:** `plaits/user_data.h` — top of the `#ifdef TEST` block
**Date:** 2026-07-15
**Why:** Upstream HEAD (`08460a6`) uses `printf` in the mock flash helpers
without including `<cstdio>`. Firmware builds never define `TEST`, so
upstream doesn't notice; our desktop test build fails to compile.

**Patch:**
```diff
 #ifdef TEST

+#include <cstdio>
+
 // Mock flash saving functions for debugging purposes.
 #define PAGE_SIZE 0x800
```
