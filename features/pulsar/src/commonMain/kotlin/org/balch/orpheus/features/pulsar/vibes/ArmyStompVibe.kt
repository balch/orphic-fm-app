package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.Arrangement
import org.balch.orpheus.features.pulsar.Band
import org.balch.orpheus.features.pulsar.BandMember
import org.balch.orpheus.features.pulsar.BarStrategy
import org.balch.orpheus.features.pulsar.ChordComping
import org.balch.orpheus.features.pulsar.ChordFollow
import org.balch.orpheus.features.pulsar.CompingFills
import org.balch.orpheus.features.pulsar.CompingHumanization
import org.balch.orpheus.features.pulsar.CompingStyle
import org.balch.orpheus.features.pulsar.Engine
import org.balch.orpheus.features.pulsar.EnvelopeProfile
import org.balch.orpheus.features.pulsar.EnvelopeType
import org.balch.orpheus.features.pulsar.EvolutionTension
import org.balch.orpheus.features.pulsar.FillType
import org.balch.orpheus.features.pulsar.GenreProfile
import org.balch.orpheus.features.pulsar.Lick
import org.balch.orpheus.features.pulsar.LickMode
import org.balch.orpheus.features.pulsar.LickStep
import org.balch.orpheus.features.pulsar.MacroOverrides
import org.balch.orpheus.features.pulsar.ProgressionAnchor
import org.balch.orpheus.features.pulsar.ProgressionStyle
import org.balch.orpheus.features.pulsar.RhythmPattern
import org.balch.orpheus.features.pulsar.RootNote
import org.balch.orpheus.features.pulsar.ScaleType
import org.balch.orpheus.features.pulsar.Section
import org.balch.orpheus.features.pulsar.SectionInversion
import org.balch.orpheus.features.pulsar.SectionTransition
import org.balch.orpheus.features.pulsar.SoloMode
import org.balch.orpheus.features.pulsar.TensionProfile
import org.balch.orpheus.features.pulsar.TonalTension
import org.balch.orpheus.features.pulsar.TrackMacroMap
import org.balch.orpheus.features.pulsar.TrackRole
import org.balch.orpheus.features.pulsar.TrackVoice
import org.balch.orpheus.features.pulsar.Vibe
import org.balch.orpheus.features.pulsar.VibeEffects
import org.balch.orpheus.features.pulsar.VibeProvider
import org.balch.orpheus.features.pulsar.bandMatrix
import org.balch.orpheus.features.pulsar.chords
import org.balch.orpheus.features.pulsar.row

@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class ArmyStompVibe : VibeProvider {

    // Per-edge transitionBars precedent: name the *musical role* the ramp serves,
    // not the bar count. Military marches mostly want HARD cuts (the default 0)
    // for snap and discipline — only ramp when the music genuinely needs to
    // breathe. Two named ramps cover this vibe:
    //
    //   marchSwellBars   — short tight swell where dynamics need a moment
    //                      (pulling out to a solo, climbing back into action).
    //   dreamDriftBars   — long ramp INTO or OUT OF the `drift` style change —
    //                      the moment the army stops marching and starts
    //                      wandering (and back). Earned, not gratuitous.
    //
    // Most march/charge/breakdown transitions stay at the SectionTransition
    // default of 0 (hard cut) — the trumpets sound, the battle begins, the
    // chaos hits. That punchiness IS the vibe.
    private val marchSwellBars = 2
    private val dreamDriftBars = 4

    override val vibe = Vibe(
        name = "Army Stomp",
        bpm = 110f,
        envelopeType = EnvelopeType.BLEND,
        rootNote = RootNote.E,
        scaleType = ScaleType.MINOR,
        lick = Lick(
            steps = listOf(
                LickStep(
                    scaleDegree = 4,
                    duration = 0.25f,
                    velocity = 0.90f
                ),   // B(5th) — grace, slides into:
                LickStep(
                    scaleDegree = 0,
                    duration = 1.75f,
                    velocity = 0.95f
                ),   // E — HEAVY opening, held long
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),    // E — pickup
                LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.85f),    // G — the jump
                LickStep(scaleDegree = 0, duration = 1.5f, velocity = 0.90f),    // E — back, held
                LickStep(
                    scaleDegree = -1,
                    duration = 1.0f,
                    velocity = 0.80f
                ),   // D — descent starts
                LickStep(scaleDegree = -2, duration = 1.0f, velocity = 0.75f),   // C — deliberate
                LickStep(
                    scaleDegree = -3,
                    duration = 1.5f,
                    velocity = 0.85f
                ),   // B low — resolve, ring out
            ),
        ),
        // 24-bar lick evolution arc — mutations stick within ±4 scale degrees of original.
        // Same tuning as Dust Groove: innerBars=8 for spurts every 8 bars, outerBars=24
        // for the super-cycle climax.
        lickMutation = 1.0f,
        band = Band(
            members = listOf(
                BandMember(
                    "Drummer", listOf(0, 1, 2), alwaysActive = true,
                    creativity = 0.3f, swing = 0.02f, drag = -0.1f
                ),
                BandMember(
                    "Bassist", listOf(3),
                    creativity = 0.4f, swing = 0.0f, drag = 0.05f
                ),
                BandMember(
                    "Keys", listOf(4),
                    creativity = 0.6f, swing = 0.0f, drag = 0.0f
                ),
                BandMember(
                    "FX", listOf(5, 6, 7),
                    creativity = 0.8f, swing = 0.0f, drag = 0.1f
                ),
            ),
            handoffMatrix = bandMatrix(
                //            DRUM  BASS  KEYS  FX
                "Drummer" to row(0.00f, 0.30f, 0.35f, 0.10f),
                "Bassist" to row(0.25f, 0.00f, 0.35f, 0.15f),
                "Keys" to row(0.20f, 0.35f, 0.00f, 0.20f),
                "FX" to row(0.15f, 0.30f, 0.30f, 0.00f),
            ),
            pullInMatrix = bandMatrix(
                //            DRUM  BASS  KEYS  FX
                "Drummer" to row(0.00f, 0.25f, 0.20f, 0.05f),
                "Bassist" to row(0.20f, 0.00f, 0.35f, 0.10f),
                "Keys" to row(0.15f, 0.35f, 0.00f, 0.10f),
                "FX" to row(0.10f, 0.20f, 0.20f, 0.00f),
            ),
            pullInBarsMin = 2, pullInBarsMax = 4,
            barsPerLeadMin = 2, barsPerLeadMax = 6,
        ),
        energy = 0.75f,
        complexity = 0.5f,
        space = 0.6f,
        mood = 0.5f,
        genre = GenreProfile(
            swingAmount = 0.02f,
            ghostProbability = 0.16f,
            noteRangeLow = 36,
            noteRangeHigh = 60,
            rhythmDensity = RhythmPattern.BACKBEAT.density,
            progressionStyle = ProgressionStyle.DARK,
            chordsPerBar = 2,
        ),
        // Tight DARK progression — march should stay on the E-centered voicing.
        progressionAnchor = ProgressionAnchor.EVERY_8,
        progressionDriftRange = 0.25f,
        tracks = listOf(
            TrackVoice(
                engineEdm = Engine.BD,
                engineSpace = Engine.BD,
                role = TrackRole.Percussive,
                volume = 0.90f,
                pan = 0.00f,
                density = 0.50f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.REPEAT
            ),
            TrackVoice(
                engineEdm = Engine.SD,
                engineSpace = Engine.SD,
                role = TrackRole.Percussive,
                volume = 0.55f,
                pan = -0.10f,
                density = 0.30f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.REPEAT
            ),
            TrackVoice(
                engineEdm = Engine.HH,
                engineSpace = Engine.NSE,
                role = TrackRole.Percussive,
                volume = 0.35f,
                pan = 0.15f,
                density = 0.25f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.REPEAT
            ),
            // Bass: disciplined march — ROOT_ONLY chord follow + REPEAT rhythm so
            // the bass locks to the chord root every stab. Range pinned to E2-B2
            // (40-47): noteRangeLow raised from E1 keeps it out of sub-bass mud,
            // noteRangeHigh dropped to B2 keeps it from climbing into the lead's
            // E3 floor (see Path B note on the squash lead below).
            TrackVoice(
                engineEdm = Engine.PD,
                engineSpace = Engine.DX,
                harmonics = .05f, // Mooger Low
                role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                volume = 0.85f,
                pan = 0.00f,
                density = 0.40f,
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
                barStrategy = BarStrategy.REPEAT,
                noteRangeLow = 40,
                noteRangeHigh = 47,  // B2 ceiling — pure bass register
                reverbBrightness = 0.5f
            ),
            // Squash lead: dual-VCF aesthetic, separated from the bass by SPACE
            // not SPECTRUM. The bass and lead share filter-sweep character but
            // live in different positions in the field:
            //   * pan = 0.20         — pushed right (bass holds center)
            //   * floor = E3 (52)    — never overlaps the bass's B2 ceiling
            //   * reverbBrightness   — 0.7 (brighter tail pushes it back in depth)
            // The space-side stays Engine.STR so at low energy you get a lush
            // string crossfade. (A/B winner over the brass approach: tried DX3
            // idx 31 "Br trumpet" — clean spectral separation but lost the
            // unified filter-sweep palette that defines the vibe's character.)
            TrackVoice(
                engineEdm = Engine.DX2, // Fender
                engineSpace = Engine.DX3, // Hammond
                harmonics = .05f,
                role = TrackRole.Melodic(lickMode = LickMode.Squash),
                volume = 0.60f,
                pan = 0.20f,
                density = 0.20f,
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
                barStrategy = BarStrategy.CALL_RESPONSE,
                noteRangeLow = 52,
                noteRangeHigh = 67,
                reverbBrightness = 0.7f,
                glideRate = 0.05f
            ), // Squash: CALL_RESPONSE owns bar 2
            // Track 5: Texture pad — density bumped so it can pulse under the breakdown.
            TrackVoice(
                engineEdm = Engine.GRN,
                engineSpace = Engine.GRN,
                role = TrackRole.Melodic(),
                volume = 0.30f,
                pan = 0.30f,
                density = 0.30f,
                envelopeProfile = EnvelopeProfile.EFFECT,
                macroMap = TrackMacroMap.EFFECT,
                barStrategy = BarStrategy.INDEPENDENT,
                modLfoRate = 0.5f,
                modLfoDepth = 0.3f,
                modLfoShape = 0.3f,
                modLfoCoupling = 0.1f,
                holdProbability = 0.1f,
                holdLengthMin = 2,
                holdLengthMax = 4,
                reverbSend = 0.1f,
                delaySend = 0.15f,
                noteRangeLow = 36,
                noteRangeHigh = 60,
                reverbBrightness = 0.5f
            ),
            TrackVoice(
                engineEdm = Engine.NSE,
                engineSpace = Engine.PAR,
                role = TrackRole.Percussive,
                volume = 0.30f,
                pan = -0.30f,
                density = 0.10f,
                envelopeProfile = EnvelopeProfile.EFFECT,
                macroMap = TrackMacroMap.EFFECT,
                barStrategy = BarStrategy.INDEPENDENT,
                modLfoRate = 0.4f,
                modLfoDepth = 0.25f,
                modLfoShape = 0.4f,
                modLfoCoupling = 0.1f,
                holdProbability = 0.05f,
                holdLengthMin = 2,
                holdLengthMax = 3,
                reverbSend = 0.1f,
                delaySend = 0.1f,
                noteRangeLow = 36,
                noteRangeHigh = 60,
                reverbBrightness = 0.5f
            ),
            // Track 7: CHORDAL mallet — subtle PAD during march sections, becomes
            // off-beat SKA_UPSTROKES stabs in the breakdown via section override.
            // Engine.DX2 with harmonics=0.54 selects patch idx 17 = "Marimba" — same
            // FM mallet family as Xylophone but inherently woodier and lower-register,
            // so the ring stays without the bell-y top end. Range dropped to F2-C4 to
            // keep the marimba in its idiomatic register and let chords sit just above
            // the bass without floating into the trumpet's territory.
            // (Iteration history: DX2/0.50 = "Xylophone" → too high and mallet-y for
            // ska. DX3/0.05 = "Hammond" → too organ-y, lost the ring. Marimba threads
            // both: ring + lower register + percussive chord stab.)
            TrackVoice(
                engineEdm = Engine.DX2, engineSpace = Engine.DX2,
                role = TrackRole.Chordal(
                    comping = ChordComping(
                        style = CompingStyle.BLUES_SHUFFLE,
                        fills = CompingFills(
                            everyNBars = 4,
                            fillType = FillType.DOUBLE_TIME,
                            skipProbability = .4f
                        ),
                    ),
                ),  // subtle in march
                timbre = .5f,
                harmonics = 0.54f,  // DX2 idx 17 = "Marimba" — wooden ring, lower than xylo
                morph = .5f,
                volume = 0.20f, pan = 0.15f, density = 0.18f,
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
                noteRangeLow = 41, noteRangeHigh = 60,  // F2-C4 — deeper mallet register
                reverbBrightness = 0.5f,
            ),
        ),
        stepCount = 32,
        tension = TensionProfile(
            // Lick spurts every 8 bars (inner), outer super-cycle at 24 bars
            spurtChance = 0.25f,
            innerBars = 8, outerBars = 24, outerDepth = 0.6f,
            volume = 0.4f,
            tonal = TonalTension(octaveShift = true, halfLick = true, chromaticPassing = 0.15f),
            timing = 0.2f,
            evolution = EvolutionTension(
                timbreLow = 0.25f, timbreHigh = 0.55f, timbreProbability = 0.8f,
                morphLow = 0.3f, morphHigh = 0.55f, morphProbability = 0.4f,
                harmonicsLow = 0.35f, harmonicsHigh = 0.50f, harmonicsProbability = 0.25f,
                attackPoint = 0.3f, releaseSpeed = 0.5f,
            ),
        ),
        effects = VibeEffects(
            delayTimeA = 0.15f,
            delayTimeB = 0.3f,
            delayFeedback = 0.25f,
            delayDamping = 0.3f,
            reverbSize = 0.3f,
            reverbDamping = 0.4f,
            reverbBrightness = 0.5f,
        ),
        arrangement = Arrangement(
            introIndex = 0,
            sections = listOf(
                // 0: march — tight, disciplined, locked drums.
                // march -> charge:    HARD CUT — trumpets sound, the battle begins.
                // march -> solo:      marchSwell (smooth pull-out into the spotlight).
                // march -> breakdown: HARD CUT — sudden chaos, no warning.
                // march -> drift:     dreamDrift (the army leaves the ground).
                Section(
                    name = "march",
                    barsMin = 8, barsMax = 16,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.4f),  // charge — hard cut
                        SectionTransition(
                            targetIndex = 2,
                            weight = 0.25f,
                            transitionBars = marchSwellBars
                        ),
                        SectionTransition(targetIndex = 3, weight = 0.15f), // breakdown — hard cut
                        SectionTransition(
                            targetIndex = 4,
                            weight = 0.2f,
                            transitionBars = dreamDriftBars
                        ),
                    ),
                    macroOverrides = MacroOverrides(complexity = 0.7f, space = 0.6f),
                ),
                // 1: charge — high energy, more fills, aggressive.
                // charge -> march:     HARD CUT — snap back to discipline.
                // charge -> solo:      marchSwell (energy yields to spotlight).
                // charge -> breakdown: HARD CUT — continuous intensity, just more chaos.
                Section(
                    name = "charge",
                    barsMin = 4, barsMax = 8,
                    transitions = listOf(
                        SectionTransition(targetIndex = 0, weight = 0.3f),  // march — hard cut
                        SectionTransition(
                            targetIndex = 2,
                            weight = 0.5f,
                            transitionBars = marchSwellBars
                        ),
                        SectionTransition(targetIndex = 3, weight = 0.2f),  // breakdown — hard cut
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = MacroOverrides(
                        energy = 1.5f, complexity = 1.5f, space = 0.4f, mood = 1.3f,
                    ),
                    soloMode = SoloMode.LongFill(probability = 0.4f),
                    customProgression = chords(0, 3, 5, 6),
                    chordsPerBar = 2,
                ),
                // 2: solo — extended spotlight, VCF bass or keys rip.
                // solo -> march / charge / breakdown: marchSwell (climb out of the solo).
                Section(
                    name = "solo",
                    barsMin = 8, barsMax = 12,
                    transitions = listOf(
                        SectionTransition(
                            targetIndex = 0,
                            weight = 0.4f,
                            transitionBars = marchSwellBars
                        ),
                        SectionTransition(
                            targetIndex = 3,
                            weight = 0.4f,
                            transitionBars = marchSwellBars
                        ),
                        SectionTransition(
                            targetIndex = 1,
                            weight = 0.2f,
                            transitionBars = marchSwellBars
                        ),
                    ),
                    recencyDecay = 0.4f,
                    macroOverrides = MacroOverrides(
                        energy = 0.8f, complexity = 1.3f, space = 1.3f, mood = 1.2f,
                    ),
                    soloMode = SoloMode.LickBuilder(probability = 0.8f, mutationRate = 0.6f),
                ),
                // 3: breakdown — CHAOS. Crazy drums, bass stabs on root, keys SKA
                // off-stabs, texture pulses. In-your-face, tight, not stripped.
                // breakdown -> charge: HARD CUT — continuous intensity into more attack.
                // breakdown -> march:  marchSwell (chaos cools back to discipline).
                // breakdown -> drift:  dreamDrift (chaos dissolves into the dream).
                Section(
                    name = "breakdown",
                    barsMin = 4, barsMax = 8,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.6f),  // charge — hard cut
                        SectionTransition(
                            targetIndex = 0,
                            weight = 0.3f,
                            transitionBars = marchSwellBars
                        ),
                        SectionTransition(
                            targetIndex = 4,
                            weight = 0.1f,
                            transitionBars = dreamDriftBars
                        ),
                    ),
                    // Energy cranked for crazy drum fills/variation; space pulled in tight
                    // for the in-your-face feel. Complexity maxed drives the drum chaos.
                    macroOverrides = MacroOverrides(
                        energy = 1.5f, complexity = 2.0f, space = 0.3f, mood = 1.3f,
                    ),
                    chordFollow = ChordFollow.ROOT_ONLY,  // bass stabs locked to root
                    compingStyle = CompingStyle.SKA_UPSTROKES,  // keys = off-beat stabs
                    compingInversion = SectionInversion.FIRST_INVERSION,  // lifted voicing for brightness
                ),
                // 4: drift — STYLE CHANGE. The army stops marching and starts wandering.
                // Bass breaks out of ROOT_ONLY and plays melodic lines; whole thing
                // feels suspended. Transitions back to march via charge most of the time.
                // drift -> march / charge: dreamDrift (the army re-engages slowly).
                // drift -> solo:           marchSwell (atmospheric handoff between two
                //                          spacious sections — short ramp is enough).
                Section(
                    name = "drift",
                    barsMin = 6, barsMax = 10,
                    transitions = listOf(
                        SectionTransition(
                            targetIndex = 0,
                            weight = 0.5f,
                            transitionBars = dreamDriftBars
                        ),
                        SectionTransition(targetIndex = 1, weight = 0.3f, transitionBars = 0),
                        SectionTransition(
                            targetIndex = 2,
                            weight = 0.2f,
                            transitionBars = marchSwellBars
                        ),
                    ),
                    recencyDecay = 0.6f,
                    // Dreamy, spacious, more complex — feels like the march left the ground
                    macroOverrides = MacroOverrides(
                        energy = 0.2f, complexity = 1.8f, space = 1.6f, mood = 1.4f,
                    ),
                    // FOLLOW (not ROOT_ONLY) lets the bass walk with the i-iv cycle
                    // instead of pedaling on E — that's the "army wandering" sound.
                    chordFollow = ChordFollow.FOLLOW,
                    compingStyle = CompingStyle.ROCK_DOWNBEATS,
                    // Loosen the keyboard's stabs: drop ~a third of them, let some
                    // jump octaves, occasionally add an extension. The march keys
                    // are tight and disciplined; drift keys should feel like a
                    // tired pianist noodling at dawn.
                    compingHumanization = CompingHumanization(
                        dropProbability = 0.35f,
                        ghostProbability = 0.20f,
                        octaveJumpProbability = 0.30f,
                        extensionProbability = 0.40f,
                    ),
                    customProgression = chords(0, 3, 0, 3),
                    chordsPerBar = 1,
                    // Long languid arc — slow attack into a late peak, slow release.
                    // octaveShift + halfLick + heavier chromaticPassing make the
                    // melodic figure wander instead of marching. Evolution probs
                    // crank to keep the timbre/morph drifting through the section.
                    tensionOverride = TensionProfile(
                        spurtChance = 0.05f,
                        innerBars = 8, outerBars = 24, outerDepth = 0.5f,
                        volume = 0.30f,
                        tonal = TonalTension(
                            octaveShift = true,
                            halfLick = true,
                            chromaticPassing = 0.30f,
                        ),
                        timing = 0.35f,
                        evolution = EvolutionTension(
                            timbreLow = 0.30f, timbreHigh = 0.70f, timbreProbability = 0.70f,
                            morphLow = 0.40f, morphHigh = 0.70f, morphProbability = 0.50f,
                            harmonicsLow = 0.35f, harmonicsHigh = 0.55f, harmonicsProbability = 0.30f,
                            attackPoint = 0.60f, releaseSpeed = 0.30f,
                        ),
                    ),
                ),
            ),
        ),
    )
}
