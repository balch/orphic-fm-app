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
import org.balch.orpheus.features.pulsar.models.LickSource
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.LpgMode
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
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Symphony No. 5 in C Minor — the opening movement's four-note motif, reimagined as a
 * driving band arrangement: orchestral electro, not an orchestra. A public-domain classical
 * composition; see the vibe-creator skill's naming carve-out for compositions.
 *
 * The motif is three short notes and a long one, twice, the second statement a step lower.
 * In C minor (C, D, Eb, F, G, Ab, Bb) that is scale degrees 4-4-4-2 / 3-3-3-1 (zero-based):
 * G-G-G-Eb, then F-F-F-D. It is pinned to `ChordFollow.FIXED` on the lead track so it always
 * plays those exact degrees — the whole point of choosing this piece first is that the motif
 * reduces to a single line without losing its identity, so it should never wander off it.
 *
 * The "orchestral electro" read: every voice that carries the motif or the root motion
 * crossfades from an orchestral character at low Energy to a synth character at high Energy —
 * literally, raising the conductor's energy hand turns the strings/brass into a clean synth lead and
 * filter bass. The rhythm section is a pair of tuned kettledrums (tracks 0-1, following the
 * root and the scale), a triangle where a hat would be, and a gran cassa wildcard.
 * Bass: upright string tone (Space) <-> plucked filter bass (EDM). Lead: brass fanfare
 * (Space, DX3 "Brass 1") <-> near-pure panel oscillator (EDM, OSC). A tuned-percussion
 * wildcard (track 7) is the orchestra's bass drum under the big hits, and track 5 comps power-chord
 * downbeats under the harmony so the band never turns into a solo pianist quoting the tune.
 *
 * Structure narrates the piece's own arc rather than reusing generic verse/chorus names, and
 * every section is a Score: a fixed length in arrangement bars (`barsMin == barsMax`, each a
 * whole multiple of its own progression's length — see the per-section comments below) and a
 * lick pinned via `lickIndex` to one of eight `lickRotation` themes, one per landmark of the
 * movement. Every lick is budgeted to exactly 8 beats: the lead is `LickMode.Fill` over a
 * 32-step pattern, so a theme is quoted at the length that fits, not its length in the score.
 *
 * The arc is a DETERMINISTIC CHAIN in sonata order, which is what makes it a Score rather than
 * a vibe with fixed lengths: dawn (the bare motif, theme 0) -> cascade (the main theme climbing
 * through the voices, theme 1) -> horn (the fanfare into E flat, theme 2) -> hush (the second
 * subject, theme 3) -> development (the motif fragmented, theme 4) -> dwindle (the exchange
 * thinning to silence, theme 5) -> drive (the recapitulation, theme 0) -> cadenza (the oboe's
 * solo bar, theme 6) -> surge (the recap resumes, theme 1). Only then does the piece branch, at
 * its single cue point on surge: the composer's path is triumph (the coda, theme 7), with
 * "round again" and "linger in the development" as the two knowing departures. triumph stays
 * terminal and is still the outro target.
 *
 * An earlier revision branched at the first statement instead, which put three theme-0 sections
 * in a probable loop and buried the second subject behind a 15% roll a minute in: the piece was
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

    // Slot 1 — the main theme proper: the motif handed up through the strings, each entry
    // a voice higher (G-G-G-Eb, Ab-Ab-Ab-G, Eb-Eb-Eb-C an octave up). Degrees at or above 7
    // wrap an octave, which is how the third entry climbs without moving the lick's octave.
    private val cascade = Lick(
        steps = listOf(
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.8f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.8f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.8f),
            LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.85f),
            LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.9f),
            LickStep(scaleDegree = 9, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 9, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 9, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 7, duration = 1.5f, velocity = 1.0f),
        ),
        loopLength = 8,
    )

    // Slot 2 — the horn call that announces the second subject: the motif's rhythm stretched
    // over a falling fifth, Bb-Bb-Bb-Eb, then F and Bb in long fanfare notes. The original's
    // last Bb drops below C; a negative degree is a REST here, so it answers at the top instead.
    private val hornCall = Lick(
        steps = listOf(
            LickStep(scaleDegree = 6, duration = 0.5f, velocity = 1.0f),
            LickStep(scaleDegree = 6, duration = 0.5f, velocity = 1.0f),
            LickStep(scaleDegree = 6, duration = 0.5f, velocity = 1.0f),
            LickStep(scaleDegree = 2, duration = 2.0f, velocity = 1.0f),
            LickStep(scaleDegree = 3, duration = 2.0f, velocity = 0.95f),
            LickStep(scaleDegree = 6, duration = 2.0f, velocity = 0.95f),
        ),
        loopLength = 8,
    )

    // Slot 3 — the second subject as written: Bb-Eb-D-Eb / F-C-C-Bb, even and dolce, sitting
    // in the upper octave. Same seven pitches as C minor, so it reads as the relative major
    // (E flat) without any key-change mechanism. Glides make it sing against the motif's hits.
    private val secondSubject = Lick(
        steps = listOf(
            LickStep(scaleDegree = 6, duration = 1.0f, velocity = 0.65f),
            LickStep(scaleDegree = 9, duration = 1.0f, velocity = 0.7f, glideRate = 0.25f),
            LickStep(scaleDegree = 8, duration = 1.0f, velocity = 0.65f, glideRate = 0.2f),
            LickStep(scaleDegree = 9, duration = 1.0f, velocity = 0.7f, glideRate = 0.2f),
            LickStep(scaleDegree = 10, duration = 1.0f, velocity = 0.75f, glideRate = 0.2f),
            LickStep(scaleDegree = 7, duration = 1.0f, velocity = 0.7f, glideRate = 0.25f),
            LickStep(scaleDegree = 7, duration = 1.0f, velocity = 0.65f),
            LickStep(scaleDegree = 6, duration = 1.0f, velocity = 0.6f, glideRate = 0.2f),
        ),
        loopLength = 8,
    )

    // Slot 4 — the motif fragmented, as the development treats it: the three short notes
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

    // Slot 5 — the development's famous dwindle: the horn call worn down to two notes, then
    // one, winds and strings trading long tones that sink and thin toward silence. The falling
    // hitProbability IS the thinning; tension lifts it back, so the exchange refills as the
    // recapitulation approaches.
    private val dwindle = Lick(
        steps = listOf(
            LickStep(scaleDegree = 9, duration = 2.0f, velocity = 0.6f),
            LickStep(scaleDegree = 5, duration = 2.0f, velocity = 0.5f, hitProbability = 0.75f),
            LickStep(scaleDegree = 8, duration = 2.0f, velocity = 0.45f, hitProbability = 0.55f),
            LickStep(scaleDegree = 4, duration = 2.0f, velocity = 0.35f, hitProbability = 0.35f),
        ),
        loopLength = 8,
    )

    // Slot 6 — the oboe cadenza that stops the recapitulation in its tracks: one held G, a
    // turn over Ab, and a slow slide down to D. Every step glides; nothing is struck.
    private val oboeCadenza = Lick(
        steps = listOf(
            LickStep(scaleDegree = 4, duration = 2.0f, velocity = 0.7f),
            LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.6f, glideRate = 0.3f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.6f, glideRate = 0.3f),
            LickStep(scaleDegree = 3, duration = 1.0f, velocity = 0.6f, glideRate = 0.35f),
            LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.55f, glideRate = 0.35f),
            LickStep(scaleDegree = 1, duration = 3.0f, velocity = 0.6f, glideRate = 0.45f),
        ),
        loopLength = 8,
    )

    // Slot 7 — the coda: hammered eighths on the tonic then the dominant, and the motif one
    // last time with its long note left to ring. Restates rather than repeats.
    private val codaHammer = Lick(
        steps = listOf(
            LickStep(scaleDegree = 7, duration = 0.5f, velocity = 1.0f),
            LickStep(scaleDegree = 7, duration = 0.5f, velocity = 0.9f),
            LickStep(scaleDegree = 7, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 7, duration = 0.5f, velocity = 0.9f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 1.0f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.9f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.9f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 1.0f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 1.0f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 1.0f),
            LickStep(scaleDegree = 2, duration = 2.5f, velocity = 1.0f),
        ),
        loopLength = 8,
    )

    // The low strings' answer, on the bass-owned channel: the motif's own rhythm on the chord
    // root, entering off an eighth rest exactly as the score does, then a root-fifth-third
    // tail. FOLLOW on the bass track carries it through every section's progression.
    private val lowStrings = Lick(
        steps = listOf(
            LickStep(scaleDegree = -1, duration = 0.5f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 0, duration = 1.0f, velocity = 0.95f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.7f, hitProbability = 0.6f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.7f, hitProbability = 0.6f),
            LickStep(scaleDegree = -1, duration = 0.5f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.9f),
            LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.8f, hitProbability = 0.7f),
        ),
        loopLength = 8,
    )

    // Genre-level harmony: hammer the tonic under the motif (that IS the motif's own
    // gesture), then a hard pivot to the dominant for tension, resolve home. Natural
    // MINOR (per spec) gives a minor v rather than a classical major V — a modal
    // reading of the same harmonic gesture rather than a textbook cadence.
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

    // The timpani's macro response. Built on RHYTHM (drums still thin out with Energy and vary
    // with Complexity) but the tone knobs are held in the membrane neighbourhood: on BD,
    // harmonics above 0.5 is overdrive and timbre is tone, neither of which a timpanist has.
    // complexitySwing stays at zero — it is read from track 0 only, and this piece is straight.
    private val timpaniMacroMap = TrackMacroMap.RHYTHM.copy(
        energyDensity = MacroTarget(0.35f, 0.75f),
        complexitySwing = MacroTarget(0.0f, 0.0f),
        complexityVariation = MacroTarget(0.05f, 0.25f),
        moodHarmonics = MacroTarget(0.05f, 0.20f),
        moodTimbre = MacroTarget(0.20f, 0.40f),
    )

    private val sectionList by lazy {
        listOf(
            // 0: dawn — the bare motif, hushed. Its own short progression (i-i-v-i, 1 chord/bar)
            // resolves fully within 4 arrangement bars; mainProgression is 8 bars long and
            // would cut off mid-cycle here.
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
            // 1: cascade — the main theme, full band, the motif climbing voice over voice.
            // One full mainProgression cycle.
            Section(
                name = "cascade",
                barsMin = 8, barsMax = 8,
                lickIndex = 1,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 1.0f, transitionBars = riseBars),
                ),
                macroOverrides = null,
            ),
            // 2: horn — the fanfare that turns the corner into E flat. The progression shadows
            // the call's own notes (VII-III-iv-VII), two chords a bar, so 2 cycles.
            // Energy sits LOW on purpose: Energy is the lead's engine crossfade, and a horn
            // call wants the DX3 brass, not the synth side. The
            // lead's own volume and reverb make up the weight the macro takes away.
            Section(
                name = "horn",
                barsMin = 4, barsMax = 4,
                lickIndex = 2,
                transitions = listOf(
                    SectionTransition(targetIndex = 3, weight = 1.0f, transitionBars = duskBars),
                ),
                macroOverrides = MacroOverrides(energy = 0.55f, complexity = 0.5f, space = 1.1f, mood = 0.8f),
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(volume = 1.0f, reverbSend = 0.45f),
                ),
                customProgression = chords(6, 2, 3, 6),
                chordsPerBar = driveChordsPerBar,
            ),
            // 3: hush — the lyrical second subject, heard as the relative major. 8 bars = the
            // theme four times over 4 cycles of its progression: long enough to settle.
            Section(
                name = "hush",
                barsMin = 8, barsMax = 8,
                lickIndex = 3,
                transitions = listOf(
                    SectionTransition(targetIndex = 4, weight = 1.0f, transitionBars = riseBars),
                ),
                macroOverrides = MacroOverrides(energy = 0.3f, complexity = 0.4f, space = 1.6f, mood = 0.7f),
                customProgression = chords(2, 5, 1, 4),
                chordsPerBar = driveChordsPerBar,
            ),
            // 4: development — the motif fragmented and passed around.
            Section(
                name = "development",
                barsMin = 8, barsMax = 8,
                lickIndex = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 5, weight = 1.0f, transitionBars = duskBars),
                ),
                macroOverrides = MacroOverrides(energy = 0.9f, complexity = 1.4f, space = 1.2f, mood = 1.0f),
                soloMode = SoloMode.LickBuilder(probability = 0.85f, mutationRate = 0.35f),
            ),
            // 5: dwindle — the exchange thinning to nothing over iv-iv-v-v, hanging on the
            // dominant. Then the big lift: the whole section is the crawl into the recap.
            Section(
                name = "dwindle",
                barsMin = 4, barsMax = 4,
                lickIndex = 5,
                transitions = listOf(
                    SectionTransition(targetIndex = 6, weight = 1.0f, transitionBars = theBigLiftBars),
                ),
                macroOverrides = MacroOverrides(energy = 0.25f, complexity = 0.3f, space = 1.7f, mood = 0.5f),
                customProgression = chords(3, 3, 4, 4),
                chordsPerBar = 1,
                // The timpani roll under the crawl into the recap: every held low-drum note
                // re-struck on the 16ths, at a density that makes it a roll and not a pulse.
                trackOverrides = mapOf(
                    0 to TrackSectionOverride(lpgMode = LpgMode.PLUCK_REPEAT, density = 0.6f, volume = 0.7f),
                ),
            ),
            // 6: drive — the recapitulation, motif at full force. driveProgression is two
            // arrangement bars, so 2 cycles. Interrupted, as in the score, by the oboe.
            Section(
                name = "drive",
                barsMin = 4, barsMax = 4,
                lickIndex = 0,
                transitions = listOf(
                    SectionTransition(targetIndex = 7, weight = 1.0f, transitionBars = riseBars),
                ),
                macroOverrides = MacroOverrides(energy = 1.4f, complexity = 1.3f, space = 0.7f, mood = 1.1f),
                customProgression = driveProgression,
                chordsPerBar = driveChordsPerBar,
            ),
            // 7: cadenza — everything stops for one slow solo line over a held i-v.
            Section(
                name = "cadenza",
                barsMin = 2, barsMax = 2,
                lickIndex = 6,
                transitions = listOf(
                    SectionTransition(targetIndex = 8, weight = 1.0f, transitionBars = riseBars),
                ),
                macroOverrides = MacroOverrides(energy = 0.2f, complexity = 0.2f, space = 1.8f, mood = 0.6f),
                customProgression = chords(0, 4),
                chordsPerBar = 1,
            ),
            // 8: surge — the recap resumes on the cascade. THE cue point, and the only branch
            // in the piece: it sits at the end of a complete pass, where "again or take it
            // home" is a real musical question. Weights are placeholders pending tuning.
            Section(
                name = "surge",
                barsMin = 4, barsMax = 4,
                lickIndex = 1,
                // All three ramps are riseBars, matching the cue's 2-bar window: a longer
                // pre-roll on a 4-bar section would start morphing before the conductor
                // is even asked.
                transitions = listOf(
                    SectionTransition(targetIndex = 9, weight = 0.70f, transitionBars = riseBars),
                    SectionTransition(targetIndex = 1, weight = 0.20f, transitionBars = riseBars),
                    SectionTransition(targetIndex = 4, weight = 0.10f, transitionBars = riseBars),
                ),
                macroOverrides = MacroOverrides(energy = 1.5f, complexity = 1.2f, space = 0.7f, mood = 1.2f),
                // A second LickBuilder section, so the drum-lead gate has somewhere to fire besides
                // the development. mutationRate stays low: the cascade has to stay recognisable.
                soloMode = SoloMode.LickBuilder(probability = 0.7f, mutationRate = 0.15f),
                customProgression = driveProgression,
                chordsPerBar = driveChordsPerBar,
            ),
            // 9: triumph — the coda, hammered. Terminal: the piece rests here. Reached as
            // the cue's CENTER branch, and still the outro target.
            Section(
                name = "triumph",
                barsMin = 4, barsMax = 4,
                lickIndex = 7,
                macroOverrides = MacroOverrides(energy = 1.6f, complexity = 0.5f, space = 0.6f, mood = 1.3f),
                customProgression = driveProgression,
                chordsPerBar = driveChordsPerBar,
                soloMode = SoloMode.LongFill(probability = 1f, barsMin = 2, barsMax = 2),
                // Both kettles roll under the coda; the gran cassa lands the downbeats.
                trackOverrides = mapOf(
                    0 to TrackSectionOverride(lpgMode = LpgMode.PLUCK_REPEAT, density = 0.6f),
                    1 to TrackSectionOverride(lpgMode = LpgMode.PLUCK_REPEAT, density = 0.3f),
                    7 to TrackSectionOverride(density = 0.15f, volume = 0.45f),
                ),
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
            // low Energy and electro at high Energy, not just the tone colors.
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.C,
            scaleType = ScaleType.MINOR,
            lick = fateMotif,
            // All 8 bank slots, one theme each; sections pin them via lickIndex. A full pool
            // leaves no slot for a LickAnomaly — Vibe.init would reject one.
            lickRotation = LickRotation(
                pool = listOf(
                    fateMotif, cascade, hornCall, secondSubject,
                    fragment, dwindle, oboeCadenza, codaHammer,
                ),
            ),
            bassLine = lowStrings,
            bassLineMutation = 0.15f, // same discipline as the lead: it is a quotation
            lickMutation = 0.12f, // low — the motif is obsessive/mechanical, not jazzy. Recognizability first.
            band = Band(
                members = listOf(
                    // alwaysActive is what MAKES the drummer solo-capable, not what prevents it:
                    // the engine's drum-lead gate (pulsar_band_solo.h) hands a span to the
                    // always_active member on a 12% roll at each handoff inside a LickBuilder
                    // section. creativity is that solo's complexity and does nothing otherwise.
                    BandMember("Timpanist", listOf(0, 1, 2), alwaysActive = true, loudness = 0.7f, creativity = 0.6f),
                    BandMember("Bassist", listOf(3), loudness = 0.75f, creativity = 0.4f),
                    BandMember("Fate", listOf(4), loudness = 0.9f, creativity = 0.35f),
                    BandMember("Strings", listOf(5, 6, 7), loudness = 0.5f, creativity = 0.6f),
                ),
                handoffMatrix = bandMatrix(
                    //            TIMP  BASS  FATE  STR
                    "Timpanist" to row(0.00f, 0.35f, 0.40f, 0.10f),
                    "Bassist" to row(0.25f, 0.00f, 0.45f, 0.15f),
                    "Fate" to row(0.15f, 0.30f, 0.00f, 0.35f),
                    "Strings" to row(0.10f, 0.25f, 0.45f, 0.00f),
                ),
                pullInMatrix = bandMatrix(
                    "Timpanist" to row(0.00f, 0.35f, 0.35f, 0.10f),
                    "Bassist" to row(0.25f, 0.00f, 0.50f, 0.15f),
                    "Fate" to row(0.20f, 0.40f, 0.00f, 0.30f),
                    "Strings" to row(0.15f, 0.25f, 0.40f, 0.00f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 4,
                // Short leads = more handoffs per section = more rolls of the drum-lead gate.
                // At 4-8 an 8-bar development saw one handoff at most; at 2-4 it sees two or three.
                barsPerLeadMin = 2, barsPerLeadMax = 4,
            ),
            energy = 0.55f,
            complexity = 0.45f,
            space = 0.45f,
            mood = 0.40f,
            deep = 0.35f,
            genre = GenreProfile(
                swingAmount = 0.0f, // straight — the motif is mechanical, not swung
                ghostProbability = 0.18f,
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
                // 0: Low timpani — the kit IS a pair of kettledrums, tuned as a timpanist
                // tunes them: this one follows the chord root (C under the tonic, G under the
                // dominant) via ROOT_ONLY, in the real instrument's range (D2..D3). On the BD
                // engine morph is decay length and harmonics is attack click (overdrive above
                // 0.5), so a long ring with a soft mallet is morph high, harmonics near zero.
                OrpheusEngine(
                    engineId = OrpheusEngineId.BD,
                    volume = 0.85f,
                    harmonics = 0.1f, pinHarmonics = true,
                    timbre = 0.3f, pinTimbre = true,
                    morph = 0.75f, pinMorph = true,
                    noteRangeLow = 38,
                    noteRangeHigh = 50,
                    reverbSend = 0.45f,
                    reverbBrightness = 0.25f,
                ).let { lowTimpani ->
                    TrackVoice(
                        engineEdm = lowTimpani,
                        engineSpace = lowTimpani,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = -0.15f,
                        density = 0.27f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = timpaniMacroMap,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 1: High timpani — the second kettle, a fifth or so above, on the modal engine
                // set to membrane (harmonics is material: low = drumhead, high = metal; morph is
                // damping). FIXED keeps it on scale tones without chasing the root, so the pair
                // give the tonic-and-dominant figure timpani actually play.
                OrpheusEngine(
                    engineId = OrpheusEngineId.MOD,
                    volume = 0.50f,
                    harmonics = 0.12f,
                    timbre = 0.30f,
                    morph = 0.55f,
                    noteRangeLow = 45,
                    noteRangeHigh = 57,
                    reverbSend = 0.40f,
                    reverbBrightness = 0.40f,
                ).let { highTimpani ->
                    TrackVoice(
                        engineEdm = highTimpani,
                        engineSpace = highTimpani,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED),
                        pan = 0.15f,
                        density = 0.20f, // answers the low drum; it does not double it
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = timpaniMacroMap,
                        barStrategy = BarStrategy.REPEAT, // rolls come from the section overrides, not FILL
                    )
                },
                // 2: Triangle — where the hat was. The modal engine at the metal end
                // (harmonics is material: this is the opposite corner from the membrane
                // kettles), bright and left to ring, struck high and always on the same note:
                // a triangle is not tuned, so the range is one pitch wide. All three tone
                // knobs are pinned, because timpaniMacroMap would otherwise pull harmonics
                // back to drumhead and Space would damp the ring.
                //
                // NOT CALL_RESPONSE: that strategy renders the section's lick onto the track
                // (genre note range, density ignored), which turns the triangle into a bell
                // doubling the motif. REPEAT keeps it a sparse, one-pitch tick.
                OrpheusEngine(
                    engineId = OrpheusEngineId.MOD,
                    volume = 0.14f,
                    harmonics = 0.88f, pinHarmonics = true,
                    timbre = 0.72f, pinTimbre = true,
                    morph = 0.62f, pinMorph = true,
                    noteRangeLow = 89,
                    noteRangeHigh = 89,
                    reverbSend = 0.30f,
                    reverbBrightness = 0.75f,
                ).let { triangle ->
                    TrackVoice(
                        engineEdm = triangle,
                        engineSpace = triangle,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED),
                        pan = 0.30f,
                        density = 0.15f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = timpaniMacroMap,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 3: Bass — electric bass playing the low strings' figure (Vibe.bassLine),
                // transposed onto each chord by FOLLOW. EDM = plucked filter bass (VCF, PLUCK
                // so each motif eighth speaks on its own); Space = upright orchestral double
                // bass (STR). This is the "orchestral <-> electro" crossfade, same idiom as
                // the lead below. (Not WSH: a wavefolder this low only adds mud.)
                OrpheusEngine(
                    engineId = OrpheusEngineId.VCF,
                    lpgMode = LpgMode.PLUCK,
                    volume = 0.78f,
                    noteRangeLow = 28,
                    noteRangeHigh = 48,
                    reverbBrightness = 0.30f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(
                            engineId = OrpheusEngineId.STR,
                            lpgMode = LpgMode.ENGINE_DEFAULT,
                            reverbBrightness = 0.45f,
                        ),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FOLLOW,
                            lickMode = LickMode.Fill,
                            lickSource = LickSource.BASS,
                        ),
                        pan = 0.00f,
                        density = 0.45f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 4: THE MOTIF — lead with bite. FIXED so it always plays the exact
                // degrees above regardless of the chord underneath (the whole point:
                // this line must never wander). EDM = the panel oscillator (OSC) tuned
                // near-pure, SUSTAINED so the long notes of the horn call and cadenza hold for
                // their written length; Space = DX3 "Brass 1" fanfare (idx 29, harmonics
                // centerpoint 0.888 — auto-pinned, see fm_patches.md). Energy crossfade = brass
                // fanfare at low Energy, clean synth lead at high Energy. (Not WSH or a VA
                // saw: both buzz on the upper-octave themes and smear every glided step.)
                //
                // The OSC tone is PINNED on all three knobs, because its whole point is to stay
                // pure: unpinned, Mood would walk timbre toward square, tension would sweep the
                // self-feedback, and the SPACE knob would sweep FM depth (morph IS FM index).
                // The custom moodTimbre window below therefore only reaches the brass side.
                OrpheusEngine(
                    engineId = OrpheusEngineId.OSC,
                    lpgMode = LpgMode.SUSTAINED,
                    harmonics = 0.05f,  // a trace of self-feedback: warmth, not grit (cap is 0.35)
                    pinHarmonics = true,
                    timbre = 0.12f,     // almost all triangle; square adds the odd-harmonic buzz
                    pinTimbre = true,
                    morph = 0.12f,      // FM depth: just enough edge for the motif's hits to speak
                    pinMorph = true,
                    fmRatio = 2f,       // octave modulator — integer, so it stays in tune
                    fmShape = 0.05f,    // near-sine modulator, fewest sidebands
                    volume = 0.65f,
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
                            lpgMode = LpgMode.ENGINE_DEFAULT,
                            // copy() carries the OSC pins and FM fields over; undo them so the
                            // brass keeps its Mood/tension movement. (DX harmonics re-pin itself.)
                            timbre = 0.5f, pinTimbre = false,
                            morph = 0.5f, pinMorph = false,
                            fmRatio = 0f, fmShape = 0f,
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
                            moodTimbre = MacroTarget(0.3f, 0.6f),
                        ),
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 5: Chordal comping — power-chord downbeats under the harmony,
                // so the band never turns into a solo pianist quoting the tune. EDM =
                // thick analog stab; Space = string ensemble.
                OrpheusEngine(
                    engineId = OrpheusEngineId.VA,
                    volume = 0.30f, // support, not a second lead: sits under the motif and the kettles
                    noteRangeLow = 43,
                    noteRangeHigh = 67,
                    reverbSend = 0.20f,
                ).let { comp ->
                    TrackVoice(
                        engineEdm = comp,
                        engineSpace = comp.copy(
                            engineId = OrpheusEngineId.ENS,
                            volume = 0.26f,
                            reverbSend = 0.5f,
                            reverbBrightness = 0.6f,
                        ),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(style = CompingStyle.ROCK_DOWNBEATS),
                        ),
                        pan = -0.20f,
                        density = 0.22f, // downbeats only; the timpani own the eighths
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 6: String pad — the orchestral backbone. Always strings, on both
                // sides, so there is a constant orchestral presence under the driving
                // synth foreground regardless of Energy.
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
                // 7: Bass drum wildcard — the orchestra's gran cassa, deeper than the timpani
                // and rarer: modal membrane set darker and longer than track 1, sparse accents
                // under the big hits. Sections that want a roll switch it to PLUCK_REPEAT.
                OrpheusEngine(
                    engineId = OrpheusEngineId.MOD,
                    volume = 0.30f,
                    harmonics = 0.08f,
                    timbre = 0.22f,
                    morph = 0.70f,
                    modLfoDepth = 0.0f,
                    holdProbability = 0.15f,
                    holdLengthMin = 2,
                    holdLengthMax = 4,
                    reverbSend = 0.5f,
                    noteRangeLow = 33,
                    noteRangeHigh = 45,
                    reverbBrightness = 0.35f,
                ).let { granCassa ->
                    TrackVoice(
                        engineEdm = granCassa,
                        engineSpace = granCassa,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED),
                        pan = 0.0f,
                        density = 0.05f, // one hit every bar or two: an accent, not a part
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
                spurtChance = 0.12f,
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
