# Horn — Leslie Speaker Effect

## Summary

A standalone Leslie speaker simulation effect that processes any audio source through a dual-rotor rotary cabinet model with real-time visualization. Based on the MI Ensemble effect (`plaits/dsp/fx/ensemble.h`), copied and adapted (removing MI dependencies like `stmlib`, `FxEngine`) into an Orpheus-native class, then extended with crossover filtering, independent rotor inertia, and phase-synchronized animations.

## Controls

| Control | Type    | Range   | Default | Description |
|---------|---------|---------|---------|-------------|
| Speed   | Float   | 0.0–1.0 | 0.5     | Base rotor rate (both rotors, scaled by Ratio) |
| Ratio   | Float   | 0.0–1.0 | 0.5     | Horn-to-woofer speed relationship. Center = classic ~1:9 Leslie ratio. Extremes = equal or inverted |
| Depth   | Float   | 0.0–1.0 | 0.5     | Doppler delay line depth. Controls pitch wobble intensity (applied within the wet signal) |
| Amount  | Float   | 0.0–1.0 | 0.5     | Modulation intensity within the wet signal. Controls how strongly the rotary effect colors the processed audio |
| Mix     | Float   | 0.0–1.0 | 0.0     | Dry/wet crossfade. Default off per mix knob pattern |
| Brake   | Boolean | on/off  | off     | Ramps both rotors to zero speed with deceleration inertia |

**Signal chain**: Input → Crossover → Horn modulator (Amount, Depth) + Woofer modulator (Amount, Depth) → Recombine → Mix (dry/wet crossfade) → Output. Amount and Depth shape the wet signal character; Mix controls how much of it you hear.

**Self-bypass**: `bypass = mix <= 0.001f` — zero CPU when mix is off. This is the primary on/off mechanism. When mix is 0, the entire unit is bypassed and no DSP processing occurs.

## DSP Architecture (C++)

### Source Material

Copy and adapt `plaits/dsp/fx/ensemble.h` into `liborpheus_dsp/src/orpheus_horn.h`, removing MI dependencies (`stmlib`, `FxEngine`, `plaits/resources.h`) and replacing with Orpheus-native equivalents (inline sine, simple delay buffer). The MI Ensemble provides:
- Dual LFOs (`phase_1_` at 0.75 Hz, `phase_2_` at 6.57 Hz)
- Three-phase delay modulation (120° offsets) creating 3-voice rotary chorus
- Cross-channel routing with left/right mixing
- Delay line interpolation for smooth modulation

### Extensions

1. **Crossover filter**: 2nd-order Linkwitz-Riley split (~800 Hz, tunable during development). Treble band routes to horn modulator, bass band to woofer modulator. This frequency-dependent rotation is the defining Leslie characteristic.

2. **Independent speed targets with inertia**: Each rotor has:
   - `target_speed`: set by Speed knob × Ratio factor
   - `current_speed`: slews toward target via exponential smoothing
   - Ramp-up time: ~1 second (motor spin-up)
   - Ramp-down time: ~3 seconds (momentum decay)
   - Brake sets both targets to 0

3. **Ratio mapping**: `horn_target = speed`, `woofer_target = speed × ratio_factor`. At center position (0.5), `ratio_factor ≈ 0.11` (classic Leslie). Left extreme = equal speed. Right extreme = inverted (woofer faster than horn).

4. **Amount and Depth**: Amount controls modulation intensity within the wet signal (how strongly the 3-phase delay modulation colors the audio). Depth controls delay line depth (Doppler pitch wobble). Both are applied before the dry/wet Mix crossfade.

5. **Phase visualization export**: Export normalized `horn_phase` (0.0–1.0) and `woofer_phase` (0.0–1.0) via the existing ring buffer visualization system. Add `VIZ_HORN_PHASE` and `VIZ_WOOFER_PHASE` entries to the `VizChannel` enum in `orpheus_viz.h`. UI-side interpolation (extrapolating phase based on last known speed) will smooth the ~94 Hz update rate to display refresh rate.

### C++ Integration Steps

1. **`orpheus_horn.h`** — Horn effect class definition (ported from MI ensemble.h, Orpheus-native)
2. **`orpheus_unit_horn.cpp`** — `unit_process_horn()` implementation (crossover, inertia, phase export)
3. **`orpheus_units.h`** — Add declaration: `void unit_process_horn(GraphUnit*, OrpheusEngine*, int, float);`
4. **`orpheus_graph.h`** — Add `UNIT_HORN` to `GraphUnitType` enum
5. **`orpheus_graph.cpp`** — Add dispatch case for `UNIT_HORN` → `unit_process_horn`
6. **`orpheus_engine.h`** — Add atomic fields for horn parameters (speed, ratio, depth, amount, mix, brake)
7. **`orpheus_engine.cpp`** — Add `set_port()` handler matching horn URI + symbol strings
8. **`orpheus_viz.h`** — Add `VIZ_HORN_PHASE` and `VIZ_WOOFER_PHASE` to `VizChannel` enum
9. **C++ test cases** in test suite

## Kotlin Integration Layer

Following the established Symbol → Plugin → ViewModel → Panel pattern.

### HornSymbol.kt

Port enumeration: `SPEED`, `RATIO`, `DEPTH`, `AMOUNT`, `MIX`, `BRAKE`.

Location: `core/plugin-api/src/commonMain/kotlin/.../symbols/HornSymbol.kt`

### HornPlugin.kt

State container forwarding port values to C++ via `audioEngine.setPort()`. Self-bypass when mix ≤ 0.001.

Location: `core/plugins/horn/src/commonMain/kotlin/.../HornPlugin.kt`

### HornFeature / HornViewModel.kt

MVI pattern following `LfoViewModel.kt` canonical reference:
- `HornFeature` interface extending `SynthFeature<HornUiState, HornPanelActions>` with `SynthControlDescriptor` (panelId, title, markdown docs, portControlKeys)
- `HornUiState`: all 6 control fields + `hornPhase: Float`, `wooferPhase: Float` for animation
- `HornPanelActions`: lambdas for each control
- `HornIntent`: sealed interface with one variant per control
- `controlFlow()` per symbol, plus phase data from visualization ring buffers
- `merge()` → `scan()` → `stateIn()`
- `companion object` with `previewFeature()` and `@Composable feature()`

Location: `features/horn/src/commonMain/kotlin/.../HornViewModel.kt`

### HornPanelRegistration.kt

Panel registration for DI discovery.

Location: `features/horn/src/commonMain/kotlin/.../HornPanelRegistration.kt`

### HornPanel.kt

Compose UI panel (see UI Design section).

Location: `features/horn/src/commonMain/kotlin/.../HornPanel.kt`

### Gradle Module Setup

- `core/plugins/horn/build.gradle.kts` — plugin module
- `features/horn/build.gradle.kts` — feature module
- Add both to `settings.gradle.kts`

### Wiring

Add `unit_process_horn` to the DSP graph as an effect node via `DefaultWiringGraph.kt`. Position in signal chain: **after warps, before delay** (`... → warps → horn → delay → reverb → ...`). Leslie is a modulation effect best placed before time-based effects (delay, reverb) so those effects process the already-rotated signal.

## UI Design

### Layout

```
┌─────────────────────────────────────────┐
│  LESLIE HORN                            │
├─────────────────────────────────────────┤
│                                         │
│  ◎ Concentric Rings  │  Cabinet Cutaway │
│     (phase view)     │  (cross-section) │
│                                         │
├─────────────────────────────────────────┤
│                                         │
│  SPEED  RATIO  DEPTH  AMOUNT  MIX  BRAKE│
│                                         │
└─────────────────────────────────────────┘
```

Top row: dual animations side by side. Bottom row: knobs and brake toggle.

### Color Theme — Blackout Crimson

- Background: `#080808` (near-black)
- Primary accent: `#cc2222` (horn glow, brighter)
- Secondary accent: `#881111` (woofer, darker crimson)
- Subtle borders/dividers: `#1a0808`
- Knob rings/labels: `#aa2222` / `#881111`
- Ember glow effects on rotors via `drawBehind { drawCircle() }` with radial gradient blur

### Concentric Rings Animation (Left)

- Inner ring = horn rotor (brighter crimson `#cc2222`, fast rotation)
- Outer ring = woofer rotor (darker crimson `#881111`, slow rotation)
- Gradient arc trails that smear with speed — faster = longer trails
- Ember glow at rotor head positions via radial gradients
- Driven by `hornPhase` / `wooferPhase` from DSP engine (with UI-side interpolation)
- Visible inertia on speed changes and brake

### Cabinet Cross-Section Animation (Right)

- Stylized louvered cabinet cutaway
- Horn rotor on top — curved shape with 3D foreshortening (scaleX oscillation)
- Woofer drum on bottom — wider paddle, same foreshortening at its own speed
- Shelf divider between rotors
- Ember glow on rotor elements via `Modifier.shadow()` or Canvas radial gradients
- Both driven by same phase data as concentric view
- Motion blur trails on horn tips for swoosh effect

### Compose Implementation

- `Canvas` composable for both visualizations
- `hornPhase` and `wooferPhase` drive `rotate()` / `drawArc()` transforms
- UI-side phase interpolation: extrapolate between DSP updates using last known speed for smooth 60fps animation
- Motion blur via semi-transparent arc trails at previous phase positions
- `BlendMode.Screen` or `BlendMode.Plus` for color blending in concentric view

### HornPanel.kt

Location: `features/horn/src/commonMain/kotlin/.../HornPanel.kt`

## Iterative Tuning Notes

These parameters are candidates for sound-design tweaking during implementation:
- Crossover frequency (~800 Hz starting point)
- Ramp-up / ramp-down time constants
- Ratio curve shape (linear vs logarithmic)
- Delay line depth range
- LFO frequency ranges beyond MI defaults
- Amplitude modulation depth (Leslie cabinets have significant AM from the directional horn)

## Out of Scope

- MIDI CC mapping (handled by existing MIDI feature)
- Preset save/load (handled by existing presets feature)
- Stereo mic simulation (potential future enhancement)
