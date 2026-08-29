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
 * Rust Belt — a swampy heartland-rock pocket built on a bass hook, dry and lazy-tough.
 *
 * ## The feel
 * Mid-tempo, laid back but insistent. THE BASS IS THE SONG: a round, fingered
 * two-bar hook that anchors the root, leans on the b7 and 5th, breathes through
 * pocket rests, and resolves home with a walking pickup. Everything else sits
 * behind it — a fat dry backbeat, a jangly chord chank on the off-hand, a twangy
 * slide lead answering between phrases, and a low-mixed drawbar organ bed.
 * Production is dry and forward: garage floor, not arena.
 *
 * ## The hook (why the bass reads as a hook)
 * Two-bar phrase in DORIAN degrees (0=root, 2=b3, 3=4th, 4=5th, 5=6th, 6=b7):
 * bar 1 states the anchor with a ghost pickup and a syncopated push, bounces
 * b7 -> 5th, and lands home ringing over the barline; bar 2 exhales (rest on the
 * downbeat, kick alone), answers with a b7/6th sixteenth turn, then walks
 * b3 -> 4 -> 5 -> b7 straight back into the downbeat. Rests are real rests
 * (negative degrees) — the swamp is in the space between notes.
 *
 * ## Arrangement
 * intro (THE HOOK ALONE over drums) -> verse (full pocket, one-chord vamp so the
 * hook never transposes) -> chorus (the lift: IV -> bVII -> i, hook transposes
 * with the roots — the payoff) -> jam (band stretches out) -> breakdown (drums +
 * bass only, one long build) -> outro (full-band lift, terminal). A/B against
 * DogHouseVibe.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class RustBeltVibe : VibeProvider {
    override val name: String = "Rust Belt"

    // Verse hangs on the i — the hook carries all the motion, so the chord bed
    // stays planted and the bass never transposes until the chorus asks it to.
    private val verseProgression = chords(0)

    // The chorus lift: IV -> bVII -> i -> i (in dorian: the major IV is the
    // money chord). ROOT_ONLY tracks transpose the hook up the lift and back.
    private val chorusProgression = chords(3, 6, 0, 0)

    // Jam seesaw — mostly home with a bVII lean every 4th bar to give soloists a shape.
    private val jamProgression = chords(0, 0, 0, 6)

    // Per-edge transition ramps named for their musical role.
    private val liftBars = 2
    private val dropBars = 2
    private val bigLiftBars = 4
    private val introBuildBars = 3

    val sectionList by lazy {
        listOf(
            // 0: intro — the KIT up front, building. A big ringing kick and a moderate
            //    shaker carry an eight-bar crescendo over the hook while the jangle and the
            //    twang stay out, so the verse downbeat is where the band actually walks on.
            //    Three things stack to make the build obvious: energy opens at 0.45x and the
            //    3-bar pre-roll ramps it to the verse's 1.0x; the tension override runs a
            //    per-bar staircase (0 -> 0.75) of velocity and timbre; and the muted tracks
            //    all arrive at once on the transition.
            //
            //    Drum morph is DECAY on BD/SD/HH, pinned here for three distinct voices: a
            //    long-ringing kick against a tight snare crack and a washy open hat.
            //
            //    Morph pinning also decides WHAT MOVES. The tension evolution below sweeps
            //    morph only on tracks that are NOT pinned, so pinning the kit and the bass
            //    leaves the shaker as the single voice drifting across the build — the
            //    texture reads as the thing developing while the hook and the pocket hold.
            Section(
                name = "intro",
                barsMin = 4, barsMax = 4,   // 4 loop-cycles = 8 real bars at stepCount 32
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = introBuildBars),
                ),
                macroOverrides = MacroOverrides(
                    energy = 0.65f, complexity = 0.4f, space = 1.15f, mood = 0.9f,
                ),
                // Replaces the vibe tension for this section only. The tension counter is
                // free-running and starts at 0 on load, so the intro is the one section that
                // gets a clean 0 -> peak sweep. The sawtooth caps at (inner-1)/inner = 0.75,
                // hence attackPoint 0 — anything higher wastes most of the range.
                tensionOverride = TensionProfile(
                    innerBars = 4,     // one staircase per visit: 0, .25, .5, .75
                    outerBars = 0,     // single-visit section — an outer arc means nothing
                    outerDepth = 0f,
                    volume = 0.9f,     // the crescendo
                    tonal = TonalTension(chromaticPassing = 0.16f),
                    timing = 0.10f,
                    // The morph range is wide on purpose: with the kit and the bass pinned it
                    // now reaches the shaker alone, so it buys texture movement instead of
                    // smearing the pocket. Widen only while that pinning holds.
                    evolution = EvolutionTension(
                        timbreLow = 0.18f, timbreHigh = 0.78f, timbreProbability = 0.95f,
                        morphLow = 0.20f, morphHigh = 0.82f, morphProbability = 0.92f,
                        attackPoint = 0.0f,
                        releaseSpeed = 0.25f,
                    ),
                    spurtChance = 0f,
                ),
                customProgression = verseProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    0 to TrackSectionOverride(volume = 0.9f, morph = 0.90f, reverbSend = 0.36f, density = .32f),  // kick: loud, ringing
                    1 to TrackSectionOverride(volume = 0.62f, morph = 0.26f, density = .15f),  // snare: tight crack
                    2 to TrackSectionOverride(volume = 0.44f, morph = 0.54f, density = .85f),  // hat: open and washy
                    // Track 3 — THE HOOK, and the one thing in the intro that must not move.
                    // No volume override: it plays at its authored 0.88 while everything
                    // around it is pulled down, which is what puts it out front. The morph
                    // pin is the point of this entry — unpinned, the tension evolution above
                    // walks the bass's morph 0.22 -> 0.72 across the build and the fingered
                    // PLUCK character wanders with it. 0.30 is what the verse's own Space
                    // setting resolves to through MELODIC's spaceDecay, so the un-pin at the
                    // section boundary lands on the same value and is inaudible.
                    3 to TrackSectionOverride(volume = 0.1f, morph = 0.30f, density = .15f),
                    4 to TrackSectionOverride(volume = 0.4f, density = .15f),   // jangle
                    5 to TrackSectionOverride(volume = 0.4f, density = .15f),   // twang
                    6 to TrackSectionOverride(volume = 0.4f, density = .15f),  // organ
                    // Track 7 — the texture, and the one voice left free to drift. Deliberately
                    // NOT morph-pinned so the evolution sweep is audible on it alone. Loud for
                    // an EFFECT track because that macro map caps energyVolume at 0.5x and
                    // texture_energy_curve ducks tracks 5-7 again; the sends give it a tail so
                    // it reads as its own layer rather than dust on the kit.
                    7 to TrackSectionOverride(volume = 0.88f, reverbSend = 0.42f, delaySend = 0.24f, density = .5f),
                ),
            ),
            // 1: verse — the full pocket, baseline. One-chord vamp; the hook rules.
            Section(
                name = "verse",
                barsMin = 8, barsMax = 12,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.60f, transitionBars = liftBars),  // -> chorus
                    SectionTransition(targetIndex = 3, weight = 0.25f, transitionBars = liftBars),  // -> jam
                    SectionTransition(targetIndex = 4, weight = 0.15f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.5f,
                macroOverrides = null,
            ),
            // 2: chorus — the lift. Hook transposes with IV -> bVII -> i; comp and
            //    lead come forward; brighter and bigger but still dry.
            Section(
                name = "chorus",
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
                customProgression = chorusProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(density = 0.40f),                  // chank drives
                    5 to TrackSectionOverride(density = 0.26f, volume = 0.56f),  // twang answers more
                    6 to TrackSectionOverride(density = 0.30f),                  // organ swells in
                ),
            ),
            // 3: jam — the band stretches out over the seesaw; hook develops.
            Section(
                name = "jam",
                barsMin = 8, barsMax = 16,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.45f, transitionBars = liftBars),  // -> chorus
                    SectionTransition(targetIndex = 1, weight = 0.35f, transitionBars = liftBars),  // -> verse
                    SectionTransition(targetIndex = 4, weight = 0.20f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.4f,
                macroOverrides = MacroOverrides(
                    energy = 0.95f, complexity = 1.35f, space = 1.55f, mood = 1.1f
                ),
                trackOverrides = mapOf(
                    0 to TrackSectionOverride(delaySend = 0.6f),
                    1 to TrackSectionOverride(delaySend = 0.2f),
                    3 to TrackSectionOverride(delaySend = 0.4f),
                    4 to TrackSectionOverride(delaySend = 0.4f),
                    5 to TrackSectionOverride(density = 0.0f),
                    6 to TrackSectionOverride(volume = .8f, delaySend = .8f, reverbSend = .6f),
                    7 to TrackSectionOverride(volume = .8f, delaySend = .8f, reverbSend = .6f),
                ),
                customProgression = jamProgression,
                chordsPerBar = 1,
                soloMode = SoloMode.Jam(probability = 0.8f, lickInfluence = 0.8f),
            ),
            // 4: breakdown — drums + bass only; the hook naked again, one long
            //    anticipation build back into the chorus.
            Section(
                name = "breakdown",
                barsMin = 4, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.70f, transitionBars = bigLiftBars), // -> chorus (THE build)
                    SectionTransition(targetIndex = 1, weight = 0.30f, transitionBars = liftBars),    // -> verse
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 0.45f, complexity = 0.6f, space = 1.4f, mood = 0.9f,
                ),
                customProgression = verseProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(density = 0.0f),
                    5 to TrackSectionOverride(density = 0.0f),
                    6 to TrackSectionOverride(density = 0.12f),  // organ whisper holds the bed
                    7 to TrackSectionOverride(density = 0.08f),
                ),
            ),
            // 5: outro — full-band lift, ride it home. Terminal.
            Section(
                name = "outro",
                barsMin = 4, barsMax = 8, barStep = 4,
                macroOverrides = MacroOverrides(
                    energy = 1.35f, complexity = 0.9f, space = 0.8f, mood = 1.15f,
                ),
                customProgression = chorusProgression,
                chordsPerBar = 1,
            ),
        )
    }

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            album = Album.RIF,
            bpm = 87f,
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = sectionList.lastIndex,
                lengthSeconds = 150..240,
                sections = sectionList,
            ),
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.D,
            scaleType = ScaleType.DORIAN,
            seed = 0,
            // --- macro defaults: relaxed pocket, warm, dry-forward ---
            energy = 0.58f,
            complexity = 0.72f,
            space = 0.34f,
            mood = 0.62f,
            deep = 0.30f,
            // --- THE HOOK (track 3 bass plays it as LickMode.Fill) ---
            // DORIAN degrees: 0=D(root), 1=E(2), 2=F(b3), 3=G(4), 4=A(5), 5=B(6), 6=C(b7), 7=D(oct).
            // Copyright-safe rewrite: keeps the swampy D-Dorian heartland pocket (scale, low
            // register, rest density, b7/6 color) but inverts the recognizable signature — a
            // single anchored root that leans DOWNWARD, an octave POP, and a DESCENDING turn
            // (the opposite of the faithful thumps -> jump-up -> walk-up). The faithful original
            // is preserved verbatim in RustBeltOgVibe (WIP, -Pcatalog). 2 bars, sums to 8.0.
            lick = Lick(
                steps = listOf(
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.98f),   // D  root — single anchor
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 6, duration = 0.75f, velocity = 0.90f),  // C  b7 — dotted downward lean
                    LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.86f),   // A  5
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 0, duration = 1.0f, velocity = 1.00f),   // D  root — home by leap DOWN, held
                    LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.80f),   // B  6 — bright lift
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 7, duration = 0.5f, velocity = 0.92f),   // D  octave POP
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.84f),   // C  b7
                    LickStep(scaleDegree = 4, duration = 0.75f, velocity = 0.88f),  // A  5 — dotted
                    LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0.0f),   // (pocket breath)
                    LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.78f),   // G  4  ┐
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.74f),   // F  b3 │ DESCENDING turn into the loop
                    LickStep(scaleDegree = 1, duration = 0.25f, velocity = 0.70f),  // E  2  ┘
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                ),
                loopLength = 8,  // 2 bars; steps sum to 8.0 exactly
            ),
            lickMutation = 0.22f,  // bumped for copyright distance (more run-to-run drift off the figure)
            lickOctave = -1,       // auto = midpoint of the bass range (lands around D2)
            genre = GenreProfile(
                swingAmount = 0.09f,          // lazy-tough pocket — behind the beat, not shuffled
                ghostProbability = 0.22f,     // pocket ghosts in the kit
                noteRangeLow = 36,
                noteRangeHigh = 64,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.MODAL,  // vamp-centric, no functional resolution
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
                        density = 0.44f,
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
                // Track 2 — Hat (HH): relaxed 8ths riding the pocket.
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.52f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f,
                        density = 0.52f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 3 — BASS (PD warm-round): THE HOOK. LickMode.Fill owns the whole
                // 2-bar phrase; PLUCK gives the fingered attack; ROOT_ONLY so the chorus
                // lift transposes the hook with the chord roots (the payoff moment).
                OrpheusEngine(
                    engineId = OrpheusEngineId.PD,
                    volume = 0.88f,
                    noteRangeLow = 26,        // D1
                    noteRangeHigh = 50,       // D3 — auto lick octave centers ~D2
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
                            lpgMode = LpgMode.SUSTAINED,  // rounder, dubbier at low energy
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
                // Track 4 — Jangle chank (CHD native chords): dry rhythm bed on the off-hand.
                OrpheusEngine(
                    engineId = OrpheusEngineId.CHD,
                    volume = 0.42f,
                    noteRangeLow = 55,        // G3
                    noteRangeHigh = 76,       // E5
                    reverbSend = 0.10f,
                    delaySend = 0.06f,
                    reverbBrightness = 0.55f,
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.42f,
                ).let { jangle ->
                    TrackVoice(
                        engineEdm = jangle,
                        engineSpace = jangle.copy(lpgMode = LpgMode.SUSTAINED, reverbSend = 0.22f),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.ROCK_DOWNBEATS,
                                arpMode = ArpMode.AUTO,          // CHD = native block chords
                                arpSpeed = 0.15f,
                                arpDirection = ArpDirection.UP,
                                sectionInversion = SectionInversion.FIRST_INVERSION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.18f,
                                    ghostProbability = 0.22f,     // lazy chank ghosts
                                    octaveJumpProbability = 0.10f,
                                    extensionProbability = 0.18f, // occasional add9 jangle color
                                ),
                                fills = CompingFills(
                                    everyNBars = 8,
                                    fillType = FillType.TURNAROUND,
                                    skipProbability = 0.25f,
                                ),
                            ),
                        ),
                        pan = 0.24f,
                        density = 0.30f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 5 — Twang lead (FM): slide-y answers between the hook's phrases.
                OrpheusEngine(
                    engineId = OrpheusEngineId.FM,
                    volume = 0.50f,
                    noteRangeLow = 57,        // A3
                    noteRangeHigh = 79,       // G5
                    reverbSend = 0.16f,
                    delaySend = 0.20f,        // slapback twang
                    reverbBrightness = 0.55f,
                    glideRate = 0.35f,        // the slide — notes bend into each other
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.55f,
                ).let { twang ->
                    TrackVoice(
                        engineEdm = twang,
                        engineSpace = twang.copy(lpgMode = LpgMode.SUSTAINED, reverbSend = 0.30f),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
                        pan = -0.20f,
                        density = 0.16f,  // sparse — answers, never crowds the hook
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.CALL_RESPONSE,  // waits for the hook, then answers
                    )
                },
                // Track 6 — Organ bed (DX3 idx 1 "Hammond", auto-pinned patch selector):
                // low-mixed sustained root bed; swells forward in the chorus.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX3,
                    volume = 0.36f,
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
                        // 0.092f is a bucket edge: idx 2 "E organ 3" or 3 "60s organ" by prior
                        // load. Ear-tuned, left as-is — see fm_patches.md on legacy edge values.
                        engineSpace = organ.copy(harmonics = 0.092f, reverbSend = 0.34f),  // wetter
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
                // Track 7 — Shaker / texture (NSE / PAR on space): quiet 8th-note motion up top.
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
                        pan = -0.30f,
                        density = 0.22f,
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
                    octaveShift = true,        // hook jumps the octave at the peak
                    chromaticPassing = 0.12f,  // chromatic approach notes — walking-bass DNA
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
                delayTimeA = 0.16f,       // tight slapback for the twang
                delayTimeB = 0.33f,
                delayFeedback = 0.20f,
                delayDamping = 0.50f,
                reverbSize = 0.42f,       // roomy but dry-forward — garage floor
                reverbDamping = 0.50f,
                reverbBrightness = 0.50f,
                deepFloor = 0.24f,
            ),
        )
    }
}
