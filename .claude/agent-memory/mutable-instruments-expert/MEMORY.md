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
- **Tides v2** → `orpheus_unit_tides.cpp` — FULLY INTEGRATED in C++. `tides::PolySlopeGenerator` is live in `OrpheusEngine` (field `tides_generator`). Supports all 3 ramp modes (AD/AR/Looping) × 4 output modes × 2 ranges. Normalization: `kTidesNorm = 0.125f` (divides by 8V max). For Pulsar envelope use: `RAMP_MODE_AD` + `OUTPUT_MODE_AMPLITUDE` + `RANGE_CONTROL`. Key parameter mapping: `slope` (0–1) = pw = attack/decay balance; `shape` (0–1) = waveshape morphing across 12 shapes; `smoothness` (0–1) = below 0.5 adds lowpass filter, above 0.5 adds wavefolder. Frequency in control range: `hz = 0.001 * pow(10000, knob)` → 0.001–10 Hz.

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

## Orpheus C++ Engine Architecture (2026-03-19)
- ALL audio is C++ only. No Kotlin DSP units active.
- 24 Plaits engines in `orpheus_voice.h` via `kOrpheusOutGain[24]`. Indices 0–7=engine2/ (v1.2), 8–23=engine/ (v1.1).
- Index map: 0=VirtualAnalogVCF, 1=PD, 2-4=SixOpFM, 5=WaveTerrain, 6=StringMachine, 7=Chiptune, 8=VirtualAnalog, 9=Waveshaping, 10=FM, 11=Grain, 12=Additive, 13=Wavetable, 14=Chord, 15=Speech, 16=Swarm, 17=Noise, 18=Particle, 19=String, 20=Modal, 21=BassDrum, 22=SnareDrum, 23=HiHat
- Other C++ units: Warps, Marbles, Grids, Rings, Clouds, Delay, Reverb, LFO, Lorenz, PolyLFO
- Warps algorithms (0–7): 0=xfade, 1=fold, 2=analog_ring_mod, 3=digital_ring_mod, 4=XOR, 5=comparator, 6=NOP

## Bass-Relevant Engines (already in C++)
- **Engine 0 (VCF)**: Saw/square + sub + dual SVF LP. Best acid bass. Harmonics=resonance, Timbre=cutoff, Morph=waveshape. outGain=0.55f
- **Engine 1 (PD)**: CZ-style phase distortion. Punchy harmonics. outGain=0.38f
- **Engine 10 (FM)**: 2-op FM. Plucky/gritty. outGain=0.45f
- **Engine 21 (BassDrum)**: 808+909 kick with overdrive. already_enveloped=true. outGain=1.0f

## Modules NOT Yet Ported (bass-relevant)
- **Peaks MultistageEnvelope** — AD/ADSR with 3 shapes (linear/exp/quartic). Simple, fast to port.
- **Streams Compressor** — log-domain compressor: attack/decay/threshold/ratio/soft knee (~150 LOC C++, fixed-point).
- **Tides2 PolySlopeGenerator** — float AD/AR/looping, 4 outputs, 9 shapes. Moderate complexity.

## Rings / Resonator Implementation Status (2026-03-23)
- Orpheus uses a CUSTOM C++ reimplementation (`orpheus_resonator.h/cpp`), NOT `rings::Part`
- Only 3 of 6 MI models implemented: Modal, Sympathetic, String
- Missing: FM Voice, Sympathetic Quantized, String+Reverb, polyphony, Plucker, NoteFilter, dispersion, ParameterInterpolator smoothing
- Full detail in [rings_analysis.md](rings_analysis.md)

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

## Bass Sequencer Clock Sync Pattern (2026-03-20)
- `seq.tick_counter` is a sample sub-counter (repurposed from tick-based to sample-based).
- Formula: `samples_per_step = round(sample_rate * 60 * ticks_per_step / (bpm * 24))`.
- At 24 PPQN: kTicksPerStep = {24, 12, 6, 3, 1} for clock_div 0-4 (quarter→64th notes).
- Clock advance loop: subtract `until_next = samples_per_step - tick_counter` each iteration, call `advance_step()` when the boundary fires, continue consuming remaining samples.
- This matches master clock timing without reading clock_phase (which belongs to unit_process_clock).
- On cycle wrap, apply mutation to ALL steps (not just the new step).
- Gate logic: pass render_gate=1 only when new_step_fired && step_gate, OR sustaining same gated step. Pass 0 when clock stopped or step has no gate.
