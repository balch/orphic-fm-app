package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.serialization.IntRangeSerializer

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
 */
@Serializable
data class SectionTransition(
    val targetIndex: Int,
    val weight: Float,
    val transitionBars: Int = 0,
)

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
 * @param density Override per-step density (0..1). Negative = no override.
 * @param volume Override mix volume (0..1).
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
 */
@Serializable
data class TrackSectionOverride(
    val holdProbability: Float? = null,
    val holdLengthMin: Int? = null,
    val holdLengthMax: Int? = null,
    val density: Float? = null,
    val volume: Float? = null,
    val reverbSend: Float? = null,
    val delaySend: Float? = null,
    val envelopeProfile: EnvelopeProfile? = null,
    val compingStyle: CompingStyle? = null,
    val sectionInversion: SectionInversion? = null,
    val arpMode: ArpMode? = null,
    val chordFollow: ChordFollow? = null,
)

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
    /** When leaving this section, fire a master record-scratch of this many ms
     *  (0 = none). The scratch grabs the outgoing audio and drops back to live on
     *  the next section's beat — put it on a build section to scratch into the drop.
     *  Fires once per exit of this section. Useful range ~400..1000 ms. */
    val exitScratchMs: Int = 0,
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
) {
    init {
        customProgression?.let { validateProgression(it, "Section.customProgression") }
        chordsPerBar?.let {
            require(it in 1..4) { "Section.chordsPerBar must be 1..4, got $it" }
        }
        require(barStep in 1..16) { "Section.barStep must be 1..16, got $barStep" }
        require(bpmMultiplier > 0f) { "Section.bpmMultiplier must be > 0, got $bpmMultiplier" }
        require(exitScratchMs >= 0) { "Section.exitScratchMs must be >= 0, got $exitScratchMs" }
        require(bpmRampBars >= 0) { "Section.bpmRampBars must be >= 0, got $bpmRampBars" }
    }
}

/**
 * Section-based song structure. Sections transition between each other using
 * weighted Markov chains, creating organic song forms.
 *
 * Use the built-in presets to get started:
 * - `Arrangement.SIMPLE` — groove ↔ variation (2 sections)
 * - `Arrangement.WITH_SOLOS` — groove → solo → build (3 sections)
 * - `Arrangement.FULL` — intro → verse → chorus → solo → breakdown → outro (6 sections)
 * - `Arrangement.JAM` — groove ↔ improv with IMPROVISERS solos (2 sections, open-ended)
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
    }
}
