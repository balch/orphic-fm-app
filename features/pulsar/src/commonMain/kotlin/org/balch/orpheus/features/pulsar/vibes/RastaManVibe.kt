package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.Album
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
class RastaManVibe : VibeProvider {

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
    private val skankLiftBars = 2
    private val dubFadeBars = 4

    override val vibe = Vibe(
        name = "Bell Tolls",
        album = Album.RIF,
        bpm = 78f,  // classic roots reggae tempo
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
                    loudness = 0.7f, creativity = 0.3f, swing = 0.0f, drag = 0.05f),
                BandMember("Bassist", listOf(3),
                    loudness = 0.85f, creativity = 0.4f, swing = 0.0f, drag = 0.08f),
                BandMember("Melodica", listOf(4),
                    loudness = 0.55f, creativity = 0.65f, swing = 0.0f, drag = 0.1f),
                BandMember("Keys", listOf(5, 6),
                    loudness = 0.5f, creativity = 0.5f, swing = 0.0f, drag = 0.05f),
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
            TrackVoice(
                engineEdm = Engine.BD, engineSpace = Engine.BD,
                role = TrackRole.Percussive,
                volume = 0.85f, pan = 0.00f, density = 0.18f,  // VERY sparse for one-drop feel
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.REPEAT,
            ),
            // 1 SNARE: Rim-shot backbeat — 2 and 4
            TrackVoice(
                engineEdm = Engine.SD, engineSpace = Engine.SD,
                role = TrackRole.Percussive,
                volume = 0.60f, pan = -0.10f, density = 0.30f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.REPEAT,
            ),
            // 2 HH: Steady 8th notes — the Rasta pulse
            TrackVoice(
                engineEdm = Engine.HH, engineSpace = Engine.HH,
                role = TrackRole.Percussive,
                volume = 0.50f, pan = 0.15f, density = 0.55f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.REPEAT,
            ),

            // ═══════════════════════════════════════════════════════════
            // 3-4: The voice of the groove — bass and melodica
            // ═══════════════════════════════════════════════════════════

            // 3 BASS: PD warm/dark tone, ROOT_ONLY for that dub bass lock.
            // Bass is prominent in reggae — mixed loud, center, deep register with slide.
            TrackVoice(
                engineEdm = Engine.PD, engineSpace = Engine.PD,
                role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                volume = 0.95f, pan = 0.00f, density = 0.40f,
                harmonics = 0.18f, timbre = 0.22f, morph = 0.12f,  // darker, rounder dub tone
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
                barStrategy = BarStrategy.REPEAT,
                noteRangeLow = 28, noteRangeHigh = 45,  // E1-A2 — deep sub bass territory
                glideRate = 0.55f,  // slidey dub bass — notes smear into each other
                reverbBrightness = 0.25f,
            ),
            // 4 LEAD: Roots horn-stand-in — lick-driven plucks crossfading to a slow pad.
            // At Energy=1: DX2 patch idx 14 = "Harpsich" (harpsichord pluck) — works as a
            // bright plucky lead in place of a real melodica. At Energy=0: DX3 patch idx 14
            // = "*Planets" (PPG-style synth pad) for the more dub/spaced-out feel.
            // Fills across the full pattern; lick mutation gives it melodic life.
            // (See references/fm_patches.md — harmonics=0.45 lands at index 14 in both
            // banks; this voice is harpsichord-pluck, not a true reedy melodica.)
            TrackVoice(
                engineEdm = Engine.DX2, engineSpace = Engine.DX3,
                role = TrackRole.Melodic(lickMode = LickMode.Fill),
                volume = 0.30f, pan = 0.20f, density = 0.30f,
                harmonics = 0.45f, timbre = 0.52f, morph = 0.24f,  // bright plucky lead → spacey pad
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
                barStrategy = BarStrategy.REPEAT,
                noteRangeLow = 60, noteRangeHigh = 79,  // C4-G5 horn register
                reverbBrightness = 0.6f, reverbSend = 0.3f, delaySend = 0.35f,
                glideRate = 0.15f,
            ),

            // ═══════════════════════════════════════════════════════════
            // 5-6: The defining reggae chord elements
            // ═══════════════════════════════════════════════════════════

            // 5 SKANK GUITAR: The iconic reggae chop — REGGAE_SKANK plays
            // chord stabs on beats 2 and 4 only. Slight humanization so the
            // skank feels played, not programmed. Occasional 8-bar fills.
            TrackVoice(
                engineEdm = Engine.CHD, engineSpace = Engine.CHD,
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
                volume = 0.32f, pan = -0.30f, density = 0.40f,  // left side — rhythm guitar spot
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
                noteRangeLow = 45, noteRangeHigh = 60,  // A2-C4 guitar mid-low register
                reverbBrightness = 0.55f, reverbSend = 0.25f, delaySend = 0.30f,
            ),
            // 6 ORGAN BUBBLE: Syncopated 16ths bubbling underneath — FUNK_STABS
            // template hits the "ands" and inner 16ths, filling the gaps between
            // skank stabs. Kept tame — a gentle bubble, not a lead.
            TrackVoice(
                engineEdm = Engine.CHD, engineSpace = Engine.CHD,
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
                volume = 0.22f, pan = 0.25f, density = 0.28f,  // right side — organ spot, quieter
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
                noteRangeLow = 48, noteRangeHigh = 62,  // C3-D4 warmer register
                reverbBrightness = 0.50f, reverbSend = 0.30f, delaySend = 0.25f,
            ),

            // ═══════════════════════════════════════════════════════════
            // 7: Background pad / texture
            // ═══════════════════════════════════════════════════════════

            // 7 TEXTURE: Warm ensemble pad, mostly atmosphere.
            // Low volume, long holds, heavy reverb — the warm Caribbean haze.
            TrackVoice(
                engineEdm = Engine.ENS, engineSpace = Engine.STR,
                role = TrackRole.Melodic(),
                volume = 0.25f, pan = -0.35f, density = 0.12f,
                envelopeProfile = EnvelopeProfile.EFFECT,
                macroMap = TrackMacroMap.EFFECT,
                barStrategy = BarStrategy.INDEPENDENT,
                modLfoRate = 0.05f, modLfoDepth = 0.4f, modLfoShape = 0.3f, modLfoCoupling = 0.2f,
                holdProbability = 0.85f, holdLengthMin = 8, holdLengthMax = 24,
                reverbSend = 0.55f, delaySend = 0.25f,
                noteRangeLow = 48, noteRangeHigh = 67,
                reverbBrightness = 0.55f,
            ),
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
        arrangement = Arrangement(
            introIndex = 0,
            sections = listOf(
                // 0 INTRO: Just drums + bass locking in — classic reggae opening.
                // intro -> groove: skankLift (the band rises into the pocket).
                Section(
                    name = "intro",
                    barsMin = 2, barsMax = 2,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = skankLiftBars),
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
                    barsMin = 8, barsMax = 12,
                    transitions = listOf(
                        SectionTransition(targetIndex = 2, weight = 0.5f, transitionBars = skankLiftBars),  // chorus
                        SectionTransition(targetIndex = 3, weight = 0.3f, transitionBars = dubFadeBars),    // dub
                        SectionTransition(targetIndex = 4, weight = 0.2f, transitionBars = skankLiftBars),  // solo
                    ),
                    recencyDecay = 0.5f,
                    // groove is baseline — no macro overrides
                ),
                // 2 CHORUS: Bumped energy, horns prominent, skank shifts to
                // SKA_UPSTROKES for a busier off-beat feel. Classic reggae lift.
                // chorus -> groove / solo: skankLift (gentle return / lead-in).
                // chorus -> dub: dubFade (long descent into the breakdown).
                Section(
                    name = "chorus",
                    barsMin = 4, barsMax =4,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.5f, transitionBars = skankLiftBars),
                        SectionTransition(targetIndex = 3, weight = 0.3f, transitionBars = dubFadeBars),
                        SectionTransition(targetIndex = 4, weight = 0.2f, transitionBars = skankLiftBars),
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = MacroOverrides(
                        energy = 1.3f, complexity = 1.2f, mood = 1.25f,
                    ),
                    compingStyle = CompingStyle.SKA_UPSTROKES,   // busier off-beats
                    compingInversion = SectionInversion.FIRST_INVERSION,
                    chordsPerBar = chorusChordsPerBar,
                    customProgression = chorusProgression
                ),
                // 3 DUB: The reggae breakdown — bass drones, everything bathed in
                // reverb and delay. Drummer keeps time but everyone else floats.
                // dub -> groove / chorus: skankLift (re-emerging from the haze).
                Section(
                    name = "dub",
                    barsMin = 4, barsMax = 8,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.6f, transitionBars = skankLiftBars),
                        SectionTransition(targetIndex = 2, weight = 0.4f, transitionBars = skankLiftBars),
                    ),
                    recencyDecay = 0.5f,
                    macroOverrides = MacroOverrides(
                        energy = 0.4f, complexity = 0.5f, space = 1.8f, mood = 1.1f,
                    ),
                    chordFollow = ChordFollow.FIXED,  // everything drones on A
                ),
                // 4 SOLO: Melodica takes the spotlight, LickBuilder evolves the
                // phrase aggressively while the band holds the pocket.
                // solo -> groove / chorus: skankLift.
                // solo -> dub: dubFade (long fade into the breakdown).
                Section(
                    name = "solo",
                    barsMin = 8, barsMax = 12,
                    transitions = listOf(
                        SectionTransition(targetIndex = 1, weight = 0.5f, transitionBars = skankLiftBars),
                        SectionTransition(targetIndex = 2, weight = 0.3f, transitionBars = skankLiftBars),
                        SectionTransition(targetIndex = 3, weight = 0.2f, transitionBars = dubFadeBars),
                    ),
                    recencyDecay = 0.4f,
                    soloMode = SoloMode.LickBuilder(),
                    macroOverrides = MacroOverrides(
                        energy = 0.9f, complexity = 1.4f, space = 1.2f, mood = 1.3f,
                    ),
                ),
            ),
        ),
    )
}
