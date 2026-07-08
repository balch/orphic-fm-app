# Spectrum Analyzer Visualization — Design

Date: 2026-07-08
Branch: `feature/spectrum-analyzer-viz`
Status: Approved (defaults locked; feel parameters to be tuned during implementation)

## 1. Summary

Add a new background `Visualization` — a real-time audio **spectrum analyzer** rendered
as neon vertical bars — to the shared visualization lineup (alongside Orphoscope, LavaLamp,
Galaxy, etc.). It shows the frequency content of the final master mix.

"Hybrid" intent: the analysis underneath is honest (a real windowed FFT with log-frequency
bands and dB scaling), but the presentation is styled to be beautiful and lives in the same
family as the other background visualizations.

Working internal name: **Spectrograph** (renameable; user-facing name must not reference any
Mutable Instruments module).

## 2. Motivation & the key constraint

The existing visualization data pipeline **cannot** feed a spectrum analyzer.

Every current viz channel is a `VizRing` (`liborpheus_dsp/src/orpheus_viz.h`) fed **one value
per 512-sample audio block** — an effective rate of ~94 Hz, and usually a block *peak* rather
than a raw sample. By Nyquist, an FFT of that stream could only represent content up to ~47 Hz.
That is an envelope follower, not a spectrum.

Real audio-rate samples exist only inside the C++/miniaudio audio callback
(`liborpheus_dsp/desktop/DesktopEngine.cpp`). Kotlin never sees raw audio; it only drives
parameters. Therefore a spectrum analyzer **requires a new audio-rate sample tap in C++**. This
is the single new mechanism in the feature; everything else follows existing patterns.

## 3. Goals / Non-goals

Goals:
- Real windowed FFT of the master mix, displayed as log-frequency neon bars with peak-hold.
- Runs on all three targets (JVM desktop, Android, WASM).
- No new third-party dependency.
- FFT never runs on the audio thread; no real-time budget risk.
- Feel parameters (ballistics, dB mapping, knob behavior) live in Kotlin for fast iteration.

Non-goals (YAGNI):
- Per-channel (stereo) spectra — analyze the mono sum `(L+R)*0.5`.
- Selectable analysis source — master mix only for v1.
- Waterfall/spectrogram history, radial form — vertical bars only for v1.
- Musical pitch/note detection, calibrated SPL metering.

## 4. Architecture overview

```
master mix ──(audio thread, per-sample write)──► SampleRing (raw f32, 2048, power of 2)
                                                       │
                             (poll thread, ~60 fps, OFF the audio thread)
                                                       ▼
                    Hann window → ShyFFT.Direct → magnitude per bin
                                                       │
                             fold into N log-spaced bands over [fMin, fMax]
                                                       ▼
        orpheus_engine_get_spectrum(bands, N) ──► SynthEngineMonitor.spectrumFlow: Flow<FloatArray>
                                                       ▼
                 SpectrumViz.Content(): dB + tilt → ballistics + peak-hold → neon bars
```

### C++ / Kotlin responsibility split

The line is drawn so the **eye-tuned "feel" is in Kotlin** (no recompile to tweak) and only
stable DSP math is in C++:

- **C++** owns: the sample tap, windowing, FFT, magnitude, and folding raw magnitudes into
  `N` log-spaced bands. Output is **linear magnitude per band**. Kotlin passes `N`, so the bar
  count is a Kotlin-side decision. ~40 floats cross the bridge per poll (tiny).
- **Kotlin** owns: dB conversion, spectral tilt, ballistics (attack/decay + peak-hold), knob
  behavior, colors, and drawing.

## 5. Component design

### 5.1 C++ `SampleRing` (new, in `orpheus_spectrum.h`)

Analogous to `VizRing`, but the audio thread writes **every sample** of the mono master sum,
not one peak per block. Lock-free, single-producer/single-consumer, monotonic `uint32_t`
write counter (same overflow-safe pattern as `VizRing`).

```cpp
struct SampleRing {
  static constexpr int kSize = 2048;   // power of 2 == FFT window
  float buf[kSize] = {};
  std::atomic<uint32_t> write_count{0};
  inline void write(float s) {
    uint32_t wc = write_count.load(std::memory_order_relaxed);
    buf[wc % kSize] = s;
    write_count.store(wc + 1, std::memory_order_release);
  }
};
```

### 5.2 C++ `SpectrumAnalyzer` (new, `orpheus_spectrum.h/.cc`)

Owns the FFT and scratch state. Instantiated once and reused (ShyFFT is non-copyable).

- FFT: `stmlib::ShyFFT<float, 2048, stmlib::RotationPhasor>` — header-only, MIT-licensed,
  already on our include path via `stmlib`, proven by Clouds' phase vocoder
  (`clouds/dsp/pvoc/stft.h`). `Init()` once; `Direct(in, out)` per analysis.
- A precomputed Hann window LUT of size 2048.
- Precomputed log-band bin edges (depends on sample rate + `N`).

```cpp
class SpectrumAnalyzer {
 public:
  void Init(float sample_rate);
  // Copies the newest window from `ring`, windows + FFTs it, folds magnitudes
  // into `n` log-spaced bands over [kFMin, kFMax]; writes linear magnitude to bands[0..n).
  void Analyze(const SampleRing& ring, float* bands, int n);
 private:
  stmlib::ShyFFT<float, 2048, stmlib::RotationPhasor> fft_;
  float window_[2048];
  float fft_in_[2048];
  float fft_out_[2048];
  float sample_rate_ = 48000.f;
};
```

Magnitude per bin uses ShyFFT's FFTReal output packing (real parts and imaginary parts
interleaved per the de Soras layout). `clouds/dsp/pvoc/stft.cc` is the reference for the exact
indexing. Band folding: for band `b` covering bins `[k_lo, k_hi)`, take the **max** magnitude in
range (reads "peakier/more alive" than a sum); for the lowest bands whose frequency span covers
fewer than one bin, use the nearest bin. `Analyze` runs on the poll thread (called from the
bridge), never the audio thread.

Constants: `kFMin = 30 Hz`, `kFMax = 16000 Hz`.

### 5.3 C++ native export + platform bridges

- Public C API in `liborpheus_dsp/include/orpheus_dsp.h`:
  `void orpheus_engine_get_spectrum(OrpheusEngine*, float* bands, int n);`
  (parallels the existing `orpheus_engine_get_viz`).
- Bridge signatures added next to the existing `nativeGetViz`:
  - Desktop JNI: `liborpheus_dsp/desktop/jni_bridge_desktop.cpp`
  - Android JNI: the Android native bridge
  - WASM: the JS bridge / exported function list

### 5.4 C++ master-out wiring

Add one per-sample `sample_ring.write((l + r) * 0.5f)` at the master output stage — the same
place `VIZ_MASTER_OUT` is written today. This is the only audio-thread cost, and it is a single
store per sample.

### 5.5 Kotlin `SynthEngineMonitor` spectrum flow

`core/dsp-engine/.../SynthEngineMonitor.kt`:
- Declare `nativeGetSpectrum(bands: FloatArray, n: Int)` on the native bridge.
- Add `spectrumFlow: Flow<FloatArray>`, polled at the existing `VIZ_POLL_INTERVAL_MS` (16 ms).
- **Gated**: only poll while the spectrum viz is active (via the viz's `onActivate`/
  `onDeactivate`, consistent with how other viz polling is scoped), so no FFT runs when the
  viz isn't on screen.
- Expose `spectrumFlow` through the `SynthEngine` interface (`core/audio/SynthEngine`) the same
  way the other `*VizFlow`s are exposed.

### 5.6 Kotlin `SpectrumViz`

New file `features/visualizations/.../viz/SpectrumViz.kt`, following `SignalMonitorViz` exactly:

```kotlin
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<Visualization>())
class SpectrumViz(private val engine: SynthEngine) : Visualization {
    override val id = "spectrum"
    override val name = "Spectrograph"
    override val color = OrpheusColors.neonCyan          // TBD in tuning
    override val knob1Label = "DECAY"
    override val knob2Label = "TILT"
    // setKnob1/2, onActivate/onDeactivate, liquidEffects, Content()
}
```

`Content()` collects `spectrumFlow`, runs the feel functions (§6), and draws N neon bars with a
glow halo pass + crisp pass + peak-hold caps on a `Canvas` (reuse the cheap read-in-draw-scope
style documented in `SignalMonitorViz`).

## 6. The tuning (feel) layer — Kotlin, tuned during implementation

Each is a small pure function with a sensible default; final values are dialed by eye/ear.
These are the highest-leverage, most-owned pieces of the feature.

### 6.1 `applyBallistics()` — the "alive" function
Fast attack (snap up instantly), slow decay (fall gently), plus peak-hold caps that hang and
then fall slowly. Default:
- bar: `bar = if (target > bar) target else max(target, bar - fallRate)`
- peak cap: `peak = if (target >= peak) target else max(target, peak - peakFallRate)`
- `fallRate` driven by `knob1` (DECAY); `peakFallRate` ~1/4 of `fallRate`.

### 6.2 `toBarHeight()` — magnitude → 0..1
`db = 20*log10(mag + 1e-9)`, normalize `(db - floorDb) / (ceilDb - floorDb)`, clamp 0..1.
Defaults: `floorDb = -70`, `ceilDb = 0`.

### 6.3 Spectral tilt (`knob2` = TILT)
Lift the highs to counter music's natural high-frequency rolloff so the display isn't
bass-heavy and dead up top. Default: `+tiltDb * log2(f / fRef)`, `tiltDb` scaled 0..~6 dB/oct
by `knob2`, `fRef = 1 kHz`.

### 6.4 Knobs
- `knob1 = DECAY`: ballistics fall rate (snappy ↔ smooth).
- `knob2 = TILT`: high-frequency lift (flat ↔ bright).

## 7. Locked decisions

| Decision | Value |
|---|---|
| FFT window | 2048 samples (~43 ms @ 48 kHz), Hann window |
| FFT implementation | `stmlib::ShyFFT<float, 2048, RotationPhasor>` (borrowed, no new dep) |
| Bar count `N` | 40 (Kotlin-chosen; C++ folds to `N`) |
| Frequency range | 30 Hz – 16 kHz, log-spaced |
| Analysis source | master mix, mono sum `(L+R)*0.5` |
| Band aggregation | max magnitude per band |
| Knobs | knob1 = DECAY, knob2 = TILT |
| Working name | "Spectrograph" |
| Poll rate | `VIZ_POLL_INTERVAL_MS` (16 ms), gated to active viz |

## 8. Testing

C++ (`writing-dsp-tests`, new `liborpheus_dsp/test/test_spectrum.cpp`):
- 1 kHz sine → the band containing 1 kHz dominates; neighbors far lower.
- Silence / DC → all bands at floor.
- White noise → roughly flat across bands, no `NaN`/`Inf`.
- Determinism + all-`N` sanity (24, 40, 64) → no out-of-range writes.

Kotlin (JVM unit tests):
- `applyBallistics`: attack jumps to target; decay falls at expected rate; peak-hold hangs then
  falls slower.
- `toBarHeight`: known magnitude → known height; floor clamps; no NaN at mag 0.

## 9. Performance, edge cases, build notes

- **Real-time safety**: FFT on the poll thread only. 2048-pt FFT @ 60 fps is trivial (Clouds
  runs a larger STFT on a 72 MHz STM32). Audio thread cost = one store per sample.
- **Gating**: poll/FFT only while the viz is active.
- **Startup**: if the ring has fewer than 2048 samples written, output the floor (guard against
  reading uninitialized tail).
- **WASM build gotcha**: `orpheus_spectrum.cc` must be added to **both** the desktop CMake
  sources and the WASM CMake sources. Per project notes the WASM `CMakeLists` already lags
  behind on engine units and `buildWasmNative` can fail locally on emsdk (CI unaffected); wire
  the new file into both, and expect the known local WASM-native caveat.

## 10. File-change checklist

C++:
- `liborpheus_dsp/src/orpheus_spectrum.h` (new) — `SampleRing`, `SpectrumAnalyzer`
- `liborpheus_dsp/src/orpheus_spectrum.cc` (new) — `Analyze` implementation
- `liborpheus_dsp/src/orpheus_engine.h` — add `SampleRing` + `SpectrumAnalyzer` members
- master output stage (where `VIZ_MASTER_OUT` is written) — per-sample ring write
- `liborpheus_dsp/include/orpheus_dsp.h` — declare `orpheus_engine_get_spectrum`
- desktop / Android / WASM native bridges — `nativeGetSpectrum`
- desktop `CMakeLists.txt` + WASM `CMakeLists` — add `orpheus_spectrum.cc`
- `liborpheus_dsp/test/test_spectrum.cpp` (new)

Kotlin:
- `core/dsp-engine/.../SynthEngineMonitor.kt` — `spectrumFlow`, poll loop, `nativeGetSpectrum`
- `core/audio/.../SynthEngine` — expose `spectrumFlow`
- native bridge class — `nativeGetSpectrum` declaration
- `features/visualizations/.../viz/SpectrumViz.kt` (new)
- Kotlin unit tests for `applyBallistics` / `toBarHeight`

## 11. Future work (out of scope for v1)

- Selectable source (DJ bus, per-track).
- Alternate forms (radial, waterfall) reusing the same band data.
- Stereo / mid-side spectra.
