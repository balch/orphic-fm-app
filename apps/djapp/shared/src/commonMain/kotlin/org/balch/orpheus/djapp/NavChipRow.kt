package org.balch.orpheus.djapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntRect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.balch.orpheus.ui.theme.OrpheusColors

/** How long a chip's single tap waits for a second tap before it counts as a swap. */
internal const val ChipDoubleTapWindowMs = 300L

/** The test tag on each chip in the row. */
internal const val NavChipTag = "navChip"

private val ChipGap = 8.dp
private val ChipRowGap = 8.dp
private val ChipFill = Color(0xFF10141C)
private val ChipShape = RoundedCornerShape(8.dp)

/** The row a long press opened: the pressed [slot] first, then the [chips] that are off the bar. */
internal class NavChipRowState(
    val slot: DjRoute,
    val chips: List<DjRoute>,
    val anchor: IntRect,
    val slotBounds: IntRect,
)

/**
 * What the bar's and the rail's tabs share while a gesture runs: where each slot sits, the chip row
 * when one is open, and the tab being dragged. Reads and writes of [slots] happen on the main thread.
 */
@Stable
internal class NavGestureHost {
    var actions: NavActions? = null
    var offBar: List<DjRoute> = emptyList()
    var vertical: Boolean = false
    var anchor: LayoutCoordinates? = null
    var haptics: HapticFeedback? = null
    val slots = HashMap<NavEntry, LayoutCoordinates>()

    var chipRow by mutableStateOf<NavChipRowState?>(null)
        private set

    /** The tab under the finger, which only changes when a drag starts or ends. */
    var lifted by mutableStateOf<NavEntry?>(null)
        private set

    /** How far the finger has moved since the drag began; read only when the lifted tab draws. */
    var dragOffset by mutableStateOf(Offset.Zero)
        private set

    var scope: CoroutineScope? = null

    /** The slot a drop would trade with right now. */
    private var dropTarget: NavEntry? = null

    /** Each tab's slide toward the lifted tab's place, previewing the trade; read only in draw. */
    private val previews = mutableStateMapOf<NavEntry, Animatable<Offset, AnimationVector2D>>()

    fun previewOffset(entry: NavEntry): Offset = previews[entry]?.value ?: Offset.Zero

    /** Opens the row for [slot]; a bar with every panel on it has no chips to offer. */
    fun openChips(slot: DjRoute) {
        val anchor = anchor?.takeIf { it.isAttached } ?: return
        val tab = slots[NavEntry.Panel(slot)]?.takeIf { it.isAttached } ?: return
        if (offBar.isEmpty()) return
        chipRow = NavChipRowState(
            slot = slot,
            chips = offBar,
            anchor = anchor.boundsInWindow().roundToIntRect(),
            slotBounds = tab.boundsInWindow().roundToIntRect(),
        )
    }

    fun closeChips() {
        chipRow = null
    }

    fun swapChip(row: NavChipRowState, chip: DjRoute) {
        closeChips()
        actions?.onChipSwap(row.slot, chip)
    }

    fun openChip(chip: DjRoute) {
        closeChips()
        actions?.onOpenOffBar(chip)
    }

    /** Raises [entry] under the finger once its long press lands, before any drag. */
    fun lift(entry: NavEntry) {
        lifted = entry
        dragOffset = Offset.Zero
    }

    /** Moves the lifted tab by [offset]; the tab under [rootPosition] slides over to show the trade. */
    fun dragTo(offset: Offset, rootPosition: Offset) {
        dragOffset = offset
        val source = lifted ?: return
        val target = targetAt(source, rootPosition)
        if (target == dropTarget) return
        dropTarget = target
        // A tick as it lands over a slot, so the finger feels where a drop would go.
        if (target != null) haptics?.performHapticFeedback(HapticFeedbackType.SegmentTick)
        previews.forEach { (entry, slide) -> if (entry != target) scope?.launch { slide.animateTo(Offset.Zero) } }
        val home = slots[source]?.takeIf { it.isAttached } ?: return
        val over = target?.let { slots[it] }?.takeIf { it.isAttached } ?: return
        val slide = previews.getOrPut(target) { Animatable(Offset.Zero, Offset.VectorConverter) }
        scope?.launch { slide.animateTo(home.boundsInRoot().center - over.boundsInRoot().center) }
    }

    /** Ends the drag in the frame the new order lands, so no tab slides on from its preview. */
    fun endDrag() {
        lifted = null
        dragOffset = Offset.Zero
        dropTarget = null
        previews.clear()
    }

    /** Trades [from] with the slot under [rootPosition]; the dome and everywhere else cancel. */
    fun drop(from: DjRoute, rootPosition: Offset) {
        val source = NavEntry.Panel(from)
        val target = targetAt(source, rootPosition) ?: return
        val actions = actions ?: return
        haptics?.performHapticFeedback(HapticFeedbackType.Confirm)
        actions.onExchange(source, target)
    }

    /** Hit-tests the slots where they are laid out, not where a preview draws them. */
    private fun targetAt(source: NavEntry, rootPosition: Offset): NavEntry? =
        slots.entries.firstOrNull { (entry, coordinates) ->
            entry != source && coordinates.isAttached && coordinates.boundsInRoot().contains(rootPosition)
        }?.key
}

/** A host that follows [actions], [offBar] and the rail or bar axis; null [actions] means no gestures. */
@Composable
internal fun rememberNavGestureHost(actions: NavActions?, offBar: List<DjRoute>, vertical: Boolean): NavGestureHost? {
    if (actions == null) return null
    val host = remember { NavGestureHost() }
    host.actions = actions
    host.haptics = LocalHapticFeedback.current
    host.scope = rememberCoroutineScope()
    host.offBar = offBar
    host.vertical = vertical
    return host
}

/** Records where the bar or rail sits, so the chip row can float against it. */
internal fun Modifier.navAnchor(host: NavGestureHost?): Modifier =
    if (host == null) this else onGloballyPositioned { host.anchor = it }

/** Makes the open slot a drop target for a dragged tab. */
internal fun Modifier.navOpenSlot(host: NavGestureHost?): Modifier =
    if (host == null) this else onGloballyPositioned { host.slots[NavEntry.Open] = it }

/**
 * A tab's gestures: a long press buzzes, lifts the tab and opens the chip row while the finger is
 * still down; moving off the tab then drags it instead. A mouse secondary click opens the row. A
 * plain tap passes through to the tab's own click.
 */
internal fun Modifier.navTabGestures(host: NavGestureHost?, route: DjRoute): Modifier {
    if (host == null) return this
    val entry = NavEntry.Panel(route)
    val lifted = host.lifted == entry
    return this
        .onGloballyPositioned { host.slots[entry] = it }
        .pointerInput(host, route) { detectNavTabGestures(host, route) }
        .zIndex(if (lifted) 1f else 0f)
        .graphicsLayer {
            if (lifted) {
                val offset = host.dragOffset
                translationX = offset.x
                translationY = offset.y
                scaleX = LiftedScale
                scaleY = LiftedScale
                alpha = LiftedAlpha
            } else {
                val offset = host.previewOffset(entry)
                translationX = offset.x
                translationY = offset.y
            }
        }
}

private const val LiftedScale = 1.1f
private const val LiftedAlpha = 0.9f

private suspend fun PointerInputScope.detectNavTabGestures(host: NavGestureHost, route: DjRoute) {
    awaitEachGesture {
        // The initial pass sees the press before the tab's own click, and the consumed lift cancels that click.
        val down = awaitAnyDown()
        val tab = host.slots[NavEntry.Panel(route)]
        fun toRoot(local: Offset): Offset = tab?.takeIf { it.isAttached }?.localToRoot(local) ?: local

        if (down.type == PointerType.Mouse && currentEvent.buttons.isSecondaryPressed) {
            down.consume()
            consumeUntilLift(down)
            host.openChips(route)
            return@awaitEachGesture
        }
        // Released or moved off before the timeout: an ordinary tap or scroll, left alone.
        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForReleaseOrSlop(down)
        } == null
        if (!held) return@awaitEachGesture

        // Answer the press at the timeout, not on lift, so the user knows it landed.
        host.haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
        host.lift(NavEntry.Panel(route))
        host.openChips(route)
        try {
            var dragging = false
            val start = toRoot(down.position)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                event.changes.forEach { it.consume() }
                val position = toRoot(change.position)
                // Only leaving the tab drags: a held finger drifts past touch slop as it lifts.
                if (!dragging && !change.position.isOver(size, viewConfiguration.touchSlop)) {
                    dragging = true
                    host.closeChips()
                }
                if (dragging) host.dragTo(position - start, position)
                if (!change.pressed) {
                    if (dragging) host.drop(route, position)
                    break
                }
            }
        } finally {
            host.endDrag()
        }
    }
}

/** True while this local position is on a [size] box grown by [margin] on every side. */
private fun Offset.isOver(size: IntSize, margin: Float): Boolean =
    x >= -margin && y >= -margin && x <= size.width + margin && y <= size.height + margin

/** The first press of any button; awaitFirstDown never returned for a mouse's secondary press here. */
private suspend fun AwaitPointerEventScope.awaitAnyDown(): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.let { return it }
    }
}

/** Returns once [down]'s pointer lifts, is consumed, or moves past touch slop. */
private suspend fun AwaitPointerEventScope.waitForReleaseOrSlop(down: PointerInputChange) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return
        if (!change.pressed || change.isConsumed) return
        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return
    }
}

/** Swallows the rest of [down]'s press so a secondary click never reaches the tab's tap. */
private suspend fun AwaitPointerEventScope.consumeUntilLift(down: PointerInputChange) {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: return
        event.changes.forEach { it.consume() }
        if (!change.pressed) return
    }
}

/** Places the row above the bar centred on the pressed tab, or beside the rail on its open side. */
internal class ChipRowPositioner(
    private val row: NavChipRowState,
    private val vertical: Boolean,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val window = windowSize.toIntRect()
        return if (vertical) {
            val y = (row.slotBounds.center.y - popupContentSize.height / 2)
                .coerceIn(window.top, (window.bottom - popupContentSize.height).coerceAtLeast(window.top))
            IntOffset(row.anchor.right + gapPx, y)
        } else {
            val x = (row.slotBounds.center.x - popupContentSize.width / 2)
                .coerceIn(window.left, (window.right - popupContentSize.width).coerceAtLeast(window.left))
            IntOffset(x, (row.anchor.top - popupContentSize.height - gapPx).coerceAtLeast(window.top))
        }
    }
}

/** The chip row, floating in a popup while [NavGestureHost.chipRow] is set. No scrim: a tap outside closes it. */
@Composable
internal fun NavChipPopup(host: NavGestureHost?) {
    val row = host?.chipRow ?: return
    val density = LocalDensity.current
    val gapPx = with(density) { ChipRowGap.roundToPx() }
    val positioner = remember(row, host.vertical, gapPx) { ChipRowPositioner(row, host.vertical, gapPx) }
    Popup(
        popupPositionProvider = positioner,
        onDismissRequest = host::closeChips,
        properties = PopupProperties(focusable = true),
    ) {
        // The bar's glass frames the row; each chip sits on a fill of its own, so no glass is behind text.
        // As wide as its chips, scrolling only when they outgrow the bar.
        val width = if (host.vertical) Modifier else Modifier.widthIn(max = with(density) { row.anchor.width.toDp() })
        Box(Modifier.then(width).tvBarGlass(true).padding(ChipGap)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(ChipGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavChip(row.slot, current = true, onClick = host::closeChips)
                row.chips.forEach { chip ->
                    DoubleTapChip(
                        route = chip,
                        onTap = { host.swapChip(row, chip) },
                        onDoubleTap = { host.openChip(chip) },
                    )
                }
            }
        }
    }
}

/** A chip that waits [ChipDoubleTapWindowMs] for a second tap before its single tap fires. */
@Composable
private fun DoubleTapChip(route: DjRoute, onTap: () -> Unit, onDoubleTap: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Job?>(null) }
    NavChip(route, current = false) {
        val waiting = pending
        if (waiting != null) {
            waiting.cancel()
            pending = null
            onDoubleTap()
        } else {
            pending = scope.launch {
                delay(ChipDoubleTapWindowMs)
                pending = null
                onTap()
            }
        }
    }
}

@Composable
private fun NavChip(route: DjRoute, current: Boolean, onClick: () -> Unit) {
    val tint = if (current) OrpheusColors.neonCyan else Color.White.copy(alpha = 0.85f)
    Row(
        modifier = Modifier
            .testTag(NavChipTag)
            .semantics { selected = current }
            .background(if (current) NavIndicatorColor.compositeOver(ChipFill) else ChipFill, ChipShape)
            .then(if (current) Modifier.border(BorderStroke(1.dp, OrpheusColors.neonCyan), ChipShape) else Modifier)
            .clickable(role = Role.Tab, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(route.icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(route.label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
    }
}
