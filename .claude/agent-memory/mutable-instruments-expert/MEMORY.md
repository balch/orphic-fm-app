# Mutable Instruments Porting Memory

## Already Ported to Orpheus (from Plaits)
- **Drum engines**: AnalogBassDrum, AnalogSnare, MetallicHiHat, FmDrum
- **Pitched engines**: FM, Noise, Waveshaping, VirtualAnalog, Additive, Grain, String, Modal
- **DSP infrastructure**: PlaitsDsp (PolyBLEP, interpolation, Random, ParameterInterpolator)
- **Lookup tables**: PlaitsTables (sine, FM quantizer, waveshaper, fold, SVF shift, stiffness)
- **Building blocks**: SineOscillator, Downsampler4x, ClockedNoise, SlopeOscillator, VariableShapeOscillator, SawOscillator, HarmonicOscillator, Grainlet, ZOscillator, PlaitsDelayLine, PlaitsString, PlaitsResonator

## Other MI Modules Ported to Orpheus
- **Marbles** → "Flux" (random CV/gate sequencer)
- **Clouds** → "Grains" (granular processor)
- **Rings** → "Resonator" (physical modeling resonator)
- **Warps** → "Blend" (cross-modulation)

## Plaits Engines NOT Yet Ported (5 remaining)
1. **Chord** - Wavetable + divide-down organ/string machine (5 voices, chord quantizer)
2. **Particle** - Filtered random pulses (6 particles + diffuser)
3. **Speech** - Three speech synths (Naive, SAM, LPC with word bank)
4. **Swarm** - 8-voice swarm of saws/sines with grain envelopes
5. **Wavetable** - 8x8x3 wave terrain navigation

## Eurorack Module Surveys (see mi_module_surveys.md for full detail)

### Kinks — NO SOURCE CODE
Hardware-only analog module (S&H, noise, rectifier, OR/AND logic). Only PCB/panel files in repo. Nothing to port.

### Tides v1 (`tides/`)
Fixed-point int16/int32 DSP, block size 16, hardware-coupled double buffer. Less useful than v2.

### Tides v2 (`tides2/`)
Float-based PolySlope generator. 4 simultaneous output channels. Modes: AD/AR/Looping × Outputs: Gates/Amplitude/SlopePhase/Frequency × Range: Control/Audio. Shape from 9-shape wavetable. Fold/waveshaping output stage. Key files: `poly_slope_generator.h`, `ramp_generator.h`, `ramp_shaper.h`, `ramp/ramp_extractor.cc`. Resources: `resources.cc` (3970 lines). MODERATE complexity.

### Elements (`elements/dsp/`)
Complete physical modeling voice: Exciter (7 models: Granular, Sample, Mallet, Plectrum, Particles, Flow, Noise) → Tube nonlinearity → Resonator (up to 64 SVF modal filters + 8 bowed delay lines) + Reverb. Patch has 19 parameters. resources.cc is 44621 lines. VERY COMPLEX to port.

### Streams (`streams/`)
Dynamics processor. Key DSP mode: LorenzGenerator — Lorenz chaotic attractor in fixed-point int32 (82 lines). Outputs gain+frequency CV driven by chaos. Two channels share state. SIMPLE to port.

### Stages (`stages/`)
`segment_generator.cc` (900 lines), 16 processing modes. Hardcoded `kSampleRate = 31250.0f`. Depends on `tides2/ramp/ramp_extractor.cc`. Designed for chained hardware modules. COMPLEX.

### Frames (`frames/`)
`poly_lfo.cc` — 4-channel LFO with coupling (phase modulated by neighbor's output). Shape morphs across 17 wavetable shapes. Spread = phase offset OR frequency detuning. ~120 DSP lines. SIMPLE to port.

## Ranked Recommendations for "Evolving Sounds"
1. **Frames PolyLFO** — SIMPLE, highest impact: 4-voice mutually-coupled wavetable LFO
2. **Tides v2** — MODERATE: versatile LFO/envelope/oscillator hybrid, 4 outputs
3. **Streams Lorenz** — SIMPLE: chaos modulator (82 lines), unique sonic territory
4. **Elements** — VERY COMPLEX: unique but resources.cc alone is 44K lines
5. **Stages** — COMPLEX: hardware-specific design, less standalone utility
6. **Kinks** — NOT PORTABLE (analog-only hardware)

## Key MI Modules in Eurorack Folder
- **Plaits** - Macro oscillator (24 engines total)
- **Rings** - Modal/string/FM resonator
- **Clouds** - Granular processor
- **Elements** - Modal synthesis voice (exciter + resonator)
- **Warps** - Modulator
- **Braids** - Legacy macro oscillator (48 modes)
- **Tides/Tides2** - Poly slope generator
- **Stages** - Multi-stage envelope generator
- **Marbles** - Random generator
- **Frames** - Keyframer + PolyLFO
- **Streams** - Dynamics + Lorenz chaos

## Porting Complexity Levels
- **Simple** (<200 LOC): Particle, Swarm, most Plaits engines, Frames PolyLFO, Streams Lorenz
- **Moderate** (200-500 LOC): Chord, Wavetable, Rings resonator, Tides v2
- **Complex** (500+ LOC): Speech, Elements voice, Clouds granular, Stages

## Gain Staging Reference
- FM/Noise engines: 0.3f
- Waveshaping: 0.25f
- Modal (percussive): 0.5f, alreadyEnveloped=true
- Drum engines: alreadyEnveloped=true, added to isDrumEngine() check

## Integration Patterns
- PlaitsEngineId enum → factory → JsynPlaitsUnit wrapper
- Engine parameters: note, timbre, morph, harmonics, accent, trigger
- Audio-rate modulation: timbreInput/morphInput with depth controls
- Dual audio paths: MUST test both direct and effect/bus routing
