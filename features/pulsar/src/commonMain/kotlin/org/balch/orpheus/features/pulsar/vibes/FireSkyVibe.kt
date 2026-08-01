package org.balch.orpheus.features.pulsar.vibes

import com.diamondedge.logging.Platform.name
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.LickAnomaly
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.ArpDirection
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.Arrangement
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
import org.balch.orpheus.features.pulsar.models.HalfLick
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickRotation
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.LpgMode
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
import org.balch.orpheus.features.pulsar.models.chords

// The founding 2-bar hook (BLUES: 0=G,1=Bb/b3,2=C/4,3=Db/b5,4=D/5,5=F/b7) — the OG A/B
// reference and Fire Sky .5f's rare anomaly flash.
private val ogLick by lazy {
    Lick(
        steps = listOf(
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.85f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.70f),
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.85f),
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 0, duration = 1.5f, velocity = 0.90f),
        ),
        loopLength = 8,
    )
}

private val aiLick by lazy {
    Lick(
        steps = listOf(
            // bar 1 — the climb, stated once: G D F, the b7 rings out the bar
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.90f),
            LickStep(scaleDegree = 5, duration = 3.0f, velocity = 0.88f),  // the hook lands — let ring
            // bar 2 — the answer: climb again, but fall through the b5 to home
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.90f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.72f),  // the b5, descending crush
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.82f),
            LickStep(scaleDegree = 0, duration = 2.0f, velocity = 0.95f),  // home, rings 2 beats
        ),
        loopLength = 8,
    )
}

private val tweakLick by lazy {
    Lick(
        steps = listOf(
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 0, duration = 1.0f, velocity = 0.85f),
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.70f),
            LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.85f),
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.95f),
            LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 0, duration = 1.0f, velocity = 0.85f),
            LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.80f),
            LickStep(scaleDegree = 1, duration = 0.25f, velocity = 0.90f),
            LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.70f),
            LickStep(scaleDegree = 3, duration = 1.0f, velocity = 0.90f),
        ),
        loopLength = 8,
    )
}

/**
 * Fire Sky (OG) — FROZEN A/B reference: the founding hook stated exactly, kept so riff
 * variants can be compared against the original statement. Catalog status WIP: dev-only,
 * visible on debuggable / `-Pcatalog=wip` builds; not for LIVE promotion — the shipping
 * vibes are the variants, this file is the reference. Reuses the live [FireSkyVibe]
 * wholesale and swaps ONLY the lick + mutation, so an A/B isolates the riff. Do not
 * edit; git commit ee8677e0 is the hard, fully-frozen snapshot.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class FireSkyOgVibe : VibeProvider {
    override val name: String = "Fire Sky OG"

    override val vibe: Vibe by lazy {
        FireSkyVibeBase().vibe.copy(
            name = name,
            lick = ogLick,
            lickMutation = 0.10f,
            stepCount = 32
        )
    }
}

@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class FireSkyCxVibe : VibeProvider {
    override val name: String = "Fire Sky CX"

    override val vibe: Vibe by lazy {
        FireSkyVibeBase().vibe.copy(
            name = name,
            lick = tweakLick,
            lickMutation = 0.60f,
            stepCount = 32
        )
    }
}

@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class FireSkyVibe : VibeProvider {
    override val name: String = "Fire Sky"
    override val vibe: Vibe by lazy {
        FireSkyVibeBase().vibe.copy(
            name = name,
            lick = aiLick,
            lickMutation = 0.25f,
            lickRotation = LickRotation(pool = listOf(aiLick, tweakLick)),
            anomalies = listOf(
                // A rare flash of the founding hook, stated exactly; also fired by
                // the manual anomaly trigger.
                LickAnomaly(lick = ogLick, chance = 0.02f),
            ),
            stepCount = 32,
        )
    }
}

/**
 * Fire Sky .5f — a flat 60 BPM Fire Sky (no half-time — every section, including
 * intro/build, runs at the same tempo, and the build's exit scratch is off) that
 * ROTATES between [aiLick] and [tweakLick] per section, with the rare [ogLick]
 * anomaly: a flash of the founding hook stated exactly, also fired by the
 * manual anomaly trigger.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class FireSky05Vibe : VibeProvider {
    override val name: String = "Fire Sky .5f"

    override val vibe: Vibe by lazy {
        FireSkyVibeBase(baseBpm = 60f, halfTimeMult = 1.0f, buildExitScratchMs = 0).vibe.copy(
            name = name,
            lick = tweakLick,  // fallback seed; the pool overrides it at load
            lickRotation = LickRotation(pool = listOf(tweakLick, aiLick)),
            anomalies = listOf(
                // A rare flash of the founding hook, stated exactly; also fired by
                // the manual anomaly trigger.
                LickAnomaly(lick = ogLick, chance = 0.02f),
            ),
            lickMutation = 0.20f,
            stepCount = 32,
        )
    }
}

/**
 * Fire Sky — a relentless, low-and-gritty hard-rock stomp built around an original
 * three-note power-climb hook on the BLUES scale in G.
 *
 * ## The feel
 * Low and gritty. A heavy, articulate riff hammers in the low-mid guitar register
 * over a stomping backbeat, a vintage drawbar-organ comping on the downbeats, and
 * a second guitar doubling the riff an octave down. It is dry-ish and forward —
 * garage/club, not hall. The hook is a rising three-note power climb (root -> 5th ->
 * ringing b7), stated once per bar and left to ring — the punch lives in the space
 * after each hit. The signature blue note (the b5) appears once per loop as a
 * descending passing tone, and the figure resolves home on a long, let-ring tonic
 * before it starts over.
 *
 * ## Riff doubling (pseudo-polyphony)
 * A Pulsar [Lick] is strictly MONOPHONIC: one scaleDegree per step — chords are
 * unrepresentable. Width comes from track 6 playing the SAME lick as track 4 with
 * a noteRange one octave lower. In FOLLOW sections the per-track octave pin folds
 * each track's render into its own register (true octave doubling); FIXED sections
 * skip the pin, so both tracks render the raw generated octave (unison thickening).
 * Both are intentional: verses get spread, choruses slam in unison. The two tracks
 * roll density and mutation with independent seeds, so the double is loose, not
 * sample-accurate — two guitarists, not one chorused voice.
 *
 * ## Scale mapping
 * Root = G, ScaleType.BLUES (degrees {0,3,5,6,7,10} semitones). So a LickStep's
 * scaleDegree indexes that list: 0 = G (root), 1 = Bb (b3), 2 = C (4th),
 * 3 = Db (b5 — the blue note), 4 = D (5th), 5 = F (b7), 6 = G (octave). The hook
 * opens 0 -> 4 -> 5 (G-D-F) and the lone b5 lands mid-descent in the bar-2 answer
 * (4 -> 3 -> 1, i.e. D -> Db -> Bb).
 *
 * ## Arrangement
 * intro (HALF-TIME cold open: the hook ALONE at ~60 BPM — its heaviest pace —
 * over a soft kick, nothing else) -> build (still half-time; the organ + bass
 * swell in under the slow riff) -> verse (THE DROP: tempo snaps back to full 84 and
 * the whole band crashes in — this is the electro version) -> chorus (driving peak,
 * lead pedals FIXED so the bVII/IV moves read as a hammering hook rather than an
 * octave-fold lurch) -> solo (band jams, riff develops) -> breakdown (one long
 * anticipation build) -> outro (climactic stomp).
 *
 * The half-time intro and the full-tempo body play the SAME [Lick] at two speeds —
 * pace lives in the sequencer clock, not the notes, so a per-section bpmMultiplier is
 * all it takes. The engine floors BPM at 60, so the slow sections use 0.72x (not a
 * literal 0.5x, which would clamp 42->60 and then over-restore the drop to 120).
 * A/B against DogHouseVibe.
 */
private class FireSkyVibeBase(
    private val baseBpm: Float = 84f,
    private val halfTimeMult: Float = 0.72f,
    private val buildExitScratchMs: Int = 500,
) {

    // The riff hangs on the i (G), then makes the iconic hard-rock move: down to
    // the bVII (degree 6) and up to the IV (degree 3), then home.
    //   i – i – i – i – bVII – IV – i – i
    private val mainProgression = chords(0, 0, 0, 0, 6, 3, 0, 0)
    private val chordsPerBar = 1

    // Tighter two-chord pump for the chorus/outro — i pedal punching to bVII/IV.
    private val chorusProgression = chords(0, 0, 6, 3)
    private val chorusChordsPerBar = 2

    // Per-edge transition ramps named for their musical role, not the bar count.
    //   liftBars    — standard climb between adjacent-energy sections.
    //   dropBars    — exhale out of a high-energy section.
    //   bigLiftBars — the breakdown -> chorus anticipation build (breakdown is
    //                 locked to 4 bars, so the whole section becomes one long ramp).
    private val liftBars = 2
    private val dropBars = 3
    private val bigLiftBars = 4

    // The vibe's tension arc. Pulled out so the build section can .copy() it with
    // halfLick = HalfLick.JAM (jam the first-bar hook) + wider tone evolution.
    private val baseTension = TensionProfile(
        innerBars = 8,   // >= 7 enables spurt + octave climax
        outerBars = 32,
        outerDepth = 0.55f,
        volume = 0.30f,
        tonal = TonalTension(octaveShift = true, chromaticPassing = 0.10f),  // riff jumps an octave at the peak
        timing = 0.20f,
        evolution = EvolutionTension(
            timbreLow = 0.25f, timbreHigh = 0.62f, timbreProbability = 0.7f,  // the distortion breathes
            morphLow = 0.35f, morphHigh = 0.55f, morphProbability = 0.5f,
            attackPoint = 0.6f, releaseSpeed = 0.35f,  // peak past midpoint = a build into the next phrase
        ),
        spurtChance = 0.10f,  // occasional lick-mutation spurt — the riff gets wilder sometimes
    )

    // Build-section tension: the lead's tone breathes harder here (wider morph/timbre,
    // faster inner ramp) as the band swells into the drop, and octaveShift is off so the
    // slow riff stays grounded. halfLick stays off — it's reserved for solo use later.
    private val buildTension = baseTension.copy(
        innerBars = 3,  // fast tension ramp so the tone actually moves within the short build
        tonal = baseTension.tonal.copy(halfLick = HalfLick.OFF, octaveShift = false),
        evolution = baseTension.evolution.copy(
            timbreLow = 0.20f, timbreHigh = 0.75f, timbreProbability = 0.85f,
            morphLow = 0.30f, morphHigh = 0.72f, morphProbability = 0.75f,
            attackPoint = 0.35f,  // tone reaches its peak earlier in the 2-bar section
        ),
    )

    private val leadInTension = baseTension.copy(
        innerBars = 2,  // fast tension ramp so the tone actually moves within the short build
        tonal = baseTension.tonal.copy(halfLick = HalfLick.JAM_INVERTED, octaveShift = false),
        evolution = baseTension.evolution.copy(
            timbreLow = 0.20f, timbreHigh = 0.75f, timbreProbability = 0.85f,
            morphLow = 0.30f, morphHigh = 0.72f, morphProbability = 0.75f,
            attackPoint = 0.35f,  // tone reaches its peak earlier in the 2-bar section
        ),
    )

    val sectionList by lazy {
        listOf(
            // 0: intro (cold open) — HALF-TIME. The hook ALONE at its heaviest
            //    pace: just the riff + a soft kick pulse, everything else out.
            //    bpmMultiplier 0.72 drops 84 -> ~60.48 BPM (the engine's 60 floor; a
            //    literal 0.5 would clamp 42->60 and then over-restore the drop to 120).
            //    Lead pinned FIXED so the bare riff doesn't transpose.
            Section(
                name = "intro",
                barsMin = 2, barsMax = 2,   // one slow riff statements (the riff loops every 2 bars)
                bpmMultiplier = halfTimeMult,      // ~60 BPM half-time cold open — the riff's true pace
                transitions = listOf(
                    SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = liftBars),  // -> build
                ),
                macroOverrides = MacroOverrides(
                    energy = 0.45f, complexity = 0.30f, space = 0.55f, mood = 0.5f,
                ),
                customProgression = chords(0),  // hang on the i alone
                chordsPerBar = 1,
                trackOverrides = mapOf(
                    1 to TrackSectionOverride(density = 0.0f),                   // snare out
                    2 to TrackSectionOverride(density = 0.0f),                   // hats out
                    3 to TrackSectionOverride(density = 0.0f),                   // bass out — riff + kick only
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),  // bare riff, no transpose
                    5 to TrackSectionOverride(density = 0.0f),                   // organ out
                    6 to TrackSectionOverride(density = 0.0f),                   // riff double out — single voice
                    7 to TrackSectionOverride(density = 0.0f),                   // texture out — truly bare
                ),
            ),
            // 1: build — STILL HALF-TIME. The organ enters and the bass creeps in under
            //    the slow riff, swelling toward the drop. Snare/hats/rhythm-gtr stay out
            //    so they all crash in together at the verse (the "band kicks in"). Energy
            //    lifts over the last liftBars into the drop.
            Section(
                name = "build",
                barsMin = 2, barsMax = 2,   // two more slow statements as the organ swells
                bpmMultiplier = halfTimeMult,      // same ~60 BPM half-time as the cold open
                exitScratchMs = buildExitScratchMs, // record-scratch out of the build's tail, into the drop
                transitions = listOf(
                    SectionTransition(targetIndex = 2, weight = 1.0f, transitionBars = liftBars),  // -> verse = THE DROP (tempo snaps to 84)
                ),
                macroOverrides = MacroOverrides(
                    energy = 0.65f, complexity = 0.42f, space = 0.45f, mood = 0.6f,
                ),
                customProgression = chords(0),  // still hanging on the i — hypnotic slow riff
                chordsPerBar = 1,
                // The lead's tone breathes harder as the organ swells (buildTension:
                // wider morph/timbre, no octave leap). The full tension arc returns at
                // the verse, which carries no override.
                tensionOverride = buildTension,
                trackOverrides = mapOf(
                    1 to TrackSectionOverride(density = 0.0f),                   // snare still out
                    2 to TrackSectionOverride(density = 0.0f),                   // hats still out
                    3 to TrackSectionOverride(density = 0.30f),                  // bass creeps in
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),  // riff still FIXED
                    5 to TrackSectionOverride(density = 0.32f, volume = 0.52f),  // organ ENTERS — the swell
                    6 to TrackSectionOverride(density = 0.0f),                   // riff double still out — saved for the drop
                    7 to TrackSectionOverride(density = 0.0f),                   // texture out
                ),
            ),
            // 2: verse — full-band groove, the baseline. macroOverrides = null (verse IS baseline).
            //    THE DROP lands here: tempo snaps from ~60 back to 84 and the full band enters.
            Section(
                name = "verse",
                barsMin = 4, barsMax = 6,
                transitions = listOf(
                    SectionTransition(targetIndex = 3, weight = 1.0f, transitionBars = liftBars),  // -> chorus
                ),
                recencyDecay = 0.5f,
                macroOverrides = null,
            ),
            // 3: chorus — fuller, driving peak. Lead PEDALS FIXED so the bVII/IV moves
            //    read as a hammering hook, not an octave-fold lurch. Tighter pump progression.
            Section(
                name = "chorus",
                barsMin = 2, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 4, weight = 0.75f, transitionBars = dropBars),  // -> solo
                    SectionTransition(targetIndex = 6, weight = 0.25f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 1.2f, complexity = 1.2f, space = 0.7f, mood = 1.15f,
                ),
                customProgression = chorusProgression,
                chordsPerBar = chorusChordsPerBar,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),  // riff pedals — no lurch at the peak
                    5 to TrackSectionOverride(density = 0.24f, volume = 0.58f),  // organ comes forward
                    6 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),  // double pinned WITH the lead — unison slam
                ),
            ),
            // 4: lead-in - jam the first bar of the riff
            Section(
                name = "lead-in",
                barsMin = 2, barsMax = 2,
                tensionOverride = leadInTension,
                transitions = listOf(
                    SectionTransition(targetIndex = 5, weight = 1.0f, transitionBars = liftBars),  // -> solo
                ),
                recencyDecay = 0.4f,
                macroOverrides = MacroOverrides(
                    energy = 1.9f, complexity = 1.4f, space = .75f, mood = 1.2f,
                ),
                soloMode = SoloMode.LongFill(probability = 0.85f),
            ),
            // 5: solo — band jams over the groove; lead develops the riff (FOLLOW kept).
            Section(
                name = "solo",
                barsMin = 4, barsMax = 6,
                transitions = listOf(
                    SectionTransition(targetIndex = 3, weight = 0.33f, transitionBars = liftBars),  // -> chorus
                    SectionTransition(targetIndex = 2, weight = 0.33f, transitionBars = liftBars),  // -> verse
                    SectionTransition(targetIndex = 6, weight = 0.34f, transitionBars = dropBars),  // -> breakdown
                ),
                recencyDecay = 0.4f,
                macroOverrides = MacroOverrides(
                    energy = 0.9f, complexity = 1.4f, space = 1.15f, mood = 1.2f,
                ),
                soloMode = SoloMode.Jam(probability = 0.85f),
                trackOverrides = mapOf(
                    6 to TrackSectionOverride(density = 0.40f),  // double thins while the lead jams
                ),
            ),
            // 6: breakdown — stripped to bass + drums + organ. Locked to 4 bars =
            //    one long anticipation build into the chorus (bigLift).
            Section(
                name = "breakdown",
                barsMin = 4, barsMax = 4,
                transitions = listOf(
                    SectionTransition(targetIndex = 3, weight = 0.70f, transitionBars = bigLiftBars), // -> chorus (THE lift)
                    SectionTransition(targetIndex = 2, weight = 0.30f, transitionBars = liftBars),     // -> verse
                ),
                recencyDecay = 0.5f,
                macroOverrides = MacroOverrides(
                    energy = 0.45f, complexity = 0.5f, space = 1.35f, mood = 0.85f,
                ),
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(density = 0.25f),  // lead pulls way back
                    6 to TrackSectionOverride(density = 0.0f),   // riff double out
                ),
            ),
            // 7: outro — the climactic stomp: riff full-throttle, everyone in, pinned FIXED.
            Section(
                name = "outro",
                barsMin = 4, barsMax = 6,
                macroOverrides = MacroOverrides(
                    energy = 1.5f, complexity = 0.5f, space = 0.5f, mood = 1.1f,
                ),
                customProgression = chorusProgression,
                chordsPerBar = chorusChordsPerBar,
                trackOverrides = mapOf(
                    4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),  // riff hammers home, no lurch
                    6 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),  // double pinned WITH the lead
                ),
            ),
        )
    }

    val vibe: Vibe by lazy {
        Vibe(
            name = name,
            album = Album.ZERO_TO_ONE,
            bpm = baseBpm,
            arrangement = Arrangement(
                introIndex = 0,
                outroIndex = sectionList.lastIndex,
                lengthSeconds = 120..166,
                sections = sectionList,
            ),
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.G,
            scaleType = ScaleType.BLUES,
            seed = 0,
            // --- macro defaults: driving, locked, dry-ish rock ---
            energy = 0.54f,
            complexity = 0.78f,
            space = 0.40f,
            mood = 0.75f,
            deep = 0.48f,
            lickMutation = 0.10f,  // low drift keeps the octave double coherent; distance now lives in the notes
            lickOctave = -1,       // auto = midpoint of the lead's note range (low-mid guitar register)
            genre = GenreProfile(
                swingAmount = 0.04f,          // near-straight rock; a hair off the grid for human feel
                ghostProbability = 0.18f,     // sparse ghosts — keep the riff clean and stomping
                noteRangeLow = 36,
                noteRangeHigh = 64,
                rhythmDensity = RhythmPattern.BACKBEAT.density,  // the rock backbeat (2 & 4)
                progressionStyle = ProgressionStyle.BLUES,
                chordsPerBar = chordsPerBar,
                customProgression = mainProgression,
            ),
            progressionAnchor = ProgressionAnchor.EVERY_8,  // reset drift each 8-bar phrase
            progressionDriftRange = 0.10f,                  // subtle — the riff hangs on the tonic
            tracks = listOf(
                // Track 0 — Kick (BD)
                OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.88f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.48f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 1 — Snare (SD), the backbeat snap
                OrpheusEngine(
                    engineId = OrpheusEngineId.SD,
                    volume = 0.66f,
                    timbre = 0.55f,
                    reverbSend = 0.12f,
                ).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.10f,
                        density = 0.36f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.FILL,  // fills at phrase boundaries
                    )
                },
                // Track 2 — Hi-hat (HH), driving 8ths
                OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.56f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f,
                        density = 0.58f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // Track 3 — Bass (WSH grit / STR warmth on space), ROOT_ONLY, doubles the riff root.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.80f,
                    harmonics = 0.58f,        // gritty waveshape
                    noteRangeLow = 31,        // G1
                    noteRangeHigh = 50,       // D3
                    reverbBrightness = 0.26f,
                    glideRate = 0.08f,        // tiny slur on chord changes
                    lpgMode = LpgMode.PLUCK,  // articulate attack so the root pulse punches
                    lpgDecay = 0.45f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.STR, lpgMode = LpgMode.SUSTAINED),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.00f,
                        density = 0.50f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,  // locked pocket
                    )
                },
                // Track 4 — Lead (WSH grit / VA on space), THE riff, LickMode.Fill.
                // The gritty WSH lead stands in for the distorted guitar; PLUCK LPG gives
                // every riff note the picked, articulated attack. FOLLOW so it transposes
                // like a real blues figure; intro/chorus/outro pin it FIXED (see sections).
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.78f,
                    harmonics = 0.74f,        // gritty / distorted character
                    timbre = 0.64f,
                    morph = 0.45f,
                    noteRangeLow = 50,        // D3
                    noteRangeHigh = 69,       // A4 — low-mid guitar register
                    reverbSend = 0.16f,
                    delaySend = 0.14f,
                    glideRate = 0.0f,         // no slur — every riff note distinct
                    lpgMode = LpgMode.PLUCK,  // hard pluck per note = picked-guitar attack
                    lpgDecay = 0.50f,
                    lpgColour = 0.55f,
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead.copy(
                            engineId = OrpheusEngineId.DX3,
                            harmonics = 0.031f,       // DX3 idx 1 "Hammond" (auto-pinned)
                            timbre = 0.55f,           // a touch of drawbar brightness
                            morph = 0.50f,
                            lpgMode = LpgMode.SUSTAINED
                        ),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FOLLOW,
                            lickMode = LickMode.Fill,  // full 32-step single continuous phrase
                        ),
                        pan = 0.05f,
                        density = 0.95f,  // high — the riff fires on (nearly) every lick step
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,  // riff repeats exactly; variation = lickMutation only
                    )
                },
                // Track 5 — Organ, the Hammond layer — Chordal, sustained.
                // DX3 harmonics is an auto-pinned 32-step patch selector: 0.031 = idx 1 "Hammond";
                // the space slot's 0.092 sits on a bucket edge and loads idx 2 "E organ 3" or
                // 3 "60s organ" by prior load. Both ear-tuned. Do not interpolate.
                OrpheusEngine(
                    engineId = OrpheusEngineId.VA,
                    volume = 0.50f,
                    harmonics = 0.031f,
                    timbre = 0.55f,
                    morph = 0.50f,
                    noteRangeLow = 48,        // C3
                    noteRangeHigh = 72,       // C5
                    reverbSend = 0.22f,
                    delaySend = 0.08f,
                    reverbBrightness = 0.55f,
                ).let { organ ->
                    TrackVoice(
                        engineEdm = organ,
                        engineSpace = organ.copy(
                            engineId = OrpheusEngineId.DX3,
                            harmonics = 0.092f,
                            reverbSend = 0.32f
                        ),  // organ-family edge value, wetter
                        role = TrackRole.Chordal(
                            chordFollow = ChordFollow.FOLLOW,
                            comping = ChordComping(
                                style = CompingStyle.ROCK_DOWNBEATS,  // hits on 1 & 3 — rock organ stabs
                                arpMode = ArpMode.AUTO,
                                arpSpeed = 0.12f,
                                arpDirection = ArpDirection.UP,
                                sectionInversion = SectionInversion.FIRST_INVERSION,
                                humanization = CompingHumanization(
                                    dropProbability = 0.16f,
                                    ghostProbability = 0.20f,
                                    octaveJumpProbability = 0.18f,
                                    extensionProbability = 0.12f,  // bluesy 7ths/9ths color
                                ),
                                fills = CompingFills(
                                    everyNBars = 8,
                                    fillType = FillType.TURNAROUND,  // bluesy organ rip at the turnaround
                                    skipProbability = 0.50f,
                                ),
                            ),
                        ),
                        pan = -0.22f,
                        density = 0.26f,
                        envelopeProfile = EnvelopeProfile.EFFECT,  // sustained organ pad-stab
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 6 — Riff harmony (WSH / ENS on space) — the lead's lick in parallel
                // fourths below (lickDegreeOffset = -2 in the blues hexatonic: an exact fourth
                // under every riff note except the b5, where the in-scale third substitutes).
                // noteRange G2..C4 sits one octave under the lead's D3..A4, so the octave pin
                // folds this track's render into the lower register (see class KDoc). PLUCK LPG
                // keeps the doubled notes picked and distinct; volume sits under the lead.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.52f,
                    harmonics = 0.68f,        // gritty, slightly less than the lead
                    timbre = 0.58f,
                    modLfoRate = 0.30f,
                    modLfoDepth = 0.15f,
                    modLfoShape = 0.3f,
                    modLfoCoupling = 0.1f,
                    noteRangeLow = 43,        // G2
                    noteRangeHigh = 60,       // C4
                    reverbSend = 0.12f,
                    delaySend = 0.08f,
                    reverbBrightness = 0.45f,
                    lpgMode = LpgMode.PLUCK,
                    lpgDecay = 0.40f,
                ).let { double ->
                    TrackVoice(
                        engineEdm = double,
                        engineSpace = double.copy(
                            engineId = OrpheusEngineId.ENS,
                            lpgMode = LpgMode.SUSTAINED,
                            reverbSend = 0.22f,
                        ),
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.FOLLOW,
                            lickMode = LickMode.Fill,      // follows the lead's riff
                            lickDegreeOffset = -2,         // parallel fourths below
                        ),
                        pan = 0.24f,          // spread against the lead at 0.05
                        density = 0.92f,      // fires with the lead; independent seed = loose double
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 7 — Texture / FX (NSE / PAR on space) — Percussive, sparse.
                OrpheusEngine(
                    engineId = OrpheusEngineId.NSE,
                    volume = 0.30f,
                    modLfoRate = 0.40f,
                    modLfoDepth = 0.25f,
                    modLfoShape = 0.4f,
                    modLfoCoupling = 0.1f,
                    holdProbability = 0.05f,
                    holdLengthMin = 2,
                    holdLengthMax = 4,
                    reverbSend = 0.30f,
                    delaySend = 0.12f,
                    noteRangeLow = 48,
                    noteRangeHigh = 72,
                    reverbBrightness = 0.60f,
                ).let { fx ->
                    TrackVoice(
                        engineEdm = fx,
                        engineSpace = fx.copy(engineId = OrpheusEngineId.PAR),
                        role = TrackRole.Percussive,
                        pan = -0.30f,
                        density = 0.12f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            stepCount = 32,  // 2 bars / 8 beats — the full riff as one LickMode.Fill phrase
            tension = baseTension,
            effects = VibeEffects(
                delayTimeA = 0.18f,       // tight slapback, not spacious
                delayTimeB = 0.28f,
                delayFeedback = 0.22f,    // low-moderate — a couple of repeats, no wash
                delayDamping = 0.45f,
                reverbSize = 0.38f,       // small-to-medium room — garage/club feel
                reverbDamping = 0.50f,
                reverbBrightness = 0.55f, // slightly bright plate
                deepFloor = 0.25f,
            ),
        )
    }
}
