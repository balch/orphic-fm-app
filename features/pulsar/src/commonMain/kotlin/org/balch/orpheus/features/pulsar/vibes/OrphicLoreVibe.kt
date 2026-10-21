package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingFills
import org.balch.orpheus.features.pulsar.models.CompingHumanization
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.DuckingProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.FillType
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.GrainMode
import org.balch.orpheus.features.pulsar.models.GrainScatterMode
import org.balch.orpheus.features.pulsar.models.GrainVoicing
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
import org.balch.orpheus.features.pulsar.models.SectionWeather
import org.balch.orpheus.features.pulsar.models.SoloMode
import org.balch.orpheus.features.pulsar.models.StrikeEffect
import org.balch.orpheus.features.pulsar.models.TensionArc
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeName
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.VolumePoint
import org.balch.orpheus.features.pulsar.models.WahParams
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Orphic Lore: slow psychedelic lament for a heavy quartet, wah guitar and Hammond over a
 * walking bass, with the Sirens above and a ship's bell in the wind.
 *
 * The bass opens on a sliding D-C-Bb-A descent, then plays a funky lick per section that
 * the wah lead doubles two octaves up while the organ comps the chords; all come
 * home to the descent in the homecoming and return sections (each is two loops). The Sirens
 * sing a third above the descent as a grain choir; the ship's bell rings in pairs in the
 * intro, the storm and the outro, smeared by the wind only in the storm. The sections
 * follow the Odyssey in order: libation (intro) -> sail (verse) -> winds (rise) ->
 * shades (homecoming) -> sirens (Dm vamp) -> storm (free-improv peak) -> ithaca (return)
 * -> ashore (outro); the jam is the centerpiece.
 *
 * Per-track wah voicing: the bass rocks a
 * slow pedal low in the spectrum while the lead works a quarter-note pedal up in the
 * vowel range. The rise, vamp and peak are Jam solos over each section's hook.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class OrphicLoreVibe : VibeProvider {
    override val name: VibeName = VibeNames.ORPHIC_LORE

    // The intro's walkdown: four descending anchors (D C Bb A), one per bar, each on the
    // downbeat, then a 16th breath and a 16th pickup that slurs into the next. glideRate lives
    // on the anchor: the engine slides INTO the step carrying the rate, and a rest right before
    // it would cancel the glide, so the breath sits before the pickup, never before an anchor.
    // Pool slot 0 (intro, homecomings, outro).
    private val descent = Lick(
        steps = listOf(
            LickStep(7, 3.5f, 0.95f, glideRate = 0.5f),   // D
            LickStep(-1, 0.25f, 0.45f),                     //   breath
            LickStep(7, 0.25f, 0.45f),                      //   pickup, lifts into C
            LickStep(6, 3.5f, 0.90f, glideRate = 0.5f),   // C
            LickStep(-1, 0.25f, 0.45f),                     //   breath
            LickStep(6, 0.25f, 0.45f),                      //   pickup, into Bb
            LickStep(5, 3.5f, 0.90f, glideRate = 0.5f),   // Bb
            LickStep(-1, 0.25f, 0.45f),                     //   breath
            LickStep(5, 0.25f, 0.45f),                      //   pickup, into A
            LickStep(4, 3.5f, 0.95f, glideRate = 0.5f),   // A, holds the bar
            LickStep(-1, 0.25f, 0.45f),                     //   breath
            LickStep(4, 0.25f, 0.45f),                      //   pickup, back to D
        ),
        loopLength = 16,  // 16 beats = the full 64-step pattern, no padding
    )

    // The Sirens' descent: the same anchors held a full bar each, without the bass's pickups,
    // which re-struck each sung note a 16th before the next. Vibe.bassLine, which only the
    // Sirens read.
    private val sirensDescent = Lick(
        steps = listOf(
            LickStep(7, 4f, 0.95f, glideRate = 0.5f),   // D
            LickStep(6, 4f, 0.90f, glideRate = 0.5f),   // C
            LickStep(5, 4f, 0.90f, glideRate = 0.5f),   // Bb
            LickStep(4, 4f, 0.95f, glideRate = 0.25f),   // A
        ),
        loopLength = 16,
    )

    private data class LickStepOverride(
        val hitProbability: Float = 1.0f,
        val glideRate: Float = 0.0f,
    )

    private fun generateMainLick(
        override1: LickStepOverride = LickStepOverride(),
        override2: LickStepOverride = LickStepOverride(),
        override3: LickStepOverride = LickStepOverride(),
        override4: LickStepOverride = LickStepOverride(),
    ) = Lick(
        steps = listOf(
            LickStep(scaleDegree = 7, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 9, duration = 0.5f, velocity = 0.8f),
            LickStep(
                scaleDegree = 10, duration = 1.0f, velocity = 0.9f,
                glideRate = override1.glideRate, hitProbability = override1.hitProbability
            ),
            LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0f),
            LickStep(
                scaleDegree = 9, duration = 0.25f, velocity = 0.75f,
                glideRate = override2.glideRate, hitProbability = override2.hitProbability
            ),
            LickStep(scaleDegree = -1, duration = .5f, velocity = 0f),
            LickStep(
                scaleDegree = 7, duration = 0.25f, velocity = 0.7f,
                glideRate = override3.glideRate, hitProbability = override3.hitProbability
            ),

            LickStep(
                scaleDegree = 4, duration = 1.5f, velocity = 0.82f,
                glideRate = override4.glideRate, hitProbability = override4.hitProbability
            ),
            LickStep(scaleDegree = -1, duration = 3.0f, velocity = 0f),
        ),
        loopLength = 8,
    )

    // The lead's lick pool: one slot per section.
    //
    // The bass plays the pool (Stay Asleep's gesture track) and the lead doubles it two
    // octaves up. Section.lickIndex pins a slot, so slot ORDER matters: descent 0 (intro,
    // homecoming, return, outro), verse 1, rise 2, vamp 3, peak 4.
    //
    // Writing against this pool:
    //   - D natural minor degrees: 0=D 1=E 2=F 3=G 4=A 5=Bb 6=C, 7=D an octave up. Degree 7
    //     renders D2 on the bass (lickOctave 2) and D4 on the lead. A NEGATIVE degree is a rest.
    //   - The bass range is A1..A2: degrees 4..11. Nothing folds a FIXED track back into range.
    //   - Durations are beats, 4 steps per beat, and rests count. loopLength is a RATIO over
    //     the 64-step pattern: the figure repeats back to back across the first
    //     (sum of durations / loopLength) of it, then silence. Make the steps (rests included)
    //     sum to loopLength and the figure repeats every loopLength beats.
    //   - At 102 BPM: 16th = 147 ms, 8th = 294 ms, beat = 588 ms.
    //   - Glide is a TIME and the ear hears pitch at the attack: 0.12 lands in ~85 ms, 0.28 in
    //     ~170 ms, 0.38 never arrives on a short note. Slides belong on longer notes.
    //   - hitProbability resolves as p + (1 - p) * tension, so a maybe-note firms up as
    //     tension climbs. A lost roll drops the note and the next one is struck, not slid.
    //   - Under a PLUCK_REPEAT grid a held note is re-plucked on the grid: a long note becomes
    //     a rhythmic pulse at one pitch. Verse plucks once, rise re-picks the off 8ths, vamp
    //     and peak the off 16ths.

    // Funk sketch on the 16ths, audition for the verse: an octave pop, ghost notes (velocity
    // ~0.3) and short notes in bar 1, an answer in bar 2. Steps sum to 8 beats, once per loop.
    // The previous verse was generateMainLick() with hitProbability 0 on overrides 1-4.
    private val funkSketch = Lick(
        steps = listOf(
            // bar 1: the one, an octave pop, then off-beat 16ths
            LickStep(7, 0.25f, 0.95f),                    // D  on 1
            LickStep(-1, 0.25f),
            LickStep(14, 0.25f, 0.75f),                   // D' octave pop on &
            LickStep(7, 0.25f, 0.30f),                    // ghost
            LickStep(-1, 0.5f),
            LickStep(9, 0.25f, 0.85f),                    // F  on & of 2
            LickStep(10, 0.5f, 0.90f, glideRate = 0.12f), // G  on a of 2, pushed into 3
            LickStep(-1, 0.25f),
            LickStep(9, 0.25f, 0.35f),                    // ghost
            LickStep(7, 0.25f, 0.85f),                    // D  on a of 3
            LickStep(-1, 0.5f),
            LickStep(4, 0.5f, 0.90f, glideRate = 0.12f),  // A  on & of 4, leaning into bar 2
            // bar 2: the answer
            LickStep(-1, 0.5f),
            LickStep(6, 0.25f, 0.80f),                    // C  pickup
            LickStep(7, 0.75f, 0.90f, glideRate = 0.12f), // D  slid into
            LickStep(-1, 0.5f),
            LickStep(9, 0.25f, 0.85f),                    // F
            LickStep(10, 0.25f, 0.35f),                   // ghost
            LickStep(9, 0.25f, 0.80f),                    // F
            LickStep(7, 0.25f, 0.80f),                    // D
            LickStep(-1, 1.0f),
        ),
        loopLength = 8,
    )

    private val verseLick = funkSketch

    private val riseLick = generateMainLick(
        override1 = LickStepOverride(
            hitProbability = 0.0f,
        ),
        override2 = LickStepOverride(
            hitProbability = 0.5f,
            glideRate = 0.3f,
        ),
        override3 = LickStepOverride(
            hitProbability = 0.0f,
        ),
        override4 = LickStepOverride(
            hitProbability = 0.5f,
            glideRate = 0.3f,
        ),
    )

    private val vampLick = generateMainLick(
        override1 = LickStepOverride(
            hitProbability = 0.3f,
            glideRate = 0.3f,
        ),
        override2 = LickStepOverride(
            hitProbability = 0.8f,
            glideRate = 0.8f,
        ),
        override3 = LickStepOverride(
            hitProbability = 0.2f,
            glideRate = 0.5f,
        ),
        override4 = LickStepOverride(
            hitProbability = 0.8f,
            glideRate = 0.3f,
        ),
    )


    private val peakLick = funkSketch
    private val lickPool = listOf(descent, verseLick, riseLick, vampLick, peakLick)

    // The intro swells from introSoft to verseLevel; the outro glides back the other way.
    private val introSoft = MacroOverrides(energy = 0.45f, complexity = 0.6f, space = 1.2f, volume = 0.55f)
    private val verseLevel = MacroOverrides(energy = 1f, complexity = 1f, space = 1f, volume = 1f)

    override val vibe: Vibe by lazy {
        Vibe(
            name = name.value,
            bpm = 102f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.D,
            scaleType = ScaleType.MINOR,
            energy = 0.55f,
            complexity = 0.4f,
            space = 0.45f,
            mood = 0.35f,
            deep = 0.5f,
            levelDb = -3.8f,  // loudness match to -16 LUFS
            stepCount = 64,   // 4-bar pattern = one full pass of the 4-chord descent
            genre = GenreProfile(
                swingAmount = 0.06f,
                ghostProbability = 0.3f,   // busy hands: ghost strokes filling between the backbeats
                noteRangeLow = 45,
                noteRangeHigh = 69,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.DARK,
                chordsPerBar = 4,   // one chord per bass anchor (stepCount 64 / 4 = 16 steps)
                customProgression = chords(0, 6, 5, 4),  // D - C - Bb - A lament
            ),
            progressionAnchor = ProgressionAnchor.EVERY_4,
            progressionDriftRange = 0.1f,

            // The pool is what the bass plays (the lead doubles it); load-time fallback, slot 0.
            lick = lickPool.first(),
            lickRotation = LickRotation(pool = lickPool, carryGrowth = true),
            lickMutation = 0.05f,   // written notes stay written; solos keep their own mutationRate
            lickOctave = 2,          // explicit: degree 7 = D2, the bass register

            bassLine = sirensDescent,
            bassLineMutation = 0.2f,     // pinned while dialing in; raise once it sounds right
            bassLineOctave = 2,        // the Sirens add 23 degrees to this (F5 to C5)

            lickWah = WahParams(
                rateDivision = 4f,     // quarter-note rock of the pedal
                depth = 0.85f,
                resonanceQ = 3f,
                centerHz = 750f,
                sweepOctaves = 1.2f,
                wet = 0.9f,
            ),
            anomalies = listOf(
                // Rare lead wah sweep. Eligibility is role-driven: melodic non-bass tracks reading
                // the lead channel, here only 4. The bass (3) is excluded by the bass-channel
                // clause; the Sirens (5) read the bass line; the organ (6), the bell (7) and the kit
                // are not Melodic. Track 4 already runs the standing lickWah, so the anomaly TAKES
                // OVER that filter rather than stacking a second bandpass.
                // Pinned by WahAnomalyTest.orphicLoreIsTheShippedTakeoverCase.
                WahAnomaly(probability = 0.03f),
            ),

            tracks = listOf(
                // 0 — kick: the floor of the whole thing. Harmonics/timbre locked (min == max)
                // so the Mood knob cannot thin the drum out from under the jam. Locking via the
                // macro map rather than pinHarmonics keeps tension-evolution alive.
                OrpheusEngine(
                    engineId = OrpheusEngineId.BD, volume = 1f,
                ).let { kick ->
                    TrackVoice(
                        engineEdm = kick, engineSpace = kick,
                        role = TrackRole.Percussive,
                        density = 0.62f,
                        barStrategy = BarStrategy.REPEAT,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM.copy(
                            moodHarmonics = MacroTarget(0.58f, 0.58f),  // fat, driven kick
                            moodTimbre = MacroTarget(0.62f, 0.62f),     // long decay
                        ),
                    )
                },
                // 1 — snare + toms: the fills are the point. Big and loose, landing hard on
                // the backbeat and spilling into tom rolls between the walkdown anchors.
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD, volume = 0.75f,
                ).let { snare ->
                    TrackVoice(
                        engineEdm = snare, engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.08f,
                        density = 0.56f,
                        barStrategy = BarStrategy.FILL,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM.copy(
                            moodTimbre = MacroTarget(0.6f, 0.6f),  // body over crack
                        ),
                    )
                },
                // 2 — ride wash: rides the whole jam rather than ticking a hat. Louder and
                // busier than a backbeat hat wants, which is exactly the point.
                OrpheusEngine(
                    engineId = OrpheusEngineId.HH, volume = 0.42f,
                    reverbSend = 0.15f,
                ).let { ride ->
                    TrackVoice(
                        engineEdm = ride, engineSpace = ride,
                        role = TrackRole.Percussive,
                        pan = 0.30f,
                        density = 0.62f,
                        barStrategy = BarStrategy.MUTATE,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                    )
                },
                // 3 — THE bass: plays the section's pool lick, like Stay Asleep's gesture track.
                // FIXED: the descent IS the progression, so FOLLOW would transpose it twice.
                OrpheusEngine(
                    engineId = OrpheusEngineId.PD, volume = 0.56f,
                    noteRangeLow = 33, noteRangeHigh = 45,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.PD),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FIXED,
                            lickMode = LickMode.Fill,
                            // The bassist works their own pedal: slow and low, against the
                            // lead's quarter-note rock. One sweep per two bars = two per pass
                            // of the walkdown. Centered where the bass's harmonics live rather
                            // than its fundamentals (D2 is 73 Hz): 380 Hz +/- 1.4 octaves spans
                            // 144 Hz to 1 kHz. Wet stays near half because a full-wet bandpass
                            // would strip the low end this track exists to provide.
                            wahLick = true,
                            wahParams = WahParams(
                                rateDivision = 0.5f,
                                depth = 0.9f,
                                resonanceQ = 2.5f,
                                centerHz = 380f,
                                sweepOctaves = 1.4f,
                                wet = 0.55f,
                            ),
                        ),
                        density = 0.6f,
                        barStrategy = BarStrategy.REPEAT,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                    )
                },
                // 4 — wah lead: standing wah insert, doubles the bass's pool lick two octaves up
                // (lickDegreeOffset 14), so the two play one funky unison riff. Plucked so
                // every note is struck; glide 0 so only steps that ask for a slide get one.
                // REPEAT, not CALL_RESPONSE, so the written lick is not rewritten as an answer.
                // WSH in both slots: a VA space slot made it thin out at random at mid energy.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH, volume = 0.79f,
                    noteRangeLow = 57, noteRangeHigh = 76,
                    delaySend = 0.25f, reverbSend = 0.3f,
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.50f, lpgColour = 0.55f,   // Fire Sky's ear-tuned grit
                    glideRate = 0f,
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead,
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FIXED,
                            lickMode = LickMode.Fill,
                            lickDegreeOffset = 14,
                            wahLick = true,
                        ),
                        density = 0.5f,
                        barStrategy = BarStrategy.REPEAT,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                    )
                },
                // 5 — the Sirens: the vowel voice harmonizes the bass's descent a diatonic third
                // above, three octaves up (F E D C over D C Bb A), one long note per anchor; a lick
                // line, so the texture notch leaves it alone. lickDegreeOffset 23 = +3 octaves and a
                // third (FIXED skips the octave pin). Grain FX turns the one voice into a choir.
                OrpheusEngine(
                    engineId = OrpheusEngineId.SPK,
                    volume = 0.24f,  // enters as a faint hum
                    // SPK: harmonics picks the speech model (0 formant, .17 SAM, .33 LPC), so 0.13 is
                    // mostly SAM; morph is the vowel (SAM: 0 a, .2 e, .4 i, .6 o, .8 u), pinned.
                    timbre = 0.5f,
                    harmonics = 0.13f, pinHarmonics = true,
                    // Each sung note closes its vowel from "ah" to "eh" over 1.5 s...
                    morph = 0f, pinMorph = true,
                    morphRise = 0.2f, morphRiseTime = 1.5f,
                    // ...and climbs an eighth tone over 2 s while DRONE swells it in.
                    pitchRise = 25f, pitchRiseTime = 2f,
                    // A singer's waver on top, fading in 0.3 s after each note starts.
                    vibratoDepth = 25f, vibratoRate = 5.5f,
                    // The choir: grains of the voice itself landing on chord tones within about a
                    // third either side, clustered on the sung note, so the Sirens sing in harmony.
                    // Long slow-attack grains carry across the note changes, so the harmony stays
                    // about 3 dB under the sung note; blend 0.45 keeps the dry singer on top.
                    grainFx = true,
                    grainMode = GrainMode.BURST,
                    grainScatterMode = GrainScatterMode.CHORD,
                    grainVoicing = GrainVoicing.CENTERED,
                    grainScatter = 0.20f,
                    grainRate = 0.6f,
                    grainRandomTiming = true,
                    grainShape = 1f,
                    grainSize = 0.75f,
                    grainBlend = 0.45f,
                    // The choir's grains stay inside this range. Capped at F5, the highest sung note:
                    // an upward grain is the voice sped up, formants and all, and above F5 it squeaks.
                    noteRangeLow = 69, noteRangeHigh = 77,
                    reverbSend = 0.6f, delaySend = 0.2f,
                    reverbBrightness = 0.5f,
                ).let { sirens ->
                    TrackVoice(
                        engineEdm = sirens, engineSpace = sirens,
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FIXED,
                            lickMode = LickMode.Fill,
                            lickSource = LickSource.BASS,
                            lickDegreeOffset = 23,
                        ),
                        pan = 0.50f,
                        density = 0.36f,
                        barStrategy = BarStrategy.REPEAT,
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.EFFECT.copy(energyDensity = MacroTarget(1f, 1f)),
                    )
                },
                // 6 — the Hammond, following the chords (the descent, the vamp's Dm-Gm, the
                // peak's climb): a held bed in the verse, homecoming and return, FUNK_STABS
                // comping in the rise, vamp and peak. The vibrato stands in for the Leslie: the
                // fast rotor at high energy, a slow chorale below 0.4 (homecoming, outro).
                // A held organ note reads far louder than a 44 ms stab at the same gain, so the
                // bed sits at 0.28 and the stab sections lift the track to 0.6.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX3, volume = 0.28f,
                    harmonics = 0.046f,                       // DX3 idx 1 "Hammond" (auto-pinned)
                    vibratoDepth = 12f, vibratoRate = 6.5f,   // fast rotor
                    noteRangeLow = 50, noteRangeHigh = 74,    // D3 to D5, under the guitar
                    delaySend = 0.18f, reverbSend = 0.34f,
                ).let { hammond ->
                    TrackVoice(
                        engineEdm = hammond,
                        engineSpace = hammond.copy(
                            vibratoDepth = 8f, vibratoRate = 0.8f,   // slow chorale
                        ),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            // PAD + NEVER, like the organ beds in Blacktop Boogie and Black Cat: one
                            // held tonewheel note per chord. A roll on this single-voice organ sounded
                            // plucked, each note cutting off the last.
                            comping = ChordComping(
                                style = CompingStyle.PAD,
                                arpMode = ArpMode.NEVER,
                                restrikeOnChordChange = true,     // a new held note on each chord
                                humanization = CompingHumanization(
                                    dropProbability = 0.05f,
                                    octaveJumpProbability = 0.15f,
                                    extensionProbability = 0.2f,   // a 9th or 4th now and then
                                ),
                                fills = CompingFills(
                                    everyNBars = 8,
                                    fillType = FillType.ASCENDING_ARP,
                                    skipProbability = 0.4f,
                                ),
                            ),
                        ),
                        pan = -0.50f,
                        density = 0.7f,
                        barStrategy = BarStrategy.REPEAT,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        // Every stab fires: MELODIC's energy roll dropped 10-20% of them.
                        macroMap = TrackMacroMap.MELODIC.copy(energyDensity = MacroTarget(1f, 1f)),
                        // The rise, vamp and peak are jam solos, so the organ comps behind a
                        // soloist there: keep every stab and most fills, at nearly full velocity.
                        duckingProfile = DuckingProfile(
                            volumeReduction = 0.05f,
                            densityReduction = 0f,
                            fillSuppression = 0.2f,
                            simplify = false,
                        ),
                    )
                },
                // 7 — the ship's bell in the wind: an FM tubular bell struck on 1 and 3, each strike
                // a quick ding-dong pair (the arp rolls the chord's root then its third), far off
                // across the water. Clean at low energy (intro, outro); in the storm the high-energy
                // slot adds grains of its own ring, gusting and smeared by the wind. Track 7 is the
                // FX slot, so it only rings at low energy or in the storm.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX2, volume = 0.34f,
                    harmonics = 0.689f,                       // DX2 idx 22 "Tub Bells" (auto-pinned)
                    noteRangeLow = 62, noteRangeHigh = 74,
                    reverbSend = 0.8f, delaySend = 0.35f,     // across water
                    reverbBrightness = 0.4f,
                ).let { bell ->
                    TrackVoice(
                        engineEdm = bell.copy(
                            grainFx = true,
                            grainMode = GrainMode.BURST,
                            grainScatterMode = GrainScatterMode.RAW,
                            grainScatter = 0.04f,                 // a wind-bent smear, not chords
                            grainRate = 0.5f,
                            grainRandomTiming = true,             // gusty spacing, wide stereo
                            grainShape = 0.8f,
                            grainSize = 0.7f,
                            grainBlend = 0.35f,                   // the strike stays clear
                        ),
                        engineSpace = bell,
                        role = TrackRole.Chordal(
                            comping = ChordComping(
                                style = CompingStyle.ROCK_DOWNBEATS,
                                arpMode = ArpMode.ALWAYS,
                                arpSpeed = 0.3f,                  // a ding-dong, not a run
                            ),
                        ),
                        pan = 0.60f,
                        density = 0.35f,                      // the wind drops some tolls
                        barStrategy = BarStrategy.REPEAT,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.EFFECT.copy(energyDensity = MacroTarget(1f, 1f)),
                    )
                },
            ),

            band = Band(
                // One soloist per section: a lead outlasts the rise (6-8) and the vamp (8-10).
                barsPerLeadMin = 10, barsPerLeadMax = 12,
                members = listOf(
                    BandMember(name = "Drummer", tracks = listOf(0, 1, 2), alwaysActive = true),
                    BandMember(name = "Bassist", tracks = listOf(3), loudness = 0.8f),
                    BandMember(name = "Guitarist", tracks = listOf(4), loudness = 0.85f),
                    // The Sirens, the organ bed and the bell: the sea around the band.
                    BandMember(name = "Sea", tracks = listOf(5, 6, 7)),
                ),
                handoffMatrix = bandMatrix(
                    //            DRUM  BASS  GTR   SEA
                    "Drummer" to row(0.0f, 0.2f, 0.6f, 0.2f),   // Drummer hands to Guitarist mostly
                    "Bassist" to row(0.1f, 0.0f, 0.7f, 0.2f),   // Bassist -> Guitarist
                    "Guitarist" to row(0.1f, 0.5f, 0.0f, 0.4f), // Guitarist -> Bassist (the trade!)
                    "Sea" to row(0.1f, 0.3f, 0.6f, 0.0f),       // Sea -> Guitarist
                ),
                // No pull-in guidance in the source draft; mirrors handoffMatrix's cast
                // relationships until balch hand-tunes a distinct pull-in feel by ear.
                pullInMatrix = bandMatrix(
                    //            DRUM  BASS  GTR   SEA
                    "Drummer" to row(0.0f, 0.2f, 0.6f, 0.2f),
                    "Bassist" to row(0.1f, 0.0f, 0.7f, 0.2f),
                    "Guitarist" to row(0.1f, 0.5f, 0.0f, 0.4f),
                    "Sea" to row(0.1f, 0.3f, 0.6f, 0.0f),
                ),
            ),

            tension = TensionProfile(
                innerBars = 8,
                outerBars = 32,
                outerDepth = 0.4f,
                volume = 0.3f,
                timing = 0.15f,
                spurtChance = 0.04f,
            ),

            effects = VibeEffects(
                delayTimeA = 0.375f,   // dotted-8th slapback
                delayFeedback = 0.35f,
                reverbSize = 0.5f,
                reverbBrightness = 0.45f,
                deepFloor = 0.25f,
            ),

            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = 7,
                lengthSeconds = 210..240,
                sections = listOf(
                    // Lengths are section bars (32 steps = two musical bars) and all even: the
                    // descent is 64 steps, so an odd count would start a homecoming on its Bb.

                    // 0 LIBATION (intro): the bass states the lament with the Sirens singing it far above.
                    // It opens soft and sparse and swells into the verse: energy, complexity,
                    // space and the whole mix glide to the verse's level by the last step,
                    // while a RISE arc lifts the velocities bar by bar.
                    Section(
                        name = "libation",
                        barsMin = 4, barsMax = 4,
                        lickIndex = 0,
                        macroOverrides = introSoft,
                        rampTo = verseLevel,
                        tensionOverride = TensionProfile(
                            innerBars = 4, volume = 1f, timing = 0.1f, arc = TensionArc.RISE,
                        ),
                        trackOverrides = mapOf(
                            0 to TrackSectionOverride(density = 0.15f),
                            1 to TrackSectionOverride(density = 0f),
                            2 to TrackSectionOverride(density = 0f),
                            4 to TrackSectionOverride(density = 0f),  // guitar and organ out
                            6 to TrackSectionOverride(density = 0f),
                            7 to TrackSectionOverride(density = 0f),
                        ),
                        transitions = listOf(SectionTransition(1, 1f, transitionBars = 1)),
                    ),
                    // 1 SAIL (verse): slow full-band groove; the lead states the funky hook. From here to
                    // the return, the Sirens breathe every 2 section bars and the bass every 3:
                    // they start together, drift into counter-motion, and meet again every 6.
                    Section(
                        name = "sail",
                        barsMin = 4, barsMax = 6, barStep = 2,
                        lickIndex = 1,
                        trackOverrides = mapOf(
                            3 to TrackSectionOverride(breatheBars = 3, breatheFloor = 0.75f),
                            5 to TrackSectionOverride(breatheBars = 2, breatheFloor = 0.55f),
                            7 to TrackSectionOverride(density = 0f),
                        ),
                        transitions = listOf(SectionTransition(2, 1f, transitionBars = 1)),
                    ),
                    // 2 WINDS (rise): the band leans in over the descent; the lead re-picks the off 8ths.
                    // Solo probability 0.95 because the roll happens ONCE per section entry.
                    Section(
                        name = "winds",
                        barsMin = 6, barsMax = 8, barStep = 2,
                        lickIndex = 2,
                        macroOverrides = MacroOverrides(
                            energy = 1.2f, complexity = 1.15f, space = 0.9f, mood = 1.05f,
                        ),
                        // Jam, not LickBuilder: it re-renders this section's hook every bar, so the
                        // solo never starts from the previous section's lick or drifts off the bar.
                        soloMode = SoloMode.Jam(probability = 0.95f),
                        trackOverrides = mapOf(
                            3 to TrackSectionOverride(breatheBars = 3, breatheFloor = 0.75f),
                            4 to TrackSectionOverride(lpgMode = LpgMode.PLUCK_REPEAT_8TH_OFF),
                            // The Sirens and the organ drift: each section pan glides there at 0.15/s,
                            // mirrored. Here they lean in with the band; shades crosses them over.
                            5 to TrackSectionOverride(volume = 0.28f, pan = 0.30f, breatheBars = 2, breatheFloor = 0.55f),
                            6 to TrackSectionOverride(compingStyle = CompingStyle.FUNK_STABS, volume = 0.6f, pan = -0.30f),
                        ),
                        transitions = listOf(SectionTransition(3, 1f, transitionBars = 1)),
                    ),
                    // 3 SHADES (homecoming): the bass and lead come home to the intro's descent, two loops,
                    // a breath before the jam. No solo here, so the vamp starts a fresh one.
                    // energy 0.55*0.7 = 0.385 drops under the FX slot's low gate (< 0.4), so the
                    // bell tolls here; density is a no-op on a Chordal track, so volume carries it.
                    Section(
                        name = "shades",
                        barsMin = 4, barsMax = 4,
                        lickIndex = 0,
                        macroOverrides = MacroOverrides(energy = 0.7f),
                        trackOverrides = mapOf(
                            3 to TrackSectionOverride(breatheBars = 3, breatheFloor = 0.75f),
                            5 to TrackSectionOverride(volume = 0.24f, pan = -0.35f, breatheBars = 2, breatheFloor = 0.55f),
                            6 to TrackSectionOverride(pan = 0.35f),
                            7 to TrackSectionOverride(volume = .42f, reverbSend = .65f),
                        ),
                        transitions = listOf(SectionTransition(4, 1f, transitionBars = 2)),
                    ),
                    // 4 SIRENS (vamp): a slow bVI-bVII climb (Bb, C) that pulls toward a D it never
                    // reaches, under the vamp lick. The Sirens keep F E D C, so their E is a #11 over Bb.
                    // The bass and lead stay FIXED on their figures, only the organ and bell follow.
                    // No chordFollow override on the bass: transposition would flatten its solos.
                    Section(
                        name = "sirens",
                        barsMin = 8, barsMax = 10, barStep = 2,
                        lickIndex = 3,
                        jamCarry = true,
                        customProgression = chords(5, 6),
                        chordsPerBar = 1,
                        macroOverrides = MacroOverrides(
                            energy = 1.25f, complexity = 1.3f, space = 1.1f, mood = 1.1f,
                        ),
                        soloMode = SoloMode.Jam(probability = 0.95f),
                        trackOverrides = mapOf(
                            3 to TrackSectionOverride(breatheBars = 3, breatheFloor = 0.75f),
                            4 to TrackSectionOverride(lpgMode = LpgMode.PLUCK_REPEAT_16TH_OFF),
                            // The Sirens dominate their section: twice the base level, a shallow
                            // breath, drier and drifting to the centre so they sit in front; the
                            // organ steps back and out to the side for them.
                            5 to TrackSectionOverride(
                                volume = 0.80f, pan = 0f, reverbSend = 0.45f, breatheBars = 2, breatheFloor = 0.85f,
                            ),
                            6 to TrackSectionOverride(compingStyle = CompingStyle.FUNK_STABS, volume = 0.45f, pan = -0.60f),
                        ),
                        transitions = listOf(SectionTransition(5, 1f, transitionBars = 2)),
                    ),
                    // 5 STORM (peak): free improv over the vamp. complexity 0.4*1.8 = 0.72 plus energy
                    // 0.55*1.5 = 0.83 opens track 7's FX gate, so the bell rings through the
                    // storm. innerBars 8 keeps the sawtooth peak above the auto-spurt threshold.
                    Section(
                        name = "storm",
                        barsMin = 8, barsMax = 12, barStep = 2,
                        lickIndex = 4,
                        // No jamCarry: the jam starts fresh here and builds through the storm.
                        // The epic climb: Dm, Bb, C, Dm (i-bVI-bVII-i), two bars each.
                        customProgression = chords(0, 5, 6, 0),
                        chordsPerBar = 2,
                        macroOverrides = MacroOverrides(
                            energy = 1.5f, complexity = 1.8f, space = 0.8f, mood = 1.15f,
                        ),
                        soloMode = SoloMode.Jam(probability = 1.0f),
                        // Poseidon's storm: a near, full downpour with a strike most bars. It swells
                        // in over the vamp's 2-bar pre-roll and stops dead at the hard cut home.
                        weather = SectionWeather(
                            rain = 1f,
                            rumble = 0.8f,
                            strikeChance = 0.9f,
                            distance = 0.2f
                        ),
                        // A crack overhead on the first beat, answered from further off.
                        entryEffects = listOf(
                            StrikeEffect(intensity = 0.95f, distance = 0.1f),
                            StrikeEffect(intensity = 0.75f, distance = 0.4f, delayMs = 420),
                        ),
                        tensionOverride = TensionProfile(
                            innerBars = 8,
                            outerBars = 0,
                            outerDepth = 0f,
                            volume = 0.35f,
                            timing = 0.15f,
                            spurtChance = 0.2f,
                        ),
                        trackOverrides = mapOf(
                            4 to TrackSectionOverride(lpgMode = LpgMode.PLUCK_REPEAT_16TH_OFF),
                            // The storm throws the Sirens and the organ wide; ithaca brings them home.
                            5 to TrackSectionOverride(volume = 0.49f, pan = 0.55f),
                            6 to TrackSectionOverride(compingStyle = CompingStyle.FUNK_STABS, volume = 0.6f, pan = -0.55f),
                            // The bell swings hard in the storm.
                            7 to TrackSectionOverride(volume = 0.5f),
                        ),
                        // Hard cut home: the biggest bar slams into the descent.
                        transitions = listOf(SectionTransition(6, 1f, transitionBars = 0)),
                    ),
                    // 6 ITHACA (return): the descent again, two loops, then back to the verse
                    Section(
                        name = "ithaca",
                        barsMin = 4, barsMax = 4,
                        lickIndex = 0,
                        trackOverrides = mapOf(
                            3 to TrackSectionOverride(breatheBars = 3, breatheFloor = 0.75f),
                            5 to TrackSectionOverride(breatheBars = 2, breatheFloor = 0.55f),
                        ),
                        transitions = listOf(SectionTransition(1, 1f, transitionBars = 1)),
                    ),
                    // 7 ASHORE (outro): the Sirens' Bb-C climb returns and the song fades out still
                    // climbing (terminal; reached only when armed). It glides from just under the
                    // verse's level to the intro's soft opening, with a FALL arc easing the
                    // velocities down, while the mix fades out: 40% as bar 5 starts, 15% halfway
                    // through it, silent a quarter into bar 6 (and through the loop the song hands
                    // off in). The fade is on the dry mix only, so the last bar is the Sirens' and
                    // the bell's reverb and delay, carrying out alone.
                    // Energy 0.75 (0.55 * 0.75 = 0.41) drops under the bell's gate (< 0.4) within a
                    // bar, so it tolls across the water almost from the start; at 1.0 it waited 3 bars.
                    Section(
                        name = "ashore",
                        barsMin = 6, barsMax = 6,
                        customProgression = chords(5, 6),
                        lickIndex = 0,
                        macroOverrides = verseLevel.copy(volume = null, energy = 0.75f),
                        rampTo = introSoft.copy(volume = null),
                        volumeEnvelope = listOf(
                            VolumePoint(bar = 0f, level = 1f),
                            VolumePoint(bar = 4f, level = 0.4f),
                            VolumePoint(bar = 4.5f, level = 0.15f),
                            VolumePoint(bar = 5.25f, level = 0f),
                        ),
                        tensionOverride = TensionProfile(
                            innerBars = 6, volume = 1f, timing = 0.1f, arc = TensionArc.FALL,
                        ),
                        trackOverrides = mapOf(
                            5 to TrackSectionOverride(  // the Sirens sing it ashore, drawing in
                                volume = 0.61f,
                                pan = 0.20f,
                                reverbSend = .7f,
                                delaySend = 0.15f
                            ),
                            6 to TrackSectionOverride(pan = -0.20f),
                            7 to TrackSectionOverride( // across water
                                density = .5f,
                                reverbSend = 0.95f,
                                delaySend = 0.55f,
                            ),
                        ),
                        transitions = emptyList(),
                    ),
                ),
            ),
        )
    }
}
