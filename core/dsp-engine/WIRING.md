# Orpheus DSP Signal Wiring Diagram

**Authoritative source**: `core/dsp-engine/src/commonMain/kotlin/.../DefaultWiringGraph.kt`
**C++ runtime**: `liborpheus_dsp/src/orpheus_graph.cpp`, `orpheus_units.cpp`

## Graph Overview

```
                    ┌─────────────────────────────────────────────────────┐
                    │              SYNTH VOICE PATH                       │
                    │                                                     │
  DuoVoice 0-5 ──→ Voice A/B ──→ Vol ──→ Pan L/R ──→ Sum Tree ──→ MasterVol
  (12 voices)       (Engine 0      (per-voice)  (constant    (groups    (mvL/mvR)
                     or Plaits)                   power)      of 4)        │
                    │                                                     │
                    │  ┌─── warps_source_buffers[0] SYNTH (+=, 1/8 norm)  │
                    │  │    (double-buffered → warps_synth_read)          │
                    └──┤                                                  │
                       │  ┌─── warps_dry_scale: out *= (1-mix)            │
                       └──┘                                               │
                                                                          ▼
                    ┌─────────────────────────────────────────────────────────┐
                    │                    GRAINS (Clouds)                       │
                    │  mvL ──→ inputA    Bypassed by default                  │
                    │  mvR ──→ inputB    (clouds_bypass, dry_wet)             │
                    │                                                         │
                    │  Also writes: warps_source_buffers[1] += (L+R)*0.5*1/3 │
                    │  Dry atten: out *= (1-mix) when DRUMS is Warps source  │
                    └───────────────┬─────────────────────────────────────────┘
                                    │
                    ┌───────────────▼─────────────────────────────────────────┐
                    │              RESONATOR (Rings)                           │
                    │                                                         │
                    │  Excitation inputs (4-way):                             │
                    │    drumChainGain × drumSum ──→ drumExGain ──┐           │
                    │    grains.out ──→ synthExGain ──────────────┤           │
                    │                                             ▼           │
                    │    exciteSum L+R ──→ mono ──→ rings.input              │
                    │                                                         │
                    │  Output = wet*wetGain + dry*dryGain + bypass           │
                    │  Also writes: warps_source_buffers[4] = out_r          │
                    └───────────────┬─────────────────────────────────────────┘
                                    │
                    ┌───────────────▼─────────────────────────────────────────┐
                    │              DRIVE (Limiter)                             │
                    │  resoOutL ──→ driveL.input                              │
                    │  resoOutR ──→ driveR.input                              │
                    └───────┬───────┬─────────────────────────────────────────┘
                            │       │
              ┌─────────────┘       └─────────────────┐
              ▼                                       ▼
┌─────────────────────────┐             ┌─────────────────────────┐
│      DELAY (Dual)       │             │     REVERB (Dattorro)   │
│ grains.out ──→ inputA   │             │ driveL ──→ inputA       │
│ driveL ──→ inputA       │             │ driveR ──→ inputB       │
│ warps.out ──→ inputA    │             │                         │
│ looper.out ──→ inputA   │             │ Parallel wet-only send  │
│ bender.aux ──→ inputA   │             └────────────┬────────────┘
│ lfo.out ──→ inputC (mod)│                          │
└────────────┬────────────┘                          │
             │                                       │
             ▼                                       ▼
┌════════════════════════════════════════════════════════════════════┐
║                        MASTER OUT                                  ║
║                                                                    ║
║  Inputs (all sum into inputA/inputB):                             ║
║    delay.out L/R                                                   ║
║    reverb.out L/R                                                  ║
║    warps.out L/R          ◄── Warps output goes directly to master║
║    perStringBender L/R                                             ║
║    drumDirectLimiter L/R  ◄── Drum MAIN path (bypasses FX chain)  ║
║                                                                    ║
║  Processing: pan → volume → peak → tanh(saturation) → output     ║
╚════════════════════════════════════════════════════════════════════╝
```

## Drum Signal Paths

Drums have TWO routing modes controlled by `drum_direct_gain` / `drum_chain_gain`:

```
Drum Voices 12-14 ──→ Grids triggers ──→ PLAITS render
        │
        ├──→ drum_mix gain (3.2 × drum_mix)
        │
        ├──→ warps_source_buffers[1] += out * 1/3   (DRUMS Warps source)
        │    (double-buffered → warps_drums_read)
        │
        ├──→ warps_dry_scale: out *= (1-mix) when DRUMS is Warps source
        │
        └──→ Vol ──→ Pan ──→ drumSum L/R
                                  │
                    ┌─────────────┴─────────────┐
                    │                           │
              drumChainGain               drumDirectGain
              (FX path, default=0)        (MAIN path, default=1)
                    │                           │
                    ▼                           ▼
             Main Resonator            Drum Resonator ──→ Limiter
             excitation input          (dry+wet mix)        │
                                                            ▼
                                                       Master Out
```

## Warps Source Routing (Shared Engine Buffers)

Warps reads from shared engine buffers, NOT from graph wire connections.
Sources are double-buffered where needed to decouple from graph execution order.

| # | Source | Written by | Accumulate | Norm | Double-buf | Dry Atten | Wet Boost |
|---|--------|------------|-----------|------|------------|-----------|-----------|
| 0 | SYNTH | unit_process_plaits (v0-7) + unit_process_duo_voice | += | 1/8 | YES | 1-mix (carrier only) | 4.0x |
| 1 | DRUMS | unit_process_plaits (v12-14) | += | 1/3 | YES | 1-mix (carrier only) | 2.0x |
| 2 | REPL | unit_process_plaits (v8-11) + unit_process_duo_voice | += | 1/4 | YES | 1-mix (carrier only) | 2.0x |
| 3 | LFO | unit_process_hyper_lfo | = | none | no | none | - |
| 4 | RESONATOR | unit_process_rings (main only) | = | none | no | none | - |
| 5 | FEEDBACK | unit_process_warps (own output) | = | none | no | none | 1.0x |
| 6 | FLUX | unit_process_marbles (X1 CV) | = | none | no | none | - |
| 7 | BENDER | unit_process_bender (audio) | = | none | no | none | - |
| 8 | STRINGS | unit_process_per_string_bender | = | none | no | none | - |

### Double-Buffering (graph execution order decoupling)

```
Frame N start:
  1. Copy warps_source_buffers[0,1,2] → warps_synth/drums/repl_read  (previous frame's data)
  2. Zero warps_source_buffers[0,1,2]  (prepare for this frame's accumulation)
  3. Process all graph units (voices accumulate, Warps reads from _read buffers)
```

### Insert Routing (dry carrier replacement)

When Warps mix > 0, carrier source voices are attenuated in the dry path:
- `warps_dry_scale()` returns `1.0 - mix` for matching voice indices
- Applied AFTER source buffer accumulation (Warps gets full signal)
- Wet output boosted by inverse normalization to replace dry at matching level

### Warps Internal Processing

```
carrier_buf ──→ int16 ──→ MI SaturatingAmplifier ──→ 6x SRC up ──→ Xmod algo ──→ 6x SRC down ──→ int16 ──→ float
mod_buf ────→ int16 ──→ MI SaturatingAmplifier ──→ 6x SRC up ──→            ──→
                                                                              ▼
                                                                    out * mix * wet_boost ──→ master
```

**Block size**: 64 samples (NOT kMaxBlockSize=96). Avoids SRC downsampler code path mismatch.

## Other Shared Buffer Paths (not graph wires)

| Buffer | Written by | Read by | Purpose |
|--------|-----------|---------|---------|
| lfo_output_buffer | unit_process_hyper_lfo | voice FM, Marbles clock | Audio-rate LFO |
| voice_fm_buffer[v] | unit_process_plaits/duo_voice | voice FM cross-mod | Per-sample FM |
| voice_envelope[v] | unit_process_plaits/duo_voice | voice coupling | Envelope follower |
| marbles_cv_output[2] | unit_process_marbles | voice FM (FLUX source) | Block-rate Flux CV |
| marbles_t/x buffers | unit_process_marbles | voice triggers/pitch | Per-sample trigger/CV |

## Maintaining This Document

When modifying signal routing:
1. Update this document to reflect the change
2. Run `./gradlew :core:dsp-engine:jvmTest --tests "*ExportOdwgTest*"` to regenerate ODWG
3. Rebuild C++ tests: `cmake --build liborpheus_dsp/build-desktop --target orpheus_dsp_test`
4. Run Warps isolation test to verify levels: check `test/output/warps_sd_*.wav`
5. Verify no clipping in CleanPatch level sweep
