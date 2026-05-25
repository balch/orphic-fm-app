package org.balch.orpheus.features.pulsar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.HorizontalFader

// ─── Per-style UI metadata ───────────────────────────────────────────────────
//
// Kept in the UI layer (not the enum) so `core/foundation`'s TransitionStyle
// stays free of presentation concerns. Extension properties give the same
// ergonomic call-site as enum fields without the layering violation.

@Immutable
private data class StyleMeta(
    val label: String,
    val glyph: String,
    val description: String,
    val defaultHandoffMs: Int,
)

private val META: Map<TransitionStyle, StyleMeta> = mapOf(
    TransitionStyle.CUT to StyleMeta(
        label = "CUT",
        glyph = "✂",
        description = "Instant swap. No fade.",
        defaultHandoffMs = 0,
    ),
    TransitionStyle.GAP to StyleMeta(
        label = "GAP",
        glyph = "⋯",
        description = "Silent gap between Vibes.",
        defaultHandoffMs = 500,
    ),
    TransitionStyle.FADE to StyleMeta(
        label = "FADE",
        glyph = "▽",
        description = "Symmetric fade-out and fade-in.",
        defaultHandoffMs = 350,
    ),
    TransitionStyle.CROSSFADE to StyleMeta(
        label = "XFADE",
        glyph = "⇌",
        description = "Overlap outgoing tail with incoming.",
        defaultHandoffMs = 400,
    ),
    TransitionStyle.TAPE to StyleMeta(
        label = "TAPE",
        glyph = "⏪",
        description = "Tape-stop ramp before the next Vibe.",
        defaultHandoffMs = 300,
    ),
    TransitionStyle.SCRATCH to StyleMeta(
        label = "SCRATCH",
        glyph = "💿",
        description = "Beat-synced stutter gate across the transition.",
        defaultHandoffMs = 500,
    ),
    TransitionStyle.FILTER to StyleMeta(
        label = "FILTER",
        glyph = "🌀",
        description = "Allpass filter sweep with Leslie.",
        defaultHandoffMs = 500,
    ),
    TransitionStyle.RANDOM to StyleMeta(
        label = "RANDOM",
        glyph = "⚄",
        description = "Pick a random style each time.",
        defaultHandoffMs = 0,
    ),
)

private val TransitionStyle.meta: StyleMeta get() = META.getValue(this)

/**
 * Synthetic chip metadata for the PLAYS option. Not a real `TransitionStyle` —
 * selecting PLAYS clears the Vibe-ending enabled flag instead of choosing a
 * transition. Lives in the UI layer so the data model stays clean.
 */
private val PLAYS_META = StyleMeta(
    label = "PLAYS",
    glyph = "▶",
    description = "Vibes loop forever; no auto-advance.",
    defaultHandoffMs = 0,
)
private const val PLAYS_DESCRIPTION = "Vibes loop forever; no auto-advance."

/**
 * Bottom-sheet UI for editing the global transition spec. Opened by tapping the
 * auto-end pill in `PulsarPanel`. Decoupled from `PulsarViewModel`: takes the
 * current [spec], the `enabled` state, plus mutator lambdas, so previews and
 * tests can drive it without DI.
 *
 * Layout:
 *  - Header — section label "STYLE" + one-line description of the selected style
 *  - Style chooser — PLAYS (which maps to `enabled = false`) plus one chip per
 *    `TransitionStyle.isVisible`. Selecting PLAYS disables Vibe-ending entirely;
 *    selecting any other style implicitly re-enables it.
 *  - MORPH row — title + handoff knob (100..2000 ms, snaps to 50 ms; disabled
 *    when the selected style's `canHandoff == false`)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransitionSettingsSheet(
    spec: TransitionSpec,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onStyleChange: (TransitionStyle) -> Unit,
    onHandoffMsChange: (Int) -> Unit,
    inactivityTimeoutMs: Long = INACTIVITY_TIMEOUT_MS,
) {
    val sheetState = rememberModalBottomSheetState()
    val visibleStyles = remember { TransitionStyle.entries.filter { it.isVisible } }
    val handoffMs = remember(spec) {
        spec.handoffMs ?: spec.style.meta.defaultHandoffMs
    }

    // Inactivity timer: any user interaction increments interactionTick, which
    // restarts the LaunchedEffect's delay. After inactivityTimeoutMs of no
    // interaction the sheet dismisses itself. Initial open counts as the first
    // interaction (tick starts at 0 so the timer arms immediately).
    var interactionTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(interactionTick, inactivityTimeoutMs) {
        delay(inactivityTimeoutMs)
        onDismiss()
    }
    val kick: () -> Unit = remember { { interactionTick++ } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OrpheusColors.deepPurple,
        contentColor = OrpheusColors.onSurfaceDark,
        dragHandle = { CosmicDragHandle() },
    ) {
        TransitionSheetContent(
            spec = spec,
            enabled = enabled,
            visibleStyles = visibleStyles,
            handoffMs = handoffMs,
            onSetEnabled = { v -> kick(); onSetEnabled(v) },
            onStyleChange = { v -> kick(); onStyleChange(v) },
            onHandoffMsChange = { v -> kick(); onHandoffMsChange(v) },
        )
    }
}

/**
 * Inner content of the sheet, factored out so it can be hosted by anything
 * that provides a dark surface — including `@Preview` (which can't render a
 * real `ModalBottomSheet` because the preview environment doesn't supply
 * the Scaffold + WindowInsets host the sheet expects).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransitionSheetContent(
    spec: TransitionSpec,
    enabled: Boolean,
    visibleStyles: List<TransitionStyle>,
    handoffMs: Int,
    onSetEnabled: (Boolean) -> Unit,
    onStyleChange: (TransitionStyle) -> Unit,
    onHandoffMsChange: (Int) -> Unit,
) {
    // Description shown alongside the STYLE label. Reflects the user's effective
    // selection — PLAYS-as-disabled has its own description.
    val description = if (!enabled) PLAYS_DESCRIPTION else spec.style.meta.description
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionLabel("END STYLE")
                Text(
                    text = description,
                    color = OrpheusColors.onSurfaceDark.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 24.dp),
                    maxLines = 2,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.End,
                )
            }
            StyleChips(
                visibleStyles = visibleStyles,
                selectedStyle = spec.style,
                enabled = enabled,
                onSelectPlays = { onSetEnabled(false) },
                onSelectStyle = { style ->
                    if (!enabled) onSetEnabled(true)
                    onStyleChange(style)
                },
            )

            HorizontalDivider(
                color = OrpheusColors.cosmicPurple.copy(alpha = 0.18f),
                modifier = Modifier.padding(top = 8.dp),
            )

            HandoffSection(
                handoffMs = handoffMs,
                enabled = enabled && spec.style.canHandoff,
                onHandoffMsChange = onHandoffMsChange,
            )
        }
    }
}

// ─── Subcomposables ──────────────────────────────────────────────────────────

@Composable
private fun CosmicDragHandle() {
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .size(width = 40.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(OrpheusColors.cosmicPurple.copy(alpha = 0.5f)),
    )
}

/**
 * Standalone HANDOFF row. "MORPH" titles the row on the leading edge; the knob
 * sits right-aligned with its ms readout on the leading side of the dial.
 * Dimmed when the selected style's `canHandoff == false`; a small "Not applicable
 * for this style" hint drops below the row in that state.
 */
@Composable
private fun HandoffSection(
    handoffMs: Int,
    enabled: Boolean,
    onHandoffMsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = OrpheusColors.cosmicPurple

    Column(modifier = modifier.alpha(if (enabled) 1f else 0.35f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel("HANDOFF")

            HorizontalFader(
                value = ((handoffMs - HANDOFF_MS_RANGE.start) /
                    (HANDOFF_MS_RANGE.endInclusive - HANDOFF_MS_RANGE.start))
                    .coerceIn(0f, 1f),
                onValueChange = { v ->
                    val ms = HANDOFF_MS_RANGE.start + v *
                        (HANDOFF_MS_RANGE.endInclusive - HANDOFF_MS_RANGE.start)
                    onHandoffMsChange(snapHandoffMs(ms))
                },
                color = accent,
                rightLabel = "${handoffMs}ms",
                trackWidth = 120,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier,
        text = text,
        color = OrpheusColors.cosmicPurple.copy(alpha = 0.7f),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleChips(
    visibleStyles: List<TransitionStyle>,
    selectedStyle: TransitionStyle,
    enabled: Boolean,
    onSelectPlays: () -> Unit,
    onSelectStyle: (TransitionStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = 3,
    ) {
        // PLAYS — virtual chip at position 0 representing "Vibe-ending disabled."
        // Tapping it short-circuits the whole transition flow and clears the
        // selection on any real style.
        StyleChip(
            meta = PLAYS_META,
            selected = !enabled,
            onClick = onSelectPlays,
            modifier = Modifier.weight(1f),
        )
        for (style in visibleStyles) {
            StyleChip(
                meta = style.meta,
                selected = enabled && style == selectedStyle,
                onClick = { onSelectStyle(style) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StyleChip(
    meta: StyleMeta,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = OrpheusColors.cosmicPurple
    val bg = if (selected) accent.copy(alpha = 0.30f) else OrpheusColors.panelSurface.copy(alpha = 0.55f)
    val borderColor = if (selected) accent else accent.copy(alpha = 0.25f)
    val labelColor = if (selected) accent else OrpheusColors.onSurfaceDark.copy(alpha = 0.55f)
    val glyphColor = if (selected) accent.copy(alpha = 0.9f) else OrpheusColors.onSurfaceDark.copy(alpha = 0.35f)
    val borderWidth = if (selected) 1.5.dp else 1.dp

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(text = meta.glyph, color = glyphColor, fontSize = 13.sp)
        Text(
            text = " ${meta.label}",
            color = labelColor,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 0.5.sp,
        )
    }
}

// ─── Constants & helpers ─────────────────────────────────────────────────────

private val HANDOFF_MS_RANGE = 100f..2000f
private const val HANDOFF_SNAP_MS = 50

private fun snapHandoffMs(raw: Float): Int =
    ((raw / HANDOFF_SNAP_MS).toInt() * HANDOFF_SNAP_MS).coerceIn(100, 2000)

/** Sheet auto-dismisses after this many ms of no user interaction. */
private const val INACTIVITY_TIMEOUT_MS: Long = 5_000L

// ─── Previews ────────────────────────────────────────────────────────────────
//
// `ModalBottomSheet` doesn't render correctly in a static `@Preview` because
// the preview environment doesn't host the Scaffold + WindowInsets the sheet
// requires. We preview `TransitionSheetContent` wrapped in a Surface that
// supplies the same deep-purple background — visually identical to the real
// sheet's content area.

@Composable
private fun PreviewHost(spec: TransitionSpec, enabled: Boolean = true) {
    OrpheusTheme {
        Surface(color = OrpheusColors.deepPurple, contentColor = OrpheusColors.onSurfaceDark) {
            TransitionSheetContent(
                spec = spec,
                enabled = enabled,
                visibleStyles = TransitionStyle.entries.filter { it.isVisible },
                handoffMs = spec.handoffMs ?: spec.style.meta.defaultHandoffMs,
                onSetEnabled = {},
                onStyleChange = {},
                onHandoffMsChange = {},
            )
        }
    }
}

@Preview(name = "TransitionSheet – FADE selected", widthDp = 360, heightDp = 320)
@Composable
private fun PreviewTransitionSheetFade() {
    PreviewHost(spec = TransitionSpec(style = TransitionStyle.FADE, handoffMs = 350))
}

@Preview(name = "TransitionSheet – PLAYS (disabled)", widthDp = 360, heightDp = 320)
@Composable
private fun PreviewTransitionSheetPlays() {
    PreviewHost(spec = TransitionSpec(style = TransitionStyle.FADE), enabled = false)
}

@Preview(name = "TransitionSheet – RANDOM selected", widthDp = 360, heightDp = 320)
@Composable
private fun PreviewTransitionSheetRandom() {
    PreviewHost(spec = TransitionSpec(style = TransitionStyle.RANDOM))
}

@Preview(name = "TransitionSheet – CUT (handoff disabled)", widthDp = 360, heightDp = 320)
@Composable
private fun PreviewTransitionSheetCut() {
    PreviewHost(spec = TransitionSpec(style = TransitionStyle.CUT))
}

@Preview(name = "TransitionSheet – TAPE selected", widthDp = 360, heightDp = 320)
@Composable
private fun PreviewTransitionSheetTape() {
    PreviewHost(spec = TransitionSpec(style = TransitionStyle.TAPE, handoffMs = 300))
}
