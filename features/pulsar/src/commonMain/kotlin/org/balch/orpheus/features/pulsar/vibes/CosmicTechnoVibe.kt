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

// Not Ready For PrimeTime
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class CosmicTechnoVibe : VibeProvider {
    override val name: String = "Cosmic Techno"
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        Vibe(
            name = "Cosmic Techno",
            bpm = 128f,
            envelopeType = EnvelopeType.AD,
            rootNote = RootNote.D,
            scaleType = ScaleType.MAJOR,
            band = Band(
                members = listOf(
                    BandMember("Drummer", listOf(0, 1, 2), alwaysActive = true,
                        loudness = 0.7f, creativity = 0.3f),
                    BandMember("Bassist", listOf(3),
                        loudness = 0.8f, creativity = 0.5f),
                    BandMember("Keys", listOf(4),
                        loudness = 0.5f, creativity = 0.5f),
                    BandMember("FX", listOf(5, 6, 7),
                        loudness = 0.3f, creativity = 0.7f),
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
            energy = 0.7f,
            complexity = 0.5f,
            space = 0.5f,
            mood = 0.4f,
            genre = GenreProfile(
                swingAmount = 0.02f,
                ghostProbability = 0.3f,
                noteRangeLow = 36,
                noteRangeHigh = 60,
                rhythmDensity = RhythmPattern.DENSE_16TH.density,
                progressionStyle = ProgressionStyle.POP,
                chordsPerBar = 2,
            ),
            // Tight POP drift — techno wants locked-in chord changes, not wandering.
            progressionAnchor = ProgressionAnchor.EVERY_4,
            progressionDriftRange = 0.15f,
            tracks = listOf(
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.90f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick.copy(engineId = OrpheusEngineId.MOD),
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.50f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                OrpheusEngine(engineId = OrpheusEngineId.SD, volume = 0.60f).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare.copy(engineId = OrpheusEngineId.NSE),
                        role = TrackRole.Percussive,
                        pan = -0.15f,
                        density = 0.35f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.65f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.20f,
                        density = 0.80f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Bass: locked techno pocket — REPEAT + ROOT_ONLY. Raised noteRangeLow
                // from F#1 (30) to E2 (40) for punchy techno bass instead of rumble.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.75f,
                    noteRangeLow = 40,
                    noteRangeHigh = 54,
                    reverbBrightness = 0.5f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.STR),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.00f,
                        density = 0.40f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Keys (CHD chord engine): REPEAT rhythm so chord pads don't drift on top
                // of the progression's native chord changes. FOLLOW stays default (chords move).
                OrpheusEngine(
                    engineId = OrpheusEngineId.CHD,
                    volume = 0.55f,
                    noteRangeLow = 48,
                    noteRangeHigh = 72,
                    reverbBrightness = 0.5f,
                    glideRate = 0.05f,
                ).let { keys ->
                    TrackVoice(
                        engineEdm = keys,
                        engineSpace = keys.copy(engineId = OrpheusEngineId.ENS),
                        role = TrackRole.Melodic(),
                        pan = -0.25f,
                        density = 0.30f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                OrpheusEngine(
                    engineId = OrpheusEngineId.CHD,
                    volume = 0.40f,
                    modLfoRate = 0.5f,
                    modLfoDepth = 0.3f,
                    modLfoShape = 0.3f,
                    modLfoCoupling = 0.1f,
                    holdProbability = 0.1f,
                    holdLengthMin = 2,
                    holdLengthMax = 4,
                    reverbSend = 0.1f,
                    delaySend = 0.15f,
                    noteRangeLow = 36,
                    noteRangeHigh = 60,
                    reverbBrightness = 0.5f,
                ).let { chordPad ->
                    TrackVoice(
                        engineEdm = chordPad,
                        engineSpace = chordPad,
                        role = TrackRole.Melodic(),
                        pan = -0.35f,
                        density = 0.20f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
                OrpheusEngine(
                    engineId = OrpheusEngineId.NSE,
                    volume = 0.30f,
                    modLfoRate = 0.4f,
                    modLfoDepth = 0.25f,
                    modLfoShape = 0.4f,
                    modLfoCoupling = 0.1f,
                    holdProbability = 0.05f,
                    holdLengthMin = 2,
                    holdLengthMax = 3,
                    reverbSend = 0.1f,
                    delaySend = 0.1f,
                    noteRangeLow = 36,
                    noteRangeHigh = 60,
                    reverbBrightness = 0.5f,
                ).let { fx ->
                    TrackVoice(
                        engineEdm = fx,
                        engineSpace = fx,
                        role = TrackRole.Percussive,
                        pan = 0.30f,
                        density = 0.15f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
                OrpheusEngine(
                    engineId = OrpheusEngineId.MOD,
                    volume = 0.25f,
                    modLfoRate = 0.6f,
                    modLfoDepth = 0.2f,
                    modLfoShape = 0.5f,
                    modLfoCoupling = 0.15f,
                    holdProbability = 0.0f,
                    holdLengthMin = 2,
                    holdLengthMax = 4,
                    reverbSend = 0.15f,
                    delaySend = 0.1f,
                    noteRangeLow = 36,
                    noteRangeHigh = 66,
                    reverbBrightness = 0.5f,
                    glideRate = 0.1f,
                ).let { wild ->
                    TrackVoice(
                        engineEdm = wild,
                        engineSpace = wild.copy(engineId = OrpheusEngineId.STR),
                        role = TrackRole.Melodic(),
                        pan = 0.40f,
                        density = 0.08f,
                        envelopeProfile = EnvelopeProfile.WILD,
                        macroMap = TrackMacroMap.WILD,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            stepCount = 32,
            tension = TensionProfile(
                innerBars = 4, outerBars = 16, outerDepth = 0.5f,
                volume = 0.4f,
                tonal = TonalTension(chromaticPassing = 0.1f),
                timing = 0.15f,
                evolution = EvolutionTension(
                    timbreLow = 0.2f, timbreHigh = 0.55f, timbreProbability = 0.8f,
                    attackPoint = 0.4f, releaseSpeed = 0.3f,
                ),
            ),
            effects = VibeEffects(
                delayTimeA = 0.2f,
                delayTimeB = 0.4f,
                delayFeedback = 0.4f,
                delayDamping = 0.5f,
                reverbSize = 0.5f,
                reverbDamping = 0.5f,
                reverbBrightness = 0.6f,
            ),
            arrangement = Arrangement(
                introIndex = 0,
                sections = listOf(
                    // 0: drive — main techno groove, relentless
                    Section(
                        name = "drive",
                        barsMin = 8, barsMax = 16,
                        transitions = listOf(
                            SectionTransition(targetIndex = 1, weight = 0.4f),
                            SectionTransition(targetIndex = 3, weight = 0.3f),
                            SectionTransition(targetIndex = 2, weight = 0.3f),
                        ),
                        recencyDecay = 0.5f,
                    ),
                    // 1: build — rising tension, filter opening, more hats
                    Section(
                        name = "build",
                        barsMin = 4, barsMax = 8,
                        transitions = listOf(
                            SectionTransition(targetIndex = 2, weight = 0.7f),
                            SectionTransition(targetIndex = 0, weight = 0.3f),
                        ),
                        recencyDecay = 0.5f,
                        macroOverrides = MacroOverrides(
                            energy = 1.3f, complexity = 1.4f, space = 0.7f, mood = 1.2f,
                        ),
                    ),
                    // 2: peak — maximum energy, round-robin solos trading licks
                    Section(
                        name = "peak",
                        barsMin = 8, barsMax = 16,
                        transitions = listOf(
                            SectionTransition(targetIndex = 3, weight = 0.6f),
                            SectionTransition(targetIndex = 0, weight = 0.4f),
                        ),
                        recencyDecay = 0.5f,
                        macroOverrides = MacroOverrides(
                            energy = 1.5f, complexity = 1.3f, mood = 1.3f,
                        ),
                        soloMode = SoloMode.Jam(probability = 0.8f),
                    ),
                    // 3: drop — sudden silence then kick returns; bass locks to D for impact
                    Section(
                        name = "drop",
                        barsMin = 2, barsMax = 4,
                        transitions = listOf(
                            SectionTransition(targetIndex = 0, weight = 0.7f),
                            SectionTransition(targetIndex = 1, weight = 0.3f),
                        ),
                        recencyDecay = 0.4f,
                        macroOverrides = MacroOverrides(
                            energy = 0.3f, complexity = 0.2f, space = 0.3f, mood = 0.5f,
                        ),
                        chordFollow = ChordFollow.FIXED,  // techno drop: bass drones on D
                    ),
                ),
            ),
    )
    }
}
