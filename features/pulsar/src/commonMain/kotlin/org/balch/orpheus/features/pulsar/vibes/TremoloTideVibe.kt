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
import org.balch.orpheus.features.pulsar.models.CompingFills
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.Evolution
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.FillType
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.HalfLick
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.PitchEvolution
import org.balch.orpheus.features.pulsar.models.ProgressionAnchor
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RhythmicEvolution
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
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Tremolo Tide — surf-noir slow burn in B minor.
 *
 * Sparse half-time drums under a brooding pedal-tone bass and a tremolo'd clean
 * lead that drifts over a three-chord modal cycle (i — bVII — iv). Cathedral
 * reverb and dotted-eighth delay swallow everything. The song breathes more
 * than it plays — long pads, lots of empty bars, occasional resonant accents.
 * Verse stays subdued; chorus lifts on a held bVII before falling back home.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class TremoloTideVibe : VibeProvider {
    override val name: String = "Tremolo Tide"
    // i — bVII — iv  (Bm — A — E in B minor). One chord per bar, 4-bar phrase
    // with the last bar resolving back to the tonic.
    private val verseProgression = chords(0, 6, 3, 0)

    // Chorus lifts by lingering on the bVII (the "what a wicked..." sustain)
    // before exhaling back down through iv to home.
    private val chorusProgression = chords(0, 6, 6, 3)

    private val chordsPerBar = 1

    // Slow tide swells. Every transition gets a long ramp — this vibe has no
    // hard cuts, only fades into and out of energy.
    private val tideRiseBars = 4   // verse -> chorus, intro -> verse
    private val tideFallBars = 6   // chorus -> verse / breakdown — slow exhale
    private val tideOutBars = 8    // breakdown drift — almost gone

    private val sectionList by lazy {
        listOf(
            // 0: intro — just bass + pads + lead. Drums sit out.
            Section(
                name = "intro",
                barsMin = 4, barsMax = 8,
                transitions = listOf(
                    SectionTransition(
                        targetIndex = 1,
                        weight = 1.0f,
                        transitionBars = tideRiseBars
                    ),
                ),
                macroOverrides = MacroOverrides(
                    energy = 0.25f, complexity = 0.4f, space = 1.4f,
                ),
            ),
            // 1: verse — full band, subdued. The pocket.
            //    Track 5 (chordal pad) gets promoted to a sustained DRONE
            //    here — held bedrock under the lead. Other sections leave
            //    the override off, so the pad reverts to its light comping
            //    base.
            Section(
                name = "verse",
                barsMin = 8, barsMax = 16,
                transitions = listOf(
                    SectionTransition(
                        targetIndex = 2,
                        weight = 0.55f,
                        transitionBars = tideRiseBars
                    ),
                    SectionTransition(
                        targetIndex = 3,
                        weight = 0.30f,
                        transitionBars = tideFallBars
                    ),
                    SectionTransition(
                        targetIndex = 4,
                        weight = 0.15f,
                        transitionBars = tideOutBars
                    ),
                ),
                recencyDecay = 0.5f,
                macroOverrides = null,  // verse is the baseline
                // Track 5 is Chordal, so its rhythm comes from compingStyle —
                trackOverrides = mapOf(
                    5 to TrackSectionOverride(
                        envelopeProfile = EnvelopeProfile.DRONE,
                    ),
                ),
            ),
            // 2: chorus — the swell. Lift on bVII; everything wider/wetter.
            Section(
                name = "chorus",
                barsMin = 6, barsMax = 10,
                transitions = listOf(
                    SectionTransition(
                        targetIndex = 1,
                        weight = 0.50f,
                        transitionBars = tideFallBars
                    ),
                    SectionTransition(
                        targetIndex = 3,
                        weight = 0.30f,
                        transitionBars = tideRiseBars
                    ),
                    SectionTransition(
                        targetIndex = 4,
                        weight = 0.20f,
                        transitionBars = tideOutBars
                    ),
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 1.30f, complexity = 1.20f, space = 1.20f, mood = 1.30f,
                ),
                customProgression = chorusProgression,
                chordsPerBar = chordsPerBar,
                // No compingStyle override — let track 5 keep PAD wash and track 7
                // keep its REGGAE_SKANK accent, so the bed stays under the lift.
                compingInversion = SectionInversion.OPEN_VOICING,
                // Pedal the tremolo lead's held-tonic hook (track 4) on the tonic
                // here rather than FOLLOWing i — bVII — bVII — iv. With FOLLOW the
                // chord offset is added then folded into the lead's lowest octave,
                // so the bVII (+10) and iv (+5) leaps fold to a lurching up-a-4th /
                // down-a-4th contour — the "disjointed" hang. Anchoring it FIXED
                // keeps the sustain stable and in-key while the bass + chordal pad
                // (track 5) + resonant accent carry the harmonic motion underneath,
                // reading more like the intended wicked-game hold than a lurch.
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
                ),
                // The lift arc: late-peaking shimmer that rides the held bVII.
                // Higher evolution probabilities push timbre/morph/harmonics
                // to wander wider during the chorus than the verse baseline.
                // octaveShift + chromaticPassing inject upward melodic motion;
                // a touch of spurtChance lets the lead accent the hang on bVII.
                // Faster releaseSpeed snaps tension back as we exit toward verse.
                tensionOverride = TensionProfile(
                    spurtChance = 0.18f,
                    innerBars = 8, outerBars = 24, outerDepth = 0.5f,
                    volume = 0.40f,
                    tonal = TonalTension(
                        octaveShift = true,
                        chromaticPassing = 0.15f,
                        halfLick = HalfLick.JAM,
                    ),
                    timing = 0.10f,
                    evolution = EvolutionTension(
                        timbreLow = 0.40f, timbreHigh = 0.75f, timbreProbability = 0.75f,
                        attackPoint = 0.70f,    // peak late — earned, not handed
                        releaseSpeed = 0.50f,   // snap back home for verse handoff
                    ),
                ),
            ),
            // 3: solo — lead-and-pads jam. Bass + sparse drums hold pocket.
            Section(
                name = "solo",
                barsMin = 8, barsMax = 16,
                transitions = listOf(
                    SectionTransition(
                        targetIndex = 2,
                        weight = 0.45f,
                        transitionBars = tideRiseBars
                    ),
                    SectionTransition(
                        targetIndex = 1,
                        weight = 0.35f,
                        transitionBars = tideFallBars
                    ),
                    SectionTransition(
                        targetIndex = 4,
                        weight = 0.20f,
                        transitionBars = tideOutBars
                    ),
                ),
                recencyDecay = 0.4f,
                macroOverrides = MacroOverrides(
                    energy = 0.85f, complexity = 1.35f, space = 1.40f, mood = 1.20f,
                ),
                soloMode = SoloMode.Jam(probability = 0.75f),
            ),
            // 4: breakdown / outro drift — fade toward silence.
            Section(
                name = "breakdown",
                barsMin = 6, barsMax = 10,
                transitions = listOf(
                    SectionTransition(
                        targetIndex = 1,
                        weight = 0.65f,
                        transitionBars = tideRiseBars
                    ),
                    SectionTransition(
                        targetIndex = 2,
                        weight = 0.35f,
                        transitionBars = tideRiseBars
                    ),
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 0.30f, complexity = 0.50f, space = 1.60f, mood = 0.85f,
                ),
                chordFollow = ChordFollow.FIXED,
                // Held-breath arc: nothing sparks, nothing develops. Tension
                // sits flat for almost the entire section, then a slow late
                // attack signals the rise out. Steady timing + minimal
                // evolution keep the section perfectly still.
                tensionOverride = TensionProfile(
                    spurtChance = 0.02f,            // almost never accent
                    innerBars = 16, outerBars = 32, outerDepth = 0.3f,
                    volume = 0.20f,
                    tonal = TonalTension(
                        octaveShift = false,
                        chromaticPassing = 0.02f,
                        halfLick = HalfLick.JAM,
                    ),
                    timing = 0.05f,                 // steady, almost frozen
                    evolution = EvolutionTension(
                        timbreLow = 0.30f, timbreHigh = 0.45f, timbreProbability = 0.30f,
                        attackPoint = 0.85f,        // peak right at the exit edge
                        releaseSpeed = 0.15f,       // slow exhale
                    ),
                ),
                trackOverrides = mapOf(
                    5 to TrackSectionOverride(
                        compingStyle = CompingStyle.ROCK_DOWNBEATS,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        volume = .5f,
                        holdProbability = 0f
                    ),
                ),
            ),
        )
    }

    override val vibe: Vibe by lazy {

        Vibe(
            name = name,
            album = Album.STEALTH,
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = sectionList.lastIndex,
                sections = sectionList
            ),
            bpm = 110f,
            envelopeType = EnvelopeType.BLEND,  // long pads need TIDES tail; drums need AD attack
            rootNote = RootNote.B,
            scaleType = ScaleType.MINOR,
            // ────────────────────────────────────────────────────────────────────
            // The lead lick — the signature melodic figure that drifts over the
            // chord progression. The current placeholder is a held tonic with one
            // accent step; it will play but won't *feel* like the song. Replace
            // the steps below with the melody you hear in your head:
            //   - scaleDegree 0 = B, 2 = D, 3 = E, 4 = F#, 5 = G, 6 = A, 7 = octave B
            //   - negative degrees go below the root
            //   - duration is in beats (0.25 = 16th, 0.5 = 8th, 1.0 = quarter, 2.0 = half)
            //   - velocity 0..1 — accents around 0.85, ghost notes around 0.4-0.5
            // Keep total step duration <= loopLength; the rest fills with silence.
            // ────────────────────────────────────────────────────────────────────
            lick = Lick(
                steps = listOf(
                    LickStep(scaleDegree = 0, duration = 2f, velocity = 1f, glideRate = .5f),
                    LickStep(scaleDegree = 7, duration = 1.0f, velocity = 0.7f, glideRate = .35f),
                    LickStep(scaleDegree = 5, duration = .25f, velocity = 0.7f),
                    LickStep(scaleDegree = -2, duration = .25f, velocity = 0.8f),
                    LickStep(scaleDegree = 3, duration = .25f, velocity = 0.8f),
                    LickStep(scaleDegree = 2, duration = .25f, velocity = 0.4f),
                    LickStep(scaleDegree = 0, duration = .5f, velocity = 0.7f),
                    LickStep(scaleDegree = -2, duration = .25f, velocity = 0.7f),
                    LickStep(scaleDegree = 0, duration = .125f, velocity = 0.45f),
                    LickStep(scaleDegree = 0, duration = .125f, velocity = 0.9f),
                ),
                loopLength = 32,  // 8 bars (32 beats / 4) — 5 beats of melody, then 27 beats of rest
            ),
            lickMutation = 0.55f,
            band = Band(
                members = listOf(
                    BandMember(
                        "Drummer", listOf(0, 1, 2), alwaysActive = true,
                        creativity = 0.20f
                    ),
                    BandMember(
                        "Bassist", listOf(3),
                        creativity = 0.30f
                    ),
                    BandMember(
                        "Lead", listOf(4),
                        creativity = 0.65f
                    ),
                    BandMember(
                        "Pads", listOf(5, 6, 7),
                        creativity = 0.55f
                    ),
                ),
                handoffMatrix = bandMatrix(
                    //            DRUM   BASS   LEAD   PADS
                    "Drummer" to row(0.00f, 0.20f, 0.55f, 0.25f),
                    "Bassist" to row(0.10f, 0.00f, 0.55f, 0.35f),
                    "Lead"    to row(0.05f, 0.30f, 0.00f, 0.65f),  // lead loves to hand to pads (the wash)
                    "Pads"    to row(0.05f, 0.20f, 0.65f, 0.10f),
                ),
                pullInMatrix = bandMatrix(
                    "Drummer" to row(0.00f, 0.30f, 0.25f, 0.30f),
                    "Bassist" to row(0.20f, 0.00f, 0.40f, 0.35f),
                    "Lead"    to row(0.15f, 0.40f, 0.00f, 0.55f),
                    "Pads"    to row(0.10f, 0.30f, 0.50f, 0.20f),
                ),
                pullInBarsMin = 4, pullInBarsMax = 8,    // long pull-ins — slow pacing
                barsPerLeadMin = 6, barsPerLeadMax = 12,
            ),
            energy = 0.40f,         // subdued by default — let the user dial up
            complexity = 0.25f,     // simple, repetitive
            space = 0.75f,          // very wet
            mood = 0.25f,           // dark — user will tweak
            deep = 0.65f,           // generous effects send
            genre = GenreProfile(
                swingAmount = 0.04f,                         // mostly straight, tiny pocket
                ghostProbability = 0.12f,                    // sparse drums; few ghosts
                noteRangeLow = 33,                           // A1 — pedal bass territory
                noteRangeHigh = 72,                          // C5 — lead ceiling
                rhythmDensity = RhythmPattern.SPARSE.density,
                progressionStyle = ProgressionStyle.MODAL,   // no V resolution — the wicked-game move
                chordsPerBar = chordsPerBar,
                customProgression = verseProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_4,   // reset every 4-bar phrase
            progressionDriftRange = 0.08f,                    // very tight — keep the cycle locked
            tracks = listOf(
                // 0: kick — sparse half-time. Boom on 1, ghost on 3.
                OrpheusEngine(
                    engineId = OrpheusEngineId.BD,
                    volume = 0.70f,
                    reverbSend = 0.1f,
                ).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.18f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 1: snare — soft brushes-feel backbeat on 2 and 4.
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD,
                    volume = 0.50f,
                    harmonics = 0.30f,
                    timbre = 0.25f,    // duller, more brush-like
                    reverbSend = 0.40f,    // wet snare — Lynch-y reverb tail
                    reverbBrightness = 0.55f,
                ).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.05f,
                        density = 0.22f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 2: hat / shaker — soft 8th feel, low velocity.
                OrpheusEngine(
                    engineId = OrpheusEngineId.HH,
                    volume = 0.35f,
                    reverbSend = 0.20f,
                ).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.10f,
                        density = 0.30f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 3: bass — sustained pedal note that glides between chord roots.
                //    Long notes, glideRate high, ROOT_ONLY locks to chord root.
                OrpheusEngine(
                    engineId = OrpheusEngineId.VCF,
                    volume = 0.78f,
                    harmonics = 0.25f,
                    timbre = 0.30f,
                    morph = 0.40f,
                    noteRangeLow = 33,
                    noteRangeHigh = 47,        // strictly low register
                    holdProbability = 0.85f,    // sustain into next chord
                    holdLengthMin = 4,
                    holdLengthMax = 8,
                    glideRate = 0.55f,          // smooth slide between roots — the signature pedal feel
                    reverbSend = 0.15f,
                    delaySend = 0.15f,
                    reverbBrightness = 0.30f,   // dark bass tail
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.PD),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.00f,
                        density = 0.30f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 4: tremolo lead — the iconic surf-noir voice. Plays the lick.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX,
                    volume = 0.25f,
                    morph = .74f,
                    timbre = .67f,
                    harmonics = 0.492f,
                    harmonicsMacroRange = 0.04f,    // mood walks ±~1 patch around base
                    noteRangeLow = 55,
                    noteRangeHigh = 76,
                    holdProbability = 0.55f,
                    holdLengthMin = 2,
                    holdLengthMax = 6,
                    glideRate = 0.20f,          // slides between notes — pedal-steel feel
                    reverbSend = 0.5f,
                    delaySend = 0.50f,          // dotted-eighth wash
                    reverbBrightness = 0.78f,   // shimmery top
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead.copy(
                            engineId = OrpheusEngineId.DX2,
                        ),
                        role = TrackRole.Melodic(lickMode = LickMode.Fill),
                        pan = 0.15f,
                        density = 0.20f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.MUTATE,
                        // Section-aware lick evolution: bigger Markov pitch drift +
                        // aggressive rhythmic transforms at peak tension. evolutionWeight
                        // gates intensity by tension — quiet at section start, gnarliest
                        // at section end / chorus / solo.
                        evolution = Evolution(
                            pitch = PitchEvolution.Contour(driftRange = 0.25f),
                            rhythmic = RhythmicEvolution(tensionResponse = 0.7f),
                        ),
                        evolutionWeight = 0.8f,
                    )
                },
                // 5: chordal pad — syncopated stabs by default; the verse promotes
                //    this to a sustained drone via TrackSectionOverride below.
                //    The chord engine picks up the progression; ENS in the space
                //    slot keeps a string-ensemble shimmer when energy is low.
                //    JAZZ_COMP keeps the bed quiet outside the verse — verse
                //    flips it to PAD for held bedrock.
                OrpheusEngine(
                    engineId = OrpheusEngineId.CHD,
                    volume = 0.40f,
                    modLfoRate = 0.05f,
                    modLfoDepth = 0.35f,
                    modLfoShape = 0.4f,
                    modLfoCoupling = 0.3f,
                    holdProbability = 0.25f,
                    holdLengthMin = 2,
                    holdLengthMax = 12,
                    noteRangeLow = 48,
                    noteRangeHigh = 67,
                    reverbSend = 0.75f,
                    delaySend = 0.25f,
                    reverbBrightness = 0.70f,
                    glideRate = 0.50f,
                ).let { chord ->
                    TrackVoice(
                        engineEdm = chord,
                        engineSpace = chord.copy(engineId = OrpheusEngineId.ENS),
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.GOSPEL_STABS,
                                arpSpeed = 1.0f,
                                fills = CompingFills(
                                    everyNBars = 16,
                                    fillType = FillType.STAB_FLURRY,
                                ),
                            ),
                        ),
                        pan = -0.25f,
                        density = 0.40f,
                        envelopeProfile = EnvelopeProfile.EFFECT,  // ducks under solos; verse swaps to DRONE
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 6: counter-pad (strings) — sustained background wash, NOT a melody
                // doubler. Removed lickMode = Fill so the strings don't shadow the
                // track 4 lead lick; instead they hang on long-held notes from the
                // generated pattern, contributing the cathedral atmosphere rather
                // than competing for the melodic foreground.
                OrpheusEngine(
                    engineId = OrpheusEngineId.STR,
                    volume = 0.42f,
                    modLfoRate = 0.07f,
                    modLfoDepth = 0.5f,
                    modLfoShape = 0.5f,
                    modLfoCoupling = 0.25f,
                    holdProbability = 0.80f,
                    holdLengthMin = 6,
                    holdLengthMax = 16,
                    noteRangeLow = 55,
                    noteRangeHigh = 74,
                    reverbSend = 0.80f,
                    delaySend = 0.30f,
                    reverbBrightness = 0.75f,
                    glideRate = 0.45f,
                ).let { strings ->
                    TrackVoice(
                        engineEdm = strings,
                        engineSpace = strings,
                        role = TrackRole.Melodic(),  // was lickMode = Fill — see comment above
                        pan = 0.30f,
                        density = 0.12f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
                // 7: resonant accent (modal) — sparse rings/pings, NOT a rhythm comp.
                // Was REGGAE_SKANK (wrong genre for surf-noir — skanks are upbeat off-
                // beat strums); switched to TrackRole.Melodic with very low density so
                // the resonator fires occasional bell-like accents in its high register
                // rather than driving a rhythmic pattern.
                OrpheusEngine(
                    engineId = OrpheusEngineId.MOD,
                    volume = 0.25f,
                    harmonics = 0.55f,
                    timbre = 0.40f,
                    morph = 0.50f,
                    modLfoRate = 0.04f,
                    modLfoDepth = 0.4f,
                    modLfoShape = 0.3f,
                    modLfoCoupling = 0.2f,
                    holdProbability = 0.40f,
                    holdLengthMin = 3,
                    holdLengthMax = 10,
                    noteRangeLow = 60,
                    noteRangeHigh = 84,
                    reverbSend = 0.50f,
                    delaySend = 0.25f,
                    reverbBrightness = 0.45f,
                ).let { accent ->
                    TrackVoice(
                        engineEdm = accent,
                        engineSpace = accent,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED),
                        pan = 0.00f,
                        density = 0.08f,  // very sparse — occasional ring/ping
                        envelopeProfile = EnvelopeProfile.DRONE,
                        macroMap = TrackMacroMap.WILD,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            stepCount = 32,
            tension = TensionProfile(
                spurtChance = 0.06f,            // rare — this vibe doesn't spike
                innerBars = 8,                  // long phrases
                outerBars = 32,                 // very long arc
                outerDepth = 0.4f,
                volume = 0.30f,
                tonal = TonalTension(octaveShift = false, chromaticPassing = 0.05f),
                timing = 0.10f,                 // very steady
                evolution = EvolutionTension(
                    timbreLow = 0.30f, timbreHigh = 0.55f, timbreProbability = 0.5f,
                    attackPoint = 0.65f, releaseSpeed = 0.25f,    // slow release
                ),
            ),
            effects = VibeEffects(
                delayTimeA = 0.25f,           // 16th
                delayTimeB = 0.375f,          // dotted-8th — the iconic guitar wash
                delayFeedback = 0.65f,        // long tail
                delayDamping = 0.3f,         // dark repeats
                reverbSize = 0.85f,           // cathedral
                reverbDamping = 0.45f,
                reverbBrightness = 0.72f,     // bright top despite dark mood
                deepFloor = 0.3f,
            ),
        )
    }
}
