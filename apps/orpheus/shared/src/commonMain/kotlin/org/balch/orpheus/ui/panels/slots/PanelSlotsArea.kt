package org.balch.orpheus.ui.panels.slots

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.ui.panels.HeaderPanelActions
import org.balch.orpheus.ui.panels.HeaderPanelUiState
import org.balch.orpheus.ui.panels.LocalCompactMode
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.lighten
import kotlin.math.max

/** Asks the slot showing [id] to flash its border; a new [seq] flashes it again. */
@Immutable
data class PanelPulse(val id: PanelId, val seq: Int)

private val SlotHeaderHeight = 32.dp
private val SlotInset = 2.dp
private val SlotShape = RoundedCornerShape(8.dp)
private const val PulseMillis = 300
private val HeaderTouchTarget = 40.dp
private val HeaderVisualSize = 22.dp
private const val EmptySlotHint = "Pick a panel from the rail or recents"

/**
 * Lays out the open panels in width-driven slots, each scaled to fit, with free units drawn as
 * placeholders. Nothing renders until the first measure reports the area's size.
 */
@Composable
fun PanelSlotsArea(
    panels: Map<PanelId, FeaturePanel>,
    entries: Map<PanelId, PanelNavEntry>,
    state: HeaderPanelUiState,
    actions: HeaderPanelActions,
    pulse: PanelPulse?,
    onDialogActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var areaPx by remember { mutableStateOf(IntSize.Zero) }
    val currentActions by rememberUpdatedState(actions)

    Box(modifier.fillMaxSize().onSizeChanged { areaPx = it }) {
        if (areaPx == IntSize.Zero) return@Box

        val density = LocalDensity.current
        val areaWidth = with(density) { areaPx.width.toDp() }
        val areaHeight = with(density) { areaPx.height.toDp() }
        val capacity = slotCapacity(areaWidth)
        LaunchedEffect(capacity) { currentActions.setCapacity(capacity) }

        val unitWidth = areaWidth / capacity
        // The reducer may still hold a wider capacity for a frame; never squeeze a slot.
        var used = 0
        val shown = state.slots.open.filter { open ->
            val fits = open.id in panels && used + open.units <= capacity
            if (fits) used += open.units
            fits
        }

        Row(Modifier.fillMaxSize()) {
            shown.forEach { open ->
                key(open.id) {
                    PanelSlot(
                        panel = panels.getValue(open.id),
                        entry = entries[open.id],
                        open = open,
                        slots = state.slots,
                        actions = actions,
                        pulse = pulse,
                        width = unitWidth * open.units,
                        height = areaHeight,
                        onDialogActiveChange = onDialogActiveChange,
                    )
                }
            }
            repeat(capacity - used) {
                EmptySlot(Modifier.width(unitWidth).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun PanelSlot(
    panel: FeaturePanel,
    entry: PanelNavEntry?,
    open: OpenPanel,
    slots: PanelSlots,
    actions: HeaderPanelActions,
    pulse: PanelPulse?,
    width: Dp,
    height: Dp,
    onDialogActiveChange: (Boolean) -> Unit,
) {
    val id = open.id
    val currentActions by rememberUpdatedState(actions)
    val glow = remember { Animatable(0f) }
    val myPulse = pulse?.takeIf { it.id == id }
    LaunchedEffect(myPulse) {
        // A cancelled flash relaunches here with a new key, so the glow never sticks.
        if (myPulse == null) {
            glow.snapTo(0f)
        } else {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(PulseMillis))
        }
    }

    val restBorder = panel.color.copy(alpha = 0.25f)
    val litBorder = panel.color.lighten(0.35f)

    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .padding(SlotInset)
            .pointerInput(id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) currentActions.touch(id)
                    }
                }
            }
            .drawWithContent {
                drawContent()
                val g = glow.value
                val stroke = (1.dp + 1.dp * g).toPx()
                drawRoundRect(
                    color = lerp(restBorder, litBorder, g),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(stroke),
                )
            },
    ) {
        SlotHeaderBar(
            label = entry?.shortLabel ?: panel.label,
            color = panel.color,
        )

        val base = LocalDensity.current
        val bodyHeight = height - SlotHeaderHeight - SlotInset * 2
        val scale = slotScale(width, bodyHeight, open.units)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(SlotHeaderHeight))
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density * scale, base.fontScale),
                    LocalCompactMode provides true,
                ) {
                    FitOrScroll {
                        panel.Content(
                            modifier = Modifier.fillMaxSize(),
                            isExpanded = true,
                            onExpandedChange = { if (!it) currentActions.close(id) },
                            onDialogActiveChange = onDialogActiveChange,
                        )
                    }
                }
            }
        }
        // Drawn over the body: the 40dp targets overhang it by 8dp, its top padding and title edge.
        SlotHeaderButtons(
            color = panel.color,
            isPinned = id in slots.pinned,
            canPin = slots.canPin(id) || id in slots.pinned,
            onTogglePin = { currentActions.togglePin(id) },
            onClose = { currentActions.close(id) },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun SlotHeaderBar(label: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SlotHeaderHeight)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .padding(start = 10.dp, end = HeaderTouchTarget * 2),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun SlotHeaderButtons(
    color: Color,
    isPinned: Boolean,
    canPin: Boolean,
    onTogglePin: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier) {
        HeaderButton(
            icon = Icons.Filled.PushPin,
            description = if (isPinned) "Unpin panel" else "Pin panel",
            enabled = canPin,
            tint = when {
                isPinned -> color
                canPin -> OrpheusColors.brightSilver.copy(alpha = 0.5f)
                else -> OrpheusColors.brightSilver.copy(alpha = 0.15f)
            },
            highlight = if (isPinned) color.copy(alpha = 0.22f) else null,
            onClick = onTogglePin,
        )
        HeaderButton(
            icon = Icons.Filled.Close,
            description = "Close panel",
            enabled = true,
            tint = OrpheusColors.brightSilver.copy(alpha = 0.7f),
            highlight = null,
            onClick = onClose,
        )
    }
}

/** A 40dp touch target whose small visual sits centred in the slot header's band. */
@Composable
private fun HeaderButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    tint: Color,
    highlight: Color?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(HeaderTouchTarget)
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick)
            .padding(top = (SlotHeaderHeight - HeaderVisualSize) / 2),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .size(HeaderVisualSize)
                .clip(CircleShape)
                .then(if (highlight != null) Modifier.background(highlight) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun EmptySlot(modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(SlotInset)
            .border(1.dp, OrpheusColors.outlineVariantDark, SlotShape)
            .background(OrpheusColors.panelSurfaceDark.copy(alpha = 0.35f), SlotShape)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = EmptySlotHint,
            color = OrpheusColors.slateGrey,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/** Per slot: set once a body's intrinsics throw (subcompose content), so the probe is not retried. */
private class FitProbe(var intrinsicsUnsupported: Boolean = false)

/**
 * A body whose intrinsic height fits gets exactly the viewport's height. A taller one gets up to its
 * intrinsic height and scrolls. Bodies without intrinsics always fit.
 */
@Composable
private fun FitOrScroll(content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    val probe = remember { FitProbe() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                if (scroll.maxValue > 0) {
                    val total = size.height + scroll.maxValue
                    val thumb = size.height * size.height / total
                    val top = (size.height - thumb) * scroll.value / scroll.maxValue
                    val w = 3.dp.toPx()
                    drawRoundRect(
                        color = OrpheusColors.brightSilver.copy(alpha = 0.35f),
                        topLeft = Offset(size.width - w - 1.dp.toPx(), top),
                        size = Size(w, thumb),
                        cornerRadius = CornerRadius(w / 2),
                    )
                }
            }
            .verticalScroll(scroll)
            .layout { measurable, constraints ->
                // verticalScroll lifts only the max, so the min is still the viewport height.
                val width = constraints.maxWidth
                val viewport = constraints.minHeight
                val natural = if (probe.intrinsicsUnsupported) {
                    0
                } else {
                    try {
                        measurable.minIntrinsicHeight(width)
                    } catch (_: IllegalStateException) {
                        probe.intrinsicsUnsupported = true
                        0
                    }
                }
                // Bounded even when overflowing, so a body with its own vertical scroller cannot crash.
                val childConstraints = if (natural > viewport) {
                    Constraints(
                        minWidth = width,
                        maxWidth = width,
                        minHeight = viewport,
                        maxHeight = max(natural, viewport),
                    )
                } else {
                    Constraints.fixed(width, viewport)
                }
                val placeable = measurable.measure(childConstraints)
                layout(width, max(viewport, placeable.height)) { placeable.place(0, 0) }
            },
    ) {
        content()
    }
}
