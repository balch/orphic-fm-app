package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.ArpDirection
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
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
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Blacktop Boogie — uptempo live blues-rock shuffle, all open road and elbow grease.
 *
 * A hard triplet shuffle (~168 BPM) over a literal 12-bar-blues frame: a walking
 * boogie bass drives the whole thing while a gritty guitar trades call-and-response
 * licks with honky-tonk piano and a Hammond bed. The form is the full 12-bar blues in
 * A Mixolydian (I-I-I-I-IV-IV-I-I-V-IV-I-V; degree 0 = I, 3 = IV, 4 = V). Built for a
 * sweaty live set: train-beat drums, busy shuffle hats, handclap percussion, a stop-time
 * "chorus" of stab chords answered by an ascending walk-up, and a structure of solos,
 * trades, and choruses.
 */
//@Inject
//@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class BlacktopBoogieVibe : VibeProvider {
    override val name: String = "Blacktop Boogie"

    // The full 12-bar blues in A Mixolydian: I-I-I-I-IV-IV-I-I-V-IV-I-V.
    //   A – A – A – A – D – D – A – A – E – D – A – E
    private val mainProgression = chords(0, 0, 0, 0, 3, 3, 0, 0, 4, 3, 0, 4)

    // Uptempo => snappier ramps than a slow blues.
    private val liftBars = 2
    private val dropBars = 2
    private val bigLiftBars = 4

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            bpm = 168f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.A,
            scaleType = ScaleType.MIXOLYDIAN,

            // ────────────────────────────────────────────────────────────────
            // THE HOOK — the walking boogie bass (played by track 3, lickMode =
            // Fill, chordFollow = FOLLOW so it walks and transposes across the
            // 12-bar). Classic root-3-5-6-b7 boogie up and back, two bars, steady
            // 8ths with accented beats. Degrees in A Mixolydian: 0=A, 2=C#(3rd),
            // 4=E(5th), 5=F#(6th), 6=G(b7). Reshape here to change the engine of
            // the whole tune — this bassline IS the song.
            // ────────────────────────────────────────────────────────────────
            lick = Lick(
                steps = listOf(
                    // bar 1 — boogie up and back
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f), // A   (beat 1)
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.80f), // C#
                    LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.88f), // E   (beat 2)
                    LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.80f), // F#
                    LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.90f), // G   (beat 3, b7 peak)
                    LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.80f), // F#
                    LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.85f), // E   (beat 4)
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.78f), // C#
                    // bar 2 — repeat with a walk-up turnaround
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.92f), // A
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.80f), // C#
                    LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.86f), // E
                    LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.80f), // F#
                    LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.88f), // G
                    LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.82f), // F#
                    LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.84f), // G  (turnaround push)
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.80f), // A  (land)
                ),
                loopLength = 8, // 2 bars, continuous — no rest, it never stops walking
            ),
            lickMutation = 0.30f, // boogie stays locked; solos nudge it

            band = Band(
                members = listOf(
                    BandMember(
                        "Drummer", listOf(0, 1, 2, 7), alwaysActive = true,
                        loudness = 0.7f, creativity = 0.25f,
                    ),
                    BandMember(
                        "Bassist", listOf(3),
                        loudness = 0.8f, creativity = 0.40f,
                    ),
                    // Guitar trades the licks — the live-set soloist.
                    BandMember(
                        "Guitar", listOf(4),
                        loudness = 0.7f, creativity = 0.60f,
                    ),
                    BandMember(
                        "Keys", listOf(5, 6),
                        loudness = 0.5f, creativity = 0.50f,
                    ),
                ),
                handoffMatrix = bandMatrix(
                    //            DRUM  BASS  GTR   KEYS
                    "Drummer" to row(0.00f, 0.25f, 0.50f, 0.25f),
                    "Bassist" to row(0.15f, 0.00f, 0.50f, 0.35f),
                    "Guitar"  to row(0.15f, 0.30f, 0.00f, 0.55f),
                    "Keys"    to row(0.15f, 0.35f, 0.50f, 0.00f),
                ),
                pullInMatrix = bandMatrix(
                    //            DRUM  BASS  GTR   KEYS
                    "Drummer" to row(0.00f, 0.30f, 0.30f, 0.20f),
                    "Bassist" to row(0.25f, 0.00f, 0.45f, 0.30f),
                    "Guitar"  to row(0.15f, 0.45f, 0.00f, 0.40f),
                    "Keys"    to row(0.15f, 0.35f, 0.45f, 0.00f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 4,
                barsPerLeadMin = 4, barsPerLeadMax = 8,
            ),

            energy = 0.70f,
            complexity = 0.50f,
            space = 0.30f,  // live but dry-ish; the room reverb does the spacing
            mood = 0.60f,   // upbeat major blues-rock
            deep = 0.30f,

            genre = GenreProfile(
                swingAmount = 0.30f,     // hard triplet shuffle
                ghostProbability = 0.25f,
                noteRangeLow = 33,       // A1
                noteRangeHigh = 67,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.BLUES,
                chordsPerBar = 1,        // one chord per bar = 12-bar feel
                customProgression = mainProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_8,
            progressionDriftRange = 0.15f,

            tracks = listOf(
                // 0: Kick — driving train pulse
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.85f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.50f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 1: Snare — hard backbeat, crack + room
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD,
                    volume = 0.70f,
                    harmonics = 0.45f,
                    timbre = 0.60f,
                    reverbSend = 0.18f,
                ).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.10f,
                        density = 0.40f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.FILL,
                    )
                },
                // 2: Hats — busy shuffle ride
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.55f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f,
                        density = 0.70f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 3: Boogie bass — THE hook. Punchy VCF on energy, VA on space.
                //    Plays the walking lick (Fill) and FOLLOWs the 12-bar so the
                //    figure transposes to I / IV / V. REPEAT keeps it relentless.
                //    (If the IV/V bars sound muddy from octave-folding, drop to
                //    ROOT_ONLY — you lose the walk but gain clarity.)
                OrpheusEngine(
                    engineId = OrpheusEngineId.VCF,
                    volume = 0.80f,
                    noteRangeLow = 33,
                    noteRangeHigh = 52,
                    reverbBrightness = 0.30f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.VA),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FOLLOW,
                            lickMode = LickMode.Fill,
                        ),
                        pan = 0.00f,
                        density = 0.55f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 4: Guitar — call-and-response licks. Gritty WSH on energy, FM on
                //    space. Generative (no lick) with CALL_RESPONSE so it answers
                //    the boogie/vocal across the bar. A touch of glide for bends.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.62f,
                    harmonics = 0.70f,
                    timbre = 0.64f,
                    morph = 0.50f,
                    noteRangeLow = 52,
                    noteRangeHigh = 76,
                    reverbSend = 0.18f,
                    delaySend = 0.15f,
                    glideRate = 0.20f,
                ).let { gtr ->
                    TrackVoice(
                        engineEdm = gtr,
                        engineSpace = gtr.copy(engineId = OrpheusEngineId.FM),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
                        pan = 0.18f,
                        density = 0.30f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.CALL_RESPONSE,
                    )
                },
                // 5: Honky-tonk piano — rolling blues comping. DX2 grand on energy,
                //    e.piano on space.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX2,
                    volume = 0.48f,
                    harmonics = 0.276f, // DX2 idx 9 "Grd Piano" (auto-pinned)
                    noteRangeLow = 48,
                    noteRangeHigh = 76,
                    reverbSend = 0.22f,
                    delaySend = 0.08f,
                ).let { piano ->
                    TrackVoice(
                        engineEdm = piano,
                        engineSpace = piano.copy(harmonics = 0.0f), // DX2 idx 0 "E piano 1"
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.BLUES_SHUFFLE,
                                arpMode = ArpMode.AUTO,
                                arpSpeed = 0.18f,
                                arpDirection = ArpDirection.UP_DOWN,
                                sectionInversion = SectionInversion.FIRST_INVERSION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.2f,
                                    ghostProbability = 0.25f,
                                    octaveJumpProbability = 0.3f,
                                    extensionProbability = 0.35f,
                                ),
                                // ASCENDING_ARP walk-up. CompingFills are track-global
                                // (the schema has no per-section fill), so this fires
                                // every 4 bars throughout: against the "chorus" section's
                                // stab comping it reads as "a couple of short chords, then
                                // a walkup"; elsewhere it's a bluesy ascending turnaround.
                                fills = CompingFills(
                                    everyNBars = 4,
                                    fillType = FillType.ASCENDING_ARP,
                                    skipProbability = 0.10f,
                                ),
                            ),
                        ),
                        pan = -0.22f,
                        density = 0.28f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 6: Hammond organ bed — sustained chords under the shuffle. DX3
                //    tonewheel on energy, string-machine swell on space.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX3,
                    volume = 0.30f,
                    harmonics = 0.031f, // DX3 idx 1 "Hammond" (auto-pinned)
                    noteRangeLow = 48,
                    noteRangeHigh = 72,
                    reverbSend = 0.30f,
                    reverbBrightness = 0.55f,
                ).let { organ ->
                    TrackVoice(
                        engineEdm = organ,
                        engineSpace = organ.copy(engineId = OrpheusEngineId.ENS),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.PAD, // sustained bed, not stabs
                                arpMode = ArpMode.NEVER,
                            ),
                        ),
                        pan = 0.26f,
                        density = 0.18f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 7: Handclap / shaker percussion — live-set energy. MOD on energy,
                //    PAR scatter on space.
                OrpheusEngine(
                    engineId = OrpheusEngineId.MOD,
                    volume = 0.36f,
                    modLfoRate = 0.10f,
                    modLfoDepth = 0.35f,
                    modLfoShape = 0.6f,
                    modLfoCoupling = 0.25f,
                    reverbSend = 0.22f,
                    delaySend = 0.10f,
                    noteRangeLow = 48,
                    noteRangeHigh = 72,
                    reverbBrightness = 0.55f,
                ).let { perc ->
                    TrackVoice(
                        engineEdm = perc,
                        engineSpace = perc.copy(engineId = OrpheusEngineId.PAR),
                        role = TrackRole.Percussive,
                        pan = 0.32f,
                        density = 0.30f,
                        envelopeProfile = EnvelopeProfile.WILD,
                        macroMap = TrackMacroMap.WILD,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            stepCount = 32,

            tension = TensionProfile(
                innerBars = 8,
                outerBars = 24,
                outerDepth = 0.6f,
                volume = 0.35f,
                tonal = TonalTension(octaveShift = true, chromaticPassing = 0.15f),
                timing = 0.20f, // tight shuffle — don't smear the triplet feel
                evolution = EvolutionTension(
                    timbreLow = 0.25f, timbreHigh = 0.60f, timbreProbability = 0.7f,
                    attackPoint = 0.5f, releaseSpeed = 0.4f,
                ),
                spurtChance = 0.15f, // lively
            ),

            effects = VibeEffects(
                delayTimeA = 0.25f,
                delayTimeB = 0.375f,
                delayFeedback = 0.30f,
                delayDamping = 0.5f,
                reverbSize = 0.50f,       // live room
                reverbDamping = 0.5f,
                reverbBrightness = 0.55f,
            ),

            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = 5,
                sections = listOf(
                    // 0: count-in — bass boogie + drums kick the tune off.
                    Section(
                        name = "intro",
                        barsMin = 2, barsMax = 4,
                        transitions = listOf(
                            SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = liftBars),
                        ),
                        macroOverrides = MacroOverrides(energy = 0.6f, complexity = 0.4f, space = 0.5f, mood = 0.9f),
                    ),
                    // 1: head — full band on the melody, the baseline.
                    Section(
                        name = "head",
                        barsMin = 6, barsMax = 10,
                        transitions = listOf(
                            SectionTransition(targetIndex = 6, weight = 0.40f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 2, weight = 0.30f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 3, weight = 0.20f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 4, weight = 0.10f, transitionBars = dropBars),
                        ),
                        recencyDecay = 0.5f,
                        macroOverrides = null,
                    ),
                    // 2: guitar solo — the soloist takes it.
                    Section(
                        name = "guitar solo",
                        barsMin = 8, barsMax = 16,
                        transitions = listOf(
                            SectionTransition(targetIndex = 6, weight = 0.35f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 1, weight = 0.30f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 3, weight = 0.20f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 4, weight = 0.15f, transitionBars = dropBars),
                        ),
                        recencyDecay = 0.4f,
                        macroOverrides = MacroOverrides(energy = 1.0f, complexity = 1.4f, space = 1.1f, mood = 1.2f),
                        soloMode = SoloMode.Jam(probability = 0.85f),
                    ),
                    // 3: trade — band trades fours, builds the boogie.
                    Section(
                        name = "trade",
                        barsMin = 8, barsMax = 12,
                        transitions = listOf(
                            SectionTransition(targetIndex = 6, weight = 0.35f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 1, weight = 0.30f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 2, weight = 0.20f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 4, weight = 0.15f, transitionBars = dropBars),
                        ),
                        recencyDecay = 0.45f,
                        macroOverrides = MacroOverrides(energy = 0.9f, complexity = 1.5f, space = 1.0f, mood = 1.2f),
                        soloMode = SoloMode.LickBuilder(probability = 0.8f, mutationRate = 0.30f),
                    ),
                    // 4: breakdown — bass + drums + claps, "let me hear you" before the lift.
                    Section(
                        name = "breakdown",
                        barsMin = 4, barsMax = 4,
                        transitions = listOf(
                            SectionTransition(targetIndex = 6, weight = 0.50f, transitionBars = bigLiftBars),
                            SectionTransition(targetIndex = 1, weight = 0.30f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 2, weight = 0.20f, transitionBars = liftBars),
                        ),
                        recencyDecay = 0.5f,
                        macroOverrides = MacroOverrides(energy = 0.5f, complexity = 0.5f, space = 1.3f, mood = 0.9f),
                    ),
                    // 5: outro — big stomping finish.
                    Section(
                        name = "outro",
                        barsMin = 4, barsMax = 6,
                        macroOverrides = MacroOverrides(energy = 1.5f, complexity = 0.6f, space = 0.5f, mood = 1.1f),
                    ),
                    // 6: chorus — the "title line". Stop-time stab chords (section
                    //    compingStyle = ROCK_DOWNBEATS makes BOTH chordal tracks hit on
                    //    1 & 3) answered by the piano's ASCENDING_ARP walk-up: "a couple
                    //    of short chords, then a walkup." Reached from the head and as the
                    //    big lift out of the breakdown. Appended at index 6 (after the
                    //    outro) so 0..5 stay put — section list order is irrelevant,
                    //    transitions are by index.
                    Section(
                        name = "chorus",
                        barsMin = 4, barsMax = 6,
                        transitions = listOf(
                            SectionTransition(targetIndex = 2, weight = 0.40f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 1, weight = 0.30f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 3, weight = 0.20f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 4, weight = 0.10f, transitionBars = dropBars),
                        ),
                        recencyDecay = 0.5f,
                        macroOverrides = MacroOverrides(energy = 1.2f, complexity = 1.1f, space = 0.8f, mood = 1.15f),
                        compingStyle = CompingStyle.ROCK_DOWNBEATS,
                    ),
                ),
            ),
        )
    }
}
