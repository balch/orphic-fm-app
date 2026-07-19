package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.SwellAnomaly
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.Evolution
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.LpgMode
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.PitchEvolution
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
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Sun Pilgrim — a slow hypnotic drone for the long pilgrimage inward.
 *
 * Mantra-like single-pedal bass anchors the whole piece on a low root while a
 * vibraphone-shimmer melody traces a Phrygian modal phrase in the mid-upper
 * register. Mallet-roll percussion swells in and out instead of locking a
 * traditional kick/snare/hat pocket — soft tuned-tom blooms and reversed
 * cymbal/wind textures replace conventional drums. Hammond-style organ and
 * mellotron pads drone underneath, with a whispered speech texture drifting
 * across the stereo field.
 *
 * One long crescendo: sparse opening, gradual build to a percussion peak,
 * back to sparse. E Phrygian gives the exotic Eastern flat-2 colour without
 * any chord motion — the entire piece hangs on the tonic.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class SpaceDroneVibe : VibeProvider {
    override val name: String = "Space & Drums"

    private val defaultChordsPerBar = 1
    private val defaultProgression = chords(0, 0, 0, 3)

    private val moveChordsPerBar = 2
    private val moveProgression = chords(0, 0, 5, 6)

    private val endChordsPerBar = 2
    private val endProgression = chords(0, 0, 0, 3)

    private val sectionList by lazy {
        listOf(
            // 0: ignition — sparse opening, sub-pedal and faint shimmer only.
            Section(
                name = "ignition",
                barsMin = 4, barsMax = 8,
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 0.85f),
                    SectionTransition(targetIndex = 2, weight = 0.15f),
                ),
                recencyDecay = 0.4f,
                macroOverrides = MacroOverrides(
                    energy = 0.4f, complexity = 0.3f, space = 1.3f, mood = 0.9f,
                ),
            ),
            // 1: drift — vibraphone melody enters, organ swells in, slow lick-build.
            Section(
                name = "drift",
                barsMin = 8, barsMax = 14,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.5f),
                    SectionTransition(targetIndex = 3, weight = 0.3f),
                    SectionTransition(targetIndex = 0, weight = 0.1f),
                ),
                recencyDecay = 0.55f,
                macroOverrides = MacroOverrides(
                    energy = 0.9f, complexity = 0.9f, space = 1.1f, mood = 0.9f,
                ),
                soloMode = SoloMode.LickBuilder(probability = 0.5f, mutationRate = 0.4f),
            ),
            // 2: ascend — the percussion peak, full mallet roll, vibraphone reaches.
            Section(
                name = "ascend",
                barsMin = 8, barsMax = 12,
                transitions = listOf(
                    SectionTransition(targetIndex = 3, weight = 0.4f),
                    SectionTransition(targetIndex = 1, weight = 0.35f),
                    SectionTransition(targetIndex = 4, weight = 0.2f),
                ),
                recencyDecay = 0.6f,
                macroOverrides = MacroOverrides(
                    energy = 1.45f, complexity = 1.3f, space = 0.95f, mood = 1.0f,
                ),
                soloMode = SoloMode.Jam(),
                customProgression = moveProgression,
                chordsPerBar = moveChordsPerBar,
            ),
            // 3: void — strip back to drone and whisper, atonal middle.
            Section(
                name = "void",
                barsMin = 6, barsMax = 10,
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 0.45f),
                    SectionTransition(targetIndex = 4, weight = 0.4f),
                    SectionTransition(targetIndex = 0, weight = 0.1f),
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 0.4f, complexity = 1.6f, space = 1.5f, mood = 1.4f,
                ),
            ),
            // 4: return — reprise of the drift, fuller, the pilgrim arrives.
            Section(
                name = "return",
                barsMin = 6, barsMax = 12,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.4f),
                    SectionTransition(targetIndex = 1, weight = 0.3f),
                    SectionTransition(targetIndex = 3, weight = 0.2f),
                    SectionTransition(targetIndex = 0, weight = 0.1f),
                ),
                recencyDecay = 0.55f,
                macroOverrides = MacroOverrides(
                    energy = 1.5f, complexity = 1.2f, space = 1.05f, mood = 1.05f,
                ),
                soloMode = SoloMode.LickBuilder(probability = 0.7f, mutationRate = 0.5f),
                customProgression = moveProgression,
                chordsPerBar = moveChordsPerBar,
            ),
            // 5:
            Section(
                name = "fadeout",
                barsMin = 6, barsMax = 6,
                macroOverrides = MacroOverrides(
                    energy = 0.5f, complexity = 1.2f, space = 1.05f, mood = .5f,
                ),
                customProgression = endProgression,
                chordsPerBar = endChordsPerBar,
            ),
        )
    }

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            album = Album.STEALTH,
            bpm = 78f,
            envelopeType = EnvelopeType.TIDES,
            rootNote = RootNote.E,
            scaleType = ScaleType.PHRYGIAN,
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = sectionList.lastIndex,
                sections = sectionList,
            ),
            anomalies = listOf(
                SwellAnomaly(),
            ),
            energy = 0.30f,
            complexity = 0.40f,
            space = 0.80f,
            mood = 0.60f,
            deep = 0.65f,
            stepCount = 32,
            progressionAnchor = ProgressionAnchor.EVERY_8,
            progressionDriftRange = 0.2f,
            lickMutation = 0.35f,
            lickOctave = 5,
            // Vibraphone modal phrase in E Phrygian.
            // Scale degrees: 0=E, 1=F (flat-2), 2=G, 3=A, 4=B, 5=C, 6=D.
            // Hold E, dip to flat-2 (F), step up to G, reach to B, settle back, dip below to D.
            lick = Lick(
                steps = listOf(
                    LickStep(scaleDegree = 0, duration = 2.0f, velocity = 0.55f),
                    LickStep(scaleDegree = 1, duration = 1.0f, velocity = 0.50f),
                    LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.55f),
                    LickStep(scaleDegree = 4, duration = 1.5f, velocity = 0.65f),
                    LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.55f),
                    LickStep(scaleDegree = 1, duration = 0.75f, velocity = 0.45f),
                    LickStep(scaleDegree = 0, duration = 1.5f, velocity = 0.55f),
                    LickStep(scaleDegree = -1, duration = 1.0f, velocity = 0.45f),
                ),
                loopLength = 8,
            ),
            genre = GenreProfile(
                swingAmount = 0.0f,
                ghostProbability = 0.20f,
                noteRangeLow = 36,
                noteRangeHigh = 79,
                rhythmDensity = RhythmPattern.SPARSE.density,
                progressionStyle = ProgressionStyle.DRONE,
                chordsPerBar = defaultChordsPerBar,
                customProgression = defaultProgression,
            ),
            band = Band(
                members = listOf(
                    BandMember(
                        "Rhythm", listOf(0, 1, 2), alwaysActive = true,
                    ),
                    BandMember(
                        "Bassist", listOf(3),
                    ),
                    BandMember(
                        "Keys", listOf(4, 5),
                    ),
                    BandMember(
                        "Textures", listOf(6, 7),
                    ),
                ),
                // Vibraphone (Keys) and Textures take the leads; Rhythm/Bassist anchor.
                handoffMatrix = bandMatrix(
                    //              RHY   BASS  KEYS  TEX
                    "Rhythm" to row(0.00f, 0.20f, 0.55f, 0.25f),
                    "Bassist" to row(0.15f, 0.00f, 0.55f, 0.30f),
                    "Keys" to row(0.10f, 0.10f, 0.00f, 0.80f),
                    "Textures" to row(0.10f, 0.10f, 0.80f, 0.00f),
                ),
                pullInMatrix = bandMatrix(
                    //              RHY   BASS  KEYS  TEX
                    "Rhythm" to row(0.00f, 0.20f, 0.50f, 0.30f),
                    "Bassist" to row(0.15f, 0.00f, 0.55f, 0.30f),
                    "Keys" to row(0.10f, 0.15f, 0.00f, 0.75f),
                    "Textures" to row(0.10f, 0.15f, 0.75f, 0.00f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 6,
                barsPerLeadMin = 4, barsPerLeadMax = 8,
            ),
            tracks = listOf(
                // 0 Timpani roll — tuned-low filtered noise standing in for soft mallet hits.
                OrpheusEngine(
                    engineId = OrpheusEngineId.BD,
                    volume = 0.78f,
                    noteRangeLow = 36, noteRangeHigh = 48,
                    reverbBrightness = 0.35f, reverbSend = 0.55f, delaySend = 0.2f,
                    holdProbability = 0.4f, holdLengthMin = 2, holdLengthMax = 6,
                ).let { timpani ->
                    TrackVoice(
                        engineEdm = timpani,
                        engineSpace = timpani,
                        density = 0.28f,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.0f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM.copy(
                            moodHarmonics = MacroTarget(.6f, .8f),
                            spaceDecay = MacroTarget(.6f, .9f),
                        ),
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 1 Mallet bloom — MOD modal resonator, tuned-tom-on-vibraphone bloom.
                OrpheusEngine(
                    engineId = OrpheusEngineId.GRN,
                    volume = 0.70f,
                    noteRangeLow = 42, noteRangeHigh = 60,
                    reverbBrightness = 0.5f, reverbSend = 0.45f, delaySend = 0.3f,
                    holdProbability = 0.35f, holdLengthMin = 2, holdLengthMax = 5,
                ).let { bloom ->
                    TrackVoice(
                        engineEdm = bloom,
                        engineSpace = bloom,
                        density = 0.20f,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = -0.25f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 2 Cymbal swell — particle scatter for reversed-cymbal/wind feel.
                OrpheusEngine(
                    engineId = OrpheusEngineId.PAR,
                    volume = 0.75f,
                    harmonics = 0.85f,
                    timbre = 0.7f,
                    morph = 1f,
                    harmonicsMacroRange = .2f,
                    pinMorph = true,
                    pinHarmonics = true,
                    pinTimbre = true,
                    noteRangeLow = 60,
                    noteRangeHigh = 84,
                    reverbBrightness = 0.7f,
                    reverbSend = 0.55f,
                    delaySend = 0.45f,
                    modLfoRate = 0.05f,
                    modLfoDepth = 0.4f,
                    modLfoShape = 0.3f,
                    modLfoCoupling = 0.35f,
                    holdProbability = 0.7f,
                    holdLengthMin = 4,
                    holdLengthMax = 12,
                ).let { swell ->
                    TrackVoice(
                        engineEdm = swell,
                        engineSpace = swell,
                        density = 0.35f,
                        role = TrackRole.Melodic(),
                        pan = 0.25f,
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
                // 3 Pedal bass — PD warm round bass, STATIC root-only pedaling on the tonic.
                OrpheusEngine(
                    engineId = OrpheusEngineId.PD,
                    harmonicsMacroRange = .2f,
                    volume = 0.65f,
                    noteRangeLow = 28, noteRangeHigh = 40,
                    reverbBrightness = 0.35f, reverbSend = 0.25f, delaySend = 0.2f,
                    glideRate = 0.0f,
                    holdProbability = 0.95f, holdLengthMin = 12, holdLengthMax = 28,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass,
                        density = 0.10f,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.0f,
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 4 Organ drone — ENS string ensemble standing in for sustained organ chord drone.
                OrpheusEngine(
                    engineId = OrpheusEngineId.ENS,
                    volume = 0.65f,
                    harmonics = 0.4f,
                    timbre = 0.3f,
                    morph = 0.25f,
                    noteRangeLow = 48,
                    noteRangeHigh = 64,
                    reverbBrightness = 0.4f,
                    reverbSend = 0.45f,
                    delaySend = 0.3f,
                    modLfoRate = 0.02f,
                    modLfoDepth = 0.5f,
                    modLfoShape = 0.1f,
                    modLfoCoupling = 0.35f,
                    holdProbability = 0.85f,
                    holdLengthMin = 16,
                    holdLengthMax = 32,
                ).let { organ ->
                    TrackVoice(
                        engineEdm = organ,
                        engineSpace = organ,
                        density = 0.12f,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = -0.15f,
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.MELODIC.copy(
                            moodHarmonics = MacroTarget(0.4f, 0.4f),
                            moodTimbre = MacroTarget(0.3f, 0.3f),
                            spaceDecay = MacroTarget(0.25f, 0.25f),
                        ),
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 5 Vibraphone — MOD with brighter settings, plays the lick.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX2,
                    volume = 0.5f,
                    harmonics = 0.398f,
                    harmonicsModulation = .1f,
                    harmonicsMacroRange = .04f,
                    noteRangeLow = 60, noteRangeHigh = 80,
                    reverbBrightness = 0.6f, reverbSend = 0.45f, delaySend = 0.4f,
                    glideRate = 0.0f,
                    holdProbability = 0.75f, holdLengthMin = 3, holdLengthMax = 10,
                    lpgMode = LpgMode.SUSTAINED,
                    lpgDecay = .6f,
                    lpgColour = .2f,
                    modLfoRate = 0.2f,
                    modLfoDepth = 0.7f,
                    modLfoShape = 0.5f,
                    modLfoCoupling = 0.65f,
                ).let { vibraphone ->
                    TrackVoice(
                        engineEdm = vibraphone,
                        engineSpace = vibraphone,
                        density = 0.22f,
                        role = TrackRole.Melodic(
                            lickMode = LickMode.Fill,
                            chordFollow = ChordFollow.FIXED
                        ),
                        pan = 0.15f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.MUTATE,
                        evolution = Evolution(pitch = PitchEvolution.Contour()),
                    )
                },
                // 6 Mellotron pad — STR drone for low strings/Mellotron texture.
                OrpheusEngine(
                    engineId = OrpheusEngineId.STR,
                    volume = 0.50f,
                    harmonics = 0.55f,
                    timbre = 0.4f,
                    morph = 0.2f,
                    noteRangeLow = 43,
                    noteRangeHigh = 60,
                    reverbBrightness = 0.45f,
                    reverbSend = 0.5f,
                    delaySend = 0.3f,
                    modLfoRate = 0.03f,
                    modLfoDepth = 0.4f,
                    modLfoShape = 0.2f,
                    modLfoCoupling = 0.3f,
                    holdProbability = 0.9f,
                    holdLengthMin = 12,
                    holdLengthMax = 28,
                ).let { mellotron ->
                    TrackVoice(
                        engineEdm = mellotron,
                        engineSpace = mellotron,
                        density = 0.10f,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.2f,
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.MELODIC.copy(
                            moodHarmonics = MacroTarget(0.55f, 0.55f),
                            moodTimbre = MacroTarget(0.4f, 0.4f),
                            spaceDecay = MacroTarget(0.2f, 0.2f),
                        ),
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
                // 7 Whispered FX — SPK speech engine for the whispered vocal feel.
                OrpheusEngine(
                    engineId = OrpheusEngineId.SPK,
                    volume = 0.35f,
                    harmonics = 0.4f,
                    timbre = 0.5f,
                    morph = 0.35f,
                    noteRangeLow = 50,
                    noteRangeHigh = 67,
                    reverbBrightness = 0.5f,
                    reverbSend = 0.55f,
                    delaySend = 0.5f,
                    modLfoRate = 0.06f,
                    modLfoDepth = 0.4f,
                    modLfoShape = 0.4f,
                    modLfoCoupling = 0.4f,
                    holdProbability = 0.4f,
                    holdLengthMin = 3,
                    holdLengthMax = 9,
                ).let { whisper ->
                    TrackVoice(
                        engineEdm = whisper,
                        engineSpace = whisper,
                        density = 0.08f,
                        role = TrackRole.Melodic(),
                        pan = -0.3f,
                        envelopeProfile = EnvelopeProfile.WILD,
                        macroMap = TrackMacroMap.WILD.copy(
                            moodHarmonics = MacroTarget(0.4f, 0.4f),
                            moodTimbre = MacroTarget(0.5f, 0.5f),
                            spaceDecay = MacroTarget(0.35f, 0.35f),
                        ),
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            tension = TensionProfile(
                innerBars = 8,
                outerBars = 32,
                outerDepth = 0.55f,
                volume = 0.5f,
                timing = 0.05f,
                evolution = EvolutionTension(
                    timbreLow = 0.1f, timbreHigh = 0.4f, timbreProbability = 0.55f,
                    morphLow = 0.1f, morphHigh = 0.5f, morphProbability = 0.4f,
                    harmonicsLow = 0.15f, harmonicsHigh = 0.45f, harmonicsProbability = 0.3f,
                    attackPoint = 0.25f, releaseSpeed = 0.8f,
                ),
            ),
            effects = VibeEffects(
                delayTimeA = 0.5f,
                delayTimeB = 0.75f,
                delayFeedback = 0.55f,
                delayDamping = 0.55f,
                reverbSize = 0.85f,
                reverbDamping = 0.55f,
                reverbBrightness = 0.35f,
                deepFloor = 0.5f,
            ),
        )
    }
}
