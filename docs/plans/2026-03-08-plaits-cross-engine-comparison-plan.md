# Plaits Cross-Engine WAV Comparison Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build comprehensive WAV snapshot tests for ALL 16 Plaits engines on both C++ and JSyn sides, enabling quantitative comparison of the two rendering paths.

**Architecture:** Each engine renders the same parameters (note, timbre, morph, harmonics) through its native signal chain. WAV files are written to `liborpheus_dsp/test/output/` with `cpp_engine_*` and `jsyn_engine_*` prefixes. A comparison test reads both and reports level ratios, spectral similarity metrics.

**Tech Stack:** C++ (liborpheus_dsp test harness), Kotlin JVM test (JSyn offline engine), WAV I/O

---

## Context & Key Findings

### Engine Index Mapping (Kotlin ordinal → C++ Plaits engine)

```
Kotlin PlaitsEngineId     | C++ engine_index
─────────────────────────────────────────────
VIRTUAL_ANALOG            |  8
WAVESHAPING               |  9
FM                        | 10
GRAIN                     | 11
ADDITIVE                  | 12
WAVETABLE                 | 13
CHORD                     | 14
SPEECH                    | 15
SWARM                     | 16
NOISE                     | 17
PARTICLE                  | 18
STRING                    | 19
MODAL                     | 20
ANALOG_BASS_DRUM          | 21
ANALOG_SNARE_DRUM         | 22
METALLIC_HI_HAT           | 23
```

### Known Differences

1. **Sample rate**: Kotlin hardcodes 44100 Hz (`SynthDsp.SAMPLE_RATE`), C++ uses 48000 Hz
2. **Gain staging**: C++ Plaits outputs int16, scaled by `(out+aux) * 0.5 * inv_32768`. Kotlin Plaits outputs float with per-engine `outGain` (e.g., VA=0.3). Current level ratio is ~10x for dry voices.
3. **Envelope**: C++ uses Plaits' internal LPG (decay parameter). Kotlin uses DspVoice's external ADSR envelope.
4. **Soft limiting**: Kotlin applies `tanh` saturation above 0.5f. C++ does not apply this to raw Plaits output.

### Existing Infrastructure

- **C++ side**: `test/test_harness.h` has `write_wav()`, `compare_wavs()`, `snapshot_check()`. `test/test_snapshots.cpp` has 6 scenarios (single voice, chord, bender, per-string-bender, reverb, delay).
- **JSyn side**: `core/audio/src/jvmMain/.../OfflineAudioEngine.kt` wraps JSyn for offline rendering. `core/dsp-engine/src/jvmTest/.../JsynSnapshotTest.kt` has 3 render tests + cross-engine comparison.
- **Output dir**: `liborpheus_dsp/test/output/`

---

## Phase 1: C++ Per-Engine WAV Snapshots

### Task 1: Add per-engine C++ snapshot test

**Files:**
- Modify: `liborpheus_dsp/test/test_snapshots.cpp`
- Modify: `liborpheus_dsp/test/test_harness.h`

**Step 1: Add `render_plaits_engine()` helper to `test_harness.h`**

After the existing `setup_voice_unit` helper, add:

```cpp
// Renders a single Plaits engine in isolation via orpheus_engine_process().
// Returns interleaved stereo buffer.
// engine_index: C++ Plaits engine index (8=VA, 9=waveshaping, etc.)
// note: MIDI note
// duration_s: total render duration
// gate_s: how long gate stays on (rest is release)
inline std::vector<float> render_plaits_engine(
    int engine_index, float note, float harmonics, float timbre, float morph,
    float decay, int sample_rate, float duration_s, float gate_s)
{
    OrpheusEngine* engine = orpheus_engine_create(sample_rate);
    orpheus_engine_set_voice_active(engine, 0, 1);
    orpheus_engine_set_voice_tune(engine, 0, note);
    orpheus_engine_set_voice_gate(engine, 0, 1);
    engine->voice_params[0].engine_index.store(engine_index);
    engine->voice_params[0].harmonics.store(harmonics);
    engine->voice_params[0].timbre.store(timbre);
    engine->voice_params[0].morph.store(morph);
    engine->voice_params[0].decay.store(decay);
    // Ensure voice has triggered
    engine->voice_params[0].ever_triggered.store(1);

    int total = (int)(sample_rate * duration_s);
    int gate_frames = (int)(sample_rate * gate_s);
    std::vector<float> buf(total * 2, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        if (off >= gate_frames)
            orpheus_engine_set_voice_gate(engine, 0, 0);
        orpheus_engine_process(engine, buf.data() + off * 2, chunk);
    }
    orpheus_engine_destroy(engine);
    return buf;
}
```

**Step 2: Add per-engine snapshot loop to `test_snapshots.cpp`**

After the existing scenarios (inside `run_snapshot_tests()`), add:

```cpp
// Per-engine Plaits snapshots
{
    struct EngineSpec {
        int cpp_index;
        const char* name;
    };
    EngineSpec engines[] = {
        { 8, "virtual_analog"}, { 9, "waveshaping"}, {10, "fm"},
        {11, "grain"}, {12, "additive"}, {13, "wavetable"},
        {14, "chord"}, {15, "speech"}, {16, "swarm"},
        {17, "noise"}, {18, "particle"}, {19, "string"},
        {20, "modal"}, {21, "bass_drum"}, {22, "snare_drum"},
        {23, "hihat"},
    };

    for (auto& e : engines) {
        char label[64];
        snprintf(label, sizeof(label), "cpp_engine_%s", e.name);
        printf("  Scenario: engine_%s\n", e.name);
        auto buf = render_plaits_engine(
            e.cpp_index, 60.0f, 0.5f, 0.5f, 0.5f, 0.5f,
            sr, 2.0f, 1.0f);
        int total = sr * 2;
        printf("    RMS=%.4f Peak=%.4f\n",
               compute_rms(buf.data(), total * 2),
               compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check(label, buf.data(), total, sr, dir);
    }
}
```

**Step 3: Build and run**

```bash
cd liborpheus_dsp && cmake --build build && build/orpheus_dsp_test
```

Expected: 16 new WAV files `cpp_engine_*.wav` in `test/output/`.

**Step 4: Commit**

```bash
git add test/test_harness.h test/test_snapshots.cpp
git commit -m "test(dsp): Add per-engine Plaits WAV snapshots for all 16 C++ engines"
```

---

## Phase 2: JSyn Per-Engine WAV Snapshots

### Task 2: Add all Kotlin Plaits engine renders to JsynSnapshotTest

**Files:**
- Modify: `core/dsp-engine/src/jvmTest/kotlin/org/balch/orpheus/core/audio/dsp/JsynSnapshotTest.kt`

**Step 1: Add engine factory helper**

In `JsynSnapshotTest`, add a helper that creates any PlaitsEngine by ID:

```kotlin
import org.balch.orpheus.plugins.plaits.PlaitsEngineFactory
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.engine.*

private fun createPlaitsEngine(id: PlaitsEngineId): Any {
    val engine = when (id) {
        PlaitsEngineId.VIRTUAL_ANALOG -> VirtualAnalogEngine()
        PlaitsEngineId.WAVESHAPING -> WaveshapingEngine()
        PlaitsEngineId.FM -> FmEngine()
        PlaitsEngineId.GRAIN -> GrainEngine()
        PlaitsEngineId.ADDITIVE -> AdditiveEngine()
        PlaitsEngineId.WAVETABLE -> WavetableEngine()
        PlaitsEngineId.CHORD -> ChordEngine()
        PlaitsEngineId.SPEECH -> SpeechEngine()
        PlaitsEngineId.SWARM -> SwarmEngine()
        PlaitsEngineId.NOISE -> NoiseEngine()
        PlaitsEngineId.PARTICLE -> ParticleEngine()
        PlaitsEngineId.STRING -> StringEngine()
        PlaitsEngineId.MODAL -> ModalEngine()
        PlaitsEngineId.ANALOG_BASS_DRUM -> ... // Check PlaitsEngineFactory for drum engine creation
        PlaitsEngineId.ANALOG_SNARE_DRUM -> ...
        PlaitsEngineId.METALLIC_HI_HAT -> ...
        PlaitsEngineId.FM_DRUM -> ...
    }
    (engine as? org.balch.orpheus.plugins.plaits.PlaitsEngine)?.init()
    return engine
}
```

**NOTE**: Drum engines may require special handling. Check `PlaitsEngineFactory` implementations to see how drum engines are instantiated. They may be in a separate module or use a different pattern. If drum engines aren't available as standalone PlaitsEngine instances, skip them for now and add a TODO.

**Step 2: Add `renderEngine` helper method**

```kotlin
private fun renderEngine(
    engineId: PlaitsEngineId,
    note: Float = 60f,
    harmonics: Float = 0.5f,
    timbre: Float = 0.5f,
    morph: Float = 0.5f,
    durationSeconds: Float = 2f,
    gateSeconds: Float = 1f
): FloatArray {
    val engine = OfflineAudioEngine(SR)
    val factory = createFactory()
    val voice = DspVoice(engine, factory, pitchMultiplier = 1.0)

    val plaitsEngine = createPlaitsEngine(engineId)
    voice.plaits.setEngine(plaitsEngine)
    voice.setEngineActive(true)
    voice.plaits.setNote(note)
    voice.plaits.setTimbre(timbre)
    voice.plaits.setMorph(morph)
    voice.plaits.setHarmonics(harmonics)

    val freqHz = 440.0 * Math.pow(2.0, (note - 69.0) / 12.0)
    voice.frequency.set(freqHz)
    voice.setEnvelopeSpeed(0.5f)

    voice.output.connect(engine.lineOutLeft)
    voice.output.connect(engine.lineOutRight)

    engine.start()

    val totalFrames = (SR * durationSeconds).toInt()
    val gateFrames = (SR * gateSeconds).toInt()
    val buf = engine.renderStereo(totalFrames, chunkSize = 1024) { offset ->
        if (offset == 0) voice.gate.set(1.0)
        if (offset >= gateFrames) voice.gate.set(0.0)
    }

    engine.stop()
    return buf
}
```

**Step 3: Add per-engine test**

```kotlin
@Test
fun allEngines() {
    // Map of PlaitsEngineId to C++ engine name (must match C++ snapshot naming)
    val engineNames = mapOf(
        PlaitsEngineId.VIRTUAL_ANALOG to "virtual_analog",
        PlaitsEngineId.WAVESHAPING to "waveshaping",
        PlaitsEngineId.FM to "fm",
        PlaitsEngineId.GRAIN to "grain",
        PlaitsEngineId.ADDITIVE to "additive",
        PlaitsEngineId.WAVETABLE to "wavetable",
        PlaitsEngineId.CHORD to "chord",
        PlaitsEngineId.SPEECH to "speech",
        PlaitsEngineId.SWARM to "swarm",
        PlaitsEngineId.NOISE to "noise",
        PlaitsEngineId.PARTICLE to "particle",
        PlaitsEngineId.STRING to "string",
        PlaitsEngineId.MODAL to "modal",
        // Drum engines if available:
        // PlaitsEngineId.ANALOG_BASS_DRUM to "bass_drum",
        // PlaitsEngineId.ANALOG_SNARE_DRUM to "snare_drum",
        // PlaitsEngineId.METALLIC_HI_HAT to "hihat",
    )

    for ((engineId, name) in engineNames) {
        println("  Rendering jsyn_engine_$name...")
        val buf = renderEngine(engineId)
        val totalFrames = SR * 2
        val rms = computeRms(buf)
        val peak = computePeak(buf)
        println("    RMS=${"%.4f".format(rms)} Peak=${"%.4f".format(peak)}")
        assertTrue(peak > 0.0001f, "Engine $name should produce audio (peak=$peak)")
        writeWav(File(OUTPUT_DIR, "jsyn_engine_$name.wav"), buf, totalFrames, SR)
    }
}
```

**Step 4: Run tests**

```bash
./gradlew :core:dsp-engine:jvmTest --tests "org.balch.orpheus.core.audio.dsp.JsynSnapshotTest.allEngines"
```

Expected: 13+ WAV files `jsyn_engine_*.wav` in `liborpheus_dsp/test/output/`.

**Step 5: Commit**

```bash
git add core/dsp-engine/src/jvmTest/kotlin/org/balch/orpheus/core/audio/dsp/JsynSnapshotTest.kt
git commit -m "test(dsp): Add per-engine JSyn Plaits WAV snapshots for all engines"
```

---

## Phase 3: Cross-Engine Comparison Report

### Task 3: Expand `crossEngineComparison` to cover all engines

**Files:**
- Modify: `core/dsp-engine/src/jvmTest/kotlin/org/balch/orpheus/core/audio/dsp/JsynSnapshotTest.kt`

**Step 1: Update `crossEngineComparison` test**

Replace the hardcoded scenario list with all engines:

```kotlin
@Test
fun crossEngineComparison() {
    val engines = listOf(
        "virtual_analog", "waveshaping", "fm", "grain", "additive",
        "wavetable", "chord", "speech", "swarm", "noise",
        "particle", "string", "modal", "bass_drum", "snare_drum", "hihat"
    )
    // Also compare the full-voice scenarios
    val fullScenarios = listOf("single_voice_c4", "4voice_chord", "voice_reverb")

    println("\n=== Cross-Engine Comparison Report ===")
    println("%-20s | %8s %8s | %8s %8s | %8s".format(
        "Engine", "JSyn RMS", "JSyn Pk", "C++ RMS", "C++ Pk", "Ratio"))
    println("-".repeat(80))

    for (name in engines + fullScenarios) {
        val jsynPrefix = if (name in engines) "jsyn_engine_" else "jsyn_"
        val cppPrefix = if (name in engines) "cpp_engine_" else "cpp_"
        val jsynFile = File(OUTPUT_DIR, "$jsynPrefix$name.wav")
        val cppFile = File(OUTPUT_DIR, "$cppPrefix$name.wav")

        if (!jsynFile.exists() || !cppFile.exists()) {
            println("%-20s | %s".format(name, "SKIP (file missing)"))
            continue
        }

        val jsynWav = readWavMono(jsynFile)
        val cppWav = readWavMono(cppFile)
        val jsynRms = computeRms(jsynWav.samples)
        val cppRms = computeRms(cppWav.samples)
        val jsynPeak = computePeak(jsynWav.samples)
        val cppPeak = computePeak(cppWav.samples)
        val ratio = if (cppRms > 0.0001f) jsynRms / cppRms else 0f

        println("%-20s | %8.4f %8.4f | %8.4f %8.4f | %8.3f".format(
            name, jsynRms, jsynPeak, cppRms, cppPeak, ratio))
    }
    println()
}
```

**Step 2: Run full comparison**

```bash
# First generate C++ snapshots
cd liborpheus_dsp && cmake --build build && build/orpheus_dsp_test
# Then generate JSyn snapshots and compare
./gradlew :core:dsp-engine:jvmTest --tests "org.balch.orpheus.core.audio.dsp.JsynSnapshotTest"
```

**Step 3: Commit**

```bash
git add core/dsp-engine/src/jvmTest/kotlin/org/balch/orpheus/core/audio/dsp/JsynSnapshotTest.kt
git commit -m "test(dsp): Add comprehensive cross-engine comparison report for all Plaits engines"
```

---

## Phase 4: Raw Engine Comparison (Bypass Signal Chain)

### Task 4: Add raw PlaitsEngine output test (no DspVoice)

This test isolates just the Kotlin PlaitsEngine render output, bypassing DspVoice's envelope, VCA, frequency chain, and soft limiter. This is the most diagnostic comparison since it tests the Kotlin Plaits port against the original C++ Plaits.

**Files:**
- Modify: `core/dsp-engine/src/jvmTest/kotlin/org/balch/orpheus/core/audio/dsp/JsynSnapshotTest.kt`

**Step 1: Add raw engine render helper**

```kotlin
import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.TriggerState

/**
 * Render a PlaitsEngine directly — no DspVoice, no JSyn, no envelope.
 * Pure Kotlin engine output at 44100 Hz.
 */
private fun renderRawEngine(
    engineId: PlaitsEngineId,
    note: Float = 60f,
    harmonics: Float = 0.5f,
    timbre: Float = 0.5f,
    morph: Float = 0.5f,
    durationSeconds: Float = 2f,
    triggerAtStart: Boolean = true
): FloatArray {
    val engine = createPlaitsEngine(engineId) as org.balch.orpheus.plugins.plaits.PlaitsEngine
    val blockSize = 24 // Plaits sub-block size
    val totalSamples = (SR * durationSeconds).toInt()
    val mono = FloatArray(totalSamples)
    val block = FloatArray(blockSize)
    val params = EngineParameters()

    var offset = 0
    var firstBlock = true
    while (offset < totalSamples) {
        val chunk = minOf(blockSize, totalSamples - offset)
        params.set(
            trigger = if (firstBlock && triggerAtStart) TriggerState.RISING_EDGE else TriggerState.LOW,
            note = note,
            timbre = timbre,
            morph = morph,
            harmonics = harmonics,
            accent = 0.8f
        )
        engine.render(params, block, null, chunk)
        // Apply outGain (same as JsynPlaitsUnit does)
        val gain = engine.outGain
        for (i in 0 until chunk) {
            mono[offset + i] = block[i] * gain
        }
        offset += chunk
        firstBlock = false
    }

    // Convert mono to interleaved stereo
    val stereo = FloatArray(totalSamples * 2)
    for (i in 0 until totalSamples) {
        stereo[i * 2] = mono[i]
        stereo[i * 2 + 1] = mono[i]
    }
    return stereo
}
```

**Step 2: Add raw engine snapshot test**

```kotlin
@Test
fun allEnginesRaw() {
    val engineNames = mapOf(
        PlaitsEngineId.VIRTUAL_ANALOG to "virtual_analog",
        PlaitsEngineId.WAVESHAPING to "waveshaping",
        PlaitsEngineId.FM to "fm",
        // ... all engines
    )

    for ((engineId, name) in engineNames) {
        println("  Rendering jsyn_raw_$name...")
        val buf = renderRawEngine(engineId)
        val totalFrames = SR * 2
        val rms = computeRms(buf)
        val peak = computePeak(buf)
        println("    RMS=${"%.4f".format(rms)} Peak=${"%.4f".format(peak)}")
        writeWav(File(OUTPUT_DIR, "jsyn_raw_$name.wav"), buf, totalFrames, SR)
    }
}
```

**Step 3: Add matching C++ raw test**

In `test/test_snapshots.cpp`, add a raw Plaits test that renders without the engine's mixing/panning:

```cpp
// Raw Plaits output — isolated voice without mixing/volume
{
    for (auto& e : engines) {
        char label[64];
        snprintf(label, sizeof(label), "cpp_raw_%s", e.name);

        OrpheusEngine* eng = orpheus_engine_create(sr);
        eng->voice_params[0].active.store(1);
        eng->voice_params[0].ever_triggered.store(1);
        eng->voice_params[0].engine_index.store(e.cpp_index);
        eng->voice_params[0].tune.store(60.0f);
        eng->voice_params[0].gate.store(1);
        eng->voice_params[0].harmonics.store(0.5f);
        eng->voice_params[0].timbre.store(0.5f);
        eng->voice_params[0].morph.store(0.5f);
        eng->voice_params[0].decay.store(0.5f);

        // Render directly via plaits::Voice (bypassing orpheus_engine_process mix stage)
        plaits::Patch patch;
        patch.engine = e.cpp_index;
        patch.note = 60.0f;
        patch.harmonics = 0.5f;
        patch.timbre = 0.5f;
        patch.morph = 0.5f;
        patch.decay = 0.5f;
        patch.lpg_colour = 0.5f;
        patch.frequency_modulation_amount = 0.0f;
        patch.timbre_modulation_amount = 0.0f;
        patch.morph_modulation_amount = 0.0f;

        plaits::Modulations mod = {};
        mod.trigger = 1.0f;
        mod.trigger_patched = true;

        int total = sr * 2;
        std::vector<float> buf(total * 2, 0.0f);
        const float inv_32768 = 1.0f / 32768.0f;

        for (int off = 0; off < total; off += 12) {
            int block = std::min(12, total - off);
            plaits::Voice::Frame frames[plaits::kMaxBlockSize];
            eng->voices_dsp[0].Render(patch, mod, frames, block);
            for (int i = 0; i < block; i++) {
                float sample = (frames[i].out + frames[i].aux) * 0.5f * inv_32768;
                buf[(off + i) * 2]     = sample;
                buf[(off + i) * 2 + 1] = sample;
            }
            // After first block, set trigger to sustain (not re-trigger)
            mod.trigger = 1.0f; // Keep gate high
        }
        printf("  Raw %s: RMS=%.4f Peak=%.4f\n", e.name,
               compute_rms(buf.data(), total * 2), compute_peak(buf.data(), total * 2));
        all_pass &= snapshot_check(label, buf.data(), total, sr, dir);
        orpheus_engine_destroy(eng);
    }
}
```

**Step 4: Commit**

```bash
git add -A
git commit -m "test(dsp): Add raw Plaits engine snapshots bypassing signal chain"
```

---

## Phase 5: Level Normalization & Spectral Comparison

### Task 5: Add normalized comparison with spectral analysis

**Files:**
- Modify: `core/dsp-engine/src/jvmTest/kotlin/org/balch/orpheus/core/audio/dsp/JsynSnapshotTest.kt`

**Step 1: Add RMS-normalized comparison**

Instead of comparing raw levels (which we know differ by ~10x), normalize both WAVs to the same RMS before comparing. This isolates timbral differences from gain differences.

```kotlin
@Test
fun normalizedComparison() {
    // For each raw engine pair, normalize both to RMS=0.1, then compute:
    // 1. Spectral centroid difference (brightness)
    // 2. Zero-crossing rate difference (pitch/noisiness)
    // 3. Peak-to-RMS ratio difference (crest factor / dynamics)

    val engines = listOf(
        "virtual_analog", "waveshaping", "fm", "grain", "additive", ...
    )

    for (name in engines) {
        val jsynFile = File(OUTPUT_DIR, "jsyn_raw_$name.wav")
        val cppFile = File(OUTPUT_DIR, "cpp_raw_$name.wav")
        if (!jsynFile.exists() || !cppFile.exists()) continue

        val jsyn = readWavMono(jsynFile)
        val cpp = readWavMono(cppFile)

        // Normalize both to RMS=0.1
        val jsynNorm = normalize(jsyn.samples, 0.1f)
        val cppNorm = normalize(cpp.samples, 0.1f)

        // Compare timbral features
        val jsynZcr = zeroCrossingRate(jsynNorm)
        val cppZcr = zeroCrossingRate(cppNorm)
        val jsynCrest = computePeak(jsynNorm) / computeRms(jsynNorm)
        val cppCrest = computePeak(cppNorm) / computeRms(cppNorm)

        println("  [$name] ZCR: jsyn=%.4f cpp=%.4f  Crest: jsyn=%.2f cpp=%.2f".format(
            jsynZcr, cppZcr, jsynCrest, cppCrest))
    }
}

private fun normalize(samples: FloatArray, targetRms: Float): FloatArray {
    val rms = computeRms(samples)
    if (rms < 0.00001f) return samples.copyOf()
    val scale = targetRms / rms
    return FloatArray(samples.size) { samples[it] * scale }
}

private fun zeroCrossingRate(samples: FloatArray): Float {
    var crossings = 0
    for (i in 1 until samples.size) {
        if ((samples[i] >= 0f) != (samples[i - 1] >= 0f)) crossings++
    }
    return crossings.toFloat() / samples.size
}
```

**Step 2: Commit**

```bash
git add -A
git commit -m "test(dsp): Add normalized timbral comparison metrics (ZCR, crest factor)"
```

---

## Phase 6: Cleanup & Documentation

### Task 6: Add test runner instructions and .gitignore

**Files:**
- Modify: `liborpheus_dsp/test/.gitignore` (create if needed)
- Modify: `liborpheus_dsp/README.md` or top-level docs

**Step 1: Gitignore WAV output**

```
# test/output/.gitignore
*.wav
!.gitignore
```

**Step 2: Add run instructions**

Add to `liborpheus_dsp/test/output/README.md`:

```markdown
# Test WAV Output

Generated by test suites. Not checked into git.

## Generate C++ snapshots
cd liborpheus_dsp && cmake --build build && build/orpheus_dsp_test

## Generate JSyn snapshots
./gradlew :core:dsp-engine:jvmTest --tests "org.balch.orpheus.core.audio.dsp.JsynSnapshotTest"

## Compare
Open any audio editor (Audacity, etc.) and import matching pairs:
- `cpp_engine_virtual_analog.wav` vs `jsyn_engine_virtual_analog.wav`
- `cpp_raw_virtual_analog.wav` vs `jsyn_raw_virtual_analog.wav`
```

**Step 3: Commit**

```bash
git add -A
git commit -m "docs: Add WAV snapshot test instructions and .gitignore"
```

---

## Summary of Deliverables

| Phase | What | Files |
|-------|------|-------|
| 1 | C++ per-engine WAV snapshots (16 engines) | test_snapshots.cpp, test_harness.h |
| 2 | JSyn per-engine WAV snapshots (13+ engines) | JsynSnapshotTest.kt |
| 3 | Cross-engine comparison table | JsynSnapshotTest.kt |
| 4 | Raw engine comparison (bypass signal chain) | JsynSnapshotTest.kt, test_snapshots.cpp |
| 5 | Normalized timbral metrics (ZCR, crest) | JsynSnapshotTest.kt |
| 6 | Docs and .gitignore | .gitignore, README.md |

After all phases: you'll have ~100 WAV files in `test/output/` covering every Plaits engine on both C++ and Kotlin, with both full-chain and raw-engine variants. The comparison test prints a summary table showing level ratios and timbral metrics per engine.

---

## Preliminary Results (2026-03-07)

All 6 phases completed. C++ test binary produced 32 WAV snapshots (16 `cpp_engine_*` + 16 `cpp_raw_*`); Kotlin `jvmTest` produced 26 WAV snapshots (13 `jsyn_engine_*` + 13 `jsyn_raw_*`). `compare_engines.py` was added to `test/output/` and run against all 13 matched engine pairs, comparing RMS, Peak, ZCR, and crest factor.

### Key Findings

- **JSyn RMS is consistently higher** (+0.01–+0.16 across all engines) — expected due to JSyn per-engine `outGain` vs C++ soft-limiter post-processing reducing output level.
- **Crest factor lower in JSyn** (−5 to −23 dB vs C++) — JSyn output is more compressed/clipped; C++ has wider dynamic range.
- **ZCR broadly similar** for tonal engines (VA, FM, Chord, String, Modal); Additive and Waveshaping show larger ZCR deltas indicating harmonic content differences between ports.
- **Speech engine is the closest match** (Δ RMS +0.001 raw, Δ crest −1.0) — most faithful port overall.
- **Particle engine is silent in JSyn full-voice render** (RMS ≈ 0.0000) — likely unimplemented or incorrectly wired in the Kotlin `DspVoice` signal chain.
- **Wavetable and Modal raw renders clip at 1.0 peak in JSyn** — gain staging issue upstream of the soft-limiter stage.

### Priority Follow-ups

1. **Particle engine** — investigate why `DspVoice` produces silence; likely missing engine registration or wiring in `DefaultWiringGraph`.
2. **Wavetable / Modal clipping** — audit per-engine `outGain` values and the `tanh` soft-limiter threshold for these two engines.
3. **Sample rate alignment** — Kotlin hardcodes 44100 Hz vs C++ 48000 Hz; consider making the JSyn test configurable to better isolate algorithmic differences from resampling artefacts.
