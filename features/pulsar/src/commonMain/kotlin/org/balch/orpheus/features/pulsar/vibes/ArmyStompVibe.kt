package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.Arrangement
import org.balch.orpheus.features.pulsar.Band
import org.balch.orpheus.features.pulsar.BandMember
import org.balch.orpheus.features.pulsar.BarStrategy
import org.balch.orpheus.features.pulsar.ChordComping
import org.balch.orpheus.features.pulsar.ChordFollow
import org.balch.orpheus.features.pulsar.CompingStyle
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
import org.balch.orpheus.features.pulsar.SectionInversion
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
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
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
        // 24-bar lick evolution arc — mutations stick within ±4 scale degrees of original.
        // Same tuning as Dust Groove: innerBars=8 for spurts every 8 bars, outerBars=24
        // for the super-cycle climax.
        lickMutation = 1.0f,
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
        // Tight DARK progression — march should stay on the E-centered voicings.
        progressionAnchor = ProgressionAnchor.EVERY_8,
        progressionDriftRange = 0.15f,
        tracks = listOf(
            TrackVoice(engineEdm = Engine.BD,  engineSpace = Engine.BD,  role = TrackRole.Percussive,  volume = 0.90f, pan =  0.00f, density = 0.50f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM,  barStrategy = BarStrategy.REPEAT),
            TrackVoice(engineEdm = Engine.SD,  engineSpace = Engine.SD,  role = TrackRole.Percussive,  volume = 0.55f, pan = -0.10f, density = 0.30f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM,  barStrategy = BarStrategy.REPEAT),
            TrackVoice(engineEdm = Engine.HH,  engineSpace = Engine.NSE, role = TrackRole.Percussive,  volume = 0.35f, pan =  0.15f, density = 0.25f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM,  barStrategy = BarStrategy.REPEAT),
            // Bass: disciplined march — ROOT_ONLY chord follow + REPEAT rhythm so the bass
            // locks to the chord root every stab. noteRangeLow raised from E1 to E2 to
            // avoid sub-bass mud; the octave pin will keep it in E2-D#3 territory.
            TrackVoice(engineEdm = Engine.VCF, engineSpace = Engine.VA,  role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY), volume = 0.85f, pan =  0.00f, density = 0.40f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC, barStrategy = BarStrategy.REPEAT,
                noteRangeLow = 40, noteRangeHigh = 52, reverbBrightness = 0.5f),
            TrackVoice(engineEdm = Engine.VCF, engineSpace = Engine.STR, role = TrackRole.Melodic(lickMode = LickMode.Squash), volume = 0.60f, pan =  0.00f, density = 0.20f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC, barStrategy = BarStrategy.CALL_RESPONSE,
                noteRangeLow = 45, noteRangeHigh = 67, reverbBrightness = 0.5f, glideRate = 0.05f), // Squash: CALL_RESPONSE owns bar 2
            // Track 5: Texture pad — density bumped so it can pulse under the breakdown.
            TrackVoice(engineEdm = Engine.GRN, engineSpace = Engine.GRN, role = TrackRole.Melodic(), volume = 0.30f, pan =  0.30f, density = 0.30f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT,  barStrategy = BarStrategy.INDEPENDENT,
                modLfoRate = 0.5f, modLfoDepth = 0.3f, modLfoShape = 0.3f, modLfoCoupling = 0.1f,
                holdProbability = 0.1f, holdLengthMin = 2, holdLengthMax = 4,
                reverbSend = 0.1f, delaySend = 0.15f,
                noteRangeLow = 36, noteRangeHigh = 60, reverbBrightness = 0.5f),
            TrackVoice(engineEdm = Engine.NSE, engineSpace = Engine.PAR, role = TrackRole.Percussive,  volume = 0.20f, pan = -0.30f, density = 0.10f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT,  barStrategy = BarStrategy.INDEPENDENT,
                modLfoRate = 0.4f, modLfoDepth = 0.25f, modLfoShape = 0.4f, modLfoCoupling = 0.1f,
                holdProbability = 0.05f, holdLengthMin = 2, holdLengthMax = 3,
                reverbSend = 0.1f, delaySend = 0.1f,
                noteRangeLow = 36, noteRangeHigh = 60, reverbBrightness = 0.5f),
            // Track 7: CHORDAL keys — subtle PAD during march sections, becomes
            // off-beat SKA_UPSTROKES stabs in the breakdown via section override.
            // CHD engine handles the chord voicing natively.
            TrackVoice(
                engineEdm = Engine.DX, engineSpace = Engine.DX,
                role = TrackRole.Chordal(comping = ChordComping(style = CompingStyle.PAD)),  // subtle in march
                timbre = .6f,
                harmonics = .3f,
                morph = .45f,
                volume = 0.30f, pan = 0.15f, density = 0.25f,
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
                noteRangeLow = 48, noteRangeHigh = 67,
                reverbBrightness = 0.5f,
            ),
        ),
        stepCount = 32,
        tension = TensionProfile(
            // Lick spurts every 8 bars (inner), outer super-cycle at 24 bars
            spurtChance = 0.15f,
            innerBars = 8, outerBars = 24, outerDepth = 0.6f,
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
                        SectionTransition(targetIndex = 1, weight = 0.4f),  // charge
                        SectionTransition(targetIndex = 2, weight = 0.25f), // solo
                        SectionTransition(targetIndex = 3, weight = 0.15f), // breakdown
                        SectionTransition(targetIndex = 4, weight = 0.2f),  // drift (style change)
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = MacroOverrides(complexity = 0.7f, space = 0.6f),
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
                // 3: breakdown — CHAOS. Crazy drums, bass stabs on root, keys SKA
                // off-stabs, texture pulses. In-your-face, tight, not stripped.
                Section(
                    name = "breakdown",
                    barsMin = 4, barsMax = 8,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.6f),
                        SectionTransition(targetIndex = 0, weight = 0.3f),
                        SectionTransition(targetIndex = 4, weight = 0.1f),
                    ),
                    recencyDecay = 0.5f,
                    // Energy cranked for crazy drum fills/variation; space pulled in tight
                    // for the in-your-face feel. Complexity maxed drives the drum chaos.
                    macroOverrides = MacroOverrides(
                        energy = 1.5f, complexity = 2.0f, space = 0.3f, mood = 1.3f,
                    ),
                    chordFollow = ChordFollow.ROOT_ONLY,  // bass stabs locked to root
                    compingStyle = CompingStyle.SKA_UPSTROKES,  // keys = off-beat stabs
                    compingInversion = SectionInversion.FIRST_INVERSION,  // lifted voicing for brightness
                ),
                // 4: drift — STYLE CHANGE. The army stops marching and starts wandering.
                // Bass breaks out of ROOT_ONLY and plays melodic lines; whole thing
                // feels suspended. Transitions back to march via charge most of the time.
                Section(
                    name = "drift",
                    barsMin = 6, barsMax = 10,
                    transitions = listOf(
                        SectionTransition(targetIndex = 0, weight = 0.5f),  // back to march
                        SectionTransition(targetIndex = 1, weight = 0.3f),  // or into charge (re-engage)
                        SectionTransition(targetIndex = 2, weight = 0.2f),  // or solo over the drift
                    ),
                    recencyDecay = 0.6f,
                    // Dreamy, spacious, more complex — feels like the march left the ground
                    macroOverrides = MacroOverrides(
                        energy = 0.55f, complexity = 1.8f, space = 1.6f, mood = 1.4f,
                    ),
                    // chordFollow = FOLLOW so the bass breaks out of ROOT_ONLY
                    // and plays its generated pattern, transposed through the chord changes
                    chordFollow = ChordFollow.FOLLOW,
                ),
            ),
        ),
    )
}
