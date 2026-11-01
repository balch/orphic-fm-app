package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.VoidAnomaly
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.CastPinRecord
import org.balch.orpheus.features.pulsar.models.CastProvenance
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.GrainMode
import org.balch.orpheus.features.pulsar.models.GrainScatterMode
import org.balch.orpheus.features.pulsar.models.GrainVoicing
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickMutationMode
import org.balch.orpheus.features.pulsar.models.LickRotation
import org.balch.orpheus.features.pulsar.models.LickSource
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.SectionWeather
import org.balch.orpheus.features.pulsar.models.SoloMode
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeName
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.VolumePoint
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Running Out — a long ocean journey against the clock: a lone ping in the dark, an organ
 * that swells into a slow pounding groove, a dive to where the whales sing, and the climb
 * back up.
 *
 * ## The feel
 * 64 bpm, C# dorian, everything wet. One dark modal bell rings a single B over and over, with
 * long delay tails between, and a clock ticks under the whole piece. The clock is the thread: it
 * keeps time running out. Quarters at first, eighths once the band pounds, slowing to half
 * notes as the light fades and the whales take over, rewound on the way up, racing in the
 * last jam, and down to one tick a bar before it stops.
 *
 * ## The arc (played once, in order)
 * pings -> organ swell -> slow pound -> bass wander -> sinking -> lone whale -> pod ->
 * surfacing -> pound returns -> deep jam -> last pings.
 * - The ringing chords are the organ-drift bed, loud in the swell.
 * - The pound is a half-time kit pinned by `hits` (kick on 1 and the "and" of 2, snare on 3),
 *   with the lead's licks placed on the beat and long rests for the delay to ring.
 * - The bass plays a build-up / build-down line on the bass channel: up two bars to a B2 peak,
 *   down two bars. The two jams swap it for a sparse skeleton of the same arch and hand the
 *   solo to the Bassist, the only member that can lead, whose Jam noodles in its rests. In the pod the bass
 *   doubles the answering whale two octaves down.
 * - Track 4 is the lead at high energy and the calling whale at low energy (VOWEL_FOF with a
 *   slow vowel, a 400-cent moan and long glides). Track 7 answers it on the bass channel (SPK
 *   "oo" with a grain cloud, so one answer sounds like many: the pod). The FX slot speaks only
 *   below energy 0.4, which is where the whale sections live.
 * - Splashes: the snare turns into a long-decay wash at low density, over a few close rain drops.
 *
 * Cast: BED_FIXED, seed 7019, tags ambient + psych, pins kick BD (0), snare (1), hat (2),
 * perc MOD (7). Tracks 6 and 7 were recast inside the texture family: 6 is the ping, 7 the
 * answering whale.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class RunningOutVibe : VibeProvider {
    override val name: VibeName = VibeNames.RUNNING_OUT

    // ── Melody lines: PLACEHOLDERS, the user writes these ──────────────────────────
    //
    // C# DORIAN degrees: 0=C# 1=D# 2=E 3=F# 4=G# 5=A# 6=B, 7=C# an octave up (9=E, 11=G#).
    // stepCount 64: a Fill line plays a 16-beat window (4 bars), so each line totals 16 beats.
    // A negative degree is a rest. At 64 bpm one beat is 0.94 s; glideRate 0.12 lands in ~80 ms,
    // 0.28 in ~150 ms, 0.6-0.7 is a slow whale slide that a 3-beat note still completes.
    // Pool order IS section order: Section.lickIndex pins slots 0-3 below, bassLineIndex 4-5.

    // TODO(lick): slot 0, the pound's lead line. On the beat, a 2-4 note motif, lots of rest.
    private val poundLick = Lick(
        steps = listOf(
            LickStep(scaleDegree = 4, duration = 1f, velocity = 0.9f),
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.75f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.8f),
            LickStep(scaleDegree = 4, duration = 2f, velocity = 0.9f, glideRate = 0.28f),
            LickStep(scaleDegree = -1, duration = 4f),
            LickStep(scaleDegree = 7, duration = 1f, velocity = 0.9f),
            LickStep(scaleDegree = 6, duration = 1f, velocity = 0.8f),
            LickStep(scaleDegree = 4, duration = 2f, velocity = 0.85f, glideRate = 0.28f),
            LickStep(scaleDegree = -1, duration = 4f),
        ),
        loopLength = 16,
    )

    // TODO(lick): slot 1, the lone whale's call. Few long notes, wide leaps, slow slides.
    private val loneWhaleCall = Lick(
        steps = listOf(
            LickStep(scaleDegree = 4, duration = 3f, velocity = 0.8f, glideRate = 0.6f),
            LickStep(scaleDegree = -1, duration = 1f),
            LickStep(scaleDegree = 7, duration = 4f, velocity = 0.85f, glideRate = 0.7f),
            LickStep(scaleDegree = -1, duration = 2f),
            LickStep(scaleDegree = 5, duration = 3f, velocity = 0.75f, glideRate = 0.6f),
            LickStep(scaleDegree = -1, duration = 3f),
        ),
        loopLength = 16,
    )

    // TODO(lick): slot 2, the pod's call. Sings beats 1-4 and 9-12 and rests where
    // [whaleAnswer] speaks (beats 5-8 and 13-16).
    private val podCall = Lick(
        steps = listOf(
            LickStep(scaleDegree = 4, duration = 3f, velocity = 0.85f, glideRate = 0.6f),
            LickStep(scaleDegree = -1, duration = 5f),
            LickStep(scaleDegree = 7, duration = 2.5f, velocity = 0.85f, glideRate = 0.7f),
            LickStep(scaleDegree = 5, duration = 1.5f, velocity = 0.75f, glideRate = 0.6f),
            LickStep(scaleDegree = -1, duration = 4f),
        ),
        loopLength = 16,
    )

    // TODO(lick): slot 3, the pound's return. Develops slot 0's motif (higher, or shifted an 8th).
    private val returnLick = Lick(
        steps = listOf(
            LickStep(scaleDegree = -1, duration = 2f),
            LickStep(scaleDegree = 9, duration = 1.5f, velocity = 0.9f, glideRate = 0.28f),
            LickStep(scaleDegree = 7, duration = 0.5f, velocity = 0.75f),
            LickStep(scaleDegree = 6, duration = 2f, velocity = 0.85f),
            LickStep(scaleDegree = -1, duration = 2f),
            LickStep(scaleDegree = 4, duration = 1f, velocity = 0.8f),
            LickStep(scaleDegree = 6, duration = 1f, velocity = 0.8f),
            LickStep(scaleDegree = 7, duration = 2f, velocity = 0.9f, glideRate = 0.28f),
            LickStep(scaleDegree = -1, duration = 4f),
        ),
        loopLength = 16,
    )

    // ── The bass channel: the bass (track 3, FIXED) and the answering whale (track 7, FOLLOW) ──
    //
    // The bass is FIXED, so its degrees are absolute: midi = C# + bassLineOctave * 12 + degree,
    // and bassLineOctave 2 puts degree 7 on C#2. So 2=E1 3=F#1 4=G#1 5=A#1 6=B1 7=C#2 8=D#2
    // 9=E2 10=F#2 11=G#2 12=A#2 13=B2, inside the bass window E1-B2 (28-47). Write each bar
    // against its chord: one chord a bar, C#m | F# | C#m | B. Track 7 folds the same degrees into
    // its own 60-67 window, so it only hears the pitch class.

    // DRAFT (tune by ear): the build-up / build-down line. Climbs two bars to the B2 peak, walks
    // back down two bars, and the last bar climbs from B1 so the loop restarts on C#2.
    private val buildLine = Lick(
        steps = listOf(
            // C#m: up C# E G# B, lean back to G#
            LickStep(scaleDegree = 7, duration = 1f, velocity = 0.9f),
            LickStep(scaleDegree = 9, duration = 0.5f, velocity = 0.7f),
            LickStep(scaleDegree = 11, duration = 0.5f, velocity = 0.75f),
            LickStep(scaleDegree = 13, duration = 1f, velocity = 0.85f),
            LickStep(scaleDegree = 11, duration = 1f, velocity = 0.75f),
            // F#: up F# G# A# to the B peak, slid into
            LickStep(scaleDegree = 10, duration = 1f, velocity = 0.9f),
            LickStep(scaleDegree = 11, duration = 0.5f, velocity = 0.7f),
            LickStep(scaleDegree = 12, duration = 0.5f, velocity = 0.75f),
            LickStep(scaleDegree = 13, duration = 1.5f, velocity = 0.9f, glideRate = 0.2f),
            LickStep(scaleDegree = 12, duration = 0.5f, velocity = 0.7f),
            // C#m: down G# E D# C#, drop to G#1
            LickStep(scaleDegree = 11, duration = 1f, velocity = 0.85f),
            LickStep(scaleDegree = 9, duration = 0.5f, velocity = 0.7f),
            LickStep(scaleDegree = 8, duration = 0.5f, velocity = 0.7f),
            LickStep(scaleDegree = 7, duration = 1f, velocity = 0.85f),
            LickStep(scaleDegree = 4, duration = 1f, velocity = 0.75f),
            // B: from the root, climb back toward C#
            LickStep(scaleDegree = 6, duration = 1f, velocity = 0.9f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.7f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.75f),
            LickStep(scaleDegree = 5, duration = 1f, velocity = 0.8f),
            LickStep(scaleDegree = 6, duration = 1f, velocity = 0.8f, glideRate = 0.12f),
        ),
        loopLength = 16,
    )

    // DRAFT: slot 5, the jams' skeleton of [buildLine]: the same arch on two notes a bar, with
    // rests the Bassist's Jam fills, so the noodling grows out of the build line.
    private val jamSkeleton = Lick(
        steps = listOf(
            LickStep(scaleDegree = 7, duration = 1f, velocity = 0.9f),
            LickStep(scaleDegree = -1, duration = 1f),
            LickStep(scaleDegree = 11, duration = 0.5f, velocity = 0.8f),
            LickStep(scaleDegree = -1, duration = 1.5f),
            LickStep(scaleDegree = 10, duration = 1f, velocity = 0.9f),
            LickStep(scaleDegree = -1, duration = 1f),
            LickStep(scaleDegree = 13, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = -1, duration = 1.5f),
            LickStep(scaleDegree = 11, duration = 1f, velocity = 0.85f),
            LickStep(scaleDegree = -1, duration = 1f),
            LickStep(scaleDegree = 8, duration = 0.5f, velocity = 0.75f),
            LickStep(scaleDegree = -1, duration = 1.5f),
            LickStep(scaleDegree = 6, duration = 1f, velocity = 0.9f),
            LickStep(scaleDegree = -1, duration = 1f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.75f),
            LickStep(scaleDegree = -1, duration = 1.5f),
        ),
        loopLength = 16,
    )

    // TODO(lick): slot 4, the answering whale (the pod only). Opens on a 4-beat rest while the
    // call sings, answers, rests again, answers lower. The bass doubles it at C#2 / E2, a deep whale.
    private val whaleAnswer = Lick(
        steps = listOf(
            LickStep(scaleDegree = -1, duration = 4f),
            LickStep(scaleDegree = 7, duration = 3f, velocity = 0.8f, glideRate = 0.6f),
            LickStep(scaleDegree = -1, duration = 5f),
            LickStep(scaleDegree = 9, duration = 2f, velocity = 0.75f, glideRate = 0.65f),
            LickStep(scaleDegree = 7, duration = 2f, velocity = 0.7f, glideRate = 0.6f),
        ),
        loopLength = 16,
    )

    // ── The clock (track 2, pinned hits on its 32-step loop; 4 steps a beat) ─────────
    private val ticksWhole = listOf(0, 16)
    private val ticksHalf = listOf(0, 8, 16, 24)
    private val ticksQuarter = listOf(0, 4, 8, 12, 16, 20, 24, 28)
    private val ticksEighth = (0 until 32 step 2).toList()
    // Eighths that break into 16ths on each bar's last beat: time running out.
    private val ticksRacing = ((0 until 32 step 2) + listOf(13, 15, 29, 31)).sorted()

    private fun clock(ticks: List<Int>, volume: Float) =
        TrackSectionOverride(hits = ticks, morph = 0.05f, volume = volume)

    private val out = TrackSectionOverride(density = 0f)

    // The snare as a long wash: a splash at the surface.
    private val splash = TrackSectionOverride(density = 0.1f, morph = 0.85f, reverbSend = 0.7f, volume = 0.3f)

    override val vibe: Vibe by lazy {
        Vibe(
            name = name.value,
            bpm = 64f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.C_SHARP,
            scaleType = ScaleType.DORIAN,
            energy = 0.55f,
            complexity = 0.35f,
            space = 0.65f,
            mood = 0.4f,
            deep = 0.65f,
            stepCount = 64,
            genre = GenreProfile(
                swingAmount = 0f,
                ghostProbability = 0.05f,
                noteRangeLow = 36,
                noteRangeHigh = 60,
                rhythmDensity = 0.55f,
                progressionStyle = ProgressionStyle.MODAL,
                chordsPerBar = 4,
                // i - IV - i - VII: C#m, F#, C#m, B. The ping's B is the VII's root.
                customProgression = chords(0, 3, 0, 6),
            ),
            progressionDriftRange = 0.1f,
            tracks = listOf(
                // 0: kick, pinned to a half-time pound in the groove sections
                TrackVoice(
                    engineEdm = OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.84f, harmonics = 0.57f, timbre = 0.42f, morph = 0.26f),
                    engineSpace = OrpheusEngine(
                        engineId = OrpheusEngineId.MOD, volume = 0.72f, harmonics = 0.26f, timbre = 0.42f, morph = 0.26f, pinHarmonics = true,
                    ),
                    role = TrackRole.Percussive,
                    density = 0.43f,
                    macroMap = TrackMacroMap(
                        energyVolume = MacroTarget(0.7f, 1f),
                        energyDensity = MacroTarget(0.4f, 0.8f),
                        complexitySwing = MacroTarget(0f, 0.1f),
                        complexityVariation = MacroTarget(0f, 0.15f),
                        spaceDecay = MacroTarget(0.11f, 0.41f),
                        moodHarmonics = MacroTarget(0.42f, 0.72f),
                        moodTimbre = MacroTarget(0.27f, 0.57f),
                    ),
                ),
                // 1: snare on 3 in the pound; a splash in the whale sections
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD, volume = 0.6f, harmonics = 0.36f, timbre = 0.45f, morph = 0.37f,
                    delaySend = 0.05f, reverbSend = 0.15f,
                ).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare.copy(timbre = 0.67f, pinTimbre = true),
                        role = TrackRole.Percussive,
                        pan = -0.09f,
                        density = 0.36f,
                        barStrategy = BarStrategy.FILL,
                        macroMap = TrackMacroMap(
                            energyVolume = MacroTarget(0.7f, 1f),
                            energyDensity = MacroTarget(0.4f, 0.8f),
                            complexitySwing = MacroTarget(0f, 0.1f),
                            complexityVariation = MacroTarget(0f, 0.15f),
                            spaceDecay = MacroTarget(0.22f, 0.52f),
                            moodHarmonics = MacroTarget(0.21f, 0.51f),
                            moodTimbre = MacroTarget(0.3f, 0.6f),
                        ),
                    )
                },
                // 2: the clock. Every section pins its ticks (see the clock vals above).
                OrpheusEngine(
                    engineId = OrpheusEngineId.HH, volume = 0.45f, harmonics = 0.47f, timbre = 0.45f, morph = 0.57f,
                    delaySend = 0.2f, reverbSend = 0.05f,
                ).let { tick ->
                    TrackVoice(
                        engineEdm = tick,
                        engineSpace = tick.copy(morph = 0.2f, pinMorph = true),
                        role = TrackRole.Percussive,
                        pan = 0.1f,
                        density = 0.4f,
                        barStrategy = BarStrategy.REPEAT,
                        macroMap = TrackMacroMap(
                            energyVolume = MacroTarget(0.7f, 1f),
                            energyDensity = MacroTarget(0.4f, 0.8f),
                            complexitySwing = MacroTarget(0f, 0.1f),
                            complexityVariation = MacroTarget(0f, 0.15f),
                            spaceDecay = MacroTarget(0.42f, 0.72f),
                            moodHarmonics = MacroTarget(0.32f, 0.62f),
                            moodTimbre = MacroTarget(0.3f, 0.6f),
                        ),
                    )
                },
                // 3: filter bass on the build line. FIXED, not ROOT_ONLY: the line is written
                // against each chord, and ROOT_ONLY would flatten it to roots.
                OrpheusEngine(
                    engineId = OrpheusEngineId.VCF, volume = 0.57f, harmonics = 0.44f, timbre = 0.38f, morph = 0.53f,
                    reverbSend = 0.05f, noteRangeLow = 28, noteRangeHigh = 47, glideRate = 0.15f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(morph = 0.3f, pinMorph = true),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FIXED, lickMode = LickMode.Fill, lickSource = LickSource.BASS,
                        ),
                        density = 0.4f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        barStrategy = BarStrategy.MUTATE,
                        macroMap = TrackMacroMap(
                            energyVolume = MacroTarget(0.5f, 1f),
                            energyDensity = MacroTarget(0.4f, 0.9f),
                            complexitySwing = MacroTarget(0f, 0.15f),
                            complexityVariation = MacroTarget(0f, 0.3f),
                            spaceDecay = MacroTarget(0.38f, 0.68f),
                            moodHarmonics = MacroTarget(0.29f, 0.59f),
                            moodTimbre = MacroTarget(0.23f, 0.53f),
                        ),
                    )
                },
                // 4: the lead at high energy, the calling whale at low energy
                TrackVoice(
                    engineEdm = OrpheusEngine(
                        engineId = OrpheusEngineId.WSH, volume = 0.35f, harmonics = 0.7f, timbre = 0.54f, morph = 0.33f,
                        delaySend = 0.35f, reverbSend = 0.35f, noteRangeLow = 68, noteRangeHigh = 79,
                        glideRate = 0.12f, doubleDetune = 10f, doubleSpread = 0.4f,
                    ),
                    // "u" opening toward "o" through each note, a slow waver and a moan upward.
                    engineSpace = OrpheusEngine(
                        engineId = OrpheusEngineId.VOWEL_FOF, volume = 0.32f, harmonics = 0.95f, morph = 0.3f,
                        pinHarmonics = true, pinMorph = true,
                        delaySend = 0.4f, reverbSend = 0.6f, noteRangeLow = 68, noteRangeHigh = 79,
                        pitchRise = 400f, pitchRiseTime = 2.5f, harmonicsRise = -0.25f, harmonicsRiseTime = 3f,
                        vibratoDepth = 20f, vibratoRate = 3.5f, glideRate = 0.55f, doubleDetune = 8f, doubleSpread = 0.5f,
                    ),
                    role = TrackRole.Melodic(lickMode = LickMode.Fill),
                    pan = 0.25f,
                    density = 0.37f,
                    envelopeProfile = EnvelopeProfile.MELODIC,
                    barStrategy = BarStrategy.MUTATE,
                    macroMap = TrackMacroMap(
                        energyVolume = MacroTarget(0.5f, 1f),
                        energyDensity = MacroTarget(0.4f, 0.9f),
                        complexitySwing = MacroTarget(0f, 0.15f),
                        complexityVariation = MacroTarget(0f, 0.3f),
                        spaceDecay = MacroTarget(0.18f, 0.48f),
                        moodHarmonics = MacroTarget(0.55f, 0.85f),
                        moodTimbre = MacroTarget(0.39f, 0.69f),
                    ),
                ),
                // 5: bed 'organ drift', the ringing chords
                OrpheusEngine(
                    engineId = OrpheusEngineId.VA, volume = 0.32f, harmonics = 0.25f, timbre = 0.2f, morph = 0.1f,
                    modLfoRate = 0.015f, modLfoDepth = 0.7f, modLfoShape = 0.1f, modLfoCoupling = 0.4f,
                    delaySend = 0.25f, reverbSend = 0.45f, noteRangeLow = 61, noteRangeHigh = 73,
                    reverbBrightness = 0.4f, glideRate = 0.6f,
                ).let { organ ->
                    TrackVoice(
                        engineEdm = organ,
                        engineSpace = organ.copy(engineId = OrpheusEngineId.ENS),
                        role = TrackRole.Chordal(
                            comping = ChordComping(arpMode = ArpMode.NEVER, restrikeOnChordChange = true),
                        ),
                        pan = -0.1f,
                        density = 0.12f,
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap(
                            energyVolume = MacroTarget(0.5f, 1f),
                            energyDensity = MacroTarget(1f, 1f),
                            complexitySwing = MacroTarget(0f, 0.15f),
                            complexityVariation = MacroTarget(0f, 0.3f),
                            spaceDecay = MacroTarget(0.2f, 0.5f),
                            moodHarmonics = MacroTarget(0.3f, 0.7f),
                            moodTimbre = MacroTarget(0.4f, 0.8f),
                        ),
                    )
                },
                // 6: the ping. A one-note window (B5) pins every generated note to B; FIXED keeps
                // it there over the chords. Dark timbre, long ring, a slight Leslie waver below.
                OrpheusEngine(
                    engineId = OrpheusEngineId.MOD, volume = 0.35f, harmonics = 0.3f, timbre = 0.25f, morph = 0.55f,
                    pinHarmonics = true, pinTimbre = true, pinMorph = true,
                    delaySend = 0.45f, reverbSend = 0.5f, noteRangeLow = 83, noteRangeHigh = 83, reverbBrightness = 0.35f,
                ).let { ping ->
                    TrackVoice(
                        engineEdm = ping,
                        engineSpace = ping.copy(
                            timbre = 0.3f, morph = 0.8f, delaySend = 0.5f, reverbSend = 0.55f, vibratoDepth = 6f, vibratoRate = 6f,
                        ),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED),
                        pan = 0.3f,
                        density = 0.1f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        barStrategy = BarStrategy.INDEPENDENT,
                        macroMap = TrackMacroMap(
                            energyVolume = MacroTarget(0.8f, 1f),
                            energyDensity = MacroTarget(1f, 1f),
                            complexitySwing = MacroTarget(0f, 0.1f),
                            complexityVariation = MacroTarget(0f, 0.15f),
                            spaceDecay = MacroTarget(0.55f, 0.55f),
                            moodHarmonics = MacroTarget(0.3f, 0.3f),
                            moodTimbre = MacroTarget(0.25f, 0.25f),
                        ),
                    )
                },
                // 7: the answering whale on the bass channel. Its grain cloud is the pod.
                TrackVoice(
                    engineEdm = OrpheusEngine(
                        engineId = OrpheusEngineId.TRN, volume = 0.4f, harmonics = 0.5f, timbre = 0.4f, morph = 0.5f,
                        delaySend = 0.3f, reverbSend = 0.6f, noteRangeLow = 60, noteRangeHigh = 67, glideRate = 0.5f,
                    ),
                    engineSpace = OrpheusEngine(
                        engineId = OrpheusEngineId.SPK, volume = 0.6f, harmonics = 0.1667f, timbre = 0.2f, morph = 0.625f,
                        pinHarmonics = true, pinTimbre = true, pinMorph = true,
                        delaySend = 0.35f, reverbSend = 0.65f, noteRangeLow = 60, noteRangeHigh = 67,
                        pitchRise = 600f, pitchRiseTime = 3.5f, morphRise = 0.08f, morphRiseTime = 2f,
                        vibratoDepth = 22f, vibratoRate = 2.8f, glideRate = 0.6f,
                        grainFx = true, grainMode = GrainMode.BURST, grainScatterMode = GrainScatterMode.SCALE,
                        grainVoicing = GrainVoicing.CENTERED, grainScatter = 0.3f, grainRate = 0.35f, grainSize = 0.75f,
                        grainShape = 1f, grainBlend = 0.55f, grainRandomTiming = true,
                        doubleDetune = 14f, doubleSpread = 0.8f,
                    ),
                    role = TrackRole.Melodic(lickMode = LickMode.Fill, lickSource = LickSource.BASS),
                    pan = -0.35f,
                    density = 0.3f,
                    envelopeProfile = EnvelopeProfile.DRONE,
                    macroMap = TrackMacroMap(
                        energyVolume = MacroTarget(0.8f, 1f),
                        energyDensity = MacroTarget(1f, 1f),
                        complexitySwing = MacroTarget(0f, 0.1f),
                        complexityVariation = MacroTarget(0f, 0.15f),
                        spaceDecay = MacroTarget(0.4f, 0.6f),
                        moodHarmonics = MacroTarget(0.35f, 0.65f),
                        moodTimbre = MacroTarget(0.4f, 0.7f),
                    ),
                ),
            ),
            lick = poundLick,
            lickMutation = 0.15f,
            lickMutationMode = LickMutationMode.CHORD_TONES,
            lickRotation = LickRotation(
                pool = listOf(poundLick, loneWhaleCall, podCall, returnLick, whaleAnswer, jamSkeleton),
            ),
            bassLine = buildLine,
            bassLineMutation = 0.1f,
            bassLineOctave = 2,
            // The Bassist is the only member that can lead, so it takes every jam and keeps it:
            // an always-active member is never picked, and a lone eligible lead holds the solo.
            band = Band(
                members = listOf(
                    BandMember("Drummer", listOf(0, 1, 2), alwaysActive = true, loudness = 0.7f, creativity = 0.3f),
                    BandMember("Sea", listOf(5, 6, 7), alwaysActive = true, loudness = 0.4f, creativity = 0.2f),
                    BandMember("Bassist", listOf(3), loudness = 0.5f, creativity = 0.9f),
                    BandMember("Lead", listOf(4), alwaysActive = true, loudness = 0.5f, creativity = 0.3f),
                ),
                handoffMatrix = bandMatrix(
                    //               DRUM  SEA   BASS  LEAD
                    "Drummer" to row(0f, 0f, 1f, 0f),
                    "Sea" to row(0f, 0f, 1f, 0f),
                    "Bassist" to row(0f, 0f, 0f, 0f),
                    "Lead" to row(0f, 0f, 1f, 0f),
                ),
                pullInMatrix = bandMatrix(
                    "Drummer" to row(0f, 0f, 0.2f, 0f),
                    "Sea" to row(0f, 0f, 0.1f, 0f),
                    "Bassist" to row(0.2f, 0.1f, 0f, 0f),
                    "Lead" to row(0f, 0f, 0f, 0f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 4,
                barsPerLeadMin = 4, barsPerLeadMax = 8,
            ),
            tension = TensionProfile(innerBars = 8, outerBars = 16, outerDepth = 0.4f, volume = 0.3f, timing = 0.1f),
            levelDb = 1.9f,  // loudness match to -16 LUFS
            // Delay A a dotted 8th (0.70 s) and B a quarter (0.94 s) at 64 bpm.
            effects = VibeEffects(
                delayTimeA = 0.348f,
                delayTimeB = 0.466f,
                delayFeedback = 0.55f,
                reverbSize = 0.85f,
                reverbDamping = 0.5f,
                reverbBrightness = 0.35f,
                deepFloor = 0.4f,
            ),
            anomalies = listOf(VoidAnomaly(probability = 0.03f, floorLevel = 0.08f, ghostIntensity = 0.4f)),
            arrangement = Arrangement(
                sections = listOf(
                    // 0: the ping alone with the clock
                    Section(
                        name = "pings",
                        barsMin = 4, barsMax = 4, barStep = 2,
                        macroOverrides = MacroOverrides(energy = 0.35f, space = 1.2f),
                        rampTo = MacroOverrides(energy = 0.5f),
                        customProgression = chords(0),
                        chordsPerBar = 1,
                        trackOverrides = mapOf(
                            0 to out, 1 to out, 2 to clock(ticksQuarter, 0.45f), 3 to out, 4 to out, 5 to out,
                            6 to TrackSectionOverride(density = 0.14f),
                            7 to out,
                        ),
                        transitions = listOf(SectionTransition(1, 1f, transitionBars = 1)),
                    ),
                    // 1: the organ comes in loud, one slow kick a phrase
                    Section(
                        name = "organ swell",
                        barsMin = 4, barsMax = 4, barStep = 2,
                        macroOverrides = MacroOverrides(energy = 0.6f, volume = 0.75f),
                        rampTo = MacroOverrides(energy = 1.1f, volume = 1f),
                        trackOverrides = mapOf(
                            0 to TrackSectionOverride(hits = listOf(0), volume = 0.59f),
                            1 to out,
                            2 to clock(ticksQuarter, 0.52f),
                            3 to TrackSectionOverride(volume = 0.48f),
                            4 to TrackSectionOverride(density = 0f, reverbSend = 0.3f),
                            5 to TrackSectionOverride(volume = 0.42f, reverbSend = 0.55f),
                            7 to out,
                        ),
                        transitions = listOf(SectionTransition(2, 1f, transitionBars = 1)),
                    ),
                    // 2: the half-time pound, licks on the beat
                    Section(
                        name = "slow pound",
                        barsMin = 6, barsMax = 8, barStep = 2,
                        lickIndex = 0,
                        macroOverrides = MacroOverrides(energy = 1.3f, complexity = 0.9f),
                        trackOverrides = mapOf(
                            0 to TrackSectionOverride(hits = listOf(0, 6, 16, 22)),
                            1 to TrackSectionOverride(hits = listOf(8, 24)),
                            2 to clock(ticksEighth, 0.6f),
                            6 to TrackSectionOverride(density = 0.06f),
                            7 to out,
                        ),
                        transitions = listOf(SectionTransition(3, 1f, transitionBars = 1)),
                    ),
                    // 3: the bass takes the solo
                    Section(
                        name = "bass wander",
                        barsMin = 6, barsMax = 8, barStep = 2,
                        lickIndex = 0,
                        bassLineIndex = 5,
                        soloMode = SoloMode.Jam(probability = 1f, lickInfluence = 0.3f),
                        macroOverrides = MacroOverrides(energy = 1.25f, complexity = 1.2f),
                        trackOverrides = mapOf(
                            2 to clock(ticksEighth, 0.6f),
                            4 to TrackSectionOverride(density = 0.2f),
                            7 to out,
                        ),
                        transitions = listOf(SectionTransition(4, 1f, transitionBars = 2)),
                    ),
                    // 4: the band thins and the clock slows as it sinks
                    Section(
                        name = "sinking",
                        barsMin = 4, barsMax = 4, barStep = 2,
                        lickIndex = 0,
                        macroOverrides = MacroOverrides(energy = 1f, space = 1f),
                        rampTo = MacroOverrides(energy = 0.55f, space = 1.3f),
                        weather = SectionWeather(rumble = 0.15f, distance = 0.8f),
                        trackOverrides = mapOf(
                            0 to TrackSectionOverride(hits = listOf(0), volume = 0.5f),
                            1 to out,
                            2 to clock(ticksQuarter, 0.45f),
                            3 to TrackSectionOverride(volume = 0.5f),
                            6 to TrackSectionOverride(density = 0.1f),
                            7 to out,
                        ),
                        transitions = listOf(SectionTransition(5, 1f, transitionBars = 1)),
                    ),
                    // 5: one whale calls; splashes, a few drops, the clock at half notes
                    Section(
                        name = "lone whale",
                        barsMin = 4, barsMax = 6, barStep = 2,
                        lickIndex = 1,
                        macroOverrides = MacroOverrides(energy = 0.5f, space = 1.3f, mood = 0.8f),
                        customProgression = chords(0, 6),
                        chordsPerBar = 1,
                        weather = SectionWeather(rain = 0.05f, rainLevel = 0.35f, rumble = 0.1f, distance = 0.3f),
                        trackOverrides = mapOf(
                            0 to out,
                            1 to splash,
                            2 to clock(ticksHalf, 0.4f),
                            3 to out,
                            4 to TrackSectionOverride(envelopeProfile = EnvelopeProfile.DRONE, doubleDetune = 6f, doubleSpread = 0.3f),
                            5 to TrackSectionOverride(volume = 0.1f),
                            6 to TrackSectionOverride(density = 0.08f, reverbSend = 0.78f, delaySend = 0.62f),
                            7 to TrackSectionOverride(density = 0f, reverbSend = 0.85f),
                        ),
                        transitions = listOf(SectionTransition(6, 1f, transitionBars = 1)),
                    ),
                    // 6: the pod: call and answer, wide doubles, the bass a deep whale under the answer
                    Section(
                        name = "pod",
                        barsMin = 6, barsMax = 8, barStep = 2,
                        lickIndex = 2,
                        bassLineIndex = 4,
                        macroOverrides = MacroOverrides(energy = 0.6f, space = 1.3f, mood = 0.9f),
                        customProgression = chords(0, 6),
                        chordsPerBar = 1,
                        weather = SectionWeather(rain = 0.12f, rainLevel = 0.5f, rumble = 0.12f, distance = 0.2f),
                        trackOverrides = mapOf(
                            0 to out,
                            1 to splash.copy(density = 0.16f, volume = 0.35f),
                            2 to clock(ticksHalf, 0.4f),
                            3 to TrackSectionOverride(volume = 0.46f),
                            4 to TrackSectionOverride(envelopeProfile = EnvelopeProfile.DRONE, doubleDetune = 22f, doubleSpread = 0.9f),
                            5 to TrackSectionOverride(volume = 0.21f),
                            6 to TrackSectionOverride(density = 0.06f),
                        ),
                        transitions = listOf(SectionTransition(7, 1f, transitionBars = 2)),
                    ),
                    // 7: the clock rewinds, the organ swells back
                    Section(
                        name = "surfacing",
                        barsMin = 4, barsMax = 4, barStep = 2,
                        lickIndex = 3,
                        macroOverrides = MacroOverrides(energy = 0.65f),
                        rampTo = MacroOverrides(energy = 1.2f),
                        trackOverrides = mapOf(
                            0 to TrackSectionOverride(hits = listOf(0, 16), volume = 0.67f),
                            1 to out,
                            2 to clock(ticksQuarter, 0.52f),
                            3 to TrackSectionOverride(volume = 0.59f),
                            5 to TrackSectionOverride(volume = 0.42f),
                            6 to TrackSectionOverride(density = 0.1f),
                            7 to out,
                        ),
                        transitions = listOf(SectionTransition(8, 1f, transitionBars = 1)),
                    ),
                    // 8: the pound again, the lead's answer line
                    Section(
                        name = "pound returns",
                        barsMin = 6, barsMax = 8, barStep = 2,
                        lickIndex = 3,
                        macroOverrides = MacroOverrides(energy = 1.35f, complexity = 1f),
                        trackOverrides = mapOf(
                            0 to TrackSectionOverride(hits = listOf(0, 6, 16, 22)),
                            1 to TrackSectionOverride(hits = listOf(8, 24)),
                            2 to clock(ticksEighth, 0.6f),
                            4 to TrackSectionOverride(doubleDetune = 12f, doubleSpread = 0.5f),
                            7 to out,
                        ),
                        transitions = listOf(SectionTransition(9, 1f, transitionBars = 1)),
                    ),
                    // 9: the bass jams again while the clock races
                    Section(
                        name = "deep jam",
                        barsMin = 6, barsMax = 8, barStep = 2,
                        lickIndex = 3,
                        bassLineIndex = 5,
                        soloMode = SoloMode.Jam(probability = 1f, lickInfluence = 0.3f),
                        macroOverrides = MacroOverrides(energy = 1.4f, complexity = 1.3f),
                        trackOverrides = mapOf(
                            2 to clock(ticksRacing, 0.62f),
                            4 to TrackSectionOverride(density = 0.25f),
                            7 to out,
                        ),
                        transitions = listOf(SectionTransition(10, 1f, transitionBars = 2)),
                    ),
                    // 10: back to the ping; the clock ticks once a bar, then stops
                    Section(
                        name = "last pings",
                        barsMin = 4, barsMax = 4, barStep = 2,
                        macroOverrides = MacroOverrides(energy = 0.4f),
                        rampTo = MacroOverrides(energy = 0.3f),
                        customProgression = chords(0),
                        chordsPerBar = 1,
                        volumeEnvelope = listOf(VolumePoint(0f, 1f), VolumePoint(2f, 0.7f), VolumePoint(4f, 0f)),
                        trackOverrides = mapOf(
                            0 to out, 1 to out, 2 to clock(ticksWhole, 0.45f), 3 to out, 4 to out,
                            5 to TrackSectionOverride(volume = 0.21f),
                            6 to TrackSectionOverride(density = 0.14f),
                            7 to out,
                        ),
                    ),
                ),
                introIndex = 0,
                outroIndex = 10,
                playOnce = true,
            ),
            cast = CastProvenance(
                seed = 7019L,
                mode = "BED_FIXED",
                styleTags = listOf("ambient", "psych"),
                pins = listOf(
                    CastPinRecord(0, "kick", OrpheusEngineId.BD),
                    CastPinRecord(1, "snare", null),
                    CastPinRecord(2, "hat", null),
                    CastPinRecord(7, "perc", OrpheusEngineId.MOD),
                ),
            ),
        )
    }
}
