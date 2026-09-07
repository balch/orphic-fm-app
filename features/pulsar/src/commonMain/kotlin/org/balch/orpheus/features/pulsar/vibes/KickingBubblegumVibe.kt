package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.Arrangement
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
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.ProgressionAnchor
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionInversion
import org.balch.orpheus.features.pulsar.models.SectionTransition
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
 * Kicking Bubblegum — a slow blues gesture on an empty street: one bass note held, falling.
 *
 * ## The feel
 * Two notes and a heartbeat. The bass holds the fourth, slides down to the root, and the
 * echo carries it away across four and a half beats of nothing. A single ringing kick
 * underneath. Everything else waits. The tension climbs the whole time and never quite
 * pays off before it starts again.
 *
 * ## Where this is
 * INTRO ONLY, deliberately. One section that loops back into itself so the gesture can be
 * tuned by ear on repeat. Snare and hat are declared and section-muted, ready for the next
 * section; tracks 4, 5 and 7 are silent placeholders. Sections get added one at a time from
 * here. A/B against DogHouseVibe.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class KickingBubblegumVibe : VibeProvider {
    override val name: String = "Kicking Bubblegum"

    // Tonic pedal. The gesture is the only thing that moves.
    private val tonicProgression = chords(0)

    // E BLUES degrees: 0=E root, 1=G b3, 2=A 4, 3=Bb b5, 4=B 5, 5=D b7.
    //
    // Two notes, then silence. loopLength 8 = 2 bars, and the expander clears everything
    // past the notes to rests, so the 4.5 beats after the E are the gap the echo lives in.
    // Nothing is authored there, which means mutation can never fill it.
    //
    // Four numbers to move by ear: how long A holds, how long E rings, how fast it falls,
    // and how much silence follows (loopLength).
    private val introLick = Lick(
        steps = listOf(
            LickStep(scaleDegree = 2, duration = 2.0f, velocity = 0.95f),
            LickStep(scaleDegree = 0, duration = 1.5f, velocity = 0.85f, glideRate = 0.35f),
        ),
        loopLength = 8,
    )

    val sectionList by lazy {
        listOf(
            // 0: intro — the gesture, the kick, and the climb. The self-edge re-enters this
            //    same section, so the tension staircase re-arms every 8 real bars: wind up,
            //    flip, wind up. No macroOverrides — with one section the vibe's own macros
            //    ARE the intro's, and a multiplier on top would just be a second place to look.
            Section(
                name = "intro",
                barsMin = 4, barsMax = 4,   // 4 loop-cycles = 8 real bars at stepCount 32
                transitions = listOf(
                    SectionTransition(targetIndex = 0, weight = 1.0f),
                ),
                trackOverrides = mapOf(
                    // Morph is DECAY on the drum engines, so this is the long ringing kick.
                    // Pinning it here also keeps the tension evolution sweep off the kit and
                    // on the bass, which is the only voice whose tone should be moving.
                    0 to TrackSectionOverride(volume = 0.82f, morph = 0.85f, density = 0.25f),
                    1 to TrackSectionOverride(density = 0f),   // snare waits
                    2 to TrackSectionOverride(density = 0f),   // hat waits
                    6 to TrackSectionOverride(volume = 0.12f), // the bed is a rumor
                ),
            ),
        )
    }

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            album = Album.RIF,
            bpm = 80f,
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = null,       // loops while the intro is being tuned
                lengthSeconds = 300..600,
                sections = sectionList,
            ),
            // AD is a 4 ms attack, full level while the gate is high, then a Space-set
            // release: it holds the long A and hits every note. BLEND would drop into the
            // slow TIDES slope at this energy, and that softness reads as an extra slide.
            envelopeType = EnvelopeType.AD,
            rootNote = RootNote.E,
            scaleType = ScaleType.BLUES,
            seed = 0,
            // --- macro defaults: slow, dark, the echo audible ---
            energy = 0.50f,
            complexity = 0.30f,
            space = 0.45f,
            mood = 0.40f,
            deep = 0.50f,
            lick = introLick,
            lickMutation = 0.05f,   // near-static: a rare stray note, never a new figure
            lickOctave = -1,        // auto = midpoint of the bass range, lands around E2
            genre = GenreProfile(
                swingAmount = 0.30f,          // heavy shuffle, behind the beat
                ghostProbability = 0.12f,
                noteRangeLow = 28,            // E1
                noteRangeHigh = 64,           // E4
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.DRONE,
                chordsPerBar = 1,
                customProgression = tonicProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_8,
            progressionDriftRange = 0.05f,  // planted
            tracks = listOf(
                // Track 0 — Kick (BD): the heartbeat. Long decay set per section.
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.86f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.25f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 1 — Snare (SD): declared, muted by the intro. Waits for a section.
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD,
                    volume = 0.60f,
                    reverbSend = 0.08f,
                ).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.05f,
                        density = 0.28f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 2 — Hat (HH): declared, muted by the intro. Waits for a section.
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.40f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.12f,
                        density = 0.44f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 3 — THE GESTURE. glideRate 0 on the voice means only the step that
                // asks for a slide gets one, so the A always lands dead-on and just the fall
                // is a slide. SUSTAINED follows the gate, so the A holds its full two beats.
                // The delay send IS the echo.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX,
                    volume = 0.90f,
                    // DX bank 0 is the bass bank. idx 1 "Mooger Low": a Moog-style low, which
                    // is the voice portamento was invented on. Centerpoint is (idx + 0.5) / 32.64
                    // — NOT the bank table's "Harm" column, which lists lower EDGES and resolves
                    // one patch low. DX auto-pins harmonics, so this is a hard patch select.
                    // Swaps: 0.015f Solid bass · 0.260f S.Bas 27.7 · 0.322f Syn-bass 2.
                    harmonics = 0.046f,
                    noteRangeLow = 28,        // E1
                    noteRangeHigh = 52,       // E3, auto lick octave centers on E2
                    reverbSend = 0.12f,
                    reverbBrightness = 0.30f,
                    delaySend = 0.40f,
                    glideRate = 0f,
                    lpgMode = LpgMode.SUSTAINED,
                    lpgDecay = 0.35f,
                    lpgColour = 0.40f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass,
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.ROOT_ONLY,
                            lickMode = LickMode.Fill,
                        ),
                        pan = 0.00f,
                        density = 0.95f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        // Density is rolled per gated step. Pinning it near 1 means the two
                        // notes ALWAYS fire: the sparseness is authored, not rolled.
                        macroMap = TrackMacroMap.MELODIC.copy(
                            energyDensity = MacroTarget(0.92f, 1.0f),
                        ),
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 4 — reserved. Silent.
                silentPlaceholder(),
                // Track 5 — reserved. Silent.
                silentPlaceholder(),
                // Track 6 — String-machine bed (ENS): low sustained root tones, the dark
                // behind the gesture. The cheapest source of dread; first thing to cut if
                // it muddies the gap.
                OrpheusEngine(
                    engineId = OrpheusEngineId.ENS,
                    volume = 0.30f,
                    holdProbability = 0.85f,
                    holdLengthMin = 4,
                    holdLengthMax = 12,
                    noteRangeLow = 40,        // E2
                    noteRangeHigh = 64,       // E4
                    reverbSend = 0.45f,
                    delaySend = 0.05f,
                    reverbBrightness = 0.35f,
                    modLfoRate = 0.03f,
                    modLfoDepth = 0.35f,
                    modLfoShape = 0.1f,
                    modLfoCoupling = 0.3f,
                ).let { bed ->
                    TrackVoice(
                        engineEdm = bed,
                        engineSpace = bed,
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.PAD,
                                arpMode = ArpMode.NEVER,  // root-tone bed, no ripple
                                sectionInversion = SectionInversion.ROOT_POSITION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.10f,
                                    ghostProbability = 0.05f,
                                    octaveJumpProbability = 0.05f,
                                    extensionProbability = 0.05f,
                                ),
                            ),
                        ),
                        pan = -0.20f,
                        density = 0.18f,
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 7 — reserved. Silent.
                silentPlaceholder(),
            ),
            stepCount = 32,  // 2 bars / 8 beats: the gesture is one LickMode.Fill phrase
            // THE CLIMB. outerBars == innerBars is a CURVE control rather than a long arc:
            // both phases share one p, so outerDepth becomes a linear-to-quadratic knob.
            // 0.60 hangs the walk-up back and rushes the last bar, paid for in peak height.
            // The kick's morph is pinned per section, so the evolution sweep lands on the
            // bass alone: the tone opens as the climb goes up.
            tension = TensionProfile(
                innerBars = 4,       // one staircase per visit
                outerBars = 4,       // == innerBars: curves the walk-up, see above
                outerDepth = 0.60f,  // 0 = linear, 1 = pure quadratic
                volume = 0.90f,      // the crescendo
                tonal = TonalTension(),  // no octave pop, no passing tones: two notes stay two notes
                timing = 0f,             // gate lengths never loosen; loosening reads as slide
                evolution = EvolutionTension(
                    timbreLow = 0.20f, timbreHigh = 0.80f, timbreProbability = 0.95f,
                    morphLow = 0.20f, morphHigh = 0.75f, morphProbability = 0.90f,
                    // A knee on the tone: evolution is (intensity - attackPoint) / (1 - attackPoint),
                    // so the timbre barely stirs early and opens late.
                    attackPoint = 0.12f,
                    releaseSpeed = 0.25f,
                ),
                spurtChance = 0f,
            ),
            // Both delays map 0..1 to 0.01..2.0 SECONDS (not bars).
            effects = VibeEffects(
                delayTimeA = 0.375f,      // 0.76 s, one beat at 80 BPM: the echo after the fall
                delayTimeB = 0.5f,        // 1.0 s
                delayFeedback = 0.50f,    // four or five repeats across the gap
                delayDamping = 0.60f,     // routed but unread by the Pulsar delay today
                reverbSize = 0.60f,       // a wide empty street
                reverbDamping = 0.50f,
                reverbBrightness = 0.35f,
                deepFloor = 0.30f,        // the echo survives Space at zero
            ),
        )
    }

    /** A silent track slot: zero volume on both engines, zero density, so it renders nothing. */
    private fun silentPlaceholder(): TrackVoice =
        OrpheusEngine(engineId = OrpheusEngineId.NSE, volume = 0f).let { silent ->
            TrackVoice(
                engineEdm = silent,
                engineSpace = silent,
                role = TrackRole.Percussive,
                density = 0f,
                envelopeProfile = EnvelopeProfile.EFFECT,
                macroMap = TrackMacroMap.EFFECT,
                barStrategy = BarStrategy.REPEAT,
            )
        }
}
