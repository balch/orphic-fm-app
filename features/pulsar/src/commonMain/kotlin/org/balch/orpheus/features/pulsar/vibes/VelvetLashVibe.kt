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
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Velvet Lash — sly mid-tempo strut driven by a tuned-mallet hook over a
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
class VelvetLashVibe : VibeProvider {
    // VERSE: i — VII — VI — V walkdown (F#m → E → D → C#)
    // Two bars per chord matches the intro tab phrasing: F#m×2, E×2, D×2, C#×2.
    private val verseProgression = chords(0, 0, 6, 6, 5, 5, 4, 4)
    private val verseChordsPerBar = 1

    // CHORUS: i — iv — i — V (F#m → Bm → F#m → C#)
    // Pushes off the IV/iv for the lift, then the V sets up the verse return.
    // Two chords per bar gives the chorus its quicker harmonic pulse.
    private val chorusProgression = chords(0, 3, 0, 4)
    private val chorusChordsPerBar = 2

    private val introProgression = chords(0, 0, 3, 4)
    private val introChordsPerBar = 2

    override val vibe = Vibe(
        name = "Velvet Lash",
        album = Album.STEALTH,
        bpm = 84f,
        envelopeType = EnvelopeType.AD,
        rootNote = RootNote.F_SHARP,
        scaleType = ScaleType.MINOR,
        // ── THE LICK ──────────────────────────────────────────────────────
        // 2-bar ascending bass hook, transcribed from the intro guitar tab.
        //
        // Scale-degree map (F# natural minor / MINOR):
        //   0=F#(root)  1=G#(2nd)  2=A(b3)  3=B(4th)  4=C#(5th)  5=D(b6)  6=E(b7)
        //
        // Bar 1 — ascend root→b3→4th→5th then return to root (over F#m)
        // Bar 2 — same shape starting on the b7 (over E / bVII chord)
        lick = Lick(
            steps = listOf(
                // Bar 1: F# — A — B — C# — F# — F#
                LickStep(scaleDegree = 0, duration = 1.0f, velocity = 0.85f),  // F# (root)
                LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.80f),  // A  (b3)
                LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.75f),  // B  (4th)
                LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.90f),  // C# (5th)
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),  // F# (root)
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.80f),  // F# (root)
                // Bar 2: E — G# — A — B — E — E
                LickStep(scaleDegree = 6, duration = 1.0f, velocity = 0.80f),  // E  (b7)
                LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.75f),  // G# (2nd)
                LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.75f),  // A  (b3)
                LickStep(scaleDegree = 3, duration = 1.0f, velocity = 0.85f),  // B  (4th)
                LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.80f),  // E  (b7)
                LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.75f),  // E  (b7)
            ),
            loopLength = 16, // 2 bars in 4/4
        ),
        lickMutation = 0.20f, // Low — the hook should stay recognizable
        band = Band(
            members = listOf(
                BandMember("Drums", listOf(0, 1, 2, 5), alwaysActive = true, creativity = 0.20f),
                BandMember("Bass", listOf(3), creativity = 0.25f, drag = 0.06f),
                BandMember("Marimba", listOf(4), creativity = 0.40f),
                BandMember("Keys", listOf(6, 7), creativity = 0.35f),
            ),
            handoffMatrix = bandMatrix(
                //              DRUM  BASS  MAR   KEYS
                "Drums"   to row(0.00f, 0.30f, 0.55f, 0.15f),
                "Bass"    to row(0.10f, 0.00f, 0.65f, 0.25f),
                "Marimba" to row(0.10f, 0.30f, 0.00f, 0.60f),
                "Keys"    to row(0.10f, 0.30f, 0.60f, 0.00f),
            ),
            pullInMatrix = bandMatrix(
                "Drums"   to row(0.00f, 0.45f, 0.30f, 0.25f),
                "Bass"    to row(0.30f, 0.00f, 0.45f, 0.25f),
                "Marimba" to row(0.20f, 0.45f, 0.00f, 0.35f),
                "Keys"    to row(0.20f, 0.40f, 0.40f, 0.00f),
            ),
            pullInBarsMin = 2, pullInBarsMax = 4,
            barsPerLeadMin = 4, barsPerLeadMax = 8,
        ),
        energy = 0.75f,
        complexity = 0.75f,
        space = 0.75f,   // Dryish 60s production
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
        progressionAnchor = ProgressionAnchor.EVERY_8, // Reset each 8-bar walkdown cycle (2 bars per chord)
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
                        chordFollow = ChordFollow.FOLLOW,
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
                harmonics = 0.54f,
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
            // 5: Tambourine — 16th-note shimmer, the 60s production fingerprint
            OrpheusEngine(
                engineId = OrpheusEngineId.PAR,
                volume = 0.45f,
                harmonics = 0.55f,
                timbre = 0.95f,
                morph = 0.95f,
                reverbSend = 0.25f,
                reverbBrightness = 0.75f, // Bright shimmer
            ).let { tambourine ->
                TrackVoice(
                    engineEdm = tambourine,
                    engineSpace = tambourine,
                    role = TrackRole.Percussive,
                    pan = -0.25f,
                    density = 0.55f,
                    envelopeProfile = EnvelopeProfile.RHYTHM,
                    macroMap = TrackMacroMap.RHYTHM,
                    barStrategy = BarStrategy.MUTATE,
                )
            },
            // 6: Keys — light chord stabs on the downbeats (rock comping)
            OrpheusEngine(
                engineId = OrpheusEngineId.DX3,
                volume = 0.60f,
                harmonics = 0.32f,    // DX2 idx 10: Steinway-ish piano
                timbre = 0.45f,
                morph = 0.40f,
                reverbSend = 0.30f,
                noteRangeLow = 48,
                noteRangeHigh = 72,
            ).let { keys ->
                TrackVoice(
                    engineEdm = keys,
                    engineSpace = keys.copy(engineId = OrpheusEngineId.CHD),
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
        arrangement = Arrangement(
            introIndex = 0,
            sections = listOf(
                // 0: intro — drums + sparse marimba state the hook
                Section(
                    name = "intro",
                    barsMin = 2, barsMax = 4,
                    transitions = listOf(SectionTransition(targetIndex = 1, weight = 1f)),
                    macroOverrides = MacroOverrides(energy = 0.6f, complexity = 0.5f, space = 0.7f),
                    customProgression = introProgression,
                    chordsPerBar = introChordsPerBar,
                    soloMode = SoloMode.LongFill(
                        probability = 1f,
                        barsMin = 2,
                        barsMax = 2,
                    )
                ),
                // 1: verse — full band on the i—VII—VI—V walkdown
                Section(
                    name = "verse",
                    barsMin = 8, barsMax = 8,
                    transitions = listOf(
                        SectionTransition(targetIndex = 2, weight = 0.55f),
                        SectionTransition(targetIndex = 3, weight = 0.30f),
                        SectionTransition(targetIndex = 4, weight = 0.15f),
                    ),
                    recencyDecay = 0.5f,
                ),
                // 2: chorus — i—iv—i—V lift, two chords per bar
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
                        energy = 1.25f, complexity = 1.20f, space = 0.85f, mood = 1.15f,
                    ),
                    customProgression = chorusProgression,
                    chordsPerBar = chorusChordsPerBar,
                ),
                // 3: solo — band trades over the walkdown
                Section(
                    name = "solo",
                    barsMin = 8, barsMax = 16,
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
            ),
        ),
    )
}
