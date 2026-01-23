# Matrix Control Quick Reference

## MIDI Control IDs

Use these IDs when MIDI learning or in MIDI configurations:

- `warps_algorithm` - Algorithm selector (0.0-1.0)
- `warps_timbre` - Timbre/modulation depth (0.0-1.0)
- `warps_carrier_level` - Carrier input level (0.0-1.0)
- `warps_modulator_level` - Modulator input level (0.0-1.0)
- `warps_carrier_source` - Carrier audio source (0=Synth, 0.5=Drums, 1=REPL)
- `warps_modulator_source` - Modulator audio source (0=Synth, 0.5=Drums, 1=REPL)
- `warps_mix` - Dry/wet blend (0.0-1.0)

*Note: Internal IDs still use "warps" prefix for backward compatibility.*

## AI Control Commands

The AI recognizes the Matrix by both MATRIX_* and WARPS_* names. Examples:

### Algorithm Selection
```
"Set matrix to ring modulator"
"Use the vocoder algorithm in the matrix"
"Switch matrix to frequency shifter mode"
"Try the XOR digital destroyer"
```

### Parameter Control
```
"Increase matrix timbre to 80%"
"Set matrix mix to 50%"
"Turn down the carrier level"
"Max out the modulator level"
```

### Source Routing
```
"Use drums as the carrier in the matrix"
"Modulate the synth with drums through the matrix"
"Route both matrix sources to synth"
```

### Combined Commands
```
"Make the matrix sound metallic and harsh"
  → AI might set ring mod + high timbre
  
"Add digital glitches to the drums using the matrix"
  → AI might set XOR algorithm, drums as carrier
  
"Cross-modulate synth and drums with vocoding"
  → AI sets vocoder, appropriate sources and mix
```

## Algorithm Quick Reference

| Value Range | Algorithm | Character |
|-------------|-----------|-----------|
| 0.000-0.124 | Crossfade | Smooth blending |
| 0.125-0.249 | Cross-folding | Rich harmonics |
| 0.250-0.374 | Ring Modulator | Metallic, harsh |
| 0.375-0.499 | XOR | Digital chaos |
| 0.500-0.624 | Comparator | Rhythmic gates |
| 0.625-0.749 | Vocoder | Spectral transfer |
| 0.750-0.874 | Chebyshev | Controlled distortion |
| 0.875-1.000 | Freq Shifter | Inharmonic tones |

## Preset Integration

Matrix settings are automatically saved with presets:
- Save preset → All Matrix parameters stored
- Load preset → Matrix fully restored
- Evolution → Can mutate Matrix parameters
- Default mix is **0.0** (dry/bypassed) to avoid unexpected sound on preset load

## SynthController Integration

Matrix now responds to all control origins:
- **UI**: Manual knob adjustments
- **MIDI**: External controllers
- **AI**: Natural language commands (using "Matrix" or "Warps" names)
- **SEQUENCER**: Automated parameter changes
- **EVO**: Evolution strategy mutations

## Parameter Details

### Algorithm (0.0 - 1.0)
Selects one of 8 meta-modulation algorithms. Each 0.125 range selects a different mode.

### Timbre (0.0 - 1.0)
Algorithm-specific modulation parameter:
- **Crossfade**: Curve shape
- **Cross-folding**: Folding depth
- **Ring Mod**: Modulation depth
- **XOR**: Bit depth/chaos amount
- **Comparator**: Threshold
- **Vocoder**: Band resonance
- **Chebyshev**: Waveshaping amount
- **Freq Shifter**: Shift frequency

### Carrier/Modulator Level (0.0 - 1.0)
Input gain for each signal. Adjust to balance or emphasize one source.

### Carrier/Modulator Source (enum)
- **0 (SYNTH)**: Use synthesizer voices
- **1 (DRUMS)**: Use 808 drum units
- **2 (REPL)**: Use REPL (looper/sample playback)

### Mix (0.0 - 1.0)
Dry/wet blend:
- 0.0: Completely dry (bypass, hear original carrier) - **DEFAULT**
- 0.5: Equal mix of dry and processed
- 1.0: Fully wet (only processed signal)

## Example Configurations

### Classic Ring Modulation
```
Algorithm: 0.3 (ring mod)
Timbre: 0.7
Carrier Source: SYNTH
Modulator Source: DRUMS
Carrier Level: 0.8
Modulator Level: 0.6
Mix: 0.7
```

### Vocoder Effect
```
Algorithm: 0.7 (vocoder)
Timbre: 0.5
Carrier Source: SYNTH (what gets filtered)
Modulator Source: DRUMS (filter control)
Carrier Level: 0.9
Modulator Level: 0.8
Mix: 0.8
```

### Frequency Shifter
```
Algorithm: 0.95 (freq shifter)
Timbre: 0.3 (shift amount)
Carrier Source: SYNTH
Modulator Source: SYNTH
Carrier Level: 1.0
Modulator Level: 0.0
Mix: 0.6
```

### Digital Destruction
```
Algorithm: 0.4 (XOR)
Timbre: 0.8
Carrier Source: DRUMS
Modulator Source: SYNTH
Carrier Level: 0.9
Modulator Level: 0.9
Mix: 0.9
```

## UI Panel

The Matrix panel displays as **"MATRIX"** in both collapsed and expanded states, providing clear identification of this signal routing and cross-modulation module.
