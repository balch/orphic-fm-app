# Matrix (Warps) - Getting the Sounds from the Video

## Understanding Your Matrix vs. Hardware Warps

Your **Matrix** implementation is based on the Mutable Instruments Warps with the **Parasites firmware**. This gives you access to 8 algorithms (the original has 7, Parasites adds the frequency shifter and makes it easier to access).

## Parameter Mapping: Hardware → Your Implementation

### Main Controls

| Hardware Warps | Your Matrix | Function |
|----------------|-------------|----------|
| CARRIER input | Carrier Source dropdown | Choose audio source (Synth/Drums/REPL) |
| MODULATOR input | Modulator Source dropdown | Choose modulation source |
| ALGORITHM knob | Algorithm knob (segmented) | Select 1 of 8 algorithms |
| TIMBRE knob | Timbre knob | Algorithm-specific control |
| LEVEL 1 knob | Carrier Level ("DRIVE") | Input gain for carrier |
| LEVEL 2 knob | Modulator Level ("DRIVE") | Input gain for modulator |
| (none - always active) | Mix knob | Dry/wet blend (0=bypass, 1=full) |

### Key Difference: Mix Control

⚠️ **Important**: The hardware Warps is always "on" - there's no dry/wet mix. Your implementation adds a **MIX** knob:
- **0.0** = Completely dry (bypass) - **DEFAULT**
- **0.5** = Equal blend of dry and processed
- **1.0** = Fully wet (like hardware Warps)

**To match the video sounds, set MIX to 1.0 (fully wet).**

## The 8 Algorithms Explained

### Algorithm Range: 0.000 - 0.124
**1. CROSSFADE**
- **What it does**: Smoothly fades between carrier and modulator
- **Timbre**: Controls crossfade curve (constant power)
- **Best for**: Smooth transitions, morphing between sounds
- **Video example**: Usually shown first as the "gentle" mode
- **Settings to try**:
  - Carrier: SYNTH, Modulator: DRUMS
  - Timbre: 0.5 (equal mix)
  - Mix: 1.0

### Algorithm Range: 0.125 - 0.249
**2. CROSS-FOLDING**
- **What it does**: Sums signals then wave folds them
- **Timbre**: Controls folding amount (0=subtle, 1=extreme)
- **Best for**: Rich harmonics, aggressive tones
- **Video example**: Creates buzzy, distorted sounds with lots of overtones
- **Settings to try**:
  - Carrier: SYNTH, Modulator: SYNTH
  - Carrier Level: 0.8, Modulator Level: 0.6
  - Timbre: 0.7 (heavy folding)
  - Mix: 1.0

### Algorithm Range: 0.250 - 0.374
**3. DIODE RING MODULATOR**
- **What it does**: Classic ring mod with diode distortion simulation
- **Timbre**: Level control + diode distortion amount
- **Best for**: Metallic, bell-like tones, vintage synth sounds
- **Video example**: The "classic" Warps sound - metallic and harsh
- **Settings to try**:
  - Carrier: SYNTH (440Hz tone), Modulator: DRUMS (kick)
  - Timbre: 0.6 (moderate distortion)
  - Mix: 1.0

### Algorithm Range: 0.375 - 0.499
**4. XOR (Digital Destroyer)**
- **What it does**: Bitwise XOR operation on 16-bit audio
- **Timbre**: Which bits are XOR'ed (low=subtle, high=chaos)
- **Best for**: Digital glitches, lo-fi destruction, bit crushing
- **Video example**: Creates weird digital artifacts and crunch
- **Settings to try**:
  - Carrier: DRUMS, Modulator: SYNTH
  - Timbre: 0.8 (high bits = chaos)
  - Mix: 0.7 (blend some dry for clarity)

### Algorithm Range: 0.500 - 0.624
**5. COMPARATOR (Comparison & Rectification)**
- **What it does**: Compares signals, replaces negative portions
- **Timbre**: Morphs through comparison/rectification methods
- **Best for**: Gate patterns from audio, rhythmic effects
- **Video example**: Creates rhythmic pulses and gates
- **Settings to try**:
  - Carrier: SYNTH (sustained), Modulator: DRUMS (rhythmic)
  - Timbre: 0.5
  - Mix: 1.0

### Algorithm Range: 0.625 - 0.749
**6. VOCODER (20-band)**
- **What it does**: Spectral transfer - modulator's spectrum filters carrier
- **Timbre**: Envelope follower release time (0=fast, 1=frozen spectrum)
- **Best for**: "Talking" instruments, spectral effects
- **Video example**: Classic vocoder - drums "talk" with synth timbre
- **Settings to try**:
  - Carrier: SYNTH (what gets filtered)
  - Modulator: DRUMS (provides filter movement)
  - Carrier Level: 0.9, Modulator Level: 0.8
  - Timbre: 0.3 (medium release)
  - Mix: 1.0
- **Note**: For best results, carrier should be spectrally rich

### Algorithm Range: 0.750 - 0.874
**7. CHEBYSHEV WAVESHAPING**
- **What it does**: Polynomial waveshaping
- **Timbre**: Waveshaping amount
- **Best for**: Controlled distortion, harmonic enhancement
- **Video example**: Adds warmth and harmonics
- **Settings to try**:
  - Carrier: SYNTH, Modulator: SYNTH
  - Timbre: 0.6
  - Mix: 0.8

### Algorithm Range: 0.875 - 1.000
**8. FREQUENCY SHIFTER** (Parasites bonus!)
- **What it does**: Shifts all frequencies by a fixed amount (inharmonic)
- **Timbre**: Shift frequency amount
- **Best for**: Alien tones, detuned effects, special FX
- **Video example**: Creates bell-like inharmonic tones
- **Settings to try**:
  - Carrier: SYNTH, Modulator: SYNTH
  - Carrier Level: 1.0, Modulator Level: 0.0
  - Timbre: 0.2 (small shift)
  - Mix: 1.0
- **Easter egg in hardware**: Requires secret button sequence!

## Recreating Classic Warps Patches

### "Metallic Bells" (Ring Mod)
```
Algorithm: 0.3 (Ring Modulator)
Carrier Source: SYNTH
Modulator Source: SYNTH
Carrier Level: 0.8
Modulator Level: 0.7
Timbre: 0.5
Mix: 1.0
```
Use different synth voice pitches for carrier vs modulator.

### "Talking Drums" (Vocoder)
```
Algorithm: 0.7 (Vocoder)
Carrier Source: SYNTH (sustained chord)
Modulator Source: DRUMS (rhythmic)
Carrier Level: 0.9
Modulator Level: 0.8
Timbre: 0.4
Mix: 1.0
```
The drums will "speak" with the synth's harmonic content.

### "Digital Chaos" (XOR)
```
Algorithm: 0.4 (XOR)
Carrier Source: DRUMS
Modulator Source: SYNTH
Carrier Level: 0.9
Modulator Level: 0.9
Timbre: 0.85 (high bits)
Mix: 0.8
```

### "Harmonic Wash" (Cross-folding)
```
Algorithm: 0.2 (Cross-folding)
Carrier Source: SYNTH
Modulator Source: SYNTH
Carrier Level: 0.7
Modulator Level: 0.6
Timbre: 0.65
Mix: 1.0
```

### "Rhythmic Gates" (Comparator)
```
Algorithm: 0.55 (Comparator)
Carrier Source: SYNTH (drone)
Modulator Source: DRUMS (rhythm)
Carrier Level: 0.8
Modulator Level: 0.9
Timbre: 0.5
Mix: 1.0
```

## Tips from the Video

### 1. Level Knobs are Crucial
- **Hardware**: Levels can go above unity for overdrive
- **Your implementation**: 0.0-1.0 range, but you can be aggressive
- **Tip**: Try extreme levels (0.9-1.0) for more intense effects

### 2. Timbre is Algorithm-Specific
- Don't expect it to do the same thing on each algorithm
- Experiment! It's not just "brightness"
- Sweet spots are often around 0.3-0.7

### 3. Source Combinations Matter
- **Synth + Synth**: Harmonic relationships create musical results
- **Synth + Drums**: Rhythmic modulation of tonal content
- **Drums + Drums**: Percussive processing and layering

### 4. Mix Control Strategy
- **Start at 1.0** to hear the pure effect (like hardware)
- **Reduce to 0.5-0.7** for subtler integration
- **Automate** for dramatic builds and drops

### 5. Algorithm Scanning
- The video likely shows smooth algorithm sweeps
- Your segmented knob jumps between algorithms
- **AI command**: "Slowly scan matrix algorithm from 0 to 1" for automation

## Quick Start: First Sounds to Try

### **Easy Win #1: Ring Mod Magic**
1. Set Algorithm to **0.3** (Ring Mod)
2. Carrier: SYNTH, Modulator: DRUMS
3. Both levels at **0.8**
4. Timbre: **0.5**
5. Mix: **1.0**
6. Play some synth notes while drums are running
7. Result: Metallic, bell-like tones

### **Easy Win #2: Vocoder Effect**
1. Set Algorithm to **0.7** (Vocoder)
2. Carrier: SYNTH (play sustained notes/chords)
3. Modulator: DRUMS (running beat)
4. Carrier Level: **0.9**, Modulator Level: **0.8**
5. Timbre: **0.4**
6. Mix: **1.0**
7. Result: Drums "speak" with synth timbre

### **Easy Win #3: Harmonic Destruction**
1. Set Algorithm to **0.2** (Cross-folding)
2. Both sources: SYNTH
3. Both levels: **0.7**
4. Slowly increase Timbre from **0.0 → 1.0**
5. Mix: **1.0**
6. Result: Smooth → aggressive wavefolder distortion

## Troubleshooting

### "I don't hear anything!"
- Check Mix knob - should be > 0.0
- Verify carrier/modulator sources are producing audio
- Check both level knobs are > 0.0

### "It sounds nothing like the video"
- Make sure Mix is at 1.0 (hardware has no dry signal)
- Verify you're on the correct algorithm
- Check that your input sources match video (synth vs drums)
- Levels might need to be higher (0.7-1.0 range)

### "Vocoder doesn't work"
- Carrier needs harmonic content (sustained synth chord works best)
- Modulator needs rhythmic/dynamic content (drums perfect)
- Try Timbre around 0.3-0.5 range
- Make sure both levels are high (0.8+)

### "Ring mod sounds wrong"
- Use musical intervals between carrier and modulator frequencies
- Try Timbre around 0.5-0.7 for classic sound
- Both sources should have tonal content

## AI Control Examples

Ask your AI to set these up for you:

```
"Set up the matrix for ring modulation - use synth as carrier 
and drums as modulator, both at 80%, timbre at 50%, full wet"

"Configure the matrix vocoder with synth carrier and drum 
modulator, high levels, and moderate timbre"

"Scan the matrix algorithm slowly from crossfade to frequency 
shifter over 30 seconds"

"Set matrix to XOR digital destroyer, high timbre for chaos, 
mix at 70%"
```

## Remember

Your Matrix is **more powerful** than the hardware because:
- ✅ You can blend dry/wet (hardware is always 100% wet)
- ✅ You can route any source (hardware has fixed inputs)
- ✅ You get Parasites frequency shifter without secret codes
- ✅ You can save presets
- ✅ You can MIDI learn and automate everything

**Have fun exploring!** 🎛️✨
