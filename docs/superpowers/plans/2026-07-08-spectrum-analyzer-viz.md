# Spectrum Analyzer Visualization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real-time spectrum-analyzer background visualization ("Spectrograph") that renders the master mix as log-frequency neon bars, driven by a genuine windowed FFT computed in C++.

**Architecture:** The audio thread taps the master mix per-sample into a new lock-free `SampleRing`. On the UI poll thread (~60 fps, never the audio thread) a `SpectrumAnalyzer` applies a Hann window, runs a borrowed `stmlib::ShyFFT`, folds magnitudes into N log-spaced bands, and hands them to Kotlin over a new `nativeGetSpectrum` bridge call. `SpectrumViz` (Compose) converts bands to dB, applies tilt + attack/decay ballistics with peak-hold, and draws the bars. The DSP math lives in C++; the eye-tuned "feel" lives in Kotlin.

**Tech Stack:** C++17 DSP engine, `stmlib::ShyFFT` (vendored, header-only, MIT), JNI/cinterop native bridge, Kotlin Multiplatform, Compose, Metro DI, kotlinx coroutines/Flow.

## Global Constraints

- **No new third-party dependency.** The FFT is `stmlib::ShyFFT<float, 2048, stmlib::RotationPhasor>` from the already-vendored `stmlib` (`$EURORACK_DIR/stmlib/fft/shy_fft.h`).
- **User-facing names must never reference Mutable Instruments or its modules.** The viz name is "Spectrograph". Internal code may reference FFT/ShyFFT freely.
- **FFT must never run on the audio thread.** The audio thread only does a single per-sample store into `SampleRing`. `Analyze()` runs on the poll thread.
- **Logging:** Kotlin uses KmLogging (`logging("Tag")`), never `println`. C++ tests use `printf` (existing test convention).
- **DI = Metro:** `@Inject`, `@ContributesIntoSet(FeatureScope::class, binding = binding<Visualization>())`.
- **Primary platform: JVM desktop** (fully implemented + tested here). Android is required for the DJ app (real JNI included). iOS uses cinterop. WASM viz is already a no-op stub — the spectrum viz will show floor on WASM, consistent with all existing viz; only compilation is required there.
- **Commit after every task.**
- **C++ build/test command** (from repo root):
  ```
  cmake -S liborpheus_dsp -B liborpheus_dsp/build-desktop -DEURORACK_DIR=$EURORACK_DIR -DBUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXPORT_COMPILE_COMMANDS=ON && cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && liborpheus_dsp/build-desktop/orpheus_dsp_test spectrum
  ```

---

### Task 1: C++ SpectrumAnalyzer + SampleRing (DSP core, TDD)

**Files:**
- Create: `liborpheus_dsp/src/orpheus_spectrum.h`
- Create: `liborpheus_dsp/src/orpheus_spectrum.cc`
- Create: `liborpheus_dsp/test/test_spectrum.cpp`
- Modify: `liborpheus_dsp/CMakeLists.txt` (add both new `.cc`/test files to their source lists)
- Modify: `liborpheus_dsp/test/test_main.cpp` (register the `spectrum` suite)

**Interfaces:**
- Produces:
  - `struct SampleRing { static constexpr int kSize = 4096; float buf[kSize]; void write(float); std::atomic<uint32_t> write_count; }`
  - `class SpectrumAnalyzer { void Init(float sample_rate); void Analyze(const SampleRing&, float* bands, int n); static constexpr int kFftSize = 2048; static constexpr float kFMin = 30.0f, kFMax = 16000.0f; }`

- [ ] **Step 1: Create the header `liborpheus_dsp/src/orpheus_spectrum.h`**

```cpp
#pragma once
#include <atomic>
#include <cstdint>
#include "stmlib/fft/shy_fft.h"

// Lock-free per-sample ring for spectrum analysis.
// Audio thread writes EVERY sample via write(); UI/poll thread reads the newest
// window in Analyze(). kSize is 2x the FFT window so the reader's window never
// collides with the writer's current slot.
struct SampleRing {
    static constexpr int kSize = 4096;
    float buf[kSize] = {};
    std::atomic<uint32_t> write_count{0};

    inline void write(float s) {
        uint32_t wc = write_count.load(std::memory_order_relaxed);
        buf[wc % kSize] = s;
        write_count.store(wc + 1, std::memory_order_release);
    }
};

// Real-FFT spectrum analyzer. Init() once (allocates nothing at Analyze time).
// Analyze() must run OFF the audio thread (it is called from the UI poll bridge).
class SpectrumAnalyzer {
 public:
    static constexpr int   kFftSize = 2048;
    static constexpr float kFMin = 30.0f;      // Hz, lowest band edge
    static constexpr float kFMax = 16000.0f;   // Hz, highest band edge

    void Init(float sample_rate);
    // Reads the newest kFftSize samples from `ring`, windows + FFTs them, folds
    // magnitudes into `n` log-spaced bands over [kFMin, kFMax]. Writes linear
    // magnitude to bands[0..n).
    void Analyze(const SampleRing& ring, float* bands, int n);

 private:
    stmlib::ShyFFT<float, kFftSize, stmlib::RotationPhasor> fft_;
    float window_[kFftSize];
    float fft_in_[kFftSize];
    float fft_out_[kFftSize];
    float sample_rate_ = 48000.0f;
    bool  initialized_ = false;
};
```

- [ ] **Step 2: Create the implementation `liborpheus_dsp/src/orpheus_spectrum.cc`**

```cpp
#include "orpheus_spectrum.h"
#include <cmath>
#include <algorithm>

namespace {
constexpr float kPi = 3.14159265358979f;
}

void SpectrumAnalyzer::Init(float sample_rate) {
    sample_rate_ = sample_rate;
    fft_.Init();
    for (int i = 0; i < kFftSize; ++i) {
        window_[i] = 0.5f * (1.0f - std::cos(2.0f * kPi * i / (kFftSize - 1)));  // Hann
    }
    initialized_ = true;
}

void SpectrumAnalyzer::Analyze(const SampleRing& ring, float* bands, int n) {
    if (!initialized_ || !bands || n <= 0) return;

    // Copy the newest kFftSize samples and apply the Hann window.
    uint32_t wc = ring.write_count.load(std::memory_order_acquire);
    uint32_t start = wc - static_cast<uint32_t>(kFftSize);
    for (int i = 0; i < kFftSize; ++i) {
        fft_in_[i] = ring.buf[(start + i) % SampleRing::kSize] * window_[i];
    }

    // Forward real FFT. NOTE: Direct() uses fft_in_ as scratch (clobbers it).
    fft_.Direct(fft_in_, fft_out_);

    // ShyFFT (de Soras FFTReal packing): for bin k in [1, N/2-1],
    //   real[k] = fft_out_[k], imag[k] = fft_out_[N/2 + k].
    // (bin 0 = DC at fft_out_[0]; bin N/2 = Nyquist at fft_out_[N/2] — both skipped;
    //  our 30 Hz..16 kHz range lives entirely inside [1, N/2-1].)
    const int   half   = kFftSize / 2;             // 1024
    const float bin_hz = sample_rate_ / kFftSize;  // ~23.4 Hz @ 48 kHz
    const float ratio  = kFMax / kFMin;

    for (int b = 0; b < n; ++b) {
        float f_lo = kFMin * std::pow(ratio, static_cast<float>(b)     / n);
        float f_hi = kFMin * std::pow(ratio, static_cast<float>(b + 1) / n);
        int k_lo = std::max(1, static_cast<int>(std::floor(f_lo / bin_hz)));
        int k_hi = std::min(half - 1, static_cast<int>(std::ceil(f_hi / bin_hz)));
        if (k_hi < k_lo) k_hi = k_lo;  // very low bands cover < 1 bin -> take nearest

        float peak = 0.0f;
        for (int k = k_lo; k <= k_hi; ++k) {
            float re = fft_out_[k];
            float im = fft_out_[half + k];
            float mag = std::sqrt(re * re + im * im);
            if (mag > peak) peak = mag;
        }
        bands[b] = peak * (2.0f / kFftSize);  // normalize to amplitude-ish scale
    }
}

// C API — defined here so all spectrum code lives together. Declared in Task 3.
#include "../include/orpheus_dsp.h"
#include "orpheus_engine.h"
int orpheus_engine_get_spectrum(OrpheusEngine* engine, float* bands, int num_bands) {
    if (!engine || !bands || num_bands <= 0) return 0;
    engine->spectrum_analyzer.Analyze(engine->spectrum_ring, bands, num_bands);
    return num_bands;
}
```

Note: the `orpheus_engine_get_spectrum` body references `engine->spectrum_analyzer` / `engine->spectrum_ring`, which are added in Task 2, and the declaration is added in Task 3. To keep Task 1 self-contained and compiling, **temporarily comment out the C API block** (the `#include "../include/orpheus_dsp.h"` block to end of file) for Task 1, and uncomment it in Task 3. The analyzer class + tests fully compile and pass on their own.

- [ ] **Step 3: Write the failing test `liborpheus_dsp/test/test_spectrum.cpp`**

```cpp
#include "test_harness.h"
#include "../src/orpheus_spectrum.h"
#include <cmath>
#include <cstdio>

static bool test_spectrum_sine_peaks_in_correct_band() {
    printf("\n=== Test: 1 kHz sine peaks in the band containing 1 kHz ===\n");
    const float sr = 48000.0f;
    const float freq = 1000.0f;
    SampleRing ring;
    for (int i = 0; i < SampleRing::kSize; ++i) {
        ring.write(std::sin(2.0f * 3.14159265f * freq * i / sr));
    }
    SpectrumAnalyzer analyzer;
    analyzer.Init(sr);
    const int N = 40;
    float bands[N];
    analyzer.Analyze(ring, bands, N);

    int argmax = 0;
    for (int b = 1; b < N; ++b) if (bands[b] > bands[argmax]) argmax = b;

    const float ratio = SpectrumAnalyzer::kFMax / SpectrumAnalyzer::kFMin;
    float f_lo = SpectrumAnalyzer::kFMin * std::pow(ratio, (float)argmax / N);
    float f_hi = SpectrumAnalyzer::kFMin * std::pow(ratio, (float)(argmax + 1) / N);
    printf("  argmax band=%d range=[%.0f,%.0f] Hz mag=%.4f\n", argmax, f_lo, f_hi, bands[argmax]);

    bool pass = (freq >= f_lo * 0.85f && freq <= f_hi * 1.15f);
    printf(pass ? "  PASS\n" : "  FAIL: 1 kHz not in peak band (check ShyFFT imag index)\n");
    return pass;
}

static bool test_spectrum_silence_is_floor() {
    printf("\n=== Test: silence -> near-zero bands ===\n");
    SampleRing ring;  // zero-initialized
    SpectrumAnalyzer analyzer;
    analyzer.Init(48000.0f);
    const int N = 40;
    float bands[N];
    analyzer.Analyze(ring, bands, N);
    float maxv = 0.0f;
    for (int b = 0; b < N; ++b) if (bands[b] > maxv) maxv = bands[b];
    bool pass = maxv < 1e-4f;
    printf("  max band on silence = %.6f  %s\n", maxv, pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_spectrum_no_nan_on_full_scale() {
    printf("\n=== Test: full-scale broadband -> finite bands ===\n");
    SampleRing ring;
    // deterministic pseudo-noise, full scale
    uint32_t s = 22222u;
    for (int i = 0; i < SampleRing::kSize; ++i) {
        s = s * 1664525u + 1013904223u;
        ring.write(((s >> 8) / 8388608.0f) - 1.0f);  // ~[-1,1)
    }
    SpectrumAnalyzer analyzer;
    analyzer.Init(48000.0f);
    const int N = 64;
    float bands[N];
    analyzer.Analyze(ring, bands, N);
    bool pass = true;
    for (int b = 0; b < N; ++b) {
        if (!std::isfinite(bands[b])) { pass = false; printf("  FAIL: band %d not finite\n", b); }
    }
    if (pass) printf("  PASS (all %d bands finite)\n", N);
    return pass;
}

bool run_spectrum_tests() {
    int p = 0, f = 0;
    auto tally = [&](bool ok) { if (ok) ++p; else ++f; };
    tally(test_spectrum_sine_peaks_in_correct_band());
    tally(test_spectrum_silence_is_floor());
    tally(test_spectrum_no_nan_on_full_scale());
    TEST_SUITE_RETURN(p, f);
}
```

- [ ] **Step 4: Register the suite in `liborpheus_dsp/test/test_main.cpp`**

Add the forward declaration immediately above the `TestSuite` table (search for `struct TestSuite {`), and add a table row next to the existing `{"viz", run_viz_tests, true},` line:

```cpp
bool run_spectrum_tests();   // add above the TestSuite table
```
```cpp
    {"spectrum",        run_spectrum_tests,        true},   // add inside the table
```

- [ ] **Step 5: Wire the new files into `liborpheus_dsp/CMakeLists.txt`**

Add the engine source to the `ORPHEUS_SRC` list (near `"src/orpheus_resonator.cpp"`):
```cmake
    "src/orpheus_spectrum.cc"
```
Add the test to the test-sources list. Locate it with `grep -n "test/test_lfo.cpp" liborpheus_dsp/CMakeLists.txt`, then add next to it:
```cmake
    "test/test_spectrum.cpp"
```

- [ ] **Step 6: Build and run — verify tests pass**

Run:
```
cmake -S liborpheus_dsp -B liborpheus_dsp/build-desktop -DEURORACK_DIR=$EURORACK_DIR -DBUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXPORT_COMPILE_COMMANDS=ON && cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && liborpheus_dsp/build-desktop/orpheus_dsp_test spectrum
```
Expected: `spectrum` suite runs; all three tests PASS. If `test_spectrum_sine_peaks_in_correct_band` FAILS, flip the imaginary index in `orpheus_spectrum.cc` from `fft_out_[half + k]` to `fft_out_[kFftSize - k]` and re-run (this is the one packing ambiguity the test exists to catch).

- [ ] **Step 7: Commit**

```bash
git add liborpheus_dsp/src/orpheus_spectrum.h liborpheus_dsp/src/orpheus_spectrum.cc liborpheus_dsp/test/test_spectrum.cpp liborpheus_dsp/test/test_main.cpp liborpheus_dsp/CMakeLists.txt
git commit -m "feat(dsp): SpectrumAnalyzer (ShyFFT) + SampleRing with tests"
```

---

### Task 2: Engine integration (sample tap + members + init)

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h` (add include + two members, after line 1138)
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp` (`orpheus_engine_create`: call `spectrum_analyzer.Init`)
- Modify: `liborpheus_dsp/src/orpheus_unit_basic.cpp` (per-sample tap + mute-path zero-fill)

**Interfaces:**
- Consumes: `SampleRing`, `SpectrumAnalyzer` (Task 1).
- Produces: `OrpheusEngine::spectrum_ring`, `OrpheusEngine::spectrum_analyzer` members.

- [ ] **Step 1: Add include + members in `liborpheus_dsp/src/orpheus_engine.h`**

Near the other engine includes at the top of the file, add:
```cpp
#include "orpheus_spectrum.h"
```
Then add the members immediately after the `VizRing viz_rings[VIZ_CHANNEL_COUNT];` line (line 1138), before the struct's closing `};`:
```cpp
    // ── Spectrum analyzer (master-mix FFT for the Spectrograph viz) ──
    // Audio thread writes every sample into spectrum_ring; UI poll thread runs
    // spectrum_analyzer.Analyze() off the audio thread.
    SampleRing       spectrum_ring;
    SpectrumAnalyzer spectrum_analyzer;
```

- [ ] **Step 2: Initialize the analyzer in `liborpheus_dsp/src/orpheus_engine.cpp`**

In `orpheus_engine_create(float sample_rate)`, after `engine->sample_rate = sample_rate;` (and near the other `.Init(..., sample_rate)` calls), add:
```cpp
    engine->spectrum_analyzer.Init(sample_rate);
```

- [ ] **Step 3: Add the per-sample master tap in `liborpheus_dsp/src/orpheus_unit_basic.cpp`**

In `unit_process_master_out`, inside the main per-sample loop, right after the soft-sat lines (`l = soft_sat(l); r = soft_sat(r);` at ~line 309-310) and before `output_buffer[i * 2] = l;`, add:
```cpp
        engine->spectrum_ring.write((l + r) * 0.5f);
```
Also handle the muted early-out path (the `if (engine->smooth_global_mute < 0.0001f && mute_target < 0.5f)` branch at ~line 222). Before its `return;` (after the existing `engine->viz_rings[VIZ_MASTER_OUT].write(0.0f);` at line 227), feed silence so the spectrum decays to floor instead of freezing:
```cpp
        for (int i = 0; i < n; ++i) engine->spectrum_ring.write(0.0f);
```

- [ ] **Step 4: Build and verify no regressions**

Run:
```
cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && liborpheus_dsp/build-desktop/orpheus_dsp_test
```
Expected: full build succeeds; the entire suite (including `spectrum`) passes with no regressions.

- [ ] **Step 5: Commit**

```bash
git add liborpheus_dsp/src/orpheus_engine.h liborpheus_dsp/src/orpheus_engine.cpp liborpheus_dsp/src/orpheus_unit_basic.cpp
git commit -m "feat(dsp): tap master mix into spectrum_ring; init analyzer"
```

---

### Task 3: C API export + desktop native bridge

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_spectrum.cc` (uncomment the C API block from Task 1)
- Modify: `liborpheus_dsp/include/orpheus_dsp.h` (declare `orpheus_engine_get_spectrum`, after line 113)
- Modify: `liborpheus_dsp/desktop/DesktopEngine.h` (declare `getSpectrum`, near line 54)
- Modify: `liborpheus_dsp/desktop/DesktopEngine.cpp` (implement `getSpectrum`, near line 172)
- Modify: `liborpheus_dsp/desktop/jni_bridge_desktop.cpp` (`nativeGetSpectrum` JNI, near line 209)

**Interfaces:**
- Produces: `int orpheus_engine_get_spectrum(OrpheusEngine*, float* bands, int num_bands)`; `DesktopEngine::getSpectrum(float*, int)`; JNI `nativeGetSpectrum`.

- [ ] **Step 1: Declare the C API in `liborpheus_dsp/include/orpheus_dsp.h`**

Directly below the `orpheus_engine_get_viz(...)` declaration (line ~112-113), add:
```c
// ── Spectrum analyzer (polled at ~60fps from UI thread; FFT runs here, off audio thread) ──
// Fills bands[0..num_bands) with linear magnitude per log-spaced band. Returns num_bands.
int  orpheus_engine_get_spectrum(OrpheusEngine* engine, float* bands, int num_bands);
```

- [ ] **Step 2: Uncomment the C API block in `liborpheus_dsp/src/orpheus_spectrum.cc`**

Re-enable the `orpheus_engine_get_spectrum` definition added (and commented) in Task 1, Step 2. It now compiles because the declaration (Step 1) and engine members (Task 2) exist.

- [ ] **Step 3: Add the DesktopEngine wrapper**

In `liborpheus_dsp/desktop/DesktopEngine.h` near the `getViz` declaration (line 54):
```cpp
    int   getSpectrum(float* bands, int numBands);
```
In `liborpheus_dsp/desktop/DesktopEngine.cpp` near `getViz` (line ~172):
```cpp
int DesktopEngine::getSpectrum(float* bands, int numBands) {
    return dsp_engine_ ? orpheus_engine_get_spectrum(dsp_engine_, bands, numBands) : 0;
}
```

- [ ] **Step 4: Add the desktop JNI function in `liborpheus_dsp/desktop/jni_bridge_desktop.cpp`**

Next to `JNI_FN(nativeGetViz)` (line ~209):
```cpp
JNIEXPORT jint JNICALL
JNI_FN(nativeGetSpectrum)(JNIEnv *env, jobject thiz, jfloatArray bands) {
    jfloat* buf = env->GetFloatArrayElements(bands, nullptr);
    int count = sEngine.getSpectrum(buf, env->GetArrayLength(bands));
    env->ReleaseFloatArrayElements(bands, buf, 0);
    return count;
}
```

- [ ] **Step 5: Build and verify**

Run:
```
cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && liborpheus_dsp/build-desktop/orpheus_dsp_test spectrum
```
Expected: builds and `spectrum` suite still passes (C API compiles into the library).

- [ ] **Step 6: Commit**

```bash
git add liborpheus_dsp/include/orpheus_dsp.h liborpheus_dsp/src/orpheus_spectrum.cc liborpheus_dsp/desktop/DesktopEngine.h liborpheus_dsp/desktop/DesktopEngine.cpp liborpheus_dsp/desktop/jni_bridge_desktop.cpp
git commit -m "feat(dsp): orpheus_engine_get_spectrum C API + desktop JNI"
```

---

### Task 4: Kotlin native bridge (all platforms)

**Files:**
- Modify: `core/audio/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/NativeDspBridge.kt:42` (interface method)
- Modify: `core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/DesktopDspBridge.kt:105` (external)
- Modify: `core/audio/src/androidMain/kotlin/org/balch/orpheus/core/audio/dsp/OboeAudioBridge.kt:54` (external)
- Modify: `core/audio/src/iosMain/kotlin/org/balch/orpheus/core/audio/dsp/IosAudioEngine.kt:431` (cinterop override)
- Modify: `apps/orpheus/shared/src/wasmJsMain/kotlin/org/balch/orpheus/core/audio/dsp/WasmNativeAudioEngine.kt:191` (stub)
- Modify: `apps/orpheus/androidApp/src/main/cpp/jni_bridge.cpp:246` (Android JNI)
- Modify: `apps/orpheus/androidApp/src/main/cpp/OboeEngine.h:56` + its `.cpp` (`getSpectrum`)

**Interfaces:**
- Consumes: `orpheus_engine_get_spectrum` (Task 3).
- Produces: `NativeDspBridge.nativeGetSpectrum(bands: FloatArray): Int`.

- [ ] **Step 1: Add to the common interface `NativeDspBridge.kt`** (next to `nativeGetViz`):
```kotlin
    fun nativeGetSpectrum(bands: FloatArray): Int
```

- [ ] **Step 2: JVM `DesktopDspBridge.kt`** (next to `nativeGetViz`):
```kotlin
    external fun nativeGetSpectrum(bands: FloatArray): Int
```

- [ ] **Step 3: Android `OboeAudioBridge.kt`** (next to `nativeGetViz`):
```kotlin
    external fun nativeGetSpectrum(bands: FloatArray): Int
```

- [ ] **Step 4: iOS `IosAudioEngine.kt`** — add the cinterop import `import orpheus_dsp.orpheus_engine_get_spectrum` and, next to `nativeGetViz`:
```kotlin
    override fun nativeGetSpectrum(bands: FloatArray): Int {
        return engine?.let { eng ->
            bands.usePinned { pinned ->
                orpheus_engine_get_spectrum(eng, pinned.addressOf(0), bands.size)
            }
        } ?: 0
    }
```

- [ ] **Step 5: WASM `WasmNativeAudioEngine.kt`** (stub, mirrors the existing `nativeGetViz = 0`):
```kotlin
    override fun nativeGetSpectrum(bands: FloatArray): Int = 0
```

- [ ] **Step 6: Android JNI `apps/orpheus/androidApp/src/main/cpp/jni_bridge.cpp`** (next to the `nativeGetViz` JNI at line 246):
```cpp
JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetSpectrum(
        JNIEnv *env, jobject thiz, jfloatArray bands) {
    jfloat* buf = env->GetFloatArrayElements(bands, nullptr);
    int count = sEngine.getSpectrum(buf, env->GetArrayLength(bands));
    env->ReleaseFloatArrayElements(bands, buf, 0);
    return count;
}
```
And in `apps/orpheus/androidApp/src/main/cpp/OboeEngine.h` (near line 56):
```cpp
    int  getSpectrum(float* bands, int numBands);
```
And in the matching `OboeEngine.cpp`, mirror the desktop wrapper:
```cpp
int OboeEngine::getSpectrum(float* bands, int numBands) {
    return dsp_engine_ ? orpheus_engine_get_spectrum(dsp_engine_, bands, numBands) : 0;
}
```
(Use the same member name this file already uses for the engine pointer — match `getViz`'s implementation in the same file.)

- [ ] **Step 7: Compile-check common + JVM**

Run:
```
./gradlew compileKotlinJvm
```
Expected: BUILD SUCCESSFUL (the interface + all `actual`/override implementations satisfy the contract).

- [ ] **Step 8: Commit**

```bash
git add core/audio apps/orpheus/shared apps/orpheus/androidApp/src/main/cpp
git commit -m "feat(audio): nativeGetSpectrum bridge across all platforms"
```

---

### Task 5: Kotlin monitor flow + gating

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/SynthEngineMonitor.kt`
- Modify: `core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/audio/SynthEngine.kt`
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DspSynthEngine.kt`

**Interfaces:**
- Consumes: `nativeBridge.nativeGetSpectrum(FloatArray)` (Task 4).
- Produces: `SynthEngine.spectrumFlow: StateFlow<FloatArray>`, `SynthEngine.setSpectrumEnabled(Boolean)`.

Spectrum polling is a UI-only poll exactly like the signal scope, so it must be
hooked into every gating touchpoint the scope uses: its own enable function,
`startMonitoring()`, both branches of `setUiVisible()`, and the teardown cancel
block. `spectrumRequested` must have the **same visibility as `vizRequested`**
(it is read from `DspSynthEngine`), so it is NOT `private`.

- [ ] **Step 1a: Declarations.** Near the other `_*VizFlow` declarations:
```kotlin
    private val _spectrumFlow = MutableStateFlow(FloatArray(0))
    val spectrumFlow: StateFlow<FloatArray> = _spectrumFlow.asStateFlow()
    private var spectrumJob: Job? = null
    var spectrumRequested = false   // matches vizRequested's visibility (read by DspSynthEngine)
```
Add the band-count constant to the companion (near `VIZ_POLL_INTERVAL_MS`):
```kotlin
        const val SPECTRUM_BAND_COUNT = 40
```

- [ ] **Step 1b: Enable + poll functions** (mirror `setVizEnabled`/`launchVizPoll`), guarded by `pollLock`:
```kotlin
    /** Enable/disable spectrum FFT polling. Only poll while Spectrograph is active. */
    fun setSpectrumEnabled(enabled: Boolean, isRunning: Boolean): Unit = synchronized(pollLock) {
        spectrumRequested = enabled
        if (enabled && isRunning && uiVisible) {
            launchSpectrumPoll()
        } else if (!enabled) {
            spectrumJob?.cancel()
            spectrumJob = null
            _spectrumFlow.value = FloatArray(0)
        }
    }

    private fun launchSpectrumPoll() {
        if (spectrumJob != null) return
        spectrumJob = monitoringScope.launch(dispatcherProvider.io) {
            while (isActive) {
                val bands = FloatArray(SPECTRUM_BAND_COUNT)
                nativeBridge.nativeGetSpectrum(bands)
                _spectrumFlow.value = bands
                delay(VIZ_POLL_INTERVAL_MS)
            }
        }
    }
```

- [ ] **Step 1c: `startMonitoring()`** — inside the existing `if (uiVisible) { ... }` block (after `if (vizRequested) launchVizPoll()`, ~line 227) add:
```kotlin
            if (spectrumRequested) launchSpectrumPoll()
```

- [ ] **Step 1d: `setUiVisible()`** — in the `if (visible)` / `if (monitoringActive)` branch (after `if (vizRequested) launchVizPoll()`, ~line 253) add:
```kotlin
                if (spectrumRequested) launchSpectrumPoll()
```
and in the `else` (hidden) branch (after `vizJob?.cancel(); vizJob = null`, ~line 266) add:
```kotlin
            spectrumJob?.cancel()
            spectrumJob = null
```

- [ ] **Step 1e: Teardown** — in the cancel-all block (~line 415-423, where `turntableVizJob`/`pulsarVizJob`/`monitoringJob` are cancelled) add:
```kotlin
        spectrumJob?.cancel()
        spectrumJob = null
```

- [ ] **Step 2: Expose on the `SynthEngine` interface (`SynthEngine.kt`)**

Near the other `*VizFlow` declarations (~line 216-248):
```kotlin
    val spectrumFlow: StateFlow<FloatArray> get() = emptyVizFlow
```
Near `fun setVizEnabled(enabled: Boolean) {}` (line 260):
```kotlin
    fun setSpectrumEnabled(enabled: Boolean) {}
```

- [ ] **Step 3: Implement in `DspSynthEngine.kt`**

Near the other viz-flow delegations (~line 89-131):
```kotlin
    override val spectrumFlow: StateFlow<FloatArray> get() = monitor.spectrumFlow
```
Near `override fun setVizEnabled(...)` (line 466):
```kotlin
    override fun setSpectrumEnabled(enabled: Boolean) {
        monitor.setSpectrumEnabled(enabled, audioEngine.isRunning)
    }
```
In the engine-start re-arm block (near line 445, after the `if (monitor.vizRequested) monitor.setVizEnabled(true, true)`):
```kotlin
        if (monitor.spectrumRequested) {
            monitor.setSpectrumEnabled(true, true)
        }
```
(`spectrumRequested` is declared non-private in Step 1a to match `vizRequested`, so `monitor.spectrumRequested` is readable here — exactly as `monitor.vizRequested` is read on the line above.)

- [ ] **Step 4: Compile-check**

Run:
```
./gradlew compileKotlinJvm
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/dsp-engine core/foundation
git commit -m "feat(audio): spectrumFlow + setSpectrumEnabled gating in monitor"
```

---

### Task 6: Kotlin feel functions (dB, tilt, ballistics) — TDD

**Files:**
- Create: `features/visualizations/src/commonMain/kotlin/org/balch/orpheus/features/visualizations/viz/SpectrumBallistics.kt`
- Create: `features/visualizations/src/jvmTest/kotlin/org/balch/orpheus/features/visualizations/viz/SpectrumBallisticsTest.kt`

**Interfaces:**
- Produces:
  - `fun magnitudeToHeight(mag: Float, floorDb: Float = -70f, ceilDb: Float = 0f): Float`
  - `fun applyTilt(mag: Float, bandIndex: Int, bandCount: Int, tiltKnob: Float): Float`
  - `class SpectrumBallistics(bandCount: Int) { val heights: FloatArray; val peaks: FloatArray; fun update(targets: FloatArray, decayKnob: Float) }`

- [ ] **Step 1: Write the failing tests `SpectrumBallisticsTest.kt`**

```kotlin
package org.balch.orpheus.features.visualizations.viz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectrumBallisticsTest {
    @Test fun `magnitude at or below floor maps to zero`() {
        assertEquals(0f, magnitudeToHeight(0f), 1e-4f)
    }

    @Test fun `unity magnitude maps to full height`() {
        assertEquals(1f, magnitudeToHeight(1f), 1e-4f) // 20*log10(1) = 0 dB = ceil
    }

    @Test fun `tilt lifts high bands and leaves the lowest band unchanged`() {
        val low = applyTilt(1f, 0, 40, tiltKnob = 1f)
        val high = applyTilt(1f, 39, 40, tiltKnob = 1f)
        assertEquals(1f, low, 1e-4f)
        assertTrue(high > low, "top band should be boosted above the bottom band")
    }

    @Test fun `ballistics attack is instant`() {
        val b = SpectrumBallistics(3)
        b.update(floatArrayOf(1f, 1f, 1f), decayKnob = 0.5f)
        assertEquals(1f, b.heights[0], 1e-4f)
    }

    @Test fun `ballistics decay falls gradually and peak-hold falls slower`() {
        val b = SpectrumBallistics(1)
        b.update(floatArrayOf(1f), decayKnob = 0.5f)   // jump up
        b.update(floatArrayOf(0f), decayKnob = 0.5f)   // start falling
        assertTrue(b.heights[0] in 0.01f..0.99f, "height should fall partway, not snap to 0")
        assertTrue(b.peaks[0] > b.heights[0], "peak-hold cap should lag above the bar")
    }
}
```

- [ ] **Step 2: Run the tests — verify they fail**

Run:
```
./gradlew :features:visualizations:jvmTest --tests "org.balch.orpheus.features.visualizations.viz.SpectrumBallisticsTest"
```
Expected: FAIL — unresolved references (`magnitudeToHeight`, `applyTilt`, `SpectrumBallistics`).

- [ ] **Step 3: Implement `SpectrumBallistics.kt`**

```kotlin
package org.balch.orpheus.features.visualizations.viz

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Linear magnitude -> normalized 0..1 bar height via a dB window. */
fun magnitudeToHeight(mag: Float, floorDb: Float = -70f, ceilDb: Float = 0f): Float {
    val db = 20f * log10(mag + 1e-9f)
    return ((db - floorDb) / (ceilDb - floorDb)).coerceIn(0f, 1f)
}

/** Spectral tilt: lift highs to counter music's natural HF rolloff. 0 at band 0. */
fun applyTilt(mag: Float, bandIndex: Int, bandCount: Int, tiltKnob: Float): Float {
    if (bandCount <= 1) return mag
    val tiltDb = tiltKnob * 9f * (bandIndex.toFloat() / (bandCount - 1))
    return mag * 10f.pow(tiltDb / 20f)
}

/**
 * Fast-attack / slow-decay bar ballistics with peak-hold caps. Mutates in place;
 * owned by the Compose frame loop (single-threaded main). decayKnob 0..1 = fall speed.
 */
class SpectrumBallistics(val bandCount: Int) {
    val heights = FloatArray(bandCount)
    val peaks = FloatArray(bandCount)

    fun update(targets: FloatArray, decayKnob: Float) {
        val fall = 0.02f + decayKnob * 0.10f   // 0.02..0.12 per frame
        val peakFall = fall * 0.25f
        val n = minOf(bandCount, targets.size)
        for (i in 0 until n) {
            val t = targets[i]
            heights[i] = if (t >= heights[i]) t else max(t, heights[i] - fall)
            peaks[i] = if (t >= peaks[i]) t else max(heights[i], peaks[i] - peakFall)
        }
    }
}
```

- [ ] **Step 4: Run the tests — verify they pass**

Run:
```
./gradlew :features:visualizations:jvmTest --tests "org.balch.orpheus.features.visualizations.viz.SpectrumBallisticsTest"
```
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add features/visualizations/src/commonMain/kotlin/org/balch/orpheus/features/visualizations/viz/SpectrumBallistics.kt features/visualizations/src/jvmTest/kotlin/org/balch/orpheus/features/visualizations/viz/SpectrumBallisticsTest.kt
git commit -m "feat(viz): spectrum feel functions (dB, tilt, ballistics) + tests"
```

---

### Task 7: SpectrumViz composable + DI registration

**Files:**
- Create: `features/visualizations/src/commonMain/kotlin/org/balch/orpheus/features/visualizations/viz/SpectrumViz.kt`

**Interfaces:**
- Consumes: `SynthEngine.spectrumFlow` (Task 5); `magnitudeToHeight`, `applyTilt`, `SpectrumBallistics` (Task 6).
- Produces: a `Visualization` with `id = "spectrum"` contributed into the DI set.

- [ ] **Step 1: Create `SpectrumViz.kt`**

```kotlin
package org.balch.orpheus.features.visualizations.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Visualization

/**
 * Spectrograph — real-time master-mix spectrum analyzer. Neon vertical bars with
 * peak-hold caps. The FFT is computed in C++; this only shapes and draws the bands.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<Visualization>())
class SpectrumViz(
    private val engine: SynthEngine,
) : Visualization {

    override val id = "spectrum"
    override val name = "Spectrograph"
    override val color = OrpheusColors.neonMagenta
    override val knob1Label = "DECAY"
    override val knob2Label = "TILT"

    private var _decay = 0.5f
    private var _tilt = 0.5f

    override fun setKnob1(value: Float) { _decay = value.coerceIn(0f, 1f) }
    override fun setKnob2(value: Float) { _tilt = value.coerceIn(0f, 1f) }
    override fun onActivate() { engine.setSpectrumEnabled(true) }
    override fun onDeactivate() { engine.setSpectrumEnabled(false) }

    @Composable
    override fun Content(modifier: Modifier) {
        val bands by engine.spectrumFlow.collectAsState()
        val n = bands.size
        val ballistics = remember(n) { SpectrumBallistics(n.coerceAtLeast(1)) }

        Canvas(modifier = modifier.fillMaxSize()) {
            drawRect(OrpheusColors.fireworksBackground)
            if (n == 0) return@Canvas

            // Shape the latest frame, then advance the ballistics.
            val targets = FloatArray(n)
            for (i in 0 until n) {
                val tilted = applyTilt(bands[i], i, n, _tilt)
                targets[i] = magnitudeToHeight(tilted)
            }
            ballistics.update(targets, _decay)

            val w = size.width
            val h = size.height
            val slot = w / n
            val barW = slot * 0.8f
            for (i in 0 until n) {
                val bh = ballistics.heights[i] * h
                val x = i * slot
                // glow halo pass + crisp pass
                drawRect(color.copy(alpha = 0.15f), Offset(x, h - bh), Size(barW, bh))
                drawRect(color, Offset(x, h - bh), Size(barW, bh))
                // peak-hold cap
                val py = h - ballistics.peaks[i] * h
                drawRect(color.copy(alpha = 0.9f), Offset(x, py), Size(barW, 2f))
            }
        }
    }
}
```
(If `OrpheusColors.fireworksBackground` is not present, use the same dark background token `SignalMonitorViz` uses — confirm with `grep -n "fireworksBackground" ui/…/OrpheusTheme.kt`; `SignalMonitorViz` already references it.)

- [ ] **Step 2: Compile-check**

Run:
```
./gradlew :features:visualizations:compileKotlinJvm
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add features/visualizations/src/commonMain/kotlin/org/balch/orpheus/features/visualizations/viz/SpectrumViz.kt
git commit -m "feat(viz): SpectrumViz neon-bar analyzer + DI registration"
```

---

### Task 8: WASM CMake wiring + end-to-end verification

**Files:**
- Modify: `liborpheus_dsp/platform/wasm/CMakeLists.txt` (add `orpheus_spectrum.cc` to `ORPHEUS_SRC`)

- [ ] **Step 1: Add the source to the WASM build**

In `liborpheus_dsp/platform/wasm/CMakeLists.txt`, in the `ORPHEUS_SRC` list (near `"${LIB_SRC}/orpheus_resonator.cpp"`), add:
```cmake
    "${LIB_SRC}/orpheus_spectrum.cc"
```
Note: per project history the local `buildWasmNative` may fail on emsdk regardless (CI builds WASM); this addition is for build correctness. Do not block on a local WASM-native failure — verify the JVM path below.

- [ ] **Step 2: Full C++ test pass**

Run:
```
cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test && liborpheus_dsp/build-desktop/orpheus_dsp_test
```
Expected: entire suite passes (including `spectrum`).

- [ ] **Step 3: Run the desktop app and confirm the viz live**

Run:
```
./gradlew :apps:orpheus:desktopApp:run
```
Then: play/enable audio, open the visualization picker, select **Spectrograph**. Expected: neon magenta bars that rise with the music, fall gently, with peak-hold caps hovering above. Turn DECAY (snappy↔smooth) and TILT (flat↔bright) and confirm they change the motion and high-end balance. Select a different viz and confirm the bars freeze/clear (polling gated off).

- [ ] **Step 4: Commit**

```bash
git add liborpheus_dsp/platform/wasm/CMakeLists.txt
git commit -m "build(wasm): compile orpheus_spectrum.cc in the WASM engine"
```

---

## Tuning phase (after the plan lands)

The feel functions in Task 6 ship with working defaults but are the intended tuning targets (learning-mode contribution spots). Dial by eye against real music:
- `SpectrumBallistics.update`: `fall` / `peakFall` coefficients (how alive vs. smooth).
- `magnitudeToHeight`: `floorDb` / `ceilDb` (noise floor + dynamic range).
- `applyTilt`: the `9f` max-tilt-dB and curve.
- `SPECTRUM_BAND_COUNT` (24–64) and the `kFMin`/`kFMax` range in C++.

## Notes / known risks

- **ShyFFT imag index**: Task 1's sine test is the guard. If it fails, switch `fft_out_[half + k]` → `fft_out_[kFftSize - k]`.
- **WASM**: spectrum shows floor (bridge stub) — matches every existing viz on WASM. Real WASM viz is out of scope.
- **Android/iOS**: bridge + JNI/cinterop included and should compile, but were not runtime-verified in this plan (primary platform is JVM desktop). Verify on-device before an Android/iOS release.
