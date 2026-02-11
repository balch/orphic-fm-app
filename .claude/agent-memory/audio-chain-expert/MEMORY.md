# Audio Chain Expert Memory

## JSyn Architecture (Investigated 2026-02-09)
See [jsyn-architecture.md](jsyn-architecture.md) for detailed findings.

### Key Facts
- All UnitGenerators are added individually via `synth.add()` -- NO Circuit usage in production code
- `SynthVoice.kt` (legacy, unused) is the ONLY file using JSyn `Circuit` class
- `DspVoice` replaced `SynthVoice`: uses `audioEngine.addUnit()` for each unit individually (32+ units per voice)
- Total: ~12 voices x 32 units + ~17 plugins with their own units = 400+ individual UnitGenerators on the JSyn synth
- AudioEngine.addUnit() dispatches to synth.add() via a `when` block on concrete types
- Composite units (JsynLooperUnit, JsynClockUnit) have multiple internal JSyn UGs that are individually added

### Enable/Disable Patterns
- NO runtime enable/disable of voices or engines -- all 12 voices always run
- Plaits engines: null engine check in `generate()` outputs zeros; engine swap via `setEngine()`
- Resonator: `enabled` boolean flag, bypasses in `generate()` (pass-through)
- Voice source switching: gain-based muting (`oscGain.inputB.set(0/1)`, `plaitsGain.inputB.set(1/0)`)
- Drum bypass: gain-based routing (`drumChainGainL/R` vs `drumDirectGainL/R`)
- No `UnitGenerator.setEnabled()` or `start()/stop()` calls except for Looper readers/writers

### Plugin Registration
- `DspPlugin.audioUnits` lists all AudioUnit instances for engine registration
- `DspWiringGraph.registerUnits()` iterates all plugins and calls `audioEngine.addUnit()` on each unit
- `DspWiringGraph.wirePlugins()` then calls each plugin's `initialize()` for internal wiring
- Plugins self-register via Metro DI `@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())`

### Signal Path Summary
- Voices -> per-voice pan -> voiceSum buses -> Grains (parallel) + Resonator (excitation)
- Resonator -> Distortion (parallel clean/drive) -> Stereo sum
- Distortion -> Delay (send) + Reverb (send)
- Delay wet -> Stereo sum; Reverb -> Stereo sum
- Drums: dual path (bypass=true: direct->limiter->stereo; bypass=false: through effects chain)
- All effects also -> Looper input; Looper -> Stereo sum
- Warps: voiceSum -> meta-modulation -> Stereo sum
- Bender audio -> Stereo sum + Delay send
- TTS -> Stereo sum (bypasses most effects)

### Platform Abstraction
- `AudioUnit` (interface) -> concrete Jsyn wrappers (JsynMultiplyWrapper, etc.)
- `AudioInput`/`AudioOutput` -> `JsynAudioInput`/`JsynAudioOutput` wrapping UnitInputPort/UnitOutputPort
- Complex units (JsynPlaitsUnit, JsynReverbUnit, JsynResonatorUnit) extend `UnitGenerator` directly
- Simple units wrapped via pattern: `JsynXxxWrapper` with `internal val jsUnit/jsOsc/etc`
