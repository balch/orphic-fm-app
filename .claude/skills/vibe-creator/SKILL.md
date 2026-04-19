---
name: vibe-creator
description: Use when creating a new Pulsar Vibe (beat-machine preset) for the Orphic FM app — especially when translating a musical reference like a song, artist, or genre feel into the Vibe schema. Triggers on prompts like "make a vibe based on [song/artist/genre]", "create a new Pulsar preset for X feel", "add a vibe that sounds like Y", or any request to add a *Vibe.kt file under features/pulsar/.../vibes/. Covers the Vibe schema, naming rules (including the no-trademark rule), DI registration, tuning recipes, and benchmark testing against DogHouseVibe.
---

# Vibe Creator

A Vibe is a complete Pulsar preset: 8 tracks, tempo, key, macro defaults, section arrangement, tension arc, band personalities, and effects. Users pick a vibe and tweak the live macros (Energy, Complexity, Space, Mood, Deep). This skill covers how to turn a musical reference into a well-tuned Vibe.

## Where vibes live

- Source: `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/<Name>Vibe.kt`
- Schema (every type you will reference): `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/PulsarVibe.kt`
- Canonical quality benchmark: `DogHouseVibe.kt` — after any Pulsar change, test this vibe first; a new vibe should feel at least as coherent and musical.

Registration is automatic via Metro DI — each vibe class carries `@Inject` and `@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())`, and `PulsarViewModel` receives `Set<VibeProvider>` in its constructor. **No extra registration file, DI module, or list edit is required** — dropping a correctly-annotated file into the `vibes/` directory is enough.

## Naming rule — read this first

This project has a hard rule: **never use trademarked artist, band, album, or song names in user-facing code or comments.** The `name` field in `Vibe(...)` is user-facing (shows up in the Pulsar vibe picker), as are KDoc block comments at the top of the file.

- Pick an **evocative original name** that captures the *feel*, not a name that identifies the source. "Closer" -> "Sunken Place". "Echoes" -> "Deep Space". "You Really Got Me" -> "Garage Blitz".
- In the KDoc at the top of the class, describe the feel (industrial, brooding, 4-on-floor, detuned bass, etc.) but do **not** name the source track, band, or album.
- Internal private notes or commit messages can mention the source if it helps — just not the committed source file.
- Check the existing vibes list before picking a name to avoid duplicates.

## Workflow

1. **Decompose the reference musically.** Before touching code, write down (in your head or scratch) the seven dimensions the schema cares about:
   - Tempo (BPM)
   - Key (root + scale)
   - Feel (swing, ghost density, chord motion, tension arc)
   - Groove (which drums play where, bass role, rhythmic density)
   - Harmony (progression style, chords-per-bar, custom progression or Markov matrix)
   - Texture (which engines for bass, lead, pads, FX)
   - Structure (sections, section energy overrides)
2. **Pick the closest gold-standard vibe** as a structural template — the file you will crib the BandMember/Arrangement shape from. Examples:
   - Brooding / dark / industrial / heavy — `DogHouseVibe`, with darker `rootNote`, `ProgressionStyle.DARK`, lower `mood`.
   - Ambient / long-form / dronal — `DeepSpaceVibe` (uses `chordMatrix`, `lickOctave`, `EnvelopeType.TIDES`, `EnvelopeProfile.DRONE`).
   - 4-on-the-floor / club / driving — `CosmicTechnoVibe`.
   - Groove / pocket / lo-fi — `DustGrooveVibe`.
   - Marching / militant / anthemic — `ArmyStompVibe`.
   - Reggae / skank — `RastaManVibe`.
   - CHORDAL-comping demo — `CompLabVibe` (uses a `generateCompLabVibe` helper — note: the file also contains commented-out family examples).
3. **Copy that template file** to your new `<Name>Vibe.kt`, change the class name, the display `name`, and then tune each parameter against your musical decomposition. Do not start from a blank file — there are too many required fields.
4. **Compile.** Run `./gradlew :features:pulsar:compileKotlinJvm` (fast — Pulsar only). Fix any type errors.
5. **Run it live.** Build the JVM desktop app and load the vibe from the picker. A/B against `DogHouseVibe`.
6. **Tune by ear.** The file should change first, not any wider code. If you find yourself wanting to change Pulsar DSP, stop — the DogHouseVibe benchmark exists to catch regressions, and vibes should only tune what the schema exposes.

## The Vibe schema — parameter-by-parameter

All of this is in `PulsarVibe.kt`. Read the KDoc there for the full range of valid values; this section focuses on **what to set based on a musical reference**. Ranges in parens are the useful-in-practice subset, not the absolute min/max.

### Top-level (the frame)

| Field | Purpose | Decision rule |
|---|---|---|
| `name` | Vibe picker label | Short, evocative, **no trademarks**. |
| `bpm` | Tempo | Match the reference's BPM (use a tap-tempo app if unsure). |
| `envelopeType` | `AD` / `TIDES` / `BLEND` | `AD` for tight/EDM/techno, `TIDES` for ambient/drone/pad-heavy, `BLEND` for anything that spans a dynamic range. |
| `rootNote` | Musical root | Use enharmonic equivalents where needed (`RootNote.G_SHARP` == Ab). |
| `scaleType` | Scale | `MINOR` / `PHRYGIAN` / `HARMONIC_MINOR` for dark, `MAJOR` / `MIXOLYDIAN` for bright, `IN_SEN` / `HIRAJOSHI` for exotic. |
| `energy` / `complexity` / `space` / `mood` | Starting macro positions (0-1) | These are the user's starting knob positions. Lean slightly toward what the vibe wants to be — users will dial from there. |
| `deep` | Starting effect send (0-1) | 0.4-0.7 for wet vibes, 0.2-0.3 for dry/punchy. |
| `stepCount` | 16 or 32 | 16 for short repetitive patterns, 32 for 2-bar phrases. |

### `GenreProfile` (groove DNA)

- `swingAmount`: `0.0` = dead straight (techno, industrial). `0.05-0.1` = natural human feel. `0.3+` = heavy shuffle (blues, hip-hop).
- `ghostProbability`: `0.05-0.15` = subtle, `0.2-0.3` = funky, `0.4+` = very busy.
- `noteRangeLow/High`: MIDI note bounds. `36` = C2 (deep bass), `48` = C3, `60` = C4, `72` = C5. Narrow the range for tight pockets; widen for melodic leads.
- `rhythmDensity`: Use `RhythmPattern.*.density`. `SPARSE` (ambient), `FOUR_ON_FLOOR` (club), `BACKBEAT` (rock/hip-hop), `DENSE_16TH` (DnB/techno).
- `progressionStyle`: `POP` (I-IV-V-vi), `BLUES` (12-bar), `DARK` (diminished/minor), `DRONE` (static), `MODAL` (no resolution), `ASCENDING` (rising), `JAZZ` (ii-V-I), `SAD` (descending).
- `chordsPerBar`: `1` = slow (static/dronal), `2` = standard, `4` = busy.
- `customProgression`: Optional `List<Int>` of scale degrees (0..6). Overrides the template sequence but keeps the Markov matrix. Great for "hang on tonic then dip" forms. Size 1..8.
- `chordTransitionMatrix`: Optional 7x7 Markov via `chordMatrix(...)`. Use only when a preset `progressionStyle` does not cover the target motion (see `DeepSpaceVibe` for an example).

### `progressionAnchor` + `progressionDriftRange`

How often the Markov progression resets to its starting state. `EVERY_4` or `EVERY_8` is typical. `progressionDriftRange` (0-1): how much the progression is allowed to wander between resets. `0.1-0.2` = tight/hypnotic, `0.5+` = loose/jazzy.

### `tracks` — exactly 8 `TrackVoice`s

Convention (not enforced): 0=kick, 1=snare, 2=hat, 3=bass, 4=keys/lead, 5-6=texture/FX, 7=wildcard. Any track can use any engine. Each track has:

- **`engineEdm` / `engineSpace`**: Two Plaits engines. The mix crossfades between them based on the Energy macro. Use the same engine in both slots if you do not want crossfade. Common picks:
  - Drums: `BD`, `SD`, `HH`, `NSE`, `PAR`.
  - Bass: `WSH` (gritty), `VCF` (filter-sweep bass), `PD` (warm round), `VA` (analog poly).
  - Lead: `DX` / `DX2` / `DX3` (FM), `WSH` (distorted), `FM`, `WTB` (wavetable).
  - Pad: `ENS` (string ensemble), `STR` (string model), `GRN` (granular), `CHD` (chord engine), `ADD` (additive).
  - Texture/FX: `MOD` (modal/metallic), `PAR` (particles), `SPK` (speech), `SWM` (swarm), `NES` (chiptune), `TRN` (wave terrain).
- **`role`**: `TrackRole.Percussive`, `TrackRole.Melodic(chordFollow, lickMode)`, or `TrackRole.Chordal(comping, chordFollow)`. Wrong role = wrong pattern generator (e.g. chord-following on drums makes no sense).
- **`volume` / `pan` / `density`**: mix-level basics.
- **`harmonics` / `timbre` / `morph`**: Plaits engine knobs, meaning varies per engine. Default 0.3-0.5 works; sweep to taste.
- **`envelopeProfile`**: `RHYTHM` (drums), `MELODIC` (bass/lead), `EFFECT` (pad/texture), `WILD` (wildcard), `DRONE` (infinite sustain ambient).
- **`macroMap`**: `TrackMacroMap.RHYTHM`/`MELODIC`/`EFFECT`/`WILD`. Controls how the 4 macros move this track's parameters. Match to `envelopeProfile` unless you have a reason not to.
- **`barStrategy`**: `REPEAT` (same every bar — driving elements, anchoring bass), `MUTATE` (slight variation), `FILL` (adds fills at phrase boundaries), `CALL_RESPONSE` (alternates), `INDEPENDENT` (regenerated — textures, pads).
- **`modLfo*`**: Slow-modulation parameters for pad/texture tracks. `rate` 0.03-0.1 is glacial; `depth` 0.3-0.7 audible.
- **`holdProbability/Min/Max`**: Sustained/tied notes. `0.8+` for pads, `0.0` for drums.
- **`delaySend` / `reverbSend`**: Per-track sends to the vibe's effects. Leads get moderate sends; pads get generous sends; drums usually stay dry.
- **`noteRangeLow/High`** (optional): Per-track MIDI bounds, overrides genre defaults.
- **`reverbBrightness`** (0-1): Dark (0.3) for deep/brooding, bright (0.7+) for airy/shimmery.
- **`delayFeedback`** (optional): Per-track override of the vibe-level delay feedback.
- **`glideRate`** (0-1): Portamento — smooth pitch transitions. 0 = instant, 0.3 = smooth, 0.6+ = very slow.
- **`evolution`**: `Evolution(rhythmic = ..., pitch = ...)`. Optional Markov drift. `PitchEvolution.Contour` for melodic tracks; `PitchEvolution.Voicing` for chordal tracks.

### `TrackRole.Chordal` sub-tuning (`ChordComping`)

Use when a track should play chord voicings. See `CompLabVibe.kt` for a matrix of families.

- `style`: `PAD`, `FUNK_STABS`, `ROCK_DOWNBEATS`, `SKA_UPSTROKES`, `BLUES_SHUFFLE`, `JAZZ_COMP`, `REGGAE_SKANK`, `GOSPEL_STABS`. Each has a preset 16-step velocity pattern.
- `arpMode`: `AUTO` (CHD engine = native chord, others = arp), `ALWAYS` (always arp), `NEVER` (root only).
- `arpSpeed` (0-1): slow roll (0.1) vs near-simultaneous stab (0.95).
- `arpDirection`: `UP`, `DOWN`, `UP_DOWN`, `RANDOM`.
- `sectionInversion`: `ROOT_POSITION`, `FIRST_INVERSION`, `SECOND_INVERSION`, `OPEN_VOICING`, `FOLLOW_STYLE`.
- `humanization`: `dropProbability`, `ghostProbability`, `octaveJumpProbability`, `extensionProbability`. Each 0-1. Low values (0.1-0.2) for tight grooves, higher (0.3+) for loose/humanized.
- `fills`: `CompingFills(everyNBars, fillType, skipProbability)`. `FillType.ASCENDING_ARP` is currently the only fully-implemented fill.

### `Lick` — the bass riff (optional)

A repeating melodic figure that a track can snap to. Used by tracks whose role is `TrackRole.Melodic(lickMode = LickMode.Fill)` (spans whole bar) or `LickMode.Squash` (compresses to fit). Max 32 steps.

- Each `LickStep`: `scaleDegree` (can be negative — plays below root), `duration` in beats, `velocity` 0-1.
- `loopLength` (in beats): larger than the sum of step durations adds rest padding. Use this for "phrase then space" feels.
- `lickMutation` on the Vibe (0-1): how much the lick drifts on repeats. 0 = static (mechanical/industrial), 1 = wide drift (jazz, improv).
- `lickOctave`: -1 for auto, or explicit 0-8. Use when you want the lick to sit in a specific octave regardless of the track's note range.

### `Band` + `BandMember` + matrices (solos)

The cast of characters for solos. Typically 4 members: Drummer (alwaysActive), Bassist, Keys/Lead, FX. Each member lists which `tracks` it owns.

- `handoffMatrix`: NxN probability of one member passing the lead to another. Build with `bandMatrix(...)` using `row(...)` helpers.
- `pullInMatrix`: probability of a soloist pulling in another member as a duet partner.
- `pullInBars*`, `barsPerLead*`: how long pull-ins and leads last.

A decent default (see `DogHouseVibe`, `ArmyStompVibe`) is 4 members — Drummer/Bassist/Keys/FX — with ~0.2-0.4 handoff weights, lower weights into Drummer (drums rarely take the lead).

### `TensionProfile` (build-and-release arc)

- `innerBars`: primary tension cycle. 4 = tight, 8 = longer phrases, 16 = epic.
- `outerBars`: secondary cycle. 0 = disabled. `16-32` for long arcs.
- `outerDepth`: how much outer modulates inner (0-1).
- `volume`: how much tension affects track volumes.
- `timing`: how much tension affects timing tightness.
- `tonal`: `TonalTension(octaveShift, keyShift, halfLick, chromaticPassing)`.
- `evolution`: `EvolutionTension` — timbre/morph/harmonics drift ranges and probabilities across the tension cycle. `attackPoint` 0-1 is where the peak lives; `releaseSpeed` is how fast it snaps back.
- `spurtChance`: per-bar random burst probability (0 = tension-only).

### `VibeEffects` (dedicated delay + reverb tuning)

- `delayTimeA/B`: two delay taps, 0-1 of a bar. 0.25 = 16th, 0.375 = dotted-8th, 0.5 = half.
- `delayFeedback`: 0.2 = subtle, 0.5 = moderate, 0.7+ = runaway.
- `delayDamping`: high-freq rolloff per repeat (darker with higher).
- `reverbSize`: 0.3 = room, 0.6 = hall, 0.9 = cathedral.
- `reverbDamping`: low-pass on tail.
- `reverbBrightness`: warm (0.3) vs shimmery (0.8).
- `deepFloor`: min DEEP multiplier even when SPACE=0 (0.2-0.4 keeps effects present).

### `Arrangement` (sections)

Optional but recommended — adds a Markov section graph on top of the vibe.

- `sections`: up to 8 `Section`s. Each has:
  - `barsMin/Max`: how long it lives before transitioning.
  - `transitions`: list of `SectionTransition(targetIndex, weight)`. Empty = terminal.
  - `recencyDecay`: penalizes recently-used transitions (0.4-0.6 is healthy).
  - `macroOverrides`: `MacroOverrides(energy, complexity, space, mood)` — **multipliers** (1.0 = no change, 1.4 = 40% boost). Use `null` to leave the default.
  - `soloMode`: `LongFill`, `LickBuilder`, or `Jam`.
  - `compingStyle` / `compingInversion` / `chordFollow`: per-section overrides of CHORDAL/melodic behavior.
- `introIndex`: which section opens; `outroIndex`: which terminates.
- Use the `Arrangement.SIMPLE`, `WITH_SOLOS`, `FULL`, `JAM` presets for quick starts.

A typical 5-section arrangement: intro -> verse/groove -> chorus/peak -> solo -> breakdown -> outro. Use macroOverrides to distinguish (chorus: energy=1.3, complexity=1.3; breakdown: energy=0.4, space=1.5).

## Translation recipes — reference -> parameter choices

When a user gives a musical reference, decompose it along these axes:

### Feel and groove
- **Straight / mechanical / industrial** -> `swingAmount = 0.0`, `timing = 0.05-0.15`, `barStrategy = REPEAT` on drums, `lickMutation <= 0.4`.
- **Shuffled / bluesy** -> `swingAmount = 0.1-0.3`, `rhythmDensity = BACKBEAT`, `progressionStyle = BLUES`.
- **Loose / human / swung** -> `swingAmount = 0.05-0.15`, `ghostProbability = 0.2-0.35`, `humanization` dialed up.
- **Hypnotic / loop-based** -> `progressionAnchor = EVERY_8`, `progressionDriftRange < 0.2`, `barStrategy = REPEAT` on bass.
- **Lots of slides / pedal-steel / sliding guitar** -> `glideRate` 0.35-0.55 on bass and lead melodic tracks. Slides are per-track portamento, not a global effect — set it on every track that should glide.

### Darkness / mood
- **Dark / brooding / heavy** -> `mood <= 0.3`, `scaleType = MINOR|PHRYGIAN`, `progressionStyle = DARK`, `reverbBrightness < 0.4`, `rootNote` = low-register key.
- **Bright / uplifting** -> `mood >= 0.6`, `MAJOR|MIXOLYDIAN`, `POP|ASCENDING`, higher `reverbBrightness`.
- **Exotic / suspended** -> `HIRAJOSHI|IN_SEN|WHOLE_TONE`, `MODAL|DRONE` progression.

### Texture / production
- **Wet / spacious / reverb-heavy** -> `space >= 0.6`, `deep >= 0.6`, `reverbSize >= 0.6`, generous per-track `reverbSend`, high `deepFloor`.
- **Dry / in-your-face** -> `space <= 0.3`, `reverbSize <= 0.35`, `reverbSend` sparse.
- **Distorted / gritty** -> `WSH` engine on bass and lead, higher `harmonics` and `timbre`.
- **Clean / polished** -> `VA`, `PD`, `CHD` engines; moderate harmonics.

### Energy / drums
- **4-on-the-floor kick** -> `RhythmPattern.FOUR_ON_FLOOR`, kick `density ~= 0.5`, `BarStrategy.REPEAT`.
- **Backbeat (rock/hip-hop)** -> `RhythmPattern.BACKBEAT`, snare on 2 and 4.
- **Dense 16ths (DnB/techno)** -> `RhythmPattern.DENSE_16TH`, high hat `density ~= 0.6-0.8`.
- **Minimal / ambient** -> `RhythmPattern.SPARSE`, drums at `density < 0.2`, or replace with modal/particle hits.

### Chord harmony
- If the reference has a clear progression, use `customProgression = listOf(0, 0, 3, 4, ...)` (0-indexed scale degrees I-VII = 0-6).
- If the reference hangs on one chord, use `progressionStyle = DRONE` or `customProgression = listOf(0)`.
- If the reference has jazz substitutions, supply a `chordTransitionMatrix` via `chordMatrix(...)` (see `DeepSpaceVibe`).
- `chordsPerBar = 1` = slow (1 chord per bar), `2` = standard, `4` = busy.

### Lick / riff
- **Repetitive 2-note riff** (garage rock, industrial): 2 steps x several pulses with low `lickMutation`. See the currently-commented `GarageBlitzVibe` in `GarageBlitzVibe.kt`.
- **Walking bass line**: longer lick with varied scale degrees, `loopLength` matches phrase length, moderate `lickMutation`.
- **Static drone with occasional embellishment**: single-note lick with long duration, low velocity on the accents, `chordFollow = FIXED`.

### Doubled-role instrumentation (two drummers, two basses, etc.)

The 8-track layout and 4-member band convention are defaults, not requirements. When the reference calls for two instances of the same role, shape it on two axes: the track engines + settings that differ between them, and the `Band.members` grouping that tells the solo system how they relate.

- **Two drummers (primary kit + tuned percussion layer)** — primary kit on tracks 0-2 (`BD`/`SD`/`HH`, `Percussive`, `REPEAT`/`FILL`/`MUTATE`). Second "drummer" on two of the texture slots (5 and 6) using `MOD` (tuned resonant metal, ringing pitched hits) and `PAR` (particle scatter). Use `BarStrategy.INDEPENDENT` + `density = 0.10-0.15` + `holdProbability = 0.4-0.6` so the hits spread across bars without repeating and the MOD rings sustain past the hit. `TrackRole.Melodic(chordFollow = FIXED)` on the MOD track keeps the ringing tuned to the key without chasing chord changes. Give the second drummer its own 5th band member (see below) if you want it to solo/trade independently from the primary kit.
- **Two basses (hook bass + sub pedal)** — hook bass on track 3 with `WSH` or `VCF`, `TrackRole.Melodic(chordFollow = ROOT_ONLY, lickMode = Fill)`, plays the lick. Sub bass on track 5 with `VCF`/`PD` and `TrackRole.Melodic(chordFollow = FIXED)` + `BarStrategy.REPEAT` + `noteRangeHigh` capped around 40 so it stays deep and locked to the root. Density ~0.5 gives it a steady pulse rather than a sustained drone. Group both tracks under a single "Bassist" band member (`tracks = listOf(3, 5)`) so they move as a unit during handoff.
- **Band members ≠ always 4.** A vibe can define 5+ members when a role deserves its own identity — a 5th "Sub Drummer" member for dual-drummer vibes, a 5th "Lead" separate from "Keys", etc. Remember `bandMatrix` is NxN — add a row and a column when you add a member, and the matrix grows to 5x5. `alwaysActive = true` can apply to more than one member (e.g. both drummers anchor the pocket, neither should duck during solos).

## File template

Copy-paste the imports and class skeleton, then tune. Always start from a working reference file (e.g. `DogHouseVibe.kt`) and edit in place rather than re-typing from scratch. Full list of imports to expect:

```kotlin
package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.*  // or individual imports matching the existing style
import org.balch.orpheus.features.pulsar.bandMatrix
import org.balch.orpheus.features.pulsar.row

/**
 * <evocative name> — one-line feel summary.
 *
 * Longer description of the groove, instrumentation, section structure.
 * Remember: NO trademarked names here — describe the feel, not the source.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class MyNewVibe : VibeProvider {
    override val vibe = Vibe(
        name = "My New Vibe",
        bpm = 120f,
        envelopeType = EnvelopeType.BLEND,
        rootNote = RootNote.A,
        scaleType = ScaleType.MINOR,
        // ... tracks, genre, effects, arrangement ...
    )
}
```

Existing vibes in the `vibes/` directory use the full list of explicit imports (not wildcard). Match that style for consistency — your IDE will auto-fill them when you reference the types.

## Testing a new vibe

1. **Compile check (fast)**: `./gradlew :features:pulsar:compileKotlinJvm`. Must pass. Errors here are almost always a missing import, a mistyped enum value, or an incorrect number of tracks (must be exactly 8).
2. **Full build**: `./gradlew :apps:orpheus:build`. Runs the whole app through compile + tests.
3. **Listen**: Launch the JVM desktop app, pick the new vibe from the Pulsar picker. Verify:
   - All 8 tracks engage where you expect.
   - The bass riff (if using a `Lick`) plays and transposes with the progression.
   - Section transitions happen within the expected bar ranges.
   - The macro knobs (Energy/Complexity/Space/Mood/Deep) feel coherent across their full range.
4. **A/B against `DogHouseVibe`**: switch back and forth. Does the new vibe feel equally tight, equally musical? If it is noticeably less coherent, the problem is usually:
   - Too many tracks trying to be the focus (lower volume + density on secondary tracks).
   - `barStrategy = INDEPENDENT` on too many tracks (nothing locks in).
   - `progressionDriftRange` too high (chord wandering feels aimless).
   - `density` too high on texture tracks (crowds out the groove).
5. **Regression-check on `DogHouseVibe`**: If you ended up changing anything under `PulsarVibe.kt` or further down (you should not have — vibes are data), load `DogHouseVibe` and confirm it still sounds great. This is the benchmark the project holds to.

## When things go wrong

- **"Vibe requires exactly 8 tracks"** runtime error: count your `TrackVoice` entries. Use silent placeholders (`volume = 0.0f, density = 0.0f`) if you do not need all 8.
- **"Row 'X' has N values, expected M"**: your `bandMatrix` or `chordMatrix` row length does not match the number of members/degrees. `bandMatrix` is NxN where N = number of rows; `chordMatrix` is fixed 7x7.
- **"customProgression degrees must be 0..6"**: use 0 (I) through 6 (VII). Do not use negative numbers or values >= 7.
- **Vibe does not show up in the picker**: verify the `@Inject` + `@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())` annotations are both present and the class implements `VibeProvider`. Also rebuild — Metro DI code generation requires a recompile.
- **Bass wanders off-key**: `chordFollow = ROOT_ONLY` on the bass track snaps it to the chord root. Most driving grooves want this.
- **Pads sound random / nothing locks in**: set `barStrategy = REPEAT` or `MUTATE` on at least the rhythm and bass tracks; reserve `INDEPENDENT` for genuine texture layers.
- **Lead does not follow the key**: verify the track's `role` is `TrackRole.Melodic` (not `Percussive`) and that `noteRangeLow/High` overlap with the scale notes.

## Key references

- Schema source-of-truth: `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/PulsarVibe.kt` (KDoc-annotated).
- Gold-standard vibe: `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/DogHouseVibe.kt`.
- Ambient / chordMatrix example: `DeepSpaceVibe.kt`.
- CHORDAL-comping family helper: `CompLabVibe.kt` (uses `generateCompLabVibe(...)`).
- ViewModel that consumes vibes (for reference — no edits needed): `PulsarViewModel.kt` — takes `Set<VibeProvider>` via DI.

## See also

- `references/examples.md` — a worked example translating a real song into a Vibe, showing the full decomposition and the parameter choices that followed.
