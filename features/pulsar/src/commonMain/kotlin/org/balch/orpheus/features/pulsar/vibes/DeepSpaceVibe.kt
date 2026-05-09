package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.core.audio.OrpheusEngineId
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
import org.balch.orpheus.features.pulsar.models.MacroOverrides
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
import org.balch.orpheus.features.pulsar.models.chordMatrix
import org.balch.orpheus.features.pulsar.models.row

// Not Ready For PrimeTime
//@Inject
//@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class DeepSpaceVibe : VibeProvider {
    override val vibe = Vibe(
        name = "Deep Space",
        bpm = 55f,
        envelopeType = EnvelopeType.TIDES,
        rootNote = RootNote.C_SHARP,
        scaleType = ScaleType.MINOR,    // C# natural minor
        // Lick: Echoes bass riff — walking figure in C# minor
        // Scale degrees in C# minor: 0=C#, 1=D#, 2=E, 3=F#, 4=G#, 5=A, 6=B
        // Pattern: C#(hold)...D#(up)...E(step)...F#(reach)...E(back)...D#(settle)...C#(home)...B(dip below)
        lick = Lick(
            steps = listOf(
                LickStep(scaleDegree = 0, duration = 1.5f, velocity = 0.7f),   // C# — root, long hold
                LickStep(scaleDegree = 1, duration = 1.0f, velocity = 0.6f),   // D# — step up
                LickStep(scaleDegree = 2, duration = 0.8f, velocity = 0.65f),  // E — natural 3rd
                LickStep(scaleDegree = 3, duration = 1.2f, velocity = 0.7f),   // F# — reach to 4th
                LickStep(scaleDegree = 2, duration = 0.8f, velocity = 0.6f),   // E — walk back
                LickStep(scaleDegree = 1, duration = 0.8f, velocity = 0.55f),  // D# — settle
                LickStep(scaleDegree = 0, duration = 1.2f, velocity = 0.65f),  // C# — home
                LickStep(scaleDegree = 6, duration = 1.0f, velocity = 0.5f),   // B — dip below root
            ),
            loopLength = 8,
        ),
        lickMutation = 0.6f,
        lickOctave = 3,        // octave 3 (MIDI 37-49, deep bass)
        band = Band(
            members = listOf(
                BandMember("Rhythm", listOf(0, 1, 2), alwaysActive = true,
                    loudness = 0.7f, creativity = 0.4f, swing = 0.08f, drag = -0.05f),
                BandMember("Bassist", listOf(3),
                    loudness = 0.5f, creativity = 0.5f, swing = 0.0f, drag = 0.1f),
                BandMember("Keys", listOf(4),
                    loudness = 0.5f, creativity = 0.6f, swing = 0.0f, drag = 0.1f),
                BandMember("Guitar", listOf(5),
                    loudness = 0.4f, creativity = 0.7f, swing = 0.0f, drag = 0.15f),
                BandMember("Textures", listOf(6, 7),
                    loudness = 0.3f, creativity = 0.8f, swing = 0.0f, drag = 0.15f),
            ),
            handoffMatrix = bandMatrix(
                //             RHYTHM BASS  KEYS  GUITAR TEX
                "Rhythm"   to row(0.00f, 0.40f, 0.25f, 0.25f, 0.10f),
                "Bassist"  to row(0.15f, 0.00f, 0.35f, 0.35f, 0.15f),
                "Keys"     to row(0.10f, 0.35f, 0.00f, 0.40f, 0.15f),
                "Guitar"   to row(0.10f, 0.30f, 0.35f, 0.00f, 0.25f),
                "Textures" to row(0.10f, 0.30f, 0.25f, 0.35f, 0.00f),
            ),
            pullInMatrix = bandMatrix(
                //             RHYTHM BASS  KEYS  GUITAR TEX
                "Rhythm"   to row(0.00f, 0.30f, 0.20f, 0.20f, 0.05f),
                "Bassist"  to row(0.20f, 0.00f, 0.40f, 0.30f, 0.10f),
                "Keys"     to row(0.15f, 0.35f, 0.00f, 0.35f, 0.10f),
                "Guitar"   to row(0.10f, 0.25f, 0.30f, 0.00f, 0.20f),
                "Textures" to row(0.10f, 0.20f, 0.20f, 0.30f, 0.00f),
            ),
            pullInBarsMin = 2, pullInBarsMax = 4,
            barsPerLeadMin = 2, barsPerLeadMax = 6,
        ),
        energy = 0.35f,
        complexity = 0.75f,
        space = 0.85f,
        mood = 0.6f,
        deep = 0.7f,
        genre = GenreProfile(
            swingAmount = 0.6f,
            ghostProbability = 0.06f,
            noteRangeLow = 37,
            noteRangeHigh = 73,
            rhythmDensity = RhythmPattern.SPARSE.density,
            progressionStyle = ProgressionStyle.DRONE,
            chordsPerBar = 3,
            // Glacial drift rooted in i: 75% stay, neighbors only, occasional VII pull
            chordTransitionMatrix = chordMatrix(
                //       I     ii    iii   IV    V     vi    VII
                "I"   to row(0.75f, 0.05f, 0.02f, 0.05f, 0.02f, 0.03f, 0.08f),
                "ii"  to row(0.12f, 0.70f, 0.08f, 0.02f, 0.03f, 0.02f, 0.03f),
                "iii" to row(0.04f, 0.10f, 0.70f, 0.08f, 0.02f, 0.04f, 0.02f),
                "IV"  to row(0.06f, 0.02f, 0.08f, 0.72f, 0.05f, 0.02f, 0.05f),
                "V"   to row(0.10f, 0.03f, 0.02f, 0.06f, 0.70f, 0.04f, 0.05f),
                "vi"  to row(0.06f, 0.04f, 0.04f, 0.02f, 0.06f, 0.72f, 0.06f),
                "VII" to row(0.14f, 0.02f, 0.02f, 0.04f, 0.03f, 0.05f, 0.70f),
            ),
        ),
        progressionAnchor = ProgressionAnchor.EVERY_8,
        progressionDriftRange = 0.2f,
        tracks = listOf(
            // ═══════════════════════════════════════════════════════════
            // Rhythm section — sparse, textural
            // ═══════════════════════════════════════════════════════════

            // 0 KICK: Sonar ping — the Leslie piano ping that opens Echoes
            // Modal resonator, sparse deep hits, iconic single-note punctuation
            OrpheusEngine(
                engineId = OrpheusEngineId.MOD,
                volume = 0.90f,
                harmonics = 0.35f, timbre = 0.25f, morph = 0.55f,
                noteRangeLow = 37, noteRangeHigh = 49,
                reverbBrightness = 0.5f, reverbSend = 0.65f, delaySend = 0.3f,
            ).let { kick ->
                TrackVoice(
                    engineEdm = kick,
                    engineSpace = kick,
                    density = 0.29f, role = TrackRole.Melodic(), pan = 0.0f,
                    envelopeProfile = EnvelopeProfile.RHYTHM,
                    macroMap = TrackMacroMap.MELODIC,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // 1 PERC: Ride shimmer — light metallic texture, like distant cymbals
            OrpheusEngine(
                engineId = OrpheusEngineId.STR,
                volume = 0.9f,
                harmonics = 0.45f, timbre = 0.35f, morph = 0.15f,
                noteRangeLow = 55, noteRangeHigh = 67,
                reverbBrightness = 0.55f, delaySend = 0.65f, reverbSend = 0.3f,
            ).let { perc ->
                TrackVoice(
                    engineEdm = perc,
                    engineSpace = perc,
                    density = 0.42f, role = TrackRole.Melodic(), pan = -0.25f,
                    envelopeProfile = EnvelopeProfile.RHYTHM,
                    macroMap = TrackMacroMap.MELODIC,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // 2 HH: Wind/atmosphere — crystalline particle scatter, like wind through a canyon
            OrpheusEngine(
                engineId = OrpheusEngineId.PAR,
                volume = 0.9f,
                harmonics = 1f, timbre = 0.5f, morph = 1f,
                reverbBrightness = 0.75f, reverbSend = 0.3f, delaySend = 0.25f,
            ).let { wind ->
                TrackVoice(
                    engineEdm = wind,
                    engineSpace = wind,
                    density = 0.7f, role = TrackRole.Melodic(), pan = 0.3f,
                    envelopeProfile = EnvelopeProfile.DRONE,
                    macroMap = TrackMacroMap.EFFECT,
                    barStrategy = BarStrategy.REPEAT,
                )
            },

            // ═══════════════════════════════════════════════════════════
            // Melodic — the Echoes core
            // ═══════════════════════════════════════════════════════════

            // 3 BASS: The Echoes bass riff — PD for warm dark bass, lick-driven.
            // chordFollow = FIXED so the iconic C#-D#-E-F#-E-D#-C#-B riff plays
            // exactly as written — chord progression moves via the KEYS drone above.
            OrpheusEngine(
                engineId = OrpheusEngineId.PD,
                volume = 0.22f,
                harmonics = 0.5f, timbre = 0.55f, morph = 0.1f,
                noteRangeLow = 37, noteRangeHigh = 49,
                reverbBrightness = 0.5f, reverbSend = 0.2f, delaySend = 0.3f,
                glideRate = 0.5f,
                holdProbability = 0.9f, holdLengthMin = 6, holdLengthMax = 16,
            ).let { bass ->
                TrackVoice(
                    engineEdm = bass,
                    engineSpace = bass,
                    role = TrackRole.Melodic(lickMode = LickMode.Fill, chordFollow = ChordFollow.FIXED),
                    pan = 0.0f, density = 0.14f,
                    envelopeProfile = EnvelopeProfile.MELODIC,
                    macroMap = TrackMacroMap.MELODIC,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // 4 KEYS: Evolving drone — VA (warm organ) at high energy, ENS (lush pad) at low
            // Long sustains with slow timbral drift. LFO sweeps harmonics/timbre for builds.
            OrpheusEngine(
                engineId = OrpheusEngineId.VA,
                volume = 0.70f,
                harmonics = 0.25f, timbre = 0.2f, morph = 0.1f,
                noteRangeLow = 49, noteRangeHigh = 61,
                reverbBrightness = 0.45f, reverbSend = 0.35f, delaySend = 0.25f,
                modLfoRate = 0.015f, modLfoDepth = 0.7f, modLfoShape = 0.1f, modLfoCoupling = 0.4f,
                glideRate = 0.6f,
                holdProbability = 0.5f, holdLengthMin = 6, holdLengthMax = 22,
            ).let { keys ->
                TrackVoice(
                    engineEdm = keys,
                    engineSpace = keys.copy(engineId = OrpheusEngineId.ENS),
                    density = 0.12f, role = TrackRole.Melodic(), pan = -0.1f,
                    envelopeProfile = EnvelopeProfile.DRONE,
                    macroMap = TrackMacroMap.MELODIC,
                    barStrategy = BarStrategy.INDEPENDENT,
                    evolution = Evolution(pitch = PitchEvolution.Contour()),
                )
            },

            // ═══════════════════════════════════════════════════════════
            // Texture / FX — atmosphere and color
            // ═══════════════════════════════════════════════════════════

            // 5 PAD (Guitar): Gilmour guitar — resonant STR, high harmonics, delay+reverb
            OrpheusEngine(
                engineId = OrpheusEngineId.STR,
                volume = 0.65f,
                harmonics = 0.9f, timbre = 0.5f, morph = 0.1f,
                noteRangeLow = 49, noteRangeHigh = 67,
                reverbBrightness = 0.55f, glideRate = 0.7f,
                modLfoRate = 0.04f, modLfoDepth = 0.35f, modLfoShape = 0.15f, modLfoCoupling = 0.2f,
                holdProbability = 0.9f, holdLengthMin = 8, holdLengthMax = 24,
                reverbSend = 0.3f, delaySend = 0.3f,
            ).let { guitar ->
                TrackVoice(
                    engineEdm = guitar,
                    engineSpace = guitar,
                    role = TrackRole.Melodic(), pan = 0.2f, density = 0.22f,
                    envelopeProfile = EnvelopeProfile.DRONE,
                    macroMap = TrackMacroMap.MELODIC,
                    barStrategy = BarStrategy.INDEPENDENT,
                )
            },
            // 6 TEXTURE: Seagull/void sounds — particle clouds for the atonal middle section
            OrpheusEngine(
                engineId = OrpheusEngineId.PAR,
                volume = 0.50f,
                harmonics = 0.3f, timbre = 0.45f, morph = 0.25f,
                noteRangeLow = 43, noteRangeHigh = 61,
                reverbBrightness = 0.7f, glideRate = 0.4f,
                modLfoRate = 0.07f, modLfoDepth = 0.5f, modLfoShape = 0.35f, modLfoCoupling = 0.4f,
                holdProbability = 0.75f, holdLengthMin = 4, holdLengthMax = 14,
                reverbSend = 0.4f, delaySend = 0.25f,
            ).let { texture ->
                TrackVoice(
                    engineEdm = texture,
                    engineSpace = texture,
                    role = TrackRole.Melodic(), pan = -0.35f, density = 0.16f,
                    envelopeProfile = EnvelopeProfile.WILD,
                    macroMap = TrackMacroMap.WILD,
                    barStrategy = BarStrategy.INDEPENDENT,
                )
            },
            // 7 FX: Echo repeats — delayed resonant pings that fade into the distance
            OrpheusEngine(
                engineId = OrpheusEngineId.MOD,
                volume = 0.40f,
                harmonics = 0.5f, timbre = 0.4f, morph = 0.15f,
                noteRangeLow = 55, noteRangeHigh = 73,
                reverbBrightness = 0.8f,
                reverbSend = 0.45f, delaySend = 0.35f,
            ).let { fx ->
                TrackVoice(
                    engineEdm = fx,
                    engineSpace = fx,
                    role = TrackRole.Melodic(), pan = 0.35f, density = 0.08f,
                    envelopeProfile = EnvelopeProfile.WILD,
                    macroMap = TrackMacroMap.WILD,
                    barStrategy = BarStrategy.INDEPENDENT,
                )
            },
        ),
        stepCount = 32,
        tension = TensionProfile(
            innerBars = 8,
            outerBars = 32,
            outerDepth = 0.45f,
            volume = 0.2f,
            timing = 0.05f,
            evolution = EvolutionTension(
                timbreLow = 0.1f, timbreHigh = 0.45f, timbreProbability = 0.6f,
                morphLow = 0.1f, morphHigh = 0.5f, morphProbability = 0.4f,
                harmonicsLow = 0.15f, harmonicsHigh = 0.4f, harmonicsProbability = 0.3f,
                attackPoint = 0.2f, releaseSpeed = 0.85f,
            ),
        ),
        effects = VibeEffects(
            delayTimeA = 0.4f,
            delayTimeB = 0.55f,
            delayFeedback = 0.5f,
            delayDamping = 0.6f,
            reverbSize = 0.8f,
            reverbDamping = 0.6f,
            reverbBrightness = 0.4f,
            deepFloor = 0.5f,  // spacious vibe — effects always present
        ),
        arrangement = Arrangement(
            introIndex = 0,
            sections = listOf(
                // 0: ping — the opening, sparse sonar pings into silence
                Section(
                    name = "ping",
                    barsMin = 4, barsMax = 6,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.9f),
                        SectionTransition(targetIndex = 3, weight = 0.1f),
                    ),
                    recencyDecay = 0.4f,
                    macroOverrides = MacroOverrides(
                        energy = 0.4f, complexity = 0.3f, space = 1.2f, mood = 0.8f,
                    ),
                ),
                // 1: groove — the main theme, bass riff drives, organ swells, guitar enters
                Section(
                    name = "groove",
                    barsMin = 8, barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 2, weight = 0.35f),
                        SectionTransition(targetIndex = 3, weight = 0.25f),
                        SectionTransition(targetIndex = 0, weight = 0.1f),
                    ),
                    recencyDecay = 0.6f,
                    macroOverrides = MacroOverrides(
                        energy = 1.3f, complexity = 1.0f, space = 0.9f,
                    ),
                    soloMode = SoloMode.LickBuilder(probability = 0.7f, mutationRate = 0.6f),
                ),
                // 2: void — the atonal middle section, wind and seagulls
                Section(
                    name = "void",
                    barsMin = 6, barsMax = 10,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.4f),
                        SectionTransition(targetIndex = 3, weight = 0.5f),
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = MacroOverrides(
                        energy = 0.3f, complexity = 2.0f, space = 1.6f, mood = 1.4f,
                    ),
                ),
                // 3: return — reprise of the groove, fuller and more triumphant
                Section(
                    name = "return",
                    barsMin = 6, barsMax = 12,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.3f),
                        SectionTransition(targetIndex = 2, weight = 0.3f),
                        SectionTransition(targetIndex = 0, weight = 0.2f),
                    ),
                    recencyDecay = 0.6f,
                    macroOverrides = MacroOverrides(
                        energy = 1.6f, complexity = 1.2f, space = 1.0f, mood = 1.1f,
                    ),
                    soloMode = SoloMode.LickBuilder(probability = 0.8f, mutationRate = 0.6f),
                ),
            ),
        ),
    )
}
