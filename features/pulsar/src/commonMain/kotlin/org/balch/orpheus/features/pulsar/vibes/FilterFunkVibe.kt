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
import org.balch.orpheus.features.pulsar.TrackVoice
import org.balch.orpheus.features.pulsar.Vibe
import org.balch.orpheus.features.pulsar.VibeEffects
import org.balch.orpheus.features.pulsar.VibeProvider
import org.balch.orpheus.features.pulsar.bandMatrix
import org.balch.orpheus.features.pulsar.row

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class FilterFunkVibe : VibeProvider {
    override val vibe = Vibe(
        name = "Filter Funk",
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
        band = Band(
            members = listOf(
                BandMember("Drummer", listOf(0, 1, 2), alwaysActive = true,
                    loudness = 0.7f, creativity = 0.3f, swing = 0.03f, drag = -0.05f),
                BandMember("Bassist", listOf(3),
                    loudness = 0.8f, creativity = 0.5f, swing = 0.0f, drag = 0.05f),
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
        energy = 0.55f,
        complexity = 0.25f,
        space = 0.4f,
        mood = 0.35f,
        genre = GenreProfile(
            swingAmount = 0.03f,
            ghostProbability = 0.15f,
            noteRangeLow = 36,
            noteRangeHigh = 60,
            rhythmDensity = RhythmPattern.DENSE_16TH.density,
            progressionStyle = ProgressionStyle.POP,
            chordsPerBar = 4,
        ),
        tracks = listOf(
            TrackVoice(engineEdm = Engine.BD,  engineSpace = Engine.BD,  isPercussive = true,  volume = 0.90f, pan =  0.00f, density = 0.55f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM,  barStrategy = BarStrategy.REPEAT),
            TrackVoice(engineEdm = Engine.SD,  engineSpace = Engine.NSE, isPercussive = true,  volume = 0.45f, pan = -0.10f, density = 0.25f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM,  barStrategy = BarStrategy.MUTATE),
            TrackVoice(engineEdm = Engine.HH,  engineSpace = Engine.HH,  isPercussive = true,  volume = 0.55f, pan =  0.15f, density = 0.50f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM,  barStrategy = BarStrategy.MUTATE),
            TrackVoice(engineEdm = Engine.VCF, engineSpace = Engine.VCF, isPercussive = false, volume = 0.85f, pan =  0.00f, density = 0.50f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC, barStrategy = BarStrategy.MUTATE,
                noteRangeLow = 33, noteRangeHigh = 52, reverbBrightness = 0.25f),
            TrackVoice(engineEdm = Engine.VCF, engineSpace = Engine.WSH, isPercussive = false, volume = 0.50f, pan = -0.20f, density = 0.20f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC, barStrategy = BarStrategy.CALL_RESPONSE,
                noteRangeLow = 45, noteRangeHigh = 64, reverbBrightness = 0.5f, glideRate = 0.1f, useLick = true),
            TrackVoice(engineEdm = Engine.ENS, engineSpace = Engine.STR, isPercussive = false, volume = 0.30f, pan =  0.25f, density = 0.10f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT,  barStrategy = BarStrategy.INDEPENDENT,
                modLfoRate = 0.1f, modLfoDepth = 0.7f, modLfoShape = 0.4f, modLfoCoupling = 0.3f,
                holdProbability = 0.8f, holdLengthMin = 6, holdLengthMax = 16,
                reverbSend = 0.6f, delaySend = 0.4f,
                noteRangeLow = 38, noteRangeHigh = 57, reverbBrightness = 0.65f, glideRate = 0.45f),
            TrackVoice(engineEdm = Engine.NSE, engineSpace = Engine.PAR, isPercussive = true,  volume = 0.20f, pan = -0.25f, density = 0.08f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT,  barStrategy = BarStrategy.INDEPENDENT,
                modLfoRate = 0.15f, modLfoDepth = 0.6f, modLfoShape = 0.5f, modLfoCoupling = 0.2f,
                holdProbability = 0.6f, holdLengthMin = 4, holdLengthMax = 12,
                reverbSend = 0.5f, delaySend = 0.3f,
                noteRangeLow = 40, noteRangeHigh = 59, reverbBrightness = 0.6f),
            TrackVoice(engineEdm = Engine.GRN, engineSpace = Engine.GRN, isPercussive = false, volume = 0.15f, pan =  0.00f, density = 0.05f, envelopeProfile = EnvelopeProfile.WILD,    macroMap = TrackMacroMap.WILD,    barStrategy = BarStrategy.INDEPENDENT,
                modLfoRate = 0.08f, modLfoDepth = 0.5f, modLfoShape = 0.6f, modLfoCoupling = 0.4f,
                holdProbability = 0.5f, holdLengthMin = 3, holdLengthMax = 8,
                reverbSend = 0.4f, delaySend = 0.2f,
                noteRangeLow = 36, noteRangeHigh = 57, reverbBrightness = 0.5f, glideRate = 0.3f),
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
        arrangement = Arrangement(
            introIndex = 0,
            sections = listOf(
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
                // 1: build — rising energy, more complexity, filter opens up
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
                ),
                // 2: interlude — round-robin short solos, each instrument takes a turn
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
                        energy = 0.8f, complexity = 1.2f, space = 1.3f, mood = 1.1f,
                    ),
                    soloMode = SoloMode.LickBuilder(probability = 0.8f, mutationRate = 0.6f),
                ),
                // 3: drop — stripped to kick + bass, maximum impact on return
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
                ),
            ),
        ),
    )
}
