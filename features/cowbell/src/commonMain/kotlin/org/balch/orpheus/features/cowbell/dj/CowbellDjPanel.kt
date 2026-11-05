package org.balch.orpheus.features.cowbell.dj

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.plugin.symbols.CowbellSymbol
import org.balch.orpheus.features.cowbell.BellBendFader
import org.balch.orpheus.features.cowbell.BellFit
import org.balch.orpheus.features.cowbell.BellMorphStyle
import org.balch.orpheus.features.cowbell.CowbellBell3D
import org.balch.orpheus.features.cowbell.CowbellBodySelector
import org.balch.orpheus.features.cowbell.CowbellFeature
import org.balch.orpheus.features.cowbell.CowbellKnob
import org.balch.orpheus.features.cowbell.CowbellPanelActions
import org.balch.orpheus.features.cowbell.CowbellPanelColor
import org.balch.orpheus.features.cowbell.CowbellSelectorHeight
import org.balch.orpheus.features.cowbell.CowbellUiState
import org.balch.orpheus.features.cowbell.CowbellViewModel
import org.balch.orpheus.features.cowbell.NoLevel
import org.balch.orpheus.plugins.cowbell.CowbellMode
import org.balch.orpheus.ui.panels.LocalPanelTopInset
import org.balch.orpheus.ui.theme.OrpheusTheme

internal const val CowbellDjPanelTag = "cowbell-dj-panel"
internal const val CowbellDjKnobsTag = "cowbell-dj-knobs"
internal const val CowbellDjBellTag = "cowbell-dj-bell"
internal const val CowbellDjFaderTag = "cowbell-dj-fader"
internal const val CowbellDjToneTag = "cowbell-dj-knob-tone"
internal const val CowbellDjRingTag = "cowbell-dj-knob-ring"
internal const val CowbellDjIntensityTag = "cowbell-dj-knob-intensity"
internal const val CowbellDjCollapseTag = "cowbell-dj-collapse"

/** The dial's diameter, the spec's smallest (28-32dp): three stacked with readouts fit the closed Duo's panel at font scale 1.3. */
internal val DjKnobSize = 28.dp

/** The controls column's width: Task 2's longest chip text fits it at font scale 1.3. */
internal val DjControlsWidth = 80.dp

/** The user asked for tight dials, so the knobs stack almost touching. */
private val KNOB_GAP = 4.dp

private val COLUMN_GAP = 6.dp

private val PANEL_SIDE_PADDING = 6.dp

/** How full the bell fills its column, leaving room for the tilt sway and a full bend. */
private const val BELL_FIT = 0.86f

/** Collapsed, the bell is the panel's focus, so it fills more and a full bend may brush the edge. */
private const val BELL_FIT_COLLAPSED = 0.92f

/** Collapsed, the bell column is this many times its height wide, so the bell and fader sit as a pair. */
private const val COLLAPSED_BELL_ASPECT = 0.6f

private const val COLLAPSE_MILLIS = 320

/** The band over the bell the selector takes. The fader leaves it too, so bell and fader stay level. */
internal val DjSelectorBand = CollapsePillTop + CowbellSelectorHeight

/** Collapsed, the pill shrinks to its chevron so the selector over the bell clears it on a narrow panel. */
internal val CollapsePillCollapsedWidth = 34.dp

/** Between the drawn Shake bell and the Fill dropdown under it. */
private val FILL_GAP = 8.dp


/** The fader's smallest usable track: the 28dp thumb plus a little travel. */
private val FADER_MIN_TRACK = 48.dp

/** The fader's longest track, centred beside the bell. A full-height one reached too far (user, 2026-11-01). */
internal val DjFaderMaxTrack = 140.dp

private const val FADER_THUMB_WIDTH = 44

/** Moves the fader in from the panel's right edge (user, 2026-11-01). */
private val FADER_END_INSET = 8.dp

/**
 * The DJ app's cowbell in four full-height columns: the controls (a mode dropdown over small
 * chips), the TONE, RING and INTENSITY knobs stacked, the bell (the body selector centred over it,
 * and the Fill dropdown under it in Shake) and the bend fader. Nothing scrolls and nothing opens over the bell, so a hand that is
 * playing is not interrupted. When width is short the bell column gives up width first.
 *
 * A handle beside the bell slides the controls and knobs away; the selector, bell and fader then
 * grow into the room and sit centred. It starts expanded.
 */
@Composable
fun CowbellDjPanel(
    feature: CowbellFeature,
    modifier: Modifier = Modifier,
    outLevel: StateFlow<Float> = NoLevel,
) {
    val state = feature.stateFlow.collectAsStateWithLifecycle()
    // Everything but the bend, which moves at drag rate: only the bell's draw phase reads that.
    val controls = remember(state) {
        derivedStateOf(structuralEqualityPolicy()) { state.value.copy(bend = 0f) }
    }
    val actions = feature.actions
    val navInset = LocalPanelTopInset.current
    // The VM's scope outlives the panel, so leaving composition is what lets go of a held note.
    DisposableEffect(actions) {
        onDispose { actions.releaseAll() }
    }
    // Keeps the bell sounding through a paused vibe while the tab is shown.
    DisposableEffect(feature) {
        feature.armLive()
        onDispose { feature.disarmLive() }
    }
    var expanded by rememberSaveable { mutableStateOf(true) }
    // 1 is expanded, 0 collapsed. Read in layout and draw only, so the slide recomposes nothing.
    val open = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(COLLAPSE_MILLIS, easing = FastOutSlowInEasing),
        label = "cowbell-columns",
    )
    val leftShown by remember { derivedStateOf { open.value > 0f } }

    // Composed in reading order (pill, controls, knobs, selector, bell, fader), so Tab and focus search follow it.
    Box(modifier.testTag(CowbellDjPanelTag)) {
        CollapsePill(
            expanded = expanded,
            open = { open.value },
            onToggle = { expanded = !expanded },
            // Lined up with the chips' left edge, over the controls column's top padding.
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = PANEL_SIDE_PADDING, top = navInset + CollapsePillTop)
                .zIndex(1f),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = PANEL_SIDE_PADDING, end = PANEL_SIDE_PADDING, top = navInset, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP, Alignment.CenterHorizontally),
        ) {
            if (leftShown) {
                ChipsBesideKnobs(Modifier.fillMaxHeight().slideAway { open.value }) {
                    CowbellControlColumn(state = controls.value, actions = actions)
                    DjKnobs(controls, actions)
                }
            }
            DjBell(
                state, controls, actions, outLevel, { open.value },
                modifier = Modifier.weight(1f, fill = false).capWhenCollapsed { open.value }.fillMaxHeight(),
            )
            // The track flexes with a short slot up to its cap. Without the arrows the widget is exactly this tall.
            BoxWithConstraints(Modifier.fillMaxHeight().padding(top = DjSelectorBand, end = FADER_END_INSET), contentAlignment = Alignment.Center) {
                BellBendFader(
                    state = state,
                    actions = actions,
                    modifier = Modifier.testTag(CowbellDjFaderTag),
                    trackHeight = maxHeight.coerceIn(FADER_MIN_TRACK, DjFaderMaxTrack).value.toInt(),
                    thumbWidth = FADER_THUMB_WIDTH,
                    showArrows = false,
                )
            }
        }
    }
}

/**
 * The chips and the knobs side by side, the knobs centred. The chips run from TONE's top to the
 * bottom of the last knob, which also keeps Mode from taking TONE's D-pad Down. In a short slot
 * they start under the collapse pill instead, and never close up past [ChipsMinHeight].
 */
@Composable
private fun ChipsBesideKnobs(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { (chips, knobs), constraints ->
        val height = constraints.maxHeight
        val knobStack = knobs.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity))
        val knobTop = (height - knobStack.height) / 2
        val bottom = knobTop + knobStack.height
        val span = max(bottom - max(knobTop, ControlsTopClearance.roundToPx()), ChipsMinHeight.roundToPx())
        val chipWidth = DjControlsWidth.roundToPx()
        val chipColumn = chips.measure(Constraints.fixed(chipWidth, span))
        val gap = COLUMN_GAP.roundToPx()
        layout(chipWidth + gap + knobStack.width, height) {
            chipColumn.placeRelative(0, min(bottom, height) - span)
            knobStack.placeRelative(chipWidth + gap, knobTop)
        }
    }
}

/**
 * Reports [open] of the content's width and slides it left under a clip, fading as it goes.
 * The content keeps its own width throughout, so nothing inside reflows.
 */
private fun Modifier.slideAway(open: () -> Float): Modifier =
    graphicsLayer {
        alpha = open()
        clip = true
    }.layout { measurable, constraints ->
        val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val w = (p.width * open()).roundToInt()
        layout(w, p.height) { p.placeRelative(w - p.width, 0) }
    }

/**
 * Lets the bell take its full share while expanded and caps it at [COLLAPSED_BELL_ASPECT] of its
 * height collapsed. The cap loosens as [open] grows, so the bell eases between the two.
 */
private fun Modifier.capWhenCollapsed(open: () -> Float): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val p = measurable.measure(constraints)
        return@layout layout(p.width, p.height) { p.placeRelative(0, 0) }
    }
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.maxWidth
    val cap = height * COLLAPSED_BELL_ASPECT / max(1f - open(), 1e-3f)
    val w = min(constraints.maxWidth.toFloat(), cap).roundToInt()
    val p = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(w, p.height) { p.placeRelative(0, 0) }
}

/**
 * A chip-wide pill with a chevron: it points left to tuck the controls away and right to bring them
 * back. It narrows with [open], read in layout, so the slide recomposes nothing.
 */
@Composable
private fun CollapsePill(
    expanded: Boolean,
    open: () -> Float,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .testTag(CowbellDjCollapseTag)
                .height(CollapsePillHeight)
                .layout { measurable, constraints ->
                    val narrow = CollapsePillCollapsedWidth.toPx()
                    val width = (narrow + (DjControlsWidth.toPx() - narrow) * open()).roundToInt()
                    val p = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(p.width, p.height) { p.placeRelative(0, 0) }
                }
                .clip(shape)
                .background(CowbellPanelColor.copy(alpha = 0.15f))
                .border(1.dp, CowbellPanelColor.copy(alpha = 0.45f), shape)
                .clickable(onClickLabel = if (expanded) "Hide controls" else "Show controls", onClick = onToggle)
                .semantics { contentDescription = if (expanded) "Hide controls" else "Show controls" }
                .drawBehind {
                    // Turns a half circle with the slide, so it points the way the next tap goes.
                    rotate(180f * (1f - open())) {
                        val w = 3.5.dp.toPx()
                        val h = 6.dp.toPx()
                        val c = center
                        val path = Path().apply {
                            moveTo(c.x + w, c.y - h)
                            lineTo(c.x - w, c.y)
                            lineTo(c.x + w, c.y + h)
                        }
                        drawPath(path, CowbellPanelColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                },
        )
    }
}

/** TONE, RING and INTENSITY, small and close, centred in their column. */
@Composable
private fun DjKnobs(
    controls: State<CowbellUiState>,
    actions: CowbellPanelActions,
    modifier: Modifier = Modifier,
) {
    val s = controls.value
    // Smaller than the Orpheus knob's text, so three stack in a 205dp slot at font scale 1.3.
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp)
    val valueStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, lineHeight = 12.sp)
    Column(
        modifier = modifier.testTag(CowbellDjKnobsTag),
        verticalArrangement = Arrangement.spacedBy(KNOB_GAP, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CowbellKnob(
            s.tone, actions.onTone, "TONE", CowbellSymbol.TONE,
            modifier = Modifier.testTag(CowbellDjToneTag), size = DjKnobSize,
            labelStyle = labelStyle, valueStyle = valueStyle,
        )
        CowbellKnob(
            s.ring, actions.onRing, "RING", CowbellSymbol.RING,
            modifier = Modifier.testTag(CowbellDjRingTag), size = DjKnobSize,
            labelStyle = labelStyle, valueStyle = valueStyle,
        )
        CowbellKnob(
            s.intensity, actions.onIntensity, "INTENSITY", CowbellSymbol.INTENSITY,
            modifier = Modifier.testTag(CowbellDjIntensityTag), size = DjKnobSize,
            labelStyle = labelStyle, valueStyle = valueStyle,
        )
    }
}

/**
 * The body selector centred over the bell, which takes the rest of the column. In Shake the bell
 * fits a box one Fill chip shorter top and bottom, and the Fill dropdown sits just under it.
 */
@Composable
private fun DjBell(
    state: State<CowbellUiState>,
    controls: State<CowbellUiState>,
    actions: CowbellPanelActions,
    outLevel: StateFlow<Float>,
    open: () -> Float,
    modifier: Modifier = Modifier,
) {
    val mode = controls.value.mode
    val latestOpen = rememberUpdatedState(open)
    val fit = remember { { djBellFit(latestOpen.value()) } }
    val bellBounds = remember { mutableStateOf(Rect.Zero) }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CowbellBodySelector(
            body = controls.value.body,
            onBody = actions.onBody,
            modifier = Modifier.padding(top = CollapsePillTop),
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag(CowbellDjBellTag)) {
            CowbellBell3D(
                mode = mode,
                actions = actions,
                bend = { state.value.bend },
                fill = controls.value.fill,
                outLevel = outLevel,
                morphStyle = BellMorphStyle.SHARDS,
                motionOn = controls.value.motion,
                modifier = Modifier.fillMaxSize(),
                fit = fit,
                onRestingBounds = { bellBounds.value = it },
            )
            if (mode == CowbellMode.SHAKE) {
                // The wrapper takes the bell's box, so the learn frame spans the chip only.
                Box(Modifier.underBell { bellBounds.value }) {
                    FillDropdown(
                        fill = controls.value.fill,
                        onFill = actions.onFill,
                        modifier = Modifier.width(DjControlsWidth),
                    )
                }
            }
        }
    }
}

/**
 * The bell's fit at [open] (1 expanded). Shake's bell leaves a Fill chip's height (and the gap)
 * clear above and below, so Fill fits under it.
 */
private fun djBellFit(open: Float): BellFit = BellFit(
    fraction = BELL_FIT_COLLAPSED + (BELL_FIT - BELL_FIT_COLLAPSED) * open,
    shakeInsetY = ControlChipHeight + FILL_GAP,
)

/** Centres the content under the drawn [bell], [FILL_GAP] below it, kept inside the box. */
private fun Modifier.underBell(bell: () -> Rect): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    layout(w, h) {
        val b = bell()
        val x = if (b.isEmpty) (w - p.width) / 2f else b.center.x - p.width / 2f
        val y = if (b.isEmpty) (h - p.height).toFloat() else b.bottom + FILL_GAP.toPx()
        p.placeRelative(
            x.roundToInt().coerceIn(0, max(0, w - p.width)),
            y.roundToInt().coerceIn(0, max(0, h - p.height)),
        )
    }
}

@Preview(widthDp = 412, heightDp = 290)
@Preview(widthDp = 412, heightDp = 290, name = "140%", fontScale = 1.4f)
@Composable
fun CowbellDjPanelPreview() {
    OrpheusTheme {
        CowbellDjPanel(feature = CowbellViewModel.previewFeature(CowbellUiState(grid = true)))
    }
}

@Preview(widthDp = 412, heightDp = 205, name = "Duo closed")
@Preview(widthDp = 412, heightDp = 205, name = "Duo closed, 140%", fontScale = 1.4f)
@Composable
private fun CowbellDjPanelDuoPreview() {
    OrpheusTheme {
        CowbellDjPanel(feature = CowbellViewModel.previewFeature())
    }
}

@Preview(widthDp = 412, heightDp = 290, name = "Shake, More 150")
@Composable
private fun CowbellDjPanelShakeMorePreview() {
    OrpheusTheme {
        CowbellDjPanel(
            feature = CowbellViewModel.previewFeature(
                CowbellUiState(mode = CowbellMode.SHAKE, level = 1f, more = 0.5f),
            ),
        )
    }
}
