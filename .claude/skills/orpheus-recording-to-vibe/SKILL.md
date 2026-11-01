---
name: orpheus-recording-to-vibe
description: Turn a take recorded in the Orpheus desktop or Android Record panel into a Pulsar vibe track. Use when the user says "use my recording", "grab my last take", "turn this take into a vibe", "capture from Orpheus", or wants a hand-tuned Orpheus voice copied onto a vibe track. Reads ~/.config/orpheus/recordings/<take-id>/ (audio.wav, preset.json, telemetry.json), explains the sound in plain words, fills an OrpheusEngine, and names what Pulsar cannot reproduce. Hands off to vibe-creator for everything after the track is written.
---

# Orpheus Recording to Vibe

The user tunes voices by hand in the Orpheus desktop synth, then wants the same sound on a Pulsar
vibe track. Copying knob values does not reproduce it: the panel voice path and the Pulsar track
path differ. A take captures what was heard (`audio.wav`), the patch (`preset.json`) and what was
played (`telemetry.json`). This skill reads a take and writes the track.

## 1. Find the take

Takes live in `~/.config/orpheus/recordings/<take-id>/`, one folder each, id `take-YYYY-MM-DD-HHMMSS`
(local time). A restart within the same second gets a suffix, e.g. `take-2026-10-17-132431-2`
(`-3`, ...), so treat the id as a prefix match. Each folder holds `audio.wav`, `preset.json` and `telemetry.json`.

```bash
ls -1t ~/.config/orpheus/recordings/        # newest first
```

Use the newest folder unless the user names one. The take's own `preset.json` is the source of
truth (on desktop the preset is also saved as `rec-<take-id>` in `~/.config/orpheus/presets/`; on a
phone that copy stays on the device). An empty or missing folder means nothing has been recorded on this
machine yet. For finding app output on disk in general, see the `grab-ai-vibes` skill.

### On Android

The Android app keeps takes in its private `files/recordings/` folder, same layout. Pull the newest
one into the desktop folder, then carry on from the desktop path above. `run-as` needs a debug
(debuggable) build; a release build refuses it. Debug builds carry an `.debug` applicationId suffix,
so the package is `org.balch.orpheus.debug`. Use `ls -1`: on-device `ls` prints columns even
when piped (see `grab-ai-vibes`).

```bash
mkdir -p ~/.config/orpheus/recordings
PKG=org.balch.orpheus.debug
TAKE=$(adb exec-out run-as "$PKG" ls -1t files/recordings 2>&1 | head -1 | tr -d '\r')
case "$TAKE" in
  take-*) adb exec-out run-as "$PKG" tar -cf - -C files/recordings "$TAKE" | tar -xf - -C ~/.config/orpheus/recordings ;;
  *) echo "No take found for $PKG (got: ${TAKE:-nothing}). Is the debug build installed and a take recorded?" ;;
esac
```

## 2. Explain it

Read `telemetry.json` first. Open `preset.json` only for what telemetry lacks (it is about 150 KB
of every port, keys like `portValues."org.balch.orpheus.plugins.voice:duo_harmonics_0".value`; use jq).

Say in plain words what was playing. Example: "pair 1-2 on VOW, vowel near e, alto voice, two
voices about 30 cents apart around F5, LFO on the pair, quad pitch swept up at 12 s."

### Telemetry fields

Top level: `version` (1), `takeId`, `app` (`"orpheus-desktop"`), `sampleRate`, `durationSec`,
`droppedFrames` (audio frames lost because the drain fell behind; nonzero means gaps in `audio.wav`),
`start` and `end` (snapshots, same shape), `events`.

Snapshot (`start`/`end`): `bpm`, `pairs`, `quads`, `effects`.

- `pairs[]` (6, voice pairs 0-5): `pair`, `engineId`, `engineName` (the short label from the engine
  picker, e.g. `VOW`; it can be `"?"` for an id the picker does not know, e.g. 108, so trust
  `engineId` and map it with `OrpheusEngineId.fromId`), `harmonics`, `timbre`, `morph` (all per pair), `cables[]` (the duo patch:
  `source`, `dest`, `slot`, `amount`, `parked`; `PatchSource`/`PatchDest` enum names; takes recorded
  before duo patches have a `modSource` name instead and no `cables`), `modSourceLevel` (the
  master depth of those cables), and `voices[]` (2 per pair) with `voice`, `tune`, `midiNote`, `envSpeed`.
- `quads[]` (3, voices 4q..4q+3): `quad`, `pitch`, `pitchSource`, `triggerSource`, `hold`.
- `effects`: `delayTime1`, `delayTime2`, `delayFeedback`, `delayMix`, `reverbAmount`,
  `distortionDrive`, `vibratoDepth`.

Events (`t` is seconds since record start), discriminated by `type`:

| type | fields |
|---|---|
| `gateOn` | `t`, `voice`, `midiNote` (sounding pitch at that moment) |
| `gateOff` | `t`, `voice` |
| `control` | `t`, `key` (e.g. `org.balch.orpheus.plugins.voice:tune_1`), `value`; voice, delay, reverb, distortion and vibrato controls only, at most 20 per second per key |
| `midiNoteOn` | `t`, `note`, `velocity`, `voice` (nullable) |
| `midiNoteOff` | `t`, `note`, `voice` (nullable) |

A preset load mid-take emits no `control` events (the reload path updates values without
emitting). So if `start` and `end` differ with no matching `control` events, a preset was loaded
during the take; the audio is the truth. The reverse also needs care: if `end` equals `start`
exactly and there were `control` events, the end snapshot may be a fallback (taken when capturing
the live state failed), so trust the events over `end`.

In Orpheus a MIDI note triggers a voice; the pitch comes from the voice's tune knob. So read
pitches from `gateOn.midiNote`, and treat `midiNoteOn.note` as the raw key pressed.

### Pitch formula

`midiNote = 33 + tune*48 + voiceOffset + 24*(quadPitch - 0.5)`

`voiceOffset` is -12 for voices 0-1, +12 for voices 6-7, else 0. The result is fractional on
purpose: paired voices often sit about 30 cents apart, and that detune is part of the sound.
Use it to set `noteRangeLow` / `noteRangeHigh` around the register played. A `control` event on
`quad_pitch_N` shows a sweep; recompute pitch from its values.

### Incomplete-timeline warning

If any `quads[*].triggerSource != 0` or `quads[*].pitchSource != 0`, tell the user the note
timeline and pitches are incomplete for that quad's voices: the C++ engine fires those gates and
moves those pitches itself (clocked or CV-driven), and Kotlin-side events cannot see them. Tidal
(REPL) pattern state is never recorded, so a pattern-driven take has the same gap. Lean on
`audio.wav` for those voices. Gates from the string bender are also missed.

### Optional: check the WAV with ffmpeg

The WAV is stereo, 48000 Hz, 32-bit float.

```bash
ffmpeg -i audio.wav -af astats -f null -                       # peak, RMS, DC offset per channel
ffmpeg -i audio.wav -lavfi showspectrumpic=s=1200x600 spec.png # spectrogram, then Read spec.png
```

Write `spec.png` outside the take folder (scratchpad). Use levels to check headroom and the
spectrogram to confirm pitch and brightness claimed from telemetry.

## 3. Write the track

Ask which vibe and which track. Then fill in `OrpheusEngine` (`features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/models/OrpheusEngine.kt`):

```kotlin
OrpheusEngine(
    engineId = OrpheusEngineId.VA,          // enum entry for the telemetry engineId (see below)
    harmonics = ..., pinHarmonics = true,   // the pair's harmonics
    timbre = ...,    pinTimbre = true,      // the pair's timbre
    morph = ...,     pinMorph = true,       // the pair's morph
    noteRangeLow = ..., noteRangeHigh = ..., // around the played pitches
    // envelope / LPG: closest to the panel's F..S setting, by ear
)
```

`engineId` is the `OrpheusEngineId` enum, never an Int, so write `OrpheusEngineId.VA`, not `8`.
Telemetry has an Int `engineId` and a short label (`engineName`, e.g. `VOW`) that is not the
enum name (`VOWEL_FOF`). Map the Int with `OrpheusEngineId.fromId(id)` or by matching the entry's
`id` in `core/audio/src/commonMain/kotlin/org/balch/orpheus/core/audio/OrpheusEngineId.kt`. BD and
FM_DRUM both have id 21, and `fromId(21)` returns BD.

Pin all three so macros and evolution do not drag the values. Leave `pinTimbre` off only when you
use `modLfoDepth` for an LFO-modulated pair (pinned timbre blocks it). `modLfoDepth` works only on
tracks 5-7 or a DRONE-profile track; on tracks 0-4 it does nothing unless the track uses the DRONE
profile (track roles: `vibe-creator` skill). The cost is that macros and
tension evolution then move timbre, so say so. Read the per-engine meaning of each parameter
before copying (DX engines force-pin harmonics, a 32-step patch selector).

### Panel glyph map

Panel glyph to preset key to Pulsar field (`features/voice/.../VoiceColumn.kt`, `DuoVoiceBox.kt`):

- engine chip `≡` = `duo_harmonics_N` = harmonics (per pair, both voices)
- `#` (green) = `duo_sharpness_N` = timbre (per pair, even though it sits under the right voice)
- `ψ` (magenta) = `duo_morph_N` = morph (per pair)
- `♫` (cyan) per voice = `tune_N`, see the pitch formula
- `F..S` slider = `env_speed_N`: an ADSR speed. The panel voice path runs LPG bypass, so there is
  no LPG equivalent; pick `lpgMode` / `lpgDecay` by ear
- pair patch = `duo_patch_N` (a JSON string of cables) plus `duo_mod_source_level_N` (master depth).
  Older `preset.json` files hold `duo_mod_source_N` ints instead (FM=0, OFF=1, LFO=2, FLUX=3,
  TIDES=4). A new take's `telemetry.json` holds `cables` for both; a take recorded before duo patches
  has a `modSource` name there (`FM`, `OFF`, `LFO`, `FLUX`, `TIDES`) and no `cables`. The app upgrades
  those on load, but read from disk you must translate the name yourself.

### Engine notes

- Braids engines (100-108): `harmonics` drives Braids TIMBRE, `morph` drives Braids COLOR, and
  `timbre` is unused (`liborpheus_dsp/src/orpheus_unit_braids.cpp`). On the 3-voice chord engines
  (100-104) harmonics is the chord interval and morph is the detune.
- OSC (id -1, `OrpheusEngineId.OSC`) is the built-in tri+square oscillator and Pulsar renders it
  (`pulsar_osc.h`, `osc_core.h`, the `engine_index < 0` branch in `orpheus_unit_pulsar.cpp`). Per knob:
  - harmonics is self-feedback (`freq += prev_output * harmonics * 200 Hz`). Pulsar clamps it to
    0.35 (`kOscModRange.harmonics_max`), so panel values above 0.35 are capped.
  - timbre crossfades triangle (0) to square (1), the same on the panel and Pulsar.
  - morph is the pair detune on both: plus or minus morph*25 cents between the pair
    (`orpheus_unit_plaits.cpp` ~360-365). Pulsar renders it as the double voice (`doubleDetune` +
    morph*50 cents, capped at 50; `doubleSpread` pans the pair). Unpinned, morph follows the SPACE
    macro (see the `OrpheusEngine.kt` KDoc), so set `pinMorph = true` to copy the panel value.
  - notes below MIDI 40 are folded up by octaves (`kOscModRange.note_min`), so keep `noteRangeLow >= 40`.
  - panel FM (an `FM` destination cable on an OSC pair) adds `source * level * amount * 400 Hz`
    (FM full scale; a converted legacy cable has amount 0.5, so 200 Hz) to the carrier (`orpheus_unit_plaits.cpp` ~407-417). The nearest Pulsar settings: `fmFreeHz > 0`
    is a free-running modulator at that rate with `fmDepth * 200 Hz` deviation, so `fmDepth` = the
    pair's `modSourceLevel` times twice the FM cable amount; `fmRatio` (0..16, integers stay in tune) is the same idea locked to the
    note as phase modulation at index `fmDepth * 8`. `fmDepth` is used as written; only `morphRise`
    moves it. `fmShape` sets the modulator wave: 0 sine, 0.5
    triangle, 1 square. Pick `fmFreeHz` to copy a panel LFO or free-running source, `fmRatio` to
    copy partner-voice FM. The panel's actual source signal is not reproduced.
  - examples in the repo: `StayAsleepVibe.kt` (`engineId = OrpheusEngineId.OSC`).
- Chaos engines (200-204) render on Pulsar and use the same three knobs, pins honored.

### Effects

Delay times map knob 0..1 to `0.01 + v*1.99` seconds on both the main delay and the Pulsar delay,
independent of tempo. `VibeEffects.delayTimeA/B` and `delayFeedback` copy straight from
`effects.delayTime1`, `delayTime2`, `delayFeedback`. `VibeEffects.delayDamping` is routed but never
read: a no-op. The main delay's `DELAY A/B` knobs (`mod_depth_1/2`) add seconds of LFO-driven delay
time (`orpheus_unit_delay.cpp` 62-63) and have no Pulsar equivalent. The `mod_source_is_lfo` and
`lfo_wave_is_triangle` toggles are stored in `DelayPlugin.kt` only; no C++ path reads them.

## 4. Name the gaps

Tell the user what Pulsar cannot reproduce, with the nearest workaround:

- Pair cables from FM, FLUX or WAVES sources, and any PITCH, ENV or FM cable: Pulsar tracks never
  read the duo patch or FM depth. OSC is the one exception, approximated with `fmRatio` / `fmFreeHz`
  (engine notes above). LFO cables to HARMONICS, TIMBRE or MORPH are the one family that maps onto
  Pulsar's own macros: the nearest is `modLfoDepth`, which only works on tracks 5-7 or a DRONE-profile track (nothing on
  tracks 0-4 unless the track uses the DRONE profile; see `vibe-creator` for track roles) and is scaled by the energy curve except on DRONE
  tracks, but pinning
  timbre blocks it and tension evolution drags an unpinned timbre, so usually pin the static value
  and say so.
- Sub-semitone detune between paired voices: tracks carry one pitch. The nearest is a second track with
  the same engine at a nearby pitch, or accepting the single voice.
- Main delay coloring the mix: on the Orpheus graph Pulsar audio also passes through the main
  dual delay, so the panel colors what you hear. The DJ app graph has no main delay. Copy the
  delay times into `VibeEffects` and expect a different tail.
- OSC engine: panel FM sources are only approximated by `fmRatio` / `fmFreeHz` / `fmDepth`,
  harmonics caps at 0.35 and notes fold up below MIDI 40. The pair detune (morph) carries over.
- Macros and tension evolution dragging unpinned values: pin what matters.
- Opt-in Pulsar fields that may help (vibrato, pitch rise, harmonics rise), if the branch being
  edited has them. Check `OrpheusEngine.kt` and `VibeEffects.kt` before citing one.
- Timeline gaps from the warning rule in step 2.

## 5. Hand off

Show the proposed `OrpheusEngine(...)` block and the gap list, then let the user listen. Do not
edit a vibe until they pick the vibe and track. The `vibe-creator` skill covers everything after
the track is written: naming rules, file layout, catalog entries, tuning and the DogHouse benchmark.
