# JSyn Architecture Deep Dive

## Unit Registration

### How UnitGenerators Are Added
All units are added individually to JSyn's `Synthesizer` via `synth.add(unitGenerator)`. There is no
use of JSyn `Circuit` in the active production code path.

The `OrpheusAudioEngine` (at `core/audio/src/jvmMain/.../OrpheusAudioEngine.kt`) wraps `JSyn.createSynthesizer()`
and exposes `addUnit(AudioUnit)` which dispatches via `when` to extract the underlying JSyn UnitGenerator:
- Simple wrappers: `synth.add(unit.jsUnit)` or `synth.add(unit.jsOsc)` etc.
- Composite units: adds multiple internal UGs (e.g., JsynLooperUnit adds 6 UGs, JsynClockUnit adds 2)
- Direct UnitGenerator subclasses (JsynPlaitsUnit, JsynReverbUnit, JsynTtsPlayerUnit, JsynResonatorUnit):
  `synth.add(unit)` via the fallback `is com.jsyn.unitgen.UnitGenerator` branch

### Registration Points
1. `DspWiringGraph.registerUnits()`: Iterates all plugins' `audioUnits` lists + local units
2. `DspVoice` constructor: Each of 12 voices registers ~32 units individually
3. `DspSynthEngine.setupAutomation()`: Creates automation helper units (players, scalers)

### Total Unit Count Estimate
- 12 voices x ~32 units each = ~384 units
- ~17 plugins with varying unit counts (1-30+ each)
- Graph-level units (drum routing, buses, etc.): ~17
- Automation units: ~20+
- Total: likely 450-500+ individual UnitGenerators

## Circuit Usage
Only one file references `Circuit`: `SynthVoice.kt` at
`apps/composeApp/src/jvmMain/.../SynthVoice.kt`. This is LEGACY CODE -- superseded by `DspVoice.kt`
which uses the platform-abstracted `AudioUnit` approach instead.

## Voice Architecture

### DspVoice (per voice, 12 total)
Each voice contains ~32 AudioUnit instances registered individually:
- 2 oscillators (triangle, square)
- Waveform morphing chain (sharpness proxy, inverter, gains, mixer)
- Envelope (DAHDSR)
- VCA chain (vca, wobble, volume)
- FM chain (depth control, freq mixer, pitch scaler, feedback)
- Vibrato chain (scaler, mixer)
- Bender chain (scaler, mixer)
- Coupling chain (scaler, mixer, envelope follower)
- Plaits unit + mod depth controls
- Source selector (osc gain, plaits gain, source add)
- Gate fanout, hold ramp, CV pitch chain
- Linear ramps for wobble and volume

### Engine Switching (Plaits)
- `setEngineActive(active)`: gain-based crossfade (hard 0/1 switch, not ramped!)
  - `oscGain.inputB.set(0.0)` / `plaitsGain.inputB.set(1.0)` for Plaits active
  - Reversed for oscillator mode
- Potential click issue: no ramping on this switch

## Enable/Disable Patterns

### What Runs When Not In Use
ALL voices and units run continuously. There is no per-voice or per-unit enable/disable:
- Inactive voices produce zero output (gate=0, envelope closed)
- Plaits with null engine: `generate()` outputs zeros (tight loop)
- Effects with zero input: process zeros (no bypass optimization)

### Effect Bypass Patterns
1. **Resonator**: `enabled` boolean in generate(), passes through input unchanged when false
2. **Drum routing**: Gain-based muting (inputB = 0.0 or 1.0 on Multiply units)
3. **Distortion mix**: Parallel clean/distorted paths with complementary gains
4. **Delay mix**: Wet/dry gains controlled by automation infrastructure

### No Dynamic Start/Stop
The only `start()/stop()` calls on UnitGenerators are:
- Looper readers/writers (recording/playback lifecycle)
- AutomationPlayer.reader.start()
- LineOut.start() at engine startup
