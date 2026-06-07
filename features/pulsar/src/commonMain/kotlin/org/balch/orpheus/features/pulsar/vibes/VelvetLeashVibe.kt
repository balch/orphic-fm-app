package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingHumanization
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.LpgMode
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.MacroSource
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.ProgressionAnchor
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
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
 * Velvet Leash — sly mid-tempo strut driven by a tuned-mallet hook over a
 * descending minor walkdown.
 *
 * Captures the cocky, dominating feel of a 60s pop-rock groove: laid-back
 * backbeat with shuffling tambourine, fuzz-tinged bass doubling a chromatic
 * walkdown (i — VII — VI — V), and a marimba lead that bites in the third
 * of the chord and resolves down to the tonic. Chorus pivots to i — iv with
 * a half-time push to the V before the verse loop returns.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class VelvetLeashVibe : VibeProvider {
    override val name: String = "Velvet Leash"

    // VERSE: i — VII — VI — V walkdown (F#m → E → D → C#).
    // One chord per musical bar — the marimba lick replays every bar, transposed
    // by the active chord, so the walkdown is heard in the melody itself.
    private val verseProgression = chords(0, 6, 5, 4)
    private val verseChordsPerBar = 2

    // CHORUS: i — iv — i — V (F#m → Bm → F#m → C#)
    // Pushes off the IV/iv for the lift, then the V sets up the verse return.
    // Two chords per bar gives the chorus its quicker harmonic pulse.
    private val chorusProgression = chords(0, 3, 0, 4)
    private val chorusChordsPerBar = 2

    private val introProgression = chords(0, 0, 3, 4)
    private val introChordsPerBar = 2

    private val sectionList by lazy {
        listOf(
            // 0: intro — drums + sparse marimba state the hook
            Section(
                name = "intro",
                barsMin = 2, barsMax = 4,
                transitions = listOf(SectionTransition(targetIndex = 1, weight = 1f)),
                macroOverrides = MacroOverrides(
                    energy = 0.6f,
                    complexity = 0.5f,
                    space = 0.7f
                ),
                customProgression = introProgression,
                chordsPerBar = introChordsPerBar,
                soloMode = SoloMode.LongFill(
                    probability = 1f,
                    barsMin = 2,
                    barsMax = 2,
                )
            ),
            // 1: verse — full band on the i—VII—VI—V walkdown.
            //    4 pattern bars = 8 musical bars = 2 walkdown cycles (standard pop verse).
            Section(
                name = "verse",
                barsMin = 4, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.55f),
                    SectionTransition(targetIndex = 3, weight = 0.30f),
                    SectionTransition(targetIndex = 4, weight = 0.15f),
                ),
                recencyDecay = 0.5f,
            ),
            // 2: chorus — i—iv—i—V lift, two chords per bar.
            //    The marimba hook PEDALS on the tonic here (per-track FIXED) rather
            //    than FOLLOWing i—iv—i—V. With FOLLOW, the chord offset is added then
            //    folded into the marimba's lowest octave, so the iv (+5) and V (+7)
            //    leaps fold to a lurching up-a-4th / down-a-4th contour — that's the
            //    "disjointed" feel. Pedaling the hook on i keeps it stable while the
            //    keys + bass carry the harmonic motion underneath.
            //    A low-mutation LickBuilder hands the pedaled hook to the lead member
            //    (marimba wins — highest melodic creativity) so the chorus gets a
            //    clear, developing focal melody while the rest of the band ducks.
            Section(
                name = "chorus",
                barsMin = 4, barsMax = 8,
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 0.55f),
                    SectionTransition(targetIndex = 3, weight = 0.30f),
                    SectionTransition(targetIndex = 4, weight = 0.15f),
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 1.25f, complexity = 1.05f, space = 0.85f, mood = 1.15f,
                ),
                customProgression = chorusProgression,
                chordsPerBar = chorusChordsPerBar,
                soloMode = SoloMode.LickBuilder(probability = 0.85f, mutationRate = 0.30f),
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
                ),
            ),
            // 3: solo — band trades over the walkdown.
            //    4–8 pattern bars = 8–16 musical bars = 2–4 walkdown cycles.
            Section(
                name = "solo",
                barsMin = 4, barsMax = 8,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.50f),
                    SectionTransition(targetIndex = 1, weight = 0.30f),
                    SectionTransition(targetIndex = 4, weight = 0.20f),
                ),
                recencyDecay = 0.4f,
                soloMode = SoloMode.Jam(probability = 0.80f),
                macroOverrides = MacroOverrides(
                    energy = 0.95f, complexity = 1.40f, mood = 1.10f,
                ),
            ),
            // 4: breakdown — strip to bass + tambourine + marimba ghost
            Section(
                name = "breakdown",
                barsMin = 4, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.7f),
                    SectionTransition(targetIndex = 1, weight = 0.3f),
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 0.45f, complexity = 0.55f, space = 1.40f, mood = 0.85f,
                ),
                customProgression = introProgression,
                chordsPerBar = introChordsPerBar,
            ),
            // 5: outro — restate the hook, fade out (inherits global default transition)
            Section(
                name = "outro",
                barsMin = 4, barsMax = 4,
                macroOverrides = MacroOverrides(
                    energy = 1.6f,
                    mood = 1.2f,
                    complexity = 0.5f,
                    space = 0.7f
                ),
                customProgression = introProgression,
                chordsPerBar = introChordsPerBar,
                soloMode = SoloMode.LongFill(
                    probability = 1f,
                    barsMin = 2,
                    barsMax = 2,
                )
            ),
        )
    }

    override val vibe by lazy {
        Vibe(
            name = name,
            album = Album.RIF,
            bpm = 96f,
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = sectionList.lastIndex,
                sections = sectionList,
            ),
            envelopeType = EnvelopeType.AD,
            rootNote = RootNote.F_SHARP,
            scaleType = ScaleType.MINOR,
            // ── THE LICK ──────────────────────────────────────────────────────
            // 1-bar mallet figure — replays every musical bar, transposed by the
            // active chord (ChordFollow.FOLLOW). Over the i—VII—VI—V verse walkdown
            // it lands on F#m, E, D, C# in sequence — same shape, descending root.
            //
            // Scale-degree map (F# natural minor / MINOR):
            //   0=F#(root)  1=G#(2nd)  2=A(b3)  3=B(4th)  4=C#(5th)  5=D(b6)  6=E(b7)
            //
            // Shape: root → b3 → 4th → 5th → root → root
            lick = Lick(
                steps = listOf(
                    LickStep(scaleDegree = 0, duration = 1.0f, velocity = 0.85f),  // F# (root)
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.80f),  // A  (b3)
                    LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.75f),  // B  (4th)
                    LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.90f),  // C# (5th)
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),  // F# (root)
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.80f),  // F# (root)
                ),
                loopLength = 0, // 4 beats of notes, no rest — wraps twice across the 32-step pattern
            ),
            lickMutation = 0.42f,
            band = Band(
                members = listOf(
                    BandMember(
                        "Drums",
                        listOf(0, 1, 2),
                        alwaysActive = true,
                        creativity = 0.20f
                    ),
                    BandMember("Bass", listOf(3), creativity = 0.25f, drag = 0.06f),
                    BandMember("Marimba", listOf(4, 5), creativity = 0.40f),
                    BandMember("Keys", listOf(6, 7), creativity = 0.35f),
                ),
                handoffMatrix = bandMatrix(
                    //              DRUM  BASS  MAR   KEYS
                    "Drums" to row(0.00f, 0.30f, 0.55f, 0.15f),
                    "Bass" to row(0.10f, 0.00f, 0.65f, 0.25f),
                    "Marimba" to row(0.10f, 0.30f, 0.00f, 0.60f),
                    "Keys" to row(0.10f, 0.30f, 0.60f, 0.00f),
                ),
                pullInMatrix = bandMatrix(
                    "Drums" to row(0.00f, 0.45f, 0.30f, 0.25f),
                    "Bass" to row(0.30f, 0.00f, 0.45f, 0.25f),
                    "Marimba" to row(0.20f, 0.45f, 0.00f, 0.35f),
                    "Keys" to row(0.20f, 0.40f, 0.40f, 0.00f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 4,
                barsPerLeadMin = 4, barsPerLeadMax = 8,
            ),
            energy = 0.6f,
            complexity = 0.75f,
            space = 0.5f,   // Dryish 60s production
            mood = 0.40f,    // Sly / slightly dark — minor key with a smirk
            deep = 0.75f,
            genre = GenreProfile(
                swingAmount = 0.10f,         // Charlie-Watts-ish laid-back lean
                ghostProbability = 0.20f,
                noteRangeLow = 36,
                noteRangeHigh = 76,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.DARK,
                chordsPerBar = verseChordsPerBar,
                customProgression = verseProgression,
            ),

            // EVERY_4 resets every 4 patterns = every 16 musical bars (epic loop, lots of room for Markov drift)
            // EVERY_2 resets every 2 patterns = exactly when the progression naturally cycles (tight, hypnotic — recommended for hook clarity)
            progressionAnchor = ProgressionAnchor.EVERY_4,
            progressionDriftRange = 0.10f,                  // Tight — the walkdown is the hook
            tracks = listOf(
                // 0: Kick — laid-back four-on-floor with a slight hold on 1
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.80f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.40f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 1: Snare — backbeat on 2 and 4, slight room
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD,
                    volume = 0.70f,
                    harmonics = 0.40f,
                    timbre = 0.55f,
                    reverbSend = 0.20f,
                ).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.10f,
                        density = 0.30f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.FILL,
                    )
                },
                // 2: Hat — steady 8ths, soft swing
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.50f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f,
                        density = 0.55f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 3: Bass — doubles the walkdown. ROOT_ONLY snaps it to the chord
                //    root so the i—VII—VI—V descent is locked. Light grit on EDM,
                //    cleaner string tone on Space.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.70f,
                    harmonics = 0.45f,
                    timbre = 0.40f,
                    morph = 0.45f,
                    noteRangeLow = 33,
                    noteRangeHigh = 52,
                    reverbBrightness = 0.35f,
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.55f,
                    glideRate = 0.0f, // No portamento — bass walks down in clean steps
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.STR),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.ROOT_ONLY,
                        ),
                        pan = 0.00f,
                        density = 0.15f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 4: MARIMBA — the iconic mallet hook. Modal resonator with low
                //    harmonics/timbre and PLUCK LPG = wooden tuned-percussion.
                //    Plays the Vibe's lick (LickMode.Fill spans the full bar).
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX2,
                    volume = 0.78f,
                    harmonics = 0.4596f,                            // DX2 idx 15 "Clav 3" — base
                    harmonicsMacroSource = MacroSource.COMPLEXITY,  // busier sections shift mallet character
                    harmonicsMacroRange = 0.04f,                    // ±~1 patch: Harpsich ↔ Clav 3 ↔ Xylophone
                    timbre = 0.5f,
                    morph = 0.55f,
                    noteRangeLow = 48,
                    noteRangeHigh = 78,
                    reverbSend = 0.25f,
                    delaySend = 0.10f,
                    reverbBrightness = 0.55f,
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.35f,     // Short — percussive mallet, no sustain
                    lpgColour = 0.45f,
                    glideRate = 0.0f,     // Mallets don't slide
                ).let { marimba ->
                    TrackVoice(
                        engineEdm = marimba,
                        engineSpace = marimba.copy(
                            timbre = .25f,
                            reverbSend = .75f,
                            lpgMode = LpgMode.BYPASS,
//                        lpgDecay = 0.75f,
//                        lpgColour = 0.25f,
                        ),
                        role = TrackRole.Melodic(lickMode = LickMode.Fill),
                        pan = 0.20f,
                        density = 0.30f,  // Lick controls the rhythm; density is the fallback fill rate
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 5: Tambourine — PAR (Particle) engine locked into its grains zone.
                // PAR interpolates between a noise side (low h/t/m) and a grains side
                // (high h, mid t, high m). We pin all three so the engine stays
                // explicitly grain-y; the macros never push it into the noise region.
                // No LFO needed — the rhythmic motion comes from BarStrategy.MUTATE.
                OrpheusEngine(
                    engineId = OrpheusEngineId.PAR,
                    volume = 0.45f,
                    harmonics = 1.0f,
                    pinHarmonics = true,
                    timbre = 0.5f,
                    pinTimbre = true,
                    morph = 1.0f,
                    pinMorph = true,
                    reverbSend = 0.25f,
                    reverbBrightness = 0.75f, // Bright shimmer
                ).let { tambourine ->
                    TrackVoice(
                        engineEdm = tambourine,
                        engineSpace = tambourine,
                        role = TrackRole.Chordal(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = -0.25f,
                        density = 0.42f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 6: Keys — light chord stabs on the downbeats (rock comping)
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX3,
                    volume = 0.2f,
                    harmonics = 0.398f,
                    reverbSend = 0.30f,
                    noteRangeLow = 48,
                    noteRangeHigh = 72,
                ).let { keys ->
                    TrackVoice(
                        engineEdm = keys,
                        engineSpace = keys.copy(
                            volume = .35f,
                            harmonics = 0.398f,
                            reverbSend = .7f,
                            reverbBrightness = .7f,
                        ),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.ROCK_DOWNBEATS,
                                humanization = CompingHumanization(
                                    dropProbability = 0.15f,
                                    ghostProbability = 0.10f,
                                    octaveJumpProbability = 0.10f,
                                ),
                            ),
                        ),
                        pan = -0.20f,
                        density = 0.20f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 7: String pad — sustained backdrop, sparse holds, gives the room glue
                OrpheusEngine(
                    engineId = OrpheusEngineId.STR,
                    volume = 0.45f,
                    modLfoRate = 0.08f,
                    modLfoDepth = 0.5f,
                    modLfoShape = 0.4f,
                    modLfoCoupling = 0.2f,
                    holdProbability = 0.7f,
                    holdLengthMin = 4,
                    holdLengthMax = 12,
                    reverbSend = 0.55f,
                    delaySend = 0.20f,
                    noteRangeLow = 48,
                    noteRangeHigh = 67,
                    reverbBrightness = 0.55f,
                    glideRate = 0.30f,
                ).let { pad ->
                    TrackVoice(
                        engineEdm = pad,
                        engineSpace = pad,
                        role = TrackRole.Chordal(chordFollow = ChordFollow.FOLLOW),
                        pan = 0.30f,
                        density = 0.10f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            stepCount = 32,
            tension = TensionProfile(
                innerBars = 4,
                outerBars = 16,
                spurtChance = 0.10f,
                volume = 0.25f,
                tonal = TonalTension(chromaticPassing = 0.10f),
                evolution = EvolutionTension(
                    timbreLow = 0.30f, timbreHigh = 0.55f, timbreProbability = 0.55f,
                    attackPoint = 0.5f, releaseSpeed = 0.4f,
                ),
            ),
            effects = VibeEffects(
                delayTimeA = 0.25f,
                delayTimeB = 0.375f,
                delayFeedback = 0.30f,
                delayDamping = 0.45f,
                reverbSize = 0.45f,
                reverbDamping = 0.50f,
                reverbBrightness = 0.55f,
            ),
        )
    }
}
