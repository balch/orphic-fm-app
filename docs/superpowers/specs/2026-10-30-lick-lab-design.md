# Lick Lab: play licks into a vibe

Date: 2026-10-30.

Status: phases 1-2 landed on main 2026-10-31. Dated snapshot; the code is the source of truth.

## Why

The user writes Pulsar licks by hand as `LickStep(degree, beats, velocity)` lists. That is slow, and when a solo
needs writing the hard part is not typing, it is knowing what to play. Lick Lab lets the user play a phrase on one of
the vibe's tracks, live, through that track's own engine and sound, with a screen that shows where they are in the key
and the song, and hands back the phrase as lick steps.

The user wants to borrow *techniques* from songs they love and make them their own. Later, the AI coaches technique
next to the keyboard. Long term this could become a game.

## Goals, non-goals, anti-goals

Goals (all phases):
- Play any of the 8 tracks live from the Mixer sheet, through its live engine, while the song plays.
- Mac keyboard and mouse on desktop; touch, several fingers and the half-open fold on Android later.
- See each note as you play it: name, scale degree in the vibe's key, and how it sits against the chord and the call.
- Slides (drag, fling) and wiggles (bend/vibrato).
- Turn a played phrase into paste-ready `LickStep`/`Rest` lines and read it from Claude Code over MCP.

Non-goals for now: chords on one track (one voice per track; paraphonic tracks are a separate, parked design), saving
wiggles into licks (phase 4), the game (phase 5), iOS-specific work.

Anti-goals:
- **The tool never invents notes.** Every note in a captured lick is one the user played. Guides, glows, calls and
  coaching only light the way. A call played by another track is that track's own authored material, never captured as
  the user's.
- No real song, artist or band names in code, comments, commits, branch names or this spec. main is public.
- Keys typed into Lick Lab must not reach the app's other key handlers (vibe stepping on arrows, Enter swallowing,
  chat input).
- Converting rests must not change how any shipped vibe sounds, unless the user rules otherwise (see Open rulings).

## Phases (agreed order)

1. `Rest()` and notes below the root.
2. Desktop v1: live track input, Mac keys and mouse with slides and live bend, note and degree display, Weave mode
   (call and answer against a caller track), takes exported as lick lines and through `keyboard_read`.
3. Touch: several fingers (hammer-ons, trills, second finger drives the caller), fling up/down as a slide, half-open
   fold layout.
4. Lyric mode, tension-driven visuals and ghost notes, AI technique cards, wiggles saved into licks.
5. The game.

This spec covers phases 1 and 2 to build; 3 to 5 are direction only.

---

## Phase 1: `Rest()` and notes below the root

### What changes

- `LickStep` gains `val rest: Boolean = false`. A rest is `rest = true`; its `scaleDegree` is ignored (written as 0).
- A top-level factory `fun Rest(beats: Float): LickStep` in `Lick.kt` returns `LickStep(0, beats, velocity = 0f,
  rest = true)`. Usage: `listOf(LickStep(4, 0.5f, 0.9f), Rest(0.5f), LickStep(-2, 1f))`.
- A negative `scaleDegree` is a real note below the lick base: degree -1 in B minor at `lickOctave = 5` is A4, -7 is B3.
  `lick_degree_to_midi` already wraps negative degrees this way.
- `Lick.init` validates `scaleDegree in -32..63` for non-rest steps (my call: comfortably beyond any authored range, and
  keeps the C++ `int8_t` and the rest marker apart).

### Wire format and engine

- The degree already travels as a float in a 4-float stride per step (`PulsarFeature.kt` ~1999, ~2023, ~2076) and is
  cast to `int8_t` in C++ (`orpheus_engine_routing.cpp` ~1160, ~1212; `orpheus_unit_pulsar.cpp` ~1607 for the pool).
- A rest is marshalled as degree `-128` (`kLickRestDegree`, defined once in C++ and mirrored by a Kotlin constant,
  checked by an existing-style port-mirror test). No stride change, so no index shifts.
- Every "degree < 0 means rest" check becomes `degree == kLickRestDegree`:
  `generate_lick_pattern` (`pulsar_pattern_gen.h` ~778), call/response (`pulsar_bar_strategy.h` ~200, ~272), the
  CHORD_TONES note in `orpheus_unit_pulsar.h` ~110. The rest-to-note mutation under RANDOM keeps working on rests.
- `mutate_live_lick` (`pulsar_lick_techniques.h` ~44-97) and the spurt drift clamp (`orpheus_unit_pulsar.cpp`
  ~329-351) must skip rests and must never produce `kLickRestDegree` from a note (clamp notes to -32..63).
- `synthesize_lick_from_steps` (`pulsar_pattern_gen.h` ~672-713) no longer needs its octave lift; remove it and update
  `test_pulsar_sections.cpp` ~1973 to expect the unshifted contour. (Only if the lift's sole purpose is dodging the rest
  rule; if it also keeps notes in range, keep it and say so.)
- `pulsar_solo.h`'s Markov -1/-2 (rest/hold) is a separate convention. Leave it.

### Migration (sound must not change)

- Every authored `-1`-as-rest in `features/pulsar` vibes (94: 35 positional, 59 named) becomes `Rest(beats)`.
  The two test uses (`PulsarLickMutationModePushTest:142`, `PulsarBassLinePushTest:98`) likewise.
- The songref importer (`apps/djapp/ai/.../songref/MelodyLick.kt:24`) emits `Rest` instead of -1;
  `TabParserTest:201` updated.
- **12 suspicious steps** are silent today but look written as notes: -2/-3 at `TremoloTideVibe:306,310`,
  `ArmyStompVibe:299,304`, `DustGrooveVibe:62`, `VoltageStrutVibe:72`, `TechnoWobbleVibe:115`, and -1 with a note-like
  velocity or comment at `ArmyStompVibe:294`, `DustGrooveVibe:61`, `VoltageStrutVibe:75`, `FilterFunkVibe:173`,
  `SpaceDroneVibe:206`. Ruled: convert them to `Rest` so nothing changes, each with a one-line
  `// silent since authoring; was degree N` comment. See Rulings.
- **Base and order.** The user commits the vibe work (uncommitted rests in the main checkout) to local
  main first. This branch is then brought up to local main (which carries many unpushed vibe commits) *before* the
  migration runs, and the rest counts and the suspicious-step list are regenerated against that base. The numbers in
  this spec are from `eed68ad7f` and are indicative only.
- Lick Lab is AI edition only (it lives in the AI Mixer and ships an MCP tool).
- Guard: a jvmTest in `features/pulsar` walks every shipped vibe's licks (lick, bass line, pool) and fails on any
  negative-degree note not on an explicit allowlist of intended below-root notes (starts empty). This catches a
  late-merged `LickStep(-1, …)` written under the old rule. Enumerate vibes the way existing catalog tests do, not via
  DI.

### JSON and the AI

- AI vibe JSON already on disk uses `"scaleDegree": -1` for rests. A custom `LickStep` serializer reads a step with no
  `rest` key and a negative degree as a rest (legacy), and always writes the `rest` key, whatever the `Json`
  instance's `encodeDefaults` (`vibeCastJson` has it off). New JSON is therefore unambiguous.
- Consequence (my call): an AI that writes a negative degree without `"rest"` gets a rest, as it always did. To write a
  note below the root it writes `"rest": false`. The guide says so.
- `vibe_guide.md` (lines ~287, 292, 298-300, 324, 326, 869, 1084 and the 8 JSON examples ~457-487) and
  `.claude/skills/vibe-creator/SKILL.md:230` are rewritten for `"rest": true` / `Rest(beats)`.
- The schema tool (`VibeSchemaTool`) shows the new field.

### Phase 1 tests

C++: the lick suites listed in the appendix, plus new cases: a negative degree renders the expected MIDI note; a rest
marker renders silence; mutation never turns a note into the marker. Kotlin: `features/pulsar`, `core/plugins/pulsar`,
`features/ai` (guide examples, apply/cast/schema), `apps/djapp/ai` (`TabParserTest`). JSON round trip of legacy and new
forms.

---

## Phase 2: Desktop v1

### Weave mode

Weave mode is the call-and-answer screen below: one track is the **caller** and plays its own sequenced material,
and the user answers on their armed track. There is no forced call/answer cycle: the user plays whenever they like,
over or between the caller's phrases, and the screen shows the gaps the caller leaves. With no caller picked, the top
half shows only the chord and beat grid. (Lyric mode, phase 4, is the other mode.)

### Which tracks, which slot, which octave (my calls)

- The track picker offers every track whose role is melodic or chordal. Drum and percussion tracks are left out of
  v1. An armed track plays single notes whatever its role: comping, arp and restrike are bypassed while armed.
- **Export target.** A chooser lists the vibe's lick slots: `lick`, `bassLine`, each `LickPool` entry, and the solo
  lick. It defaults to the slot the armed track plays from (the bass track defaults to `bassLine`; a track playing the
  vibe lick defaults to `lick`), falling back to `lick`.
- Degree 0 is the base of the chosen slot: `lickOctave` for `lick` and the pool, `bassLineOctave` for `bassLine`, and
  the solo's `octaveShift` applied for the solo lick, each resolved the way C++ resolves it. The key map's home row
  starts at that base, so a bass take sits in the bass register. Changing the slot re-bases the map and the readout.
- The copied Kotlin names the slot: `bassLine = Lick(...)`, `lick = Lick(...)`, and so on.

### While armed, what the arrangement still does (my calls)

- Section mutes, section volume and the fader still apply to the armed track (they act on its output).
- Solo handoffs skip an armed track; the handoff picks another or holds.
- A section key shift (kraken) moves the key map and the degree readout with it: read the shift from
  `pulsarVizFlow.krakenShift`. Degrees in a take are relative to the vibe's key, not the shifted key, so a take played
  in a shifted section exports in the vibe's own key.

### Where it lives

A **Lick Lab** toggle in the AI Mixer header swaps the track strips area for the Lick Lab surface (my call: keeps the
chat in the `beside` slot free for phase 4 coaching). Wide layout (`WideMixer`, ≥600×400dp) is the target. The compact
and stacked layouts show a "Lick Lab needs a wider window" note for now.

Lick Lab gets its own `LickLabViewModel` + `LickLabFeature` following the panel-viewmodel-feature MVI pattern
(`LfoViewModel` is the canonical reference), contributed under FeatureScope like `AiMixerViewModel`.

### Screen (call and answer, chosen from the mockups)

- **Header row:** my track picker (8 track chips, showing each track's name and engine), caller track picker (or
  none), key readout ("B minor · 0 = B4"), in-key/chromatic toggle, record dot.
- **Top half, the call:** the caller track's notes as they sound, drawn as a scrolling constellation on a 2-bar window,
  with the current chord shown underneath.
- **Bottom half, the answer:** the user's notes drawn the same way, live. Each star shows the note name and degree.
  Chord tones glow brighter than passing tones; the root flashes. Dashed echo lines join an answer note to a call note
  on the same beat position.
- **Keyboard strip:** on-screen keys labelled with Mac key, note name and degree; lit while held; clickable.

### Mac keyboard map (in-key mode, default)

- Home row `A S D F G H J K L ; '` = degrees 0..10 (B4 up).
- Top row `Q W E R T Y U I O P` = one octave up (degrees 7..16).
- Bottom row `Z X C V B N M` = one octave down (degrees -7..-1).
- In-key mode cannot play a wrong note. Chromatic mode switches to a piano layout on the home and top rows.
- Overlapping keys (press a second key while holding the first) is legato: no re-strike, pitch jumps (hammer-on).
  Releasing the second key returns to the still-held first. Holding Shift while overlapping makes it a slide.
- Auto-repeat is ignored (`SynthKeyboardHandler` in `features/voice` is the model).

### Mouse

- Click a key to play, release to stop. Drag across keys = slide (glide), drag speed sets `glideRate`.
- Vertical drag on a held key = live pitch bend, up to ±2 semitones (heard, not saved, until phase 4).
- Velocity from where the key is clicked (lower on the key = harder, like a piano key). Mac keys play at 0.8.

### Focus

The Lick Lab surface is focusable and claims focus when shown. Its `onPreviewKeyEvent` consumes every key it maps,
plus Shift, before the window's handlers see them, so arrows-step-vibe, Enter swallowing and the chat field never react.
Esc still closes the sheet. Clicking the chat field moves focus away and Lick Lab stops listening (the key strip dims).

### Live track input (engine)

- New per-track atomics, written through the Pulsar plugin ports like `track_N_audition_gain` (port symbols,
  `excludeFromPresets = true`): `live_active`, `live_note` (float MIDI, includes bend), `live_gate`, `live_velocity`,
  `live_glide`.
- In `orpheus_unit_pulsar.cpp`, while `live_active` is set for a track, the sequencer's gating for that track is
  skipped (the score path's `continue` near ~5216 is the precedent) and the voice takes `target_pitch`, gate, velocity
  and glide from the atomics. A rising gate re-strikes; a pitch change with the gate held is legato (glide per
  `live_glide`, 0 for a hammer-on).
- My call: while a track is armed in Lick Lab, its sequenced notes are silent, so answers have room. The track's
  engine, sound, effects, fader and mute all stay as they are.
- Arming survives vibe switches? No: a vibe switch disarms (the key and track may change).

### Readbacks the screen needs (not available today)

- Each track's sounding note and gate at viz rate (~60 fps): add to `PulsarVizData` (it has gates and velocities but no
  pitches). Feeds the caller constellation.
- The current chord (degree and quality) per bar: add to the viz or arrangement readback. Feeds chord glow.
- Beat position: `musicPulseFlow` (`beatPhase`, `msPerBeat`) plus `arrangementStateFlow.barsElapsed`.
- Key: `vibeFlow` (`rootNote`, `scaleType`, `lickOctave`, `bpm`). Degree 0 = `root + lick_octave_base(...)`; when
  `lickOctave` is auto (-1) use the same fallback as C++ (`lick_octave_base`), mirrored in Kotlin with a parity test.

### Takes and export

- Recording is on while the record dot is lit. A take ends after one bar of silence or when the user hits stop.
- Quantisation: note starts and lengths to the nearest 16th (0.25 beat) against the beat clock. Gaps become `Rest`.
  A take starts on the bar it was played in; leading silence from the bar line becomes a leading `Rest`.
- Each step: `scaleDegree` from the played note (relative to degree 0), `duration` in beats, `velocity` as played,
  `glideRate` when the note was slid into, else default.
- Chromatic notes cannot be written as scale degrees. A take that contains one is marked, and its export snaps each to
  the nearest scale tone with a `// played C natural` comment so the user decides. (My call.)
- `loopLength` is the take's length (from its first bar line to the end of its last note) rounded up to whole bars,
  in beats. (My call.)
- Takes list (last 20, in memory): play back on the armed track, star, delete. "Copy" puts paste-ready Kotlin on the
  clipboard:
  `Lick(steps = listOf(LickStep(4, 0.5f, 0.9f), Rest(0.5f), ...), loopLength = 8)`.
- MCP: a `keyboard_read` tool (ToolProvider, FeatureScope, lazy deps, as in `MixTools.kt`) returns the takes:
  structured steps, the Kotlin text, vibe, track, key, bpm and starred flag. MCP-only (added by hand next to
  `AgentErrorsTool`) until the in-app AI has a use for it.

### Phase 2 tests

- Pure Kotlin: key map (in-key and chromatic), degree math against `lick_degree_to_midi` cases, quantiser and
  take-to-Kotlin export (including rests, glides, chromatic marks), hammer-on/held-note stack.
- ViewModel tests in `apps/djapp/ai` (arm/disarm, port writes, vibe-switch disarm).
- C++: live override suite (gate, legato, glide, disarm hands back to the sequencer cleanly, armed track silent).
- `keyboard_read` result shape test. Focus test: a mapped key does not reach the arrows/Enter handlers.
- Ear and hand test by the user on `./gradlew :apps:djapp:desktopApp:run -Pedition=ai`.

---

## Phases 3-5 (direction)

- **Touch and fold (3):** several fingers on one track work like a guitarist's hand (held-note stack, hammer-ons,
  trills); a second finger on the call half drives the caller track. Fling up/down = slide up or fall-off.
  Half-open fold (`DjLayout.Tabletop`, `LocalHinge`): call above the hinge, playing surface below.
- **Lyric mode and coaching (4):** no caller; phrase slots like lines of a lyric sheet, following the arrangement's
  sections. Song tension drives screen intensity; soft ghost notes are saved with `hitProbability < 1` so they fill in
  as the section climbs. AI technique cards beside the keyboard via `song_reference` (devices, never notes). Per-note
  wiggle saved into `LickStep` (new field + engine).
- **Game (5):** open.

## Rulings

1. The 12 suspicious steps stay silent: each becomes `Rest` with a `// silent since authoring; was degree N` comment.
   Un-silencing any of them is a later, per-vibe ear-test decision. (User, 2026-10-30.)
2. A rest is a step marker (C++ -128), not a degree, so degrees -32..63 all sound and negative ones play below the root.
   A rest keeps the velocity its legacy step had and may leave out `scaleDegree`.
3. The live input latches each strike count once across blocks and holds through an engine swap; a tap shorter than
   a block still sounds.

## Known gaps

- Takes recorded on a chord-following track are only marked "paste may transpose". Whether live play should follow
  the chords is still the user's call.
- Lick Lab shows only in the AI Mixer's wide layout.

## Appendix: tests per area

C++ lick suites: pattern_gen, chord_mutation, sections, pulsar, osc, str_lick, lick_marshalling, bass_line,
lick_offset, lick_select, solos, tension, texture_line, ghost_pitch.
Kotlin: `features/pulsar` (PulsarLickTest, PulsarLickMutationModePushTest, PulsarBassLinePushTest,
PulsarMarshalStrideTest, LickRotationTest, VibeSerializationTest, VibeCatalogTest), `core/plugins/pulsar`
(PulsarPluginLickPortsTest), `features/ai` (VibeApply/Cast/Schema/Read tool tests, VibeGuideContentTest,
VibeGuideExamplesTest), `apps/djapp/ai` (AiMixerViewModelTest, MixToolsTest, McpToolAdapterTest, TabParserTest).
