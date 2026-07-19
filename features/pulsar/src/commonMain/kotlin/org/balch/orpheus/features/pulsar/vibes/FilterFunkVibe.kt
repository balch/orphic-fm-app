package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.FilterAnomaly
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.ArpDirection
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingFills
import org.balch.orpheus.features.pulsar.models.CompingHumanization
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.FillType
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
import org.balch.orpheus.features.pulsar.models.SectionInversion
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.SoloMode
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TonalTension
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class FilterFunkVibe : VibeProvider {
    override val name: String = "Filter Funk"

    private val sectionList by lazy {
        listOf(
            // 0: groove — main funk pocket, tight 16ths
            Section(
                name = "groove",
                barsMin = 8, barsMax = 16,
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 0.4f),
                    SectionTransition(targetIndex = 2, weight = 0.35f),
                    SectionTransition(targetIndex = 3, weight = 0.25f),
                ),
                recencyDecay = 0.5f,
                macroOverrides = null,  // groove IS the baseline
            ),
            // 1: build — rising energy, more complexity, filter opens up.
            //    The lead PEDALS the hook here (track 4 → per-track FIXED) instead of
            //    FOLLOWing the POP I—V—vi—IV chords. With FOLLOW, the active chord
            //    offset is added then folded into the lead's lowest octave, so the
            //    V (+7) and IV (+5) leaps fold to a lurching up-a-4th / down-a-4th
            //    contour four times a bar — the "disjointed" feel at the song's peak.
            //    Pedaling keeps the hook in-key while bass + keys carry the motion.
            //    A low-mutation LickBuilder hands the pedaled hook to the lead member
            //    so the build gets a recognizable, developing focal melody.
            Section(
                name = "build",
                barsMin = 4, barsMax = 8,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.6f),
                    SectionTransition(targetIndex = 0, weight = 0.2f),
                    SectionTransition(targetIndex = 3, weight = 0.2f),
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 1.3f, complexity = 1.5f, space = 0.8f, mood = 1.3f,
                ),
                soloMode = SoloMode.LickBuilder(probability = 0.7f, mutationRate = 0.30f),
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
                ),
            ),
            // 2: interlude — round-robin short solos, each instrument takes a turn.
            //    Tuned for body: previously this stacked an energy CUT (0.8) + heavy
            //    space (1.3) + 0.8 solo probability, so the section thinned out — one
            //    instrument soloing over a ducked, washed-out, low-energy backing felt
            //    weak. Now energy sits at full (1.0), space is only lightly lifted
            //    (1.1), and the solo probability drops to 0.7 so more full-band passages
            //    survive between solos. (The softened solo ducking in the DSP keeps the
            //    backing present during the solos themselves.)
            Section(
                name = "interlude",
                barsMin = 8, barsMax = 16,
                transitions = listOf(
                    SectionTransition(targetIndex = 0, weight = 0.5f),
                    SectionTransition(targetIndex = 3, weight = 0.3f),
                    SectionTransition(targetIndex = 1, weight = 0.2f),
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 1.0f, complexity = 1.2f, space = 1.1f, mood = 1.1f,
                ),
                soloMode = SoloMode.LickBuilder(probability = 0.7f, mutationRate = 0.6f),
            ),
            // 3: drop — stripped to kick + bass droning on G for impact on return
            Section(
                name = "drop",
                barsMin = 2, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 0, weight = 0.8f),
                    SectionTransition(targetIndex = 1, weight = 0.2f),
                ),
                recencyDecay = 0.4f,
                macroOverrides = MacroOverrides(
                    energy = 0.3f, complexity = 0.2f, space = 0.4f, mood = 0.6f,
                ),
                chordFollow = ChordFollow.FIXED,  // bass locks to G during the drop
            ),
            // 4: outro — low-energy wind-down on a fixed 2-chord progression
            Section(
                name = "outro",
                barsMin = 4, barsMax = 6,
                macroOverrides = MacroOverrides(
                    energy = 0.5f, complexity = 0.1f, space = 0.6f, mood = 1.6f,
                ),
                chordFollow = ChordFollow.FIXED,
                customProgression = chords(0, 3)
            ),
        )
    }

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            album = Album.STEALTH,
            arrangement = Arrangement(
                introIndex = 0,
                sections = sectionList,
                outroIndex = sectionList.lastIndex,
                lengthSeconds = 130 .. 150,
            ),
            bpm = 110f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.G,
            scaleType = ScaleType.MINOR,
            lick = Lick(
                steps = listOf(
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.80f),
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.85f),
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.80f),
                    LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.90f),
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.85f),
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.80f),
                    LickStep(scaleDegree = -1, duration = 1.0f, velocity = 0.75f),
                ),
            ),
            lickMutation = 0.6f,
            anomalies = listOf(
                FilterAnomaly(probability = .1f)
            ),
            band = Band(
                members = listOf(
                    BandMember(
                        "Drummer", listOf(0, 1, 2), alwaysActive = true,
                        loudness = 0.7f, creativity = 0.3f
                    ),
                    BandMember(
                        "Bassist", listOf(3),
                        loudness = 0.8f, creativity = 0.5f
                    ),
                    BandMember(
                        "Keys", listOf(4),
                        loudness = 0.5f, creativity = 0.5f
                    ),
                    BandMember(
                        "FX", listOf(5, 6, 7),
                        loudness = 0.3f, creativity = 0.7f
                    ),
                ),
                handoffMatrix = bandMatrix(
                    //            DRUM  BASS  KEYS  FX
                    "Drummer" to row(0.00f, 0.30f, 0.35f, 0.10f),
                    "Bassist" to row(0.25f, 0.00f, 0.35f, 0.15f),
                    "Keys" to row(0.20f, 0.35f, 0.00f, 0.20f),
                    "FX" to row(0.15f, 0.30f, 0.30f, 0.00f),
                ),
                pullInMatrix = bandMatrix(
                    //            DRUM  BASS  KEYS  FX
                    "Drummer" to row(0.00f, 0.25f, 0.20f, 0.05f),
                    "Bassist" to row(0.20f, 0.00f, 0.35f, 0.10f),
                    "Keys" to row(0.15f, 0.35f, 0.00f, 0.10f),
                    "FX" to row(0.10f, 0.20f, 0.20f, 0.00f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 4,
                barsPerLeadMin = 2, barsPerLeadMax = 6,
            ),
            energy = 0.51f,
            complexity = 0.25f,
            space = 0.4f,
            mood = 0.45f,
            genre = GenreProfile(
                swingAmount = 0.03f,
                ghostProbability = 0.15f,
                noteRangeLow = 36,
                noteRangeHigh = 60,
                rhythmDensity = RhythmPattern.DENSE_16TH.density,
                progressionStyle = ProgressionStyle.POP,
                chordsPerBar = 4,
            ),
            // Tight POP drift; 4 chords/bar is already busy, don't need Markov wandering on top.
            progressionAnchor = ProgressionAnchor.EVERY_4,
            progressionDriftRange = 0.15f,
            tracks = listOf(
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.90f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.55f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT
                    )
                },
                OrpheusEngine(engineId = OrpheusEngineId.SD, volume = 0.45f).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare.copy(engineId = OrpheusEngineId.NSE),
                        role = TrackRole.Percussive,
                        pan = -0.10f,
                        density = 0.25f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE
                    )
                },
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.55f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f,
                        density = 0.50f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE
                    )
                },
                // Bass: locked funk pocket with ROOT_ONLY chord follow — 4 chords/bar means
                // the bass chases chord roots beat-by-beat (classic funk). noteRangeLow 33 → 40
                // lifts it to E2 for punchy funk bass instead of A1 sub.
                OrpheusEngine(
                    engineId = OrpheusEngineId.VCF,
                    volume = 0.85f,
                    noteRangeLow = 40,
                    noteRangeHigh = 52,
                    reverbBrightness = 0.25f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.00f,
                        density = 0.50f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                OrpheusEngine(
                    engineId = OrpheusEngineId.TRIPLE_RING_MOD,
                    volume = .5f,
                    noteRangeLow = 45,
                    noteRangeHigh = 64,
                    reverbBrightness = 0.5f,
                    glideRate = 0.1f,
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead.copy(
                            engineId = OrpheusEngineId.WSH,
                        ),
                        role = TrackRole.Melodic(lickMode = LickMode.Squash), // Squash: CALL_RESPONSE owns bar 2
                        pan = -0.20f,
                        density = 0.20f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.CALL_RESPONSE,
                    )
                },
                OrpheusEngine(
                    engineId = OrpheusEngineId.ENS,
                    volume = 0.10f,
                    modLfoRate = 0.1f,
                    modLfoDepth = 0.7f,
                    modLfoShape = 0.4f,
                    modLfoCoupling = 0.3f,
                    holdProbability = 0.8f,
                    holdLengthMin = 6,
                    holdLengthMax = 16,
                    reverbSend = 0.6f,
                    delaySend = 0.4f,
                    noteRangeLow = 38,
                    noteRangeHigh = 57,
                    reverbBrightness = 0.65f,
                    glideRate = 0.45f,
                ).let { keys ->
                    TrackVoice(
                        engineEdm = keys,
                        engineSpace = keys.copy(engineId = OrpheusEngineId.TRN),
                        role = TrackRole.Chordal(
                            comping = ChordComping(
                                style = CompingStyle.FUNK_STABS,
                                arpMode = ArpMode.AUTO,
                                arpSpeed = 0.1f,
                                arpDirection = ArpDirection.UP_DOWN,
                                sectionInversion = SectionInversion.SECOND_INVERSION,
                                humanization = CompingHumanization(
                                    dropProbability = .1f,
                                    ghostProbability = .1f,
                                    octaveJumpProbability = .2f,
                                    extensionProbability = .2f
                                ),
                                fills = CompingFills(
                                    everyNBars = 6,
                                    fillType = FillType.DOUBLE_TIME,
                                    skipProbability = .1f
                                ),
                            ),
                        ),
                        pan = 0.25f,
                        density = 0.10f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // PAR grain percussion: sparse particle hits scattered across the bar.
                // Plaits Particle macros: timbre = particle density (lower = more
                // audible individual grains), morph = filter type (below 0.5 =
                // diffuse all-pass, above = resonant band-pass), harmonics =
                // frequency randomization. Keeping all three in the low-quarter
                // puts us in the "dust cloud of discrete grains" zone.
                OrpheusEngine(
                    engineId = OrpheusEngineId.PAR,
                    volume = 0.20f,
                    timbre = 0.28f,         // sparse particle density
                    morph = 0.25f,          // all-pass network = diffuse, grainy
                    harmonics = 0.25f,      // mild frequency randomization
                    noteRangeLow = 48,
                    noteRangeHigh = 72,      // lift up so grains sit above the bass
                    reverbSend = 0.35f,      // grains love reverb — smears them
                    reverbBrightness = 0.7f,
                ).let { grains ->
                    TrackVoice(
                        engineEdm = grains,
                        engineSpace = grains,
                        role = TrackRole.Percussive,
                        pan = -0.25f,
                        density = 0.35f,        // fewer triggers = each grain audible
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
                OrpheusEngine(
                    engineId = OrpheusEngineId.GRN,
                    volume = 0.15f,
                    modLfoRate = 0.08f,
                    modLfoDepth = 0.5f,
                    modLfoShape = 0.6f,
                    modLfoCoupling = 0.4f,
                    holdProbability = 0.5f,
                    holdLengthMin = 3,
                    holdLengthMax = 8,
                    reverbSend = 0.4f,
                    delaySend = 0.2f,
                    noteRangeLow = 36,
                    noteRangeHigh = 57,
                    reverbBrightness = 0.5f,
                    glideRate = 0.3f,
                ).let { wild ->
                    TrackVoice(
                        engineEdm = wild,
                        engineSpace = wild,
                        role = TrackRole.Melodic(),
                        pan = 0.00f,
                        density = 0.05f,
                        envelopeProfile = EnvelopeProfile.WILD,
                        macroMap = TrackMacroMap.WILD,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            stepCount = 32,
            tension = TensionProfile(
                spurtChance = 0.12f,
                innerBars = 4, outerBars = 16, outerDepth = 0.5f,
                volume = 0.35f,
                tonal = TonalTension(chromaticPassing = 0.08f),
                timing = 0.2f,
                evolution = EvolutionTension(
                    timbreLow = 0.25f, timbreHigh = 0.60f, timbreProbability = 0.9f,
                    morphLow = 0.3f, morphHigh = 0.55f, morphProbability = 0.6f,
                    harmonicsLow = 0.35f, harmonicsHigh = 0.55f, harmonicsProbability = 0.3f,
                    attackPoint = 0.4f, releaseSpeed = 0.35f,
                ),
            ),
            effects = VibeEffects(
                delayTimeA = 0.18f,
                delayTimeB = 0.35f,
                delayFeedback = 0.3f,
                delayDamping = 0.4f,
                reverbSize = 0.4f,
                reverbDamping = 0.4f,
                reverbBrightness = 0.7f,
            ),
        )
    }
}
