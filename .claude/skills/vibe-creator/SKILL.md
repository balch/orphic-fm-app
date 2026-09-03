---
name: vibe-creator
description: Use when creating a new Pulsar Vibe (beat-machine preset) for the Orphic FM app — especially when translating a musical reference like a song, artist, or genre feel into the Vibe schema. Triggers on prompts like "make a vibe based on [song/artist/genre]", "create a new Pulsar preset for X feel", "add a vibe that sounds like Y", or any request to add a *Vibe.kt file under features/pulsar/.../vibes/. Covers the Vibe schema, naming rules (including the no-trademark rule), DI registration, tuning recipes, and benchmark testing against DogHouseVibe.
---

# Vibe Creator

A Vibe is a complete Pulsar preset: 8 tracks, tempo, key, macro defaults, section arrangement, tension arc, band personalities, and effects. Users pick a vibe and tweak the live macros (Energy, Complexity, Space, Mood, Deep). This skill covers how to turn a musical reference into a well-tuned Vibe.

## Where vibes live

- Source: `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/<Name>Vibe.kt`
- Schema (every type you will reference): the `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/models/` package — **one file per type**, not a single `PulsarVibe.kt` (that file does not exist). The top-level `Vibe` data class plus the `RootNote` / `ScaleType` / `EnvelopeType` / `Album` enums live in `models/Vibe.kt`; the rest are siblings (`OrpheusEngine.kt`, `TrackVoice.kt`, `TrackRole.kt`, `GenreProfile.kt`, `ChordComping.kt`, `Lick.kt`, `ChordStep.kt`, `Band.kt`, `TensionProfile.kt`, `VibeEffects.kt`, `Arrangement.kt`, `Anomaly.kt`, `LickAnomaly.kt`, `VoidAnomaly.kt`, `MacroTarget.kt`, `Evolution.kt`, `EnvelopeProfile.kt`, `SoloBehavior.kt`, `VibeProvider.kt`).
- Canonical quality benchmark: `DogHouseVibe.kt` — after any Pulsar change, test this vibe first; a new vibe should feel at least as coherent and musical.

Registration is automatic via Metro DI — each vibe class carries `@Inject` and `@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())`, and `PulsarViewModel` receives `Set<VibeProvider>` in its constructor. **No extra registration file, DI module, or list edit is required** — dropping a correctly-annotated file into the `vibes/` directory is enough.

`VibeProvider` has **two** members, both required: `override val name: String` (a cheap constant for picker sorting) and `override val vibe: Vibe` (the heavy body — declare it `by lazy { Vibe(name = name, ...) }` so selecting the picker, not listing it, builds the tracks). See the file template below.

## Starting from a codegen-imported vibe

If `grab-ai-vibes` already ran `tools:vibe-codegen` on an AI-archived JSON, there's already a
compiling `<Class>Vibe.kt` in this directory with every field spelled out explicitly and a
provenance-header comment instead of prose. Once that vibe earns a keep, start from *that* file
rather than `DogHouseVibe.kt` — your job is polish, not translation: collapse identical
`engineEdm`/`engineSpace` pairs into the `OrpheusEngine(...).let { x -> TrackVoice(engineEdm = x,
engineSpace = x) }` idiom (the generator never does this itself), add the musical-intent KDoc
every hand-authored vibe carries (see the naming rule below — no trademarks), and tune by ear.

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
4. **Compile.** Run `./gradlew :features:pulsar:compileKotlinJvm` (fast — Pulsar only). Fix any type errors. If any section sets a `soloMode`, the vibe needs a `band` — `BandPresets.quartet(...)` is the one-liner (see the `Band` section).
5. **Run it live.** Build the JVM desktop app and load the vibe from the picker. A/B against `DogHouseVibe`.
6. **Tune by ear.** The file should change first, not any wider code. If you find yourself wanting to change Pulsar DSP, stop — the DogHouseVibe benchmark exists to catch regressions, and vibes should only tune what the schema exposes.

## The Vibe schema — parameter-by-parameter

All of this is in the `models/` package (one file per type — see "Where vibes live"). Read the KDoc there for the full range of valid values; this section focuses on **what to set based on a musical reference**. Ranges in parens are the useful-in-practice subset, not the absolute min/max.

### Top-level (the frame)

| Field | Purpose | Decision rule |
|---|---|---|
| `name` | Vibe picker label | Short, evocative, **no trademarks**. Must equal `vibe.name`. |
| `album` | Picker grouping | Optional, default `Album.STEALTH` (also `RIF`, `ZERO_TO_ONE`). |
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
- `customProgression`: Optional `List<ChordStep>` (degree 0..6 + optional per-chord glide 0..1). Use the `chords(0, 3, 5, 6)` helper for the no-glide case, or build the list explicitly with `listOf(ChordStep(0), ChordStep(3, glideRate = 0.4f), ...)` when you want a slide into a specific chord. Overrides the template sequence but keeps the Markov matrix. Great for "hang on tonic then dip" forms, or a literal 12-bar blues. Size 1..12 (`kMaxProgressionLength`; raising it means matched edits in C++ `orpheus_unit_pulsar.h`/`orpheus_engine.h`/routing + the `section_progression_*_${s * N + i}` stride on both sides).
- `chordTransitionMatrix`: Optional 7x7 Markov via `chordMatrix(...)`. Use only when a preset `progressionStyle` does not cover the target motion (see `DeepSpaceVibe` for an example).

### `progressionAnchor` + `progressionDriftRange`

How often the Markov progression resets to its starting state. `EVERY_4` or `EVERY_8` is typical. `progressionDriftRange` (0-1): how much the progression is allowed to wander between resets. `0.1-0.2` = tight/hypnotic, `0.5+` = loose/jazzy.

### `tracks` — exactly 8 `TrackVoice`s

Convention (not enforced): 0=kick, 1=snare, 2=hat, 3=bass, 4=keys/lead, 5-6=texture/FX, 7=wildcard. Any track can use any engine.

A `TrackVoice` is split into **two layers**:
1. **Per-voice character** lives on `OrpheusEngine` — separate instances for `engineEdm` (high energy) and `engineSpace` (low energy). The two slots crossfade based on the Energy macro.
2. **Track-level concerns** (role, mix, pattern generator, evolution) live on `TrackVoice` — they describe the track regardless of which voice is active.

#### Authoring convention — `.let { ... }` + `.copy()`

To avoid duplicating shared knobs across both engine slots, declare the engine once and reuse:

```kotlin
// Same engine on both sides — declare once, reuse
// NB: engine ids are the SHORT codes (BD/SD/HH/WSH/STR/DX/DX2/...), not long names.
OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.85f).let { kick ->
    TrackVoice(
        engineEdm = kick,
        engineSpace = kick,
        role = TrackRole.Percussive,
        density = 0.45f,
        // ...track-level fields
    )
},

// Engines differ in id only — share knobs, .copy() the id
OrpheusEngine(
    engineId = OrpheusEngineId.WSH,
    volume = 0.75f,
    noteRangeLow = 33,
    noteRangeHigh = 52,
).let { bass ->
    TrackVoice(
        engineEdm = bass,
        engineSpace = bass.copy(engineId = OrpheusEngineId.STR),
        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
        // ...
    )
},

// Engines differ in id AND one knob — chain the .copy()
engineSpace = bass.copy(engineId = OrpheusEngineId.STR, harmonics = 0.7f),
```

Use the `let` parameter name to label the track's role (`kick`, `bass`, `keys`, etc.) — it reads better than a generic `engine`. **Do not duplicate `OrpheusEngine(...)` blocks in full** — that's the explicit anti-pattern this convention exists to prevent.

#### `OrpheusEngine` (per-voice character)

- **`engineId`**: The Plaits engine. Required. Common picks:
  - Drums: `BD`, `SD`, `HH`, `NSE`, `PAR`.
  - Bass: `WSH` (gritty), `VCF` (filter-sweep bass), `PD` (warm round), `VA` (analog poly), `DX` (FM bass — see below).
  - Keys / E.piano / chroma percussion: `DX2` (see below — NOT a generic FM lead).
  - Lead: `DX3` (FM brass/strings/pads — see below), `WSH` (distorted), `FM` (2-op), `WTB` (wavetable).
  - Pad: `ENS` (string ensemble), `STR` (string model), `GRN` (granular), `CHD` (chord engine), `ADD` (additive), `DX3` (FM cinematic pads).
  - Texture/FX: `MOD` (modal/metallic), `PAR` (particles), `SPK` (speech), `SWM` (swarm), `NES` (chiptune), `TRN` (wave terrain).
  - **Important**: `DX` / `DX2` / `DX3` share a 6-op FM voice but load different 32-patch sysex banks. `harmonics` is a **patch selector** (quantized to 32 zones), not a tone control. **See `references/fm_patches.md`** for the bank tables before using a DX engine.
- **`volume`** (default `0.8`): Track volume. Start ~0.8 for leads, 0.3-0.5 for texture.
- **`harmonics` / `timbre` / `morph`** (default `0.5`): Plaits engine knobs, meaning varies per engine. **For SixOp FM (`DX`/`DX2`/`DX3`), `harmonics` is a 32-step patch selector — see `references/fm_patches.md`.** Always set explicitly on DX engines.
- **`pinHarmonics` / `pinTimbre` / `pinMorph`** (default `false`): When `true`, the corresponding parameter is used verbatim at render time — bypasses the macro map's range, evolution drift, accent boost, and slow-LFO modulation. Per-engine playability floor still applies. Use this to lock a tone color from vibe code (paste-from-Orpheus workflow). **DX-family (`DX`/`DX2`/`DX3`) auto-pin harmonics** — their `harmonics` is a quantized patch selector and the loader forces `pinHarmonics = true` on them regardless of the field's value. When pinning DX harmonics, set `harmonics = (patchIndex + 0.5f) / (32f * 1.02f)` to land cleanly inside the desired bucket — the `1.02f` factor mirrors the DX engine's internal scaling, and the `+ 0.5f` aims at the bucket's midpoint. **Both terms are required.** The quantizer floors rather than rounds, so `patchIndex / (32f * 1.02f)` is the bucket's *lower edge* and resolves to `patchIndex - 1`; dropping the `1.02f` instead drifts off by one above index 24.
- **`harmonicsModulation`** (default `0.0f`): Optional opt-in escape hatch for *pinned* harmonics. When non-zero, the slow LFO is allowed to walk harmonics by ±this much around the pinned base, giving a bounded patch-walking texture effect that's otherwise locked out by the pin contract. The walk depth is `harmonicsModulation × modLfoDepth × texture_curve`, so the LFO depth still controls the overall amplitude. A useful value for DX-family pads is `0.03f`–`0.10f` (walks within a tonal-family neighborhood); above `0.20f` the patches start drifting unpredictably. See `references/fm_patches.md` for the full discussion.
- **`harmonicsMacroSource` / `harmonicsMacroRange`** (default `MOOD` / `0.0f`): User-knob-driven DX patch walk. **DX-family only.** When `harmonicsMacroRange > 0`, the live macro selected by `harmonicsMacroSource` (default `MOOD`) walks harmonics across `[base − range, base + range]` as the user moves the knob. At the macro's midpoint (`0.5f`) the walk is zero — the pinned base patch plays. This restores the pre-pin feel where a small mood tweak shifted DX voices on the same rhythm. Independent of `harmonicsModulation` (LFO-driven) — they sum. Typical values: `0.05f` ≈ ±2 patches per full knob sweep, `0.10f` ≈ ±3 patches. See `references/fm_patches.md` for the discussion.
- **`modLfoRate` / `modLfoDepth` / `modLfoShape` / `modLfoCoupling`**: Slow-modulation parameters for pad/texture voices. `rate` 0.03-0.1 is glacial; `depth` 0.3-0.7 audible. `depth = 0` (default) disables.
- **`holdProbability` / `holdLengthMin` / `holdLengthMax`**: Sustained/tied notes. `0.8+` for pads, `0` (default) for drums.
- **`delaySend` / `reverbSend`** (default `0.0`): Per-voice sends to the vibe's effects. Leads moderate, pads generous, drums usually dry.
- **`noteRangeLow` / `noteRangeHigh`** (default `0` = use genre default): Per-voice MIDI bounds.
- **`reverbBrightness`** (default `0.5`): Dark (0.3) for deep/brooding, bright (0.7+) for airy/shimmery.
- **`delayFeedback`** (default `null` = use vibe-level): Per-voice override.
- **`glideRate`** (default `0.0`): Portamento. 0 = instant, 0.3 = smooth, 0.6+ = very slow.
- **`lpgMode`** (default `ENGINE_DEFAULT`): Vactrol LPG mode — `BYPASS` (raw), `SUSTAINED` (gate-following), `PLUCK` (asymmetric bloom), or `ENGINE_DEFAULT` (consult per-engine table). Set explicitly per voice when EDM/Space want different envelope behavior (e.g. `PLUCK` for a WSH bass on EDM, `BYPASS` for a STR drone on Space).
- **`lpgDecay` / `lpgColour`** (default `0.5`): Vactrol decay length and HF bleed.

#### `TrackVoice` (track-level)

- **`role`**: `TrackRole.Percussive`, `TrackRole.Melodic(chordFollow, lickMode)`, or `TrackRole.Chordal(comping, chordFollow)`. Wrong role = wrong pattern generator.
- **`pan`**: Stereo position -1.0 to 1.0.
- **`density`**: Probability that a step gets a note, 0-1.
- **`envelopeProfile`**: Per-track envelope shape — `RHYTHM`, `MELODIC`, `EFFECT`, `WILD`, `DRONE`. See `references/envelopes.md` for solo/ducking specifics.
- **`macroMap`**: `TrackMacroMap.RHYTHM`/`MELODIC`/`EFFECT`/`WILD`, or a custom `TrackMacroMap(...)` you build inline. Match to `envelopeProfile` unless you have a reason not to. The macro map is **how the four live knobs reshape per-track parameters at render time** — and on every parameter it covers, the value written on `OrpheusEngine` is *overwritten* unless the matching `pinHarmonics`/`pinTimbre`/`pinMorph` is set. See the next subsection for when to write your own.
- **`barStrategy`**: `REPEAT`, `MUTATE`, `FILL`, `CALL_RESPONSE`, `INDEPENDENT`.
- **`evolutionWeight`** (default `-1` = auto): How much tension-driven evolution affects this track.
- **`soloBehavior`** / **`duckingProfile`**: Optional. See `models/SoloBehavior.kt` KDoc.
- **`evolution`**: `Evolution(rhythmic, pitch)` — optional Markov drift. `PitchEvolution.Contour` for melodic, `PitchEvolution.Voicing` for chordal.

### Custom `TrackMacroMap` — when the presets don't fit

`TrackMacroMap` is a plain `data class` with seven `MacroTarget(min, max)` fields. The four presets (`RHYTHM`/`MELODIC`/`EFFECT`/`WILD`) cover most cases, but you can instantiate your own inline in the `TrackVoice(...)` block when the preset ranges fight your design.

**What the seven fields actually do at render time** (from `orpheus_unit_pulsar.cpp:1631-1641` and `1555`):

| Field | Macro that drives it | Effect on render |
|---|---|---|
| `energyVolume` | Energy | Per-track output gain multiplier. |
| `energyDensity` | Energy | Per-step note-trigger probability. |
| `complexitySwing` | Complexity | Note timing offset (track 0 only — drives the bar swing). |
| `complexityVariation` | Complexity | Step-pattern mutation amount. |
| `spaceDecay` | **Space** (not mood!) | Drives `morph` when `pinMorph = false`. |
| `moodHarmonics` | Mood | Drives `harmonics` when `pinHarmonics = false`. |
| `moodTimbre` | Mood | Drives `timbre` when `pinTimbre = false`. **Also gates auto evolution weight** — see gotchas. |

Interpolation is linear: `value = min + macro × (max − min)`. So `MacroTarget(0.8f, 0.2f)` (min > max) is a valid **inverted** response — the parameter shrinks as the macro grows.

**When to write a custom map (in order of how often you'll reach for it):**

1. **The preset's tonal range is wrong for the engine.** A `MOD` modal voice tuned for dark cathedral bells wants `moodHarmonics = MacroTarget(0.05f, 0.20f)`, not `RHYTHM`'s `0.3-0.6` or `MELODIC`'s `0.3-0.7`. Custom map lets you keep mood-knob expressiveness inside the *right* tonal neighborhood.
2. **You want a parameter locked but still want evolution.** `MacroTarget(0.42f, 0.42f)` collapses the lerp to a constant — equivalent to pinning that knob *for the macro*, but tension-evolution drift (the `EvolutionTension` sweeps in lines 1662-1689) still applies. Pinning kills both.
3. **You want to opt the track out of tension-evolution entirely.** Set `moodTimbre = MacroTarget(0f, 0f)`. The auto-rule on line 1647 (`evo_weight = (mm.mood_timbre.max_value > 0.001f) ? 1.0f : 0.0f`) zeros the evolution weight when the moodTimbre max is ≤ 0.001, so the entire EvolutionTension cycle becomes a no-op for that track. Drone/pad tracks that should never breathe with tension want this.
4. **You want an inverted relationship.** A breakdown-FX track that should *quiet down* as energy rises: `energyVolume = MacroTarget(0.8f, 0.2f)`. (`EFFECT` already attenuates volume but not by inverting.) Same trick for a hat that opens up as space rises but closes down as mood goes bright.
5. **You want a much narrower range than any preset.** A bass track where mood should *only* shift timbre between 0.45 and 0.55 (subtle): `moodTimbre = MacroTarget(0.45f, 0.55f)`. None of the presets are that tight.

**Where to write it:**

Inline at the `TrackVoice` site, or as a `private val` at the top of the vibe file if multiple tracks share it. The map is plain data — it serializes through DI to C++ as 14 floats and there is no runtime cost to a custom map vs a preset.

**Preferred form for small overrides: `.copy(...)` on a preset.** `TrackMacroMap` is a Kotlin `data class`, so you get `copy()` for free. This is the right hammer when you only need to adjust 1-3 fields — you keep the preset's intent for everything else and surface only the deltas:

```kotlin
// Lock harmonics/timbre/morph to the engine's authored tonal color
// while keeping RHYTHM's energy + complexity behavior intact.
TrackVoice(
    engineEdm = kick,
    engineSpace = kick,
    macroMap = TrackMacroMap.RHYTHM.copy(
        moodHarmonics = MacroTarget(0.30f, 0.30f),  // locks harmonics; tension-evo still active
        moodTimbre    = MacroTarget(0.60f, 0.60f),  // locks timbre
        spaceDecay    = MacroTarget(0.70f, 0.70f),  // locks morph
    ),
    // ...
)
```

`SunPilgrimVibe.kt` and `SunCourseVibe.kt` use this form on every track to make the hand-tuned `OrpheusEngine` tonal values actually audible. Note that `moodTimbre.max` is still `> 0.001f` (it's the locked value itself), so the auto-evo-weight rule still enables tension-evolution drift — the harmonics/timbre/morph are locked to the macro but free to be modulated by tension. Setting `moodTimbre = MacroTarget(0f, 0f)` is the only way to *also* opt out of evolution; any non-zero lock keeps evolution on.

**Full-instantiation form** is for tracks that diverge from every preset:

```kotlin
TrackVoice(
    engineEdm = bass,
    engineSpace = bass,
    macroMap = TrackMacroMap(
        energyVolume = MacroTarget(0.6f, 1.0f),
        energyDensity = MacroTarget(0.35f, 0.7f),
        complexitySwing = MacroTarget(0.0f, 0.05f),
        complexityVariation = MacroTarget(0.0f, 0.1f),
        spaceDecay = MacroTarget(0.4f, 0.4f),       // morph locked, but still tension-evolvable
        moodHarmonics = MacroTarget(0.05f, 0.20f),  // dark-bell neighborhood
        moodTimbre = MacroTarget(0.30f, 0.55f),     // narrow mood window
    ),
    // ...
)
```

**Custom map vs. pinning — which to reach for:**

| Goal | Use |
|---|---|
| Lock the rendered value AND lock tension-evolution AND lock LFO walk | `pinTimbre = true` (etc.) |
| Lock the rendered value but keep tension-evolution drift | Custom map with `min == max` |
| Restrict the live-macro range but keep all modulation active | Custom map with narrowed `(min, max)` |
| Disable tension-evolution timbre sweeps only | Custom map with `moodTimbre = MacroTarget(0f, 0f)` |
| Override only on DX harmonics (32-patch selector) | Pin is forced on automatically — set the patch index and forget |

**Gotchas:**

- **`morph` is driven by `spaceDecay`, not by `moodMorph`** — there is no `moodMorph` field. The naming is asymmetric. If you want morph to follow mood, you cannot get there with a custom map; you must pin morph and modulate it some other way (LFO, tension evolution).
- **`complexitySwing` is read only from track 0** (`orpheus_unit_pulsar.cpp:1555`) — setting it on tracks 1-7 has no effect on swing. Other macro fields read per-track normally.
- **`pushMacroMap` pushes 14 floats** even though the inline comment in `PulsarViewModel.kt:1133` says "16". Just a stale comment.
- The auto-evo-weight rule means that any custom map where `moodTimbre.max <= 0.001f` silently disables tension-driven harmonics/timbre/morph drift for the whole track. If you want a track that's tension-still on timbre but tension-active on harmonics, you have to set `evolutionWeight` explicitly on the `TrackVoice` (not `-1`).
- No existing committed vibe writes a custom map (as of this writing) — you'd be first. That's fine, the schema supports it, but it means there is no reference example to crib from yet.

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

- Each `LickStep`: `scaleDegree` (0 = root, 1 = 2nd degree, …), `duration` in beats, `velocity` 0-1. **A negative `scaleDegree` is a REST** for that step's duration — *not* a below-root note (verified in `pulsar_pattern_gen.h`: `if (degree < 0)` skips the slots). The rest is reliable at low `lickMutation`; at high mutation a negative step has a `~mutation × 0.3` chance of filling in with a random note instead. This is how you put silence *between* notes inside a lick (e.g. stop-time: hit, hit, rest, walk-up).
- `loopLength` (in beats): larger than the sum of step durations adds rest padding. Use this for "phrase then space" feels.
- `lickMutation` on the Vibe (0-1): how much the lick drifts on repeats. 0 = static (mechanical/industrial), 1 = wide drift (jazz, improv).
- `lickOctave`: -1 for auto, or explicit 0-8. Use when you want the lick to sit in a specific octave regardless of the track's note range.

### `LickRotation` — rotating between licks (optional)

Instead of one static `lick`, a vibe can hold a pool of licks and rotate between them per section. Set `Vibe.lickRotation = LickRotation(pool)`; while active it overrides the static `lick` (keep `lick` set as a fallback seed/load-time pick). Needs an `Arrangement` — rotation happens at section boundaries; without one the pool falls back to a single load-time pick.

- `pool: List<Lick>` — the rotation members; the engine picks one per section (2–4 works well).
- `MAX_LICK_POOL = 4` caps `pool.size` — plus one more shared slot if the vibe also declares a `LickAnomaly` (see the Anomalies section below): both ride the same C++ lick bank.
- Copyright: if any pooled lick is a recognizable copyrighted riff, keep the vibe dev-only WIP — never LIVE.

`LickRotation` is **pool-only** now. The rare "swap in an original riff" event that used to live here as `anomaly`/`anomalyChance` is now a **`LickAnomaly`** in `Vibe.anomalies` — configured, force-fired, and auto-rolled independently of the rotation pool (see the Anomalies section below).

Working example: `FireSky05Vibe` in `FireSkyVibe.kt` (rotates `aiLick`/`tweakLick` via `lickRotation`, with a rare `ogLick` `LickAnomaly`).

### `Band` + `BandMember` + matrices (solos)

The cast of characters for solos. Typically 4 members: Drummer (alwaysActive), Bassist, Keys/Lead, FX. Each member lists which `tracks` it owns.

**A `soloMode` does nothing without a band.** The engine starts a section solo only when the vibe declares a `Vibe.band`; with no band the solo never starts and the section plays as an ordinary one, silently. `Vibe`'s init now rejects this outright — a vibe whose arrangement has any section with a non-null `soloMode` must set `band`. Six shipped vibes carried a dead `SoloMode.Jam` for months before that require existed.

**Use a preset.** `BandPresets` (`models/BandPresets.kt`) builds a working cast from track indices alone — no hand-written matrices:

| Preset | Cast | Use it for |
| --- | --- | --- |
| `BandPresets.quartet(kit, bass, lead, colour)` | Drummer (alwaysActive) / Bassist / Lead / Colour | The workhorse. Rock, blues, funk — anything with one star voice. `DogHouseVibe`'s shape. |
| `BandPresets.tradingLeads(kit, bass, leadA, leadB)` | Drummer (alwaysActive) / Bassist / Lead A / Lead B | Two front-line voices passing the solo back and forth. `SwampSwaggerVibe`, `VelvetLeashVibe`, `SpaceDroneVibe`'s keys-led ensemble. |
| `BandPresets.twoVoiceTexture(bed, voiceA, voiceB)` | Bed (alwaysActive) / Voice A / Voice B | Sparse ambient. The minimum working band. |

```kotlin
band = BandPresets.quartet(
    kit = listOf(0, 1, 2, 7), bass = listOf(3), lead = listOf(5), colour = listOf(4, 6),
),
```

Hand-write `Band(...)` only when you want bespoke member names or hand-tuned weights.

**Two members is not a band.** The engine refuses to hand the lead to an `alwaysActive` member, so an anchor plus one voice deadlocks and the drums end up "soloing". Every band needs at least two non-anchor members — which is why `twoVoiceTexture` has three.

**Only `Melodic`-ROLE tracks can lead a jam.** A JAM solo renders an improvised melodic LINE, so its lead member must own at least one track whose `role` is `TrackRole.Melodic`. A melodic-sounding *engine* is irrelevant: an organ on a `TrackRole.Chordal` track cannot lead a jam and the engine will pick around it. Check the roles, not the engine ids, when you decide who is in which member. (`LongFill` and `LickBuilder` are not filtered this way — only `Jam`.)

**Do not `density = 0f` a would-be soloist.** `TrackSectionOverride(density = 0f)` is a render MUTE for the whole section and the solo system does not lift it, so zeroing a melodic track inside its own solo section makes that solo inaudible whenever that member wins the lead. Thin it to 0.1-0.3 instead. "The lead steps aside so the solo system owns it" is exactly the case that breaks.

- `handoffMatrix`: NxN probability of one member passing the lead to another. Build with `bandMatrix(...)` using `row(...)` helpers.
- `pullInMatrix`: probability of a soloist pulling in another member as a duet partner.
- `pullInBars*`, `barsPerLead*`: how long pull-ins and leads last.
- Tracks in NO member get the full support duck during a solo, so give every track an owner.

A decent hand-written default (see `DogHouseVibe`, `ArmyStompVibe`) is 4 members — Drummer/Bassist/Keys/FX — with ~0.2-0.4 handoff weights, lower weights into Drummer (drums rarely take the lead).

### `TensionProfile` (build-and-release arc)

- `innerBars`: primary tension cycle. 4 = tight, 8 = longer phrases, 16 = epic.
- `outerBars`: secondary cycle. 0 = disabled. `16-32` for long arcs.
- `outerDepth`: how much outer modulates inner (0-1).
- `volume`: how much tension affects track volumes.
- `timing`: how much tension affects timing tightness.
- `tonal`: `TonalTension(octaveShift, keyShift, halfLick, chromaticPassing)`.
  - `halfLick` is a `HalfLick` enum, not a boolean:
    - `HalfLick.OFF` (default) — the FILL lick plays its full length.
    - `HalfLick.JAM` — loop only the lick's first bar so the opening figure repeats
      while its tone evolves. On release the riff re-locks to bar 1. This is what a
      plain `halfLick = true` used to mean, and it is what you almost always want.
    - `HalfLick.JAM_INVERTED` — same truncation, but on release the riff deliberately
      resumes on bar 2 and stays a bar out of phase with the harmony until the next
      section boundary. Use it when you want a section to open with the riff's answer
      phrase instead of its hook. It reads as a turned-around riff, so it is a strong
      flavor: set it on the section BEFORE the one you want turned around.
    - `HalfLick.JAM_LAST_BAR` — jams the riff's LAST bar (the answer phrase) instead of
      its first, then re-locks to bar 1 on release. Reads as a turnaround into whatever
      follows. Pairs naturally with `JAM` for a lead-in/lead-out sandwich around a solo:
      `JAM` states the hook going in, `JAM_LAST_BAR` states the answer coming out.
  - `JAM` and `JAM_INVERTED` both jam bar 1. Only `JAM_LAST_BAR` jams a different bar —
    it shifts the loop window rather than shortening it.
  - Only affects `LickMode.Fill` licks. `LickMode.Squash` licks are already one bar, so
    every mode is a no-op on them.

**Section budget.** An arrangement may declare up to `Arrangement.MAX_SECTIONS` (12)
sections and `MAX_SECTION_TRANSITIONS` (8) outgoing edges each. Both mirror C++ constants
in `liborpheus_dsp/src/pulsar_limits.h` and are enforced by `PulsarSectionLimitsTest`.
Exceeding sections throws at `Arrangement.<init>`; exceeding edges is clamped silently.
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
  - `barsMin/Max`: how long it lives before transitioning. `barStep` (default 1) snaps the random length to multiples — set 2 for even-bar phrases, 4 for 4-bar increments.
  - `transitions`: list of `SectionTransition(targetIndex, weight, transitionBars)`. Empty = terminal. **`transitionBars`** (default 0 = hard cut) crossfades the macro overrides toward the destination over the *last* N bars of the source section — a per-edge pre-roll ramp. `DogHouseVibe` leans on this (`bluesLiftBars`/`bluesyDropBars`/`bigBluesLiftBars`); name the bar count after the musical role the ramp serves, not the count.
  - `recencyDecay`: penalizes recently-used transitions (0.4-0.6 is healthy).
  - `macroOverrides`: `MacroOverrides(energy, complexity, space, mood)` — **multipliers** (1.0 = no change, 1.4 = 40% boost). Use `null` to leave the default.
  - `soloMode`: `SoloMode.Jam(probability)`, `SoloMode.LickBuilder(probability, mutationRate)`, or `SoloMode.LongFill` — these take constructor params, they aren't bare objects. **Setting any of them requires `Vibe.band`** (see the `Band` section above); without one the engine never starts the solo, and `Vibe`'s init now rejects the combination.
  - `compingStyle` / `compingInversion` / `compingHumanization` / `chordFollow`: per-section overrides applied to **all** CHORDAL/melodic tracks at once.
  - `trackOverrides`: `Map<Int, TrackSectionOverride>` — per-*track* overrides scoped to this section, auto-restored on exit. This is how you pedal one track's hook on the tonic while everything else follows the progression (the octave-fold fix): `trackOverrides = mapOf(4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED))`. `TrackSectionOverride` can also override density/volume/morph/sends/`envelopeProfile`/comping per section. Two carry rules worth knowing:
    - **`density`**: `0` takes the track OUT for the section (clean mute, any role, restored on exit). A *positive* value regenerates the pattern at that density at the boundary, thinning fills and ghosts — but only on tracks whose pattern is generated from density, so on a `Chordal` track or one playing a `LickMode.Fill`/`Squash` figure a positive density does nothing while `0` still mutes. To duck rather than drop, use `volume`.
    - **`morph`**: pins morph for the section so the Space macro's `spaceDecay` cannot overwrite it. On the drum engines (`BD`/`SD`/`HH`) morph is **decay**, so this is how one section gets a long-ringing kick against a tight snare and a washy open hat. See `RustBeltVibe`'s intro.
  - `customProgression` / `chordsPerBar` / `bpmMultiplier`: per-section harmony plus a tempo multiplier (`0.5` = half-time breakdown, `2.0` = double-time burst).
- `introIndex`: which section opens (default 0; `null` = random weighted start). `outroIndex`: which terminates (`null` = loops forever).
- `lengthSeconds` (default `150..240`): the song's auto-end window; both bounds must be in `15..1800`.
- There are no arrangement presets — write the sections out. Copy the shape from a shipped vibe whose form you want; `RustBeltVibe` is a worked eight-section example.

A typical 5-section arrangement: intro -> verse/groove -> chorus/peak -> solo -> breakdown -> outro. Use macroOverrides to distinguish (chorus: energy=1.3, complexity=1.3; breakdown: energy=0.4, space=1.5).

### Section macroOverrides × `harmonicsMacroRange` = automatic per-section DX voices

When a DX-family engine has `harmonicsMacroRange > 0`, the section's `macroOverrides` multiplier on the selected macro feeds directly into the per-section harmonics walk. **This means sections become tonal contexts on DX engines for free — no per-section patch declaration needed.** Example pattern on `BellTollsVibe.kt` track 4 horn:

```kotlin
OrpheusEngine(
    engineId = OrpheusEngineId.DX2,
    harmonics = 0.647f,           // DX2 idx 21 "Bells" — the BASE patch
    harmonicsMacroRange = 0.05f,  // ±~1.5 patches around base
    // harmonicsMacroSource defaults to MOOD
)
```

With this setup and the vibe's mood baseline of 0.7, sections do:
- intro (`mood = 0.9 × 0.7 = 0.63`) — slight darken
- groove (baseline mood = 0.7) — centered near base
- chorus (`mood × 1.25 → ~0.875`) — walks UP → "Tub Bells" / "Gong 2"
- dub (`mood × 1.1`) — small upward shift

Same engine, **different audible patch per section, driven purely by the section's macroOverride**. Pick the base patch as the *centerpoint* of a tonal family (e.g., the bell-y end of DX2, the pad-y end of DX3) and let sections walk you across the neighborhood.

The same pattern works with `COMPLEXITY` source — see `ArmyStompVibe.kt` track 7 mallet, where `breakdown` (complexity × 2.0) drives the marimba up into bright "Vibe 1" / "Glockenspl" territory exactly when the section needs the extra energy.

**Recommended ranges for section-driven voice change:**
- `0.03f` — ±~1 patch, subtle (good for disciplined leads where you want voice variation without losing the lock)
- `0.04f`–`0.05f` — ±~1–2 patches, audibly different per section while staying in a tonal family
- `0.08f`–`0.10f` — ±~3 patches, dramatic voice swings (use sparingly; risks the chorus sounding like a different instrument from the verse)

This pattern only works on `DX`/`DX2`/`DX3` engines (the quantizer is what makes the smooth macro sweep land on discrete voices). On continuous engines it does nothing.

### Anomalies (`Vibe.anomalies` — the Anomaly Engine)

`anomalies: List<Anomaly> = emptyList()` on `Vibe` — a **sealed list** of rare, dramatic events the Anomaly Engine may fire. `Anomaly` is a `sealed interface` (`models/Anomaly.kt`); each concrete subtype carries a kotlinx `"type"` discriminator and auto-fires on its own probability/chance, so anomalies surface on their own, rarely, as a surprise. **At most one instance of each concrete type** (`Vibe.init` enforces this). **Both subtypes require an `arrangement`** — they only arm while a section graph is active, which also gates the manual trigger below.

**Manual trigger** (long-press on the VIBE dropdown): force-fires every anomaly the vibe **declares**, all at once, at the next musical bar. This is declared-only — there is no config-or-default fallback: a vibe with an **empty** `anomalies` list ignores the trigger entirely (no highlight, no counter bump).

#### `VoidAnomaly` — `{"type": "void", ...}`

A rare, dramatic breath: the whole mix eases down to near-silence, holds a suppressed floor for a bar or two — optionally with one "ghost bar" of the full arrangement flickering through — then swells back up, end-aligned to the section boundary on auto-fire. Reverb/delay tails ring out into the quiet (the duck is inside the sequencer, not the reverb returns). Durations are in **musical bars** (16 steps).

- `probability`: chance the void auto-fires at each section entry (0-1). Keep it low (0.02-0.06); ship default `0.04`. Set `0` to leave only the manual trigger. (Higher only for testing.)
- `floorLevel`: mix gain at the bottom of the dip (0-1). `0.05` = near-silent; `0.15-0.3` = a gentler duck.
- `rampDownBars` / `rampUpBars`: musical bars to ease down to the floor and to swell back up (defaults `1.0` / `1.5`).
- `floorBarsMin` / `floorBarsMax`: the near-quiet hold length, drawn per occurrence (defaults `1.0` / `2.0`).
- `ghostIntensity`: `0` = clean silent void; `>0` punches one bar of the full arrangement through the middle of the floor at that gain (`1.0` = full flash, `0.3` = distant echo). Default `0.5`.

Best on ambient / spacey / cinematic vibes where a rare void adds drama; skip it on relentless dance grooves.

#### `LickAnomaly` — `{"type": "lick", "lick": {...}, "chance": 0.02}`

A rare one-statement swap-in of `lick` (e.g. an original riff) over whatever lick is otherwise playing — a "the record remembers" moment. On each ~2-bar statement the engine may swap in `lick` with probability `chance`, then reverts.

- `lick`: the `Lick` to swap in — see the `Lick` section above for the step schema.
- `chance` (0-1, default `0.02`): per-~2-bar-statement swap probability. `0.02` ≈ 1-in-50 (genuinely rare); keep it low so it stays a surprise.

Requires the vibe to have a lick source — either its own `lick` or a `lickRotation` pool (`Vibe.init` enforces this). The anomaly lick rides the **same C++ lick bank as the `LickRotation` pool**, occupying the slot past the pool, so `lickRotation.pool.size + 1` must fit `LickRotation.MAX_LICK_POOL` (4) — also enforced by `Vibe.init`.

#### Authoring shape

```kotlin
Vibe(
    // ...
    lickRotation = LickRotation(pool = listOf(aiLick, tweakLick)),
    anomalies = listOf(
        VoidAnomaly(probability = 0.04f, floorLevel = 0.05f, ghostIntensity = 0.5f),
        LickAnomaly(lick = ogLick, chance = 0.02f),
    ),
)
```

Omit the field, or pass `emptyList()` (the default), for a vibe with no anomalies — the manual gesture then does nothing.

#### Roadmap

The sealed interface is the extension point: future dramatic events (Scratch / Tape / Sweep) join as new `@SerialName` subtypes with their own config fields, no changes needed to `Vibe` or the manual-trigger dispatch.

(Kept in sync with `VibeGuide.kt`'s `STATIC_GUIDE` section 9.)

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
- **Rare void / breakdown (drops to near-silence and swells back)** -> add a `VoidAnomaly(probability = 0.04, floorLevel = 0.05, ghostIntensity = 0.5)` (a ghost of the arrangement flickers through) to `anomalies`; needs an `arrangement`. See the Anomalies section above.
- **Rare original-riff flash / "the record remembers" moment** -> add a `LickAnomaly(lick = ogLick, chance = 0.02)` to `anomalies` alongside a `LickRotation` pool; needs a lick source and an `arrangement`. See the Anomalies section above.

### Energy / drums
- **4-on-the-floor kick** -> `RhythmPattern.FOUR_ON_FLOOR`, kick `density ~= 0.5`, `BarStrategy.REPEAT`.
- **Backbeat (rock/hip-hop)** -> `RhythmPattern.BACKBEAT`, snare on 2 and 4.
- **Dense 16ths (DnB/techno)** -> `RhythmPattern.DENSE_16TH`, high hat `density ~= 0.6-0.8`.
- **Minimal / ambient** -> `RhythmPattern.SPARSE`, drums at `density < 0.2`, or replace with modal/particle hits.

### Chord harmony
- If the reference has a clear progression, use `customProgression = chords(0, 0, 3, 4)` (0-indexed scale degrees I-VII = 0-6).
- If the reference hangs on one chord, use `progressionStyle = DRONE` or `customProgression = chords(0)`.
- For per-chord pedal-steel slides, build the list explicitly: `customProgression = listOf(ChordStep(0), ChordStep(3, glideRate = 0.45f), ChordStep(4))`.
- If the reference has jazz substitutions, supply a `chordTransitionMatrix` via `chordMatrix(...)` (see `DeepSpaceVibe`).
- `chordsPerBar = 1` = slow (1 chord per bar), `2` = standard, `4` = busy.

### Lick / riff
- **Repetitive 2-note riff** (garage rock, industrial): 2 steps x several pulses with low `lickMutation`. See the currently-commented `GarageBlitzVibe` in `GarageBlitzVibe.kt`.
- **Walking bass line**: longer lick with varied scale degrees, `loopLength` matches phrase length, moderate `lickMutation`.
- **Static drone with occasional embellishment**: single-note lick with long duration, low velocity on the accents, `chordFollow = FIXED`.
- **Rotating riff for variety** (keep a repetitive riff from wearing out): a `lickRotation.pool` of 2–4 licks the engine swaps per section; for a rare surprise line, add a `LickAnomaly` to `anomalies` alongside it (see the Anomalies section above). See `FireSky05Vibe`.

### Doubled-role instrumentation (two drummers, two basses, etc.)

The 8-track layout and 4-member band convention are defaults, not requirements. When the reference calls for two instances of the same role, shape it on two axes: the track engines + settings that differ between them, and the `Band.members` grouping that tells the solo system how they relate.

- **Two drummers (primary kit + tuned percussion layer)** — primary kit on tracks 0-2 (`BD`/`SD`/`HH`, `Percussive`, `REPEAT`/`FILL`/`MUTATE`). Second "drummer" on two of the texture slots (5 and 6) using `MOD` (tuned resonant metal, ringing pitched hits) and `PAR` (particle scatter). Use `BarStrategy.INDEPENDENT` + `density = 0.10-0.15` + `holdProbability = 0.4-0.6` so the hits spread across bars without repeating and the MOD rings sustain past the hit. `TrackRole.Melodic(chordFollow = FIXED)` on the MOD track keeps the ringing tuned to the key without chasing chord changes. Give the second drummer its own 5th band member (see below) if you want it to solo/trade independently from the primary kit.
- **Two basses (hook bass + sub pedal)** — hook bass on track 3 with `WSH` or `VCF`, `TrackRole.Melodic(chordFollow = ROOT_ONLY, lickMode = Fill)`, plays the lick. Sub bass on track 5 with `VCF`/`PD` and `TrackRole.Melodic(chordFollow = FIXED)` + `BarStrategy.REPEAT` + `noteRangeHigh` capped around 40 so it stays deep and locked to the root. Density ~0.5 gives it a steady pulse rather than a sustained drone. Group both tracks under a single "Bassist" band member (`tracks = listOf(3, 5)`) so they move as a unit during handoff.
- **Band members ≠ always 4.** A vibe can define 5+ members when a role deserves its own identity — a 5th "Sub Drummer" member for dual-drummer vibes, a 5th "Lead" separate from "Keys", etc. Remember `bandMatrix` is NxN — add a row and a column when you add a member, and the matrix grows to 5x5. `alwaysActive = true` can apply to more than one member (e.g. both drummers anchor the pocket, neither should duck during solos).

## File template

Copy-paste the imports and class skeleton, then tune. Always start from a working reference file (e.g. `DogHouseVibe.kt`) and edit in place rather than re-typing from scratch.

Two things that bite every time:
- **`OrpheusEngineId` lives in `org.balch.orpheus.core.audio`** — NOT the pulsar package. Everything else (the schema types *and* the `bandMatrix` / `row` / `chords` / `chordMatrix` helpers) lives in `org.balch.orpheus.features.pulsar.models`. There is no `org.balch.orpheus.features.pulsar.*` package for these — that import will not resolve.
- **`VibeProvider` has two members: `name` and `vibe`.** Declare `name` as a cheap constant and `vibe` `by lazy` so picker sorting never builds the heavy body, and pass `name = name` so the two stay in sync.

```kotlin
package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId          // engines: core.audio, NOT pulsar
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Vibe          // schema types: ...pulsar.models
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
// ...one import per other type/enum you reference (BarStrategy, ChordFollow, Band,
//    BandMember, Section, SectionTransition, MacroOverrides, TensionProfile, VibeEffects,
//    Arrangement, RhythmPattern, ProgressionStyle, ProgressionAnchor, ...)
import org.balch.orpheus.features.pulsar.models.bandMatrix    // helpers: also ...pulsar.models
import org.balch.orpheus.features.pulsar.models.row
import org.balch.orpheus.features.pulsar.models.chords

/**
 * <evocative name> — one-line feel summary.
 *
 * Longer description of the groove, instrumentation, section structure.
 * Remember: NO trademarked names here — describe the feel, not the source.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class MyNewVibe : VibeProvider {
    override val name: String = "My New Vibe"

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            // album = Album.STEALTH,         // optional; default STEALTH (also RIF / ZERO_TO_ONE)
            bpm = 120f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.A,
            scaleType = ScaleType.MINOR,
            // ... tracks (exactly 8), genre, band, effects, tension, arrangement ...
        )
    }
}
```

Existing vibes use the full list of explicit imports (no wildcard) — match that style; your IDE auto-fills them as you reference the types.

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
5. **Regression-check on `DogHouseVibe`**: If you ended up changing anything under `models/` or further down (you should not have — vibes are data), load `DogHouseVibe` and confirm it still sounds great. This is the benchmark the project holds to.

## When things go wrong

- **"Vibe requires exactly 8 tracks"** runtime error: count your `TrackVoice` entries. For silent placeholders, set `density = 0.0f` on the `TrackVoice` and `volume = 0.0f` inside both `OrpheusEngine` slots.
- **"Row 'X' has N values, expected M"**: your `bandMatrix` or `chordMatrix` row length does not match the number of members/degrees. `bandMatrix` is NxN where N = number of rows; `chordMatrix` is fixed 7x7.
- **"section(s) [...] declare a soloMode but Vibe.band is null"**: set `band` (start with `BandPresets.quartet(...)`) or drop the `soloMode` from the named sections. A solo is the band passing a lead around; with no band there is nobody to pass it to.
- **The solo section sounds like every other section**: the vibe has a band, but the only members that could lead are `alwaysActive`, or their tracks are all `Chordal`/`Percussive` (a `Jam` lead must own a `Melodic`-role track), or the section `density = 0f`s the soloist. See the `Band` section.
- **"customProgression degrees must be 0..6"**: use 0 (I) through 6 (VII). Do not use negative numbers or values >= 7.
- **Vibe does not show up in the picker**: verify the `@Inject` + `@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())` annotations are both present and the class implements `VibeProvider`. Also rebuild — Metro DI code generation requires a recompile.
- **Bass wanders off-key**: `chordFollow = ROOT_ONLY` on the bass track snaps it to the chord root. Most driving grooves want this.
- **Pads sound random / nothing locks in**: set `barStrategy = REPEAT` or `MUTATE` on at least the rhythm and bass tracks; reserve `INDEPENDENT` for genuine texture layers.
- **Lead does not follow the key**: verify the track's `role` is `TrackRole.Melodic` (not `Percussive`) and that `noteRangeLow/High` overlap with the scale notes.

## Key references

- Schema source-of-truth: the `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/models/` package — one KDoc-annotated file per type (`Vibe.kt`, `OrpheusEngine.kt`, `GenreProfile.kt`, `ChordComping.kt`, `Arrangement.kt`, …). There is no single `PulsarVibe.kt`.
- Gold-standard vibe: `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/DogHouseVibe.kt`.
- Ambient / chordMatrix example: `DeepSpaceVibe.kt`.
- CHORDAL-comping family helper: `CompLabVibe.kt` (uses `generateCompLabVibe(...)`).
- ViewModel that consumes vibes (for reference — no edits needed): `PulsarViewModel.kt` — takes `Set<VibeProvider>` via DI.
- **`references/fm_patches.md`** — full SixOp FM patch banks for `DX`/`DX2`/`DX3`. Read before setting `harmonics` on any of these engines. Includes a per-bank index→patch-name table and the harmonics-zone math.
- **`references/envelopes.md`** — `EnvelopeType` (vibe-global) and `EnvelopeProfile` (per-track) reference. Includes solo and ducking behavior per profile and the AD/TIDES/BLEND crossover math.

## See also

- `references/examples.md` — a worked example translating a real song into a Vibe, showing the full decomposition and the parameter choices that followed.
