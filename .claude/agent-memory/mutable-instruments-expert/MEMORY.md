# Mutable Instruments Porting Memory

## Already Ported to Orpheus (from Plaits)
- **Drum engines**: AnalogBassDrum, AnalogSnare, MetallicHiHat, FmDrum
- **Pitched engines**: FM, Noise, Waveshaping, VirtualAnalog, Additive, Grain, String, Modal
- **DSP infrastructure**: PlaitsDsp (PolyBLEP, interpolation, Random, ParameterInterpolator)
- **Lookup tables**: PlaitsTables (sine, FM quantizer, waveshaper, fold, SVF shift, stiffness)
- **Building blocks**: SineOscillator, Downsampler4x, ClockedNoise, SlopeOscillator, VariableShapeOscillator, SawOscillator, HarmonicOscillator, Grainlet, ZOscillator, PlaitsDelayLine, PlaitsString, PlaitsResonator

## Plaits Engines NOT Yet Ported (5 remaining)
1. **Chord** - Wavetable + divide-down organ/string machine (5 voices, chord quantizer)
2. **Particle** - Filtered random pulses (6 particles + diffuser)
3. **Speech** - Three speech synths (Naive, SAM, LPC with word bank)
4. **Swarm** - 8-voice swarm of saws/sines with grain envelopes
5. **Wavetable** - 8x8x3 wave terrain navigation

## Key MI Modules in Eurorack Folder
- **Plaits** - Macro oscillator (24 engines total, 12 already ported)
- **Rings** - Modal/string/FM resonator (6 modes, up to 4 voices)
- **Clouds** - Granular processor (4 playback modes: granular, stretch, looping delay, spectral)
- **Elements** - Modal synthesis voice (exciter + resonator, bow/blow/strike)
- **Warps** - Modulator (ring mod, freq shifter, vocoder, bitcrusher, wavefolder, XOR)
- **Braids** - Legacy macro oscillator (48 modes)
- **Tides2** - Poly slope generator (4-channel LFO/envelope/oscillator)
- **Stages** - Multi-stage envelope generator (up to 36 segments)
- **Marbles** - Random generator (Turing machine, quantized/unquantized)

## Porting Complexity Levels
- **Simple** (<200 LOC): Particle, Swarm, most Plaits engines
- **Moderate** (200-500 LOC): Chord, Wavetable, Rings resonator, Stages envelopes
- **Complex** (500+ LOC): Speech, Elements voice, Clouds granular, Rings full part

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
