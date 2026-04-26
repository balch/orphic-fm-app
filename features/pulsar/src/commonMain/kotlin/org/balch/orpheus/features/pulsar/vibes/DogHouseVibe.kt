package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.ArpDirection
import org.balch.orpheus.features.pulsar.ArpMode
import org.balch.orpheus.features.pulsar.Arrangement
import org.balch.orpheus.features.pulsar.Band
import org.balch.orpheus.features.pulsar.BandMember
import org.balch.orpheus.features.pulsar.BarStrategy
import org.balch.orpheus.features.pulsar.ChordComping
import org.balch.orpheus.features.pulsar.ChordFollow
import org.balch.orpheus.features.pulsar.CompingFills
import org.balch.orpheus.features.pulsar.CompingHumanization
import org.balch.orpheus.features.pulsar.CompingStyle
import org.balch.orpheus.features.pulsar.Engine
import org.balch.orpheus.features.pulsar.EnvelopeProfile
import org.balch.orpheus.features.pulsar.EnvelopeType
import org.balch.orpheus.features.pulsar.EvolutionTension
import org.balch.orpheus.features.pulsar.FillType
import org.balch.orpheus.features.pulsar.GenreProfile
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
import org.balch.orpheus.features.pulsar.chords
import org.balch.orpheus.features.pulsar.row

@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class DogHouseVibe : VibeProvider {
    // Slow blues: hang on tonic for 4 bars, touch IV for 2, resolve through V.
    // I-I-I-I-IV-IV-I-V — the 8-bar "hold your ground then move" form.
    private val mainProgression = chords(0, 0, 0, 0, 3, 3, 0, 4)
    private val chordsPerBar = 1

    private val chorusProgression = chords(3, 3, 0, 0)
    private val chorusChordsPerBar = 2

    // Per-edge transitionBars precedent: name the *musical role* the ramp serves,
    // not the bar count. Slow blues lives on dynamic swings — every edge gets a
    // ramp, sized to the size of the energy change.
    //
    //   bluesLiftBars     — standard climb between adjacent-energy sections
    //                       (intro -> verse, verse -> chorus, solo -> chorus).
    //   bluesyDropBars    — slow blues exhale out of high-energy sections
    //                       (chorus -> solo / breakdown, verse -> breakdown).
    //   bigBluesLiftBars  — THE iconic moment: breakdown -> chorus. Since
    //                       `breakdown` is locked to 4 bars (barsMin == barsMax),
    //                       the entire breakdown becomes one long anticipation
    //                       buildup — energy crawls 0.4 -> 1.4 across all 4 bars.
    private val bluesLiftBars = 2
    private val bluesyDropBars = 3
    private val bigBluesLiftBars = 4

    override val vibe = Vibe(
        name = "Dog House",
        bpm = 85f,
        envelopeType = EnvelopeType.BLEND,
        rootNote = RootNote.E,
        scaleType = ScaleType.PHRYGIAN,
        band = Band(
            members = listOf(
                BandMember(
                    "Drummer", listOf(0, 1, 2), alwaysActive = true,
                    loudness = 0.7f, creativity = 0.3f, swing = 0.1f, drag = -0.05f
                ),
                BandMember(
                    "Bassist", listOf(3),
                    loudness = 0.8f, creativity = 0.5f, swing = 0.0f, drag = 0.08f
                ),
                BandMember(
                    "Keys", listOf(4),
                    loudness = 0.5f, creativity = 0.5f, swing = 0.0f, drag = 0.0f
                ),
                BandMember(
                    "FX", listOf(5, 6, 7),
                    loudness = 0.3f, creativity = 0.7f, swing = 0.0f, drag = 0.12f
                ),
            ),
            handoffMatrix = bandMatrix(
                //            DRUM  BASS  KEYS  FX
                "Drummer" to row(0.00f, 0.40f, 0.30f, 0.05f),
                "Bassist" to row(0.30f, 0.00f, 0.35f, 0.10f),
                "Keys" to row(0.25f, 0.40f, 0.00f, 0.10f),
                "FX" to row(0.20f, 0.30f, 0.30f, 0.00f),
            ),
            pullInMatrix = bandMatrix(
                // Generous: drums+bass lock, bass+keys tight
                "Drummer" to row(0.00f, 0.35f, 0.20f, 0.05f),
                "Bassist" to row(0.30f, 0.00f, 0.45f, 0.10f),
                "Keys" to row(0.20f, 0.45f, 0.00f, 0.10f),
                "FX" to row(0.10f, 0.25f, 0.25f, 0.00f),
            ),
            pullInBarsMin = 2, pullInBarsMax = 4,
            barsPerLeadMin = 4, barsPerLeadMax = 8,
        ),
        energy = 0.7f,
        complexity = 0.4f,
        space = 0.4f,
        mood = 0.5f,
        genre = GenreProfile(
            swingAmount = 0.10f,
            ghostProbability = 0.25f,
            noteRangeLow = 36,
            noteRangeHigh = 60,
            rhythmDensity = RhythmPattern.BACKBEAT.density,
            progressionStyle = ProgressionStyle.BLUES,   // matrix stays bluesy
            chordsPerBar = chordsPerBar,
            customProgression = mainProgression
        ),
        progressionAnchor = ProgressionAnchor.EVERY_8,   // reset each 8-bar phrase
        progressionDriftRange = 0.12f,                    // subtle drift — preserve the blues shape
        tracks = listOf(
            TrackVoice(
                engineEdm = Engine.BD,
                engineSpace = Engine.BD,
                role = TrackRole.Percussive,
                volume = 0.85f,
                pan = 0.00f,
                density = 0.45f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.MUTATE
            ),
            TrackVoice(
                engineEdm = Engine.SD,
                engineSpace = Engine.SD,
                role = TrackRole.Percussive,
                volume = 0.60f,
                pan = -0.10f,
                density = 0.35f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.FILL
            ),
            TrackVoice(
                engineEdm = Engine.HH,
                engineSpace = Engine.HH,
                role = TrackRole.Percussive,
                volume = 0.55f,
                pan = 0.15f,
                density = 0.55f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.MUTATE
            ),
            // Bass: REPEAT pattern so the riff is tight — chord transposition provides the only variation.
            TrackVoice(
                engineEdm = Engine.WSH,
                engineSpace = Engine.STR,
                role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                volume = 0.75f,
                pan = 0.00f,
                density = 0.40f,
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
                barStrategy = BarStrategy.REPEAT,
                noteRangeLow = 33,
                noteRangeHigh = 52,
                reverbBrightness = 0.25f,
            ),
            // Keys: reduced volume + density + REPEAT strategy so chord progression motion
            // sits behind the drums/bass instead of dominating (restores pre-progression-wiring feel).
            // Engine.DX with harmonics=0.33 lands on patch index 10 = "Syn-bass 2" (DX bank
            // is the bass+analog-synth bank, not E.piano — see references/fm_patches.md).
            // At volume=0.28 played as Chordal/BLUES_SHUFFLE comping, this synth-bass tone
            // voices chords with a thick analog-FM character that sits well under the bass.
            TrackVoice(
                engineEdm = Engine.DX,
                engineSpace = Engine.GRN,
                harmonics = 0.33f,  // DX bank idx 10 = "Syn-bass 2" — synth-bass voicing chords
                timbre = 0.32f,     // modulator index — not too bright for blues
                morph = 0.31f,       // less feedback, cleaner attack
                volume = 0.28f,
                pan = -0.25f,
                density = 0.30f,
                role = TrackRole.Chordal(
                    comping = ChordComping(
                        style = CompingStyle.BLUES_SHUFFLE,
                        arpMode = ArpMode.AUTO,
                        arpSpeed = 0.1f,
                        arpDirection = ArpDirection.UP_DOWN,
                        sectionInversion = SectionInversion.FIRST_INVERSION,
                        humanization = CompingHumanization(
                            dropProbability = .2f,
                            ghostProbability = .2f,
                            octaveJumpProbability = .4f,
                            extensionProbability = .4f
                        ),
                        fills = CompingFills(
                            everyNBars = 6,
                            fillType = FillType.TURNAROUND,
                            skipProbability = .1f
                        ),
                    ),
                ),
                envelopeProfile = EnvelopeProfile.EFFECT,
                macroMap = TrackMacroMap.MELODIC,
                barStrategy = BarStrategy.REPEAT,
                noteRangeLow = 45,
                noteRangeHigh = 65,
                reverbBrightness = 0.5f,
                delayFeedback = .3f,
                delaySend = .3f,
                glideRate = 0.1f,
            ),
            TrackVoice(
                engineEdm = Engine.STR,
                engineSpace = Engine.STR,
                role = TrackRole.Melodic(),
                volume = 0.30f,
                pan = 0.30f,
                density = 0.05f,
                envelopeProfile = EnvelopeProfile.EFFECT,
                macroMap = TrackMacroMap.EFFECT,
                barStrategy = BarStrategy.INDEPENDENT,
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
                glideRate = 0.4f
            ),
            TrackVoice(
                engineEdm = Engine.GRN,
                engineSpace = Engine.GRN,
                role = TrackRole.Melodic(),
                volume = 0.30f,
                pan = -0.30f,
                density = 0.15f,
                envelopeProfile = EnvelopeProfile.EFFECT,
                macroMap = TrackMacroMap.EFFECT,
                barStrategy = BarStrategy.INDEPENDENT,
                modLfoRate = 0.15f,
                modLfoDepth = 0.6f,
                modLfoShape = 0.5f,
                modLfoCoupling = 0.2f,
                holdProbability = 0.6f,
                holdLengthMin = 4,
                holdLengthMax = 12,
                reverbSend = 0.5f,
                delaySend = 0.3f,
                noteRangeLow = 41,
                noteRangeHigh = 60,
                reverbBrightness = 0.7f,
                glideRate = 0.35f
            ),
            TrackVoice(
                engineEdm = Engine.MOD,
                engineSpace = Engine.STR,
                role = TrackRole.Melodic(),
                volume = 0.20f,
                pan = 0.00f,
                density = 0.08f,
                envelopeProfile = EnvelopeProfile.WILD,
                macroMap = TrackMacroMap.WILD,
                barStrategy = BarStrategy.REPEAT,
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
                noteRangeHigh = 58,
                reverbBrightness = 0.5f,
                glideRate = 0.3f
            ),
        ),
        stepCount = 32,
        tension = TensionProfile(
            spurtChance = 0.12f,
            innerBars = 4,
            volume = 0.35f,
            tonal = TonalTension(octaveShift = true, chromaticPassing = 0.15f),
            timing = 0.25f,
            evolution = EvolutionTension(
                timbreLow = 0.25f, timbreHigh = 0.6f, timbreProbability = 0.7f,
                attackPoint = 0.5f, releaseSpeed = 0.4f,
            ),
        ),
        effects = VibeEffects(
            delayTimeA = 0.25f,
            delayTimeB = 0.375f,
            delayFeedback = 0.35f,
            delayDamping = 0.4f,
            reverbSize = 0.5f,
            reverbDamping = 0.5f,
            reverbBrightness = 0.6f,
        ),
        arrangement = Arrangement(
            introIndex = 0,
            sections = listOf(
                // 0: intro — drums only, building energy.
                // intro -> verse: bluesLift (the band joins in).
                Section(
                    name = "intro",
                    barsMin = 2, barsMax = 4,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = bluesLiftBars),
                    ),
                    macroOverrides = MacroOverrides(energy = 0.5f, complexity = 0.4f, space = 0.5f),
                    customProgression = chorusProgression,
                    chordsPerBar = chorusChordsPerBar,
                ),
                // 1: verse — full band, moderate energy, bluesy swing.
                // verse -> chorus / solo: bluesLift (climb or sideways).
                // verse -> breakdown:     bluesyDrop (slow exhale into the breakdown).
                Section(
                    name = "verse",
                    barsMin = 6, barsMax = 10,
                    transitions = listOf(
                        SectionTransition(targetIndex = 2, weight = 0.6f, transitionBars = bluesLiftBars),
                        SectionTransition(targetIndex = 3, weight = 0.25f, transitionBars = bluesLiftBars),
                        SectionTransition(targetIndex = 4, weight = 0.15f, transitionBars = bluesyDropBars),
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = null,  // verse IS the baseline
                ),
                // 2: chorus — high energy, tight, driving.
                // chorus -> verse:      bluesLift (small step down).
                // chorus -> solo:       bluesyDrop (chorus exhales into the solo).
                // chorus -> breakdown:  bluesyDrop (big drop into the stripped-back section).
                Section(
                    name = "chorus",
                    barsMin = 4, barsMax = 6,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.4f, transitionBars = bluesLiftBars),
                        SectionTransition(targetIndex = 3, weight = 0.35f, transitionBars = bluesyDropBars),
                        SectionTransition(targetIndex = 4, weight = 0.25f, transitionBars = bluesyDropBars),
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = MacroOverrides(
                        energy = 1.4f, complexity = 1.3f, space = 0.7f, mood = 1.2f,
                    ),
                    customProgression = chorusProgression,
                    chordsPerBar = chorusChordsPerBar,
                ),
                // 3: solo — band jams together, combos lock in.
                // solo -> chorus / verse: bluesLift (solo climaxes back up).
                // solo -> breakdown:      bluesyDrop (solo dissolves into the breakdown).
                Section(
                    name = "solo",
                    barsMin = 8, barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 2, weight = 0.5f, transitionBars = bluesLiftBars),
                        SectionTransition(targetIndex = 1, weight = 0.3f, transitionBars = bluesLiftBars),
                        SectionTransition(targetIndex = 4, weight = 0.2f, transitionBars = bluesyDropBars),
                    ),
                    recencyDecay = 0.4f,
                    macroOverrides = MacroOverrides(
                        energy = 0.8f, complexity = 1.3f, space = 1.3f, mood = 1.3f,
                    ),
                    soloMode = SoloMode.Jam(probability = 0.85f),
                ),
                // 4: breakdown — stripped back, just bass and percussion.
                // breakdown -> chorus: bigBluesLift — THE moment. Locked to 4 bars,
                //   so the entire breakdown is one long anticipation buildup as
                //   energy 0.4 climbs to 1.4 across all 4 bars before chorus hits.
                // breakdown -> verse:  bluesLift (gentler return to baseline).
                Section(
                    name = "breakdown",
                    barsMin = 4, barsMax = 4,
                    transitions = listOf(
                        SectionTransition(targetIndex = 2, weight = 0.7f, transitionBars = bigBluesLiftBars),
                        SectionTransition(targetIndex = 1, weight = 0.3f, transitionBars = bluesLiftBars),
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = MacroOverrides(
                        energy = 0.4f, complexity = 0.5f, space = 1.5f, mood = 0.8f,
                    ),
                    customProgression = chorusProgression,
                    chordsPerBar = chorusChordsPerBar,
                ),
            ),
        ),
    )
}
