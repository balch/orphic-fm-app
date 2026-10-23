package org.balch.orpheus.features.ai.tools

import org.balch.orpheus.features.ai.TurnBudget
import orpheus.features.ai.generated.resources.Res

/**
 * JSON example strings embedded in the agent-facing guide prose (the guide (vibe_guide.md) and
 * `TRACK_OVERRIDES_NOTE` in VibeSchemaTool.kt). Extracted so [VibeGuideExamplesTest] can decode
 * each one with the real apply-path Json (`vibeApplyJson`) and assert it is actually interpolated
 * into the shipped text — prose examples can no longer silently drift from the schema.
 */
internal object VibeGuideExamples {
    /** Bare-degree shorthand for `GenreProfile.customProgression` — a I-IV-VI-VII riff, no glide. */
    const val CUSTOM_PROGRESSION_SHORTHAND: String = "[0, 3, 5, 6]"

    /** Object form of the same field, needed when a chord step carries per-chord glide. */
    const val CUSTOM_PROGRESSION_WITH_GLIDE: String =
        """[{"degree": 0}, {"degree": 3, "glideRate": 0.4}]"""

    /** `Section.trackOverrides` shape: override two fields on track 4 for one section. */
    const val TRACK_OVERRIDES_EXAMPLE: String =
        """{ "4": { "chordFollow": "FIXED", "density": 0.3 } }"""

    /** `Section.trackOverrides` shape used to pin only chordFollow (the section-7 pegging recipe). */
    const val TRACK_OVERRIDES_CHORD_FOLLOW_EXAMPLE: String =
        """{ "4": { "chordFollow": "FIXED" } }"""

    /** Polymorphic `compingStyle` object form (NOT a bare enum string). */
    const val COMPING_STYLE_EXAMPLE: String =
        """{ "type": "org.balch.orpheus.features.pulsar.models.CompingStyle.FUNK_STABS" }"""

    /**
     * A complete working `band` — the one any section with a `soloMode` must have. Values mirror
     * `BandPresets.quartet(kit = [0,1,2,7], bass = [3], lead = [5], colour = [4,6])`, the dev-side
     * preset, so the agent and a Kotlin author get the same cast; [VibeGuideExamplesTest] asserts
     * the two stay equal.
     */
    const val MINIMUM_BAND: String = """{
  "members": [
    { "name": "Drummer", "tracks": [0, 1, 2, 7], "alwaysActive": true, "loudness": 0.7, "creativity": 0.3 },
    { "name": "Bassist", "tracks": [3], "loudness": 0.8, "creativity": 0.5 },
    { "name": "Lead", "tracks": [5], "loudness": 0.65, "creativity": 0.6 },
    { "name": "Colour", "tracks": [4, 6], "loudness": 0.4, "creativity": 0.7 }
  ],
  "handoffMatrix": [0.0, 0.3, 0.45, 0.1,  0.2, 0.0, 0.5, 0.15,  0.15, 0.4, 0.0, 0.25,  0.1, 0.3, 0.45, 0.0],
  "pullInMatrix":  [0.0, 0.3, 0.25, 0.1,  0.25, 0.0, 0.4, 0.15,  0.2, 0.4, 0.0, 0.2,  0.1, 0.25, 0.3, 0.0],
  "barsPerLeadMin": 4, "barsPerLeadMax": 8
}"""
}

/** `{{NAME}}` placeholders in vibe_guide.md, filled from [VibeGuideExamples] and [TurnBudget]'s caps. */
private val guidePlaceholders: Map<String, String> = mapOf(
    "MINIMUM_BAND" to VibeGuideExamples.MINIMUM_BAND,
    "TRACK_OVERRIDES_CHORD_FOLLOW_EXAMPLE" to VibeGuideExamples.TRACK_OVERRIDES_CHORD_FOLLOW_EXAMPLE,
    "CUSTOM_PROGRESSION_SHORTHAND" to VibeGuideExamples.CUSTOM_PROGRESSION_SHORTHAND,
    "CUSTOM_PROGRESSION_WITH_GLIDE" to VibeGuideExamples.CUSTOM_PROGRESSION_WITH_GLIDE,
    "MAX_APPLIES" to TurnBudget.MAX_APPLIES.toString(),
    "MAX_MIX_EDITS" to TurnBudget.MAX_MIX_EDITS.toString(),
)

/** Substitutes every `{{NAME}}` in [template]; an unknown name throws so a typo cannot ship. */
internal fun fillGuidePlaceholders(template: String, values: Map<String, String> = guidePlaceholders): String =
    Regex("""\{\{(\w+)\}\}""").replace(template) { m ->
        values[m.groupValues[1]] ?: error("vibe_guide.md has unknown placeholder {{${m.groupValues[1]}}}")
    }

/**
 * The composition guide handed to the in-app agent before it builds a vibe. One Markdown resource
 * (files/vibe_guide.md) shared with Claude Code; JSON examples come in through {{NAME}} placeholders.
 */
internal suspend fun loadStaticGuide(): String =
    fillGuidePlaceholders(Res.readBytes("files/vibe_guide.md").decodeToString())
