---
name: vibe-creator
description: Use when creating a new Pulsar Vibe (beat-machine preset) for the Orphic FM app — especially when translating a musical reference like a song, artist, or genre feel into the Vibe schema. Triggers on prompts like "make a vibe based on [song/artist/genre]", "create a new Pulsar preset for X feel", "add a vibe that sounds like Y", or any request to add a *Vibe.kt file under features/pulsar/.../vibes/. Covers the Kotlin workflow for shipped vibes (cast a skeleton, never copy a vibe), naming rules (including the no-trademark rule), DI registration, Kotlin authoring idioms, testing, and the measure harness. The composition itself comes from the shared vibe guide.
---

# Vibe Creator

A Vibe is a complete Pulsar preset: 8 tracks, tempo, key, macro defaults, section arrangement, tension arc, band personalities, and effects. Users pick a vibe and tweak the live macros (Energy, Complexity, Space, Mood, Deep). This skill is the Kotlin workflow for a shipped `*Vibe.kt`.

## Composition comes from the shared guide

How to compose a vibe (casting, the music-theory rules, drone beds, expressive voices, what every field does, feel-to-settings recipes, mixing targets) lives in one place, shared with the in-app agent and with Claude Code over MCP:

- `features/ai/src/commonMain/composeResources/files/vibe_guide.md`, or
- `pulsar_vibe_guide` over MCP (the same text with its `{{NAME}}` examples filled in from `VibeGuideExamples.kt`, plus a catalog of shipped vibes for ideas only).

Read it before composing. This skill does not repeat it. The guide speaks JSON; the Kotlin names are the same fields (see "Kotlin authoring" below for the few spellings that differ). Its section B rules (one register per sustained voice, the bass holds the root, consonant pads, one arpeggiator, EDM/SPACE pairs, comping that breathes) apply to Kotlin vibes too.

## No copying: every new vibe starts from a cast

Never copy another `*Vibe.kt`, or edit one into a new vibe. Shipped vibes are for ideas only ("how does Orphic Lore build its singer?"). A new vibe starts from a cast skeleton with a stated seed:

- **Over MCP (the AI desktop app running, see the `orphic-mcp` skill):** call `pulsar_cast_vibe` with `mode` (`BED_FIXED` or `ALL_ROLLED`), `styleTags`, `pins` and, to rebuild a known roll, `seed`. It returns the `seed`, a one-line `summary` per track and the skeleton as `vibeJson`. Compose on it per the guide, set `name`, and `pulsar_apply_vibe` to hear it. `mix_capture` archives it in `~/.config/orpheus-dj/ai-vibes/`.
- **Without the app:** call `VibeCaster.cast(CastRequest(mode = CastMode.BED_FIXED, seed = 1234L, styleTags = setOf("reggae")))` from a scratch test in `features/ai` (`composer/VibeCasterTest.kt` shows the call), and write `vibeApplyJson.encodeToString(result.vibe)` to a file. The same request always rolls the same skeleton.
- **Into Kotlin:** `.claude/skills/grab-ai-vibes/scripts/import_vibe.sh <vibe.json>` turns the JSON into a compiling `<Class>Vibe.kt` and adds its `VibeNames` constant and a WIP `VibeCatalog` entry (see `grab-ai-vibes`). Set `name` in the JSON first: the cast's placeholder is "Untitled cast".
- **State the seed:** the skeleton's `cast` block (`CastProvenance`: seed, mode, style tags, pins) records the roll, and the codegen writes it into the Kotlin. Keep it, and name the seed in the KDoc too, so the skeleton can be rebuilt.

## Where vibes live

- Source: `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/<Name>Vibe.kt`
- Schema (every type you will reference): the `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/models/` package — **one file per type**, not a single `PulsarVibe.kt` (that file does not exist). The top-level `Vibe` data class plus the `RootNote` / `ScaleType` / `EnvelopeType` / `Album` enums live in `models/Vibe.kt`; the rest are siblings (`OrpheusEngine.kt`, `TrackVoice.kt`, `TrackRole.kt`, `GenreProfile.kt`, `ChordComping.kt`, `Lick.kt`, `ChordStep.kt`, `Band.kt`, `TensionProfile.kt`, `VibeEffects.kt`, `Arrangement.kt`, `Anomaly.kt`, `LickAnomaly.kt`, `VoidAnomaly.kt`, `MacroTarget.kt`, `Evolution.kt`, `EnvelopeProfile.kt`, `SoloBehavior.kt`, `VibeProvider.kt`).
- Quality benchmark: `DogHouseVibe.kt`. After any Pulsar change, listen to this vibe first; a new vibe should feel at least as coherent and musical. It is a listening benchmark, not a template.

DI registration is automatic via Metro — each vibe class carries `@Inject` and `@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())`, and `PulsarViewModel` receives `Set<VibeProvider>` in its constructor. No DI module edit is needed, but the apps show a vibe only once it has a name and a catalog entry in `vibes/`:

- `VibeNames.kt`: its name, `val MY_NEW_VIBE = VibeName("My New Vibe")`, added alphabetically by constant. The provider and both catalogs reference this constant, so the name is typed once.
- `VibeCatalog.kt`: `VibeNames.MY_NEW_VIBE to CatalogEntry(...)`, its tier (`WIP` while tuning, `LIVE` once it passes its ear test). Position = picker order.
- `AlbumCatalog.kt`: only for RIF, 0-2-1 or Anomalies. STEALTH takes every cataloged vibe no other album lists, so a vibe that belongs there needs no line. Otherwise add `VibeNames.MY_NEW_VIBE` to that album's list where it should play. Album order and track order are authored there, never on the `Vibe`. `AlbumCatalogTest` fails on a listed name that is not a `VibeCatalog` entry.

Do not set `album` on the `Vibe`. The apps ignore it (the field exists only so older JSON decodes), and `AlbumCatalogTest` fails on a vibe that sets a non-default one.

`VibeProvider` has **two** members, both required: `override val name: VibeName` (a cheap constant for picker sorting, written `VibeNames.MY_NEW_VIBE`) and `override val vibe: Vibe` (the heavy body — declare it `by lazy { Vibe(name = name.value, ...) }` so selecting the picker, not listing it, builds the tracks). See the file template below.

## Starting from a codegen-imported vibe

`import_vibe.sh` runs `tools:vibe-codegen`, so the cast (or an AI-archived JSON that `grab-ai-vibes` fetched) arrives as a compiling `<Class>Vibe.kt` in this directory, with every field spelled out explicitly and a provenance-header comment instead of prose. Your job from there is polish, not translation: collapse identical `engineEdm`/`engineSpace` pairs into the `OrpheusEngine(...).let { x -> TrackVoice(engineEdm = x, engineSpace = x) }` idiom (the generator never does this itself), delete the generated `album =` line (the album comes from `AlbumCatalog.kt`; STEALTH needs no line there), add the musical-intent KDoc every hand-authored vibe carries (see the naming rule below — no trademarks — and state the cast seed), and tune by ear.

## Naming rule — read this first

This project has a hard rule: **never use trademarked artist, band, album, or song names in user-facing code or comments.** The `name` field in `Vibe(...)` is user-facing (shows up in the Pulsar vibe picker), as are KDoc block comments at the top of the file.

- Pick an **evocative original name** that captures the *feel*, not a name that identifies the source. "Closer" -> "Sunken Place". "Echoes" -> "Deep Space". "You Really Got Me" -> "Sixties Rebel".
- In the KDoc at the top of the class, describe the feel (industrial, brooding, 4-on-floor, detuned bass, etc.) but do **not** name the source track, band, or album.
- Internal private notes or commit messages can mention the source if it helps — just not the committed source file.
- Check the existing vibes list before picking a name to avoid duplicates.

### Carve-out: public-domain classical works

Public-domain classical repertoire is **exempt** from the rule above. Composer names, work titles, catalogue numbers, and movement names may all appear in user-facing `name` fields and in KDoc. "Symphony No. 5", "Beethoven", and "BWV 565" are historical facts rather than trademarks, and for a vibe built on the classical canon, naming the work *is* the point. Do not invent an evocative substitute for these.

The exemption is narrow. Both conditions must hold:

1. **The composer died more than 70 years ago and the work was published before 1930.** That clears the EU life-plus-70 term and the US published-works term together, without needing a per-territory analysis. Bach, Beethoven, Mozart, Debussy, Ravel, Holst, and Gershwin all qualify. Shostakovich, Stravinsky, Britten, and anything by a living composer do not.
2. **You are naming the composition, not a performance of it.** Specific recordings, performers, conductors, ensembles, record labels, and published editions stay off limits. "Symphony No. 5" is fine. "Karajan's Fifth" and "the Bärenreiter edition" are not.

Outside those two conditions nothing changes. A vibe that references a modern band, song, or album still needs an evocative original name.

## Workflow

1. **Read the guide** (`vibe_guide.md` or `pulsar_vibe_guide`) and decompose the reference along it: the frame (tempo, key, mode), the groove, the harmony, the voices, the sections and the effects.
2. **Cast a skeleton** (see "No copying" above). Pick the mode and style tags from the reference, pin anything it names, and note the seed.
3. **Compose on the skeleton** per the guide. If the AI app is running, `pulsar_apply_vibe` lets you hear the JSON before it becomes Kotlin, and the guide's mix pass applies.
4. **Bring it into Kotlin** with `import_vibe.sh`, then polish (see "Starting from a codegen-imported vibe"). The script adds the `VibeNames` constant and a `WIP` `VibeCatalog` entry. It lands on STEALTH; add it to `AlbumCatalog.kt`, at its track position, only if it belongs on RIF, 0-2-1 or Anomalies.
5. **Compile.** Run `./gradlew :features:pulsar:compileKotlinJvm` (fast — Pulsar only). Fix any type errors. If any section sets a `soloMode`, the vibe needs a `band` — `BandPresets.quartet(...)` is the one-liner (see "Kotlin authoring").
6. **Run it live.** Build the JVM desktop app and load the vibe from the picker. A/B against `DogHouseVibe`.
7. **Tune by ear.** The file should change first, not any wider code. If you find yourself wanting to change Pulsar DSP, stop — the DogHouseVibe benchmark exists to catch regressions, and vibes should only tune what the schema exposes.
8. **Measure the mix, then level-match.** Balance the tracks with the headless harness, then set `levelDb` so the vibe plays at -16 LUFS like every other live vibe. See "Mixing and level matching" under Testing.

## Kotlin authoring

The guide covers what every field does. This section covers how to write it in Kotlin. Read the KDoc in `models/` for the full range of valid values.

### JSON to Kotlin

| Guide (JSON) | Kotlin |
|---|---|
| `"name"` | `name = name.value`, so it equals the provider's `VibeNames` constant. |
| `"album"` | Not set (see "Where vibes live"). |
| `"levelDb"` | Set last, from the harness (see "Mixing and level matching"). Never use it to balance tracks. |
| `"rhythmDensity": 0.33` | `rhythmDensity = RhythmPattern.FOUR_ON_FLOOR.density` (`SPARSE`, `FOUR_ON_FLOOR`, `BACKBEAT`, `DENSE_16TH`). |
| `"customProgression": [0, 3, 5, 6]` | `customProgression = chords(0, 3, 5, 6)`. With a per-chord glide: `listOf(ChordStep(0), ChordStep(3, glideRate = 0.4f))`. |
| `"chordTransitionMatrix"` (49 weights) | `chordMatrix(...)` (see `DeepSpaceVibe`). |
| `"compingStyle": {"type": "...FUNK_STABS"}` | `CompingStyle.FUNK_STABS`. |
| `"macroMap"` | `TrackMacroMap.RHYTHM` / `MELODIC` / `EFFECT` / `WILD`, a `.copy(...)` of one, or a full `TrackMacroMap(...)` (below). |
| `"band"` | `BandPresets.quartet(...)` and friends, or `Band(...)` with `bandMatrix(row(...), ...)` (below). |
| `"anomalies"` | `VoidAnomaly(...)`, `LickAnomaly(...)` (below). |

`customProgression` holds 1..12 steps (`kMaxProgressionLength`); raising it means matched edits in C++ `orpheus_unit_pulsar.h`/`orpheus_engine.h`/routing + the `section_progression_*_${s * N + i}` stride on both sides.

### `.let { ... }` + `.copy()`

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

Use the `let` parameter name to label the track's role (`kick`, `bass`, `keys`, etc.) — it reads better than a generic `engine`. **Do not duplicate `OrpheusEngine(...)` blocks in full** — that's the explicit anti-pattern this convention exists to prevent. The guide's section B still applies: the two slots must differ (another engine of the part, or one knob pinned at least 0.2 away).

### DX patches

`DX` / `DX2` / `DX3` share a 6-op FM voice but load different 32-patch sysex banks, and `harmonics` is a **patch selector** (quantized to 32 zones), not a tone control. **See `references/fm_patches.md`** for the bank tables before using a DX engine. The loader forces `pinHarmonics = true` on them. To land a patch, set `harmonics = (patchIndex + 0.5f) / (32f * 1.02f)`. **Both terms are required.** The `1.02f` mirrors the engine's internal scaling and the `+ 0.5f` aims at the bucket's midpoint; the quantizer floors rather than rounds, so `patchIndex / (32f * 1.02f)` is the bucket's *lower edge* and resolves to `patchIndex - 1`, and dropping the `1.02f` drifts off by one above index 24. `references/fm_patches.md` also discusses `harmonicsModulation` and `harmonicsMacroRange` per bank. Section-driven patch walks are in the guide's section 13 (worked examples: `BellTollsVibe.kt` track 4 on MOOD, `ArmyStompVibe.kt` track 7 on `COMPLEXITY`).

### Custom `TrackMacroMap`

The guide's sections 5 and 13 cover what the seven fields do, inverted ranges, locking with `min == max`, and pin-or-window. In Kotlin, `TrackMacroMap` is a plain `data class` of seven `MacroTarget(min, max)` fields. Write it inline at the `TrackVoice` site, or as a `private val` at the top of the vibe file if multiple tracks share it. It serializes to C++ as 14 floats, and a custom map costs nothing at runtime over a preset.

**Preferred form for small overrides: `.copy(...)` on a preset.** This is the right hammer when you only need to adjust 1-3 fields — you keep the preset's intent for everything else and surface only the deltas:

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

`SpaceDroneVibe.kt` uses this form to make the hand-tuned `OrpheusEngine` tonal values actually audible.

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

The auto-evolution rule lives in `orpheus_unit_pulsar.cpp`: `evo_weight = (mm.mood_timbre.max_value > 0.001f) ? 1.0f : 0.0f`.

### `Band` presets

The guide's section 7 covers how a band works and its three traps. In Kotlin, `BandPresets` (`models/BandPresets.kt`) builds a working cast from track indices alone — no hand-written matrices:

| Preset | Cast | Use it for |
| --- | --- | --- |
| `BandPresets.quartet(kit, bass, lead, colour)` | Drummer (alwaysActive) / Bassist / Lead / Colour | The workhorse. Rock, blues, funk — anything with one star voice. `DogHouseVibe`'s shape. |
| `BandPresets.tradingLeads(kit, bass, leadA, leadB)` | Drummer (alwaysActive) / Bassist / Lead A / Lead B | Two front-line voices passing the solo back and forth. `VelvetLeashVibe`, `SpaceDroneVibe`'s keys-led ensemble. |
| `BandPresets.twoVoiceTexture(bed, voiceA, voiceB)` | Bed (alwaysActive) / Voice A / Voice B | Sparse ambient. The minimum working band. |

```kotlin
band = BandPresets.quartet(
    kit = listOf(0, 1, 2, 7), bass = listOf(3), lead = listOf(5), colour = listOf(4, 6),
),
```

Hand-write `Band(...)` only when you want bespoke member names or hand-tuned weights, with `bandMatrix(...)` built from `row(...)` helpers. The guide's `MINIMUM_BAND` example mirrors `BandPresets.quartet(kit = [0,1,2,7], bass = [3], lead = [5], colour = [4,6])`, and `VibeGuideExamplesTest` keeps the two equal.

### Anomalies

The guide's section 9 covers what `VoidAnomaly` and `LickAnomaly` do. In Kotlin, `anomalies: List<Anomaly>` is a sealed list (`models/Anomaly.kt`); each subtype carries a kotlinx `"type"` discriminator. `Vibe.init` enforces at most one instance of each type, a lick source for a `LickAnomaly`, and `lickRotation.pool.size + 1 <= LickRotation.MAX_LICK_POOL` (8).

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

Omit the field, or pass `emptyList()` (the default), for a vibe with no anomalies. Working example: `FireSky05Vibe` in `FireSkyVibe.kt` (rotates `aiLick`/`tweakLick`, with a rare `ogLick` `LickAnomaly`).

To hear one while testing: a long-press on the VIBE dropdown force-fires every anomaly the vibe **declares**, all at once, at the next bar. A vibe with an empty `anomalies` list ignores the gesture.

The sealed interface is the extension point: future dramatic events (Scratch / Tape / Sweep) join as new `@SerialName` subtypes with their own config fields, no changes needed to `Vibe` or the manual-trigger dispatch.

### Limits and checks in code

- **Sections:** up to `Arrangement.MAX_SECTIONS` (12) sections and `MAX_SECTION_TRANSITIONS` (8) outgoing edges each. Both mirror C++ constants in `liborpheus_dsp/src/pulsar_limits.h` and are enforced by `PulsarSectionLimitsTest`. Exceeding sections throws at `Arrangement.<init>`; exceeding edges is clamped silently.
- **Licks:** up to `Lick.MAX_LICK_STEPS` (64, mirroring C++ `kMaxLickSteps`) steps. A negative `scaleDegree` is a rest (`if (degree < 0)` in `pulsar_pattern_gen.h`).
- **Solos:** a section with a `soloMode` needs `Vibe.band`; `Vibe.init` rejects the combination.
- **Copyright:** if a lick (or a pooled one) is a recognizable copyrighted riff, keep the vibe dev-only `WIP`, never `LIVE`.
- **MST morph:** `DeadStaticToneAudit` fails a clean vibe whose MST track writes `morph` without `pinMorph = true`.
- **MST reach:** MST is Pulsar-only. Panel voices and score parts cannot play it (they would play a hi-hat), and the drum slot pickers leave it out.
- **OSC limits:** `kOscModRange` in `liborpheus_dsp/src/pulsar_osc.h` (the guide's Reference part states them).

### Engine notes the guide does not carry

- **VOWEL_FOF knobs:** `orpheus_unit_braids.cpp` sends `harmonics` to the oscillator's first parameter and `morph` to its second, and the eurorack `RenderVowelFof` (table `formant_f_data[voice][vowel]`) reads the first as the vowel (0 a, .25 e, .5 i, .75 o, 1 u) and the second as the voice type (bass up to soprano). A `harmonicsRise` sweeps the vowel; keep it to one step or it passes through the vowels between. The guide, the `morphRise` KDoc and the cast's singer recipe follow this: the cast gives a VOWEL_FOF singer `harmonicsRise` 0.15 and an SPK singer `morphRise` 0.08, since morph is the vowel on SPK only.
- **SPK:** `harmonics` crossfades speech models (0 formant synth, 0.17 SAM, 0.33 LPC) and the level drops about 4x between 0.25 and 0.21.

### Dropped: a second bass below the bass

An earlier version of this skill described a sub-bass pedal on track 5 (`noteRangeHigh` about 40, `chordFollow = FIXED`) under the hook bass. The guide's rule B makes the bass track the only voice below 48, so that recipe is retired. A vibe that already does it keeps working.

## File template

`import_vibe.sh` writes the file for you. Use this template to check imports, or to finish a file by hand.

Two things that bite every time:
- **`OrpheusEngineId` lives in `org.balch.orpheus.core.audio`** — NOT the pulsar package. Everything else (the schema types *and* the `bandMatrix` / `row` / `chords` / `chordMatrix` helpers) lives in `org.balch.orpheus.features.pulsar.models`. There is no `org.balch.orpheus.features.pulsar.*` package for these — that import will not resolve.
- **`VibeProvider` has two members: `name` and `vibe`.** Declare `name` as a cheap `VibeNames` constant and `vibe` `by lazy` so picker sorting never builds the heavy body, and pass `name = name.value` so the two stay in sync.

```kotlin
package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId          // engines: core.audio, NOT pulsar
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Vibe          // schema types: ...pulsar.models
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.VibeName
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
 * Cast: BED_FIXED, seed 1234, tags reggae + psych.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class MyNewVibe : VibeProvider {
    override val name: VibeName = VibeNames.MY_NEW_VIBE

    override val vibe: Vibe by lazy {
        Vibe(
            name = name.value,
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

## Mixing

The guide's section 11 has the mix targets (kick-bass gap, low end, pan, loudness, knee, the mid-energy notch, EDM/SPACE slot levels, volume cap). This section is the Kotlin side: the gain path and the harness.

The gain path: engine trim, then `volume` x `energyVolume` x notch, then the TRACKS mixer, then pan, then the DJ bus (pre-clipper drive 1.65, `clipDrive`), a soft clip, and a 35 Hz output HPF. Vibes are levelled to about -16 LUFS at master 1.0. Peaks run near -4.5 dBFS.

Harness-only targets from `scripts/mix/measure.sh` (bounds taken from the live vibes in `summary.md` on 2026-10-20; most meet them, a few do not):
- Pre-limit peak at or under +4 dBFS (live vibes sit between -0.3 and +6.6).
- Under about 3% of samples past the knee (live vibes sit between 0% and 5%).

Run `scripts/mix/measure.sh "<vibe name>"` before and after any level change. It re-dumps the vibe, so skip `NO_DUMP=1` after an edit. Results go to `liborpheus_dsp/test/output/vibe_measure/`.

To mix by ear, launch the Mix Bench: `./gradlew :apps:djapp:desktopApp:run -Pbench=true` (URL in `build/mix-bench/url.txt`). It writes capture files; the `apply-mix-capture` skill puts them into the vibe source. Over MCP, the `orphic-mcp` skill's mixing workflow does the same.

## Testing a new vibe

1. **Compile check (fast)**: `./gradlew :features:pulsar:compileKotlinJvm`. Must pass. Errors here are almost always a missing import, a mistyped enum value, or an incorrect number of tracks (must be exactly 8).
2. **Full build**: `./gradlew :apps:orpheus:desktopApp:build`. Runs the whole app through compile + tests.
3. **Listen**: Launch the JVM desktop app, pick the new vibe from the Pulsar picker. Verify:
   - All 8 tracks engage where you expect.
   - The bass riff (if using a `Lick`) plays and transposes with the progression.
   - Section transitions happen within the expected bar ranges.
   - The macro knobs (Energy/Complexity/Space/Mood/Deep) feel coherent across their full range.
4. **A/B against `DogHouseVibe`**: switch back and forth. Does the new vibe feel equally tight, equally musical? If it is noticeably less coherent, see the guide's "When a vibe does not hang together" (section 13).
5. **Regression-check on `DogHouseVibe`**: If you ended up changing anything under `models/` or further down (you should not have — vibes are data), load `DogHouseVibe` and confirm it still sounds great. This is the benchmark the project holds to.
6. **Mix and level-match** (below).

### Mixing and level matching

`scripts/mix/measure.sh "<Vibe Name>"` dumps the vibe's port writes from Kotlin, replays 240 s through the C++ DJ graph and writes `liborpheus_dsp/test/output/vibe_measure/summary.md` (LUFS, clipper load, per-track LU against the dry mix, per section). Use the exact display name ("Lost In Space", not "Lost in Space"): a wrong name fails the dump, and a redirected run hides that. `NO_DUMP=1` reuses the dump when no vibe file changed; `VIBE_TRIM=3:-2.6` tries a per-track dB move without editing (capped at +3.5 dB).

Mix first, against the guide's section 11 targets. What the harness adds:
- A non-bass voice with no range shows up as rumble: Velvet Leash's tambourine read 99.6% below 40 Hz until it got 72-96.
- To compare the EDM and SPACE slots (Fire Sky's riff measured 9 dB apart), read a track's `rms_dbfs` in a high-energy section against a low-energy one in the JSON, and set the two slot volumes so it doesn't jump.
- Sends are post-fader and the harness meters dry tracks, so reverb/delay changes don't show in the numbers. Check those by ear.
- The summary's pan table catches a lopsided or mono mix (all-zero pans read as correlation ~1.0). The harness JSON's per-section track `pan` shows the path of a `TrackSectionOverride.pan` glide (Orphic Lore's Sirens and Hammond, mirrored, the Sirens centred in their vamp).

Then level-match: with the balance settled, add `-16 - LUFS` to the vibe's current `levelDb` (rounded to 0.1), re-measure, and nudge it until the vibe reads -16 ±0.5 LUFS (the harness plays the master at 1.0, as the DJ app does). Boosts land short (the clipper compresses them), so overshoot a boost by about a quarter. `levelDb` scales every track and the storm, rain, street and speech voices, sends included, so it changes loudness, not balance.

## When things go wrong

- **"Vibe requires exactly 8 tracks"** runtime error: count your `TrackVoice` entries. For silent placeholders, set `density = 0.0f` on the `TrackVoice` and `volume = 0.0f` inside both `OrpheusEngine` slots.
- **"Row 'X' has N values, expected M"**: your `bandMatrix` or `chordMatrix` row length does not match the number of members/degrees. `bandMatrix` is NxN where N = number of rows; `chordMatrix` is fixed 7x7.
- **"section(s) [...] declare a soloMode but Vibe.band is null"**: set `band` (start with `BandPresets.quartet(...)`) or drop the `soloMode` from the named sections. A solo is the band passing a lead around; with no band there is nobody to pass it to.
- **The solo section sounds like every other section**: the vibe has a band, but the only members that could lead are `alwaysActive`, or their tracks are all `Chordal`/`Percussive` (a `Jam` lead must own a `Melodic`-role track), or the section `density = 0f`s the soloist. See the guide's section 7.
- **"customProgression degrees must be 0..6"**: use 0 (I) through 6 (VII). Do not use negative numbers or values >= 7.
- **Vibe does not show up in the picker**: verify the `@Inject` + `@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())` annotations are both present and the class implements `VibeProvider`, and that its `VibeNames` constant is listed in `VibeCatalog.kt` (a `WIP` entry shows only on debug builds and `-Pcatalog=wip` desktop runs). Also rebuild — Metro DI code generation requires a recompile.
- **Vibe shows no album badge or chip, or is missing from Android Auto**: it is not in `VibeCatalog.kt` (STEALTH takes every cataloged vibe no other album lists). A vibe that should be on RIF, 0-2-1 or Anomalies instead sits on STEALTH until it is listed in `AlbumCatalog.kt`.
- **Bass wanders off-key**: `chordFollow = ROOT_ONLY` on the bass track snaps it to the chord root. Most driving grooves want this.
- **Pads sound random / nothing locks in**: set `barStrategy = REPEAT` or `MUTATE` on at least the rhythm and bass tracks; reserve `INDEPENDENT` for genuine texture layers.
- **Lead does not follow the key**: verify the track's `role` is `TrackRole.Melodic` (not `Percussive`) and that `noteRangeLow/High` overlap with the scale notes.

## Key references

- **The shared composition guide:** `features/ai/src/commonMain/composeResources/files/vibe_guide.md` (its `{{NAME}}` examples come from `features/ai/.../tools/VibeGuide.kt`'s `VibeGuideExamples`). Its tests are `VibeGuide*Test` in `features/ai`; after editing it, run `./gradlew :features:ai:jvmTest`.
- **The caster:** `features/ai/src/commonMain/kotlin/org/balch/orpheus/features/ai/composer/` (`VibeCaster.kt`, `CastRules.kt`, `VoicePalette.kt`), exposed as `pulsar_cast_vibe` (`tools/VibeCastTool.kt`).
- **JSON to Kotlin:** `.claude/skills/grab-ai-vibes/scripts/import_vibe.sh` (runs `tools:vibe-codegen`).
- Schema source-of-truth: the `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/models/` package — one KDoc-annotated file per type (`Vibe.kt`, `OrpheusEngine.kt`, `GenreProfile.kt`, `ChordComping.kt`, `Arrangement.kt`, …). There is no single `PulsarVibe.kt`.
- Listening benchmark: `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/DogHouseVibe.kt`.
- ViewModel that consumes vibes (for reference — no edits needed): `PulsarViewModel` (in `PulsarFeature.kt`) — takes `Set<VibeProvider>` via DI.
- **`references/fm_patches.md`** — full SixOp FM patch banks for `DX`/`DX2`/`DX3`. Read before setting `harmonics` on any of these engines. Includes a per-bank index→patch-name table and the harmonics-zone math.
- **`references/envelopes.md`** — `EnvelopeType` (vibe-global) and `EnvelopeProfile` (per-track) reference. Includes solo and ducking behavior per profile and the AD/TIDES/BLEND crossover math.

## See also

- `references/examples.md` — a worked example translating a real song into a Vibe, showing the full decomposition and the parameter choices that followed. It predates casting: read it for the decomposition, not as a way to start a file.
