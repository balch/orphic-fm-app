package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Album
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
import org.balch.orpheus.features.pulsar.models.LpgMode
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
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Sunken Place — industrial dirge at 95 BPM in G#/Ab minor.
 *
 * Brooding, mechanical, sexual-tension groove: four-on-the-floor kick, heavy
 * processed backbeat snare, detuned gritty bass ostinato, sustained dark pads,
 * sparse distorted lead stabs, and noise/grain layers used as a musical element
 * rather than decoration. Hypnotic — builds through layering, not dynamics.
 *
 * Sparse hats (this is not techno — the groove is wide and brooding).
 * Bass locks to root (ROOT_ONLY) with a 2-note minor-key riff hammered
 * relentlessly; lead enters as an occasional piercing stab, not a melody.
 * Pads drone under everything and morph across a long outer-cycle tension arc.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class TechnoWobbleVibe : VibeProvider {

    private val mainProgression = chords(0, 0, 0, 0, 6, 5, 0, 3)
    private val chordsPerBar = 1

    private val chorusProgression = chords(3, 3, 5, 6, 0)
    private val chorusChordsPerBar = 2

    private val cleanHumanization = CompingHumanization(
        dropProbability = 0.05f,        // mostly hold each stab
        ghostProbability = 0.10f,
        octaveJumpProbability = 0.0f,   // PROTECT the 5th-in-bass voicing
        extensionProbability = 0.10f,   // sparse color
    )

    // Per-edge transitionBars precedent: name the *musical role* the ramp serves,
    // not the bar count. Lets future tuning happen in one place.
    //
    //   chorusLiftBars  — smooth macro climb INTO higher-energy sections
    //                     (intro -> groove, grind -> stab, fall -> grind, etc.)
    //   chorusDropBars  — long fade INTO low-energy / atmospheric sections
    //                     (grind -> fall, stab -> fall — the dramatic descent)
    //
    // 0-bar (the SectionTransition default) is a hard cut and is reserved for
    // moments that *want* to be jarring (e.g., entering the outro `drift`).
    private val chorusLiftBars = 2
    private val chorusDropBars = 4

    override val vibe = Vibe(
        name = "Techno Wobble",
        album = Album.RIF,
        bpm = 84f,
        envelopeType = EnvelopeType.BLEND,  // punchy drums, breathing pads
        rootNote = RootNote.G_SHARP,         // G# minor = Ab minor enharmonic
        scaleType = ScaleType.HARMONIC_MINOR,
        // The bass riff — a 2-bar ostinato. Minor-key, hammered on the root,
        // with a flat-6 dip that creates the sexual-tension pull. Low
        // velocities on the dip so the root accents stay heavy. Long final
        // hold lets the distortion bloom.
        lick = Lick(
            steps = listOf(
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),   // G# — hammer
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),   // G# — pulse
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.90f),   // G# — pulse
                LickStep(scaleDegree = -2, duration = 0.5f, velocity = 0.70f),  // F — dip below
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),   // G# — back, hit
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),
                LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.80f),   // C# — reach to 4th
                LickStep(scaleDegree = 0, duration = 1.0f, velocity = 0.90f),   // G# — resolve, hang
            ),
            loopLength = 8,
        ),
        lickMutation = 0.35f,  // mostly static — mechanical, but drifts over long arc
        band = Band(
            members = listOf(
                BandMember(
                    "Drummer", listOf(0, 1, 2), alwaysActive = true,
                    loudness = 0.8f, creativity = 0.2f, swing = 0.0f, drag = 0.0f,
                ),
                BandMember(
                    "Bassist", listOf(3),
                    loudness = 0.85f, creativity = 0.3f, swing = 0.0f, drag = 0.02f,
                ),
                BandMember(
                    "Lead", listOf(4),
                    loudness = 0.55f, creativity = 0.6f, swing = 0.0f, drag = 0.05f,
                ),
                BandMember(
                    "Pads", listOf(5, 6, 7),
                    loudness = 0.4f, creativity = 0.5f, swing = 0.0f, drag = 0.1f,
                ),
            ),
            handoffMatrix = bandMatrix(
                //             DRUM  BASS  LEAD  PADS
                "Drummer" to row(0.00f, 0.35f, 0.30f, 0.15f),
                "Bassist" to row(0.15f, 0.00f, 0.45f, 0.20f),
                "Lead"    to row(0.15f, 0.45f, 0.00f, 0.20f),
                "Pads"    to row(0.10f, 0.30f, 0.35f, 0.00f),
            ),
            pullInMatrix = bandMatrix(
                //             DRUM  BASS  LEAD  PADS
                "Drummer" to row(0.00f, 0.30f, 0.15f, 0.10f),
                "Bassist" to row(0.25f, 0.00f, 0.30f, 0.15f),
                "Lead"    to row(0.20f, 0.40f, 0.00f, 0.25f),
                "Pads"    to row(0.15f, 0.25f, 0.30f, 0.00f),
            ),
            pullInBarsMin = 4, pullInBarsMax = 8,   // slow, deliberate
            barsPerLeadMin = 6, barsPerLeadMax = 12, // leads hang forever — hypnotic
        ),
        // Brooding baseline — moderate energy, low complexity (rigid),
        // moderate space (reverb tails but not washy), low mood (dark).
        energy = 0.78f,
        complexity = 0.3f,
        space = 0.35f,
        mood = 0.55f,
        deep = 0.6f,
        genre = GenreProfile(
            swingAmount = 0.0f,          // dead straight — mechanical
            ghostProbability = 0.12f,    // sparse ghosts for grit
            noteRangeLow = 32,           // G#1/Ab1 — deep bass territory
            noteRangeHigh = 60,
            rhythmDensity = RhythmPattern.FOUR_ON_FLOOR.density,
            progressionStyle = ProgressionStyle.DARK,
            chordsPerBar = chordsPerBar,
            customProgression = mainProgression,
        ),
        progressionAnchor = ProgressionAnchor.EVERY_8,   // 8-bar hypnotic loop
        progressionDriftRange = 0.08f,                    // very tight — stays locked
        tracks = listOf(
            // Track 0: Kick — dead-straight four-on-the-floor, heavy
            OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.95f).let { kick ->
                TrackVoice(
                    engineEdm = kick,
                    engineSpace = kick,
                    role = TrackRole.Percussive,
                    pan = 0.00f,
                    density = 0.50f,   // 4-on-floor lands kicks on every quarter
                    envelopeProfile = EnvelopeProfile.RHYTHM,
                    macroMap = TrackMacroMap.RHYTHM,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // Track 1: Snare — processed, lo-fi thwack on 2 and 4
            OrpheusEngine(
                engineId = OrpheusEngineId.SD,
                volume = 0.72f,
                harmonics = 0.55f,  // slight noise boost for the processed snap
                timbre = 0.45f,
                reverbSend = 0.25f,  // a touch of reverb for the "big room" snare
                reverbBrightness = 0.35f,
            ).let { snare ->
                TrackVoice(
                    engineEdm = snare,
                    engineSpace = snare,
                    role = TrackRole.Percussive,
                    pan = -0.08f,
                    density = 0.35f,
                    envelopeProfile = EnvelopeProfile.RHYTHM,
                    macroMap = TrackMacroMap.RHYTHM,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // Track 2: Hihat — minimal, scratchy. This is NOT techno — hats
            // are an accent color, not a driving force.
            OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.40f).let { hat ->
                TrackVoice(
                    engineEdm = hat,
                    engineSpace = hat,
                    role = TrackRole.Percussive,
                    pan = 0.15f,
                    density = 0.22f,     // sparse — not a 16th-note stream
                    envelopeProfile = EnvelopeProfile.RHYTHM,
                    macroMap = TrackMacroMap.RHYTHM,
                    barStrategy = BarStrategy.MUTATE,
                )
            },
            // Track 3: Bass — the engine of the whole thing. Gritty WSH engine
            // with the lick locked in. ROOT_ONLY so the chord motion transposes
            // the riff cleanly without any extra wandering. Fill mode so the
            // lick is a full-bar statement, not a sub-bar stab.
            //
            // WSH (EDM slot) needs the LPG vactrol pluck so its raw oscillator
            // gets an articulate attack. STR (SPACE slot) already has its own
            // physical-model decay envelope — BYPASS lets it ride at full volume
            // instead of being doubly attenuated.
            OrpheusEngine(
                engineId = OrpheusEngineId.WSH,
                volume = 0.85f,
                harmonics = 0.42f,
                timbre = 0.55f,
                morph = 0.35f,
                noteRangeLow = 32,   // G#1/Ab1 — deep
                noteRangeHigh = 50,
                reverbBrightness = 0.25f,  // dark bass, not bright
                lpgMode = LpgMode.PLUCK,
            ).let { bass ->
                TrackVoice(
                    engineEdm = bass,
                    engineSpace = bass.copy(engineId = OrpheusEngineId.STR, lpgMode = LpgMode.BYPASS),
                    role = TrackRole.Melodic(
                        chordFollow = ChordFollow.ROOT_ONLY,
                        lickMode = LickMode.Fill,
                    ),
                    pan = 0.00f,
                    density = 0.55f,
                    envelopeProfile = EnvelopeProfile.MELODIC,
                    macroMap = TrackMacroMap.MELODIC,
                    barStrategy = BarStrategy.REPEAT,
                )
            },
            // Track 4: Lead stabs — distorted mid-register, sparse but piercing.
            // CHORDAL with ROCK_DOWNBEATS style so it hits on 1 and 3 alongside
            // the kick, but with octave jumps and drops so it feels
            // unpredictable and aggressive — not a tidy chord pattern.
            OrpheusEngine(
                engineId = OrpheusEngineId.WSH,
                volume = 0.55f,
                harmonics = 0.75f,   // heavy distortion character
                timbre = 0.60f,
                morph = 0.50f,
                noteRangeLow = 48,   // C3 — mid range
                noteRangeHigh = 68,
                reverbSend = 0.35f,  // wet — long tail on every stab
                delaySend = 0.20f,
                reverbBrightness = 0.55f,
                delayFeedback = 0.45f, // trailing echoes
                glideRate = 0.08f,
            ).let { stab ->
                TrackVoice(
                    engineEdm = stab,
                    engineSpace = stab.copy(engineId = OrpheusEngineId.STR),
                    role = TrackRole.Chordal(
                        comping = ChordComping(
                            style = CompingStyle.ROCK_DOWNBEATS,
                            arpMode = ArpMode.NEVER,  // straight stabs, no arp
                            sectionInversion = SectionInversion.ROOT_POSITION,
                            humanization = CompingHumanization(
                                dropProbability = 0.30f,       // lots of dropped stabs — sparse, deliberate
                                ghostProbability = 0.15f,
                                octaveJumpProbability = 0.20f, // occasional octave leaps
                                extensionProbability = 0.10f,  // sparse color
                            ),
                            fills = CompingFills(
                                everyNBars = 8,
                                fillType = FillType.TURNAROUND,
                                skipProbability = 0.5f,
                            ),
                        ),
                    ),
                    pan = 0.10f,
                    density = 0.28f,     // sparse — each hit matters
                    envelopeProfile = EnvelopeProfile.MELODIC,
                    macroMap = TrackMacroMap.MELODIC,
                    barStrategy = BarStrategy.MUTATE,
                )
            },
            // Track 5: Pad — dark sustained string ensemble. Drones under
            // everything with slow LFO timbre drift. This is the bed.
            OrpheusEngine(
                engineId = OrpheusEngineId.DX3,
                volume = 0.45f,
                harmonics = 0.30f,   // dark
                timbre = 0.25f,
                morph = 0.15f,
                modLfoRate = 0.04f,  // glacial drift
                modLfoDepth = 0.65f,
                modLfoShape = 0.2f,  // sine-ish, smooth
                modLfoCoupling = 0.4f,
                holdProbability = 0.85f,  // hang forever
                holdLengthMin = 8,
                holdLengthMax = 24,
                reverbSend = 0.60f,
                delaySend = 0.25f,
                noteRangeLow = 44,   // G#2 area
                noteRangeHigh = 62,
                reverbBrightness = 0.35f,  // dark reverb
                glideRate = 0.6f,
            ).let { pad ->
                TrackVoice(
                    engineEdm = pad,
                    engineSpace = pad,
                    role = TrackRole.Melodic(),
                    pan = -0.35f,
                    density = 0.12f,     // very sparse hits but long-held
                    envelopeProfile = EnvelopeProfile.DRONE,
                    macroMap = TrackMacroMap.EFFECT,
                    barStrategy = BarStrategy.INDEPENDENT,
                )
            },
            // Track 6: Noise/grain texture — the "dirt" layer. Distortion
            // crackle and grain clouds as a musical element, not an accent.
            // Panned opposite the pad for width.
            OrpheusEngine(
                engineId = OrpheusEngineId.GRN,
                volume = 0.30f,
                harmonics = 0.55f,
                timbre = 0.45f,
                morph = 0.50f,
                modLfoRate = 0.08f,
                modLfoDepth = 0.55f,
                modLfoShape = 0.7f,   // more angular — mechanical
                modLfoCoupling = 0.3f,
                holdProbability = 0.35f,
                holdLengthMin = 3,
                holdLengthMax = 10,
                reverbSend = 0.40f,
                delaySend = 0.15f,
                noteRangeLow = 40,
                noteRangeHigh = 60,
                reverbBrightness = 0.30f,
            ).let { texture ->
                TrackVoice(
                    engineEdm = texture,
                    engineSpace = texture.copy(engineId = OrpheusEngineId.NSE),
                    role = TrackRole.Percussive,
                    pan = 0.35f,
                    density = 0.18f,
                    envelopeProfile = EnvelopeProfile.EFFECT,
                    macroMap = TrackMacroMap.EFFECT,
                    barStrategy = BarStrategy.INDEPENDENT,
                )
            },
            // Track 7: Wild card — modal resonator for the industrial "clang."
            // Metallic hits that surface occasionally. WILD macro map so it
            // gets swept unpredictably as the macros move.
            OrpheusEngine(
                engineId = OrpheusEngineId.MOD,
                volume = 0.25f,
                harmonics = 0.65f,
                timbre = 0.55f,
                morph = 0.70f,
                modLfoRate = 0.05f,
                modLfoDepth = 0.45f,
                modLfoShape = 0.6f,
                modLfoCoupling = 0.35f,
                holdProbability = 0.4f,
                holdLengthMin = 4,
                holdLengthMax = 12,
                reverbSend = 0.55f,
                delaySend = 0.35f,
                noteRangeLow = 38,
                noteRangeHigh = 66,
                reverbBrightness = 0.40f,
                glideRate = 0.25f,
            ).let { wild ->
                TrackVoice(
                    engineEdm = wild,
                    engineSpace = wild,
                    role = TrackRole.Melodic(),
                    pan = 0.00f,
                    density = 0.08f,     // very sparse — dramatic interruptions
                    envelopeProfile = EnvelopeProfile.WILD,
                    macroMap = TrackMacroMap.WILD,
                    barStrategy = BarStrategy.INDEPENDENT,
                )
            },
        ),
        stepCount = 32,  // 32-step patterns support the 8-bar hypnotic loop
        tension = TensionProfile(
            innerBars = 8, outerBars = 32, outerDepth = 0.7f,  // long arcs
            volume = 0.30f,        // subtle volume tension — keep it relentless
            tonal = TonalTension(
                octaveShift = false,   // bass stays in the deep
                chromaticPassing = 0.18f,
            ),
            timing = 0.10f,        // very tight — this is machine music
            evolution = EvolutionTension(
                timbreLow = 0.25f, timbreHigh = 0.70f, timbreProbability = 0.85f,  // breathing distortion
                morphLow = 0.30f,  morphHigh = 0.65f, morphProbability = 0.6f,
                harmonicsLow = 0.40f, harmonicsHigh = 0.75f, harmonicsProbability = 0.5f,
                attackPoint = 0.6f,    // build past midpoint — slow inevitability
                releaseSpeed = 0.25f,  // slow decay — hang in the tension
            ),
            spurtChance = 0.08f,   // occasional bursts — not jittery
        ),
        effects = VibeEffects(
            // Long echoes for the industrial reverb-tail feel
            delayTimeA = 0.375f,     // dotted-8th feel at 95 BPM
            delayTimeB = 0.5f,       // half-note echo layer
            delayFeedback = 0.45f,   // long trails but not runaway
            delayDamping = 0.55f,    // darkens each repeat
            reverbSize = 0.70f,      // big industrial hall
            reverbDamping = 0.60f,
            reverbBrightness = 0.40f, // dark reverb — not sparkly
            deepFloor = 0.35f,
        ),
        arrangement = Arrangement(
            introIndex = 0,
            sections = listOf(
                // 0: pulse — opens with kick + pad only. Dread builds.
                // pulse -> grind: chorusLift (smooth climb into the verse).
                Section(
                    name = "pulse",
                    barsMin = 6, barsMax = 6,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = chorusLiftBars),
                    ),
                    macroOverrides = MacroOverrides(
                        energy = 0.45f, complexity = 0.3f, space = 1.3f, mood = 0.7f,
                    ),
                ),
                // 1: grind — the main groove. All 8 tracks in, full weight.
                // grind -> stab: chorusLift (the chorus rises in).
                // grind -> fall: chorusDrop (long fade into the breakdown).
                // grind -> drift: hard cut (sudden end of the song).
                Section(
                    name = "grind",
                    barsMin = 8, barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 2, weight = 0.5f, transitionBars = chorusLiftBars),
                        SectionTransition(targetIndex = 3, weight = 0.3f, transitionBars = chorusDropBars),
                        SectionTransition(targetIndex = 4, weight = 0.2f),  // 0-bar = hard outro entry
                    ),
                    recencyDecay = 0.6f,
                    macroOverrides = null,  // grind IS the baseline
                ),
                // 2: stab — lead gets aggressive, pads push forward, lick mutates.
                // compingHumanization suppresses octave jumps so SECOND_INVERSION
                // pedal voicing reads cleanly — track-level humanization
                // (octaveJump=0.20) would scramble it.
                // stab -> grind: chorusLift (gentle return to verse).
                // stab -> fall:  chorusDrop (dramatic glide down into the breakdown).
                // stab -> drift: hard cut (sudden ending after the chorus).
                Section(
                    name = "stab",
                    barsMin = 6, barsMax = 10,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.5f, transitionBars = chorusLiftBars),
                        SectionTransition(targetIndex = 3, weight = 0.3f, transitionBars = chorusDropBars),
                        SectionTransition(targetIndex = 4, weight = 0.2f),
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = MacroOverrides(
                        energy = 1.3f, complexity = 1.4f, space = 0.7f, mood = 1.2f,
                    ),
                    soloMode = SoloMode.LickBuilder(probability = 0.75f, mutationRate = 0.5f),
                    compingStyle = CompingStyle.BLUES_SHUFFLE,
                    customProgression = chorusProgression,
                    chordsPerBar = chorusChordsPerBar,
                    compingInversion = SectionInversion.SECOND_INVERSION,
                    compingHumanization = cleanHumanization,
                    // Stab adds melodic motion via arpeggiated lead stabs and lifts
                    // the MOD clang into a chorus signature rather than a rare accent.
                    trackOverrides = mapOf(
                        4 to TrackSectionOverride(
                            delaySend = .6f,
                            reverbSend = .6f,
                            holdProbability = .6f,
                            holdLengthMin = 2,
                            holdLengthMax = 8,
                            chordFollow = ChordFollow.FOLLOW
                        ),
                        7 to TrackSectionOverride(
                            volume = 0.45f,              // ringing clang foregrounded
                            delaySend = 0.50f,
                        ),
                    ),
                ),
                // 3: fall — stripped to bass+kick+pad drone. Dangerous space.
                // Lead gone, hats gone, grain whispers. Only the heart beats.
                // fall -> grind / fall -> stab: chorusLift (re-emerging from the dread).
                // fall -> drift: hard cut.
                Section(
                    name = "fall",
                    barsMin = 6, barsMax = 10,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.6f, transitionBars = chorusLiftBars),
                        SectionTransition(targetIndex = 2, weight = 0.3f, transitionBars = chorusLiftBars),
                        SectionTransition(targetIndex = 4, weight = 0.1f),
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = MacroOverrides(
                        energy = 0.35f, complexity = 0.4f, space = 1.6f, mood = 0.6f,
                    ),
                    chordFollow = ChordFollow.FIXED,  // bass drones on G#, no motion
                    compingStyle = CompingStyle.BLUES_SHUFFLE,
                    compingHumanization = cleanHumanization,
                    // The breakdown trades aggression for atmosphere. The lead
                    // (track 4) transforms from sharp stabs into a quiet held
                    // drone — same instrument, opposite role. Pad relentlessly
                    // sustains; grain texture recedes into ambient whisper.
                    trackOverrides = mapOf(
                        4 to TrackSectionOverride(
                            compingStyle = CompingStyle.PAD,        // continuous chord — held drone
                            arpMode = ArpMode.NEVER,
                            envelopeProfile = EnvelopeProfile.EFFECT,
                            volume = 0.20f,
                        ),
                        5 to TrackSectionOverride(
                            holdProbability = 0.95f,
                            holdLengthMin = 16,
                            holdLengthMax = 28,
                            volume = 0.55f,
                        ),
                        6 to TrackSectionOverride(
                            volume = 0.15f,
                            reverbSend = 0.75f,
                        ),
                    ),
                ),
                // 4: drift — the ending. Everything decays, pad holds, kick fades.
                // True outro: empty transitions terminate the arrangement.
                // drift -> grind: chorusLift (loop re-entry rises smoothly).
                Section(
                    name = "drift",
                    barsMin = 3, barsMax = 4,
                    macroOverrides = MacroOverrides(
                        energy = 0.25f, space = 1.7f, mood = 0.9f,
                    ),
                    chordFollow = ChordFollow.ROOT_ONLY,
                    transitions = listOf(
                            SectionTransition(targetIndex = 1, weight = 1f, transitionBars = chorusLiftBars),
                    ),
                    compingHumanization = cleanHumanization,
                    // Drift is the deliberate decay. Kick fades, lead becomes
                    // sparse held decays, pad holds nearly forever, MOD wildcard
                    // becomes a distant ghost-ring. Each instrument hands off
                    // its character to the reverb.
                    trackOverrides = mapOf(
                        0 to TrackSectionOverride(volume = 0.40f),  // kick fades
                        4 to TrackSectionOverride(
                            compingStyle = CompingStyle.PAD,
                            arpMode = ArpMode.NEVER,
                            envelopeProfile = EnvelopeProfile.EFFECT,
                            volume = 0.15f,
                        ),
                        5 to TrackSectionOverride(
                            holdProbability = 0.95f,
                            holdLengthMin = 24,
                            holdLengthMax = 28,
                        ),
                        7 to TrackSectionOverride(
                            volume = 0.10f,
                            reverbSend = 0.85f,
                            delaySend = 0.65f,
                        ),
                    ),
                ),
            ),
        ),
    )
}
