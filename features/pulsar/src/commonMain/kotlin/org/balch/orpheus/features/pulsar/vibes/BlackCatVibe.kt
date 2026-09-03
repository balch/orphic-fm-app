package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.ArpDirection
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.Arrangement
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
import org.balch.orpheus.features.pulsar.models.chords

/**
 * Black Cat — a greasy southern soul-blues strut prowling a two-bar pentatonic hook,
 * answered by horn stabs.
 *
 * ## The feel
 * Mid-slow and laid back but insistent — a sixties soul-revue rhythm section playing
 * a hard-luck blues. THE BASS IS THE SONG: a round, fingered two-bar hook prowls the
 * low register while a tight dry backbeat rides underneath. A brass section punches
 * short syncopated stabs into the hook's rest pockets, a stinging lead answers between
 * phrases with slow bends, and a low-mixed drawbar organ holds the bed. Production is
 * dry and forward — small smoky club, not a hall.
 *
 * ## The hook (why it reads as a hook)
 * Two-bar phrase in BLUES-scale degrees (see mapping below): bar 1 opens ON TOP with
 * a stuttered octave and falls home early — landing the root mid-phrase, not at the
 * end; bar 2 opens with SPACE (the horns answer there), then prowls back up and ends
 * on the b7, leaning into the octave that opens the next loop. The unresolved b7 is
 * what makes the 2-bar figure circular. Rests are real rests (negative degrees) —
 * the call-and-response is composed into the lick, not left to chance.
 *
 * ## Scale mapping
 * Root = C#, ScaleType.BLUES (degrees {0,3,5,6,7,10} semitones). A LickStep's
 * scaleDegree indexes that list: 0 = C# (root), 1 = E (b3), 2 = F# (4th),
 * 3 = G (b5 — the blue note), 4 = G# (5th), 5 = B (b7), 6 = C# (octave).
 * The hook is PURE PENTATONIC — degree 3 (the b5) never appears in it; the blue
 * note is reserved for the answering lead, which draws from the full scale.
 *
 * ## Chord-degree caveat
 * chords(...) degrees are DIATONIC (0=I, 3=IV, 4=V, 6=bVII) and do NOT share the
 * lick's scale-note indexing — FireSkyVibe ear-proved this pairing on BLUES.
 *
 * ## Arrangement
 * intro (slowed cold open: the hook + kick alone, accelerando into the drop) ->
 * verse (full pocket, one-chord vamp — the hook never transposes) -> lift (the
 * payoff: IV-IV-i-i, horns come forward, hook transposes with the roots) -> jam
 * (band stretches out over a vamp with an IV lean) -> breakdown (drums + naked
 * hook, one long build) -> outro (V-IV-i turnaround, full band, terminal).
 * A/B against DogHouseVibe and RustedCoastVibe.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class BlackCatVibe : VibeProvider {
    override val name: String = "Black Cat"

    // Verse hangs on the i — the hook carries all the motion; transposition is
    // saved for the lift, where it lands as an event.
    private val verseProgression = chords(0)

    // The lift: IV -> IV -> i -> i. ROOT_ONLY bass transposes the hook up with
    // the chord roots — the payoff moment.
    private val liftProgression = chords(3, 3, 0, 0)

    // Jam seesaw — planted on the i with an IV lean every 4th bar for solo shape.
    private val jamProgression = chords(0, 0, 0, 3)

    // Outro turnaround: V -> IV -> i -> i, ride it home.
    private val outroProgression = chords(4, 3, 0, 0)

    // Per-edge transition ramps named for their musical role.
    private val liftBars = 2
    private val dropBars = 2
    private val bigLiftBars = 4

    val sectionList by lazy {
        listOf(
            // 0: intro — slowed cold open so you can PICK OUT the hook: bass + a soft
            //    kick at ~69 BPM (81×0.85), everything else out. Over the last bar the
            //    tempo winds UP (bpmRampBars) into the full-tempo verse.
            Section(
                name = "intro",
                barsMin = 2, barsMax = 2,   // the 2-bar hook stated twice, slow
                bpmMultiplier = 0.85f,      // ~69 BPM — slowed so the hook reads
                bpmRampBars = 1,            // accelerando over the last bar into the drop
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = liftBars),
                ),
                macroOverrides = MacroOverrides(
                    energy = 0.7f, complexity = 0.5f, space = 0.9f, mood = 0.95f,
                ),
                customProgression = verseProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    1 to TrackSectionOverride(density = 0.0f),   // snare out — unmask the hook
                    2 to TrackSectionOverride(density = 0.0f),   // hats out
                    4 to TrackSectionOverride(density = 0.0f),   // horns out
                    5 to TrackSectionOverride(density = 0.0f),   // lead out
                    6 to TrackSectionOverride(density = 0.0f),   // organ out
                    7 to TrackSectionOverride(density = 0.05f),  // shaker barely there
                ),
            ),
            // 1: verse — the full pocket, baseline. One-chord vamp; the hook rules.
            Section(
                name = "verse",
                barsMin = 8, barsMax = 10,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.55f, transitionBars = liftBars),  // -> lift
                    SectionTransition(targetIndex = 3, weight = 0.25f, transitionBars = liftBars),  // -> jam
                    SectionTransition(targetIndex = 4, weight = 0.20f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.5f,
                macroOverrides = null,
            ),
            // 2: lift — the payoff. Hook transposes with IV -> IV -> i -> i; the horn
            //    section comes FORWARD and the lead wakes up. Bigger but still dry.
            Section(
                name = "lift",
                barsMin = 4, barsMax = 8, barStep = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 0.45f, transitionBars = dropBars),  // -> verse
                    SectionTransition(targetIndex = 3, weight = 0.30f, transitionBars = liftBars),  // -> jam
                    SectionTransition(targetIndex = 4, weight = 0.25f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 1.3f, complexity = 1.15f, space = 0.85f, mood = 1.2f,
                ),
                customProgression = liftProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(density = 0.40f, volume = 0.58f),  // horns punch forward
                    5 to TrackSectionOverride(density = 0.22f),                  // lead answers more
                    6 to TrackSectionOverride(density = 0.30f),                  // organ swells in
                ),
            ),
            // 3: jam — the band stretches out; the answering lead thins so the solo system owns
            //    the lead voice, horns thin to accents.
            Section(
                name = "jam",
                barsMin = 8, barsMax = 12,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.45f, transitionBars = liftBars),  // -> lift
                    SectionTransition(targetIndex = 1, weight = 0.35f, transitionBars = liftBars),  // -> verse
                    SectionTransition(targetIndex = 4, weight = 0.20f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.4f,
                macroOverrides = MacroOverrides(
                    energy = 0.95f, complexity = 1.35f, space = 1.5f, mood = 1.1f,
                ),
                customProgression = jamProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(density = 0.14f),                  // horns thin to accents
                    // NOT 0: density 0 is a render mute for the whole section, and the solo
                    // system does not lift it — the lead's own jam line would be inaudible.
                    5 to TrackSectionOverride(density = 0.10f),                  // lead steps back for the soloist
                    6 to TrackSectionOverride(volume = 0.50f, delaySend = 0.3f), // organ blooms a little
                    7 to TrackSectionOverride(reverbSend = 0.4f),
                ),
                soloMode = SoloMode.Jam(probability = 0.8f, lickInfluence = 0.8f),
            ),
            // 4: breakdown — drums + naked hook; one long anticipation build back
            //    into the lift.
            Section(
                name = "breakdown",
                barsMin = 4, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.70f, transitionBars = bigLiftBars), // -> lift (THE build)
                    SectionTransition(targetIndex = 1, weight = 0.30f, transitionBars = liftBars),    // -> verse
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 0.45f, complexity = 0.6f, space = 1.4f, mood = 0.9f,
                ),
                customProgression = verseProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(density = 0.0f),   // horns out
                    5 to TrackSectionOverride(density = 0.0f),   // lead out
                    6 to TrackSectionOverride(density = 0.12f),  // organ whisper holds the bed
                    7 to TrackSectionOverride(density = 0.08f),
                ),
            ),
            // 5: outro — the V-IV-i turnaround, full band, ride it home. Terminal.
            Section(
                name = "outro",
                barsMin = 4, barsMax = 8, barStep = 4,
                macroOverrides = MacroOverrides(
                    energy = 1.35f, complexity = 0.9f, space = 0.8f, mood = 1.15f,
                ),
                customProgression = outroProgression,
                chordsPerBar = 1,
            ),
        )
    }

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            album = Album.RIF,
            bpm = 81f,
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = sectionList.lastIndex,
                lengthSeconds = 150..240,
                sections = sectionList,
            ),
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.C_SHARP,
            scaleType = ScaleType.BLUES,
            seed = 0,
            // The jam's Jam solo needs a cast to hand around; without one the engine never starts
            // it. Both melodic voices are members, so the hook bass and the lead trade the solo;
            // horns and organ comp together.
            band = BandPresets.quartet(
                kit = listOf(0, 1, 2, 7),
                bass = listOf(3),
                lead = listOf(5),
                colour = listOf(4, 6),
            ),
            // --- macro defaults: greasy mid-energy strut, dry-forward ---
            energy = 0.56f,
            complexity = 0.70f,
            space = 0.34f,
            mood = 0.52f,
            deep = 0.30f,
            // --- THE HOOK (track 3 bass plays it as LickMode.Fill) ---
            // BLUES degrees: 0=C#(root), 1=E(b3), 2=F#(4), 3=G(b5 — unused here),
            // 4=G#(5), 5=B(b7), 6=C#(oct).
            // Copyright-safe rewrite: keeps the greasy C# pentatonic strut (scale, low
            // register, stutter, rest pockets sized for horn answers) but inverts the
            // recognizable signature — opens ON TOP with a stuttered octave and FALLS
            // (the source climbs to it), lands home mid-phrase instead of at the end,
            // and bar 2 leads with space before a low prowl that ends on the b7 leaning
            // into the loop restart. Faithful original preserved in BlackCatOgVibe
            // (WIP, -Pcatalog). 2 bars, sums to exactly 8.0 beats.
            lick = Lick(
                steps = listOf(
                    // Bar 1 — fall from the top, land home early
                    LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.94f),   // C# oct — open on top
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 6, duration = 0.25f, velocity = 0.85f),  // oct stutter, displaced
                    LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.88f),   // B  b7
                    LickStep(scaleDegree = 4, duration = 0.75f, velocity = 0.86f),  // G# 5 — dotted lean
                    LickStep(scaleDegree = 2, duration = 0.25f, velocity = 0.78f),  // F# 4 passing
                    LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.84f),   // E  b3
                    LickStep(scaleDegree = 0, duration = 1.0f, velocity = 0.96f),   // C# root — home EARLY
                    // Bar 2 — horn pocket first, then the prowl back up
                    LickStep(scaleDegree = -1, duration = 0.75f, velocity = 0.0f),  // (rest — horns answer here)
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.82f),  // C# pickup
                    LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.84f),   // E  b3
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.80f),   // C# root
                    LickStep(scaleDegree = 2, duration = 0.75f, velocity = 0.86f),  // F# 4 — dotted
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.88f),   // G# 5
                    LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.90f),   // B  b7 — leans into the loop
                ),
                loopLength = 8,  // 2 bars; steps sum to 8.0 exactly
            ),
            lickMutation = 0.20f,  // run-to-run drift off the figure = extra copyright distance
            lickOctave = -1,       // auto = midpoint of the bass range (lands around C#2)
            genre = GenreProfile(
                swingAmount = 0.11f,          // laid-back grease — behind the beat, not a shuffle
                ghostProbability = 0.25f,     // funky snare ghosts
                noteRangeLow = 36,
                noteRangeHigh = 64,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.BLUES,
                chordsPerBar = 1,
                customProgression = verseProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_8,
            progressionDriftRange = 0.10f,  // planted — the hook supplies the motion
            tracks = listOf(
                // Track 0 — Kick (BD): fat, laid back, locked.
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.88f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.45f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 1 — Snare (SD): the big dry crack on 2 & 4, fills at phrase ends.
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD,
                    volume = 0.68f,
                    reverbSend = 0.14f,
                ).let { snare ->
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
                // Track 2 — Hat (HH): tight 8ths riding the pocket.
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.52f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f,
                        density = 0.50f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 3 — BASS (PD warm-round): THE HOOK. LickMode.Fill owns the whole
                // 2-bar phrase; PLUCK gives the fingered attack; ROOT_ONLY so the lift
                // transposes the hook with the chord roots (the payoff moment).
                OrpheusEngine(
                    engineId = OrpheusEngineId.PD,
                    volume = 0.88f,
                    noteRangeLow = 25,        // C#1
                    noteRangeHigh = 49,       // C#3 — auto lick octave centers ~C#2
                    reverbSend = 0.06f,       // nearly dry — forward in the mix
                    reverbBrightness = 0.30f,
                    glideRate = 0.06f,        // tiny finger-slur between close notes
                    lpgMode = LpgMode.PLUCK,  // fingered articulation, note-by-note
                    lpgDecay = 0.52f,
                    lpgColour = 0.45f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(
                            lpgMode = LpgMode.SUSTAINED,  // rounder, smokier at low energy
                            reverbSend = 0.16f,
                        ),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.ROOT_ONLY,
                            lickMode = LickMode.Fill,  // the full 2-bar hook as one phrase
                        ),
                        pan = 0.00f,
                        density = 0.95f,  // the hook fires on (nearly) every step
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,  // locked; variation = lickMutation only
                    )
                },
                // Track 4 — HORNS (DX3 brass, auto-pinned patch selector): short punchy
                // stabs into the hook's rest pockets. FUNK_STABS supplies the syncopated
                // velocity pattern; near-simultaneous arp reads as a section hit.
                // 0.888 = idx 28 "Syn orch"; space slot 0.919 sits on a bucket edge and
                // loads idx 29 "Brass 1" or 30 "Brass 6 BC" depending on the previously
                // loaded patch. Both ear-tuned — see fm_patches.md on legacy edge values.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX3,
                    volume = 0.48f,
                    harmonics = 0.888f,       // DX3 idx 28 "Syn orch" (auto-pinned, ear-tuned)
                    noteRangeLow = 56,        // G#3
                    noteRangeHigh = 74,       // D5
                    reverbSend = 0.18f,
                    delaySend = 0.06f,
                    reverbBrightness = 0.50f,
                    lpgMode = LpgMode.PLUCK,  // stab articulation
                    lpgDecay = 0.45f,
                ).let { horns ->
                    TrackVoice(
                        engineEdm = horns,
                        engineSpace = horns.copy(
                            harmonics = 0.919f,           // "Brass 6 BC" — breathier
                            lpgMode = LpgMode.SUSTAINED,  // smoky held brass at low energy
                            reverbSend = 0.28f,
                        ),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.FUNK_STABS,
                                arpMode = ArpMode.AUTO,          // DX3 = arp; speed makes it a stab
                                arpSpeed = 0.92f,                // near-simultaneous section hit
                                arpDirection = ArpDirection.UP,
                                sectionInversion = SectionInversion.ROOT_POSITION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.15f,
                                    ghostProbability = 0.12f,
                                    octaveJumpProbability = 0.08f,
                                    extensionProbability = 0.20f,  // bluesy 7th/9th color
                                ),
                                fills = CompingFills(
                                    everyNBars = 8,
                                    fillType = FillType.TURNAROUND,  // section rip at the turnaround
                                    skipProbability = 0.25f,
                                ),
                            ),
                        ),
                        pan = 0.22f,
                        density = 0.24f,  // answers, never a wall
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 5 — LEAD (FM): stinging answers between the hook's phrases.
                // The b5 blue note lives HERE (full-scale pattern gen), not in the hook;
                // big glide approximates the bends. CALL_RESPONSE waits for the hook.
                OrpheusEngine(
                    engineId = OrpheusEngineId.FM,
                    volume = 0.50f,
                    noteRangeLow = 57,        // A3
                    noteRangeHigh = 79,       // G5
                    reverbSend = 0.16f,
                    delaySend = 0.22f,        // slapback sting
                    reverbBrightness = 0.55f,
                    glideRate = 0.42f,        // the bends — notes pull into each other
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.55f,
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead.copy(lpgMode = LpgMode.SUSTAINED, reverbSend = 0.30f),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
                        pan = -0.20f,
                        density = 0.15f,  // sparse — answers, never crowds the hook
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.CALL_RESPONSE,  // waits for the hook, then answers
                    )
                },
                // Track 6 — Organ bed (DX3 idx 1 "Hammond", auto-pinned patch selector):
                // low-mixed sustained root bed; swells forward in the lift.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX3,
                    volume = 0.34f,
                    harmonics = 0.031f,       // DX3 idx 1 "Hammond" (auto-pinned)
                    holdProbability = 0.72f,  // sustained bed
                    holdLengthMin = 2,
                    holdLengthMax = 6,
                    noteRangeLow = 48,        // C3
                    noteRangeHigh = 69,       // A4
                    reverbSend = 0.26f,
                    delaySend = 0.06f,
                    reverbBrightness = 0.50f,
                ).let { organ ->
                    TrackVoice(
                        engineEdm = organ,
                        engineSpace = organ.copy(harmonics = 0.092f, reverbSend = 0.34f),  // "60s organ", wetter
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.PAD,
                                arpMode = ArpMode.NEVER,  // root-tone bed — organ pedal, no ripple
                                sectionInversion = SectionInversion.ROOT_POSITION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.10f,
                                    ghostProbability = 0.05f,
                                    octaveJumpProbability = 0.05f,
                                    extensionProbability = 0.10f,
                                ),
                            ),
                        ),
                        pan = -0.24f,
                        density = 0.22f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 7 — Shaker / texture (NSE / PAR on space): quiet motion up top.
                OrpheusEngine(
                    engineId = OrpheusEngineId.NSE,
                    volume = 0.26f,
                    noteRangeLow = 60,
                    noteRangeHigh = 76,
                    reverbSend = 0.22f,
                    reverbBrightness = 0.62f,
                ).let { shaker ->
                    TrackVoice(
                        engineEdm = shaker,
                        engineSpace = shaker.copy(engineId = OrpheusEngineId.PAR, reverbSend = 0.32f),
                        role = TrackRole.Percussive,
                        pan = 0.30f,
                        density = 0.18f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
            ),
            stepCount = 32,  // 2 bars / 8 beats — the whole hook as one LickMode.Fill phrase
            tension = TensionProfile(
                innerBars = 8,   // >= 7 keeps spurt + octave climax enabled
                outerBars = 32,
                outerDepth = 0.50f,
                volume = 0.28f,
                tonal = TonalTension(
                    octaveShift = true,        // hook pops the octave at the peak
                    chromaticPassing = 0.12f,  // chromatic approach notes — blues walking DNA
                ),
                timing = 0.18f,
                evolution = EvolutionTension(
                    timbreLow = 0.30f, timbreHigh = 0.55f, timbreProbability = 0.6f,
                    morphLow = 0.35f, morphHigh = 0.52f, morphProbability = 0.5f,
                    attackPoint = 0.6f, releaseSpeed = 0.4f,
                ),
                spurtChance = 0.08f,
            ),
            effects = VibeEffects(
                delayTimeA = 0.16f,       // tight slapback for the lead stings
                delayTimeB = 0.33f,
                delayFeedback = 0.20f,
                delayDamping = 0.50f,
                reverbSize = 0.40f,       // small dry room — smoky club floor, not a hall
                reverbDamping = 0.50f,
                reverbBrightness = 0.48f,
                deepFloor = 0.24f,
            ),
        )
    }
}
