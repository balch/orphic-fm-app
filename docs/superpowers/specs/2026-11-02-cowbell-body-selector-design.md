# Cowbell body selector and TONE knob

Status: landed on main 2026-11-04. Dated snapshot; the code is the source of truth.

Snapshot of a design agreed on 2026-11-02. The code is the source of truth once this lands;
this file records the intent and the calls behind it.

## Intent

The cowbell sounds great, but there are more bad knob combinations than good ones. Today's BODY
knob sweeps five slices, each a different resonator model, so the character lurches every
0.2 of the knob; RING changes polyphony (1/2/4), damping and brightness at once, so the same
RING position means something different on every body; and the ENGINE picker adds a synth-voice
exciter nobody needs for a bell. The player should be able to nail the classic bell sounds,
plus a handful of string and synth sounds, by picking a body and turning knobs that only ever
land somewhere good. Fewer sounds in total, all of them keepers.

## Goals

- Pick the body with a selector, not a knob: **STRING · METAL · SYNTH**, with **SITAR** hidden
  behind a long press on the first position (press-and-hold STRING toggles it to SITAR and
  back). Four bodies, one resonator model each.
- Three knobs that shape within the body: **TONE** (resonator structure), **RING** (resonator damping),
  **INTENSITY** (brightness plus hit strength, as now). Each knob's range is clamped per body so
  its whole sweep is usable, and about five distinct sounds sit along TONE on every body.
- Remove the ENGINE picker and the synth-voice exciter. The bare impulse (and SYNTH mode's noise bed)
  excites every body.
- Polyphony is fixed per body, no longer stepped by RING.
- The untouched bell (a tap with nothing changed) is a classic Modal cowbell. This is a deliberate
  change: today's default `metal = 0.84` sits in the FM slice, so the current untouched bell is
  the FM model at structure 0.42 (user's call, 2026-11-02: METAL as the default, accept the change).
- Everything else stays: TAP/SYNTH/SHAKE modes, fills, lock, bend (always to scale while Pulsar plays, the chip is gone), grid,
  octave, MORE, motion, tilt owning structure, the finger's vertical position offsetting
  structure.

## Non-goals

- An on-board reverb. Deferred until the fifteen or so sounds are heard dry.
- Blending between bodies. A crossfade between resonator models needs a second resonator instance,
  which is too much for this. The selector switches, and the resonator's own "model applies at
  the next strike" rule covers the transition.
- Any change to strikes, fills, bursts, ducking, stereo width or the bell's 3D view.

## Anti-goals

- A knob sweep with a dead or ugly stretch. If a range end sounds bad, narrow the range.
- RING meaning different things on different bodies. On every body it is the tail: short knock
  at the bottom, long ring at the top.
- Hiding a sound change inside INTENSITY. It stays brightness and hit strength only.

## The bodies

| Selector | Resonator model | Polyphony | Character |
|---|---|---|---|
| STRING | 2 (string) | 2 | pluck, guitar, harp, muted wire |
| SITAR (hidden) | 1 (sympathetic strings) | 1 | shimmering drone, strings ringing along |
| METAL | 0 (modal) | 1 | cowbell, agogo, small and big bells, glass |
| SYNTH | 3 (FM) | 2 | two-operator FM through the resonator |

The resonator splits a fixed budget between voices, so polyphony changes each hit's tone as well as the
overlap: modal gets 60, 28 or 12 modes per voice at polyphony 1, 2 or 4, and sympathetic strings
get 8, 4 or 2 strings per note. Polyphony 1 is the richest tone, so METAL and SITAR use it (a
repeat hit re-excites the same bank, which is SITAR's drone). 2 on STRING and SYNTH lets a
ringing note survive the next hit, which keeps fast playing articulate. The stereo width rule
(narrow ping-pong above polyphony 1) is unchanged.

## The knobs

All three ports are 0..1 floats. The mapping to the resonator lives in `cowbell_macros.h` as a
per-body table, replacing the five-slice `cowbell_macros`:

```
structure  = tone_lo[body]  + (tone_hi[body]  - tone_lo[body])  * TONE
damping    = ring_lo[body]  + (ring_hi[body]  - ring_lo[body])  * RING
brightness = bright_lo[body] + (bright_hi[body] - bright_lo[body]) * INTENSITY - 0.15 * RING
strike_gain = 0.35 + 0.65 * INTENSITY            (unchanged)
```

The `- 0.15 * RING` brightness trim stays: it is what keeps a long tail from building past the resonator's
limiter at full INTENSITY, and the SYNTH-mode noise trim (`kNoiseRingTrim`) keys off RING the
same way it does today.

Measured ranges as built, tuned to measurements and not yet by ear (the ear test is the user's).
They replace the builder's first guess. Source of truth is `cowbell_macros.h`:

| Body | structure (TONE) | damping (RING) | brightness (INTENSITY) | level_trim |
|---|---|---|---|---|
| STRING | 0.25 → 0.90 | 0.30 → 0.84 | 0.37 → 0.87 | 3.2 |
| SITAR | 0.10 → 0.70 | 0.00 → 0.95 | 0.30 → 0.85 | 1.6 |
| METAL | 0.25 → 0.75 | 0.3156 → 0.76 | 0.44 → 0.84 | 1.0 |
| SYNTH | 0.30 → 0.90 | 0.35 → 0.90 | 0.25 → 0.75 | 1.25 |

The resonator's structure clamps at 0.9995 and damping at 0.95 as today. On the FM model, structure is
the operator ratio and brightness is feedback, so SYNTH's brightness ceiling sits lower.

The tilt and finger offsets (`structure_offset`, −0.4..0.4, and `position_offset`) keep riding
on top of the mapped structure and a centred position, exactly as now.

**Default sound.** Body METAL, TONE 0, RING 0.48, INTENSITY 0.85, the user's values by ear on
2026-11-03. The design started from TONE 0.3, RING 0.1, INTENSITY 0.7: the modal model at structure
0.40, damping 0.36, brightness 0.705, a tight classic cowbell. Today's untouched bell (FM at structure 0.42,
damping 0.35) is close on SYNTH at TONE 0.2, RING 0, though SYNTH's polyphony of 2 thins it a
little against today's polyphony 1. The exact default TONE
and RING are the user's to settle in the ear test; the values above are the starting point.

**Damping match.** Only the METAL default needs to land near today's damping at RING 0. The
other bodies' RING floors are set by ear, not by the old formula.

**SYNTH mode's held note.** With the exciter gone, the held note runs on the noise bed plus the
gate strike on every body, which is exactly what it does today when no engine is picked. No
other change to SYNTH mode.

## Ports

In `CowbellSymbol` and the plugin:

- Remove `METAL` (float) and `ENGINE` (int).
- Add `BODY` (int, 0..3: STRING, SITAR, METAL, SYNTH; default 2 METAL).
- Add `TONE` (float 0..1).
- `RING` and `INTENSITY` keep their symbols; their meaning narrows as above.

The C++ side mirrors this: `cowbell_metal` and `cowbell_engine` atomics go, `cowbell_body` (int)
and `cowbell_tone` (float) arrive, and the routing in `orpheus_engine_routing.cpp` maps the new
symbols. The exciter voice, its allocation buffer, `render_exciter`, the exciter envelope kinds
and the `kotlin_engine_ordinal.h` include leave `orpheus_unit_cowbell.cpp` and
`orpheus_engine.h`. SYNTH mode's held note keeps its noise bed and its gate strike.

## Persistence and presets

- `CowbellUiState` drops `metal` and `engine`, adds `body: CowbellBody` and `tone: Float`. The
  saved-state JSON ignores unknown keys, so an old save simply loses those two fields and lands
  on the defaults. No mapping of an old `metal` value onto a body: the slices never matched the
  new bodies one to one, and the defaults are the sound this change is for (builder's call).
- DJ vibe presets: `PresetField.BELL_METAL` ("bell.metal") and `BELL_ENGINE` ("bell.engine")
  become `BELL_BODY` ("bell.body", label "BODY") and `BELL_TONE` ("bell.tone", label "TONE").
  Old keys in a stored preset are dropped on load. No shipped vibe carries a bell preset today,
  so nothing ships with the old keys. Preset change chips and preset naming that special-cased
  the engine name now name the body.
- MIDI learn: the body selector is learnable where the ENGINE chip was; TONE is a learnable knob.

## UI

**DJ panel** (`CowbellDjPanel`, `CowbellControls`): the body selector goes on top of the first
(controls) column and the ENGINE chip goes, so the chip count holds and the knob column stays
three knobs: TONE over RING over INTENSITY. The selector is a three-way segmented chip row,
STRING · METAL · SYNTH, shortened to `Str`, `Mtl`, `Syn` in the 80dp column. The first
segment reads String or Sitar to show which is live; a long press on it toggles. A short press
on any segment selects it. SITAR is its own `BODY` value, so selecting METAL and coming back to
the first segment returns to whichever of STRING or SITAR was last live there, and a saved state
or preset restores it.

**Orpheus panel** (`CowbellPanel`): the engine picker row becomes the same selector; the knob row
becomes TONE, RING, INTENSITY.

`metalModeName` and the five-step names go. The `hasNativeEngine` parameter threaded through
both panels for the engine picker goes with it.

**Agent and help text**: the cowbell's port descriptions and help markdown in `CowbellFeature`
describe the four bodies and three knobs.

## Tests

- C++: `test_cowbell_unit.cpp`'s macro tests check the per-body table: model and polyphony per
  body, the range ends, monotonic structure along TONE and damping along RING, and that the
  default is the modal model at the documented structure and damping. The exciter tests go. Suites to run:
  `cowbell`, then the full `orpheus_dsp_test -j`.
- Kotlin: `features/cowbell` (panel tests swap the metal tag for a tone tag and the name test for
  a body-name test; the selector's press and long press get a test), `core/plugins/cowbell`,
  `apps/djapp/shared` preset tests (`VibePresetPortsTest`, `PresetChangesTest`,
  `PresetNamingTest`, the preset ViewModel test's bell assertions).
- The ear test comes before landing on main: the user plays each body across TONE and RING and
  adjusts the table. The ranges in this doc are a starting point, not the deliverable.

## Left to the builder

- The selector's long-press threshold and feedback (match the panel's existing long-press
  conventions, e.g. the mode chip).
- Whether `CowbellBody` lives next to `CowbellMode` in `features/cowbell` or in `plugin-api`
  beside the symbol.
- The order of removals so each module compiles at every step (symbol, plugin, engine, unit,
  feature, panels, presets).

## Added while building (2026-11-02)

- **A new body waits for the next strike.** The unit latches the whole row in `sounding_body`, so
  a tail keeps its model, ranges and level until a strike, a gate rising edge or an idle bell.
  Without it a change mid-ring stepped the level up to 10 dB and could leave a drone.
- **MIDI.** A learned CC on BODY maps 0-21 STRING, 22-63 SITAR, 64-105 METAL, 106-127 SYNTH. Other
  int ports keep their old float scaling.

- **Selector placement (user, 2026-11-03).** It sits centred over the bell on both panels, in the
  DJ panel's open and collapsed views. This replaces the placement in the UI section above. The
  collapse pill narrows to its chevron when collapsed so the selector clears it, and the DJ chips
  centre on the knobs' line when they can.
- **A dropdown, not a three-way (user, 2026-11-03).** The selector is one chip naming the body, 34dp
  tall with 13sp text and at most 120dp wide, whose menu lists String, Sitar, Metal and Synth. Sitar
  is no longer hidden, so the long press and the remembered string are gone. This replaces the
  three-way selector and long press in the Goals and UI sections above.

- **Defaults by ear (user, 2026-11-03).** METAL, TONE 0, RING 0.48, INTENSITY 0.85. The Bend
  Free/Scale option is gone: the bend snaps while Pulsar plays and is free when stopped.

## Known gaps at the ear test

- **STRING pitch:** the strongest partial reads +3, +158, +247, +194, +103 cents across TONE. Left
  for the user's ruling.
- **SITAR RING:** T60 only spans about 4 to 5.7 s. A shorter tail needs more than a range change.
- **METAL INTENSITY:** about 25 dB RMS from 0.2 to 1. **METAL TONE 0** reads 3x the root.
- **Old saves** keep `ring` 0, so METAL sounds shorter there than on a fresh install.
