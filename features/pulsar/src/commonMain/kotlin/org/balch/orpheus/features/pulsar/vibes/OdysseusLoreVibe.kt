package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickSource
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.ProgressionAnchor
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.SoloMode
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.WahParams
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Odysseus Lore — slow psychedelic power-trio lament with a wah-drenched lead.
 *
 * A descending Andalusian bassline (the vibe's whole spine) walks D-C-Bb-A under
 * a standing tempo-synced wah on the lead. Structure breathes verse -> driven jam
 * -> hard cut back to the slow verse. First vibe on the bass line channel and the
 * per-track lick-wah insert: the bass owns its authored figure, the lead owns its
 * wah phrases, and LickBuilder jams mutate whichever channel the soloist owns.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class OdysseusLoreVibe : VibeProvider {
    override val name: String = "Odysseus Lore"

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            bpm = 102f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.D,
            scaleType = ScaleType.MINOR,
            energy = 0.55f,
            complexity = 0.4f,
            space = 0.45f,
            mood = 0.35f,
            deep = 0.5f,
            stepCount = 64,   // 4-bar pattern = one full pass of the 4-chord descent
            genre = GenreProfile(
                swingAmount = 0.06f,
                ghostProbability = 0.18f,
                noteRangeLow = 45,
                noteRangeHigh = 69,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.DARK,
                chordsPerBar = 1,
                customProgression = chords(0, 6, 5, 4),  // D - C - Bb - A lament
            ),
            progressionAnchor = ProgressionAnchor.EVERY_4,
            progressionDriftRange = 0.1f,

            // The lead's wah phrase: a rising call that hangs, then falls home.
            // (Draft — tuned by ear with balch; degrees are D natural minor, +7 = octave.)
            lick = Lick(
                steps = listOf(
                    LickStep(scaleDegree = 7, duration = 0.5f, velocity = 0.85f),
                    LickStep(scaleDegree = 9, duration = 0.5f, velocity = 0.8f),
                    LickStep(scaleDegree = 10, duration = 1.0f, velocity = 0.9f, glideRate = 0.3f),
                    LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0f),
                    LickStep(scaleDegree = 9, duration = 0.5f, velocity = 0.75f),
                    LickStep(scaleDegree = 7, duration = 0.5f, velocity = 0.7f),
                    LickStep(scaleDegree = 4, duration = 1.5f, velocity = 0.82f),
                    LickStep(scaleDegree = -1, duration = 3.0f, velocity = 0f),
                ),
                loopLength = 8,  // phrase then space — the wah breathes
            ),
            lickMutation = 0.45f,
            lickOctave = -1,

            // The spine: four descending anchors (D C Bb A), one bar each, each
            // stated long, restated short, and answered with an upper-neighbor
            // walk. FIXED chordFollow renders it exactly as written.
            bassLine = Lick(
                steps = listOf(
                    // Bar 1 — D anchor
                    LickStep(7, 1.5f, 0.92f),
                    LickStep(-1, 0.5f, 0f),
                    LickStep(7, 0.5f, 0.7f),
                    LickStep(9, 0.5f, 0.62f),
                    LickStep(8, 1.0f, 0.78f),
                    // Bar 2 — C anchor
                    LickStep(6, 1.5f, 0.9f),
                    LickStep(-1, 0.5f, 0f),
                    LickStep(6, 0.5f, 0.68f),
                    LickStep(8, 0.5f, 0.6f),
                    LickStep(7, 1.0f, 0.76f),
                    // Bar 3 — Bb anchor
                    LickStep(5, 1.5f, 0.9f),
                    LickStep(-1, 0.5f, 0f),
                    LickStep(5, 0.5f, 0.68f),
                    LickStep(7, 0.5f, 0.6f),
                    LickStep(6, 1.0f, 0.76f),
                    // Bar 4 — A anchor, then walk the octave back up to D
                    LickStep(4, 1.0f, 0.92f),
                    LickStep(-1, 0.5f, 0f),
                    LickStep(4, 0.5f, 0.7f),
                    LickStep(5, 0.5f, 0.72f),
                    LickStep(6, 0.5f, 0.74f),
                    LickStep(7, 1.0f, 0.8f, glideRate = 0.25f),
                ),
                loopLength = 16,  // exactly the 4-bar cycle, no rest padding
            ),
            bassLineMutation = 0.25f,  // the hook stays recognizable
            bassLineOctave = 2,        // D2 register descent (38 -> 36 -> 34 -> 33)

            lickWah = WahParams(
                rateDivision = 4f,     // quarter-note rock of the pedal
                depth = 0.85f,
                resonanceQ = 3f,
                centerHz = 750f,
                sweepOctaves = 1.2f,
                wet = 0.9f,
            ),
            anomalies = listOf(
                WahAnomaly(probability = 0.03f),  // rare whole-mix wah moment
            ),

            tracks = listOf(
                // 0 — kick: steady rock anchor
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.85f).let { kick ->
                    TrackVoice(
                        engineEdm = kick, engineSpace = kick,
                        role = TrackRole.Percussive,
                        density = 0.45f,
                        barStrategy = BarStrategy.REPEAT,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                    )
                },
                // 1 — snare/toms: backbeat with tom-leaning fills
                OrpheusEngine(engineId = OrpheusEngineId.SD, volume = 0.8f).let { snare ->
                    TrackVoice(
                        engineEdm = snare, engineSpace = snare,
                        role = TrackRole.Percussive,
                        density = 0.4f,
                        barStrategy = BarStrategy.FILL,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                    )
                },
                // 2 — hat/ride wash
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.55f).let { hat ->
                    TrackVoice(
                        engineEdm = hat, engineSpace = hat,
                        role = TrackRole.Percussive,
                        density = 0.5f,
                        barStrategy = BarStrategy.MUTATE,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                    )
                },
                // 3 — THE bass: owns the bass line channel
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH, volume = 0.85f,
                    noteRangeLow = 33, noteRangeHigh = 45,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.PD),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FIXED,  // the phrase IS the progression
                            lickMode = LickMode.Fill,
                            lickSource = LickSource.BASS,
                        ),
                        density = 0.6f,
                        barStrategy = BarStrategy.REPEAT,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                    )
                },
                // 4 — wah lead: standing wah insert, plays the lead lick
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH, volume = 0.78f,
                    noteRangeLow = 57, noteRangeHigh = 76,
                    delaySend = 0.25f, reverbSend = 0.3f,
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead.copy(engineId = OrpheusEngineId.VA),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FIXED,
                            lickMode = LickMode.Fill,
                            wahLick = true,
                        ),
                        density = 0.5f,
                        barStrategy = BarStrategy.CALL_RESPONSE,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                    )
                },
                // 5 — pad haze: thin organ-adjacent bed
                OrpheusEngine(
                    engineId = OrpheusEngineId.ENS, volume = 0.35f,
                    holdProbability = 0.85f, reverbSend = 0.45f,
                ).let { pad ->
                    TrackVoice(
                        engineEdm = pad, engineSpace = pad,
                        role = TrackRole.Chordal(),
                        density = 0.25f,
                        barStrategy = BarStrategy.REPEAT,
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.EFFECT,
                    )
                },
                // 6 — psych swirl: sparse granular color
                OrpheusEngine(
                    engineId = OrpheusEngineId.GRN, volume = 0.3f, reverbSend = 0.5f,
                ).let { swirl ->
                    TrackVoice(
                        engineEdm = swirl, engineSpace = swirl,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED),
                        density = 0.12f,
                        barStrategy = BarStrategy.INDEPENDENT,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                    )
                },
                // 7 — silent spare (power trio: nobody else on stage)
                OrpheusEngine(engineId = OrpheusEngineId.NSE, volume = 0f).let { spare ->
                    TrackVoice(
                        engineEdm = spare, engineSpace = spare,
                        role = TrackRole.Percussive,
                        density = 0f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                    )
                },
            ),

            band = Band(
                members = listOf(
                    BandMember(name = "Drummer", tracks = listOf(0, 1, 2), alwaysActive = true),
                    BandMember(name = "Bassist", tracks = listOf(3)),
                    BandMember(name = "Guitarist", tracks = listOf(4)),
                    BandMember(name = "Haze", tracks = listOf(5, 6)),
                ),
                handoffMatrix = bandMatrix(
                    //            DRUM  BASS  GTR   HAZE
                    "Drummer" to row(0.0f, 0.2f, 0.6f, 0.2f),   // Drummer hands to Guitarist mostly
                    "Bassist" to row(0.1f, 0.0f, 0.7f, 0.2f),   // Bassist -> Guitarist
                    "Guitarist" to row(0.1f, 0.5f, 0.0f, 0.4f), // Guitarist -> Bassist (the trade!)
                    "Haze" to row(0.1f, 0.3f, 0.6f, 0.0f),      // Haze -> Guitarist
                ),
                // No pull-in guidance in the source draft; mirrors handoffMatrix's cast
                // relationships until balch hand-tunes a distinct pull-in feel by ear.
                pullInMatrix = bandMatrix(
                    //            DRUM  BASS  GTR   HAZE
                    "Drummer" to row(0.0f, 0.2f, 0.6f, 0.2f),
                    "Bassist" to row(0.1f, 0.0f, 0.7f, 0.2f),
                    "Guitarist" to row(0.1f, 0.5f, 0.0f, 0.4f),
                    "Haze" to row(0.1f, 0.3f, 0.6f, 0.0f),
                ),
            ),

            tension = TensionProfile(
                innerBars = 8,
                outerBars = 32,
                outerDepth = 0.4f,
                volume = 0.3f,
                timing = 0.15f,
                spurtChance = 0.04f,
            ),

            effects = VibeEffects(
                delayTimeA = 0.375f,   // dotted-8th slapback
                delayFeedback = 0.35f,
                reverbSize = 0.5f,
                reverbBrightness = 0.45f,
                deepFloor = 0.25f,
            ),

            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = 3,
                lengthSeconds = 180..300,
                sections = listOf(
                    // 0 INTRO — the bass alone states the lament; band swells in
                    Section(
                        name = "intro",
                        barsMin = 1, barsMax = 2,
                        macroOverrides = MacroOverrides(energy = 0.7f, space = 1.2f),
                        trackOverrides = mapOf(
                            0 to TrackSectionOverride(density = 0.15f),
                            1 to TrackSectionOverride(density = 0f),
                            2 to TrackSectionOverride(density = 0f),
                            4 to TrackSectionOverride(density = 0f),
                            5 to TrackSectionOverride(density = 0f),
                            6 to TrackSectionOverride(density = 0f),
                        ),
                        transitions = listOf(SectionTransition(1, 1f, transitionBars = 1)),
                    ),
                    // 1 VERSE — slow full-band groove under the wah
                    Section(
                        name = "verse",
                        barsMin = 2, barsMax = 3,
                        transitions = listOf(
                            SectionTransition(2, 0.8f, transitionBars = 1),
                            SectionTransition(3, 0.1f),
                        ),
                    ),
                    // 2 JAM — driven; LickBuilder trades mutate the soloist's channel
                    Section(
                        name = "jam",
                        barsMin = 2, barsMax = 4,
                        macroOverrides = MacroOverrides(
                            energy = 1.35f, complexity = 1.3f, space = 0.85f, mood = 1.1f,
                        ),
                        soloMode = SoloMode.LickBuilder(probability = 0.55f, mutationRate = 0.5f),
                        transitions = listOf(
                            // Hard cut home: "right back into the slower section"
                            SectionTransition(1, 1f, transitionBars = 0),
                        ),
                    ),
                    // 3 OUTRO — the lament dissolves
                    Section(
                        name = "outro",
                        barsMin = 1, barsMax = 2,
                        macroOverrides = MacroOverrides(energy = 0.55f, space = 1.5f),
                        transitions = emptyList(),
                    ),
                ),
            ),
        )
    }
}
