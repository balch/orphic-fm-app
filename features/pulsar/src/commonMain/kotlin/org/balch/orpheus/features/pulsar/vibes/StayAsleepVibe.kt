package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.FootstepPace
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
import org.balch.orpheus.features.pulsar.models.SectionStreet
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.SpeechCue
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TonalTension
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.VibeSpeech
import org.balch.orpheus.features.pulsar.models.chords

/**
 * Stay Asleep — a slow blues gesture on an empty street: one bass note held, falling.
 *
 * ## The feel
 * Two notes and a heartbeat. The bass holds the fourth, slides down to the root, and the
 * echo carries it away across four and a half beats of nothing. A single ringing kick
 * underneath. Everything else waits. The tension climbs the whole time and never quite
 * pays off before it starts again.
 *
 * ## The arc
 * The sections are places too: rain on the window as it wakes, an alley that echoes, a siren
 * in the chase that fades as it runs, rain again once awake.
 *
 * Six sections that finally pay the climb off: intro / verse / build / drop / breakdown /
 * outro. The band arrives, lands the drop, and leaves. One edge out of build goes back to
 * verse instead of into the drop — about one time in seven the build does NOT pay off,
 * which is the old intro-only character surviving as an event rather than the whole piece.
 *
 * Two things carry the arc that are not volume. The kick's morph is decay, so it is pinned
 * long and ringing at the edges and tight through the middle: the kit closes up when the
 * band arrives and rings out when it leaves. And the street (track 7) runs OPPOSITE
 * everyone else — loudest in the outro, quietest in the drop. The band drowns out the
 * street; when the band goes, the street is still there.
 *
 * The gesture itself never changes density. What changes is WHICH lick it plays: each
 * section pins a [LickRotation] pool slot via [Section.lickIndex], so the identity is
 * constant and only the sentence is different. A/B against DogHouseVibe.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class StayAsleepVibe : VibeProvider {
    override val name: String = "Stay Asleep"

    // Tonic pedal. The gesture is the only thing that moves.
    private val tonicProgression = chords(0)

    // E BLUES degrees: 0=E root, 1=G b3, 2=A 4, 3=Bb b5, 4=B 5, 5=D b7.
    //
    // E, up to A, back to E, up to G, back to E. 3 beats of notes; the expander clears
    // the rest of the pattern to rests, so what follows is the gap the echo lives in.
    //
    // glideRate is a TIME and the ear reads pitch from a note's ATTACK, so a glide has
    // to land inside the first few tens of ms or the note is heard at the pitch it left,
    // not the one it arrives at. 0.12 lands in ~85 ms; 0.28 takes ~170 ms, which is the
    // entire length of a .25f note here, and 0.38 (~260 ms) never arrives at all.
    //
    // So the slides live on the two LONG notes and the short returns to the root are
    // struck clean. Glide on every step is what turns this from a lick into one smear.
    private fun generateIntroLick(firstNoteHitProbability: Float) = Lick(
        steps = listOf(
            // The pickup is a maybe. At rest it lands about a third of the time; the
            // tension staircase lifts it toward certain as the section climbs, so the
            // phrase gathers itself over the 4 bars and re-arms on the self-edge. When
            // it does not land the A is struck clean instead of slid into, because
            // there is no note to glide from.
            LickStep(scaleDegree = 0, duration = .25f, velocity = 0.5f, hitProbability = firstNoteHitProbability),
            LickStep(scaleDegree = 2, duration = 2.0f, velocity = 0.95f, glideRate = .12f),
            LickStep(scaleDegree = 0, duration = .5f, velocity = 0.85f, glideRate = .3f),
        ),
        loopLength = 8,
    )

    private fun generateMainLick(
        firstNoteHitProbability: Float,
        secondNoteHitProbability: Float,
        thirdNoteHitProbability: Float,
        forthNoteHitProbability: Float,
    ) = Lick(
        steps = listOf(
            LickStep(scaleDegree = 0, duration = .25f, velocity = 0.5f, hitProbability = firstNoteHitProbability),
            LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.95f, glideRate = .12f,),
            LickStep(scaleDegree = 0, duration = .25f, velocity = 0.5f, hitProbability = secondNoteHitProbability),
            LickStep(scaleDegree = 1, duration = .5f, velocity = 0.95f, glideRate = .12f, hitProbability = thirdNoteHitProbability),
            LickStep(scaleDegree = 1, duration = .5f, velocity = 0.95f, glideRate = .42f, hitProbability = forthNoteHitProbability),
            LickStep(scaleDegree = 0, duration = .75f, velocity = 0.95f, glideRate = .32f),
        ),
        loopLength = 8,
    )

    // ── The lick pool: one slot per section ──────────────────────────────────────
    //
    // Section.lickIndex pins a slot, so slot ORDER IS SECTION ORDER — do not reshuffle
    // this list without moving the matching lickIndex values.
    //
    // Slot 0 is the tuned intro figure. Slots 1-5 are PLACEHOLDERS: each one currently
    // returns the intro figure so the whole arrangement is audible end to end, and each
    // carries the shape it is meant to become. Overwrite the bodies.
    //
    // Writing against this pool — the constraints that actually bite:
    //   - E BLUES is hexatonic (6 degrees): 0=E 1=G♭3 2=A4 3=B♭♭5 4=B5 5=D♭7. Degree 6
    //     is the root an OCTAVE UP (degrees wrap with an octave bump), which is how a
    //     figure changes register without touching lickOctave.
    //   - A NEGATIVE scaleDegree is a REST for its full duration. It is the only way to
    //     author silence INSIDE a figure — loopLength only pads the end — so a reply
    //     that has to enter late opens with one.
    //   - Durations are beats; the sequencer runs 4 steps/beat. loopLength = 8 fills one
    //     32-step cycle exactly, so sum the durations to <= 8 and the remainder is silence.
    //   - Glide is a TIME, and the ear reads pitch from the attack. At 80 BPM 0.12 lands
    //     in ~85 ms, 0.28 takes ~170 ms, 0.38 never arrives. Slides belong on long notes.
    //   - hitProbability resolves as p + (1-p) * tension. Tension is a per-cycle staircase that
    //     peaks at 0.64 here (innerBars 4, outerDepth 0.6) and snaps back to 0, so a 0.2 pickup
    //     climbs 20% -> 31% -> 48% -> 71% and starts over. A lost roll drops the whole note.
    private val lickPool = listOf(
        generateMainLick(.1f, .1f, .1f, .1f),
        generateMainLick(.2f, .2f, .2f, .3f),
        generateMainLick(.4f, .2f, .2f, .5f),
        generateMainLick(.5f, .5f, .5f, .6f),
        generateMainLick(.6f, .5f, .5f, .7f),
        generateMainLick(.7f, .5f, .5f, .8f),
    )

    private val replyLick = Lick(
        steps = listOf(
            LickStep(scaleDegree = -1, duration = 3.0f),   // rest: the call is still speaking
            // Maybes, so the reply changes shape; tension lifts both toward certain.
            LickStep(scaleDegree = 5, duration = .25f, velocity = 0.40f, hitProbability = 0.70f),
            LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.60f, glideRate = .15f, hitProbability = 0.85f),
            LickStep(scaleDegree = 5, duration = .25f, velocity = 0.80f, hitProbability = 0.70f),
            LickStep(scaleDegree = 0, duration = .25f, velocity = 0.60f, hitProbability = 0.70f),
            LickStep(scaleDegree = 4, duration = 1.5f, velocity = 0.80f, glideRate = .15f),
        ),
        loopLength = 8,
    )

    val sectionList by lazy {
        listOf(
            // Bar counts are LOOP-CYCLES, not real bars: at stepCount 32 one cycle is two
            // real bars, so barsMin = 4 is 8 bars of music.
            //
            // Track 3 (the gesture) is absent from every trackOverrides map on purpose. Its
            // density never changes — only its lickIndex does. A positive density override
            // would be a no-op on a LickMode.Fill track anyway (their generator takes no
            // density parameter); only 0 would do anything, and it would mute the vibe.

            // 0: intro — the gesture, the kick, and the street. The gap is everything.
            //    No macroOverrides: the vibe's own macros ARE the intro's, and a multiplier
            //    on top would just be a second place to look.
            Section(
                name = "wakeup",
                barsMin = 4, barsMax = 4,   // 4 loop-cycles = 8 real bars at stepCount 32
                lickIndex = 0,
                street = SectionStreet(footsteps = 0.50f, pace = FootstepPace.WALK, footstepEcho = 0.10f), // walking on the beat
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = 2),
                ),
                trackOverrides = mapOf(
                    // Morph is DECAY on the drum engines, so this is the long ringing kick.
                    // Pinning it here also keeps the tension evolution sweep off the kit and
                    // on the bass, which is the only voice whose tone should be moving.
                    0 to TrackSectionOverride(
                        volume = 0.82f,
                        morph = 0.85f,
                        density = 0.25f,
                        // The gap belongs to the bass echo. Enough send to place the
                        // kick in the same room, not enough to fill the four bars after it.
                        reverbSend = 0.55f,
                        delaySend = 0.20f,
                    ),
                    1 to TrackSectionOverride(density = 0f),   // snare waits
                    2 to TrackSectionOverride(density = 0f),   // hat waits
                    4 to TrackSectionOverride(density = 0f),   // nothing answers yet
                    5 to TrackSectionOverride(density = 0f),   // rim waits
                    6 to TrackSectionOverride(density = 0f),   // the siren waits
                    7 to TrackSectionOverride(volume = 0.45f), // the street, from bar one
                ),
            ),
            // 1: verse — the snare finds the backbeat and the reply enters. The hat stays
            //    out: holding it back is what leaves the build somewhere to go.
            Section(
                name = "street",
                barsMin = 4, barsMax = 6,
                lickIndex = 1,
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 1f, transitionBars = 2),
                ),
                trackOverrides = mapOf(
                    0 to TrackSectionOverride(volume = 0.84f, morph = 0.72f, density = 0.35f),
                    1 to TrackSectionOverride(density = 0.28f),
                    2 to TrackSectionOverride(density = 0f),
                    4 to TrackSectionOverride(density = 0.50f, volume = 0.58f),
                    5 to TrackSectionOverride(density = 0f),
                    6 to TrackSectionOverride(density = 0f),
                    7 to TrackSectionOverride(volume = 0.12f),
                ),
            ),
            // 2: build — hat and rim arrive, the kit tightens. The 0.15 edge back to verse
            //    is the build that does not pay off: rare enough to read as a withheld
            //    promise rather than a broken arrangement.
            Section(
                name = "alley",
                barsMin = 2, barsMax = 4,
                lickIndex = 2,
                macroOverrides = MacroOverrides(energy = 1.15f, complexity = 1.20f),
                transitions = listOf(
                    // transitionBars pre-rolls the drop's macros over the last 2 cycles of
                    // THIS section, so the lift arrives before the boundary does.
                    SectionTransition(targetIndex = 3, weight = 1f, transitionBars = 2),
                ),
                street = SectionStreet(footsteps = 0.55f, pace = FootstepPace.WALK, footstepEcho = 0.80f), // the alley echoes
                trackOverrides = mapOf(
                    0 to TrackSectionOverride(volume = 0.86f, morph = 0.55f, density = 0.50f),
                    1 to TrackSectionOverride(density = 0.40f),
                    2 to TrackSectionOverride(density = 0.44f),
                    // The reply starts picking triplets here, a build into the chase.
                    4 to TrackSectionOverride(
                        density = 0.70f, volume = 0.72f, lpgMode = LpgMode.PLUCK_REPEAT_8TH_OFF,
                    ),
                    5 to TrackSectionOverride(density = 0.35f),
                    6 to TrackSectionOverride(density = 0f),
                    7 to TrackSectionOverride(volume = 0.18f),
                ),
            ),
            // 3: drop — the payoff the intro kept withholding. The kick is at its tightest
            //    and the street at its quietest: the band is finally louder than the place.
            Section(
                name = "chase",
                barsMin = 4, barsMax = 4,
                lickIndex = 3,
                macroOverrides = MacroOverrides(energy = 1.35f, complexity = 1.30f),
                transitions = listOf(
                    SectionTransition(targetIndex = 4, weight = 1f, transitionBars = 2),
                ),
                street = SectionStreet(footsteps = 0.60f, pace = FootstepPace.RUN, footstepEcho = 0.20f), // running
                trackOverrides = mapOf(
                    0 to TrackSectionOverride(volume = 0.90f, morph = 0.60f, density = 0.60f),
                    1 to TrackSectionOverride(density = 0.50f),
                    2 to TrackSectionOverride(density = 0.60f),
                    // The gesture stops holding and starts driving: the off-8th grid
                    // re-picks the held note on every "&", so the hook pulses under the run.
                    3 to TrackSectionOverride(lpgMode = LpgMode.PLUCK_REPEAT_8TH_OFF),
                    4 to TrackSectionOverride(density = 0.90f, volume = 0.85f),
                    5 to TrackSectionOverride(density = 0.50f),
                    // The wail, under the kit. Density 1 starts the hold on step 0, so every
                    // cycle is the same two wails. Breathe sinks it from the second cycle:
                    // 6 over this section's 4 cycles is 1.0 / 0.75 / 0.25 / 0, and never rises.
                    6 to TrackSectionOverride(
                        density = 1f, morph = 1.0f, reverbSend = 0.35f,
                        breatheBars = 6, breatheFloor = 0f,
                    ),
                    7 to TrackSectionOverride(volume = 0.10f),
                ),
            ),
            // 4: breakdown — everything falls away and the bed is exposed. The kick's long
            //    ring comes back, and so does the street.
            Section(
                name = "escape",
                barsMin = 2, barsMax = 4,
                lickIndex = 4,
                macroOverrides = MacroOverrides(energy = 0.55f, space = 1.40f),
                street = SectionStreet(train = .5f, footsteps = .2f),
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 0.60f, transitionBars = 2),
                    SectionTransition(targetIndex = 2, weight = 0.40f, transitionBars = 2),
                ),
                trackOverrides = mapOf(
                    0 to TrackSectionOverride(volume = 0.80f, morph = 0.88f, density = 0.25f),
                    1 to TrackSectionOverride(volume = 0.50f ,morph = 0.68f, density = 0.1f),
                    2 to TrackSectionOverride(volume = 0.50f, morph = 0.18f, density = 0.2f),
                    4 to TrackSectionOverride(density = .1f, volume = 0.05f),
                    5 to TrackSectionOverride(density = 0.15f),
                    6 to TrackSectionOverride(density = 0f),
                    7 to TrackSectionOverride(volume = 0.25f),
                ),
            ),
            // 5: outro — terminal, so it has no transitions; the auto-end machinery inside
            //    Arrangement.lengthSeconds is what routes here. One last reply, and the
            //    street outlasts the song.
            Section(
                name = "awake",
                barsMin = 2, barsMax = 2,
                lickIndex = 5,
                // The line, said once: this section is exactly two loops, so the setup takes the
                // first and the punchline the second, ending on the last downbeat of the song.
                speech = listOf(
                    SpeechCue(phrase = 0, everyLoops = 2, loopPhase = 0, level = 0.45f),
                    SpeechCue(phrase = 1, everyLoops = 2, loopPhase = 1, level = 0.45f),
                ),
                macroOverrides = MacroOverrides(energy = 0.50f, space = 1.30f),
                trackOverrides = mapOf(
                    0 to TrackSectionOverride(volume = 0.80f, morph = 0.78f, density = 0.25f),
                    1 to TrackSectionOverride(volume = 0.40f ,morph = 0.78f, density = 0.1f),
                    2 to TrackSectionOverride(density = 0f),
                    4 to TrackSectionOverride(density = 0.25f, volume = 0.45f),
                    5 to TrackSectionOverride(density = 0f),
                    // Density 0, not volume 0: density mutes on the boundary sample, while a
                    // volume override lands up to 200 ms late and lets the wail blip through.
                    6 to TrackSectionOverride(density = 0f),
                    7 to TrackSectionOverride(volume = 0.30f),
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
                outroIndex = 5,          // terminal: the pass ends here and hands off
                lengthSeconds = 240..480,
                sections = sectionList,
                // Told once, in order: wakeup to awake, then the next vibe. The random edges above
                // still shape each crossfade, but the walk no longer wanders.
                playOnce = true,
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
            // With lickRotation set, the pool is what the gesture actually plays and this
            // is the load-time fallback seed. Keep the two in sync: it is slot 0.
            lick = lickPool.firstOrNull(),
            // Growth the Complexity knob adds survives the figure swap each section makes.
            lickRotation = LickRotation(pool = lickPool, carryGrowth = true),
            lickMutation = 0.05f,   // near-static: a rare stray note, never a new figure
            lickOctave = -1,        // auto = midpoint of the bass range, lands around E2
            // Setup and punchline. Each lands in the gap after the figure and ends on the kick.
            speech = VibeSpeech(
                phrases = listOf(
                    "I'm here to chew bubblegum, and..",
                    "I'm all out of bubblegum",
                ),
                // Irish female, stepping down to British then American female where Moira is
                // not installed. The fallbacks live in VoiceAliases.
                voice = "Moira",
            ),
            // The answer voice's figure (track 4). One figure for the whole song — the
            // rotation pool is lead-only — so its variety has to come from the section grid.
            bassLine = replyLick,
            bassLineMutation = 0.10f,  // a little looser than the gesture; it is the reply
            bassLineOctave = -1,       // auto = midpoint of track 4's range, around E3
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
            progressionAnchor = ProgressionAnchor.EVERY_16,
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
                // is a slide. The delay send IS the echo.
                //
                // OSC is the panel's own tri+square oscillator and bypasses OrpheusVoice,
                // so the vactrol below is the note's whole shape. Pulsar's AD envelope holds
                // full level for the entire gate, which on its own reads as an organ.
                // lpgMode PLUCK is what makes this a struck string instead.
                //
                // harmonics/timbre/morph are deliberately NOT authored. On OSC all three are
                // macro- and evolution-driven on this track, so a static would be a value the
                // gate never lets through. The ranges below are where they really live.
                OrpheusEngine(
                    engineId = OrpheusEngineId.OSC,
                    volume = 0.90f,
                    // The pluck. PLUCK blooms on note-on then decays regardless of how long
                    // the gate is held, so both notes get an attack and a ring.
                    // lpgDecay is ring LENGTH: 0.2 is a short thud, 0.8 nearly sustains.
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.60f,        // long enough that the slide lands while it still sounds
                    lpgColour = 0.35f,        // dark tail, bite only on the attack
                    // Modulator an octave DOWN: a sub-octave ratio rounds the tone rather than
                    // adding bite. 1f is fatter and buzzier, 1.5f goes clangy.
                    fmRatio = .5f,
                    fmShape = 0.42f,          // between sine and triangle: a few sidebands, still soft
                    // E2. Also OSC's playability floor (kOscModRange.note_min = 40) — below it
                    // the render clamp folds notes UP an octave, so a stray mutated note would
                    // jump register. The auto lick octave still centers on E2, so the two
                    // authored notes are unchanged: E2 = 40, A2 = 45.
                    noteRangeLow = 40,
                    noteRangeHigh = 52,       // E3
                    reverbSend = 0.12f,
                    reverbBrightness = 0.30f,
                    delaySend = 0.20f,
                    glideRate = .2f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass,
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FOLLOW,
                            lickMode = LickMode.Fill,
                        ),
                        pan = 0.00f,
                        density = 0.95f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC.copy(
                            // Density is rolled per gated step. Pinning it near 1 means the two
                            // notes ALWAYS fire: the sparseness is authored, not rolled.
                            energyDensity = MacroTarget(0.92f, 1.0f),
                            // Harmonics is self-feedback grit on OSC and the render clamps it
                            // at kOscModRange.harmonics_max = 0.35. MELODIC's stock 0.3-0.7
                            // would sit pinned at max feedback with most of the Mood knob dead;
                            // this keeps the default around 0.15 and gives the knob real travel.
                            moodHarmonics = MacroTarget(0.05f, 0.30f),
                            // Complexity is the growth knob: 0 plays the written figure, 1 ghosts
                            // ~8% of empty steps and drifts ~10% of notes per loop-cycle.
                            complexityVariation = MacroTarget(0f, 1f),
                        ),
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 4 — THE ANSWER. An electric piano replying into the gap the bass
                // echo currently owns alone. It is the one track on the BASS channel:
                // lickSource = BASS renders Vibe.bassLine instead of the rotation pool, so
                // the gesture keeps all six pool slots to itself.
                //
                // That means the reply figure does NOT rotate per section — the pool is
                // lead-only. Its arc comes from the section grid instead: silent in the
                // intro and breakdown, sparse in the verse, insistent in the drop.
                //
                // Sitting a fifth above the gesture (E3-E4 against the bass's E2-E3) is what
                // keeps it an answer rather than a thickening.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX2,
                    volume = 0.78f,
                    // DX2 idx 1 "Fender 1", a Rhodes. The patch carries its own FM envelope,
                    // so the vactrol only follows the gate and a held note sustains. (STR was
                    // silent on this lick line even at full volume.)
                    harmonics = 0.046f,
                    lpgMode = LpgMode.SUSTAINED,
                    noteRangeLow = 52,       // E3
                    noteRangeHigh = 64,      // E4
                    reverbSend = 0.22f,      // less wash, so the reply reads as a line
                    reverbBrightness = 0.40f,
                    delaySend = 0.28f,       // it answers, then the room answers it
                    glideRate = 0f,          // only steps that ask for a slide get one
                ).let { answer ->
                    TrackVoice(
                        engineEdm = answer,
                        engineSpace = answer,
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FOLLOW,
                            lickMode = LickMode.Fill,
                            lickSource = LickSource.BASS,
                        ),
                        pan = 0.18f,         // off-axis from the bass, which sits centre
                        density = 0.50f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        // Complexity growth at twice the bass's rate: ghosts, drift and per-hit
                        // velocity life all scale with this, so the reply keeps moving.
                        macroMap = TrackMacroMap.MELODIC.copy(complexityVariation = MacroTarget(0f, 2f)),
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 5 — Rim. The backbeat marker that arrives with the build. An SD with
                // the decay pinned short reads as a rim rather than a second snare, and it
                // keeps the kit to one drum engine family.
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD,
                    volume = 0.34f,
                    harmonics = 0.70f,       // tighter, higher crack than track 1's snare
                    reverbSend = 0.18f,
                ).let { rim ->
                    TrackVoice(
                        engineEdm = rim,
                        engineSpace = rim,
                        role = TrackRole.Percussive,
                        pan = -0.22f,        // opposite the answer voice
                        density = 0.35f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        // Morph is decay on SD. Locking it short is what separates this from
                        // the snare; min == max still leaves tension evolution free to move it.
                        macroMap = TrackMacroMap.RHYTHM.copy(
                            spaceDecay = MacroTarget(0.18f, 0.18f),
                        ),
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 6 — THE SIREN. OSC in free-run FM is a real frequency sweep of
                // morph × 200 Hz at fmFreeHz, so one held note wails. Holds only exist on
                // tracks 5-7, and the OSC gate stays high through a hold, so a wail spans it.
                //
                // Every note-on resets the sweep to phase 0, so the hold has to outlast the
                // wail or each note is a cut-off partial sweep. One 32-step hold fills the
                // 6 s cycle, and 1/3 Hz is exactly two wails in it, so the retrigger at the
                // loop point lands where the sweep already is. Retune fmFreeHz with the BPM.
                OrpheusEngine(
                    engineId = OrpheusEngineId.OSC,
                    // The chase's level lives here, not in a section override: volume
                    // overrides land late, and the base is what plays until they do.
                    volume = 0.10f,
                    fmFreeHz = 1f / 3f,       // two wails per 32-step cycle at 80 BPM
                    fmShape = 0f,             // sine sweep
                    morph = 1f,               // full ±200 Hz; sections set their own depth
                    pinMorph = true,
                    timbre = 0.35f,           // mostly triangle, a little edge
                    pinTimbre = true,
                    modLfoDepth = 0f,
                    holdProbability = 1f,
                    holdLengthMin = 32,       // the whole cycle; the generator clips to stepCount
                    holdLengthMax = 32,
                    // A full octave on purpose: the effect generator folds notes badly in
                    // narrower windows (pulsar_pattern_gen.h:516-517).
                    noteRangeLow = 72,
                    noteRangeHigh = 84,
                    lpgMode = LpgMode.BYPASS,
                    reverbSend = 0.35f,
                    reverbBrightness = 0.40f,
                    delaySend = 0.10f,
                ).let { siren ->
                    TrackVoice(
                        engineEdm = siren,
                        engineSpace = siren,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED),
                        pan = -0.20f,
                        density = 0f,         // out unless a section brings it in
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.MELODIC.copy(
                            // The cycle is one note, so a lost fire roll drops a whole cycle.
                            energyDensity = MacroTarget(1f, 1f),
                            moodHarmonics = MacroTarget(0.05f, 0.05f),  // no self-feedback grit
                            moodTimbre = MacroTarget(0f, 0f),           // opts out of tension evolution
                            complexityVariation = MacroTarget(0f, 0f),  // no growth: effect rests are note 0
                        ),
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 7 — THE STREET. Filtered noise held long enough to read as room tone
                // rather than as a part: wind down an empty street. It is the cheapest way to
                // make the gap sound like a PLACE instead of an absence.
                //
                // This is the one track that ignores the song. moodTimbre = (0,0) zeroes the
                // auto evolution weight, so the tension staircase never reaches it — the
                // street does not get tense because the band does. Its only arc is the
                // section volumes, which run opposite everyone else's.
                OrpheusEngine(
                    engineId = OrpheusEngineId.NSE,
                    volume = 0.22f,
                    harmonics = 0.20f,       // dark, most of the noise filtered away
                    timbre = 0.30f,
                    holdProbability = 0.95f,
                    holdLengthMin = 8,
                    holdLengthMax = 16,      // effectively continuous
                    noteRangeLow = 36,
                    noteRangeHigh = 48,
                    reverbSend = 0.55f,
                    reverbBrightness = 0.25f,
                    modLfoRate = 0.02f,      // slower than the bed: weather, not vibrato
                    modLfoDepth = 0.40f,
                    modLfoShape = 0.0f,
                    modLfoCoupling = 0.2f,
                ).let { street ->
                    TrackVoice(
                        engineEdm = street,
                        engineSpace = street,
                        // FIXED keeps it off the progression — room tone has no harmony.
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED),
                        pan = 0.00f,
                        density = 0.30f,
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.EFFECT.copy(
                            moodTimbre = MacroTarget(0f, 0f),  // opts out of tension evolution
                        ),
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
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
                    // These REPLACE the macro value on most steps, so they set the tone at the
                    // top of every climb. On the OSC bass timbre is the triangle-to-square blend:
                    // capped at 0.45 it opens a little under tension instead of squaring off (0.80 did).
                    timbreLow = 0.20f, timbreHigh = 0.45f, timbreProbability = 0.95f,
                    // Morph is the FM index, morph * 8 radians: 0.10-0.38 stays short of clangy.
                    morphLow = 0.10f, morphHigh = 0.38f, morphProbability = 0.90f,
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
}
