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
import org.balch.orpheus.features.pulsar.models.LickStep
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
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Jupiter, the Bringer of Jollity — the broad central theme, reimagined as an anthemic
 * orchestral-rock ballad: a band swelling behind a soaring lead, not a synthesizer quoting
 * a hymn. A public-domain classical composition (Holst, d. 1934; "The Planets" published
 * 1921); see the vibe-creator skill's naming carve-out for compositions.
 *
 * Chosen as the second piece against the spec's own criteria: diatonic (Eb MAJOR), reduces
 * to a single monophonic line that still identifies the piece, and a deliberately different
 * tempo and character from the Fifth — broad and major instead of terse and minor, so the
 * pair exercises the conductor's follow clock across its range rather than one BPM twice.
 *
 * ## How the melody was verified
 * The theme is NOT transcribed from memory. Its scale-degree sequence is taken from the tune
 * incipit published by Hymnary.org's tune index for THAXTED (Holst) — "35617 51217 67653",
 * 1-indexed scale degrees, fetched directly from the page (not summarized) — which is the same
 * melody voiced as a unison hymn tune. Octave placement (which affects only whether a step
 * leaps or steps, not which of the 7 pitch classes it is) was reconstructed with nearest-tone
 * voice leading, the standard way to realize a bare scale-degree incipit, and cross-checked for
 * overall shape (a rise to a climax roughly two-thirds through the phrase, then a settle) against
 * a rendered piano arrangement (Holst, arr. E Muirhead, "Thaxted"/Jupiter Theme, DundeePiano.co.uk)
 * and against the tempo/range/key metadata independently corroborated by flutetunes.com and
 * musicnotes.com (Eb major, Eb4-G5, 3/4, Andante maestoso ~76 BPM). Key, meter, tempo, and the
 * pitch-CLASS sequence are verified against those references; the exact octave of each step and
 * all durations/velocities are this vibe's own reasonable realization, not independently
 * pixel-verified against a score. See the task report for the full derivation.
 *
 * Composition-only naming: "Jupiter" is Holst's own title for this movement of The Planets.
 * The hymn setting ("Thaxted", the poem "I Vow to Thee, My Country") is a separate, later
 * derivative work and is not named here or in the picker.
 *
 * "Orchestral rock" read: same engine-crossfade idiom as the Fifth — every voice that carries
 * the theme or its harmony leans orchestral at low Energy and band-rock at high Energy. Lead:
 * warm sustained strings (Space, DX3 "Full strin") <-> a soaring, legato lead with more glide
 * than the Fifth's terse motif (EDM, waveshaper). Comping: string ensemble (Space) <-> Hammond
 * organ chords (EDM) — the classic rock-ballad texture. A pipe-organ wildcard (track 7) is the
 * one deliberate, unnamed nod to the tune's hymn afterlife.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class JupiterVibe : VibeProvider {
    override val name: String = "Jupiter, the Bringer of Jollity"

    // THE THEME — scale-degree sequence verified against Hymnary.org's THAXTED (Holst)
    // tune incipit "35617 51217 67653" (1-indexed: 1=do..7=ti), converted to this
    // schema's zero-based scaleDegree and realized with nearest-tone voice leading
    // (each step moves to the closest octave of its target pitch class). Values >6
    // are octave-up per this schema's documented degree>=scale.count wrap (e.g. 7 =
    // root up an octave, 8 = 2nd degree up an octave) — NOT a different meaning.
    // Degrees in Eb MAJOR (Eb=0 F=1 G=2 Ab=3 Bb=4 C=5 D=6):
    //   mi sol la do' ti | sol do' re' do' ti | la ti la sol mi
    //    2   4  5   7  6 |  4   7   8   7  6 |  5  6  5   4  2
    // Rhythm/velocity are this vibe's own reasonable realization (not independently
    // verified) — broad "maestoso" values: mostly quarter/half-ish beats, swelling
    // toward the "re'" climax, easing into a long final landing note.
    private val jupiterTheme = Lick(
        steps = listOf(
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.72f), // mi
            LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.85f), // sol
            LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.75f), // la
            LickStep(scaleDegree = 7, duration = 1.5f, velocity = 0.90f), // do'
            LickStep(scaleDegree = 6, duration = 1.0f, velocity = 0.80f), // ti
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.78f), // sol
            LickStep(scaleDegree = 7, duration = 1.0f, velocity = 0.88f), // do'
            LickStep(scaleDegree = 8, duration = 0.5f, velocity = 0.95f), // re' — climax
            LickStep(scaleDegree = 7, duration = 1.0f, velocity = 0.85f), // do'
            LickStep(scaleDegree = 6, duration = 1.0f, velocity = 0.80f), // ti
            LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.75f), // la
            LickStep(scaleDegree = 6, duration = 0.5f, velocity = 0.80f), // ti
            LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.75f), // la
            LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.82f), // sol
            LickStep(scaleDegree = 2, duration = 2.5f, velocity = 0.92f), // mi — long landing
        ),
        // 13.5 beats of notes; loopLength=16 beats (64 steps) leaves a 2.5-beat
        // breath before the phrase repeats. stepCount below matches loopLength*4
        // exactly, same rule as the Fifth, so the theme renders as one clean
        // statement + rest per cycle, not a truncated or doubled one.
        loopLength = 16,
    )

    // Plagal-leaning progression: I-IV-I-IV-V-IV-I-I. Deliberately avoids the strong
    // dominant-to-tonic resolution most sources note this theme's own harmony avoids
    // (Holst treats it modally) — mostly I/IV "amen" motion, with V only glimpsed.
    private val mainProgression = chords(0, 3, 0, 3, 4, 3, 0, 0)
    private val mainChordsPerBar = 1

    // Climax harmony: I-V-IV-I — a little more dominant color for the biggest moments only.
    private val climaxProgression = chords(0, 4, 3, 0)
    private val climaxChordsPerBar = 2

    // Per-edge transitionBars, named for the role they serve (DogHouseVibe precedent):
    // unfurlBars is the standard lift into a bigger section; recedeBars is the pull-back
    // exhale; theBigSwellBars is THE moment — recede -> swell, recede locked to 4 bars so
    // the whole section is one long crawl from near-silence into the anthem.
    private val unfurlBars = 2
    private val recedeBars = 3
    private val theBigSwellBars = 4

    private val sectionList by lazy {
        listOf(
            // 0: hush — very quiet, spacious; strings and a bare statement of the theme.
            // hush -> verse: unfurl (the band gathers in).
            Section(
                name = "hush",
                barsMin = 2, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = unfurlBars),
                ),
                macroOverrides = MacroOverrides(energy = 0.25f, complexity = 0.25f, space = 0.85f, mood = 0.55f),
            ),
            // 1: verse — full band at baseline, theme carried by the lead.
            // verse -> swell / reprise: unfurl. verse -> recede: recede.
            Section(
                name = "verse",
                barsMin = 6, barsMax = 10,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.50f, transitionBars = unfurlBars),
                    SectionTransition(targetIndex = 3, weight = 0.30f, transitionBars = unfurlBars),
                    SectionTransition(targetIndex = 4, weight = 0.20f, transitionBars = recedeBars),
                ),
                recencyDecay = 0.5f,
                macroOverrides = null,
            ),
            // 2: swell — the anthem: full band, high energy, the theme soaring.
            // swell -> verse: unfurl (small step down). swell -> reprise / recede: recede.
            Section(
                name = "swell",
                barsMin = 4, barsMax = 8,
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 0.40f, transitionBars = unfurlBars),
                    SectionTransition(targetIndex = 3, weight = 0.30f, transitionBars = recedeBars),
                    SectionTransition(targetIndex = 4, weight = 0.30f, transitionBars = recedeBars),
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(energy = 1.35f, complexity = 1.10f, space = 0.90f, mood = 1.25f),
                customProgression = climaxProgression,
                chordsPerBar = climaxChordsPerBar,
            ),
            // 3: reprise — the band trades around the theme, looser than the Fifth's
            // development (Jam rather than LickBuilder — a freer, warmer feel).
            // reprise -> swell: unfurl. -> verse: unfurl. -> recede: recede.
            Section(
                name = "reprise",
                barsMin = 8, barsMax = 16,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.50f, transitionBars = unfurlBars),
                    SectionTransition(targetIndex = 1, weight = 0.30f, transitionBars = unfurlBars),
                    SectionTransition(targetIndex = 4, weight = 0.20f, transitionBars = recedeBars),
                ),
                recencyDecay = 0.4f,
                macroOverrides = MacroOverrides(energy = 0.85f, complexity = 1.20f, space = 1.30f, mood = 1.10f),
                soloMode = SoloMode.Jam(probability = 0.80f, lickInfluence = 0.60f),
            ),
            // 4: recede — pulls back before the final swell. Locked to 4 bars, and
            // dips the tempo slightly then ramps back to full over its last 2 bars,
            // winding up into the coda instead of cutting into it.
            // recede -> swell: THE moment (theBigSwellBars). recede -> verse: unfurl.
            Section(
                name = "recede",
                barsMin = 4, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 0.7f, transitionBars = theBigSwellBars),
                    SectionTransition(targetIndex = 1, weight = 0.3f, transitionBars = unfurlBars),
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(energy = 0.35f, complexity = 0.4f, space = 1.5f, mood = 0.8f),
                bpmMultiplier = 0.9f,
                bpmRampBars = 2,
            ),
            // 5: coda — final full-band statement of the theme, spotlighted, triumphant.
            Section(
                name = "coda",
                barsMin = 4, barsMax = 4,
                macroOverrides = MacroOverrides(energy = 1.5f, complexity = 0.6f, space = 0.7f, mood = 1.3f),
                customProgression = climaxProgression,
                chordsPerBar = climaxChordsPerBar,
                soloMode = SoloMode.LongFill(probability = 1f, barsMin = 2, barsMax = 2),
            ),
        )
    }

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            bpm = 76f, // verified: flutetunes.com + musicnotes.com, "Andante maestoso"
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = sectionList.lastIndex,
                sections = sectionList,
            ),
            // BLEND crossfades AD <-> TIDES with Energy, mirroring the per-track engine
            // crossfade: orchestral/sustained at low Energy, driving band at high Energy.
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.D_SHARP, // Eb — verified key (flutetunes.com, musicnotes.com)
            scaleType = ScaleType.MAJOR, // diatonic, per the spec's constraint
            lick = jupiterTheme,
            lickMutation = 0.25f, // more give than the Fifth's obsessive motif — a warmer, living band
            band = Band(
                members = listOf(
                    BandMember("Drummer", listOf(0, 1, 2), alwaysActive = true, loudness = 0.65f, creativity = 0.25f),
                    BandMember("Bassist", listOf(3), loudness = 0.70f, creativity = 0.40f),
                    BandMember("Herald", listOf(4), loudness = 0.85f, creativity = 0.40f),
                    BandMember("Choir", listOf(5, 6, 7), loudness = 0.55f, creativity = 0.55f),
                ),
                handoffMatrix = bandMatrix(
                    //            DRUM  BASS  HERALD CHOIR
                    "Drummer" to row(0.00f, 0.30f, 0.45f, 0.15f),
                    "Bassist" to row(0.20f, 0.00f, 0.50f, 0.20f),
                    "Herald" to row(0.10f, 0.30f, 0.00f, 0.45f),
                    "Choir" to row(0.10f, 0.20f, 0.50f, 0.00f),
                ),
                pullInMatrix = bandMatrix(
                    "Drummer" to row(0.00f, 0.30f, 0.35f, 0.20f),
                    "Bassist" to row(0.25f, 0.00f, 0.45f, 0.25f),
                    "Herald" to row(0.15f, 0.35f, 0.00f, 0.45f),
                    "Choir" to row(0.15f, 0.25f, 0.45f, 0.00f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 4,
                barsPerLeadMin = 4, barsPerLeadMax = 8,
            ),
            energy = 0.50f,
            complexity = 0.40f,
            space = 0.55f,
            mood = 0.60f,
            deep = 0.45f,
            genre = GenreProfile(
                swingAmount = 0.05f, // a touch of human lilt — warmer than the Fifth's dead-straight feel
                ghostProbability = 0.18f,
                noteRangeLow = 36,
                noteRangeHigh = 67,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.MODAL, // matches the theme's own avoidance of V-I resolution
                chordsPerBar = mainChordsPerBar,
                customProgression = mainProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_8, // matches the 8-chord progression length
            progressionDriftRange = 0.20f,
            tracks = listOf(
                // 0: Kick — broad rock pulse, less frantic than the Fifth.
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
                // 1: Snare — broad backbeat.
                OrpheusEngine(engineId = OrpheusEngineId.SD, volume = 0.65f).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.10f,
                        density = 0.35f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.FILL,
                    )
                },
                // 2: Hat — steady but not busy; broad, not driving-fast.
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.48f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f,
                        density = 0.45f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 3: Bass — electric bass carrying the root motion, snapped to the
                // chord root. EDM = electric (WSH, slight glide for a singing quality
                // under the sustained theme); Space = upright orchestral (STR).
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.72f,
                    noteRangeLow = 28,
                    noteRangeHigh = 48,
                    glideRate = 0.05f,
                    reverbBrightness = 0.35f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.STR, reverbBrightness = 0.5f),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.00f,
                        density = 0.40f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 4: HERALD — the theme, soaring. FIXED so the verified degree sequence
                // always plays as encoded, immune to chord-driven transposition/octave
                // fold. EDM = waveshaper lead with more glide than the Fifth's motif
                // (legato, singing); Space = DX3 "Full strin" (idx 27, harmonics
                // centerpoint 0.827 — auto-pinned, see fm_patches.md). Energy crossfade:
                // full string section at low Energy, soaring lead guitar at high Energy.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.82f,
                    noteRangeLow = 60,
                    noteRangeHigh = 79,
                    glideRate = 0.15f,
                    reverbBrightness = 0.55f,
                    delaySend = 0.20f,
                ).let { herald ->
                    TrackVoice(
                        engineEdm = herald,
                        engineSpace = herald.copy(
                            engineId = OrpheusEngineId.DX3,
                            harmonics = 0.827f, // DX3 idx 27 "Full strin" — auto-pinned
                            reverbBrightness = 0.65f,
                            reverbSend = 0.45f,
                            glideRate = 0.0f, // section swells, not slides
                        ),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FIXED,
                            lickMode = LickMode.Fill,
                        ),
                        pan = 0.05f,
                        density = 0.6f, // lick controls the rhythm; density is the fallback fill rate
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 5: Chordal comping — sustained, swelling chords rather than the
                // Fifth's rock downbeats, matching this piece's broader character.
                // EDM = Hammond organ chords (idx 1, harmonics 0.031 — auto-pinned,
                // the classic rock-ballad texture); Space = string ensemble.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX3,
                    volume = 0.34f,
                    harmonics = 0.031f, // DX3 idx 1 "Hammond" — auto-pinned
                    noteRangeLow = 48,
                    noteRangeHigh = 72,
                    reverbSend = 0.30f,
                ).let { comp ->
                    TrackVoice(
                        engineEdm = comp,
                        engineSpace = comp.copy(
                            engineId = OrpheusEngineId.ENS,
                            volume = 0.40f,
                            reverbSend = 0.55f,
                            reverbBrightness = 0.6f,
                        ),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.PAD,
                                sectionInversion = SectionInversion.OPEN_VOICING,
                            ),
                        ),
                        pan = -0.20f,
                        density = 0.25f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 6: String pad — orchestral backbone, always strings on both sides,
                // constant presence under the band regardless of Energy.
                OrpheusEngine(
                    engineId = OrpheusEngineId.STR,
                    volume = 0.34f,
                    modLfoRate = 0.07f,
                    modLfoDepth = 0.6f,
                    modLfoShape = 0.4f,
                    modLfoCoupling = 0.2f,
                    holdProbability = 0.82f,
                    holdLengthMin = 8,
                    holdLengthMax = 16,
                    reverbSend = 0.6f,
                    delaySend = 0.30f,
                    noteRangeLow = 43,
                    noteRangeHigh = 63,
                    reverbBrightness = 0.6f,
                    glideRate = 0.4f,
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
                // 7: Pipe-organ wildcard — the one deliberate, unnamed nod to this
                // tune's hymn afterlife. Sustained, cathedral-like, low in the mix.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX3,
                    volume = 0.22f,
                    harmonics = 0.153f, // DX3 idx 5 "Pipes 1" — auto-pinned
                    holdProbability = 0.6f,
                    holdLengthMin = 6,
                    holdLengthMax = 14,
                    reverbSend = 0.6f,
                    delaySend = 0.15f,
                    noteRangeLow = 48,
                    noteRangeHigh = 68,
                    reverbBrightness = 0.5f,
                ).let { pipes ->
                    TrackVoice(
                        engineEdm = pipes,
                        engineSpace = pipes,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
                        pan = -0.05f,
                        density = 0.10f,
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            // loopLength=16 beats * 4 steps/beat = 64 steps: one full theme cycle
            // (13.5 beats of notes + 2.5 beats rest) fits Vibe.stepCount exactly.
            stepCount = 64,
            tension = TensionProfile(
                innerBars = 8, // broader phrases than the Fifth's tight 4 — maestoso, not urgent
                outerBars = 16,
                outerDepth = 0.6f,
                spurtChance = 0.05f,
                volume = 0.30f,
                tonal = TonalTension(octaveShift = true, chromaticPassing = 0.05f), // stays clean/diatonic
                timing = 0.15f,
                evolution = EvolutionTension(
                    timbreLow = 0.30f, timbreHigh = 0.60f, timbreProbability = 0.6f,
                    attackPoint = 0.6f, releaseSpeed = 0.3f, // slower release — broader pacing
                ),
            ),
            effects = VibeEffects(
                delayTimeA = 0.25f,
                delayTimeB = 0.5f,
                delayFeedback = 0.30f,
                delayDamping = 0.35f,
                reverbSize = 0.65f,   // hall-sized — bigger than the Fifth, matching orchestral space
                reverbDamping = 0.45f,
                reverbBrightness = 0.60f, // warmer/brighter, matching the major key
            ),
        )
    }
}
