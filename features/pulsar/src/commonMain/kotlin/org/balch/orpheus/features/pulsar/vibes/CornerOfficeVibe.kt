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
 * Corner Office — a cynical 70s funk-rock strut: staccato minor-key bass riff,
 * tight guitar stabs, a string-machine bed, and a lead that takes over the room.
 *
 * ## The feel
 * Confident, slinky, a little menacing. The bass and the stabs lock into a
 * sixteenth-note strut on a minor vamp; everything is played tight and dry-ish
 * with studio polish (not garage). A vintage string-machine pad hangs behind the
 * band, and between vocal-line spaces a searing lead answers — then the jam
 * section hands it the whole room.
 *
 * ## The strut (the riff)
 * Two bars in natural-minor degrees (0=root, 2=b3, 3=4, 4=5, 6=b7): bar 1 opens
 * with staccato sixteenth root stabs (double-hit, rest, hit — the funk hammer),
 * snakes down b7 -> 5 then b3 -> 4, and lands home ringing. Bar 2 exhales, ghosts
 * two sixteenths on the root, climbs b3 -> 4 -> 5, snaps the b7 off the top and
 * falls back 5 -> b3 to resolve at the loop. Rests are real rests — the strut
 * is in the gaps.
 *
 * ## Arrangement
 * intro (riff + drums, stabs ghosting in) -> verse (full strut on the vamp) ->
 * lift (the bVI -> bVII -> i climb — the money move) -> jam (the lead OWNS it:
 * SoloMode.Jam with the lead pushed forward) -> breakdown (stripped build) ->
 * outro (terminal, ride the lift home). A/B against DogHouseVibe.
 */
// Benched after first ear-test (2026-07-02): registered but curated out — it is
// VibeStatus.WIP in VibeCatalog. Flip that entry to LIVE when the next tuning
// pass lands; no annotation-commenting needed.
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class CornerOfficeVibe : VibeProvider {
    override val name: String = "Corner Office"

    // Verse hangs on the i — the strut carries the motion.
    private val verseProgression = chords(0)

    // The lift: bVI -> bVII -> i -> i (C -> D -> Em in E minor) — the classic
    // aeolian climb. ROOT_ONLY tracks ride the roots up it.
    private val liftProgression = chords(5, 6, 0, 0)

    // Jam seesaw — vamp with the climb's front half leaning in every 4th bar.
    private val jamProgression = chords(0, 0, 5, 6)

    private val liftBars = 2
    private val dropBars = 2
    private val bigLiftBars = 4

    val sectionList by lazy {
        listOf(
            // 0: intro — HALF-TIME cold open so you can PICK OUT the strut. Bass + a soft kick
            //    at ~61 BPM (bpmMultiplier 0.5; 122×0.5=61 clears the 60 floor). Snare/hats out
            //    to unmask it, and the stabs stay out for the bare first statement; lead/pad
            //    out. Over the last bar the tempo winds UP (bpmRampBars) into the full-tempo
            //    verse — an accelerando drop.
            Section(
                name = "intro",
                barsMin = 2, barsMax = 2,   // one full 4-bar strut, stated slow
                bpmMultiplier = 0.5f,       // ~61 BPM half-time — the strut, legible
                bpmRampBars = 1,            // accelerando over the last bar, lands on the drop
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = liftBars),
                ),
                macroOverrides = MacroOverrides(
                    energy = 0.75f, complexity = 0.6f, space = 0.9f, mood = 0.95f,
                ),
                customProgression = verseProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    1 to TrackSectionOverride(density = 0.0f),   // snare out — unmask the strut
                    2 to TrackSectionOverride(density = 0.0f),   // hats out — unmask the strut
                    4 to TrackSectionOverride(density = 0.0f),   // stabs out for the bare statement
                    5 to TrackSectionOverride(density = 0.0f),   // lead out
                    6 to TrackSectionOverride(density = 0.0f),   // pad out
                    7 to TrackSectionOverride(density = 0.05f),
                ),
            ),
            // 1: verse — the full strut, baseline.
            Section(
                name = "verse",
                barsMin = 8, barsMax = 12,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.55f, transitionBars = liftBars),  // -> lift
                    SectionTransition(targetIndex = 3, weight = 0.30f, transitionBars = liftBars),  // -> jam
                    SectionTransition(targetIndex = 4, weight = 0.15f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.5f,
                macroOverrides = null,
            ),
            // 2: lift — the bVI -> bVII -> i climb; band swells, stabs drive.
            Section(
                name = "lift",
                barsMin = 4, barsMax = 8, barStep = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 0.40f, transitionBars = dropBars),  // -> verse
                    SectionTransition(targetIndex = 3, weight = 0.40f, transitionBars = liftBars),  // -> jam
                    SectionTransition(targetIndex = 4, weight = 0.20f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 1.25f, complexity = 1.15f, space = 0.9f, mood = 1.2f,
                ),
                customProgression = liftProgression,
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(density = 0.42f),                  // stabs drive
                    5 to TrackSectionOverride(density = 0.24f, volume = 0.58f),  // lead leans in
                    6 to TrackSectionOverride(density = 0.30f),                  // pad swells
                ),
            ),
            // 3: jam — THE solo. The lead owns the room; band digs into the seesaw.
            Section(
                name = "jam",
                barsMin = 8, barsMax = 16,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.45f, transitionBars = liftBars),  // -> lift
                    SectionTransition(targetIndex = 1, weight = 0.30f, transitionBars = liftBars),  // -> verse
                    SectionTransition(targetIndex = 4, weight = 0.25f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.4f,
                // Ear-test: the jam wanted more heat — energy and complexity pushed up,
                // space reined in a touch so the band feels closer while it burns.
                macroOverrides = MacroOverrides(
                    energy = 1.35f, complexity = 1.5f, space = 1.05f, mood = 1.1f,
                ),
                customProgression = jamProgression,
                chordsPerBar = 1,
                soloMode = SoloMode.Jam(probability = 0.9f),
                trackOverrides = mapOf(
                    5 to TrackSectionOverride(density = 0.55f, volume = 0.66f),  // the lead takes over
                ),
            ),
            // 4: breakdown — stripped to drums + bass strut; one long build back up.
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
                    4 to TrackSectionOverride(density = 0.0f),
                    5 to TrackSectionOverride(density = 0.0f),
                    // Pad whisper pinned at the pre-trim level — the breakdown ear-tested
                    // as "fabulous", so the global bed tone-down must not reach it.
                    6 to TrackSectionOverride(density = 0.14f, volume = 0.34f),
                    7 to TrackSectionOverride(density = 0.08f),
                ),
            ),
            // 5: outro — terminal; ride the climb home with everyone in.
            Section(
                name = "outro",
                barsMin = 4, barsMax = 8, barStep = 4,
                macroOverrides = MacroOverrides(
                    energy = 1.3f, complexity = 0.95f, space = 0.9f, mood = 1.15f,
                ),
                customProgression = liftProgression,
                chordsPerBar = 1,
            ),
        )
    }

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            album = Album.RIF,
            bpm = 122f,
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = sectionList.lastIndex,
                lengthSeconds = 150..240,
                sections = sectionList,
            ),
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.E,
            scaleType = ScaleType.MINOR,
            seed = 0,
            // --- macro defaults: tight strut, studio polish, minor-key cool ---
            energy = 0.66f,
            complexity = 0.50f,
            space = 0.40f,
            mood = 0.45f,
            deep = 0.34f,
            // --- THE STRUT (track 3 bass plays it as LickMode.Fill) ---
            // MINOR degrees: 0=E(root), 1=F#(2), 2=G(b3), 3=A(4), 4=B(5), 5=C(b6), 6=D(b7), 7=E(oct).
            // Copyright-safe rewrite: keeps the cynical E-minor filter-funk strut (scale, register,
            // staccato 16ths, rest-carried pocket, b6/b7 color) but drops the recognizable
            // signature — a grace-pickup opening instead of stabbed root triplets, octave POPS +
            // a b6 lean the source never uses, a rising b7-pickup turnaround, and a through-
            // composed 4-bar arc instead of a 2-bar A/A. Faithful original preserved in
            // CornerOfficeOgVibe (WIP, -Pcatalog). 4 bars, sums to exactly 16.0 beats on the grid.
            lick = Lick(
                steps = listOf(
                    // bar 1 — grace-pickup into a held root, then ASCENDING (root->4->5->b7)
                    LickStep(scaleDegree = 4, duration = 0.25f, velocity = 0.72f),  // B  5 — grace pickup
                    LickStep(scaleDegree = 0, duration = 0.75f, velocity = 1.00f),  // E  root — held
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.60f),  // E  root — ghost
                    LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.85f),   // A  4  ┐
                    LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.88f),   // B  5  │ climb
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.80f),   // D  b7 ┘
                    // bar 2 — octave POP hook, walk back down through b7-5-b3-4 to a long home
                    LickStep(scaleDegree = 4, duration = 0.75f, velocity = 0.78f),  // B  5 — recoil
                    LickStep(scaleDegree = 7, duration = 0.5f, velocity = 0.92f),   // E  octave POP
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 6, duration = 0.25f, velocity = 0.70f),  // D  b7
                    LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.80f),   // B  5
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.82f),   // G  b3
                    LickStep(scaleDegree = 3, duration = 0.25f, velocity = 0.68f),  // A  4
                    LickStep(scaleDegree = 0, duration = 1.25f, velocity = 0.90f),  // E  root — long ring
                    // bar 3 — sparser strut leaning on the b6 (new tension), b3 held into the turn
                    LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0.0f),   // (rest — pocket)
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),   // E  root
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.80f),   // G  b3
                    LickStep(scaleDegree = 5, duration = 0.75f, velocity = 0.86f),  // C  b6 — the minor lean, long
                    LickStep(scaleDegree = 4, duration = 0.25f, velocity = 0.72f),  // B  5
                    LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.78f),   // A  4
                    LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.80f),   // G  b3 — held into the turn
                    // bar 4 — rising turnaround (root->4->5->b7), octave snap, close on a b7 pickup
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.88f),   // E  root
                    LickStep(scaleDegree = -1, duration = 0.25f, velocity = 0.0f),  // (rest)
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.62f),  // E  root — ghost
                    LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.82f),   // A  4  ┐
                    LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.85f),   // B  5  │ rising turnaround
                    LickStep(scaleDegree = 6, duration = 0.75f, velocity = 0.90f),  // D  b7 ┘
                    LickStep(scaleDegree = 7, duration = 0.25f, velocity = 0.80f),  // E  octave snap off the top
                    LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.78f),   // B  5
                    LickStep(scaleDegree = 6, duration = 1.0f, velocity = 0.92f),   // D  b7 pickup pulling into the loop
                ),
                loopLength = 16,  // 4 bars; steps sum to 16.0 exactly
            ),
            lickMutation = 0.24f,  // bumped for copyright distance (more run-to-run drift off the figure)
            lickOctave = -1,       // auto = midpoint of the bass range (~E2)
            genre = GenreProfile(
                swingAmount = 0.06f,          // tight strut — barely off the grid
                ghostProbability = 0.30f,     // funky ghosts in the kit
                noteRangeLow = 36,
                noteRangeHigh = 64,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.MODAL,
                chordsPerBar = 1,
                customProgression = verseProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_8,
            progressionDriftRange = 0.10f,
            tracks = listOf(
                // Track 0 — Kick (BD): solid, tight, funk-rock four-limb feel.
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.88f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.46f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 1 — Snare (SD): crisp studio backbeat, fills at phrase ends.
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD,
                    volume = 0.66f,
                    timbre = 0.56f,
                    reverbSend = 0.12f,
                ).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.08f,
                        density = 0.36f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.FILL,
                    )
                },
                // Track 2 — Hat (HH): busy 16th-leaning funk ride.
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.54f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f,
                        density = 0.62f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 3 — BASS (VCF filter-funk): THE STRUT. Staccato PLUCK, nearly dry,
                // ROOT_ONLY so the lift climbs the riff up bVI -> bVII -> i.
                OrpheusEngine(
                    engineId = OrpheusEngineId.VCF,
                    volume = 0.88f,
                    harmonics = 0.52f,        // filter bite
                    timbre = 0.55f,
                    morph = 0.42f,
                    noteRangeLow = 28,        // E1
                    noteRangeHigh = 52,       // E3 — auto lick octave centers ~E2
                    reverbSend = 0.05f,       // dry and forward
                    reverbBrightness = 0.30f,
                    glideRate = 0.04f,        // tighter than swamp — funk articulation
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.46f,         // shorter notes = staccato strut
                    lpgColour = 0.45f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(
                            engineId = OrpheusEngineId.PD,   // rounder at low energy
                            lpgMode = LpgMode.SUSTAINED,
                            reverbSend = 0.14f,
                        ),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.ROOT_ONLY,
                            lickMode = LickMode.Fill,
                        ),
                        pan = 0.00f,
                        density = 0.95f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 4 — Funk stabs (CHD native chords): the tight off-hand chank.
                OrpheusEngine(
                    engineId = OrpheusEngineId.CHD,
                    volume = 0.44f,
                    harmonics = 0.45f,
                    timbre = 0.60f,
                    morph = 0.45f,
                    noteRangeLow = 52,        // E3
                    noteRangeHigh = 74,       // D5
                    reverbSend = 0.10f,
                    delaySend = 0.08f,
                    reverbBrightness = 0.50f,
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.36f,         // clipped stabs
                ).let { stabs ->
                    TrackVoice(
                        engineEdm = stabs,
                        engineSpace = stabs.copy(lpgMode = LpgMode.SUSTAINED, reverbSend = 0.20f),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.FUNK_STABS,
                                arpMode = ArpMode.AUTO,          // CHD = native block stabs
                                arpSpeed = 0.20f,
                                arpDirection = ArpDirection.UP,
                                sectionInversion = SectionInversion.FIRST_INVERSION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.20f,
                                    ghostProbability = 0.28f,     // funk ghosts
                                    octaveJumpProbability = 0.12f,
                                    extensionProbability = 0.24f, // m7/9 color — the slink
                                ),
                                fills = CompingFills(
                                    everyNBars = 8,
                                    fillType = FillType.TURNAROUND,
                                    skipProbability = 0.25f,
                                ),
                            ),
                        ),
                        pan = 0.24f,
                        density = 0.32f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 5 — Lead (WSH grit / VA on space): answers in the verse,
                // OWNS the jam (section override pushes it forward).
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.52f,
                    harmonics = 0.66f,        // singing overdrive, less fuzz than garage
                    timbre = 0.58f,
                    morph = 0.45f,
                    noteRangeLow = 55,        // G3
                    noteRangeHigh = 81,       // A5 — room to sing
                    reverbSend = 0.20f,
                    delaySend = 0.18f,
                    reverbBrightness = 0.55f,
                    glideRate = 0.28f,        // bends — notes pulled into each other
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.60f,         // long singing notes
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead.copy(engineId = OrpheusEngineId.VA, lpgMode = LpgMode.SUSTAINED),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
                        pan = -0.18f,
                        density = 0.16f,  // sparse in the verse — answers only
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.CALL_RESPONSE,
                    )
                },
                // Track 6 — String machine (ENS): the 70s studio bed behind the band.
                // Ear-test: the bed held too long and sat too still — shorter holds, quieter,
                // and a faster/deeper slow-LFO so it visibly breathes instead of droning.
                OrpheusEngine(
                    engineId = OrpheusEngineId.ENS,
                    volume = 0.26f,
                    harmonics = 0.48f,
                    timbre = 0.52f,
                    morph = 0.55f,
                    holdProbability = 0.60f,  // sustained bed, but it lets go more often
                    holdLengthMin = 2,
                    holdLengthMax = 4,
                    modLfoRate = 0.09f,       // slow shimmer you can actually hear move
                    modLfoDepth = 0.42f,
                    modLfoShape = 0.4f,
                    modLfoCoupling = 0.15f,
                    noteRangeLow = 52,        // E3
                    noteRangeHigh = 76,       // E5
                    reverbSend = 0.30f,
                    reverbBrightness = 0.45f,
                    lpgMode = LpgMode.SUSTAINED,
                ).let { strings ->
                    TrackVoice(
                        engineEdm = strings,
                        engineSpace = strings.copy(reverbSend = 0.40f),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.PAD,
                                arpMode = ArpMode.NEVER,  // root-tone bed
                                sectionInversion = SectionInversion.ROOT_POSITION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.08f,
                                    ghostProbability = 0.05f,
                                    octaveJumpProbability = 0.05f,
                                    extensionProbability = 0.12f,
                                ),
                            ),
                        ),
                        pan = -0.26f,
                        density = 0.18f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 7 — Texture (NSE / PAR on space): quiet top-end motion.
                OrpheusEngine(
                    engineId = OrpheusEngineId.NSE,
                    volume = 0.20f,
                    timbre = 0.60f,
                    noteRangeLow = 60,
                    noteRangeHigh = 76,
                    reverbSend = 0.24f,
                    reverbBrightness = 0.60f,
                ).let { texture ->
                    TrackVoice(
                        engineEdm = texture,
                        engineSpace = texture.copy(engineId = OrpheusEngineId.PAR, reverbSend = 0.34f),
                        role = TrackRole.Percussive,
                        pan = -0.30f,
                        density = 0.18f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
            ),
            stepCount = 64,  // 4 bars / 16 beats — the whole strut as one LickMode.Fill phrase (longer-lick demo)
            tension = TensionProfile(
                innerBars = 8,
                outerBars = 32,
                outerDepth = 0.50f,
                volume = 0.28f,
                tonal = TonalTension(
                    octaveShift = true,        // the strut jumps the octave at the peak
                    chromaticPassing = 0.15f,  // chromatic passing — the riff's snaky DNA
                ),
                timing = 0.15f,               // tight band — less timing slop than swamp
                evolution = EvolutionTension(
                    timbreLow = 0.32f, timbreHigh = 0.58f, timbreProbability = 0.6f,
                    morphLow = 0.35f, morphHigh = 0.52f, morphProbability = 0.5f,
                    attackPoint = 0.6f, releaseSpeed = 0.4f,
                ),
                spurtChance = 0.10f,
            ),
            effects = VibeEffects(
                delayTimeA = 0.375f,      // dotted-8th — the studio-polish echo
                delayTimeB = 0.25f,
                delayFeedback = 0.24f,
                delayDamping = 0.50f,
                reverbSize = 0.50f,       // bigger room than the garage — studio live room
                reverbDamping = 0.50f,
                reverbBrightness = 0.45f,
                deepFloor = 0.26f,
            ),
        )
    }
}
