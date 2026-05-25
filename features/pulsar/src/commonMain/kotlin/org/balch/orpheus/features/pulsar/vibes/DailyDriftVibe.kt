package org.balch.orpheus.features.pulsar.vibes

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingHumanization
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.ProgressionAnchor
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.chords
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Time-Drift family — one shared 8-track structure, two different "clocks".
 *
 * Both vibes call [dailyDriftVibe] for their body and only differ in the seed
 * they pass in.  Same seed → same patterns; different seed → audibly different
 * generation while the engines, drum kit, key, and section graph stay constant.
 *
 *  - **UTC**:       seed regenerates once per UTC calendar day.  Every listener
 *                   on earth hears the same "today's drift" within that 24-hour
 *                   window.
 *  - **TimeZone**:  seed regenerates once per LOCAL calendar day AND varies by
 *                   timezone, so two listeners in different zones get different
 *                   drifts on the same wall-clock day.
 *
 * The character: 88 BPM, A minor, BLEND envelopes, four "time-of-day" sections.
 * DX2 keys + DX3 lead use macro-driven patch walking, so the seed-derived
 * mood/energy defaults *also* shift which FM patches play that day.
 */

// ─────────────────────────────────────────────────────────────────────────────
// USER-AUTHORED HOOKS  ▼  Three small functions — the heart of the feature.
// ─────────────────────────────────────────────────────────────────────────────

private data class MacroDefaults(
    val energy: Float,
    val complexity: Float,
    val space: Float,
    val mood: Float,
    val deep: Float,
)

/**
 * Hash of an ISO-ish "HH-zone" key — stable for the current hour in this zone.
 *
 *  - For `TimeZone.UTC` the zone id is `"Z"`, so every listener on earth gets
 *    the same seed during the same UTC hour (UTC vibe character).
 *  - For `TimeZone.currentSystemDefault()` the zone id is e.g.
 *    `"America/Los_Angeles"`, so two listeners in different zones on the same
 *    wall-clock hour get distinct seeds (TimeZone vibe character).
 *
 * Hourly granularity gives a fresh musical idea every hour while keeping
 * re-loads within the hour stable — so the user can pause/resume without the
 * vibe shapeshifting.
 */
private fun TimeZone.toCurrentHourSeed(): Int {
    val hour = Clock.System.now().toLocalDateTime(this).hour
    return "$hour-$id".hashCode()
}

private fun utcHourlySeed(): Int =
    TimeZone.UTC.toCurrentHourSeed()

private fun localHourlySeed(): Int =
    TimeZone.currentSystemDefault().toCurrentHourSeed()

/**
 * Derive the five live-macro starting positions from [seed] + [hourBasis].
 *
 * Strategy:
 *  - **Mood** sweeps smoothly with the hour of day in [hourBasis]: lowest at
 *    midnight, peak at noon, back down by midnight.  Mood drives the DX patch
 *    walk on keys + lead, so this is what makes "today at noon" sound visibly
 *    different from "tonight at 3am" without sounding random.
 *  - **Energy / Space** ride a gentler version of the same brightness curve —
 *    days are punchier, nights are roomier.
 *  - **Per-seed jitter** (small ±5–15 % from [Random]) layers on top so the
 *    same hour on different days still has subtle variety.
 *
 * Net effect: hour-to-hour changes are smooth; UTC listeners worldwide land
 * on the same mood at the same UTC hour; TimeZone listeners get a curve
 * shaped by their own local clock.
 */
private fun deriveMacros(seed: Int, hourBasis: TimeZone): MacroDefaults {
    val r = Random(seed)
    val hour = Clock.System.now().toLocalDateTime(hourBasis).hour
    // 0 at midnight, 1 at noon, 0 at midnight again — smooth bi-directional sweep.
    val brightness = 1f - abs(hour - 12) / 12f
    fun jitter(amount: Float) = (r.nextFloat() - 0.5f) * 2f * amount
    return MacroDefaults(
        energy     = (0.45f + brightness * 0.30f + jitter(0.05f)).coerceIn(0.30f, 0.85f),
        complexity = (0.35f + jitter(0.15f)).coerceIn(0.15f, 0.65f),
        space      = (0.45f + (1f - brightness) * 0.25f + jitter(0.05f)).coerceIn(0.30f, 0.85f),
        mood       = (0.25f + brightness * 0.55f + jitter(0.05f)).coerceIn(0.15f, 0.90f),
        deep       = (0.55f + (1f - brightness) * 0.15f + jitter(0.10f)).coerceIn(0.40f, 0.85f),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// USER-AUTHORED HOOKS  ▲   Everything below is the shared structural body.
// ─────────────────────────────────────────────────────────────────────────────

// 4-bar drift figure — gentle outline over the bar.
// Scale degrees: 0 / 2 / 6 / 3.  In Lydian this reads as I — iii — vii — #iv°
// — three minor/dim turns around a single major anchor: dark, modal, no V.
private val driftProgression = chords(0, 2, 6, 3)

// Shared macro map for the LOW drum kit (kick + snare).  Locks each drum
// into a "dark, sustaining thump" zone tonally, while letting space breathe
// the transient character openly.
//   - harmonics 0.1..0.3    : never clicky — always rounded
//   - timbre    0.6..0.9    : long-bodied, subby
//   - morph     0.3..0.8    : opens up wide with space
// Everything else (volume / density / swing / variation) stays as RHYTHM defaults.
private val driftPercussionMap = TrackMacroMap.RHYTHM.copy(
    moodHarmonics = MacroTarget(.1f, .3f),
    moodTimbre = MacroTarget(.6f, .9f),
    spaceDecay = MacroTarget(.3f, .8f),
)

// Hi-hat needs the SAME dark colour as the rest of the kit but a much tighter
// envelope — long-timbre on HH = always-open hat = "loose".  Pulls timbre down
// hard, narrows space so the hat doesn't bloom into open territory at night.
//   - harmonics 0.1..0.3    : matches kit colour (dark)
//   - timbre    0.10..0.30  : closed → barely-cracked, never open
//   - morph     0.2..0.5    : modest air at night, tight by day
private val driftHatMap = TrackMacroMap.RHYTHM.copy(
    moodHarmonics = MacroTarget(.1f, .3f),
    moodTimbre = MacroTarget(.10f, .30f),
    spaceDecay = MacroTarget(.2f, .5f),
)

// ── Chaos space-slot macroMaps ──────────────────────────────────────────────
// Each is a narrowed-range variant of a preset, so live macros don't push
// chaos engines into harsh territory. The pattern: pin HARMONICS on the
// engine itself (chaos coefficient lock), let TIMBRE (chaos rate) and MORPH
// (carrier/chaos blend) ride a tamed macro response, and add slow modLfo
// with coupling > 0 on melodic/texture tracks for rise/fall breathing.

// Chaos percussion: carrier-leaning morph so the percussive hit retains
// its pitched body; chaos adds crunch as a colour, not the main signal.
// Used on kick/snare/hat space slots.
private val chaosPercMap = TrackMacroMap.RHYTHM.copy(
    moodHarmonics = MacroTarget(.15f, .35f),
    moodTimbre    = MacroTarget(.18f, .40f),
    spaceDecay    = MacroTarget(.35f, .60f),
)

// Chaos melodic: strongly carrier-favoured — pitch fundamentals carry the
// musical line; chaos is wobble on top. Used on bass/lead space slots.
private val chaosMelodicMap = TrackMacroMap.MELODIC.copy(
    moodHarmonics = MacroTarget(.15f, .35f),
    moodTimbre    = MacroTarget(.12f, .28f),
    spaceDecay    = MacroTarget(.18f, .40f),
)

// Chaos pad/keys: carrier-dominant so the chord stack stays musical.
// Per-voice attractors layered across chord notes need the most discipline.
private val chaosPadMap = TrackMacroMap.MELODIC.copy(
    moodHarmonics = MacroTarget(.15f, .32f),
    moodTimbre    = MacroTarget(.10f, .25f),
    spaceDecay    = MacroTarget(.15f, .35f),
)

// Chaos texture/FX: background layer — can carry more chaos colour.
private val chaosTextureMap = TrackMacroMap.EFFECT.copy(
    moodHarmonics = MacroTarget(.18f, .45f),
    moodTimbre    = MacroTarget(.18f, .42f),
    spaceDecay    = MacroTarget(.35f, .60f),
)

// A wandering 4-step bass lick — three degrees plus a sub-tonic dip.
private val driftLick = Lick(
    steps = listOf(
        LickStep(scaleDegree = 0, duration = 1.5f, velocity = 0.9f),
        LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.7f, glideRate = .7f),
        LickStep(scaleDegree = -3, duration = 0.75f, velocity = 0.8f),
        LickStep(scaleDegree = 4, duration = 0.75f, velocity = 0.75f, glideRate = .2f),
    ),
    loopLength = 8,
)

private val sectionList = listOf(
    // 0: dawn — kick + texture only, sparse, mostly atmosphere.
    Section(
        name = "dawn",
        barsMin = 2, barsMax = 4,
        transitions = listOf(SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = 2)),
        macroOverrides = MacroOverrides(energy = 0.55f, complexity = 0.5f, space = 1.25f, mood = 0.8f),
    ),
    // 1: morning — full band, baseline. The verse-equivalent.
    Section(
        name = "morning",
        barsMin = 6, barsMax = 10,
        transitions = listOf(
            SectionTransition(targetIndex = 2, weight = 0.55f, transitionBars = 2),
            SectionTransition(targetIndex = 3, weight = 0.45f, transitionBars = 3),
        ),
        recencyDecay = 0.5f,
        macroOverrides = null,
    ),
    // 2: noon — peak energy. Mood high so DX patches walk into bright zones.
    Section(
        name = "noon",
        barsMin = 4, barsMax = 6,
        transitions = listOf(
            SectionTransition(targetIndex = 1, weight = 0.4f, transitionBars = 2),
            SectionTransition(targetIndex = 3, weight = 0.6f, transitionBars = 3),
        ),
        recencyDecay = 0.5f,
        macroOverrides = MacroOverrides(energy = 1.3f, complexity = 1.25f, space = 0.8f, mood = 1.2f),
    ),
    // 3: dusk — recede. Mood low pulls DX into darker patches, space stretches.
    Section(
        name = "dusk",
        barsMin = 4, barsMax = 8,
        transitions = listOf(
            SectionTransition(targetIndex = 1, weight = 0.8f, transitionBars = 3),
            SectionTransition(targetIndex = 0, weight = 0.2f, transitionBars = 4),
        ),
        recencyDecay = 0.5f,
        macroOverrides = MacroOverrides(energy = 0.7f, complexity = 0.8f, space = 1.3f, mood = 0.7f),
    ),
)

/**
 * Build the shared Time-Drift vibe body.
 *
 * The 8-track structure, drum patterns, lick, key, and section graph are all
 * fixed.  What [seed] controls is the generative pattern realization (via the
 * Pulsar pattern RNG inside the C++ engine) plus, through [deriveMacros], the
 * starting positions of the five live macros.
 */
private fun dailyDriftVibe(displayName: String, seed: Int, hourBasis: TimeZone): Vibe {
    val m = deriveMacros(seed, hourBasis)
    // Vibe.seed = 0 means "random per load" — never let a derived seed of 0
    // accidentally turn into a fresh random.
    val safeSeed = if (seed == 0) 1 else seed
    return Vibe(
        name = displayName,
        seed = safeSeed,
        bpm = 88f,
        envelopeType = EnvelopeType.BLEND,
        rootNote = RootNote.A,
        scaleType = ScaleType.MINOR,
        stepCount = 16,
        energy = m.energy,
        complexity = m.complexity,
        space = m.space,
        mood = m.mood,
        deep = m.deep,
        progressionAnchor = ProgressionAnchor.EVERY_8,
        progressionDriftRange = 0.35f,
        lick = driftLick,
        lickMutation = 0.45f,
        lickOctave = -1,
        genre = GenreProfile(
            swingAmount = 0.06f,
            ghostProbability = 0.18f,
            noteRangeLow = 36, noteRangeHigh = 72,
            rhythmDensity = RhythmPattern.FOUR_ON_FLOOR.density,
            progressionStyle = ProgressionStyle.DRONE,
            chordsPerBar = 1,
            customProgression = driftProgression,
        ),
        tracks = listOf(
            // 0 — kick.  Solid backbone, drifts only slightly with the seed.
            // `HEN` Hénon chaos on space slot for ambient mode.
            OrpheusEngine(
                engineId = OrpheusEngineId.BD, volume = 0.85f
            ).let { kick ->
                TrackVoice(
                    engineEdm = kick,
                    engineSpace = kick.copy(
                        engineId = OrpheusEngineId.HEN,
                        harmonics = 0.30f, timbre = 0.25f, morph = 0.60f,
                        pinHarmonics = true,
                        harmonicsModulation = 0.10f,
                        modLfoRate = 0.06f, modLfoDepth = 0.50f,
                        modLfoShape = 0.3f, modLfoCoupling = 0.0f,
                    ),
                    role = TrackRole.Percussive,
                    density = 0.5f,
                    envelopeProfile = EnvelopeProfile.RHYTHM,
                    macroMap = chaosPercMap,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // 1 — snare.  Backbeat with a touch of fill on hot sections.
            // `HEN` Hénon chaos on space slot for ambient mode.
            OrpheusEngine(engineId = OrpheusEngineId.SD, volume = 0.75f).let { snare ->
                TrackVoice(
                    engineEdm = snare,
                    engineSpace = snare.copy(
                        engineId = OrpheusEngineId.HEN,
                        harmonics = 0.35f, timbre = 0.30f, morph = 0.65f,
                        pinHarmonics = true,
                        harmonicsModulation = 0.10f,
                        modLfoRate = 0.06f, modLfoDepth = 0.55f,
                        modLfoShape = 0.3f, modLfoCoupling = 0.0f,
                    ),
                    role = TrackRole.Percussive,
                    density = 0.22f,
                    envelopeProfile = EnvelopeProfile.RHYTHM,
                    macroMap = chaosPercMap,
                    barStrategy = BarStrategy.MUTATE,
                )
            },
            // 2 — hat.  Carries the pulse; complexity macro opens it up.
            // `CHU` Chua chaos on space slot for ambient mode.
            OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.55f).let { hat ->
                TrackVoice(
                    engineEdm = hat,
                    engineSpace = hat.copy(
                        engineId = OrpheusEngineId.CHU,
                        harmonics = 0.30f, timbre = 0.20f, morph = 0.55f,
                        pinHarmonics = true,
                        harmonicsModulation = 0.08f,
                        modLfoRate = 0.07f, modLfoDepth = 0.50f,
                        modLfoShape = 0.3f, modLfoCoupling = 0.0f,
                    ),
                    role = TrackRole.Percussive,
                    density = 0.55f,
                    envelopeProfile = EnvelopeProfile.RHYTHM,
                    macroMap = chaosPercMap,
                    barStrategy = BarStrategy.MUTATE,
                )
            },
            // 3 — bass.  VCF filter bass plays the drift lick.
            // `DUF` Duffing chaos on space slot for ambient mode.
            OrpheusEngine(
                engineId = OrpheusEngineId.VCF,
                volume = 0.8f,
                harmonics = 0.35f, timbre = 0.55f, morph = 0.45f,
                noteRangeLow = 34, noteRangeHigh = 50,
                glideRate = 0.25f,
            ).let { bass ->
                TrackVoice(
                    engineEdm = bass,
                    engineSpace = bass.copy(
                        engineId = OrpheusEngineId.DUF,
                        harmonics = 0.30f, timbre = 0.18f, morph = 0.45f,
                        pinHarmonics = true,
                        harmonicsModulation = 0.10f,
                        modLfoRate = 0.05f, modLfoDepth = 0.60f,
                        modLfoShape = 0.2f, modLfoCoupling = 0.45f,
                    ),
                    role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY, lickMode = LickMode.Fill),
                    density = 0.6f,
                    envelopeProfile = EnvelopeProfile.MELODIC,
                    macroMap = chaosMelodicMap,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // 4 — keys.  DX2 chord stabs — mood-driven patch walk gives the
            // day's "keyboard voice".  `LRZ` Lorenz chaos on space slot for ambient mode.
            OrpheusEngine(
                engineId = OrpheusEngineId.OSC,
                volume = 0.5f,
                harmonics = 0.35f,                // DX2 mid-bank base patch
                harmonicsMacroRange = 0.06f,     // ±~2 patches across mood sweep
                timbre = 0.55f, morph = 0.5f,
                noteRangeLow = 55, noteRangeHigh = 68,
                delaySend = 0.25f, reverbSend = 0.35f,
            ).let { keys ->
                TrackVoice(
                    engineEdm = keys,
                    engineSpace = keys.copy(
                        engineId = OrpheusEngineId.OSC,
                        // Keys is track 4 — Pulsar's mod-LFO only runs on tracks 5+
                        // or DRONE envelopes, so harmonics here cannot be LFO-walked.
                        // Leave harmonics unpinned so the macroMap (mood) drives it —
                        // that gives motion via section transitions (dawn→noon→dusk).
                        harmonics = 0.15f, timbre = 0.18f, morph = 0.22f,
                        harmonicsMacroRange = 0.0f,  // explicit: ROS is not DX-family
                    ),
                    role = TrackRole.Chordal(
                        comping = ChordComping(
                            style = CompingStyle.GOSPEL_STABS,
                            humanization = CompingHumanization(extensionProbability = 0.2f),
                        ),
                        chordFollow = ChordFollow.FOLLOW,
                    ),
                    density = 0.35f,
                    envelopeProfile = EnvelopeProfile.DRONE,
                    macroMap = chaosPadMap,
                    barStrategy = BarStrategy.MUTATE,
                )
            },
            // 5 — pad.  ROS Rössler on EDM, DUF Duffing on Space — chaos pad with
            // pinned harmonics + audible LFO breathing.  Shorter holds (1-3 bars)
            // keep notes moving instead of droning on a single tone.
            OrpheusEngine(
                engineId = OrpheusEngineId.ROS,
                volume = 0.22f,
                harmonics = 0.28f, timbre = 0.18f, morph = 0.28f,
                pinHarmonics = true,
                harmonicsModulation = 0.10f,
                noteRangeLow = 48, noteRangeHigh = 72,
                // Almost no holds — when notes hold, pitch can't follow the chord
                // changes (chordsPerBar=1) so the pad "sticks" on the old note.
                // Letting density drive re-triggers means each note picks afresh
                // from the current chord; the chaos engine + LFO supply texture
                // motion, the note changes supply pitch motion.
                holdProbability = 0.15f,
                holdLengthMin = 1, holdLengthMax = 1,
                reverbSend = 0.5f,
                modLfoRate = 0.12f, modLfoDepth = 0.70f,
                modLfoShape = 0.2f, modLfoCoupling = 0.45f,
            ).let { pad ->
                TrackVoice(
                    engineEdm = pad,
                    engineSpace = pad.copy(
                        engineId = OrpheusEngineId.DUF,
                        harmonics = 0.28f, timbre = 0.18f, morph = 0.32f,
                        pinHarmonics = true,
                        harmonicsModulation = 0.10f,
                        modLfoRate = 0.12f, modLfoDepth = 0.70f,
                        modLfoShape = 0.2f, modLfoCoupling = 0.45f,
                    ),
                    role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
                    density = 0.18f,
                    envelopeProfile = EnvelopeProfile.MELODIC,
                    macroMap = chaosPadMap,
                    barStrategy = BarStrategy.INDEPENDENT,
                )
            },
            // 6 — lead.  DX3 evolving voice — also walks with mood, giving the
            // day's "lead instrument" alongside the keys.
            // `CHU` Chua chaos on space slot for ambient mode.
            OrpheusEngine(
                engineId = OrpheusEngineId.DX3,
                volume = 0.45f,
                harmonics = 0.582f,              // DX3 idx 19 "Etherial5a" — pad family
                harmonicsMacroRange = 0.07f,     // ±~2 patches: airy ↔ ethereal ↔ mal poly
                timbre = 0.55f, morph = 0.5f,
                noteRangeLow = 60, noteRangeHigh = 84,
                delaySend = 0.3f, reverbSend = 0.45f,
            ).let { lead ->
                TrackVoice(
                    engineEdm = lead,
                    engineSpace = lead.copy(
                        engineId = OrpheusEngineId.CHU,
                        harmonics = 0.35f, timbre = 0.22f, morph = 0.45f,
                        pinHarmonics = true,
                        harmonicsModulation = 0.10f,
                        modLfoRate = 0.05f, modLfoDepth = 0.60f,
                        modLfoShape = 0.2f, modLfoCoupling = 0.5f,
                        harmonicsMacroRange = 0.0f,  // explicit: CHU is not DX-family
                    ),
                    role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
                    density = 0.28f,
                    envelopeProfile = EnvelopeProfile.MELODIC,
                    macroMap = chaosMelodicMap,
                    barStrategy = BarStrategy.MUTATE,
                )
            },
            // 7 — texture.  Granular drift — ever-present low-volume haze.
            // `HEN` Hénon chaos on space slot for ambient mode.
            OrpheusEngine(
                engineId = OrpheusEngineId.GRN,
                volume = 0.28f,
                harmonics = 0.45f, timbre = 0.6f, morph = 0.55f,
                noteRangeLow = 42, noteRangeHigh = 66,
                holdProbability = 0.75f,
                holdLengthMin = 3, holdLengthMax = 6,
                reverbSend = 0.65f, delaySend = 0.2f,
                modLfoRate = 0.04f, modLfoDepth = 0.55f,
            ).let { tex ->
                TrackVoice(
                    engineEdm = tex,
                    engineSpace = tex.copy(
                        engineId = OrpheusEngineId.HEN,
                        harmonics = 0.35f, timbre = 0.30f, morph = 0.60f,
                        pinHarmonics = true,
                        harmonicsModulation = 0.10f,
                        modLfoRate = 0.05f, modLfoDepth = 0.65f,
                        modLfoShape = 0.3f, modLfoCoupling = 0.55f,
                    ),
                    role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
                    density = 0.2f,
                    envelopeProfile = EnvelopeProfile.EFFECT,
                    macroMap = chaosTextureMap,
                    barStrategy = BarStrategy.INDEPENDENT,
                )
            },
        ),
        tension = TensionProfile(
            innerBars = 8,
            outerBars = 16,
            outerDepth = 0.5f,
            volume = 0.4f,
            timing = 0.15f,
        ),
        effects = VibeEffects(
            delayTimeA = 0.375f,           // dotted-8th
            delayTimeB = 0.5f,             // half
            delayFeedback = 0.45f,
            delayDamping = 0.45f,
            reverbSize = 0.7f,
            reverbDamping = 0.45f,
            reverbBrightness = 0.55f,
            deepFloor = 0.3f,
        ),
        arrangement = Arrangement(
            introIndex = 0,
            outroIndex = sectionList.lastIndex,
            sections = sectionList,
            lengthSeconds = 40..60,
        ),
    )
}

//@Inject
//@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class UtcDriftVibe : VibeProvider {
    override val name: String = "UTC"
    override val vibe: Vibe
        get() = dailyDriftVibe(
            displayName = name,
            seed = utcHourlySeed(),
            hourBasis = TimeZone.UTC,
        )
}

//@Inject
//@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class TimeZoneDriftVibe : VibeProvider {
    override val name: String = "TimeZone"
    override val vibe: Vibe
        get() = dailyDriftVibe(
            displayName = name,
            seed = localHourlySeed(),
            hourBasis = TimeZone.currentSystemDefault(),
        )
}

