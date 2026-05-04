package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.Arrangement
import org.balch.orpheus.features.pulsar.Band
import org.balch.orpheus.features.pulsar.BandMember
import org.balch.orpheus.features.pulsar.BarStrategy
import org.balch.orpheus.features.pulsar.ChordComping
import org.balch.orpheus.features.pulsar.ChordFollow
import org.balch.orpheus.features.pulsar.CompingHumanization
import org.balch.orpheus.features.pulsar.CompingStyle
import org.balch.orpheus.features.pulsar.EnvelopeType
import org.balch.orpheus.features.pulsar.GenreProfile
import org.balch.orpheus.features.pulsar.Lick
import org.balch.orpheus.features.pulsar.LickMode
import org.balch.orpheus.features.pulsar.LickStep
import org.balch.orpheus.features.pulsar.MacroOverrides
import org.balch.orpheus.features.pulsar.OrpheusEngine
import org.balch.orpheus.features.pulsar.ProgressionAnchor
import org.balch.orpheus.features.pulsar.ProgressionStyle
import org.balch.orpheus.features.pulsar.RhythmPattern
import org.balch.orpheus.features.pulsar.RootNote
import org.balch.orpheus.features.pulsar.ScaleType
import org.balch.orpheus.features.pulsar.Section
import org.balch.orpheus.features.pulsar.SectionTransition
import org.balch.orpheus.features.pulsar.SoloMode
import org.balch.orpheus.features.pulsar.TensionProfile
import org.balch.orpheus.features.pulsar.TonalTension
import org.balch.orpheus.features.pulsar.TrackRole
import org.balch.orpheus.features.pulsar.TrackVoice
import org.balch.orpheus.features.pulsar.Vibe
import org.balch.orpheus.features.pulsar.VibeEffects
import org.balch.orpheus.features.pulsar.VibeProvider
import org.balch.orpheus.features.pulsar.bandMatrix
import org.balch.orpheus.features.pulsar.chords
import org.balch.orpheus.features.pulsar.row

/**
 * Sixties Rebel — driving garage rock with a signature fuzzed-out riff.
 *
 * This vibe captures the raw energy of 60s rock: a syncopated, gritty lead riff
 * over a steady backbeat and driving bass. The harmonic structure is simple
 * and modal, shifting between the tonic and the subdominant. The fuzz lead
 * is the star, while acoustic strums and piano provide a solid foundation.
 */
// Not Ready For PrimeTime
//@Inject
//@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class SixtiesRebelVibe : VibeProvider {
    // I — IV (E — A in E Mixolydian). Simple back-and-forth.
    private val verseProgression = chords(0, 3, 0, 3)
    private val chordsPerBar = 1

    // Chorus pushes to the V before resolving back.
    private val chorusProgression = chords(0, 4, 0, 3)
    private val chorusChordsPerBar = 2

    override val vibe = Vibe(
        name = "Sixties Rebel",
        bpm = 136f,
        envelopeType = EnvelopeType.AD,
        rootNote = RootNote.E,
        scaleType = ScaleType.MIXOLYDIAN,
        // The signature riff: B-B B-C#-D  D-D-D-C#-B
        // scaleDegree 4=B, 5=C#, 6=D (Mixolydian b7)
        lick = Lick(
            steps = listOf(
                LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.9f),
                LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.8f),
                LickStep(scaleDegree = 4, duration = 0.25f, velocity = 0.85f),
                LickStep(scaleDegree = 5, duration = 0.25f, velocity = 0.85f),
                LickStep(scaleDegree = 6, duration = 0.5f, velocity = 1.0f),
                LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.9f),
                LickStep(scaleDegree = 6, duration = 0.25f, velocity = 0.85f),
                LickStep(scaleDegree = 5, duration = 0.25f, velocity = 0.85f),
                LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.9f),
            ),
            loopLength = 8, // 2 bars
        ),
        lickMutation = 0.45f, // Higher base mutation allows section overrides to "dissolve" the riff
        band = Band(
            members = listOf(
                BandMember("Drums", listOf(0, 1, 2, 7), alwaysActive = true, creativity = 0.15f),
                BandMember("Bass", listOf(3), creativity = 0.20f),
                BandMember("Guitar", listOf(4, 5), creativity = 0.45f),
                BandMember("Keys", listOf(6), creativity = 0.35f),
            ),
            handoffMatrix = bandMatrix(
                "Drums"    to row(0.00f, 0.20f, 0.60f, 0.20f),
                "Bass"    to row(0.10f, 0.00f, 0.70f, 0.20f),
                "Guitar"  to row(0.05f, 0.25f, 0.00f, 0.70f),
                "Keys" to row(0.10f, 0.20f, 0.70f, 0.00f),
            ),
            pullInMatrix = bandMatrix(
                "Drums"    to row(0.00f, 0.40f, 0.30f, 0.30f),
                "Bass"    to row(0.30f, 0.00f, 0.50f, 0.20f),
                "Guitar"  to row(0.20f, 0.40f, 0.00f, 0.40f),
                "Keys" to row(0.20f, 0.30f, 0.50f, 0.00f),
            ),
        ),
        energy = 0.65f,
        complexity = 0.40f,
        space = 0.35f, // Rock is generally drier
        mood = 0.70f,  // Bright and rebellious
        deep = 0.30f,
        genre = GenreProfile(
            swingAmount = 0.06f,
            ghostProbability = 0.15f,
            noteRangeLow = 40,
            noteRangeHigh = 76,
            rhythmDensity = RhythmPattern.BACKBEAT.density,
            progressionStyle = ProgressionStyle.POP,
            chordsPerBar = chordsPerBar,
            customProgression = verseProgression,
        ),
        progressionAnchor = ProgressionAnchor.EVERY_4,
        progressionDriftRange = 0.15f,
        tracks = listOf(
            // 0: Kick - driving pulse
            OrpheusEngine(engineId = OrpheusEngineId.ANALOG_BASS_DRUM, volume = 0.85f).let { kick ->
                TrackVoice(
                    engineEdm = kick,
                    engineSpace = kick,
                    role = TrackRole.Percussive,
                    density = 0.45f,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // 1: Snare - crackling backbeat
            OrpheusEngine(
                engineId = OrpheusEngineId.ANALOG_SNARE_DRUM,
                volume = 0.75f,
                harmonics = 0.45f,
                timbre = 0.60f,
                reverbSend = 0.15f,
            ).let { snare ->
                TrackVoice(
                    engineEdm = snare,
                    engineSpace = snare,
                    role = TrackRole.Percussive,
                    density = 0.35f,
                    barStrategy = BarStrategy.FILL,
                )
            },
            // 2: Hats - steady 8ths
            OrpheusEngine(engineId = OrpheusEngineId.METALLIC_HI_HAT, volume = 0.55f).let { hat ->
                TrackVoice(
                    engineEdm = hat,
                    engineSpace = hat,
                    role = TrackRole.Percussive,
                    density = 0.60f,
                    barStrategy = BarStrategy.MUTATE,
                )
            },
            // 3: Bass - driving root notes
            OrpheusEngine(
                engineId = OrpheusEngineId.SIX_OP_FM, // Solid bass
                volume = 0.80f,
                harmonics = 0.02f, // DX Idx 0: Solid bass
                noteRangeLow = 40,
                noteRangeHigh = 52,
            ).let { bass ->
                TrackVoice(
                    engineEdm = bass,
                    engineSpace = bass.copy(engineId = OrpheusEngineId.VIRTUAL_ANALOG),
                    role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                    density = 0.50f,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // 4: Fuzz Lead - the signature lick
            OrpheusEngine(
                engineId = OrpheusEngineId.WAVESHAPING, // Gritty fuzz
                volume = 0.65f,
                harmonics = 0.75f, // Gritty harmonics
                timbre = 0.70f,
                morph = 0.50f,
                noteRangeLow = 52,
                noteRangeHigh = 76,
                reverbSend = 0.20f,
                delaySend = 0.15f,
                glideRate = 0.15f, // Slight slide into notes
            ).let { lead ->
                TrackVoice(
                    engineEdm = lead,
                    engineSpace = lead.copy(engineId = OrpheusEngineId.FM),
                    role = TrackRole.Melodic(lickMode = LickMode.Fill),
                    pan = 0.15f,
                    density = 0.25f,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // 5: Acoustic Strums
            OrpheusEngine(
                engineId = OrpheusEngineId.SIX_OP_FM_2, // Guit acous
                volume = 0.50f,
                harmonics = 0.35f, // DX2 Idx 11: Guit acous
            ).let { strums ->
                TrackVoice(
                    engineEdm = strums,
                    engineSpace = strums.copy(engineId = OrpheusEngineId.CHORD),
                    role = TrackRole.Chordal(
                        chordFollow = ChordFollow.FOLLOW,
                        comping = ChordComping(
                            style = CompingStyle.ROCK_DOWNBEATS,
                            humanization = CompingHumanization(dropProbability = 0.1f)
                        )
                    ),
                    pan = -0.20f,
                    density = 0.20f,
                    barStrategy = BarStrategy.MUTATE,
                )
            },
            // 6: Piano - staccato accents
            OrpheusEngine(
                engineId = OrpheusEngineId.SIX_OP_FM_2, // Steinway
                volume = 0.45f,
                harmonics = 0.32f, // DX2 Idx 10: Steinway
                reverbSend = 0.30f,
            ).let { piano ->
                TrackVoice(
                    engineEdm = piano,
                    engineSpace = piano.copy(engineId = OrpheusEngineId.VIRTUAL_ANALOG),
                    role = TrackRole.Chordal(
                        chordFollow = ChordFollow.FOLLOW,
                        comping = ChordComping(style = CompingStyle.FUNK_STABS)
                    ),
                    pan = 0.25f,
                    density = 0.15f,
                    barStrategy = BarStrategy.FILL,
                )
            },
            // 7: Tambourine / Percussion
            OrpheusEngine(
                engineId = OrpheusEngineId.MODAL,
                volume = 0.40f,
                reverbSend = 0.25f,
            ).let { perc ->
                TrackVoice(
                    engineEdm = perc,
                    engineSpace = perc.copy(engineId = OrpheusEngineId.PARTICLE),
                    role = TrackRole.Percussive,
                    density = 0.30f,
                    barStrategy = BarStrategy.INDEPENDENT,
                )
            },
        ),
        stepCount = 32,
        tension = TensionProfile(
            innerBars = 8,
            outerBars = 32,
            spurtChance = 0.12f,
            volume = 0.25f,
            tonal = TonalTension(chromaticPassing = 0.10f),
        ),
        effects = VibeEffects(
            delayTimeA = 0.25f,
            delayTimeB = 0.50f,
            delayFeedback = 0.40f,
            reverbSize = 0.45f,
            reverbBrightness = 0.60f,
        ),
        arrangement = Arrangement(
            introIndex = 0,
            sections = listOf(
                Section(
                    name = "intro",
                    barsMin = 2, barsMax = 2,
                    transitions = listOf(SectionTransition(1, 1f)),
                    macroOverrides = MacroOverrides(energy = 1.3f, complexity = 0.7f, mood = 1.3f),
                    chordsPerBar = 2,
                    // Just fuzz + drums
                ),
                Section(
                    name = "verse",
                    barsMin = 8, barsMax = 8,
                    transitions = listOf(
                        SectionTransition(2, 0.6f),
                        SectionTransition(3, 0.4f)
                    ),
                ),
                Section(
                    name = "chorus",
                    barsMin = 4, barsMax = 4,
                    transitions = listOf(SectionTransition(1, 1f)),
                    macroOverrides = MacroOverrides(energy = .5f, complexity = 2.0f, mood = .4f),
                    customProgression = chorusProgression,
                    chordsPerBar = chorusChordsPerBar,
                ),
                Section(
                    name = "solo",
                    barsMin = 8, barsMax = 16,
                    transitions = listOf(SectionTransition(1, 1f)),
                    soloMode = SoloMode.Jam(probability = 0.8f),
                    macroOverrides = MacroOverrides(energy = 1.4f, complexity = 1.5f),
                ),
            )
        )
    )
}
