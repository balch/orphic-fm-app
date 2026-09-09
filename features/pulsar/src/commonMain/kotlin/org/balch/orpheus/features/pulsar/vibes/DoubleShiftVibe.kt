package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.ArpDirection
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.BandPresets
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
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.chords

/**
 * Double Shift — a loping, shift-worker's groove: mixolydian, half-swung, more space
 * than notes.
 *
 * The point of this vibe is a drone. Track 5 is a CHORDAL track pinned to the tonic with
 * `ChordFollow.FIXED` and `ArpMode.NEVER`, so it sustains a C while the comp on track 4
 * walks IV - vi - V underneath it. That is the open-tuning trick: the ringing string stays
 * put and the moving harmony collides with it. Here the collisions are deliberate — C over
 * F is just the fifth, C over Am is the third, but C over G is a suspended fourth, so the
 * tension lands on the V where the phrase wants to turn.
 *
 * Track 4 carries a high `extensionProbability` for the same reason: the extension adds 2
 * or 5 semitones to an arp note, and +5 from the root is the fourth — a suspension that
 * resolves as the arp moves on. It is the only humanization field that is authored high
 * here; the rest stay near their floors.
 *
 * No other shipped vibe pairs a FIXED chordal drone with a FOLLOW comp, so treat the whole
 * middle of the mix as the experiment.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class DoubleShiftVibe : VibeProvider {
    override val name: String = "Double Shift"

    // Verse: IV-I three times, then V-I to turn it around. Eight bars, one chord each,
    // so the harmony moves slower than the riff and the drone has time to bite.
    private val verseProgression = chords(3, 0, 3, 0, 3, 0, 4, 0)

    // The change: the vi is the one chord that is neither the tonic nor a dominant, so it
    // is where the drone stops being a suspension and just sits inside the chord.
    private val changeProgression = chords(3, 5, 4, 0)

    // Original riff. Root, a shove up to the fourth, then a fall onto the third — the
    // sus-to-major move played as a line instead of a chord. The flat seventh in the
    // second half is what keeps it mixolydian rather than plain major. Ends on three
    // beats of nothing: the lope is the rest, not the notes.
    private val shiftRiff = Lick(
        steps = listOf(
            LickStep(scaleDegree = 0, duration = 0.75f, velocity = 0.95f),
            LickStep(scaleDegree = 3, duration = 0.25f, velocity = 0.70f),
            LickStep(scaleDegree = 2, duration = 0.50f, velocity = 0.85f),
            LickStep(scaleDegree = -1, duration = 0.50f, velocity = 0.0f),
            LickStep(scaleDegree = 0, duration = 0.50f, velocity = 0.80f),
            LickStep(scaleDegree = 6, duration = 0.50f, velocity = 0.75f),
            LickStep(scaleDegree = 4, duration = 0.75f, velocity = 0.80f),
        ),
        loopLength = 8,
    )

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            bpm = 104f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.C,
            scaleType = ScaleType.MIXOLYDIAN,
            energy = 0.55f,
            complexity = 0.45f,
            space = 0.40f,
            mood = 0.55f,
            deep = 0.35f,
            stepCount = 32,
            lick = shiftRiff,
            lickMutation = 0.25f,
            genre = GenreProfile(
                swingAmount = 0.16f,          // half-swung — leans without becoming a shuffle
                ghostProbability = 0.22f,
                noteRangeLow = 36,
                noteRangeHigh = 64,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.POP,
                chordsPerBar = 1,
                customProgression = verseProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_8,
            progressionDriftRange = 0.10f,     // the form is the point; barely let it wander
            tracks = listOf(
                // 0 Kick — plain and unhurried, the shift clock.
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.88f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.0f,
                        density = 0.40f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 1 Snare — backbeat, ghosted around it.
                OrpheusEngine(engineId = OrpheusEngineId.SD, volume = 0.72f).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.08f,
                        density = 0.34f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.FILL,
                    )
                },
                // 2 Hat — the only busy thing in the kit.
                OrpheusEngine(
                    engineId = OrpheusEngineId.HH,
                    volume = 0.44f,
                    reverbSend = 0.10f,
                ).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.18f,
                        density = 0.52f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 3 Bass — carries the riff. ROOT_ONLY so it stays in key across the change.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.78f,
                    harmonics = 0.42f,
                    timbre = 0.48f,
                    noteRangeLow = 33,
                    noteRangeHigh = 52,
                    reverbSend = 0.08f,
                    glideRate = 0.12f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.PD, harmonics = 0.30f),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.ROOT_ONLY,
                            lickMode = LickMode.Fill,
                        ),
                        pan = 0.0f,
                        density = 0.55f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 4 Chop — the moving comp. arpSpeed 0.9 makes it a strummed stab rather
                //   than a roll, and the extensions are the suspended-fourth colour.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.30f,
                    harmonics = 0.34f,
                    timbre = 0.62f,
                    noteRangeLow = 52,
                    noteRangeHigh = 72,
                    reverbSend = 0.22f,
                    delaySend = 0.16f,
                ).let { chop ->
                    TrackVoice(
                        engineEdm = chop,
                        engineSpace = chop.copy(engineId = OrpheusEngineId.VA, timbre = 0.45f),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.BLUES_SHUFFLE,
                                arpMode = ArpMode.AUTO,
                                arpSpeed = 0.90f,
                                arpDirection = ArpDirection.UP,
                                sectionInversion = SectionInversion.OPEN_VOICING,
                                humanization = CompingHumanization(
                                    dropProbability = 0.18f,
                                    ghostProbability = 0.12f,
                                    octaveJumpProbability = 0.0f,
                                    extensionProbability = 0.30f,  // the suspension
                                ),
                                fills = CompingFills(
                                    everyNBars = 8,
                                    fillType = FillType.TURNAROUND,
                                    skipProbability = 0.35f,
                                ),
                            ),
                        ),
                        pan = -0.34f,
                        density = 0.42f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 5 Drone — the experiment. FIXED pins it to the tonic while track 4 moves,
                //   NEVER keeps it sustained instead of rolled. Low and wide.
                OrpheusEngine(
                    engineId = OrpheusEngineId.STR,
                    volume = 0.26f,
                    harmonics = 0.40f,
                    timbre = 0.35f,
                    holdProbability = 0.85f,
                    holdLengthMin = 6,
                    holdLengthMax = 16,
                    noteRangeLow = 45,
                    noteRangeHigh = 60,
                    reverbSend = 0.42f,
                    reverbBrightness = 0.45f,
                    glideRate = 0.20f,
                ).let { drone ->
                    TrackVoice(
                        engineEdm = drone,
                        engineSpace = drone.copy(volume = 0.32f, reverbSend = 0.58f),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FIXED,
                            comping = ChordComping(
                                style = CompingStyle.PAD,
                                arpMode = ArpMode.NEVER,
                                humanization = CompingHumanization(
                                    dropProbability = 0.08f,
                                ),
                            ),
                        ),
                        pan = 0.30f,
                        density = 0.16f,
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 6 Lead — the voice that takes the solo. Sits above the chop.
                OrpheusEngine(
                    engineId = OrpheusEngineId.VA,
                    volume = 0.36f,
                    harmonics = 0.52f,
                    timbre = 0.55f,
                    noteRangeLow = 55,
                    noteRangeHigh = 76,
                    reverbSend = 0.30f,
                    delaySend = 0.28f,
                    glideRate = 0.22f,
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead.copy(engineId = OrpheusEngineId.ENS, volume = 0.30f),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
                        pan = 0.22f,
                        density = 0.24f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.CALL_RESPONSE,
                    )
                },
                // 7 Yard noise — sparse metallic hits, the plant outside the groove.
                OrpheusEngine(
                    engineId = OrpheusEngineId.MOD,
                    volume = 0.20f,
                    harmonics = 0.28f,
                    modLfoRate = 0.07f,
                    modLfoDepth = 0.45f,
                    modLfoShape = 0.55f,
                    holdProbability = 0.45f,
                    holdLengthMin = 3,
                    holdLengthMax = 9,
                    noteRangeLow = 48,
                    noteRangeHigh = 72,
                    reverbSend = 0.48f,
                    delaySend = 0.22f,
                ).let { yard ->
                    TrackVoice(
                        engineEdm = yard,
                        engineSpace = yard.copy(engineId = OrpheusEngineId.PAR),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED),
                        pan = -0.16f,
                        density = 0.10f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            band = BandPresets.quartet(
                kit = listOf(0, 1, 2),
                bass = listOf(3),
                lead = listOf(6),
                colour = listOf(4, 5, 7),
            ),
            arrangement = Arrangement(
                sections = listOf(
                    // 0 intro — kit and drone only; the drone states the tonic alone so the
                    //   collisions later have something to be heard against.
                    Section(
                        name = "intro",
                        barsMin = 2, barsMax = 4,
                        transitions = listOf(SectionTransition(1, 1.0f, transitionBars = 2)),
                        macroOverrides = MacroOverrides(energy = 0.45f, complexity = 0.35f),
                        customProgression = verseProgression,
                        chordsPerBar = 1,
                    ),
                    // 1 verse — the riff and the IV-I rock.
                    Section(
                        name = "verse",
                        barsMin = 8, barsMax = 16, barStep = 4,
                        transitions = listOf(
                            SectionTransition(2, 0.55f, transitionBars = 2),
                            SectionTransition(3, 0.30f, transitionBars = 2),
                            SectionTransition(4, 0.15f, transitionBars = 3),
                        ),
                        recencyDecay = 0.5f,
                        customProgression = verseProgression,
                        chordsPerBar = 1,
                    ),
                    // 2 change — the vi arrives and the drone stops suspending for a bar.
                    Section(
                        name = "change",
                        barsMin = 4, barsMax = 8, barStep = 4,
                        transitions = listOf(
                            SectionTransition(1, 0.5f, transitionBars = 2),
                            SectionTransition(3, 0.5f, transitionBars = 2),
                        ),
                        macroOverrides = MacroOverrides(energy = 1.2f, complexity = 1.15f, mood = 1.1f),
                        customProgression = changeProgression,
                        chordsPerBar = 1,
                    ),
                    // 3 solo — someone takes it over the verse changes.
                    Section(
                        name = "solo",
                        barsMin = 8, barsMax = 16, barStep = 4,
                        transitions = listOf(
                            SectionTransition(1, 0.6f, transitionBars = 2),
                            SectionTransition(4, 0.4f, transitionBars = 3),
                        ),
                        macroOverrides = MacroOverrides(energy = 1.1f, complexity = 1.3f, space = 1.15f),
                        soloMode = SoloMode.Jam(probability = 0.55f),
                        customProgression = verseProgression,
                        chordsPerBar = 1,
                    ),
                    // 4 breakdown — kit thins, drone and chop left rubbing against each other.
                    Section(
                        name = "breakdown",
                        barsMin = 4, barsMax = 4,
                        transitions = listOf(SectionTransition(1, 1.0f, transitionBars = 4)),
                        macroOverrides = MacroOverrides(energy = 0.4f, complexity = 0.5f, space = 1.5f),
                        customProgression = changeProgression,
                        chordsPerBar = 1,
                    ),
                ),
                introIndex = 0,
            ),
            tension = TensionProfile(
                innerBars = 8,
                outerBars = 24,
                outerDepth = 0.4f,
                volume = 0.28f,
                timing = 0.20f,
                spurtChance = 0.08f,
                tonal = TonalTension(chromaticPassing = 0.10f),
                evolution = EvolutionTension(
                    timbreLow = 0.30f, timbreHigh = 0.60f, timbreProbability = 0.55f,
                    attackPoint = 0.55f, releaseSpeed = 0.35f,
                ),
            ),
            effects = VibeEffects(
                delayTimeA = 0.375f,      // dotted-8th — pushes the lope along
                delayTimeB = 0.25f,
                delayFeedback = 0.32f,
                delayDamping = 0.45f,
                reverbSize = 0.48f,
                reverbDamping = 0.5f,
                reverbBrightness = 0.5f,
            ),
        )
    }
}
