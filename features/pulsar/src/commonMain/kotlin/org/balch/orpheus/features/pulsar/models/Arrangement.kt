package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.serialization.IntRangeSerializer

/**
 * The -8..1 offset bound every effects list shares, independent of any edge's own
 * `transitionBars`: C++ clamps a longer offset to its owning section's start, so a wider
 * range would only collapse silently.
 */
private fun requireStrikeOffsetsInRange(effects: List<TransitionEffect>) {
    effects.filterIsInstance<StrikeEffect>().forEach { strike ->
        require(strike.offsetBars in -8f..1f) {
            "StrikeEffect.offsetBars must be -8f..1f, got ${strike.offsetBars}"
        }
    }
}

/**
 * A weighted edge in the section graph.
 * @param targetIndex Index into [Arrangement.sections] for the destination section.
 * @param weight Relative probability of this transition (weights are normalized per section).
 * @param transitionBars If > 0, the macro overrides crossfade smoothly toward the
 *   destination section's overrides over the LAST [transitionBars] bars of the
 *   source section ("pre-roll" model). At the boundary, the destination section
 *   takes over with full character. Default 0 = hard cut at the boundary.
 *
 *   Per-edge so each route can have its own personality:
 *   - chorus lift:    `SectionTransition(STAB, 0.5f, transitionBars = 2)`
 *   - hard verse cut: `SectionTransition(VERSE, 0.5f)` (no ramp)
 *   - long fade-out:  `SectionTransition(DRIFT, 0.2f, transitionBars = 4)`
 * @param effects Dramatic events armed at the flip, fired in list order. Up to
 *   [TransitionEffect.MAX_PER_FLIP]. Replaces the retired single-purpose `Section.exitScratchMs`
 *   field — a [ScratchEffect] here reproduces that behavior. Always (re)arms, even over an
 *   anomaly already in flight on the same target. Unioned with the source section's
 *   [Section.exitEffects] (which fire on every edge including this one) and the destination
 *   section's [Section.entryEffects].
 */
@Serializable
data class SectionTransition(
    val targetIndex: Int,
    val weight: Float,
    val transitionBars: Int = 0,
    val effects: List<TransitionEffect> = emptyList(),
) {
    init {
        require(effects.size <= TransitionEffect.MAX_PER_FLIP) {
            "SectionTransition.effects size ${effects.size} exceeds MAX_PER_FLIP=${TransitionEffect.MAX_PER_FLIP}"
        }
        requireStrikeOffsetsInRange(effects)
    }
}

/**
 * Per-track parameter overrides scoped to a single [Section].
 *
 * Each field is nullable; null means "keep the track's base value from [TrackVoice]".
 * Applied at section transitions by `PulsarViewModel`. Restored to base values
 * automatically when the section ends.
 *
 * Use this to give a track a different character per section — e.g. a pad that
 * holds as a drone during the verse but plays short comping stabs during the
 * chorus.
 *
 * @param holdProbability Override pattern-generator hold-chain probability (0..1).
 *   Only meaningful for tracks 5..7 (effect/pad slots) — tracks 0..4 ignore holds.
 * @param holdLengthMin Override min hold-chain length in steps.
 * @param holdLengthMax Override max hold-chain length in steps.
 * @param density Override per-step density (0..1). Null = no override.
 *   **`0` takes the track OUT for the section** — a clean mute, restored on exit, and it
 *   works for every role. A POSITIVE value regenerates the track's pattern at that density
 *   at the section boundary, which thins fills and ghosts; that regeneration only reaches
 *   tracks whose pattern is generated from density, so on a `Chordal` track or one playing
 *   a `LickMode.Fill`/`Squash` figure a positive density is a no-op (their generators take
 *   no density parameter) while `0` still mutes. Density is a pattern-GENERATION input, so
 *   unlike [volume] it cannot change mid-section — the boundary is where it lands.
 * @param volume Override mix volume (0..1).
 * @param morph Override the voice's morph (0..1), pinning it for the section's duration so
 *   the macro map's `spaceDecay` cannot overwrite it. On the drum engines (BD/SD/HH) morph
 *   is decay, so this is how one section gets a long-ringing kick against a tight snare.
 *   The track's base morph and pin state are restored on exit.
 * @param reverbSend Override per-track reverb send (0..1).
 * @param delaySend Override per-track delay send (0..1).
 * @param envelopeProfile Override envelope profile for this section. Switch e.g.
 *   from `DRONE` (long swelling sustain) to `EFFECT` (short stabs that wash out
 *   under solos).
 * @param compingStyle Override comping style for chordal tracks. e.g. set the
 *   base style to short stabs (`FUNK_STABS` / `JAZZ_COMP`) and use `PAD` here
 *   only for sections that should sustain. Hold params do **not** affect
 *   chordal tracks — comping style is the rhythm source.
 * @param sectionInversion Override voicing inversion for chordal tracks
 *   (e.g. `OPEN_VOICING` in chorus, `ROOT_POSITION` in verse).
 * @param arpMode Override arpeggiation mode for chordal tracks. `ALWAYS` to
 *   force arpeggiation, `NEVER` for block stabs, `AUTO` for engine-default.
 * @param chordFollow Override chord-follow mode for melodic and chordal tracks.
 *   e.g. flip a bass from `ROOT_ONLY` (verse pedal) to `FOLLOW` (chorus walks).
 * @param breatheBars Cycle period for the volume/timbre "breathe" modulation, in loop-units.
 *   0 = off. Starts at the top on section entry and descends first, then repeats; snaps back
 *   to unity on section exit.
 * @param breatheFloor Gain at the bottom of the breathe cycle, 0-1.
 * @param breatheTimbreSpan How much timbre (morph) closes as the breath sinks, 0-1. 0 = gain
 *   only, no timbre movement.
 */
@Serializable
data class TrackSectionOverride(
    val holdProbability: Float? = null,
    val holdLengthMin: Int? = null,
    val holdLengthMax: Int? = null,
    val density: Float? = null,
    val volume: Float? = null,
    val morph: Float? = null,
    val reverbSend: Float? = null,
    val delaySend: Float? = null,
    val envelopeProfile: EnvelopeProfile? = null,
    val compingStyle: CompingStyle? = null,
    val sectionInversion: SectionInversion? = null,
    val arpMode: ArpMode? = null,
    val chordFollow: ChordFollow? = null,
    val breatheBars: Int = 0,
    val breatheFloor: Float = 0f,
    val breatheTimbreSpan: Float = 0f,
) {
    init {
        require(breatheBars >= 0) { "TrackSectionOverride.breatheBars must be >= 0, got $breatheBars" }
        require(breatheFloor in 0f..1f) {
            "TrackSectionOverride.breatheFloor must be 0..1, got $breatheFloor"
        }
        require(breatheTimbreSpan in 0f..1f) {
            "TrackSectionOverride.breatheTimbreSpan must be 0..1, got $breatheTimbreSpan"
        }
    }
}

/**
 * One section in an [Arrangement] — a distinct musical phase like verse, chorus, or solo.
 *
 * @param name Display name (e.g., "groove", "verse", "chorus", "solo", "breakdown").
 * @param barsMin Minimum bars in this section before transition.
 * @param barsMax Maximum bars in this section.
 * @param barStep Increment within `[barsMin, barsMax]` when randomizing the section
 *   length. Default 1 = any value in the range. Set to 2 for "even bars only"
 *   (with `barsMin` even) or "odd bars only" (with `barsMin` odd) — useful for
 *   keeping phrase lengths musical (4-bar / 8-bar phrases). Set to e.g. 4 for
 *   4-bar increments only.
 * @param transitions Where this section can go next. List of (targetIndex, weight,
 *   transitionBars) edges. Empty = terminal section (arrangement ends here).
 *   Per-edge `transitionBars` controls macro pre-roll crossfade — see [SectionTransition].
 * @param recencyDecay Penalty for recently-visited sections in transition selection, 0-1.
 * @param macroOverrides Multiply macro values during this section.
 *   e.g., `MacroOverrides(energy = 1.4f)` boosts energy 40% during this section.
 * @param tensionOverride Replace the vibe's tension profile for this section.
 * @param soloMode Solo mode for this section (null = no solos).
 * @param trackOverrides Per-track parameter overrides specific to this section.
 *   Map of track index (0..7) to [TrackSectionOverride]. Applied at section
 *   entry; restored to track base values at section exit.
 */
@Serializable
data class Section(
    val name: String,
    val barsMin: Int = 4,
    val barsMax: Int = 8,
    val barStep: Int = 1,
    val transitions: List<SectionTransition> = emptyList(),
    val recencyDecay: Float = 0.5f,
    val macroOverrides: MacroOverrides? = null,
    val tensionOverride: TensionProfile? = null,
    val soloMode: SoloMode? = null,
    val trackOverrides: Map<Int, TrackSectionOverride>? = null,
    /** Override all CHORDAL tracks' comping style for this section. null = keep defaults. */
    val compingStyle: CompingStyle? = null,
    /** Override all CHORDAL tracks' section inversion for this section. null = keep defaults. */
    val compingInversion: SectionInversion? = null,
    /** Override CompingHumanization for ALL CHORDAL tracks in this section. null = keep track defaults. */
    val compingHumanization: CompingHumanization? = null,
    /** Override all melodic+chordal tracks' chord-follow mode. null = keep defaults. */
    val chordFollow: ChordFollow? = null,
    /** Per-section chord sequence. Null = inherit vibe's progression.
     *  When set, this section restarts the progression at its degree 0 on entry.
     *  Same constraints as [GenreProfile.customProgression]: size 1..12, degrees 0..6.
     *  Per-chord glide is honored when supplied. */
    val customProgression: List<ChordStep>? = null,
    /** Per-section chord-change rate override. Null = inherit vibe's chordsPerBar.
     *  Valid range 1..4; common values are 1 (static), 2 (standard), 4 (busy). */
    val chordsPerBar: Int? = null,
    /** Per-section tempo multiplier applied on top of the vibe's [Vibe.bpm].
     *  1.0 = no change (default), 0.5 = half-time breakdown, 2.0 = double-time burst.
     *  Useful range ~0.25..2.0. On each section transition the live BPM is
     *  scaled by (newMultiplier / previousMultiplier), so live tempo edits the
     *  user makes during a section carry through into subsequent sections at
     *  their relative multiplier. */
    val bpmMultiplier: Float = 1.0f,
    /** Accelerando: over the last [bpmRampBars] arrangement-bars of this section, ramp
     *  the live BPM from this section's [bpmMultiplier] tempo UP to the vibe's full base
     *  tempo, landing on the next section's downbeat (0 = no ramp; instant flip as before).
     *  Put it on a half-time intro/build to wind up into the drop instead of scratching.
     *  Bars are counted in the same units as [barsMin]/[barsMax]. Prototype: ramps toward
     *  full base tempo (1.0×); see PulsarViewModel accelerando. Useful range 1..2. */
    val bpmRampBars: Int = 0,
    /** Carry an in-flight band solo across THIS section's entry seam. When true
     *  and a solo is active as this section begins (and this section declares a
     *  [soloMode]), the engine skips the section-entry solo reset: same soloist,
     *  same member roles, same evolving LickBuilder live lick and Jam phrase
     *  memory — continuing under THIS section's solo parameters. When no solo is
     *  in flight, a normal fresh solo start (with this section's probability
     *  roll) happens instead. Default false = today's full reset. Use on chained
     *  jam stages so the jam develops across them instead of restarting. */
    val jamCarry: Boolean = false,
    /** Storm/rain ambience layered under this section while active. Crossfades in over the
     *  entry pre-roll and drains on the walk-back, same lerp as the macro overrides.
     *  Null = no weather (all-zero bed, no strikes). */
    val weather: SectionWeather? = null,
    /** Dramatic events fired whenever this section ENDS, on every outgoing edge — the
     *  section-wide counterpart to per-edge [SectionTransition.effects]. The two are a
     *  UNION at a flip: both this list and the taken edge's own list stage and fire, which
     *  is why the combined total is capped at [TransitionEffect.MAX_PER_FLIP] below.
     *
     *  A TERMINAL section (no [transitions]) never flips, so its exitEffects never fire;
     *  ending the song is separate machinery ([Arrangement.transitionOut]). */
    val exitEffects: List<TransitionEffect> = emptyList(),
    /** Dramatic events fired whenever this section BEGINS, whichever section it came from —
     *  the mirror of [exitEffects]. Authoring an arrival here costs one row no matter how many
     *  edges lead in, instead of the same effect copied onto every inbound edge.
     *
     *  At a flip all THREE lists are a union: the departing section's [exitEffects], the taken
     *  edge's [SectionTransition.effects], and this list. [Arrangement] caps that combined total
     *  at [TransitionEffect.MAX_PER_FLIP] — only it can see all three.
     *
     *  Does NOT fire for the arrangement's OPENING section at song start: there is no transition
     *  into it, and these fire on flips only. A section re-entered by a self-edge does fire.
     *
     *  Offsets are measured from this section's own first downbeat: a [StrikeEffect] at 0 or
     *  below lands on it, +1 fires one bar in. Negatives cannot reach back into the departing
     *  section — the edge that arrives is not known until the flip — so they collapse to 0. */
    val entryEffects: List<TransitionEffect> = emptyList(),
) {
    init {
        customProgression?.let { validateProgression(it, "Section.customProgression") }
        chordsPerBar?.let {
            require(it in 1..4) { "Section.chordsPerBar must be 1..4, got $it" }
        }
        require(barStep in 1..16) { "Section.barStep must be 1..16, got $barStep" }
        require(bpmMultiplier > 0f) { "Section.bpmMultiplier must be > 0, got $bpmMultiplier" }
        require(bpmRampBars >= 0) { "Section.bpmRampBars must be >= 0, got $bpmRampBars" }
        require(exitEffects.size <= TransitionEffect.MAX_PER_FLIP) {
            "Section.exitEffects size ${exitEffects.size} exceeds MAX_PER_FLIP=${TransitionEffect.MAX_PER_FLIP}"
        }
        require(entryEffects.size <= TransitionEffect.MAX_PER_FLIP) {
            "Section.entryEffects size ${entryEffects.size} exceeds MAX_PER_FLIP=${TransitionEffect.MAX_PER_FLIP}"
        }
        requireStrikeOffsetsInRange(exitEffects)
        requireStrikeOffsetsInRange(entryEffects)
        // exitEffects union the edge's own list at every flip, and C++ stages only
        // MAX_PER_FLIP pending slots — the worst edge has to fit, not just each list.
        val worstEdge = transitions.maxOfOrNull { it.effects.size } ?: 0
        require(exitEffects.size + worstEdge <= TransitionEffect.MAX_PER_FLIP) {
            "Section.exitEffects (${exitEffects.size}) + its busiest edge's effects ($worstEdge) " +
                "exceeds MAX_PER_FLIP=${TransitionEffect.MAX_PER_FLIP}"
        }
    }
}

/**
 * Section-based song structure. Sections transition between each other using
 * weighted Markov chains, creating organic song forms. Every shipped vibe writes
 * its own sections; there are no arrangement presets. A section carrying a
 * [SoloMode] also needs a [Vibe.band] — see [BandPresets] for ready-made ones.
 *
 * @param sections Up to 8 sections.
 * @param introIndex Which section to start with (default 0; pass null for random weighted choice).
 * @param outroIndex Which section ends the arrangement (null = loops forever).
 * @param defaultSectionBars Default bar count if a section doesn't specify.
 * @param lengthSeconds Range of vibe playing-time in seconds. The lower bound is
 *   the earliest time the auto-end probability can begin rolling (song never ends
 *   before it). The upper bound forces auto-end on the next bar. Default 150..300
 *   (2:30–5:00). Both endpoints must be in 15..1800.
 * @param transitionOut Per-vibe transition-out spec. Null = inherit global default
 *   from `AppPreferences.pulsarTransitionDefault`. When set, the vibe owns the
 *   entire spec — no partial overrides.
 */
@Serializable
data class Arrangement(
    val sections: List<Section>,
    val introIndex: Int? = 0,
    val outroIndex: Int? = null,
    val defaultSectionBars: Int = 8,
    @Serializable(with = IntRangeSerializer::class)
    val lengthSeconds: IntRange = 150..240,
    val transitionOut: TransitionSpec? = null,
) {
    val minVibeSeconds: Int get() = lengthSeconds.first
    val maxVibeSeconds: Int get() = lengthSeconds.last

    init {
        require(sections.size <= MAX_SECTIONS) {
            "Arrangement sections size ${sections.size} exceeds MAX_SECTIONS=$MAX_SECTIONS"
        }
        require(minVibeSeconds in 15..1800) {
            "Arrangement.minVibeSeconds must be 15..1800, got $minVibeSeconds"
        }
        require(maxVibeSeconds in minVibeSeconds..1800) {
            "Arrangement.maxVibeSeconds must be in $minVibeSeconds..1800, got $maxVibeSeconds"
        }
        // Both reach C++ as raw section indices. null is the documented "unset" sentinel.
        introIndex?.let {
            require(it in sections.indices) {
                "Arrangement.introIndex must be in 0..${sections.size - 1}, got $it"
            }
        }
        outroIndex?.let {
            require(it in sections.indices) {
                "Arrangement.outroIndex must be in 0..${sections.size - 1}, got $it"
            }
        }
        // Three lists stage at one flip — the source's exitEffects, the taken edge's own
        // effects, and the DESTINATION's entryEffects. Section.init enforces the first two;
        // only the arrangement can see the third. C++ holds kMaxPendingFx and silently drops
        // the rest, so this is what keeps that unreachable from authored data.
        sections.forEachIndexed { s, source ->
            source.transitions.forEach { edge ->
                val target = sections.getOrNull(edge.targetIndex) ?: return@forEach
                val total = source.exitEffects.size + edge.effects.size + target.entryEffects.size
                require(total <= TransitionEffect.MAX_PER_FLIP) {
                    "Section $s '${source.name}' -> ${edge.targetIndex} '${target.name}' stages " +
                        "$total effects at one flip: exitEffects=${source.exitEffects.size} + " +
                        "edge effects=${edge.effects.size} + entryEffects=${target.entryEffects.size} " +
                        "exceeds MAX_PER_FLIP=${TransitionEffect.MAX_PER_FLIP}"
                }
            }
        }
    }

    companion object {
        /**
         * Sections per arrangement. MUST equal `kMaxSections` in
         * `liborpheus_dsp/src/pulsar_limits.h` — the arrangement crosses into the audio
         * thread through preallocated atomic arrays sized from that constant, and the
         * routing layer SILENTLY DROPS writes past the bound. `PulsarSectionLimitsTest`
         * parses the header and fails if these drift apart.
         */
        const val MAX_SECTIONS = 12

        /**
         * Outgoing transitions per section, and the stride this side writes with. MUST
         * equal `kMaxSectionTransitions`. Distinct from [MAX_SECTIONS]: they were both 8
         * for a long time, which hid a stride mismatch in the C++ transition unpack.
         */
        const val MAX_SECTION_TRANSITIONS = 8

        /**
         * Floats per section in the `section_data_$i` bank (slots 0-20 existing fields,
         * 21-25 [SectionWeather]). MUST equal `kSectionDataFields` in `pulsar_limits.h`.
         * `PulsarSectionLimitsTest` parses the header and fails if these drift apart.
         *
         * NOT shared with the co-located `pulsar_section_tension_data` bank: that array
         * reuses this constant only for its C++-side allocation size, but its own
         * per-section stride is independently fixed at 21 (see the `tBase`/`tb` literals
         * in `PulsarFeature.pushArrangement` and `orpheus_unit_pulsar.cpp`) — tension never
         * grew the 5 weather slots, so bumping this constant again must NOT touch those.
         */
        const val SECTION_DATA_FIELDS = 26
    }
}
