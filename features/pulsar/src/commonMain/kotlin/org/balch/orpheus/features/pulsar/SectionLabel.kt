package org.balch.orpheus.features.pulsar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState

/** Who is soloing, or null: the band member's name when the vibe has a band, else the track name. */
fun PulsarArrangementState.soloistName(): String? {
    if (!soloActive || soloTrack < 0) return null
    return if (bandSolo) {
        bandMemberNames.getOrElse(soloTrack) { "?" }
    } else {
        PULSAR_TRACK_NAMES.getOrElse(soloTrack) { "?" }
    }
}

/** The section line the step grid and the mixer show: [plain] in the base colour, then [accent] in the accent colour. */
data class SectionReadout(val plain: String, val accent: String = "") {
    fun annotated(base: Color, accentColor: Color): AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(color = base)) { append(plain) }
        withStyle(SpanStyle(color = accentColor)) { append(accent) }
    }
}

/**
 * "section bar X/Y [▸ soloist]", with " — STYLE" when the final section is playing and a transition is pending.
 * While a transition fires it is "▸ STYLE" alone, all accent. Null when there is no section and no transition.
 */
fun sectionReadout(
    sectionName: String?,
    state: PulsarArrangementState?,
    activeTransition: TransitionStyle?,
    finalSectionIndex: Int,
    pendingTransition: TransitionStyle?,
): SectionReadout? {
    if (activeTransition != null) return SectionReadout("", "▸ ${activeTransition.name}")
    if (sectionName == null) return null
    val bar = (state?.barsElapsed ?: 0) + 1
    val solo = state?.soloistName()?.let { " ▸ $it" } ?: ""
    val isFinal = state != null && finalSectionIndex >= 0 && state.sectionIndex == finalSectionIndex && pendingTransition != null
    return SectionReadout(
        plain = "$sectionName $bar/${state?.barsTotal ?: 0}$solo",
        accent = if (isFinal) " — ${pendingTransition?.name}" else "",
    )
}
