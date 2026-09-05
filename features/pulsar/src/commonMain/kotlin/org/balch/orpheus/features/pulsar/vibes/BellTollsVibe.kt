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
import org.balch.orpheus.features.pulsar.models.CompingHumanization
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.DuckingProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.FillType
import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
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
 * Rasta Man — roots reggae at 78 BPM in A minor.
 *
 * "Never send to know for whom the bell tolls; it tolls for thee (this time),"
 *    written by John Donne in 1624
 *
 * The classic arrangement: one-drop drums (sparse kick, backbeat snare,
 * steady hats), a big round dub bass locked to root, guitar skank on
 * beats 2+4, organ bubble filling the gaps with syncopated 16ths, and
 * a melodica-style horn lick that evolves over a 24-bar arc.
 *
 * The 'dub' section puts everything in reverb and drones the bass —
 * classic reggae dub treatment for breakdowns.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class BellTollsVibe : VibeProvider {
    override val name: String = "Bell Tolls"

    // Roots reggae: hang on tonic for 3 bars, VII turnaround on bar 4.
    // Am-Am-Am-G — the signature "one drop" phrase shape.
    private val mainProgression = chords(0, 0, 0, 6)
    private val chordsPerBar = 1

    private val chorusProgression = chords(0, 5, 6, 0)
    private val chorusChordsPerBar = 2

    // Per-edge transitionBars precedent: name the *musical role* the ramp serves,
    // not the bar count. Reggae lives on smooth transitions — use these for
    // every edge, not just the obvious ones.
    //
    //   skankLiftBars — gentle ramp UP into a busier section (chorus, solo) or
    //                   re-emergence from a breakdown (dub -> groove).
    //   dubFadeBars   — long dreamy fade INTO the dub breakdown — reverb tails
    //                   bloom as the band drops out.
    private val skankLiftBars = 1
    private val dubFadeBars = 2

    private val sectionList by lazy {
        listOf(
            // 0 INTRO: Just drums + bass locking in — classic reggae opening.
            // intro -> groove: skankLift (the band rises into the pocket).
            Section(
                name = "intro",
                barsMin = 2, barsMax = 2,
                transitions = listOf(
                    SectionTransition(
                        targetIndex = 1,
                        weight = 1.0f,
                        transitionBars = skankLiftBars
                    ),
                ),
                macroOverrides = MacroOverrides(
                    energy = 0.45f, complexity = 0.25f, space = 0.8f, mood = 0.9f,
                ),
                chordsPerBar = 3,
                customProgression = chords(0, 0, 6)
            ),
            // 1 GROOVE: Full band roots feel — the main pocket.
            // groove -> chorus / solo: skankLift (gentle energy climb).
            // groove -> dub: dubFade (long dreamy descent into the breakdown).
            Section(
                name = "groove",
                barsMin = 8, barsMax = 10,
                transitions = listOf(
                    SectionTransition(
                        targetIndex = 2,
                        weight = 0.5f,
                        transitionBars = skankLiftBars
                    ),  // chorus
                    SectionTransition(
                        targetIndex = 3,
                        weight = 0.3f,
                        transitionBars = dubFadeBars
                    ),    // dub
                    SectionTransition(
                        targetIndex = 4,
                        weight = 0.2f,
                        transitionBars = skankLiftBars
                    ),  // solo
                ),
                recencyDecay = 0.5f,
                // groove is baseline — no macro overrides
            ),
            // 2 CHORUS: Bumped energy, horns prominent, skank shifts to
            // SKA_UPSTROKES for a busier off-beat feel. Classic reggae lift.
            // chorus -> groove / solo: skankLift (gentle return / lead-in).
            // chorus -> dub: dubFade (long descent into the breakdown).
            //   The melodica hook PEDALS on the tonic here (per-track FIXED) rather
            //   than FOLLOWing the 0—5—6—0 chorus chords. With FOLLOW, the chord
            //   offset is added then folded into the melodica's lowest octave, so the
            //   degree-5 leap (and the 6—0 drop) folds to a lurching up-a-leap /
            //   back-down contour twice per bar — the "disjointed" feel. Pedaling the
            //   hook on A keeps it stable while skank + organ + bass carry the 0—5—6—0
            //   motion underneath. A low-mutation LickBuilder hands the pedaled hook
            //   to the lead member (melodica wins — highest melodic creativity) so the
            //   chorus gains a clear, developing focal melody on the lift.
            Section(
                name = "chorus",
                barsMin = 4, barsMax = 4,
                transitions = listOf(
                    SectionTransition(
                        targetIndex = 1,
                        weight = 0.5f,
                        transitionBars = skankLiftBars
                    ),
                    SectionTransition(targetIndex = 3, weight = 0.3f, transitionBars = dubFadeBars),
                    SectionTransition(
                        targetIndex = 4,
                        weight = 0.2f,
                        transitionBars = skankLiftBars
                    ),
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 1.3f, complexity = 1.05f, mood = 1.25f,
                ),
                compingStyle = CompingStyle.SKA_UPSTROKES,   // busier off-beats
                compingInversion = SectionInversion.FIRST_INVERSION,
                chordsPerBar = chorusChordsPerBar,
                customProgression = chorusProgression,
                soloMode = SoloMode.LickBuilder(probability = 0.85f, mutationRate = 0.30f),
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
                ),
            ),
            // 3 DUB: The reggae breakdown — bass drones, everything bathed in
            // reverb and delay. Drummer keeps time but everyone else floats.
            // dub -> groove / chorus: skankLift (re-emerging from the haze).
            Section(
                name = "dub",
                barsMin = 4, barsMax = 6,
                transitions = listOf(
                    SectionTransition(
                        targetIndex = 1,
                        weight = 0.6f,
                        transitionBars = skankLiftBars
                    ),
                    SectionTransition(
                        targetIndex = 2,
                        weight = 0.4f,
                        transitionBars = skankLiftBars
                    ),
                ),
                recencyDecay = 0.5f,
                // energy ×0.55 (not ×0.4): with base energy 0.55 the old
                // multiplier dropped effective energy to ~0.22, which maxed
                // every low-energy humanization system at once (tempo wander,
                // long drum decay, slow Tides attacks) — the drums fell apart
                // in the haze instead of anchoring it. The drummer keeps time
                // through the dub; space still carries the drowning.
                macroOverrides = MacroOverrides(
                    energy = 0.55f, complexity = 0.5f, space = 1.8f, mood = 1.1f,
                ),
                chordFollow = ChordFollow.FIXED,  // everything drones on A
            ),
            // 4 SOLO: Melodica takes the spotlight, LickBuilder evolves the
            // phrase aggressively while the band holds the pocket.
            // solo -> groove / chorus: skankLift.
            // solo -> dub: dubFade (long fade into the breakdown).
            Section(
                name = "solo",
                barsMin = 8, barsMax = 10,
                transitions = listOf(
                    SectionTransition(
                        targetIndex = 1,
                        weight = 0.5f,
                        transitionBars = skankLiftBars
                    ),
                    SectionTransition(
                        targetIndex = 2,
                        weight = 0.3f,
                        transitionBars = skankLiftBars
                    ),
                    SectionTransition(targetIndex = 3, weight = 0.2f, transitionBars = dubFadeBars),
                ),
                recencyDecay = 0.4f,
                soloMode = SoloMode.Jam(),
                macroOverrides = MacroOverrides(
                    energy = 0.4f, complexity = 1f, space = 1.2f, mood = .9f,
                ),
                // The bass follows the chords here instead of ROOT_ONLY, so a bass-led
                // jam keeps its improvised pitches (folded into E1..D#2) rather than
                // collapsing to a root pulse.
                trackOverrides = mapOf(
                    3 to TrackSectionOverride(chordFollow = ChordFollow.FOLLOW),
                ),
            ),
            // 5 OUTRO: Just drums + bass locking in — classic reggae opening.
            Section(
                name = "outro",
                barsMin = 4, barsMax = 4,
                // energy ×0.55 keeps the closing one-drop tight (see dub
                // section note); the song should end locked-in, not loose.
                macroOverrides = MacroOverrides(
                    energy = 0.55f, complexity = 0.5f, space = 1.8f, mood = 1.1f,
                ),
                chordFollow = ChordFollow.FIXED,  // everything drones on A
                compingInversion = SectionInversion.SECOND_INVERSION,
                customProgression = chords(0)
            ),
        )
    }

    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            album = Album.RIF,
            bpm = 78f,  // classic roots reggae tempo
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = sectionList.lastIndex,
                sections = sectionList,
                lengthSeconds = 180..300,
            ),
            anomalies = listOf(
                WahAnomaly(probability = .05f),
            ),
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.A,
            scaleType = ScaleType.MINOR_PENTATONIC,
            // Melodica horn lick — A minor pentatonic-flavored.
            // A → C → E (hold) → C → A (home, long) — 4 beats of phrase + 4 beats rest.
            lick = Lick(
                steps = listOf(
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.80f),   // A — root call
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.75f),   // C — minor third
                    LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.88f),   // E — fifth, hold
                    LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.70f),   // C — step back
                    LickStep(scaleDegree = 0, duration = 1.5f, velocity = 0.80f),   // A — home, long breath
                ),
                loopLength = 8,  // phrase + rest = reggae "horn stab then space"
            ),
            lickMutation = 0.7f,  // moderate melodic freedom; evolves across the 24-bar arc
            band = Band(
                members = listOf(
                    BandMember("Drummer", listOf(0, 1, 2), alwaysActive = true,
                        loudness = 0.7f, creativity = 0.3f),
                    BandMember("Bassist", listOf(3),
                        loudness = 0.5f, creativity = 0.4f),
                    BandMember("Melodica", listOf(4),
                        loudness = 0.55f, creativity = 0.65f),
                    BandMember("Keys", listOf(5, 6),
                        loudness = 0.5f, creativity = 0.5f),
                ),
                handoffMatrix = bandMatrix(
                    //            DRUM  BASS  MELOD KEYS
                    "Drummer"  to row(0.00f, 0.35f, 0.25f, 0.40f),
                    "Bassist"  to row(0.20f, 0.00f, 0.40f, 0.40f),
                    "Melodica" to row(0.15f, 0.35f, 0.00f, 0.50f),
                    "Keys"     to row(0.15f, 0.40f, 0.30f, 0.15f),
                ),
                pullInMatrix = bandMatrix(
                    "Drummer"  to row(0.00f, 0.40f, 0.20f, 0.35f),
                    "Bassist"  to row(0.30f, 0.00f, 0.30f, 0.40f),
                    "Melodica" to row(0.15f, 0.35f, 0.00f, 0.45f),
                    "Keys"     to row(0.20f, 0.40f, 0.25f, 0.10f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 4,
                barsPerLeadMin = 4, barsPerLeadMax = 8,
            ),
            energy = 0.55f,
            complexity = 0.45f,
            space = 0.6f,     // reggae has space — don't crowd the mix
            mood = 0.7f,      // warm, uplifting
            genre = GenreProfile(
                swingAmount = 0.05f,   // straight feel — reggae swings via articulation, not timing
                ghostProbability = 0.12f,
                noteRangeLow = 40,
                noteRangeHigh = 72,
                rhythmDensity = RhythmPattern.BACKBEAT.density,
                progressionStyle = ProgressionStyle.MODAL,  // matrix still modal for drift flavor
                chordsPerBar = chordsPerBar,
                customProgression = mainProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_4,   // reset every phrase
            progressionDriftRange = 0.08f,                    // minimal drift — keep the phrase shape
            tracks = listOf(
                // ═══════════════════════════════════════════════════════════
                // 0-2: One-drop drums
                // ═══════════════════════════════════════════════════════════

                // 0 KICK: One-drop — sparse, accent on beat 3 (the signature)
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.85f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f, density = 0.18f,  // VERY sparse for one-drop feel
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 1 SNARE: Rim-shot backbeat — 2 and 4
                OrpheusEngine(engineId = OrpheusEngineId.SD, volume = 0.60f).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.10f, density = 0.30f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 2 HH: Steady 8th notes — the Rasta pulse
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.50f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f, density = 0.55f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },

                // ═══════════════════════════════════════════════════════════
                // 3-4: The voice of the groove — bass and melodica
                // ═══════════════════════════════════════════════════════════

                // 3 BASS: PD warm/dark tone, ROOT_ONLY for that dub bass lock.
                // Bass is prominent in reggae — mixed loud, center, deep register with slide.
                OrpheusEngine(
                    engineId = OrpheusEngineId.PD,
                    volume = 0.95f,
                    harmonics = 0.18f, timbre = 0.22f, morph = 0.12f,  // darker, rounder dub tone
                    noteRangeLow = 28, noteRangeHigh = 45,  // E1-A2, the solo folds into this now
                    // EAR-TUNE(user owns after ear test): the slide. 0.55 was a half-second
                    // sweep on every leap; 0.35 is a finger slide between neighbours.
                    glideRate = 0.35f,
                    reverbBrightness = 0.25f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass,
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.00f, density = 0.40f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                        // Under a keys or melodica lead the bass plays less and slaps more:
                        // the engine adds the slaps, this sets how much thinner it gets.
                        // EAR-TUNE(user owns after ear test)
                        duckingProfile = DuckingProfile(densityReduction = 0.40f),
                    )
                },
                // 4 LEAD: Roots horn-stand-in — lick-driven, dual DX with mood-driven
                // patch walking so sections shift the horn's voice character automatically.
                // EDM = DX2 idx 21 "Bells" (chromatic-percussion family); macro walk shifts
                //   mood-up sections toward "Tub Bells"/"Gong 2" and mood-down sections
                //   toward "Bell C"/"Glockenspl".
                // Space = DX3 idx 18 "Textures 6" / 19 "Etherial5a" — 0.582f is a bucket-edge
                //   value, so which of the two loads depends on the previously loaded patch.
                //   Either way it stays in the evolving pad family; the macro walk shifts
                //   toward "Airy"/"Boron A" in brighter moods and "Mal Poly" in
                //   darker moods. The chorus section's mood=1.25 override automatically
                //   selects a brighter bell + brighter pad for the lift; the dub section's
                //   mood=1.1 nudges it slightly without overshooting.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX2,
                    volume = 0.30f,
                    harmonics = 0.647f,             // DX2 idx 21 "Bells" — base
                    harmonicsMacroRange = 0.05f,    // ±~1.5 patches: Glockenspl ↔ Bells ↔ Tub Bells
                    noteRangeLow = 60, noteRangeHigh = 79,  // C4-G5 horn register
                    reverbBrightness = 0.6f, reverbSend = 0.3f, delaySend = 0.35f,
                    glideRate = 0.15f,
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead.copy(
                            engineId = OrpheusEngineId.DX3,
                            harmonics = 0.582f,           // DX3 bucket edge: idx 18 "Textures 6" or 19 "Etherial5a"
                            harmonicsMacroRange = 0.05f,  // Mal Poly ↔ Etherial5a ↔ Airy
                        ),
                        role = TrackRole.Melodic(lickMode = LickMode.Fill),
                        pan = 0.20f, density = 0.30f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },

                // ═══════════════════════════════════════════════════════════
                // 5-6: The defining reggae chord elements
                // ═══════════════════════════════════════════════════════════

                // 5 SKANK GUITAR: The iconic reggae chop — REGGAE_SKANK plays
                // chord stabs on beats 2 and 4 only. Slight humanization so the
                // skank feels played, not programmed. Occasional 8-bar fills.
                OrpheusEngine(
                    engineId = OrpheusEngineId.CHD,
                    volume = 0.32f,
                    noteRangeLow = 45, noteRangeHigh = 60,  // A2-C4 guitar mid-low register
                    reverbBrightness = 0.55f, reverbSend = 0.25f, delaySend = 0.30f,
                ).let { skank ->
                    TrackVoice(
                        engineEdm = skank,
                        engineSpace = skank,
                        role = TrackRole.Chordal(
                            comping = ChordComping(
                                style = CompingStyle.REGGAE_SKANK,
                                sectionInversion = SectionInversion.ROOT_POSITION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.06f,       // skank rarely drops — too important
                                    octaveJumpProbability = 0.04f,
                                    extensionProbability = 0.08f,  // occasional 7th color
                                ),
                                fills = CompingFills(
                                    everyNBars = 8,
                                    fillType = FillType.ASCENDING_ARP,
                                    skipProbability = 0.5f,  // half the time skip — fills sparingly
                                ),
                            ),
                        ),
                        pan = -0.30f, density = 0.40f,  // left side — rhythm guitar spot
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                    )
                },
                // 6 ORGAN BUBBLE: Syncopated 16ths bubbling underneath — FUNK_STABS
                // template hits the "ands" and inner 16ths, filling the gaps between
                // skank stabs. Kept tame — a gentle bubble, not a lead.
                OrpheusEngine(
                    engineId = OrpheusEngineId.CHD,
                    volume = 0.22f,
                    noteRangeLow = 48, noteRangeHigh = 62,  // C3-D4 warmer register
                    reverbBrightness = 0.50f, reverbSend = 0.30f, delaySend = 0.25f,
                ).let { organ ->
                    TrackVoice(
                        engineEdm = organ,
                        engineSpace = organ,
                        role = TrackRole.Chordal(
                            comping = ChordComping(
                                style = CompingStyle.FUNK_STABS,  // syncopated 16ths = bubbling organ
                                sectionInversion = SectionInversion.FIRST_INVERSION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.35f,        // drop often — bubble is sparse
                                    ghostProbability = 0.08f,        // fewer extra hits
                                    octaveJumpProbability = 0.02f,   // basically never jump up
                                    extensionProbability = 0.10f,
                                ),
                            ),
                        ),
                        pan = 0.25f, density = 0.28f,  // right side — organ spot, quieter
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                    )
                },

                // ═══════════════════════════════════════════════════════════
                // 7: Background pad / texture
                // ═══════════════════════════════════════════════════════════

                // 7 TEXTURE: Warm ensemble pad, mostly atmosphere.
                // Low volume, long holds, heavy reverb — the warm Caribbean haze.
                OrpheusEngine(
                    engineId = OrpheusEngineId.ENS,
                    volume = 0.25f,
                    modLfoRate = 0.05f, modLfoDepth = 0.4f, modLfoShape = 0.3f, modLfoCoupling = 0.2f,
                    holdProbability = 0.85f, holdLengthMin = 8, holdLengthMax = 24,
                    reverbSend = 0.55f, delaySend = 0.25f,
                    noteRangeLow = 48, noteRangeHigh = 67,
                    reverbBrightness = 0.55f,
                ).let { pad ->
                    TrackVoice(
                        engineEdm = pad,
                        engineSpace = pad.copy(engineId = OrpheusEngineId.STR),
                        role = TrackRole.Melodic(),
                        pan = -0.35f, density = 0.12f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            stepCount = 32,
            tension = TensionProfile(
                // 24-bar lick evolution arc — same pattern as Dust Groove / Army Stomp
                innerBars = 8, outerBars = 24, outerDepth = 0.5f,
                volume = 0.25f,
                tonal = TonalTension(chromaticPassing = 0.08f),
                timing = 0.08f,  // loose feel, but not wandering
                evolution = EvolutionTension(
                    timbreLow = 0.3f, timbreHigh = 0.65f, timbreProbability = 0.7f,
                    morphLow = 0.25f, morphHigh = 0.55f, morphProbability = 0.5f,
                    attackPoint = 0.5f, releaseSpeed = 0.4f,
                ),
                spurtChance = 0.22f,
            ),
            effects = VibeEffects(
                // Classic reggae delay — dotted 8th feel, long feedback for that dub tail
                delayTimeA = 0.375f,
                delayTimeB = 0.5f,
                delayFeedback = 0.48f,
                delayDamping = 0.45f,
                // Big warm reverb — the Caribbean sunset
                reverbSize = 0.65f,
                reverbDamping = 0.5f,
                reverbBrightness = 0.5f,
                deepFloor = 0.4f,
            ),
    )

    }
}
