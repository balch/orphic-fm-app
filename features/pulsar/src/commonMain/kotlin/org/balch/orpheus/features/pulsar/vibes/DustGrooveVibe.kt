package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
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
import org.balch.orpheus.features.pulsar.models.TonalTension
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.row

@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class DustGrooveVibe : VibeProvider {
    override val name: String = "Dust Groove"
    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            bpm = 110f,
            envelopeType = EnvelopeType.AD,
            rootNote = RootNote.E,
            scaleType = ScaleType.MINOR,
            lick = Lick(
                steps = listOf(
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.90f),
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.80f),
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.85f),
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.80f),
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.85f),
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.90f),
                    LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0.75f),
                    LickStep(scaleDegree = -2, duration = 1.0f, velocity = 0.80f),
                ),
                loopLength = 16,
            ),
            // 24-bar lick evolution arc — mutations stick:
            //  - lickMutation = 1.0 → max drift = 4 scale degrees (full musical range)
            //  - Mutations within ±4 degrees of original are KEPT (not reverted), so the
            //    lick permanently shifts after each spurt until it hits the bound.
            //  - innerBars=8 + outerBars=24 → spurts at bars 8, 16, 24 with outer-cycle
            //    amplification peaking at bar 24.
            //  - spurtChance=0.15 → occasional extra random spurts between tension peaks.
            lickMutation = 1.0f,
            band = Band(
                members = listOf(
                    BandMember("Drummer", listOf(0, 1, 2), alwaysActive = true,
                        loudness = 0.7f, creativity = 0.4f, swing = 0.1f, drag = -0.05f),
                    BandMember("Bassist", listOf(3),
                        loudness = 0.8f, creativity = 0.5f, swing = 0.0f, drag = 0.08f),
                    BandMember("Keys", listOf(4),
                        loudness = 0.5f, creativity = 0.5f, swing = 0.0f, drag = 0.0f),
                    BandMember("FX", listOf(5, 6, 7),
                        loudness = 0.3f, creativity = 0.7f, swing = 0.0f, drag = 0.1f),
                ),
                handoffMatrix = bandMatrix(
                    //            DRUM  BASS  KEYS  FX
                    "Drummer" to row(0.00f, 0.30f, 0.35f, 0.10f),
                    "Bassist" to row(0.25f, 0.00f, 0.35f, 0.15f),
                    "Keys"    to row(0.20f, 0.35f, 0.00f, 0.20f),
                    "FX"      to row(0.15f, 0.30f, 0.30f, 0.00f),
                ),
                pullInMatrix = bandMatrix(
                    //            DRUM  BASS  KEYS  FX
                    "Drummer" to row(0.00f, 0.25f, 0.20f, 0.05f),
                    "Bassist" to row(0.20f, 0.00f, 0.35f, 0.10f),
                    "Keys"    to row(0.15f, 0.35f, 0.00f, 0.10f),
                    "FX"      to row(0.10f, 0.20f, 0.20f, 0.00f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 4,
                barsPerLeadMin = 2, barsPerLeadMax = 6,
            ),
            energy = 0.6f,
            complexity = 0.4f,
            space = 0.25f,
            mood = 0.4f,
            genre = GenreProfile(
                swingAmount = 0.10f,
                ghostProbability = 0.25f,
                noteRangeLow = 36,
                noteRangeHigh = 66,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.MODAL,
                chordsPerBar = 2,
            ),
            // Tighter MODAL drift so the pocket doesn't wander.
            progressionAnchor = ProgressionAnchor.EVERY_8,
            progressionDriftRange = 0.15f,
            tracks = listOf(
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.85f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.50f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT
                    )
                },
                OrpheusEngine(engineId = OrpheusEngineId.SD, volume = 0.65f).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.10f,
                        density = 0.40f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.FILL
                    )
                },
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.60f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.20f,
                        density = 0.65f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE
                    )
                },
                // Bass: locked pattern + ROOT_ONLY chord follow keeps the dusty pocket tight.
                // noteRangeLow 33 (A1) preserved — the dusty warmth is the character.
                OrpheusEngine(
                    engineId = OrpheusEngineId.VCF,
                    volume = 0.80f,
                    noteRangeLow = 33,
                    noteRangeHigh = 52,
                    reverbBrightness = 0.35f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.VA),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.00f,
                        density = 0.55f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                OrpheusEngine(
                    engineId = OrpheusEngineId.FM,
                    volume = 0.45f,
                    noteRangeLow = 45,
                    noteRangeHigh = 64,
                    reverbBrightness = 0.45f,
                    glideRate = 0.1f,
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead,
                        role = TrackRole.Melodic(lickMode = LickMode.Squash), // Squash: CALL_RESPONSE owns bar 2
                        pan = -0.20f,
                        density = 0.35f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.CALL_RESPONSE,
                    )
                },
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.30f,
                    modLfoRate = 0.3f,
                    modLfoDepth = 0.5f,
                    modLfoShape = 0.35f,
                    modLfoCoupling = 0.2f,
    //                    holdProbability = 0.45f,
    //                    holdLengthMin = 4,
    //                    holdLengthMax = 10,
                    reverbSend = 0.35f,
                    delaySend = 0.25f,
                    noteRangeLow = 38,
                    noteRangeHigh = 59,
                    reverbBrightness = 0.55f,
                    glideRate = 0.2f,
                ).let { wsh ->
                    TrackVoice(
                        engineEdm = wsh,
                        engineSpace = wsh.copy(engineId = OrpheusEngineId.ADD),
                        role = TrackRole.Melodic(lickMode = LickMode.Squash), // Squash: CALL_RESPONSE owns bar 2
                        pan = 0.30f,
                        density = 0.20f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.CALL_RESPONSE,
                    )
                },
                OrpheusEngine(
                    engineId = OrpheusEngineId.GRN,
                    volume = 0.30f,
                    modLfoRate = 0.25f,
                    modLfoDepth = 0.4f,
                    modLfoShape = 0.45f,
                    modLfoCoupling = 0.15f,
                    holdProbability = 0.3f,
                    holdLengthMin = 3,
                    holdLengthMax = 8,
                    reverbSend = 0.3f,
                    delaySend = 0.2f,
                    noteRangeLow = 40,
                    noteRangeHigh = 60,
                    reverbBrightness = 0.5f,
                    glideRate = 0.25f,
                ).let { grain ->
                    TrackVoice(
                        engineEdm = grain,
                        engineSpace = grain.copy(engineId = OrpheusEngineId.WTB),
                        role = TrackRole.Melodic(),
                        pan = -0.30f,
                        density = 0.15f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
                OrpheusEngine(
                    engineId = OrpheusEngineId.PAR,
                    volume = 0.20f,
                    modLfoRate = 0.35f,
                    modLfoDepth = 0.35f,
                    modLfoShape = 0.55f,
                    modLfoCoupling = 0.25f,
                    holdProbability = 0.25f,
                    holdLengthMin = 2,
                    holdLengthMax = 6,
                    reverbSend = 0.25f,
                    delaySend = 0.15f,
                    noteRangeLow = 36,
                    noteRangeHigh = 60,
                    reverbBrightness = 0.45f,
                    glideRate = 0.15f,
                ).let { fx ->
                    TrackVoice(
                        engineEdm = fx,
                        engineSpace = fx.copy(engineId = OrpheusEngineId.NSE),
                        role = TrackRole.Percussive,
                        pan = 0.35f,
                        density = 0.08f,
                        envelopeProfile = EnvelopeProfile.WILD,
                        macroMap = TrackMacroMap.WILD,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
            ),
            stepCount = 32,
            tension = TensionProfile(
                // 8-bar inner cycle, 24-bar outer — lick spurts every 8 bars, outer peaks at 24
                innerBars = 8, outerBars = 24, outerDepth = 0.6f,
                volume = 0.4f,
                tonal = TonalTension(octaveShift = true, chromaticPassing = 0.12f),
                timing = 0.3f,
                evolution = EvolutionTension(
                    timbreLow = 0.25f, timbreHigh = 0.55f, timbreProbability = 0.75f,
                    attackPoint = 0.5f, releaseSpeed = 0.3f,
                ),
                spurtChance = 0.15f,  // occasional extra random spurts
            ),
            effects = VibeEffects(
                delayTimeA = 0.3f,
                delayTimeB = 0.45f,
                delayFeedback = 0.35f,
                delayDamping = 0.6f,
                reverbSize = 0.55f,
                reverbDamping = 0.6f,
                reverbBrightness = 0.4f,
            ),
            arrangement = Arrangement(
                introIndex = 0,
                sections = listOf(
                    // 0: loop — main dusty groove pocket
                    Section(
                        name = "loop",
                        barsMin = 8, barsMax = 16,
                        transitions = listOf(
                            SectionTransition(targetIndex = 1, weight = 0.35f),
                            SectionTransition(targetIndex = 2, weight = 0.4f),
                            SectionTransition(targetIndex = 3, weight = 0.25f),
                        ),
                        recencyDecay = 0.5f,
                    ),
                    // 1: breakdown — stripped, bass drones on root for the lo-fi pocket
                    Section(
                        name = "breakdown",
                        barsMin = 4, barsMax = 8,
                        transitions = listOf(
                            SectionTransition(targetIndex = 0, weight = 0.4f),
                            SectionTransition(targetIndex = 3, weight = 0.6f),
                        ),
                        recencyDecay = 0.5f,
                        macroOverrides = MacroOverrides(
                            energy = 0.5f, complexity = 0.4f, space = 1.4f, mood = 0.7f,
                        ),
                        chordFollow = ChordFollow.FIXED,  // bass drones on E during breakdown
                    ),
                    // 2: jam — IMPROVISERS, modal improv over the groove
                    Section(
                        name = "jam",
                        barsMin = 8, barsMax = 16,
                        transitions = listOf(
                            SectionTransition(targetIndex = 0, weight = 0.5f),
                            SectionTransition(targetIndex = 1, weight = 0.3f),
                            SectionTransition(targetIndex = 3, weight = 0.2f),
                        ),
                        recencyDecay = 0.5f,
                        macroOverrides = MacroOverrides(
                            energy = 0.8f, complexity = 1.5f, space = 1.2f, mood = 1.3f,
                        ),
                        soloMode = SoloMode.LickBuilder(probability = 0.8f, mutationRate = 0.35f),
                    ),
                    // 3: buildup — rising energy, more fills, pushing toward the loop
                    Section(
                        name = "buildup",
                        barsMin = 4, barsMax = 8,
                        transitions = listOf(
                            SectionTransition(targetIndex = 0, weight = 0.8f),
                            SectionTransition(targetIndex = 2, weight = 0.2f),
                        ),
                        recencyDecay = 0.5f,
                        macroOverrides = MacroOverrides(
                            energy = 1.4f, complexity = 1.3f, space = 0.6f,
                        ),
                    ),
                ),
            ),
    )
    }
}
