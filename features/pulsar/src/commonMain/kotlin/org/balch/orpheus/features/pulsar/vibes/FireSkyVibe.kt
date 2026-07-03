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
 * Fire Sky — a relentless, low-and-gritty hard-rock stomp built around the
 * most-taught rock riff of all time, recast for the BLUES scale in G.
 *
 * ## The feel
 * Low and gritty. A heavy, articulate riff hammers in the low-mid guitar register
 * over a stomping backbeat, a vintage drawbar-organ comping on the downbeats, and
 * a chugging rhythm-guitar layer. It is dry-ish and forward — garage/club, not hall.
 * The signature blue note (the b5) leans in once per loop and the figure resolves
 * home on a long, let-ring tonic before it starts over. The riff never rests: it is
 * a continuous two-bar phrase that loops on top of the band.
 *
 * ## Monophony limitation (important)
 * The reference riff stacks parallel FOURTHS — two-note power-chord shapes moving
 * in parallel. A Pulsar [Lick] is strictly MONOPHONIC: one scaleDegree per step.
 * This reproduces ONLY the single-note melodic skeleton of the figure. The "power"
 * of the harmonized fourths is NOT in the notes — it is approximated by a gritty
 * Waveshaping lead with a hard per-note pluck LPG, sitting in a low-mid register.
 * Do not read this as the fourths being voiced; they are not.
 *
 * ## Scale mapping
 * Root = G, ScaleType.BLUES (degrees {0,3,5,6,7,10} semitones). So a LickStep's
 * scaleDegree indexes that list: 0 = G (root), 1 = Bb (b3), 2 = C (4th),
 * 3 = Db (b5 — the blue note), 4 = D (5th), 5 = F (b7). The single-note reduction
 * of the riff is [0,1,2] [0,1,3,2] [0,1,2] [1,0]; the lone b5 (Db = degree 3)
 * lands in cell 2 and is the signature lean-in.
 *
 * ## Arrangement
 * intro (bare riff + drums) -> verse (full band baseline) -> chorus (driving peak,
 * lead pedals FIXED so the bVII/IV moves read as a hammering hook rather than an
 * octave-fold lurch) -> solo (band jams, riff develops) -> breakdown (one long
 * anticipation build) -> outro (climactic stomp). A/B against DogHouseVibe.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class FireSkyVibe : VibeProvider {
    override val name: String = "Fire Sky"

    // The riff hangs on the i (G), then makes the iconic hard-rock move: down to
    // the bVII (degree 6) and up to the IV (degree 3), then home.
    //   i – i – i – i – bVII – IV – i – i
    private val mainProgression = chords(0, 0, 0, 0, 6, 3, 0, 0)
    private val chordsPerBar = 1

    // Tighter two-chord pump for the chorus/outro — i pedal punching to bVII/IV.
    private val chorusProgression = chords(0, 0, 6, 3)
    private val chorusChordsPerBar = 2

    // Per-edge transition ramps named for their musical role, not the bar count.
    //   liftBars    — standard climb between adjacent-energy sections.
    //   dropBars    — exhale out of a high-energy section.
    //   bigLiftBars — the breakdown -> chorus anticipation build (breakdown is
    //                 locked to 4 bars, so the whole section becomes one long ramp).
    private val liftBars = 2
    private val dropBars = 3
    private val bigLiftBars = 4

    val sectionList by lazy {
        listOf(
            // 0: intro — the RIFF ALONE. Drums + lead riff, no organ/rhythm yet.
            //    Lead pinned FIXED so the bare riff doesn't transpose before the band is in.
            Section(
                name = "intro",
                barsMin = 2, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = liftBars),
                ),
                macroOverrides = MacroOverrides(
                    energy = 0.55f, complexity = 0.35f, space = 0.4f, mood = 0.5f,
                ),
                customProgression = chords(0),  // hang on the i alone
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),  // bare riff, no transpose
                    5 to TrackSectionOverride(density = 0.0f),                   // organ out
                    6 to TrackSectionOverride(density = 0.0f),                   // rhythm gtr out
                    7 to TrackSectionOverride(density = 0.05f),                  // minimal texture
                ),
            ),
            // 1: verse — full-band groove, the baseline. macroOverrides = null (verse IS baseline).
            Section(
                name = "verse",
                barsMin = 6, barsMax = 10,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.60f, transitionBars = liftBars),  // -> chorus
                    SectionTransition(targetIndex = 3, weight = 0.25f, transitionBars = liftBars),  // -> solo
                    SectionTransition(targetIndex = 4, weight = 0.15f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.5f,
                macroOverrides = null,
            ),
            // 2: chorus — fuller, driving peak. Lead PEDALS FIXED so the bVII/IV moves
            //    read as a hammering hook, not an octave-fold lurch. Tighter pump progression.
            Section(
                name = "chorus",
                barsMin = 4, barsMax = 6,
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 0.40f, transitionBars = liftBars),  // -> verse
                    SectionTransition(targetIndex = 3, weight = 0.35f, transitionBars = dropBars),  // -> solo
                    SectionTransition(targetIndex = 4, weight = 0.25f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 1.4f, complexity = 1.2f, space = 0.7f, mood = 1.15f,
                ),
                customProgression = chorusProgression,
                chordsPerBar = chorusChordsPerBar,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),  // riff pedals — no lurch at the peak
                    5 to TrackSectionOverride(density = 0.34f, volume = 0.58f),  // organ comes forward
                    6 to TrackSectionOverride(density = 0.44f),                  // rhythm gtr drives harder
                ),
            ),
            // 3: solo — band jams over the groove; lead develops the riff (FOLLOW kept).
            Section(
                name = "solo",
                barsMin = 8, barsMax = 16,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.50f, transitionBars = liftBars),  // -> chorus
                    SectionTransition(targetIndex = 1, weight = 0.30f, transitionBars = liftBars),  // -> verse
                    SectionTransition(targetIndex = 4, weight = 0.20f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.4f,
                macroOverrides = MacroOverrides(
                    energy = 0.9f, complexity = 1.4f, space = 1.15f, mood = 1.2f,
                ),
                soloMode = SoloMode.Jam(probability = 0.85f),
            ),
            // 4: breakdown — stripped to bass + drums + organ. Locked to 4 bars =
            //    one long anticipation build into the chorus (bigLift).
            Section(
                name = "breakdown",
                barsMin = 4, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.70f, transitionBars = bigLiftBars), // -> chorus (THE lift)
                    SectionTransition(targetIndex = 1, weight = 0.30f, transitionBars = liftBars),     // -> verse
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 0.45f, complexity = 0.5f, space = 1.35f, mood = 0.85f,
                ),
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(density = 0.25f),  // lead pulls way back
                    6 to TrackSectionOverride(density = 0.0f),   // rhythm gtr out
                ),
            ),
            // 5: outro — the climactic stomp: riff full-throttle, everyone in, pinned FIXED.
            Section(
                name = "outro",
                barsMin = 4, barsMax = 6,
                macroOverrides = MacroOverrides(
                    energy = 1.5f, complexity = 0.5f, space = 0.5f, mood = 1.1f,
                ),
                customProgression = chorusProgression,
                chordsPerBar = chorusChordsPerBar,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),  // riff hammers home, no lurch
                ),
            ),
        )
    }

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            album = Album.ZERO_TO_ONE,
            bpm = 114f,
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = sectionList.lastIndex,
                lengthSeconds = 150..240,
                sections = sectionList,
            ),
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.G,
            scaleType = ScaleType.BLUES,
            seed = 0,
            // --- macro defaults: driving, locked, dry-ish rock ---
            energy = 0.54f,
            complexity = 0.78f,
            space = 0.40f,
            mood = 0.75f,
            deep = 0.48f,
            // --- the riff (track 4 plays it as LickMode.Fill) ---
            // BLUES degrees: 0=G, 1=Bb(b3), 2=C(4th), 3=Db(b5 blue note), 4=D(5th), 5=F(b7).
            // Sequence [0,1,2] [0,1,3,2] [0,1,2] [1,0]; durations in BEATS sum to exactly
            // 8.0 (= 2 bars), all multiples of 0.25 so every onset lands on the 16th grid.
            // No rest — the riff is relentless.
            lick = Lick(
                steps = listOf(
                    // cell 1: G  Bb  C   — state the figure
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),  // G  (downbeat, hard)
                    LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),  // Bb (b3, passing up)
                    LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.85f),  // C  (4th, held answer-tone)
                    // cell 2: G  Bb  Db  C — push past it to the BLUE NOTE
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),  // G
                    LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),  // Bb (b3)
                    LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.70f),  // Db (b5 — the signature blue note)
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.85f),  // C  (4th, settle back)
                    // cell 3: G  Bb  C   — restate
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),  // G
                    LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),  // Bb (b3)
                    LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.85f),  // C  (4th, held)
                    // cell 4: Bb  G     — the answer / landing
                    LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),  // Bb (b3)
                    LickStep(scaleDegree = 0, duration = 1.5f, velocity = 0.90f),  // G  (home, let-ring)
                ),
                loopLength = 8,  // 2 bars; notes sum to 8.0 — no rest, the riff is relentless
            ),
            lickMutation = 0.10f,  // hook stays instantly recognizable run-to-run; just-living variation
            lickOctave = -1,       // auto = midpoint of the lead's note range (low-mid guitar register)
            genre = GenreProfile(
                swingAmount = 0.04f,          // near-straight rock; a hair off the grid for human feel
                ghostProbability = 0.18f,     // sparse ghosts — keep the riff clean and stomping
                noteRangeLow = 36,
                noteRangeHigh = 64,
                rhythmDensity = RhythmPattern.BACKBEAT.density,  // the rock backbeat (2 & 4)
                progressionStyle = ProgressionStyle.BLUES,
                chordsPerBar = chordsPerBar,
                customProgression = mainProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_8,  // reset drift each 8-bar phrase
            progressionDriftRange = 0.10f,                  // subtle — the riff hangs on the tonic
            tracks = listOf(
                // Track 0 — Kick (BD)
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.88f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.48f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 1 — Snare (SD), the backbeat snap
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD,
                    volume = 0.66f,
                    timbre = 0.55f,
                    reverbSend = 0.12f,
                ).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.10f,
                        density = 0.36f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.FILL,  // fills at phrase boundaries
                    )
                },
                // Track 2 — Hi-hat (HH), driving 8ths
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.56f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f,
                        density = 0.58f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 3 — Bass (WSH grit / STR warmth on space), ROOT_ONLY, doubles the riff root.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.80f,
                    harmonics = 0.58f,        // gritty waveshape
                    noteRangeLow = 31,        // G1
                    noteRangeHigh = 50,       // D3
                    reverbBrightness = 0.26f,
                    glideRate = 0.08f,        // tiny slur on chord changes
                    lpgMode = LpgMode.PLUCK,  // articulate attack so the root pulse punches
                    lpgDecay = 0.45f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.STR, lpgMode = LpgMode.SUSTAINED),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.00f,
                        density = 0.50f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,  // locked pocket
                    )
                },
                // Track 4 — Lead (WSH grit / VA on space), THE riff, LickMode.Fill.
                // The gritty WSH lead stands in for the distorted guitar; PLUCK LPG gives
                // every riff note the picked, articulated attack. FOLLOW so it transposes
                // like a real blues figure; intro/chorus/outro pin it FIXED (see sections).
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.78f,
                    harmonics = 0.74f,        // gritty / distorted character
                    timbre = 0.64f,
                    morph = 0.45f,
                    noteRangeLow = 50,        // D3
                    noteRangeHigh = 69,       // A4 — low-mid guitar register
                    reverbSend = 0.16f,
                    delaySend = 0.14f,
                    glideRate = 0.0f,         // no slur — every riff note distinct
                    lpgMode = LpgMode.PLUCK,  // hard pluck per note = picked-guitar attack
                    lpgDecay = 0.50f,
                    lpgColour = 0.55f,
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead.copy(
                            engineId = OrpheusEngineId.DX3,
                            harmonics = 0.031f,       // DX3 idx 1 "Hammond" (auto-pinned)
                            timbre = 0.55f,           // a touch of drawbar brightness
                            morph = 0.50f,
                            lpgMode = LpgMode.SUSTAINED
                        ),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FOLLOW,
                            lickMode = LickMode.Fill,  // full 32-step single continuous phrase
                        ),
                        pan = 0.05f,
                        density = 0.95f,  // high — the riff fires on (nearly) every lick step
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,  // riff repeats exactly; variation = lickMutation only
                    )
                },
                // Track 5 — Organ, the Hammond layer — Chordal, sustained.
                // DX3 harmonics is an auto-pinned 32-step patch selector: 0.031 = "Hammond",
                // 0.092 = "60s organ" (space slot). Do not interpolate.
                OrpheusEngine(
                    engineId = OrpheusEngineId.VA,
                    volume = 0.50f,
                    harmonics = 0.031f,
                    timbre = 0.55f,
                    morph = 0.50f,
                    noteRangeLow = 48,        // C3
                    noteRangeHigh = 72,       // C5
                    reverbSend = 0.22f,
                    delaySend = 0.08f,
                    reverbBrightness = 0.55f,
                ).let { organ ->
                    TrackVoice(
                        engineEdm = organ,
                        engineSpace = organ.copy(
                            engineId = OrpheusEngineId.DX3,
                            harmonics = 0.092f,
                            reverbSend = 0.32f
                        ),  // "60s organ", wetter
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.ROCK_DOWNBEATS,  // hits on 1 & 3 — rock organ stabs
                                arpMode = ArpMode.AUTO,
                                arpSpeed = 0.12f,
                                arpDirection = ArpDirection.UP,
                                sectionInversion = SectionInversion.FIRST_INVERSION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.16f,
                                    ghostProbability = 0.20f,
                                    octaveJumpProbability = 0.18f,
                                    extensionProbability = 0.22f,  // bluesy 7ths/9ths color
                                ),
                                fills = CompingFills(
                                    everyNBars = 8,
                                    fillType = FillType.TURNAROUND,  // bluesy organ rip at the turnaround
                                    skipProbability = 0.20f,
                                ),
                            ),
                        ),
                        pan = -0.22f,
                        density = 0.26f,
                        envelopeProfile = EnvelopeProfile.EFFECT,  // sustained organ pad-stab
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 6 — Rhythm guitar chug (WSH / ENS on space) — Melodic root chug, ROOT_ONLY.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.46f,
                    harmonics = 0.68f,        // gritty, slightly less than the lead
                    timbre = 0.58f,
                    modLfoRate = 0.30f,
                    modLfoDepth = 0.15f,
                    modLfoShape = 0.3f,
                    modLfoCoupling = 0.1f,
                    noteRangeLow = 43,        // G2
                    noteRangeHigh = 60,       // C4
                    reverbSend = 0.12f,
                    delaySend = 0.08f,
                    reverbBrightness = 0.45f,
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.40f,
                ).let { rhythm ->
                    TrackVoice(
                        engineEdm = rhythm,
                        engineSpace = rhythm.copy(
                            engineId = OrpheusEngineId.ENS,
                            lpgMode = LpgMode.SUSTAINED,
                            reverbSend = 0.22f,
                        ),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.24f,
                        density = 0.34f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 7 — Texture / FX (NSE / PAR on space) — Percussive, sparse.
                OrpheusEngine(
                    engineId = OrpheusEngineId.NSE,
                    volume = 0.30f,
                    modLfoRate = 0.40f,
                    modLfoDepth = 0.25f,
                    modLfoShape = 0.4f,
                    modLfoCoupling = 0.1f,
                    holdProbability = 0.05f,
                    holdLengthMin = 2,
                    holdLengthMax = 4,
                    reverbSend = 0.30f,
                    delaySend = 0.12f,
                    noteRangeLow = 48,
                    noteRangeHigh = 72,
                    reverbBrightness = 0.60f,
                ).let { fx ->
                    TrackVoice(
                        engineEdm = fx,
                        engineSpace = fx.copy(engineId = OrpheusEngineId.PAR),
                        role = TrackRole.Percussive,
                        pan = -0.30f,
                        density = 0.12f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            stepCount = 32,  // 2 bars / 8 beats — the full riff as one LickMode.Fill phrase
            tension = TensionProfile(
                innerBars = 8,   // >= 7 enables spurt + octave climax
                outerBars = 32,
                outerDepth = 0.55f,
                volume = 0.30f,
                tonal = TonalTension(octaveShift = true, chromaticPassing = 0.10f),  // riff jumps an octave at the peak
                timing = 0.20f,
                evolution = EvolutionTension(
                    timbreLow = 0.25f, timbreHigh = 0.62f, timbreProbability = 0.7f,  // the distortion breathes
                    morphLow = 0.35f, morphHigh = 0.55f, morphProbability = 0.5f,
                    attackPoint = 0.6f, releaseSpeed = 0.35f,  // peak past midpoint = a build into the next phrase
                ),
                spurtChance = 0.10f,  // occasional lick-mutation spurt — the riff gets wilder sometimes
            ),
            effects = VibeEffects(
                delayTimeA = 0.18f,       // tight slapback, not spacious
                delayTimeB = 0.28f,
                delayFeedback = 0.22f,    // low-moderate — a couple of repeats, no wash
                delayDamping = 0.45f,
                reverbSize = 0.38f,       // small-to-medium room — garage/club feel
                reverbDamping = 0.50f,
                reverbBrightness = 0.55f, // slightly bright plate
                deepFloor = 0.25f,
            ),
        )
    }
}
