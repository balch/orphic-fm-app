# Worked example: translating a reference into a Vibe

This reference walks through creating `SunkenPlaceVibe` as a worked example. It's the vibe that was produced by the first invocation of the vibe-creator skill — an industrial/brooding groove translated from a specific NIN song (not named here, per the project rule).

Reading this end-to-end shows how each musical property of the reference maps to one or more fields in the Vibe schema.

> **Two syntax notes for the snippets below.** (1) `Engine.X` is shorthand for the real enum **`OrpheusEngineId.X`** (from `org.balch.orpheus.core.audio`); write `engineId = OrpheusEngineId.X` in code. (2) `customProgression` takes a `List<ChordStep>`, not a `List<Int>` — use the `chords(0, 0, 5, 6)` helper (or `listOf(ChordStep(0), ...)` for per-chord glides). A bare `listOf(0, 0, ...)` will not compile.

## The reference (described, not named)

- Tempo: ~95 BPM
- Feel: industrial, mechanical, visceral, brooding; sparse but heavy
- Drums: four-on-the-floor kick, processed backbeat snare, minimal hats
- Bass: detuned, distorted mid-register ostinato, minor key
- Harmony: dark minor (original key Ab minor = G# minor enharmonic)
- Texture: sustained pads, gritty distortion as a musical element, long reverb tails
- Structure: loop-based, builds through layering not dynamics, sexual-tension pull

## Decomposition to schema

| Reference property | Schema choice | Why |
|---|---|---|
| 95 BPM | `bpm = 95f` | Direct match. |
| Ab minor | `rootNote = RootNote.G_SHARP`, `scaleType = ScaleType.MINOR` | Enharmonic — `G_SHARP` is Ab. |
| Industrial / mechanical | `swingAmount = 0.0f`, `timing = 0.10f`, `complexity = 0.3f`, `lickMutation = 0.35f` | Straight grid, tight timing, low variability, lick barely drifts. |
| Dark minor / brooding | `progressionStyle = ProgressionStyle.DARK`, `mood = 0.25f`, `reverbBrightness = 0.30-0.40f` on most tracks | Dark matrix + muted reverb. |
| Static / loop-based | `progressionAnchor = ProgressionAnchor.EVERY_8`, `progressionDriftRange = 0.08f`, `customProgression = chords(0, 0, 5, 6, 0, 0, 3, 6)` | Anchor every 8 bars; minimal drift; explicit progression holds on tonic then steps. |
| 4-on-the-floor kick | Track 0 = `Engine.BD`, `BarStrategy.REPEAT`, `density = 0.50`, `rhythmDensity = RhythmPattern.FOUR_ON_FLOOR.density` | Kick on every quarter. |
| Backbeat snare | Track 1 = `Engine.SD`, `density = 0.35`, `reverbSend = 0.25` | Lo-fi processed snap. |
| Minimal hats | Track 2 = `Engine.HH`/`NSE`, `density = 0.22` | This is not techno — sparse hats. |
| Detuned distorted bass | Track 3 = `Engine.WSH` (EDM) / `Engine.VA` (space), `harmonics = 0.70`, `role = TrackRole.Melodic(chordFollow = ROOT_ONLY, lickMode = LickMode.Fill)` | WSH is the gritty waveshaping engine; ROOT_ONLY locks to chord root; Lick covers a full bar. |
| Bass ostinato / 2-note riff | `Lick(...)` with root-heavy steps and one flat-6 dip | Mechanical hammering on root, minor flat-6 dip for tension. |
| Sustained dark pads | Track 5 = `Engine.STR`/`ENS`, `envelopeProfile = DRONE`, `holdProbability = 0.85`, `modLfoRate = 0.04`, `reverbSend = 0.60` | Long-held, slow modulation, wet. |
| Distorted lead stabs | Track 4 = `Engine.WSH`, `TrackRole.Chordal(comping = ChordComping(ROCK_DOWNBEATS, arpMode = NEVER, high dropProbability/octaveJump))` | Piercing but sparse chord stabs on 1 & 3 with humanization. |
| Noise/grain as musical element | Track 6 = `Engine.GRN`/`NSE`, `role = Percussive`, `BarStrategy.INDEPENDENT` | Texture layer, regenerates each bar. |
| Industrial "clang" | Track 7 = `Engine.MOD`, `envelopeProfile = WILD`, `macroMap = WILD`, low density | Metallic modal hits surface occasionally. |
| Long reverb tails | `VibeEffects(reverbSize = 0.70, delayFeedback = 0.45, delayTimeA = 0.375, delayTimeB = 0.5, reverbBrightness = 0.40)` | Big hall, dark reverb, dotted-8th + half-note delay taps. |
| Slow inevitable build | `TensionProfile(innerBars = 8, outerBars = 32, outerDepth = 0.7, attackPoint = 0.6, releaseSpeed = 0.25)` | Long inner and outer cycles; peak past midpoint; slow decay. |
| 5-section loop-based structure | `Arrangement` with pulse -> grind -> stab -> fall -> drift | Opens sparse, hits the main groove, spikes aggressive, drops back, fades. |

## What to notice

- **Naming**: file is `SunkenPlaceVibe.kt`; display name is `"Sunken Place"`. Neither the band, song, nor album name appears. The KDoc at the top of the class describes the feel (industrial, brooding, mechanical, sexual-tension) without identifying the source.
- **Engine pairing**: many tracks use two different engines for `engineEdm`/`engineSpace`. The Energy macro crossfades. For Sunken Place, the "space" variants lean drier/less aggressive (e.g., bass: WSH → VA, snare keeps SD, hats: HH → NSE for lo-fi when energy is lower).
- **Band members**: `Drummer` / `Bassist` / `Lead` / `Pads`. Handoff weights favor keeping Drummer out of the lead rotation (drums rarely take a solo); pull-in weights are moderate so members lock together.
- **Section macroOverrides are multipliers**: the `stab` section uses `energy = 1.3f, complexity = 1.4f` — not absolute values. Reading as percentages: +30% energy, +40% complexity.
- **Registration is implicit**: the class is annotated `@Inject` + `@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())`. No DI module edit needed.
- **customProgression uses 0-indexed scale degrees**: `chords(0, 0, 5, 6, 0, 0, 3, 6)` means I-I-vi-VII-I-I-iv-VII in the minor scale. (Scale degrees: 0=I, 1=ii, 2=iii, 3=iv, 4=v, 5=VI, 6=VII.)

## Verifying the result

```bash
./gradlew :features:pulsar:compileKotlinJvm
```

Then launch the JVM desktop app and pick "Sunken Place" from the vibe menu. A/B against "Dog House" — they should feel equally tight and purposeful.
