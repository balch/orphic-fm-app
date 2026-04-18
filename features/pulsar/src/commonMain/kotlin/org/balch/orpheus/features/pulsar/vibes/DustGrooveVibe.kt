package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.features.pulsar.Arrangement
import org.balch.orpheus.features.pulsar.Band
import org.balch.orpheus.features.pulsar.BandMember
import org.balch.orpheus.features.pulsar.BarStrategy
import org.balch.orpheus.features.pulsar.ChordFollow
import org.balch.orpheus.features.pulsar.Engine
import org.balch.orpheus.features.pulsar.EnvelopeProfile
import org.balch.orpheus.features.pulsar.EnvelopeType
import org.balch.orpheus.features.pulsar.EvolutionTension
import org.balch.orpheus.features.pulsar.GenreProfile
import org.balch.orpheus.features.pulsar.Lick
import org.balch.orpheus.features.pulsar.LickMode
import org.balch.orpheus.features.pulsar.LickStep
import org.balch.orpheus.features.pulsar.MacroOverrides
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
class DustGrooveVibe : VibeProvider {
    override val vibe = Vibe(
        name = "Dust Groove",
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
            TrackVoice(
                engineEdm = Engine.BD,
                engineSpace = Engine.BD,
                role = TrackRole.Percussive,
                volume = 0.85f,
                pan = 0.00f,
                density = 0.50f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.REPEAT
            ),
            TrackVoice(
                engineEdm = Engine.SD,
                engineSpace = Engine.SD,
                role = TrackRole.Percussive,
                volume = 0.65f,
                pan = -0.10f,
                density = 0.40f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.FILL
            ),
            TrackVoice(
                engineEdm = Engine.HH,
                engineSpace = Engine.HH,
                role = TrackRole.Percussive,
                volume = 0.60f,
                pan = 0.20f,
                density = 0.65f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.MUTATE
            ),
            // Bass: locked pattern + ROOT_ONLY chord follow keeps the dusty pocket tight.
            // noteRangeLow 33 (A1) preserved — the dusty warmth is the character.
            TrackVoice(
                engineEdm = Engine.VCF,
                engineSpace = Engine.VA,
                role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                volume = 0.80f,
                pan = 0.00f,
                density = 0.55f,
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
                barStrategy = BarStrategy.REPEAT,
                noteRangeLow = 33,
                noteRangeHigh = 52,
                reverbBrightness = 0.35f,
            ),
            TrackVoice(
                engineEdm = Engine.FM,
                engineSpace = Engine.FM,
                role = TrackRole.Melodic(lickMode = LickMode.Squash), // Squash: CALL_RESPONSE owns bar 2
                volume = 0.45f,
                pan = -0.20f,
                density = 0.35f,
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
                barStrategy = BarStrategy.CALL_RESPONSE,
                noteRangeLow = 45,
                noteRangeHigh = 64,
                reverbBrightness = 0.45f,
                glideRate = 0.1f,
            ),
            TrackVoice(
                engineEdm = Engine.WSH,
                engineSpace = Engine.ADD,
                role = TrackRole.Melodic(lickMode = LickMode.Squash), // Squash: CALL_RESPONSE owns bar 2
                volume = 0.30f,
                pan = 0.30f,
                density = 0.20f,
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
                barStrategy = BarStrategy.CALL_RESPONSE,
                modLfoRate = 0.3f,
                modLfoDepth = 0.5f,
                modLfoShape = 0.35f,
                modLfoCoupling = 0.2f,
//                holdProbability = 0.45f,
//                holdLengthMin = 4,
//                holdLengthMax = 10,
                reverbSend = 0.35f,
                delaySend = 0.25f,
                noteRangeLow = 38,
                noteRangeHigh = 59,
                reverbBrightness = 0.55f,
                glideRate = 0.2f,
            ),
            TrackVoice(
                engineEdm = Engine.GRN,
                engineSpace = Engine.WTB,
                role = TrackRole.Melodic(),
                volume = 0.30f,
                pan = -0.30f,
                density = 0.15f,
                envelopeProfile = EnvelopeProfile.EFFECT,
                macroMap = TrackMacroMap.EFFECT,
                barStrategy = BarStrategy.INDEPENDENT,
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
                glideRate = 0.25f
            ),
            TrackVoice(
                engineEdm = Engine.PAR,
                engineSpace = Engine.NSE,
                role = TrackRole.Percussive,
                volume = 0.20f,
                pan = 0.35f,
                density = 0.08f,
                envelopeProfile = EnvelopeProfile.WILD,
                macroMap = TrackMacroMap.WILD,
                barStrategy = BarStrategy.REPEAT,
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
                glideRate = 0.15f
            ),
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
