# Dynamic Voice Pan + Dattorro Reverb Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add dynamic per-voice stereo panning and Dattorro plate reverb to the C++ graph engine, matching JSyn behavior for A/B comparison.

**Architecture:** Port existing Kotlin `DattorroReverb` algorithm to C++ as `UNIT_REVERB` graph unit. Extend port map for per-voice pan gains. Both changes are in shared code (commonMain + liborpheus_dsp) so Android and desktop both benefit.

**Tech Stack:** C++ (liborpheus_dsp), Kotlin Multiplatform (commonMain), ODWG binary graph format

---

### Task 1: Dynamic Voice Pan — Port Map Entries

Add 24 port map entries (L+R per voice) to `DefaultWiringGraph.kt` so the C++ graph can receive pre-computed pan gains at runtime.

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt`

**Step 1: Add pan port map entries to the portMap block**

In `DefaultWiringGraph.kt`, add 24 entries inside the existing `portMap { }` block (after the quad_vol entries, line ~129):

```kotlin
// Per-voice pan gains (constant-power, computed in Kotlin)
for (v in 0 until 12) {
    map("org.balch.orpheus.plugins.stereo", "voice_pan_L_$v", "v${v}_pL", IPORT_INPUT_B)
    map("org.balch.orpheus.plugins.stereo", "voice_pan_R_$v", "v${v}_pR", IPORT_INPUT_B)
}
```

These map `nativeSetPort("org.balch.orpheus.plugins.stereo", "voice_pan_L_0", 0.707)` → sets `v0_pL` inputB to 0.707.

**Step 2: Build and verify**

Run: `cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp && ./gradlew :core:dsp-engine:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt
git commit -m "feat(dsp): Add per-voice pan port map entries to ODWG graph"
```

---

### Task 2: Dynamic Voice Pan — Kotlin Forwarding

When `setPluginPort("stereo", "voice_pan_N", value)` is called, compute cos/sin constant-power gains and forward L/R to C++ graph.

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DspSynthEngine.kt`

**Step 1: Add pan forwarding in `setPluginPort`**

In `DspSynthEngine.kt`, the `setPluginPort()` method at line 774 already calls `nativeBridge?.nativeSetPort(pluginUri, symbol, value.asFloat())` for all ports. For stereo voice_pan ports, we need to **intercept** and send L/R gains instead.

Add this before the generic `nativeBridge?.nativeSetPort(...)` call:

```kotlin
override fun setPluginPort(pluginUri: String, symbol: String, value: PortValue): Boolean {
    // Intercept voice pan to compute constant-power L/R gains for C++ graph
    if (pluginUri == "org.balch.orpheus.plugins.stereo" && symbol.startsWith("voice_pan_")) {
        val pan = value.asFloat()  // -1..+1
        val angle = ((pan + 1f) * 0.5f) * (kotlin.math.PI.toFloat() * 0.5f)
        val leftGain = kotlin.math.cos(angle)
        val rightGain = kotlin.math.sin(angle)
        val voiceIndex = symbol.removePrefix("voice_pan_").toIntOrNull()
        if (voiceIndex != null) {
            nativeBridge?.nativeSetPort(pluginUri, "voice_pan_L_$voiceIndex", leftGain)
            nativeBridge?.nativeSetPort(pluginUri, "voice_pan_R_$voiceIndex", rightGain)
        }
    } else {
        nativeBridge?.nativeSetPort(pluginUri, symbol, value.asFloat())
    }
    val result = pluginProvider.getPlugin(pluginUri)?.setPortValue(symbol, value) ?: false
    // Keep bendFlow in sync when Bender BEND is set externally (gesture, MIDI, AI)
    if (result && pluginUri == BENDER_URI && symbol == BenderSymbol.BEND.symbol) {
        _bendFlow.value = value.asFloat()
    }
    return result
}
```

**Step 2: Add pan sync in `syncNativeBridgeState()`**

After the quad volume sync (line ~190), add:

```kotlin
// Sync per-voice pan
val stereoPlugin = pluginProvider.stereoPlugin
for (v in 0 until 12) {
    val pan = stereoPlugin.getVoicePan(v)
    val angle = ((pan + 1f) * 0.5f) * (kotlin.math.PI.toFloat() * 0.5f)
    bridge.nativeSetPort("org.balch.orpheus.plugins.stereo", "voice_pan_L_$v", kotlin.math.cos(angle))
    bridge.nativeSetPort("org.balch.orpheus.plugins.stereo", "voice_pan_R_$v", kotlin.math.sin(angle))
}
```

**Important:** Check if `stereoPlugin.getVoicePan(v)` exists. If not, use the default pan array matching the graph:
```kotlin
val defaultPans = floatArrayOf(0f, 0f, -0.3f, -0.3f, 0.3f, 0.3f, -0.7f, 0.7f, 0f, 0f, 0f, 0f)
```

**Step 3: Build and verify**

Run: `./gradlew :core:dsp-engine:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DspSynthEngine.kt
git commit -m "feat(dsp): Forward dynamic voice pan as constant-power L/R gains to C++"
```

---

### Task 3: Reverb — C++ Engine State

Add reverb state (32KB buffer + parameters) to `OrpheusEngine`.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.h`

**Step 1: Add reverb state to `OrpheusEngine` struct**

Add after the HyperLFO section (line ~176, before the closing `};`):

```cpp
// ── Dattorro Plate Reverb ─────────────────────────
static constexpr int kReverbBufferSize = 32768;
static constexpr int kReverbMask = kReverbBufferSize - 1;
float reverb_buffer[kReverbBufferSize] = {};
int   reverb_write_pos{0};

// LFO state (two cosine oscillators for modulated delay reads)
float reverb_lfo1_phase{0.0f};
float reverb_lfo2_phase{0.0f};
float reverb_lfo1_value{0.0f};
float reverb_lfo2_value{0.0f};

// LP filter state
float reverb_lp_decay1{0.0f};
float reverb_lp_decay2{0.0f};

// Parameters (atomics, written from UI)
std::atomic<float> reverb_amount{0.0f};     // 0-1, wet level
std::atomic<float> reverb_time{0.5f};       // 0-1, feedback/decay
std::atomic<float> reverb_damping{0.7f};    // 0-1, LP coefficient
std::atomic<float> reverb_diffusion{0.625f};// 0-1, allpass coefficient
std::atomic<int>   reverb_bypass{1};        // self-bypass when amount<=0.001
```

**Step 2: Build C++ to verify**

Run: `cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp/liborpheus_dsp && cmake --build build 2>&1 | tail -5`
Expected: Build succeeds (no new code references the fields yet)

**Step 3: Commit**

```bash
git add liborpheus_dsp/src/orpheus_engine.h
git commit -m "feat(dsp): Add Dattorro reverb state to OrpheusEngine"
```

---

### Task 4: Reverb — C++ Port Routing

Route reverb parameter changes from `orpheus_engine_set_port` to the new atomics.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_engine.cpp`

**Step 1: Add reverb port routing**

In `orpheus_engine_set_port()`, add a new `else if` block after the distortion block (line ~692):

```cpp
else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.reverb") == 0) {
    if (std::strcmp(symbol, "amount") == 0) {
        engine->reverb_amount.store(value, std::memory_order_relaxed);
        engine->reverb_bypass.store(value <= 0.001f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(symbol, "time") == 0)
        engine->reverb_time.store(value, std::memory_order_relaxed);
    else if (std::strcmp(symbol, "damping") == 0)
        engine->reverb_damping.store(value, std::memory_order_relaxed);
    else if (std::strcmp(symbol, "diffusion") == 0)
        engine->reverb_diffusion.store(value, std::memory_order_relaxed);
}
```

**Step 2: Build and verify**

Run: `cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp/liborpheus_dsp && cmake --build build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add liborpheus_dsp/src/orpheus_engine.cpp
git commit -m "feat(dsp): Route reverb parameters to engine atomics"
```

---

### Task 5: Reverb — C++ Dattorro Processor

Port the Kotlin `DattorroReverb` algorithm to C++ as `unit_process_reverb()`.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_units.h` — Add declaration
- Modify: `liborpheus_dsp/src/orpheus_units.cpp` — Add full implementation

**Step 1: Add declaration to orpheus_units.h**

Add after `unit_process_hyper_lfo` declaration (line 26):

```cpp
void unit_process_reverb(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
```

**Step 2: Implement the Dattorro plate reverb in orpheus_units.cpp**

Add at the end of the file. This is a direct port of `DattorroReverb.kt`:

```cpp
// ═══════════════════════════════════════════════════════════════════════
// Dattorro Plate Reverb (ported from DattorroReverb.kt / MI Rings)
// ═══════════════════════════════════════════════════════════════════════

void unit_process_reverb(GraphUnit* u, OrpheusEngine* engine,
                         int num_frames, float sample_rate) {
    // Self-bypass
    if (engine->reverb_bypass.load(std::memory_order_relaxed)) {
        std::memset(u->output_buffers[OPORT_OUT], 0, num_frames * sizeof(float));
        std::memset(u->output_buffers[OPORT_OUT_RIGHT], 0, num_frames * sizeof(float));
        return;
    }

    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    // Load parameters
    const float amount = engine->reverb_amount.load(std::memory_order_relaxed);
    const float krt    = engine->reverb_time.load(std::memory_order_relaxed);
    const float klp    = engine->reverb_damping.load(std::memory_order_relaxed);
    const float kap    = engine->reverb_diffusion.load(std::memory_order_relaxed);
    const float gain   = 0.5f;  // inputGain

    // Reference delay lengths at 48kHz, scaled to runtime sample rate
    const float rate_ratio = sample_rate / 48000.0f;

    // Input allpass lengths
    const int ap1_len = static_cast<int>(150 * rate_ratio);
    const int ap2_len = static_cast<int>(214 * rate_ratio);
    const int ap3_len = static_cast<int>(319 * rate_ratio);
    const int ap4_len = static_cast<int>(527 * rate_ratio);

    // Loop allpass + delay lengths
    const int dap1a_len = static_cast<int>(2182 * rate_ratio);
    const int dap1b_len = static_cast<int>(2690 * rate_ratio);
    const int del1_len  = static_cast<int>(4501 * rate_ratio);
    const int dap2a_len = static_cast<int>(2525 * rate_ratio);
    const int dap2b_len = static_cast<int>(2197 * rate_ratio);
    const int del2_len  = static_cast<int>(6312 * rate_ratio);

    // Bases (cumulative offsets)
    const int ap1_base  = 0;
    const int ap2_base  = ap1_base  + ap1_len + 1;
    const int ap3_base  = ap2_base  + ap2_len + 1;
    const int ap4_base  = ap3_base  + ap3_len + 1;
    const int dap1a_base = ap4_base + ap4_len + 1;
    const int dap1b_base = dap1a_base + dap1a_len + 1;
    const int del1_base  = dap1b_base + dap1b_len + 1;
    const int dap2a_base = del1_base + del1_len + 1;
    const int dap2b_base = dap2a_base + dap2a_len + 1;
    const int del2_base  = dap2b_base + dap2b_len + 1;

    // LFO modulation tap offsets
    const float del1_tap = 4460.0f * rate_ratio;
    const float del1_lfo_amp = 40.0f * rate_ratio;
    const float del2_tap = 6261.0f * rate_ratio;
    const float del2_lfo_amp = 50.0f * rate_ratio;

    // LFO frequencies (updated every 32 samples)
    const float lfo1_freq = 0.5f / sample_rate * 32.0f;
    const float lfo2_freq = 0.3f / sample_rate * 32.0f;

    float* buf = engine->reverb_buffer;
    const int mask = OrpheusEngine::kReverbMask;
    int wp = engine->reverb_write_pos;
    float lp1 = engine->reverb_lp_decay1;
    float lp2 = engine->reverb_lp_decay2;
    float lfo1_phase = engine->reverb_lfo1_phase;
    float lfo2_phase = engine->reverb_lfo2_phase;
    float lfo_val0 = engine->reverb_lfo1_value;
    float lfo_val1 = engine->reverb_lfo2_value;

    // Lambdas for buffer access
    auto read_buf = [&](int offset) -> float {
        return buf[(wp + offset) & mask];
    };
    auto write_buf = [&](int offset, float value) {
        buf[(wp + offset) & mask] = value;
    };
    auto allpass = [&](int base, int len, float input, float coeff) -> float {
        float tail = read_buf(base + len - 1);
        float v = input + tail * coeff;
        write_buf(base, v);
        return v * (-coeff) + tail;
    };
    auto interpolate = [&](int base, float offset, float lfo_val, float amplitude) -> float {
        float mod_offset = offset + amplitude * lfo_val;
        int int_part = static_cast<int>(mod_offset);
        float frac = mod_offset - int_part;
        float a = buf[(wp + int_part + base) & mask];
        float b = buf[(wp + int_part + base + 1) & mask];
        return a + (b - a) * frac;
    };

    for (int i = 0; i < num_frames; i++) {
        // Advance write pointer (decrement, wrapping)
        wp = (wp - 1 + OrpheusEngine::kReverbBufferSize) & mask;

        // Update LFOs every 32 samples (cosine approximation)
        if ((wp & 31) == 0) {
            lfo1_phase += lfo1_freq;
            if (lfo1_phase >= 1.0f) lfo1_phase -= 1.0f;
            lfo_val0 = std::cos(lfo1_phase * 2.0f * 3.14159265f);

            lfo2_phase += lfo2_freq;
            if (lfo2_phase >= 1.0f) lfo2_phase -= 1.0f;
            lfo_val1 = std::cos(lfo2_phase * 2.0f * 3.14159265f);
        }

        // Mono sum input
        float acc = (in_l[i] + in_r[i]) * gain;

        // 4 input allpass diffusers
        acc = allpass(ap1_base, ap1_len, acc, kap);
        acc = allpass(ap2_base, ap2_len, acc, kap);
        acc = allpass(ap3_base, ap3_len, acc, kap);
        acc = allpass(ap4_base, ap4_len, acc, kap);

        float apout = acc;

        // Path 1 (left)
        acc = apout;
        acc += interpolate(del2_base, del2_tap, lfo_val1, del2_lfo_amp) * krt;
        lp1 += klp * (acc - lp1);
        acc = lp1;
        acc = allpass(dap1a_base, dap1a_len, acc, -kap);
        acc = allpass(dap1b_base, dap1b_len, acc, kap);
        write_buf(del1_base, acc);
        out_l[i] = acc * 2.0f * amount;

        // Path 2 (right)
        acc = apout;
        acc += interpolate(del1_base, del1_tap, lfo_val0, del1_lfo_amp) * krt;
        lp2 += klp * (acc - lp2);
        acc = lp2;
        acc = allpass(dap2a_base, dap2a_len, acc, kap);
        acc = allpass(dap2b_base, dap2b_len, acc, -kap);
        write_buf(del2_base, acc);
        out_r[i] = acc * 2.0f * amount;
    }

    // Save state
    engine->reverb_write_pos = wp;
    engine->reverb_lp_decay1 = lp1;
    engine->reverb_lp_decay2 = lp2;
    engine->reverb_lfo1_phase = lfo1_phase;
    engine->reverb_lfo2_phase = lfo2_phase;
    engine->reverb_lfo1_value = lfo_val0;
    engine->reverb_lfo2_value = lfo_val1;
}
```

**Step 3: Build and verify**

Run: `cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp/liborpheus_dsp && cmake --build build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (function is compiled but not yet called from graph)

**Step 4: Commit**

```bash
git add liborpheus_dsp/src/orpheus_units.h liborpheus_dsp/src/orpheus_units.cpp
git commit -m "feat(dsp): Implement Dattorro plate reverb processor for UNIT_REVERB"
```

---

### Task 6: Reverb — Wire Into Graph Dispatcher

Add `UNIT_REVERB` case to the graph process switch so the reverb unit gets called.

**Files:**
- Modify: `liborpheus_dsp/src/orpheus_graph.cpp`

**Step 1: Add case to switch**

In `orpheus_graph_process()`, add after the `UNIT_HYPER_LFO` case (line ~354):

```cpp
case UNIT_REVERB:
    unit_process_reverb(u, engine, num_frames, sr); break;
```

**Step 2: Build and verify**

Run: `cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp/liborpheus_dsp && cmake --build build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add liborpheus_dsp/src/orpheus_graph.cpp
git commit -m "feat(dsp): Add UNIT_REVERB to graph process dispatcher"
```

---

### Task 7: Reverb — Kotlin Graph Wiring

Add reverb unit to `DefaultWiringGraph.kt` as a parallel send from drive output, summing wet output into the clip stage.

**Files:**
- Modify: `core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt`

**Step 1: Add reverb unit and wiring**

In `DefaultWiringGraph.kt`, after the drive section and before the warps section (between lines ~91 and ~93):

```kotlin
// Reverb (Dattorro plate) — parallel send from drive output
// Wet-only output sums into clip inputs alongside delay output
val reverb = reverb("reverb")
driveL.out to reverb.inputA
driveR.out to reverb.inputB
```

Then modify the clip wiring to also receive reverb output. After the delay→clip connections (line ~115-116), add:

```kotlin
reverb.out to clipL.input
reverb.outRight to clipR.input
```

The `port_prepare` auto-summing (up to 4 sources) handles this — delay.out + reverb.out both feed clipL.input.

**Step 2: Build and verify**

Run: `./gradlew :core:dsp-engine:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add core/dsp-engine/src/commonMain/kotlin/org/balch/orpheus/core/audio/dsp/DefaultWiringGraph.kt
git commit -m "feat(dsp): Wire Dattorro reverb into C++ graph as parallel send"
```

---

### Task 8: Integration Build + Test

Build the full C++ library and Kotlin module, verify the graph loads and reverb/pan work.

**Files:**
- No new files — verification only

**Step 1: Build C++ library**

Run: `cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp/liborpheus_dsp && cmake --build build 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 2: Build Kotlin**

Run: `cd /Users/balch/Source/orphic-fm-app/.worktrees/cpp-dsp && ./gradlew :core:dsp-engine:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

**Step 3: Build full app**

Run: `./gradlew :apps:composeApp:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

**Step 4: Update gap analysis**

Update `docs/plans/2026-03-07-cpp-dsp-parity-gap-analysis.md`:
- Gap #5 (Reverb): mark **FIXED**
- Gap #19 (Per-voice pan): mark **FIXED**

**Step 5: Commit**

```bash
git add docs/plans/2026-03-07-cpp-dsp-parity-gap-analysis.md
git commit -m "docs: Mark reverb and per-voice pan as FIXED in parity gap analysis"
```
