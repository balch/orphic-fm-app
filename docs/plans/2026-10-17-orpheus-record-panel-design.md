# Orpheus Record panel and recording-to-vibe skill

Date: 2026-10-17. Base: `main` (`98472d477`). Platform: JVM desktop first.

## Why

The user tunes voices by hand in the Orpheus desktop synth, then wants the same sound on a
Pulsar vibe track. Copying knob values by hand does not reproduce it: the panel voice path and
the Pulsar track path differ (LPG bypass vs track envelopes, pair mod sources with no Pulsar
equivalent, macros that drag unpinned values, sub-semitone detune between paired voices, the
main delay coloring the Orpheus mix). The Sirens on Odysseus Lore were the case that hurt.

Today the only record is a preset JSON with every port, plus a screen recording that macOS
saves without audio. Claude has to guess what was playing and how it sounded.

The fix has two halves:

1. **A Record panel** in Orpheus. One button records what you hear, saves the patch as a preset,
   and writes telemetry, all as one "take" on disk.
2. **A Claude skill** that reads a take and turns the sound into a vibe track.

## Goals

- Pressing record and then stop leaves a take on disk with three things: the full stereo output as a WAV,
  the current state as a preset, and telemetry that says what was playing.
- The panel lists the takes, deletes them, and plays the selected one back.
- The skill explains a take in plain words, writes an `OrpheusEngine` / `TrackVoice` for a
  chosen vibe track, and says what Pulsar cannot reproduce and the nearest workaround.

## Non-goals (first version)

- Rendering the Pulsar version and comparing it against the recording. The take keeps the WAV
  so this can be added later; the C++ harness (`test_harness.h` `write_wav` / `compare_wavs`,
  `test_preset_voices.cpp`) is the likely tool.
- Recording single voices, or recording dry before effects. The user chose the full output.
- Android, iOS and WASM. The panel may exist there, but recording is desktop only for now.
- Renaming takes, tagging, or exporting them anywhere but the local folder.
- Recording the Pulsar beat machine on purpose. If Pulsar is playing, it is in the mix; the
  skill's job is panel voices.

## Anti-goals

- Any glitch, dropout or extra CPU on the audio thread while recording or not recording. The
  audio callback must stay lock-free and allocation-free.
- Playing a take back through the synth engine, so that playback ends up in the next recording or
  colored by the live delay.
- A take that can't be traced back to its preset (the WAV and the settings must always travel
  together).

## What the user decided

- **Panel layout A** (mockup: `.superpowers/brainstorm/45219-1792282164/content/record-panel-layout.html`,
  gitignored):
  - Left column: a big round record button. While recording it shows a running timer
    (`00:12.4`) and a level meter.
  - Right column: the list of takes. Each row has a play icon, a name, the length and date, and
    a delete icon.
  - Under the list: one listen bar (play/pause, waveform or progress, position / length) for the
    selected take.
  - The panel follows the Orpheus look: a vertical title strip (`REC`, red accent) like the other
    header panels.
- **What gets recorded:** the full stereo output as heard, effects included.
- **Preset:** pressing record saves the current state as a preset.
- **Telemetry:** enough for AI to tune a vibe from it, including a note timeline (the user said
  "let's do it" to the small hook that publishes note events).
- **List:** the takes, with delete. **Listen bar:** wanted ("optional" in the user's words, so it is the
  last thing built and the first thing cut if it fights the schedule).
- **Skill:** "describes how to take a recording and extract the sound into a vibe."
- **Base branch:** `main`.

## How it works

### The take on disk

One folder per take, under `~/.config/orpheus/recordings/<take-id>/` (next to the existing
`presets/` and `midi-mappings/`):

```
take-2026-10-17-132431/
  audio.wav        stereo, engine sample rate, 32-bit float
  preset.json      the SynthPreset, same format as ~/.config/orpheus/presets/*.json
  telemetry.json   see below
```

Take id = `take-YYYY-MM-DD-HHMMSS` (local time). This is also its display name in the list.
*(My call: no naming UI in v1; the skill finds "the newest take" by default.)*

The preset is also saved to the normal presets folder as `rec-<take-id>`, so it can be
reloaded from the Presets panel. Deleting a take removes its folder only; the saved preset
stays. *(My call: deleting a preset the user may have started using elsewhere is worse than a
stray entry in the preset list.)*

### Capturing audio

There is no live audio recorder today. Audio is rendered in C++: miniaudio's callback
(`liborpheus_dsp/desktop/DesktopEngine.cpp:7`) calls `DesktopEngine::process`, which calls
`orpheus_engine_process` and fills an interleaved stereo float buffer (512-frame periods).

- Right after `orpheus_engine_process` in `DesktopEngine::process`, when a record flag is set,
  copy the block into a lock-free single-producer single-consumer ring. The ring is
  preallocated when recording starts, never on the audio thread.
- A JNI call (following the `nativeGetViz` / `getScope` pattern in `jni_bridge_desktop.cpp`)
  lets the JVM drain the ring. A JVM coroutine on an IO dispatcher drains it every few tens
  of milliseconds and streams it to `audio.wav`. The WAV header is patched with the final size
  when recording stops.
- **Max take length:** 5 minutes. Recording stops itself at the cap.
  *(My call: it bounds disk use (about 115 MB at 48 kHz float stereo). The synth only needs
  seconds to minutes.)*
- If the ring overflows (the JVM fell behind), count the dropped frames and record the count in
  telemetry rather than blocking the audio thread.
- `write_wav` in `liborpheus_dsp/test/test_harness.h:38` shows the header layout; the
  app-side writer lives in Kotlin (JVM), not in the test harness.

### Telemetry

`telemetry.json` holds what the preset alone cannot say: what was actually played, how it
moved, and the values already translated into Pulsar's terms.

```jsonc
{
  "version": 1,
  "takeId": "take-2026-10-17-132431",
  "app": "orpheus-desktop",
  "sampleRate": 48000,
  "durationSec": 21.4,
  "droppedFrames": 0,
  "start": { /* snapshot */ },
  "end":   { /* snapshot, same shape */ },
  "events": [
    { "t": 0.84, "type": "gateOn",  "voice": 1, "midiNote": 77.1, "source": "keyboard" },
    { "t": 1.92, "type": "gateOff", "voice": 1 },
    { "t": 3.10, "type": "control", "key": "org.balch.orpheus.plugins.voice:tune_1", "value": 0.70 },
    { "t": 4.00, "type": "midiNoteOn", "note": 64, "velocity": 100, "voice": 1 }
  ]
}
```

**Snapshot**, for each of the voice pairs the panel shows:

- engine id and its short name (the label shown in `VoiceEnginePickerPopup.kt`, e.g. `VOW` = 107)
- harmonics, timbre and morph (`duo_harmonics_N`, `duo_sharpness_N`, `duo_morph_N`)
- mod source as a name (`OFF/LFO/FM/FLUX/TIDES`; the stored ints differ from the display order,
  see the glyph notes below) and its level
- for each voice in the pair: `tune`, the computed sounding pitch in MIDI (fractional), `env_speed`, and hold state

A snapshot also carries:
- quad pitch per quad, its pitch source (`quad_pitch_source_N`), and its trigger source (TriggerRouter)
- the master/effects values the skill needs to name: main delay time and feedback, reverb
  amount, distortion drive
- bpm
- whether a Tidal/REPL pattern is playing (best effort; flags gates the timeline will miss)

**Sounding pitch** = `33 + tune*48 + voiceOffset` (voices 0-1 −12, 6-7 +12;
`orpheus_engine_routing.cpp:130-139`) plus `24*(qp-0.5)` for quad pitch (applied in
`DspSynthEngine.tuneToMidiNote`, around `:779`). This is the static pitch: a quad pitch
source (Marbles CV), global vibrato, bend and coupling move it further in C++. Keep the fractional part:
the user's paired voices sit about 30 cents apart and that detune is part of the sound.

**Events:**
- `gateOn` / `gateOff` come from `SynthController.onPulseStart` / `onPulseEnd`. These cover
  keyboard, MIDI, AI tools and MediaPipe. They miss gates the C++ engine fires itself:
  Marbles-clocked quads (`orpheus_unit_plaits.cpp` `process_ext_gate`), Tidal/REPL automation,
  and the string bender (`DspSynthEngine.setStringBend`). Each `gateOn` carries the voice's
  static pitch at that moment. **The user chose Kotlin-side events only**: no C++ event
  queue. The snapshot's per-quad trigger and pitch sources tell the skill when a take was
  clocked or pattern-driven, so it can warn that the timeline is incomplete.
- `control` comes from `SynthController.onControlChange` for the voice, quad and effect
  controls listed above, so knob moves during the take (like sweeping the quad pitch) show up.
  Throttle each key to at most 20 events/s. *(My call: enough to follow a sweep, small enough to read.)*
- `midiNoteOn` / `midiNoteOff`: raw MIDI note and velocity. This is the new hook the user approved:
  `MidiInputHandler.onNoteOn/onNoteOff` (`core/midi/.../MidiInputHandler.kt:39-66`) currently
  maps a note to a voice gate and drops the note number. Publish it as a flow, for example on
  `SynthController` next to `onPulseStart`, and record it.
  Note that in Orpheus a MIDI note *triggers a voice*; the voice's pitch comes from its tune
  knob. So `midiNote` on `gateOn` is the computed pitch, and the raw MIDI note is kept
  separately for completeness.
- `source` on `gateOn` is best effort. If the gate flow can't say where a trigger came from
  without reworking every caller, omit it.

### The Record panel

A new feature module `features/record` following the panel-viewmodel-feature skill
(`.claude/skills/panel-viewmodel-feature/`). `features/midi` is the closest template: a
ViewModel with non-DSP side effects backed by a file repository. Add `PanelId.RECORD` in
`core/features/.../FeaturePanel.kt`.

- **Record button:** toggles recording. On start:
  1. Save the preset (`PresetLoader.currentStateAsPreset` + `PresetsRepository.save`, the same
     path as `PresetsViewModel.saveNewPreset`).
  2. Take the start snapshot.
  3. Begin collecting events and audio.
  On stop it takes the end snapshot and writes `telemetry.json`. Then the new take appears at
  the top of the list, selected.
- **Timer and level meter** while recording. The meter can read the existing master level flow
  (`SynthEngine.masterLevelFlow` or `peakFlow`).
- **List:** newest first, read from the recordings folder on open and after each change.
- **Delete:** removes the take folder. Ask for a confirm before deleting.
  *(My call: one tap that destroys a recording is easy to hit by accident; a small inline "delete? yes/no" is enough.)*
- **Listen bar:** plays the selected `audio.wav` with the JVM's own audio output
  (`javax.sound.sampled`), not through the synth engine. Play/pause, a progress line you can
  click to seek, and position / length. Starting a recording stops playback.
- On platforms without recording, the panel isn't registered.

### The skill

A new skill at `.claude/skills/orpheus-recording-to-vibe/SKILL.md`. The
`grab-ai-vibes` skill is the model for finding app output on disk. The steps:

1. **Find the take:** the newest folder in `~/.config/orpheus/recordings/`, or the one the user
   names.
2. **Explain it:** read `telemetry.json` (the preset only for anything telemetry lacks) and say
   in plain words what was playing. For example: "pair 1-2 on Braids VOW, vowel near e, alto voice,
   two voices about 30 cents apart around F5, LFO on the pair, quad pitch swept up at 12 s."
   Optionally pull pitch and brightness from `audio.wav` with ffmpeg to back that up.
   If any quad had a non-manual trigger source (Marbles clock), a pitch source, or Tidal
   patterns were running, say that the note timeline and pitches are incomplete for those
   voices and lean on the WAV instead.
3. **Write the track:** ask which vibe and track. Then fill in `OrpheusEngine`: engine id; harmonics,
   timbre and morph with pins; a note range that covers the pitches played; and the envelope and LPG closest
   to the F..S setting. Use the engine-specific meaning of each parameter, e.g. on Braids
   (100-108) harmonics → Braids TIMBRE, morph → COLOR, timbre unused.
4. **Name the gaps:** list what Pulsar can't reproduce and the nearest workaround:
   - pair mod source FM, FLUX or TIDES (no equivalent; `modLfoDepth` is the nearest for LFO, and pinning timbre blocks it)
   - sub-semitone detune between paired voices
   - main delay coloring the Orpheus mix (the DJ app graph has no main delay)
   - OSC engine (id −1) has no Pulsar engine (use VA)
   - macros and tension evolution dragging unpinned values
   - opt-in Pulsar fields that may help (vibrato, pitch rise, harmonics rise) if they are on the
     branch being edited
5. **Hand off:** the user listens. The vibe-creator skill covers the rest of vibe editing.

The knowledge in the memory note `reference_orpheus_panel_to_pulsar_engine_mapping.md` (glyph
map, stored int order of mod sources, OSC → VA stand-in, delay times in seconds,
`delayDamping` is a no-op) moves into this skill so it lives in the repo.

## Constraints

- Follow CLAUDE.md, the panel-viewmodel-feature skill (MVI, Metro DI, registration), and
  dsp-implementation for the C++ and JNI piece.
- Audio-thread rules from the anti-goals: no locks, no allocation, no JNI calls from the
  callback.
- Comments 1-3 lines; no em-dashes in prose.
- Desktop JNI only; don't touch the Android (Oboe) or iOS render paths.

## Testing

- Kotlin unit tests:
  - the pitch formula, including the voice offsets and quad pitch
  - the snapshot mapping (stored mod-source int → name, engine id → short name)
  - telemetry JSON round trip
  - the WAV writer (header fields and the size patch on stop)
- C++ test for the ring: wraparound, overflow counting, drain order.
- Manual run on desktop:
  1. Record a take with a pair singing and the quad pitch swept.
  2. Confirm `audio.wav` plays in the listen bar and in Finder.
  3. Confirm the preset reloads the same sound.
  4. Confirm the telemetry shows the sweep.
  5. Run the skill on it.

## Left to the builder

- Ring size, drain cadence, and the JNI shape.
- Where the record flag lives (DesktopEngine vs a bridge object) and how start/stop crosses JNI.
- Exact module boundaries: whether take storage is its own repository class or part of the
  feature, and whether telemetry types live in the feature or in `core`.
- The listen bar's drawing: waveform peaks or a plain progress line.
- Whether the panel is registered only in the desktop app or hidden at runtime elsewhere.
