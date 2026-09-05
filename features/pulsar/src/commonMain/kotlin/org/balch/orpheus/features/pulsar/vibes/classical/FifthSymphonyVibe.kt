package org.balch.orpheus.features.pulsar.vibes.classical

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickRotation
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.MacroTarget
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
 * Symphony No. 5 in C Minor — the opening movement's four-note motif, reimagined as a
 * driving band arrangement: orchestral rock, not an orchestra. A public-domain classical
 * composition; see the vibe-creator skill's naming carve-out for compositions.
 *
 * The motif is three short notes and a long one, twice, the second statement a step lower.
 * In C minor (C, D, Eb, F, G, Ab, Bb) that is scale degrees 4-4-4-2 / 3-3-3-1 (zero-based):
 * G-G-G-Eb, then F-F-F-D. It is pinned to `ChordFollow.FIXED` on the lead track so it always
 * plays those exact degrees — the whole point of choosing this piece first is that the motif
 * reduces to a single line without losing its identity, so it should never wander off it.
 *
 * The "orchestral rock" read: every voice that carries the motif or the root motion crossfades
 * from an orchestral character at low Energy to a driven rock character at high Energy —
 * literally, raising the conductor's energy hand turns the strings/brass into guitar and bass.
 * Bass: upright string tone (Space) <-> gritty electric bass (EDM). Lead: brass fanfare
 * (Space, DX3 "Brass 1") <-> distorted lead with bite (EDM, waveshaper). A tuned-percussion
 * wildcard (track 7) stands in for timpani under the big hits, and track 5 comps rock power-chord
 * downbeats under the harmony so the band never turns into a solo pianist quoting the tune.
 *
 * Structure narrates the piece's own arc rather than reusing generic verse/chorus names, and
 * every section is a Score: a fixed length in arrangement bars (`barsMin == barsMax`, each a
 * whole multiple of its own progression's length — see the per-section comments below) and a
 * lick pinned via `lickIndex` to one of four `lickRotation` themes.
 *
 * The arc is a DETERMINISTIC CHAIN in sonata order, which is what makes it a Score rather than
 * a vibe with fixed lengths: dawn (hushed, theme 0) -> statement (full band on the motif,
 * theme 0) -> hush (the lyrical second subject on scale degree 2, theme 2) -> development (the
 * motif fragmented, theme 1) -> drive (the recapitulation, theme 0). Every theme is heard once,
 * in order, inside the first two minutes. Only then does the piece branch, at its single cue
 * point on drive: the composer's path is triumph (the coda, motif in augmentation, theme 3),
 * with "round again" and "linger in the development" as the two knowing departures. See
 * `ScoreCatalog`. triumph stays terminal and is still the outro target.
 *
 * An earlier revision branched at statement instead, which put three theme-0 sections in a
 * probable loop and buried the second subject behind a 15% roll a minute in: the piece was
 * audibly one lick. Branch late, after the material has been stated.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class FifthSymphonyVibe : VibeProvider {
    override val name: String = "Symphony No. 5 in C Minor"

    // THE MOTIF — given verbatim by the spec. Zero-based scaleDegree in C MINOR
    // (C=0 D=1 Eb=2 F=3 G=4 Ab=5 Bb=6): G-G-G-Eb (4-4-4-2), then F-F-F-D (3-3-3-1),
    // a step lower. Three short notes and a long one, twice. loopLength=8 beats gives
    // a 1-beat breath before the phrase repeats (7 beats of notes + 1 beat rest = 32
    // steps at 4 steps/beat, matching Vibe.stepCount below exactly).
    private val fateMotif = Lick(
        steps = listOf(
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 2, duration = 2.0f, velocity = 1.0f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.9f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.9f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.9f),
            LickStep(scaleDegree = 1, duration = 2.0f, velocity = 1.0f),
        ),
        loopLength = 8,
    )

    // Slot 1 — the motif fragmented, as the development treats it: the three short notes
    // alone, rising, then the motif's own long note halved rather than removed.
    private val fragment = Lick(
        steps = listOf(
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.9f),
            LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.9f),
            LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 1, duration = 1.0f, velocity = 0.95f),
        ),
        loopLength = 8,
    )

    // Slot 2 — the lyrical answer, centred on degree 2 (E flat). Same seven pitches as
    // C minor, so this reads as the relative major without any key-change mechanism.
    private val secondSubject = Lick(
        steps = listOf(
            LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.7f, glideRate = 0.2f),
            LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.7f),
            LickStep(scaleDegree = 6, duration = 1.0f, velocity = 0.75f),
            LickStep(scaleDegree = 5, duration = 2.0f, velocity = 0.7f, glideRate = 0.25f),
            LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.65f),
            LickStep(scaleDegree = 2, duration = 2.0f, velocity = 0.7f),
        ),
        loopLength = 8,
    )

    // Slot 3 — the motif in augmentation for the final statement: same shape, twice the
    // length, so triumph restates rather than repeats.
    private val augmented = Lick(
        steps = listOf(
            LickStep(scaleDegree = 4, duration = 1.0f, velocity = 1.0f),
            LickStep(scaleDegree = 4, duration = 1.0f, velocity = 1.0f),
            LickStep(scaleDegree = 4, duration = 1.0f, velocity = 1.0f),
            LickStep(scaleDegree = 2, duration = 4.0f, velocity = 1.0f),
        ),
        loopLength = 8,
    )

    // Genre-level harmony: hammer the tonic under the motif (that IS the motif's own
    // gesture), then a hard pivot to the dominant for tension, resolve home. Natural
    // MINOR (per spec) gives a minor v rather than a classical major V — a modal, more
    // "rock" reading of the same harmonic gesture rather than a textbook cadence.
    // i-i-i-i-v-v-i-i over 8 bars (chordsPerBar=1).
    private val mainProgression = chords(0, 0, 0, 0, 4, 4, 0, 0)
    private val mainChordsPerBar = 1

    // Section harmony for drive/triumph: i-iv-v-i, two chords per bar — more harmonic
    // motion for the high-energy sections.
    private val driveProgression = chords(0, 3, 4, 0)
    private val driveChordsPerBar = 2

    // Per-edge transitionBars, named for the musical role they serve (see DogHouseVibe
    // precedent): riseBars is the standard lift between adjacent-energy sections;
    // duskBars is the exhale into a quieter section; theBigLiftBars is THE moment —
    // development -> drive, the long crawl out of the fragmentation into the
    // recapitulation's full-force return of the motif.
    private val riseBars = 2
    private val duskBars = 3
    private val theBigLiftBars = 4

    private val sectionList by lazy {
        listOf(
            // 0: dawn — hushed, the motif not yet entered. Its own short progression
            // (i-i-v-i, 1 chord/bar) resolves fully within 4 arrangement bars;
            // mainProgression is 8 bars long and would cut off mid-cycle here.
            Section(
                name = "dawn",
                barsMin = 4, barsMax = 4,
                lickIndex = 0,
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = riseBars),
                ),
                macroOverrides = MacroOverrides(energy = 0.35f, complexity = 0.3f, space = 0.8f, mood = 0.6f),
                customProgression = chords(0, 0, 4, 0),
                chordsPerBar = 1,
            ),
            // 1: statement — full band on the motif, one full mainProgression cycle.
            // Exits down into the lyrical answer, sonata-fashion, rather than branching.
            Section(
                name = "statement",
                barsMin = 8, barsMax = 8,
                lickIndex = 0,
                transitions = listOf(
                    SectionTransition(targetIndex = 4, weight = 1.0f, transitionBars = duskBars),
                ),
                macroOverrides = null,
            ),
            // 2: drive — the recapitulation, motif at full force. driveProgression is two
            // arrangement bars, so 2 cycles. THE cue point, and the only branch in the
            // piece: it sits at the end of a complete pass, where "again or take it home"
            // is a real musical question. Weights are placeholders pending tuning.
            Section(
                name = "drive",
                barsMin = 4, barsMax = 4,
                lickIndex = 0,
                // All three ramps are riseBars, matching the cue's 2-bar window: a longer
                // pre-roll on a 4-bar section would start morphing before the conductor
                // is even asked.
                transitions = listOf(
                    SectionTransition(targetIndex = 5, weight = 0.70f, transitionBars = riseBars),
                    SectionTransition(targetIndex = 1, weight = 0.20f, transitionBars = riseBars),
                    SectionTransition(targetIndex = 3, weight = 0.10f, transitionBars = riseBars),
                ),
                macroOverrides = MacroOverrides(energy = 1.4f, complexity = 1.3f, space = 0.7f, mood = 1.1f),
                customProgression = driveProgression,
                chordsPerBar = driveChordsPerBar,
            ),
            // 3: development — the motif fragmented and passed around, then the big lift
            // into the recapitulation.
            Section(
                name = "development",
                barsMin = 8, barsMax = 8,
                lickIndex = 1,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 1.0f, transitionBars = theBigLiftBars),
                ),
                macroOverrides = MacroOverrides(energy = 0.9f, complexity = 1.4f, space = 1.2f, mood = 1.0f),
                soloMode = SoloMode.LickBuilder(probability = 0.85f, mutationRate = 0.35f),
            ),
            // 4: hush — the lyrical second subject, centred on degree 2. Same seven
            // pitches as C minor, heard as the relative major. Sits between the statement
            // and the development, which is where the Fifth actually puts it.
            Section(
                name = "hush",
                barsMin = 4, barsMax = 4,
                lickIndex = 2,
                transitions = listOf(
                    SectionTransition(targetIndex = 3, weight = 1.0f, transitionBars = riseBars),
                ),
                macroOverrides = MacroOverrides(energy = 0.3f, complexity = 0.4f, space = 1.6f, mood = 0.7f),
                customProgression = chords(2, 5, 1, 4),
                chordsPerBar = driveChordsPerBar,
            ),
            // 5: triumph — the coda, motif in augmentation. Terminal: the piece rests
            // here. Reached as the cue's CENTER branch, and still the outro target.
            Section(
                name = "triumph",
                barsMin = 4, barsMax = 4,
                lickIndex = 3,
                macroOverrides = MacroOverrides(energy = 1.6f, complexity = 0.5f, space = 0.6f, mood = 1.3f),
                customProgression = driveProgression,
                chordsPerBar = driveChordsPerBar,
                soloMode = SoloMode.LongFill(probability = 1f, barsMin = 2, barsMax = 2),
            ),
        )
    }

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            bpm = 108f,
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = sectionList.lastIndex,
                sections = sectionList,
            ),
            // BLEND crossfades AD (punchy) <-> TIDES (sustained) with Energy, mirroring
            // the per-track engine crossfade below: the whole vibe leans orchestral at
            // low Energy and rock at high Energy, not just the tone colors.
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.C,
            scaleType = ScaleType.MINOR,
            lick = fateMotif,
            lickRotation = LickRotation(pool = listOf(fateMotif, fragment, secondSubject, augmented)),
            lickMutation = 0.12f, // low — the motif is obsessive/mechanical, not jazzy. Recognizability first.
            band = Band(
                members = listOf(
                    BandMember("Drummer", listOf(0, 1, 2), alwaysActive = true, loudness = 0.7f, creativity = 0.25f),
                    BandMember("Bassist", listOf(3), loudness = 0.75f, creativity = 0.4f),
                    BandMember("Fate", listOf(4), loudness = 0.9f, creativity = 0.35f),
                    BandMember("Strings", listOf(5, 6, 7), loudness = 0.5f, creativity = 0.6f),
                ),
                handoffMatrix = bandMatrix(
                    //            DRUM  BASS  FATE  STR
                    "Drummer" to row(0.00f, 0.35f, 0.40f, 0.10f),
                    "Bassist" to row(0.25f, 0.00f, 0.45f, 0.15f),
                    "Fate" to row(0.15f, 0.30f, 0.00f, 0.35f),
                    "Strings" to row(0.10f, 0.25f, 0.45f, 0.00f),
                ),
                pullInMatrix = bandMatrix(
                    "Drummer" to row(0.00f, 0.35f, 0.35f, 0.10f),
                    "Bassist" to row(0.25f, 0.00f, 0.50f, 0.15f),
                    "Fate" to row(0.20f, 0.40f, 0.00f, 0.30f),
                    "Strings" to row(0.15f, 0.25f, 0.40f, 0.00f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 4,
                barsPerLeadMin = 4, barsPerLeadMax = 8,
            ),
            energy = 0.55f,
            complexity = 0.45f,
            space = 0.45f,
            mood = 0.40f,
            deep = 0.35f,
            genre = GenreProfile(
                swingAmount = 0.0f, // straight — this is rock, not blues
                ghostProbability = 0.15f,
                noteRangeLow = 36,
                noteRangeHigh = 64,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.DARK,
                chordsPerBar = mainChordsPerBar,
                customProgression = mainProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_8, // reset each 8-bar phrase, matching the progression length
            progressionDriftRange = 0.15f,                  // tight — preserve the dramatic i-i-i-i-v-v-i-i shape
            tracks = listOf(
                // 0: Kick — driving straight rock, no swing.
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.85f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.50f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 1: Snare — rock backbeat on 2 and 4.
                OrpheusEngine(engineId = OrpheusEngineId.SD, volume = 0.70f).let { snare ->
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
                // 2: Hat — steady, driving 16ths.
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.55f).let { hat ->
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
                // 3: Bass — electric bass carrying the root motion, snapped to the
                // chord root. EDM = gritty electric (WSH); Space = upright orchestral
                // double bass (STR). This is the "orchestral <-> rock" crossfade,
                // same idiom as the lead below.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.78f,
                    noteRangeLow = 28,
                    noteRangeHigh = 48,
                    reverbBrightness = 0.30f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.STR, reverbBrightness = 0.45f),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.00f,
                        density = 0.45f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 4: THE MOTIF — lead with bite. FIXED so it always plays the exact
                // degrees above regardless of the chord underneath (the whole point:
                // this line must never wander). EDM = distorted lead guitar-ish
                // waveshaper; Space = DX3 "Brass 1" fanfare (idx 29, harmonics
                // centerpoint 0.888 — auto-pinned, see fm_patches.md). Energy crossfade
                // = brass fanfare at low Energy, distorted guitar riff at high Energy.
                // Custom macro map: MELODIC's moodTimbre floor raised so the lead keeps
                // some edge/bite even when Mood sits low.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.85f,
                    noteRangeLow = 55,
                    noteRangeHigh = 79,
                    glideRate = 0.06f,
                    reverbBrightness = 0.55f,
                    delaySend = 0.15f,
                ).let { fate ->
                    TrackVoice(
                        engineEdm = fate,
                        engineSpace = fate.copy(
                            engineId = OrpheusEngineId.DX3,
                            harmonics = 0.888f, // DX3 idx 29 "Brass 1" — auto-pinned
                            reverbBrightness = 0.6f,
                            reverbSend = 0.35f,
                            glideRate = 0.0f, // brass fanfare hits, no slide
                        ),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FIXED,
                            lickMode = LickMode.Fill,
                        ),
                        pan = 0.05f,
                        density = 0.6f, // lick controls the rhythm; density is the fallback fill rate
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC.copy(
                            moodTimbre = MacroTarget(0.5f, 0.85f), // keep bite even at low Mood
                        ),
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 5: Chordal comping — rock power-chord downbeats under the harmony,
                // so the band never turns into a solo pianist quoting the tune. EDM =
                // thick analog stab; Space = string ensemble.
                OrpheusEngine(
                    engineId = OrpheusEngineId.VA,
                    volume = 0.45f,
                    noteRangeLow = 43,
                    noteRangeHigh = 67,
                    reverbSend = 0.20f,
                ).let { comp ->
                    TrackVoice(
                        engineEdm = comp,
                        engineSpace = comp.copy(
                            engineId = OrpheusEngineId.ENS,
                            volume = 0.38f,
                            reverbSend = 0.5f,
                            reverbBrightness = 0.6f,
                        ),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(style = CompingStyle.ROCK_DOWNBEATS),
                        ),
                        pan = -0.20f,
                        density = 0.35f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 6: String pad — the orchestral backbone. Always strings, on both
                // sides, so there is a constant orchestral presence under the driving
                // rock foreground regardless of Energy.
                OrpheusEngine(
                    engineId = OrpheusEngineId.STR,
                    volume = 0.32f,
                    modLfoRate = 0.09f,
                    modLfoDepth = 0.6f,
                    modLfoShape = 0.4f,
                    modLfoCoupling = 0.25f,
                    holdProbability = 0.8f,
                    holdLengthMin = 6,
                    holdLengthMax = 16,
                    reverbSend = 0.55f,
                    delaySend = 0.30f,
                    noteRangeLow = 43,
                    noteRangeHigh = 62,
                    reverbBrightness = 0.55f,
                    glideRate = 0.35f,
                ).let { strings ->
                    TrackVoice(
                        engineEdm = strings,
                        engineSpace = strings,
                        role = TrackRole.Melodic(),
                        pan = 0.30f,
                        density = 0.08f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
                // 7: Timpani wildcard — tuned percussion standing in for orchestral
                // timpani under the big hits. Low register, sparse, dramatic accents.
                OrpheusEngine(
                    engineId = OrpheusEngineId.MOD,
                    volume = 0.35f,
                    modLfoDepth = 0.0f,
                    holdProbability = 0.15f,
                    holdLengthMin = 2,
                    holdLengthMax = 4,
                    reverbSend = 0.5f,
                    noteRangeLow = 33,
                    noteRangeHigh = 48,
                    reverbBrightness = 0.4f,
                ).let { timpani ->
                    TrackVoice(
                        engineEdm = timpani,
                        engineSpace = timpani,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED),
                        pan = 0.0f,
                        density = 0.12f,
                        envelopeProfile = EnvelopeProfile.WILD,
                        macroMap = TrackMacroMap.WILD,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            // loopLength=8 beats * 4 steps/beat = 32 steps: one full lick cycle
            // (7 beats of notes + 1 beat rest) fits Vibe.stepCount exactly.
            stepCount = 32,
            tension = TensionProfile(
                innerBars = 4, // tight — matches the motif's insistence
                spurtChance = 0.10f,
                volume = 0.40f,
                tonal = TonalTension(octaveShift = true, chromaticPassing = 0.12f),
                timing = 0.20f,
                evolution = EvolutionTension(
                    timbreLow = 0.30f, timbreHigh = 0.65f, timbreProbability = 0.65f,
                    attackPoint = 0.5f, releaseSpeed = 0.4f,
                ),
            ),
            effects = VibeEffects(
                delayTimeA = 0.25f,
                delayTimeB = 0.375f,
                delayFeedback = 0.30f,
                delayDamping = 0.45f,
                reverbSize = 0.55f,
                reverbDamping = 0.45f,
                reverbBrightness = 0.45f, // moderate-dark, matches the C minor key
            ),
        )
    }
}
