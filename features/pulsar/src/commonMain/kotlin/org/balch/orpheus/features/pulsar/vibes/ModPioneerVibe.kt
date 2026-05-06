package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
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
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Mod Pioneer — moody, driving beat-group energy with melodic bass and sharp guitar stabs.
 *
 * Inspired by the mid-60s British beat sound, this vibe features a melodic bassline
 * that carries the harmonic movement, paired with sharp, percussive guitar chords.
 * The mood is slightly minor/melancholic but maintains a steady, driving tempo.
 * Transitions are crisp, and the tension builds through rhythmic density.
 */
// Not Ready For PrimeTime
//@Inject
//@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class ModPioneerVibe : VibeProvider {
    // i — bVII — bVI — v (Am — G — F — E in A minor). Descending minor move.
    private val verseProgression = chords(0, 6, 5, 4)

    // Chorus lifts slightly with a I — III — IV move
    private val chorusProgression = chords(0, 2, 3, 4)

    private val chordsPerBar = 2

    override val vibe = Vibe(
        name = "Mod Pioneer",
        bpm = 124f,
        envelopeType = EnvelopeType.AD,
        rootNote = RootNote.A,
        scaleType = ScaleType.MINOR,
        // The signature descending bass/lead figure
        lick = Lick(
            steps = listOf(
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.70f),
                LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0.85f),
                LickStep(scaleDegree = -2, duration = 0.5f, velocity = 0.85f),
                LickStep(scaleDegree = -3, duration = 1.0f, velocity = 0.90f),
            ),
            loopLength = 8,
        ),
        lickMutation = 0.35f,
        band = Band(
            members = listOf(
                BandMember("Drummer", listOf(0, 1, 2), alwaysActive = true, creativity = 0.15f),
                BandMember("Bassist", listOf(3), creativity = 0.45f), // Bass is melodic
                BandMember("Guitarist", listOf(4, 5), creativity = 0.35f),
                BandMember("Organist", listOf(6), creativity = 0.30f),
            ),
            handoffMatrix = bandMatrix(
                "Drummer"   to row(0.00f, 0.40f, 0.40f, 0.20f),
                "Bassist"   to row(0.15f, 0.00f, 0.50f, 0.35f),
                "Guitarist" to row(0.10f, 0.50f, 0.00f, 0.40f),
                "Organist"  to row(0.10f, 0.45f, 0.45f, 0.00f),
            ),
            pullInMatrix = bandMatrix(
                "Drummer"   to row(0.00f, 0.50f, 0.30f, 0.20f),
                "Bassist"   to row(0.40f, 0.00f, 0.40f, 0.20f),
                "Guitarist" to row(0.20f, 0.40f, 0.00f, 0.40f),
                "Organist"  to row(0.20f, 0.30f, 0.50f, 0.00f),
            ),
        ),
        energy = 0.60f,
        complexity = 0.50f,
        space = 0.40f,
        mood = 0.45f, // Slightly moody
        deep = 0.35f,
        genre = GenreProfile(
            swingAmount = 0.08f,
            ghostProbability = 0.18f,
            noteRangeLow = 45,
            noteRangeHigh = 72,
            rhythmDensity = RhythmPattern.BACKBEAT.density,
            progressionStyle = ProgressionStyle.SAD, // Descending
            chordsPerBar = chordsPerBar,
            customProgression = verseProgression,
        ),
        progressionAnchor = ProgressionAnchor.EVERY_4,
        progressionDriftRange = 0.12f,
        tracks = listOf(
            // 0: Kick
            OrpheusEngine(engineId = OrpheusEngineId.ANALOG_BASS_DRUM, volume = 0.80f).let { kick ->
                TrackVoice(
                    engineEdm = kick,
                    engineSpace = kick,
                    role = TrackRole.Percussive,
                    density = 0.40f,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // 1: Snare
            OrpheusEngine(engineId = OrpheusEngineId.ANALOG_SNARE_DRUM, volume = 0.70f).let { snare ->
                TrackVoice(
                    engineEdm = snare,
                    engineSpace = snare,
                    role = TrackRole.Percussive,
                    density = 0.35f,
                    barStrategy = BarStrategy.FILL,
                )
            },
            // 2: Hats
            OrpheusEngine(engineId = OrpheusEngineId.METALLIC_HI_HAT, volume = 0.50f).let { hat ->
                TrackVoice(
                    engineEdm = hat,
                    engineSpace = hat,
                    role = TrackRole.Percussive,
                    density = 0.70f,
                    barStrategy = BarStrategy.MUTATE,
                )
            },
            // 3: Melodic Bass - the "driving" force
            OrpheusEngine(
                engineId = OrpheusEngineId.OSC,
                volume = 0.85f,
                harmonics = 0.0f,
                morph = 0f,
                timbre = .2f,
                noteRangeLow = 45,
                noteRangeHigh = 57,
                glideRate = 0.20f,
            ).let { bass ->
                TrackVoice(
                    engineEdm = bass,
                    engineSpace = bass,
                    role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
                    density = 0.45f,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // 4: Sharp Guitar Stabs
            OrpheusEngine(
                engineId = OrpheusEngineId.SIX_OP_FM_2, // Fender 1
                volume = 0.55f,
                harmonics = 0.05f, // DX2 Idx 1: Fender 1
                reverbSend = 0.20f,
            ).let { stabs ->
                TrackVoice(
                    engineEdm = stabs,
                    engineSpace = stabs.copy(engineId = OrpheusEngineId.WAVESHAPING),
                    role = TrackRole.Chordal(
                        chordFollow = ChordFollow.FOLLOW,
                        comping = ChordComping(
                            style = CompingStyle.SKA_UPSTROKES, // Crisp off-beats
                            arpSpeed = 0.90f,
                        )
                    ),
                    pan = -0.25f,
                    density = 0.25f,
                )
            },
            // 5: Rhythm Guitar
            OrpheusEngine(
                engineId = OrpheusEngineId.SIX_OP_FM_2, // Guit acous
                volume = 0.45f,
                harmonics = 0.35f, // DX2 Idx 11: Guit acous
            ).let { rhythm ->
                TrackVoice(
                    engineEdm = rhythm,
                    engineSpace = rhythm.copy(engineId = OrpheusEngineId.CHORD),
                    role = TrackRole.Chordal(
                        chordFollow = ChordFollow.FOLLOW,
                        comping = ChordComping(style = CompingStyle.ROCK_DOWNBEATS)
                    ),
                    pan = 0.25f,
                    density = 0.20f,
                    barStrategy = BarStrategy.MUTATE,
                )
            },
            // 6: Triple-saw chord stab (Braids) - on EDM, organ pad on Space
            // HARMONICS knob picks the chord interval inside the Braids triple-saw.
            OrpheusEngine(
                engineId = OrpheusEngineId.BRAIDS_TRIPLE_SAW,
                volume = 0.40f,
                harmonics = 0.45f, // mid chord interval on Braids triple-saw
                holdProbability = 0.75f,
                reverbSend = 0.40f,
            ).let { stab ->
                TrackVoice(
                    engineEdm = stab,
                    engineSpace = stab.copy(engineId = OrpheusEngineId.STRING_MACHINE),
                    role = TrackRole.Chordal(
                        chordFollow = ChordFollow.FOLLOW,
                        comping = ChordComping(style = CompingStyle.PAD)
                    ),
                    pan = 0.15f,
                    density = 0.15f,
                )
            },
            // 7: Tambourine
            OrpheusEngine(engineId = OrpheusEngineId.MODAL, volume = 0.45f).let { tamb ->
                TrackVoice(
                    engineEdm = tamb,
                    engineSpace = tamb.copy(engineId = OrpheusEngineId.PARTICLE),
                    role = TrackRole.Percussive,
                    density = 0.35f,
                    barStrategy = BarStrategy.INDEPENDENT,
                )
            },
        ),
        effects = VibeEffects(
            delayTimeA = 0.375f, // Dotted 8th
            delayFeedback = 0.35f,
            reverbSize = 0.50f,
            reverbBrightness = 0.55f,
        ),
        arrangement = Arrangement(
            sections = listOf(
                Section(
                    name = "intro",
                    barsMin = 4, barsMax = 4,
                    transitions = listOf(SectionTransition(1, 1f)),
                    macroOverrides = MacroOverrides(energy = 0.7f, space = 1.2f),
                ),
                Section(
                    name = "verse",
                    barsMin = 8, barsMax = 12,
                    transitions = listOf(SectionTransition(2, 0.7f), SectionTransition(3, 0.3f)),
                ),
                Section(
                    name = "chorus",
                    barsMin = 8, barsMax = 8,
                    transitions = listOf(SectionTransition(1, 1f)),
                    customProgression = chorusProgression,
                    macroOverrides = MacroOverrides(energy = 1.2f, complexity = 1.3f),
                ),
                Section(
                    name = "breakdown",
                    barsMin = 4, barsMax = 8,
                    transitions = listOf(SectionTransition(1, 1f)),
                    macroOverrides = MacroOverrides(energy = 0.4f, space = 1.5f),
                ),
            )
        )
    )
}
