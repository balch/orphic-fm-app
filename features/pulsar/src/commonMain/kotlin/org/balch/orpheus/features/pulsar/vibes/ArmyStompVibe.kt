package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.features.pulsar.Arrangement
import org.balch.orpheus.features.pulsar.Band
import org.balch.orpheus.features.pulsar.BandMember
import org.balch.orpheus.features.pulsar.BarStrategy
import org.balch.orpheus.features.pulsar.Engine
import org.balch.orpheus.features.pulsar.EnvelopeProfile
import org.balch.orpheus.features.pulsar.EnvelopeType
import org.balch.orpheus.features.pulsar.EvolutionTension
import org.balch.orpheus.features.pulsar.GenreProfile
import org.balch.orpheus.features.pulsar.Lick
import org.balch.orpheus.features.pulsar.LickMode
import org.balch.orpheus.features.pulsar.LickStep
import org.balch.orpheus.features.pulsar.MacroOverrides
import org.balch.orpheus.features.pulsar.ProgressionStyle
import org.balch.orpheus.features.pulsar.RhythmPattern
import org.balch.orpheus.features.pulsar.RootNote
import org.balch.orpheus.features.pulsar.ScaleType
import org.balch.orpheus.features.pulsar.Section
import org.balch.orpheus.features.pulsar.SectionTransition
import org.balch.orpheus.features.pulsar.SoloMode
import org.balch.orpheus.features.pulsar.TensionProfile
import org.balch.orpheus.features.pulsar.TonalTension
import org.balch.orpheus.features.pulsar.TrackMacroMap
import org.balch.orpheus.features.pulsar.TrackRole
import org.balch.orpheus.features.pulsar.TrackVoice
import org.balch.orpheus.features.pulsar.Vibe
import org.balch.orpheus.features.pulsar.VibeEffects
import org.balch.orpheus.features.pulsar.VibeProvider
import org.balch.orpheus.features.pulsar.bandMatrix
import org.balch.orpheus.features.pulsar.row

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class ArmyStompVibe : VibeProvider {
    override val vibe = Vibe(
        name = "Army Stomp",
        bpm = 110f,
        envelopeType = EnvelopeType.BLEND,
        rootNote = RootNote.E,
        scaleType = ScaleType.MINOR,
        lick = Lick(
            steps = listOf(
                LickStep(scaleDegree = 4, duration = 0.25f, velocity = 0.90f),   // B(5th) — grace, slides into:
                LickStep(scaleDegree = 0, duration = 1.75f, velocity = 0.95f),   // E — HEAVY opening, held long
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),    // E — pickup
                LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.85f),    // G — the jump
                LickStep(scaleDegree = 0, duration = 1.5f, velocity = 0.90f),    // E — back, held
                LickStep(scaleDegree = -1, duration = 1.0f, velocity = 0.80f),   // D — descent starts
                LickStep(scaleDegree = -2, duration = 1.0f, velocity = 0.75f),   // C — deliberate
                LickStep(scaleDegree = -3, duration = 1.5f, velocity = 0.85f),   // B low — resolve, ring out
            ),
        ),
        lickMutation = 0.15f,
        band = Band(
            members = listOf(
                BandMember("Drummer", listOf(0, 1, 2), alwaysActive = true,
                    loudness = 0.7f, creativity = 0.3f, swing = 0.02f, drag = -0.1f),
                BandMember("Bassist", listOf(3),
                    loudness = 0.8f, creativity = 0.4f, swing = 0.0f, drag = 0.05f),
                BandMember("Keys", listOf(4),
                    loudness = 0.5f, creativity = 0.6f, swing = 0.0f, drag = 0.0f),
                BandMember("FX", listOf(5, 6, 7),
                    loudness = 0.3f, creativity = 0.8f, swing = 0.0f, drag = 0.1f),
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
        complexity = 0.2f,
        space = 0.6f,
        mood = 0.3f,
        genre = GenreProfile(
            swingAmount = 0.02f,
            ghostProbability = 0.08f,
            noteRangeLow = 36,
            noteRangeHigh = 60,
            rhythmDensity = RhythmPattern.BACKBEAT.density,
            progressionStyle = ProgressionStyle.DARK,
            chordsPerBar = 2,
        ),
        tracks = listOf(
            TrackVoice(engineEdm = Engine.BD,  engineSpace = Engine.BD,  role = TrackRole.PERCUSSIVE,  volume = 0.90f, pan =  0.00f, density = 0.50f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM,  barStrategy = BarStrategy.REPEAT),
            TrackVoice(engineEdm = Engine.SD,  engineSpace = Engine.SD,  role = TrackRole.PERCUSSIVE,  volume = 0.55f, pan = -0.10f, density = 0.30f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM,  barStrategy = BarStrategy.REPEAT),
            TrackVoice(engineEdm = Engine.HH,  engineSpace = Engine.NSE, role = TrackRole.PERCUSSIVE,  volume = 0.35f, pan =  0.15f, density = 0.25f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM,  barStrategy = BarStrategy.REPEAT),
            TrackVoice(engineEdm = Engine.VCF, engineSpace = Engine.VA,  role = TrackRole.MELODIC, volume = 0.85f, pan =  0.00f, density = 0.40f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC, barStrategy = BarStrategy.MUTATE,
                noteRangeLow = 28, noteRangeHigh = 52, reverbBrightness = 0.5f),
            TrackVoice(engineEdm = Engine.VCF, engineSpace = Engine.STR, role = TrackRole.MELODIC, volume = 0.60f, pan =  0.00f, density = 0.20f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC, barStrategy = BarStrategy.CALL_RESPONSE,
                noteRangeLow = 45, noteRangeHigh = 67, reverbBrightness = 0.5f, glideRate = 0.05f,
                lickMode = LickMode.Squash), // Squash: CALL_RESPONSE owns bar 2
            TrackVoice(engineEdm = Engine.ENS, engineSpace = Engine.STR, role = TrackRole.MELODIC, volume = 0.30f, pan =  0.30f, density = 0.15f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT,  barStrategy = BarStrategy.INDEPENDENT,
                modLfoRate = 0.5f, modLfoDepth = 0.3f, modLfoShape = 0.3f, modLfoCoupling = 0.1f,
                holdProbability = 0.1f, holdLengthMin = 2, holdLengthMax = 4,
                reverbSend = 0.1f, delaySend = 0.15f,
                noteRangeLow = 36, noteRangeHigh = 60, reverbBrightness = 0.5f),
            TrackVoice(engineEdm = Engine.NSE, engineSpace = Engine.PAR, role = TrackRole.PERCUSSIVE,  volume = 0.20f, pan = -0.30f, density = 0.10f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT,  barStrategy = BarStrategy.INDEPENDENT,
                modLfoRate = 0.4f, modLfoDepth = 0.25f, modLfoShape = 0.4f, modLfoCoupling = 0.1f,
                holdProbability = 0.05f, holdLengthMin = 2, holdLengthMax = 3,
                reverbSend = 0.1f, delaySend = 0.1f,
                noteRangeLow = 36, noteRangeHigh = 60, reverbBrightness = 0.5f),
            TrackVoice(engineEdm = Engine.MOD, engineSpace = Engine.MOD, role = TrackRole.PERCUSSIVE,  volume = 0.10f, pan =  0.00f, density = 0.05f, envelopeProfile = EnvelopeProfile.WILD,    macroMap = TrackMacroMap.WILD,    barStrategy = BarStrategy.INDEPENDENT,
                modLfoRate = 0.6f, modLfoDepth = 0.2f, modLfoShape = 0.5f, modLfoCoupling = 0.15f,
                holdProbability = 0.0f, holdLengthMin = 2, holdLengthMax = 4,
                reverbSend = 0.15f, delaySend = 0.1f,
                noteRangeLow = 36, noteRangeHigh = 65, reverbBrightness = 0.5f, glideRate = 0.05f),
        ),
        stepCount = 32,
        tension = TensionProfile(
            spurtChance = 0.12f,
            innerBars = 4, outerBars = 16, outerDepth = 0.6f,
            volume = 0.4f,
            tonal = TonalTension(octaveShift = true, halfLick = true, chromaticPassing = 0.15f),
            timing = 0.2f,
            evolution = EvolutionTension(
                timbreLow = 0.25f, timbreHigh = 0.55f, timbreProbability = 0.8f,
                morphLow = 0.3f, morphHigh = 0.55f, morphProbability = 0.4f,
                harmonicsLow = 0.35f, harmonicsHigh = 0.50f, harmonicsProbability = 0.25f,
                attackPoint = 0.3f, releaseSpeed = 0.5f,
            ),
        ),
        effects = VibeEffects(
            delayTimeA = 0.15f,
            delayTimeB = 0.3f,
            delayFeedback = 0.25f,
            delayDamping = 0.3f,
            reverbSize = 0.3f,
            reverbDamping = 0.4f,
            reverbBrightness = 0.5f,
        ),
        arrangement = Arrangement(
            introIndex = 0,
            sections = listOf(
                // 0: march — tight, disciplined, locked drums
                Section(
                    name = "march",
                    barsMin = 8, barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.5f),
                        SectionTransition(targetIndex = 2, weight = 0.3f),
                        SectionTransition(targetIndex = 3, weight = 0.2f),
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = MacroOverrides(complexity = 0.7f, space = 0.6f),  // march IS the base energy
                ),
                // 1: charge — high energy, more fills, aggressive
                Section(
                    name = "charge",
                    barsMin = 4, barsMax = 8,
                    transitions = listOf(
                        SectionTransition(targetIndex = 0, weight = 0.3f),
                        SectionTransition(targetIndex = 2, weight = 0.5f),
                        SectionTransition(targetIndex = 3, weight = 0.2f),
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = MacroOverrides(
                        energy = 1.5f, complexity = 1.5f, space = 0.4f, mood = 1.3f,
                    ),
                    soloMode = SoloMode.LongFill(probability = 0.4f),
                ),
                // 2: solo — extended spotlight, VCF bass or keys rip
                Section(
                    name = "solo",
                    barsMin = 8, barsMax = 12,
                    transitions = listOf(
                        SectionTransition(targetIndex = 0, weight = 0.4f),
                        SectionTransition(targetIndex = 3, weight = 0.4f),
                        SectionTransition(targetIndex = 1, weight = 0.2f),
                    ),
                    recencyDecay = 0.4f,
                    macroOverrides = MacroOverrides(
                        energy = 0.8f, complexity = 1.3f, space = 1.3f, mood = 1.2f,
                    ),
                    soloMode = SoloMode.LickBuilder(probability = 0.8f, mutationRate = 0.6f),
                ),
                // 3: breakdown — drums only, stripped, building tension
                Section(
                    name = "breakdown",
                    barsMin = 4, barsMax = 8,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.7f),
                        SectionTransition(targetIndex = 0, weight = 0.3f),
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = MacroOverrides(
                        energy = 0.5f, complexity = 0.4f, space = 0.3f, mood = 0.6f,
                    ),
                ),
            ),
        ),
    )
}
