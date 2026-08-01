package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.ArpDirection
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
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.FillType
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
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Swamp Swagger — lazy, swampy bluesy rock with a sliding lead and a shimmering
 * vibraphone intro.
 *
 * Mid-tempo (~122 BPM) major-keyed blues-rock swagger: a swung backbeat that
 * sits just behind the grid, a driving root-pulse bass, and a gritty slide
 * lead that hammers the tonic then answers with the flat-VII turnaround. A motor-
 * vibrato vibraphone opens the tune and a rolling blues piano fills the pocket.
 * Harmony is a I–IV–bVII vamp (B Mixolydian: degree 0 = I, 3 = IV, 6 = bVII), so
 * the "blues" lives in the groove (swing + shuffle comping) rather than in bent
 * thirds the scale can't spell. Builds through a 6-section arc to a climactic
 * stomping outro.
 */
// PARKED — intentionally NOT contributed to the VibeProvider set, so Swamp Swagger
// does not appear in the picker (WIP, not shipping yet). To re-enable, restore:
//   import dev.zacsweers.metro.{Inject, ContributesIntoSet, binding}
//   import org.balch.orpheus.core.di.FeatureScope
//   @Inject @ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class SwampSwaggerVibe : VibeProvider {
    override val name: String = "Swamp Swagger"

    // I–IV–bVII vamp in B Mixolydian. Hang on the tonic, lean into the IV (E),
    // shove the bVII (A) for the rock turnaround, land home.
    //   B – B – E – B – B – A – E – B
    private val mainProgression = chords(0, 0, 3, 0, 0, 6, 3, 0)

    // Per-edge transition ramps, named for the role they serve (DogHouse precedent).
    private val liftBars = 2     // climb between adjacent-energy sections
    private val dropBars = 3     // swampy exhale out of a high-energy section
    private val bigLiftBars = 4  // the breakdown -> chorus anticipation build

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            bpm = 122f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.B,
            scaleType = ScaleType.MIXOLYDIAN,

            // ────────────────────────────────────────────────────────────────
            // THE HOOK — the slide-guitar riff. Played by track 4
            // (lickMode = Fill) with a high glideRate so the steps slur into each
            // other = the slide. Degrees are B Mixolydian
            // (0=B,1=C#,2=D#,3=E(IV),4=F#,5=G#,6=A(bVII));
            // negatives drop below the root.
            //
            // State the root, climb into the IV color, reach up to
            // F#, then slide back home with a long landing — riff-then-breathe
            // (notes total 4.5 beats, loopLength 8 leaves space for the "vocal").
            // Swap the degrees/durations below to reshape the hook.
            // ────────────────────────────────────────────────────────────────
            lick = Lick(
                steps = listOf(
                    // bar 1 — state it
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),  // B  (downbeat hit)
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.82f),  // B
                    LickStep(scaleDegree = 2, duration = 0.25f, velocity = 0.85f), // D# (step up)
                    LickStep(scaleDegree = 3, duration = 0.75f, velocity = 0.92f), // E  (IV color, held)
                    // bar 2 — answer & slide home
                    LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.90f),  // F# (reach up)
                    LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.85f),  // E  (slide down)
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.80f),  // D#
                    LickStep(scaleDegree = 0, duration = 1.0f, velocity = 0.90f),  // B  (land, long)
                ),
                loopLength = 8, // 2 bars; ~3.5 beats of rest = the riff breathes
            ),
            lickMutation = 0.40f, // holds its shape in the groove; solos develop it

            band = Band(
                members = listOf(
                    // Drums + tambourine anchor the pocket and never duck.
                    BandMember(
                        "Drummer", listOf(0, 1, 2, 7), alwaysActive = true,
                        loudness = 0.7f, creativity = 0.25f,
                    ),
                    BandMember(
                        "Bassist", listOf(3),
                        loudness = 0.8f, creativity = 0.40f,
                    ),
                    // The slide is the star — highest creativity, wins the lead.
                    BandMember(
                        "Slide", listOf(4),
                        loudness = 0.7f, creativity = 0.55f,
                    ),
                    BandMember(
                        "Keys", listOf(5, 6),
                        loudness = 0.5f, creativity = 0.50f,
                    ),
                ),
                handoffMatrix = bandMatrix(
                    //            DRUM  BASS  SLIDE KEYS
                    "Drummer" to row(0.00f, 0.25f, 0.50f, 0.25f),
                    "Bassist" to row(0.15f, 0.00f, 0.55f, 0.30f),
                    "Slide"   to row(0.15f, 0.35f, 0.00f, 0.50f),
                    "Keys"    to row(0.15f, 0.30f, 0.55f, 0.00f),
                ),
                pullInMatrix = bandMatrix(
                    //            DRUM  BASS  SLIDE KEYS
                    "Drummer" to row(0.00f, 0.30f, 0.30f, 0.20f),
                    "Bassist" to row(0.25f, 0.00f, 0.50f, 0.25f),
                    "Slide"   to row(0.15f, 0.45f, 0.00f, 0.40f),
                    "Keys"    to row(0.15f, 0.30f, 0.45f, 0.00f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 4,
                barsPerLeadMin = 4, barsPerLeadMax = 8,
            ),

            energy = 0.65f,
            complexity = 0.45f,
            space = 0.35f,  // 60s-rock dry, but roomy
            mood = 0.55f,   // swaggering, swampy-bright (not as dark as a minor blues)
            deep = 0.30f,   // dryish / punchy

            genre = GenreProfile(
                swingAmount = 0.14f,     // lazy swung backbeat, just behind the grid
                ghostProbability = 0.22f,
                noteRangeLow = 35,       // B1
                noteRangeHigh = 64,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.BLUES, // matrix stays bluesy
                chordsPerBar = 1,        // slow vamp — hang on the chord
                customProgression = mainProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_8,
            progressionDriftRange = 0.12f, // subtle — preserve the vamp shape

            tracks = listOf(
                // 0: Kick — driving pulse
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.85f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.45f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 1: Snare — swung backbeat, room snap
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD,
                    volume = 0.62f,
                    timbre = 0.55f,
                    reverbSend = 0.15f,
                ).let { snare ->
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
                // 2: Hat — lazy 8ths
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.55f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f,
                        density = 0.52f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 3: Bass — driving root pulse. WSH grit on energy, STR warmth on space.
                //    ROOT_ONLY snaps it to the chord root; a touch of glide for the
                //    occasional Wyman-style slur. REPEAT keeps the pocket tight.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.78f,
                    harmonics = 0.55f,
                    noteRangeLow = 33,
                    noteRangeHigh = 50,
                    reverbBrightness = 0.28f,
                    glideRate = 0.12f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.STR),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.00f,
                        density = 0.50f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 4: Slide lead — THE hook. Gritty WSH on energy, FM on space.
                //    High glideRate is the slide. Plays the vibe lick (Fill).
                //    chordFollow = FOLLOW so the riff transposes across the I–IV–bVII
                //    like a 12-bar blues figure; the chorus pedals it FIXED (see
                //    arrangement) so the bVII leap doesn't lurch at the peak.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.66f,
                    harmonics = 0.72f, // gritty
                    timbre = 0.66f,
                    morph = 0.50f,
                    noteRangeLow = 50,
                    noteRangeHigh = 72,
                    reverbSend = 0.20f,
                    delaySend = 0.18f,
                    glideRate = 0.25f, // the slide
                ).let { slide ->
                    TrackVoice(
                        engineEdm = slide,
                        engineSpace = slide.copy(engineId = OrpheusEngineId.CSAW),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FOLLOW,
                            lickMode = LickMode.Fill,
                        ),
                        pan = 0.18f,
                        density = 0.30f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 5: Piano — rolling blues comping. DX2 grand on energy, e.piano on space.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX2,
                    volume = 0.45f,
                    harmonics = 0.276f, // DX2 idx 9 "Grd Piano" (auto-pinned)
                    noteRangeLow = 48,
                    noteRangeHigh = 72,
                    reverbSend = 0.25f,
                    delaySend = 0.10f,
                ).let { piano ->
                    TrackVoice(
                        engineEdm = piano,
                        engineSpace = piano.copy(harmonics = 0.0f), // DX2 idx 0 "E piano 1"
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.BLUES_SHUFFLE,
                                arpMode = ArpMode.AUTO,
                                arpSpeed = 0.12f,
                                arpDirection = ArpDirection.UP_DOWN,
                                sectionInversion = SectionInversion.FIRST_INVERSION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.2f,
                                    ghostProbability = 0.2f,
                                    octaveJumpProbability = 0.3f,
                                    extensionProbability = 0.3f,
                                ),
                                fills = CompingFills(
                                    everyNBars = 8,
                                    fillType = FillType.TURNAROUND,
                                    skipProbability = 0.15f,
                                ),
                            ),
                        ),
                        pan = -0.22f,
                        density = 0.22f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 6: Vibraphone — the intro shimmer. DX2 "Vibe 1" with motor-vibrato
                //    (slow LFO) and a wet shimmer tail. Sparse by default; the intro
                //    section brings it forward via trackOverrides.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX2,
                    volume = 0.40f,
                    harmonics = 0.551f, // DX2 idx 17 "Marimba" (auto-pinned, ear-tuned)
                    modLfoRate = 0.10f,
                    modLfoDepth = 0.30f, // motor vibrato
                    modLfoShape = 0.40f,
                    modLfoCoupling = 0.25f,
                    holdProbability = 0.6f,
                    holdLengthMin = 4,
                    holdLengthMax = 12,
                    reverbSend = 0.45f,
                    delaySend = 0.20f,
                    noteRangeLow = 60,
                    noteRangeHigh = 84,
                    reverbBrightness = 0.70f, // shimmery
                ).let { vibes ->
                    TrackVoice(
                        engineEdm = vibes,
                        engineSpace = vibes,
                        role = TrackRole.Melodic(),
                        pan = 0.28f,
                        density = 0.12f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
                // 7: Tambourine / swampy percussion. MOD metallic on energy, PAR
                //    scatter on space. Spreads across bars; never repeats exactly.
                OrpheusEngine(
                    engineId = OrpheusEngineId.MOD,
                    volume = 0.38f,
                    modLfoRate = 0.08f,
                    modLfoDepth = 0.4f,
                    modLfoShape = 0.6f,
                    modLfoCoupling = 0.3f,
                    holdProbability = 0.3f,
                    holdLengthMin = 2,
                    holdLengthMax = 6,
                    reverbSend = 0.22f,
                    delaySend = 0.10f,
                    noteRangeLow = 48,
                    noteRangeHigh = 72,
                    reverbBrightness = 0.55f,
                ).let { perc ->
                    TrackVoice(
                        engineEdm = perc,
                        engineSpace = perc.copy(engineId = OrpheusEngineId.PAR),
                        role = TrackRole.Percussive,
                        pan = 0.32f,
                        density = 0.28f,
                        envelopeProfile = EnvelopeProfile.WILD,
                        macroMap = TrackMacroMap.WILD,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            stepCount = 32,

            tension = TensionProfile(
                innerBars = 8,
                outerBars = 32,
                outerDepth = 0.6f,
                volume = 0.30f,
                tonal = TonalTension(octaveShift = true, chromaticPassing = 0.12f),
                timing = 0.25f,
                evolution = EvolutionTension(
                    timbreLow = 0.25f, timbreHigh = 0.60f, timbreProbability = 0.7f,
                    attackPoint = 0.6f, releaseSpeed = 0.35f, // peak past midpoint = a build
                ),
                spurtChance = 0.12f,
            ),

            effects = VibeEffects(
                delayTimeA = 0.25f,
                delayTimeB = 0.375f,
                delayFeedback = 0.32f,
                delayDamping = 0.5f,
                reverbSize = 0.45f,       // room / small hall
                reverbDamping = 0.5f,
                reverbBrightness = 0.5f,  // warm-ish plate
            ),

            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = 5,
                sections = listOf(
                    // 0: intro — vibraphone + piano + light drums, building. The slide
                    //    stays out (muted via trackOverride); vibes come forward.
                    Section(
                        name = "intro",
                        barsMin = 2, barsMax = 4,
                        transitions = listOf(
                            SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = liftBars),
                        ),
                        macroOverrides = MacroOverrides(energy = 0.5f, complexity = 0.4f, space = 0.6f, mood = 0.9f),
                        trackOverrides = mapOf(
                            4 to TrackSectionOverride(density = 0.0f),                 // slide silent in intro
                            6 to TrackSectionOverride(density = 0.34f, volume = 0.55f), // vibes lead the intro
                        ),
                    ),
                    // 1: verse — full band groove, the baseline.
                    Section(
                        name = "verse",
                        barsMin = 6, barsMax = 10,
                        transitions = listOf(
                            SectionTransition(targetIndex = 2, weight = 0.60f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 3, weight = 0.25f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 4, weight = 0.15f, transitionBars = dropBars),
                        ),
                        recencyDecay = 0.5f,
                        macroOverrides = null,
                    ),
                    // 2: chorus — the swaggering peak. Slide PEDALS on the tonic here
                    //    (FIXED) so the bVII leap reads as a hammering hook, not a lurch.
                    Section(
                        name = "chorus",
                        barsMin = 4, barsMax = 6,
                        transitions = listOf(
                            SectionTransition(targetIndex = 1, weight = 0.40f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 3, weight = 0.35f, transitionBars = dropBars),
                            SectionTransition(targetIndex = 4, weight = 0.25f, transitionBars = dropBars),
                        ),
                        recencyDecay = 0.5f,
                        macroOverrides = MacroOverrides(energy = 1.4f, complexity = 1.2f, space = 0.7f, mood = 1.1f),
                        trackOverrides = mapOf(
                            4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
                        ),
                    ),
                    // 3: solo — slide jam over the groove.
                    Section(
                        name = "solo",
                        barsMin = 8, barsMax = 16,
                        transitions = listOf(
                            SectionTransition(targetIndex = 2, weight = 0.50f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 1, weight = 0.30f, transitionBars = liftBars),
                            SectionTransition(targetIndex = 4, weight = 0.20f, transitionBars = dropBars),
                        ),
                        recencyDecay = 0.4f,
                        macroOverrides = MacroOverrides(energy = 0.9f, complexity = 1.4f, space = 1.2f, mood = 1.2f),
                        soloMode = SoloMode.Jam(probability = 0.85f),
                    ),
                    // 4: breakdown — stripped to bass + drums + vibes. Locked to 4 bars
                    //    so it's one long anticipation build into the chorus.
                    Section(
                        name = "breakdown",
                        barsMin = 4, barsMax = 4,
                        transitions = listOf(
                            SectionTransition(targetIndex = 2, weight = 0.70f, transitionBars = bigLiftBars),
                            SectionTransition(targetIndex = 1, weight = 0.30f, transitionBars = liftBars),
                        ),
                        recencyDecay = 0.5f,
                        macroOverrides = MacroOverrides(energy = 0.45f, complexity = 0.5f, space = 1.4f, mood = 0.85f),
                    ),
                    // 5: outro — the climactic stomp.
                    Section(
                        name = "outro",
                        barsMin = 4, barsMax = 6,
                        macroOverrides = MacroOverrides(energy = 1.5f, complexity = 0.5f, space = 0.6f, mood = 1.1f),
                    ),
                ),
            ),
        )
    }
}
