package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickSource
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
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.WahParams
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Odysseus Lore — slow psychedelic lament for a heavy quartet, twin guitars over a
 * walking bass.
 *
 * A descending bassline (the vibe's whole spine) walks D-C-Bb-A under two guitars
 * moving in diatonic thirds, on a drum kit that plays the room rather than the click.
 * Structure breathes verse -> rise -> Dm vamp -> free-improv peak -> hard cut back to
 * the slow verse; the jam is the centerpiece (2-3 minutes most plays).
 *
 * First vibe on the bass line channel and on per-track wah voicing: the bass rocks a
 * slow pedal low in the spectrum while the lead works a quarter-note pedal up in the
 * vowel range, two pedals at two rates at once. The bass owns its authored figure, the
 * lead owns its wah phrases, the second guitar reads the same lick a third above and
 * drifts off it the way a second player does, and LickBuilder jams mutate whichever
 * channel the soloist owns.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class OdysseusLoreVibe : VibeProvider {
    override val name: String = "Odysseus Lore"

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            bpm = 102f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.D,
            scaleType = ScaleType.MINOR,
            energy = 0.55f,
            complexity = 0.4f,
            space = 0.45f,
            mood = 0.35f,
            deep = 0.5f,
            stepCount = 64,   // 4-bar pattern = one full pass of the 4-chord descent
            genre = GenreProfile(
                swingAmount = 0.06f,
                ghostProbability = 0.3f,   // busy hands: ghost strokes filling between the backbeats
                noteRangeLow = 45,
                noteRangeHigh = 69,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.DARK,
                chordsPerBar = 1,
                customProgression = chords(0, 6, 5, 4),  // D - C - Bb - A lament
            ),
            progressionAnchor = ProgressionAnchor.EVERY_4,
            progressionDriftRange = 0.1f,

            // The lead's wah phrase: a rising call that hangs, then falls home.
            // (Draft — tuned by ear with balch; degrees are D natural minor, +7 = octave.)
            lick = Lick(
                steps = listOf(
                    LickStep(scaleDegree = 7, duration = 0.5f, velocity = 0.85f),
                    LickStep(scaleDegree = 9, duration = 0.5f, velocity = 0.8f),
                    LickStep(scaleDegree = 10, duration = 1.0f, velocity = 0.9f, glideRate = 0.3f),
                    LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0f),
                    LickStep(scaleDegree = 9, duration = 0.5f, velocity = 0.75f),
                    LickStep(scaleDegree = 7, duration = 0.5f, velocity = 0.7f),
                    LickStep(scaleDegree = 4, duration = 1.5f, velocity = 0.82f),
                    LickStep(scaleDegree = -1, duration = 3.0f, velocity = 0f),
                ),
                loopLength = 8,  // phrase then space — the wah breathes
            ),
            lickMutation = 0.45f,
            lickOctave = -1,

            // The spine: four descending anchors (D C Bb A), one per bar, each landing
            // ON the downbeat with a 16th pickup that slurs into the next. glideRate
            // lives on the ANCHOR, not the pickup: the engine slides from the previous
            // pitch INTO the step carrying the rate, so a rate on a same-pitch pickup
            // slides nowhere. No rests either — a rest clears prev_step_gated and the
            // following glide is skipped. FIXED chordFollow renders it as written.
            bassLine = Lick(
                steps = listOf(
                    LickStep(7, 3.75f, 0.95f, glideRate = 0.5f),                   // bar 1 — D2
                    LickStep(7, 0.25f, 0.45f),                   //   pickup, lifts into C
                    LickStep(6, 3.75f, 0.90f, glideRate = 0.5f), // bar 2 — C2, slurs down
                    LickStep(6, 0.25f, 0.45f),
                    LickStep(5, 3.75f, 0.90f, glideRate = 0.5f), // bar 3 — Bb1
                    LickStep(5, 0.25f, 0.45f),
                    LickStep(4, 3.75f, 0.95f, glideRate = 0.5f),  // bar 4 — A1, holds the bar
                    LickStep(4, 0.25f, 0.45f),  // bar 4 — A1,
                ),
                loopLength = 16,  // 16 beats of notes = the full 64-step pattern, no padding
            ),
            bassLineMutation = 0f,     // pinned while dialing in; raise once it sounds right
            bassLineOctave = 2,        // D2 register descent (38 -> 36 -> 34 -> 33)

            lickWah = WahParams(
                rateDivision = 4f,     // quarter-note rock of the pedal
                depth = 0.85f,
                resonanceQ = 3f,
                centerHz = 750f,
                sweepOctaves = 1.2f,
                wet = 0.9f,
            ),
            anomalies = listOf(
                // Rare lead-only wah sweep. Eligibility is role-driven, so it covers every
                // melodic non-bass track: 4 (lead), 6 (harmony guitar) and 7 (the swirl, which
                // is Melodic even though it reads as texture). The bass (3) is excluded by the
                // bass-channel clause despite carrying its own standing wah, and the pad (5)
                // and the kit are excluded by role. Track 4 already runs the standing lickWah
                // above, so the anomaly TAKES OVER that one filter for its armed duration
                // rather than stacking a second bandpass on it; 6 and 7 have no standing wah,
                // so they get fresh voices that fade in and back out. Pinned by
                // WahAnomalyTest.odysseusLoreIsTheShippedTakeoverCase.
                WahAnomaly(probability = 0.03f),
            ),

            tracks = listOf(
                // 0 — kick: the floor of the whole thing. Long vactrol decay so each hit
                // booms instead of clicking, and harmonics/timbre locked (min == max) so the
                // Mood knob cannot thin the drum out from under the jam. Locking via the
                // macro map rather than pinHarmonics keeps tension-evolution alive.
                OrpheusEngine(
                    engineId = OrpheusEngineId.BD, volume = 1f,
                    lpgDecay = 0.72f, lpgColour = 0.35f,
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
                    engineId = OrpheusEngineId.SD, volume = 0.95f,
                    lpgDecay = 0.6f,
                ).let { snare ->
                    TrackVoice(
                        engineEdm = snare, engineSpace = snare,
                        role = TrackRole.Percussive,
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
                    engineId = OrpheusEngineId.HH, volume = 0.7f,
                    lpgDecay = 0.55f, reverbSend = 0.15f,
                ).let { ride ->
                    TrackVoice(
                        engineEdm = ride, engineSpace = ride,
                        role = TrackRole.Percussive,
                        density = 0.62f,
                        barStrategy = BarStrategy.MUTATE,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                    )
                },
                // 3 — THE bass: owns the bass line channel
                OrpheusEngine(
                    engineId = OrpheusEngineId.PD, volume = 0.85f,
                    noteRangeLow = 33, noteRangeHigh = 45,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.PD),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FIXED,  // the phrase IS the progression
                            lickMode = LickMode.Fill,
                            lickSource = LickSource.BASS,
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
                // 4 — wah lead: standing wah insert, plays the lead lick
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH, volume = 0.78f,
                    noteRangeLow = 57, noteRangeHigh = 76,
                    delaySend = 0.25f, reverbSend = 0.3f,
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead.copy(engineId = OrpheusEngineId.VA),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FIXED,
                            lickMode = LickMode.Fill,
                            wahLick = true,
                        ),
                        density = 0.5f,
                        barStrategy = BarStrategy.CALL_RESPONSE,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                    )
                },
                // 5 — organ haze: was a thin bed, now a real presence under the jam. The
                // slow mod LFO gives it drift so it breathes instead of sitting flat; a
                // DRONE profile is one of the two things that enable that LFO at all.
                OrpheusEngine(
                    engineId = OrpheusEngineId.ENS, volume = 0.5f,
                    holdProbability = 0.9f, reverbSend = 0.58f,
                    modLfoRate = 0.05f, modLfoDepth = 0.4f,
                    reverbBrightness = 0.4f,
                ).let { pad ->
                    TrackVoice(
                        engineEdm = pad, engineSpace = pad,
                        role = TrackRole.Chordal(),
                        density = 0.36f,
                        barStrategy = BarStrategy.REPEAT,
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.EFFECT,
                    )
                },
                // 6 — second guitar. MUST NOT live on track 7: that index is the FX slot, and
                // its note-ons are probability-gated by compute_fx_probability(energy,
                // complexity), which is flat zero unless energy < 0.4 or (complexity > 0.7 and
                // energy > 0.6). This vibe's base macros are 0.55 / 0.4; rise and vamp only lift
                // them to 0.66 / 0.46 and 0.69 / 0.52, but the peak's 1.5x/1.8x pushes them to
                // 0.83 / 0.72, so a lead parked on 7 now fires at the peak (complexity > 0.7 and
                // energy > 0.6) as well as the outro. Index carries behavior independently of
                // TrackRole; see the branches on `t` in orpheus_unit_pulsar.cpp.
                //
                // Track 6 is not free of that either: tracks >= 5 are the texture band, so this
                // voice is scaled by texture_energy_curve(energy), a notch pinned at 0.05 across
                // energy 0.45..0.55 that ramps back to 1.0 by 0.65. Base energy 0.55 sits exactly
                // on the notch floor, so the harmony is a whisper in the VERSE (no override),
                // full from the RISE (0.66) through the VAMP (0.69) and PEAK (0.83), and just
                // as full in the OUTRO (0.30): the curve is full below 0.35 too, not just by
                // 0.65, notched only across 0.45..0.55. That reads as a second player who
                // sits out the verse and comes in for the jam, which is the arrangement we want,
                // but it is forced rather than chosen: tracks 0-2 are the kit, 3 is the bass bus
                // and 4 is the lead, so track 4 is the ONLY slot with no texture duck. Lifting
                // the harmony into the verse means base energy >= 0.65, which drives the whole
                // lament harder and narrows the verse-to-jam contrast the structure is built on.
                //
                // It plays the same lick as track 4 offset +2 scale degrees, a diatonic third in
                // a 7-note scale. It will NOT lock to the lead in parallel thirds, and that is
                // not tunable from here. The lick render is seeded per track (seed ^ track *
                // 7919u), so at lickMutation 0.45 roughly half the call-half notes are perturbed
                // differently on each guitar. lickDegreeOffset is applied AFTER mutation
                // (bar_strategy_call_response), so the third is exact only where the two happen
                // to agree.
                //
                // Zeroing mutation would NOT fix it, so don't bother trying: mutation is
                // channel-wide (Vibe.lickMutation / bassLineMutation, no per-track field exists),
                // and the CALL_RESPONSE answer half picks its transform as `seed % 4` off that
                // same salted seed, so at mutation 0 one guitar can invert the contour while the
                // other transposes up a third. A true locked harmony needs an engine-side "render
                // this track from track N's steps" flag. Both guitars keep CALL_RESPONSE anyway
                // because that makes them PHRASE together, call then answer, which is the part
                // that reads as one section. Two players reading one chart, not a doubling.
                //
                // Stays dry on purpose: a third wah against the lead's quarter-note pedal and the
                // bass's slow one would be mud. Give it its own wahParams if you want that.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH, volume = 0.6f,
                    noteRangeLow = 57, noteRangeHigh = 79,
                    delaySend = 0.22f, reverbSend = 0.34f,
                ).let { harmony ->
                    TrackVoice(
                        engineEdm = harmony,
                        engineSpace = harmony.copy(engineId = OrpheusEngineId.VA),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FIXED,
                            lickMode = LickMode.Fill,
                            lickDegreeOffset = 2,
                        ),
                        density = 0.42f,
                        barStrategy = BarStrategy.CALL_RESPONSE,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                    )
                },
                // 7 — psych swirl: granular color, up from "barely there" to "noticed".
                // Still INDEPENDENT so it drifts across the bar lines rather than locking.
                // On the FX slot deliberately, swapped here from track 6: the probability gate
                // that silences a lead is exactly right for a texture voice, so the swirl now
                // surfaces at low energy and again when the jam drives complexity up.
                OrpheusEngine(
                    engineId = OrpheusEngineId.GRN, volume = 0.45f,
                    reverbSend = 0.62f, delaySend = 0.3f,
                    modLfoRate = 0.07f, modLfoDepth = 0.45f,
                ).let { swirl ->
                    TrackVoice(
                        engineEdm = swirl, engineSpace = swirl,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED),
                        density = 0.2f,
                        barStrategy = BarStrategy.INDEPENDENT,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                    )
                },
            ),

            band = Band(
                members = listOf(
                    BandMember(name = "Drummer", tracks = listOf(0, 1, 2), alwaysActive = true),
                    BandMember(name = "Bassist", tracks = listOf(3), loudness = 0.8f),
                    // Both guitars move as one member: they read the same lick a third apart,
                    // so handing the solo to "Guitarist" hands it to the pair.
                    BandMember(name = "Guitarist", tracks = listOf(4, 6), loudness = 0.85f),
                    BandMember(name = "Haze", tracks = listOf(5, 7)),
                ),
                handoffMatrix = bandMatrix(
                    //            DRUM  BASS  GTR   HAZE
                    "Drummer" to row(0.0f, 0.2f, 0.6f, 0.2f),   // Drummer hands to Guitarist mostly
                    "Bassist" to row(0.1f, 0.0f, 0.7f, 0.2f),   // Bassist -> Guitarist
                    "Guitarist" to row(0.1f, 0.5f, 0.0f, 0.4f), // Guitarist -> Bassist (the trade!)
                    "Haze" to row(0.1f, 0.3f, 0.6f, 0.0f),      // Haze -> Guitarist
                ),
                // No pull-in guidance in the source draft; mirrors handoffMatrix's cast
                // relationships until balch hand-tunes a distinct pull-in feel by ear.
                pullInMatrix = bandMatrix(
                    //            DRUM  BASS  GTR   HAZE
                    "Drummer" to row(0.0f, 0.2f, 0.6f, 0.2f),
                    "Bassist" to row(0.1f, 0.0f, 0.7f, 0.2f),
                    "Guitarist" to row(0.1f, 0.5f, 0.0f, 0.4f),
                    "Haze" to row(0.1f, 0.3f, 0.6f, 0.0f),
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
                outroIndex = 5,
                lengthSeconds = 360..420,
                sections = listOf(
                    // 0 INTRO — the bass alone states the lament; band swells in
                    Section(
                        name = "intro",
                        barsMin = 2, barsMax = 2,
                        macroOverrides = MacroOverrides(energy = 0.7f, space = 1.2f),
                        trackOverrides = mapOf(
                            0 to TrackSectionOverride(density = 0.15f),
                            1 to TrackSectionOverride(density = 0f),
                            2 to TrackSectionOverride(density = 0f),
                            4 to TrackSectionOverride(density = 0f),  // lead guitar out
                            5 to TrackSectionOverride(density = 0f),
                            6 to TrackSectionOverride(density = 0f),  // harmony guitar out
                            7 to TrackSectionOverride(density = 0f),
                        ),
                        transitions = listOf(SectionTransition(1, 1f, transitionBars = 1)),
                    ),
                    // 1 VERSE — slow full-band groove under the wah; frames the jam
                    Section(
                        name = "verse",
                        barsMin = 2, barsMax = 4,
                        transitions = listOf(SectionTransition(2, 1f, transitionBars = 1)),
                    ),
                    // 2 RISE — the band leans in over the descent. Energy 0.55*1.2 = 0.66
                    // crosses the texture-curve knee, so the harmony guitar wakes HERE
                    // (it whispers in the verse). Solo probability 0.95 because the roll
                    // happens ONCE per section entry — 0.55 left ~45% of jams soloist-free.
                    Section(
                        name = "rise",
                        barsMin = 6, barsMax = 8,
                        macroOverrides = MacroOverrides(
                            energy = 1.2f, complexity = 1.15f, space = 0.9f, mood = 1.05f,
                        ),
                        soloMode = SoloMode.LickBuilder(probability = 0.95f, mutationRate = 0.5f),
                        transitions = listOf(SectionTransition(3, 1f, transitionBars = 2)),
                    ),
                    // 3 VAMP — the harmony freezes on i (Dm) while the bass keeps the
                    // authored descent: ostinato floor under a frozen sky. Deliberately
                    // NO chordFollow override on the bass — trigger-time transposition
                    // would flatten a bassist's LickBuilder solo to roots. The descent
                    // departs organically when the Bassist trades and mutates it.
                    // jamCarry: the solo that started in RISE walks in mid-stride.
                    Section(
                        name = "vamp",
                        barsMin = 8, barsMax = 10,
                        jamCarry = true,
                        customProgression = chords(0),
                        chordsPerBar = 1,
                        macroOverrides = MacroOverrides(
                            energy = 1.25f, complexity = 1.3f, space = 1.1f, mood = 1.1f,
                        ),
                        soloMode = SoloMode.LickBuilder(probability = 0.95f, mutationRate = 0.6f),
                        transitions = listOf(SectionTransition(4, 1f, transitionBars = 2)),
                    ),
                    // 4 PEAK — free improv over the vamp: SoloMode.Jam generates a
                    // chord-anchored line each bar and hands 70% of its interval
                    // character to the next soloist. complexity 0.4*1.8 = 0.72 plus
                    // energy 0.55*1.5 = 0.83 opens track 7's fx gate, so the swirl
                    // joins exactly at the climax. Spurts (lick mutation x3, then
                    // reeled back) fire often via the tension override; innerBars
                    // stays 8 so the sawtooth peak (0.875) still crosses the 0.85
                    // auto-spurt threshold. outerBars is meaningless inside a
                    // single visit, so disabled.
                    Section(
                        name = "peak",
                        barsMin = 8, barsMax = 12,
                        jamCarry = true,
                        customProgression = chords(0),
                        chordsPerBar = 1,
                        macroOverrides = MacroOverrides(
                            energy = 1.5f, complexity = 1.8f, space = 0.8f, mood = 1.15f,
                        ),
                        soloMode = SoloMode.Jam(probability = 1.0f),
                        tensionOverride = TensionProfile(
                            innerBars = 8,
                            outerBars = 0,
                            outerDepth = 0f,
                            volume = 0.35f,
                            timing = 0.15f,
                            spurtChance = 0.2f,
                        ),
                        // Hard cut home: the biggest bar slams into the slow verse.
                        transitions = listOf(SectionTransition(1, 1f, transitionBars = 0)),
                    ),
                    // 5 OUTRO — the lament dissolves (terminal; reached only when armed)
                    Section(
                        name = "outro",
                        barsMin = 2, barsMax = 2,
                        macroOverrides = MacroOverrides(energy = 0.55f, space = 1.5f),
                        transitions = emptyList(),
                    ),
                ),
            ),
        )
    }
}
