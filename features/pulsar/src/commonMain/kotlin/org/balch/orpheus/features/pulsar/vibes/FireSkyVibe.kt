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
 * intro (HALF-TIME cold open: the iconic riff ALONE at ~60 BPM — its correct, heavy
 * pace — over a soft kick, nothing else) -> build (still half-time; the organ + bass
 * swell in under the slow riff) -> verse (THE DROP: tempo snaps back to full 114 and
 * the whole band crashes in — this is the electro version) -> chorus (driving peak,
 * lead pedals FIXED so the bVII/IV moves read as a hammering hook rather than an
 * octave-fold lurch) -> solo (band jams, riff develops) -> breakdown (one long
 * anticipation build) -> outro (climactic stomp).
 *
 * The half-time intro and the full-tempo body play the SAME [Lick] at two speeds —
 * pace lives in the sequencer clock, not the notes, so a per-section bpmMultiplier is
 * all it takes. The engine floors BPM at 60, so the slow sections use 0.53x (not a
 * literal 0.5x, which would clamp 57->60 and then over-restore the drop to 120).
 * A/B against DogHouseVibe.
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

    // The vibe's tension arc. Pulled out so the build section can .copy() it with
    // halfLick = true (jam the first-bar hook) + wider tone evolution.
    private val baseTension = TensionProfile(
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
    )

    // Build-section tension: the lead's tone breathes harder here (wider morph/timbre,
    // faster inner ramp) as the band swells into the drop, and octaveShift is off so the
    // slow riff stays grounded. halfLick stays off — it's reserved for solo use later.
    private val buildTension = baseTension.copy(
        innerBars = 3,  // fast tension ramp so the tone actually moves within the short build
        tonal = baseTension.tonal.copy(halfLick = false, octaveShift = false),
        evolution = baseTension.evolution.copy(
            timbreLow = 0.20f, timbreHigh = 0.75f, timbreProbability = 0.85f,
            morphLow = 0.30f, morphHigh = 0.72f, morphProbability = 0.75f,
            attackPoint = 0.35f,  // tone reaches its peak earlier in the 2-bar section
        ),
    )

    private val leadInTension = baseTension.copy(
        innerBars = 2,  // fast tension ramp so the tone actually moves within the short build
        tonal = baseTension.tonal.copy(halfLick = true, octaveShift = false),
        evolution = baseTension.evolution.copy(
            timbreLow = 0.20f, timbreHigh = 0.75f, timbreProbability = 0.85f,
            morphLow = 0.30f, morphHigh = 0.72f, morphProbability = 0.75f,
            attackPoint = 0.35f,  // tone reaches its peak earlier in the 2-bar section
        ),
    )

    val sectionList by lazy {
        listOf(
            // 0: intro (cold open) — HALF-TIME. The iconic riff ALONE at its correct,
            //    heavy pace: just the riff + a soft kick pulse, everything else out.
            //    bpmMultiplier 0.53 drops 114 -> ~60 BPM (the engine's 60 floor; a
            //    literal 0.5 would clamp 57->60 and then over-restore the drop to 120).
            //    Lead pinned FIXED so the bare riff doesn't transpose.
            Section(
                name = "intro",
                barsMin = 2, barsMax = 2,   // one slow riff statements (the riff loops every 2 bars)
                bpmMultiplier = 0.53f,      // ~60 BPM half-time cold open — the riff's true pace
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = liftBars),  // -> build
                ),
                macroOverrides = MacroOverrides(
                    energy = 0.45f, complexity = 0.30f, space = 0.55f, mood = 0.5f,
                ),
                customProgression = chords(0),  // hang on the i alone
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    1 to TrackSectionOverride(density = 0.0f),                   // snare out
                    2 to TrackSectionOverride(density = 0.0f),                   // hats out
                    3 to TrackSectionOverride(density = 0.0f),                   // bass out — riff + kick only
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),  // bare riff, no transpose
                    5 to TrackSectionOverride(density = 0.0f),                   // organ out
                    6 to TrackSectionOverride(density = 0.0f),                   // rhythm gtr out
                    7 to TrackSectionOverride(density = 0.0f),                   // texture out — truly bare
                ),
            ),
            // 1: build — STILL HALF-TIME. The organ enters and the bass creeps in under
            //    the slow riff, swelling toward the drop. Snare/hats/rhythm-gtr stay out
            //    so they all crash in together at the verse (the "band kicks in"). Energy
            //    lifts over the last liftBars into the drop.
            Section(
                name = "build",
                barsMin = 2, barsMax = 2,   // two more slow statements as the organ swells
                bpmMultiplier = 0.53f,      // same ~60 BPM half-time as the cold open
                exitScratchMs = 500,        // record-scratch out of the build's tail, into the drop
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 1.0f, transitionBars = liftBars),  // -> verse = THE DROP (tempo snaps to 114)
                ),
                macroOverrides = MacroOverrides(
                    energy = 0.65f, complexity = 0.42f, space = 0.45f, mood = 0.6f,
                ),
                customProgression = chords(0),  // still hanging on the i — hypnotic slow riff
                chordsPerBar = 1,
                // The lead's tone breathes harder as the organ swells (buildTension:
                // wider morph/timbre, no octave leap). The full tension arc returns at
                // the verse, which carries no override.
                tensionOverride = buildTension,
                trackOverrides = mapOf(
                    1 to TrackSectionOverride(density = 0.0f),                   // snare still out
                    2 to TrackSectionOverride(density = 0.0f),                   // hats still out
                    3 to TrackSectionOverride(density = 0.30f),                  // bass creeps in
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),  // riff still FIXED
                    5 to TrackSectionOverride(density = 0.32f, volume = 0.52f),  // organ ENTERS — the swell
                    6 to TrackSectionOverride(density = 0.0f),                   // rhythm gtr out
                    7 to TrackSectionOverride(density = 0.0f),                   // texture out
                ),
            ),
            // 2: verse — full-band groove, the baseline. macroOverrides = null (verse IS baseline).
            //    THE DROP lands here: tempo snaps from ~60 back to 114 and the full band enters.
            Section(
                name = "verse",
                barsMin = 4, barsMax = 6,
                transitions = listOf(
                    SectionTransition(targetIndex = 3, weight = 1.0f, transitionBars = liftBars),  // -> chorus
                ),
                recencyDecay = 0.5f,
                macroOverrides = null,
            ),
            // 3: chorus — fuller, driving peak. Lead PEDALS FIXED so the bVII/IV moves
            //    read as a hammering hook, not an octave-fold lurch. Tighter pump progression.
            Section(
                name = "chorus",
                barsMin = 2, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 4, weight = 0.75f, transitionBars = dropBars),  // -> solo
                    SectionTransition(targetIndex = 6, weight = 0.25f, transitionBars = dropBars),  // -> breakdown
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
            // 4: solo — band jams over the groove; lead develops the riff (FOLLOW kept).
            Section(
                name = "lead-in",
                barsMin = 2, barsMax = 4,
                tensionOverride = leadInTension,
                transitions = listOf(
                    SectionTransition(targetIndex = 5, weight = 1.0f, transitionBars = liftBars),  // -> chorus
                ),
                recencyDecay = 0.4f,
                macroOverrides = MacroOverrides(
                    energy = 1.9f, complexity = 1.4f, space = .75f, mood = 1.2f,
                ),
                soloMode = SoloMode.LongFill(probability = 0.85f),
            ),
            Section(
                name = "solo",
                barsMin = 6, barsMax = 8,
                transitions = listOf(
                    SectionTransition(targetIndex = 3, weight = 0.33f, transitionBars = liftBars),  // -> chorus
                    SectionTransition(targetIndex = 2, weight = 0.33f, transitionBars = liftBars),  // -> verse
                    SectionTransition(targetIndex = 6, weight = 0.34f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.4f,
                macroOverrides = MacroOverrides(
                    energy = 0.9f, complexity = 1.4f, space = 1.15f, mood = 1.2f,
                ),
                soloMode = SoloMode.Jam(probability = 0.85f),
            ),
            // 5: breakdown — stripped to bass + drums + organ. Locked to 4 bars =
            //    one long anticipation build into the chorus (bigLift).
            Section(
                name = "breakdown",
                barsMin = 4, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 3, weight = 0.70f, transitionBars = bigLiftBars), // -> chorus (THE lift)
                    SectionTransition(targetIndex = 2, weight = 0.30f, transitionBars = liftBars),     // -> verse
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
            // 6: outro — the climactic stomp: riff full-throttle, everyone in, pinned FIXED.
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
            // BLUES degrees: 0=G, 1=Bb(b3), 2=C(4th), 3=Db(b5 blue note), 4=D(5th), 5=F(b7), 6=G(oct).
            // Copyright-safe rewrite (highest-exposure source — pushed hardest): keeps the heavy
            // G-blues single-note stomp (scale, low-mid register, gritty, relentless/no-rest) but
            // abandons the recognizable cell chain — opens on a descending root gallop, moves the
            // b5 off its telltale slot into a fast descending crush, leaps to the OCTAVE as an
            // arch-shaped peak, and drops the 4th entirely. Faithful original preserved in
            // FireSkyOgVibe (WIP, -Pcatalog). 2 bars, sums to exactly 8.0 beats, no rest.
            lick = Lick(
                steps = listOf(
                    // bar 1 — 16th gallop that DROPS to the b7, a fast b5 crush, up to the 5th
                    LickStep(scaleDegree = 4, duration = 0.25f, velocity = 0.78f), // D  5 — pickup
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.98f), // G  root ┐ gallop
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.85f), // G  root ┘
                    LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.88f),  // F  b7 — drop below the tonic
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.95f), // G  root
                    LickStep(scaleDegree = 3, duration = 0.25f, velocity = 0.72f), // Db b5 — fast descending crush
                    LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.86f),  // D  5
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.90f), // G  root
                    LickStep(scaleDegree = 1, duration = 0.25f, velocity = 0.80f), // Bb b3
                    LickStep(scaleDegree = 4, duration = 0.25f, velocity = 0.84f), // D  5 — launch
                    // bar 2 — leap to the OCTAVE peak, bluesy b7-5-b3 tail, low gallop, long home
                    LickStep(scaleDegree = 6, duration = 0.75f, velocity = 0.92f), // G  octave — arch peak, let-ring
                    LickStep(scaleDegree = 5, duration = 0.25f, velocity = 0.82f), // F  b7 ┐
                    LickStep(scaleDegree = 4, duration = 0.25f, velocity = 0.80f), // D  5  │ turnaround tail
                    LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.78f),  // Bb b3 ┘
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.95f), // G  root ┐ gallop chug
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.85f), // G  root ┘
                    LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.80f),  // F  b7
                    LickStep(scaleDegree = 4, duration = 0.25f, velocity = 0.82f), // D  5
                    LickStep(scaleDegree = 1, duration = 0.25f, velocity = 0.76f), // Bb b3
                    LickStep(scaleDegree = 0, duration = 1.75f, velocity = 0.90f), // G  root — long let-ring close
                ),
                loopLength = 8,  // 2 bars; notes sum to 8.0 — no rest, relentless
            ),
            lickMutation = 0.18f,  // bumped for copyright distance (more run-to-run drift off the figure)
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
            tension = baseTension,
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
