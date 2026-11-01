package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.raisedAccentSurface
import org.balch.orpheus.ui.theme.OrpheusColors

private val TvChipShape = RoundedCornerShape(14.dp)
private val TvChipFill = Color(0xFF10141C)
private val TvChipIconSize = 36.dp
private val TvChipMinHeight = 72.dp

/** The room between the chip row and the bar's top edge. */
internal val TvChipRowGap = 12.dp

/** Places the content right above its parent's top edge and takes no height of its own. */
internal fun Modifier.floatAboveParent(gap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val gapPx = gap.roundToPx()
    layout(placeable.width, 0) { placeable.place(0, -placeable.height - gapPx) }
}

/** The row a held OK opened above [slot]'s tab: [chips] are the panels off the bar. */
internal class TvChipRow(val slot: DjRoute, val chips: List<DjRoute>)

/**
 * What the remote is doing to the bar's order: the open chip row, the tab being slid, and the
 * focus requesters that return focus to a tab when either ends. Written from key handlers and
 * composition on the main thread.
 */
@Stable
internal class TvNavCustomize(private val actionsNow: () -> NavActions) {
    private val actions: NavActions get() = actionsNow()

    var chipRow by mutableStateOf<TvChipRow?>(null)
        private set

    /** The tab left and right slide along the bar. */
    var moving by mutableStateOf<DjRoute?>(null)
        private set

    /** A tab to hand focus back to once the row or move mode has ended. */
    var restoreFocus by mutableStateOf<DjRoute?>(null)
        private set

    /** Set when a hold opened the row; the OK still held down must not act on a chip. */
    private var holdingOk = false
    private val heldArrows = mutableSetOf<Key>()
    private val itemFocus = HashMap<DjRoute, FocusRequester>()

    fun focusFor(route: DjRoute): FocusRequester = itemFocus.getOrPut(route) { FocusRequester() }

    fun focusRestored() {
        restoreFocus?.let { runCatching { focusFor(it).requestFocus() } }
        restoreFocus = null
    }

    fun openChips(slot: DjRoute, offBar: List<DjRoute>) {
        holdingOk = true
        chipRow = TvChipRow(slot, offBar)
    }

    /** Closes the row and hands focus to [focusRoute]'s tab. */
    fun closeChips(focusRoute: DjRoute) {
        holdingOk = false
        chipRow = null
        restoreFocus = focusRoute
    }

    fun swap(row: TvChipRow, chip: DjRoute) {
        closeChips(chip)
        actions.onChipSwap(row.slot, chip)
    }

    fun openOffBar(row: TvChipRow, chip: DjRoute) {
        closeChips(row.slot)
        actions.onOpenOffBar(chip)
    }

    fun startMove(row: TvChipRow) {
        closeChips(row.slot)
        moving = row.slot
    }

    fun endMove() {
        moving = null
        heldArrows.clear()
    }

    /** The release of the OK that opened the row, and its auto-repeats, belong to nobody. */
    private fun swallowHeldOk(event: KeyEvent): Boolean {
        if (!holdingOk || !event.isOk()) return false
        if (event.type == KeyEventType.KeyUp) holdingOk = false
        return true
    }

    /** A tab's keys while the row is closed: the held-OK timer's release, and move mode's arrows. */
    fun tabKey(event: KeyEvent, route: DjRoute, nav: NavBar, hold: HoldTimer): Boolean {
        if (swallowHeldOk(event)) return true
        if (moving == route) return moveKey(event, route, nav)
        if (!event.isOk()) return false
        when (event.type) {
            KeyEventType.KeyDown -> hold.start { openChips(route, nav.offBar) }
            KeyEventType.KeyUp -> hold.cancel()
        }
        // A short press falls through to the tab's own click.
        return false
    }

    private fun moveKey(event: KeyEvent, route: DjRoute, nav: NavBar): Boolean {
        val up = event.type == KeyEventType.KeyUp
        val key = event.key
        when {
            key == Key.DirectionLeft || key == Key.DirectionRight -> {
                // AWT carries no repeat flag: one slide per press, whatever the auto-repeat.
                if (up) heldArrows -= key
                else if (heldArrows.add(key)) slide(route, nav, if (key == Key.DirectionLeft) -1 else 1)
            }
            event.isOk() || event.isBack() -> if (up) endMove()
            else -> return false
        }
        return true
    }

    /** Exchanges [route] with its neighbour on the bar, the dome skipped. */
    private fun slide(route: DjRoute, nav: NavBar, step: Int) {
        val entries = nav.left + nav.right
        val self = NavEntry.Panel(route)
        val neighbour = entries.getOrNull(entries.indexOf(self) + step)?.takeIf { it != self } ?: return
        actions.onExchange(self, neighbour)
    }

    /** The row's keys: focus stays on its chips, and Back closes it. */
    fun chipRowKey(event: KeyEvent, row: TvChipRow, index: Int, chips: List<FocusRequester>): Boolean {
        if (swallowHeldOk(event)) return true
        val down = event.type == KeyEventType.KeyDown
        when {
            event.isBack() -> if (!down) closeChips(row.slot)
            event.key == Key.DirectionLeft -> if (down) chips[(index - 1).coerceAtLeast(0)].requestFocus()
            event.key == Key.DirectionRight -> if (down) chips[(index + 1).coerceAtMost(chips.lastIndex)].requestFocus()
            event.key == Key.DirectionUp || event.key == Key.DirectionDown -> Unit
            else -> return false
        }
        return true
    }
}

/** A key held down toward the long-press timeout, cancelled by its release. */
internal class HoldTimer(private val scope: CoroutineScope, private val timeoutMs: () -> Long) {
    private var job: Job? = null

    fun start(onHold: () -> Unit) {
        // Auto-repeated key downs arrive while the first one is still counting.
        if (job != null) return
        job = scope.launch {
            delay(timeoutMs())
            job = null
            onHold()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

private fun KeyEvent.isOk() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter || key == Key.Spacebar

private fun KeyEvent.isBack() = key == Key.Back || key == Key.Escape

/** The customizing state for the bar, or null (no gestures) when [actions] is. */
@Composable
internal fun rememberTvNavCustomize(actions: NavActions?): TvNavCustomize? {
    if (actions == null) return null
    // Read through, so a new actions value (its callers rebuild it) keeps an open row open.
    val current by rememberUpdatedState(actions)
    return remember { TvNavCustomize { current } }
}

/**
 * Hold OK on a tab for the chip row; in move mode the arrows slide it. A short press is left to the
 * tab's click. Losing focus ends the hold and the move.
 */
@Composable
internal fun Modifier.tvNavTabKeys(customize: TvNavCustomize?, route: DjRoute, nav: NavBar): Modifier {
    if (customize == null) return this
    val scope = rememberCoroutineScope()
    val viewConfiguration by rememberUpdatedState(LocalViewConfiguration.current)
    val hold = remember(scope) { HoldTimer(scope) { viewConfiguration.longPressTimeoutMillis } }
    val currentNav by rememberUpdatedState(nav)
    return this
        .focusRequester(customize.focusFor(route))
        .onPreviewKeyEvent { customize.tabKey(it, route, currentNav, hold) }
        .onFocusChanged {
            if (!it.hasFocus) {
                hold.cancel()
                if (customize.moving == route) customize.endMove()
            }
        }
}

/** Hands focus back to a tab after the chip row or move mode ends. */
@Composable
internal fun TvNavFocusRestore(customize: TvNavCustomize?, nav: NavBar) {
    val route = customize?.restoreFocus ?: return
    // After the swap's recomposition, so the tab that took the slot exists to be focused.
    LaunchedEffect(route, nav) { customize.focusRestored() }
}

/**
 * The chip row above [TvNavCustomize.chipRow]'s tab: the tab's own panel (current), the panels off
 * the bar, then Move. Focus starts on the current chip and stays in the row until it closes.
 */
@Composable
internal fun TvNavChipRow(customize: TvNavCustomize, modifier: Modifier = Modifier) {
    val row = customize.chipRow ?: return
    val requesters = remember(row) { List(row.chips.size + 2) { FocusRequester() } }
    var index by remember(row) { mutableIntStateOf(0) }
    var entered by remember(row) { mutableStateOf(false) }
    var hasFocus by remember(row) { mutableStateOf(false) }
    LaunchedEffect(row) { requesters[0].requestFocus() }
    // Focus passes through "nowhere" between two chips; only a loss that outlasts a frame closes the row.
    LaunchedEffect(row, hasFocus) {
        if (hasFocus) {
            entered = true
        } else if (entered) {
            withFrameNanos { }
            customize.closeChips(row.slot)
        }
    }
    Row(
        modifier = modifier
            .background(TvChipFill.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
            .padding(12.dp)
            .focusGroup()
            .onPreviewKeyEvent { customize.chipRowKey(it, row, index, requesters) }
            // A click elsewhere takes focus out of the row: it closes rather than stay a dead plate.
            .onFocusChanged { hasFocus = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvChip(row.slot.label, row.slot.icon, requesters[0], current = true, onFocus = { index = 0 }) {
            customize.closeChips(row.slot)
        }
        row.chips.forEachIndexed { i, chip ->
            DoublePressChip(
                chip = chip,
                focus = requesters[i + 1],
                onFocus = { index = i + 1 },
                onSwap = { customize.swap(row, chip) },
                onOpen = { customize.openOffBar(row, chip) },
            )
        }
        val last = requesters.lastIndex
        TvChip("Move", Icons.Rounded.SwapHoriz, requesters[last], current = false, onFocus = { index = last }) {
            customize.startMove(row)
        }
    }
}

/** A chip that waits [ChipDoubleTapWindowMs] for a second press before its single press swaps. */
@Composable
private fun DoublePressChip(
    chip: DjRoute,
    focus: FocusRequester,
    onFocus: () -> Unit,
    onSwap: () -> Unit,
    onOpen: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Job?>(null) }
    TvChip(chip.label, chip.icon, focus, current = false, onFocus = onFocus) {
        val waiting = pending
        if (waiting != null) {
            waiting.cancel()
            pending = null
            onOpen()
        } else {
            pending = scope.launch {
                delay(ChipDoubleTapWindowMs)
                pending = null
                onSwap()
            }
        }
    }
}

@Composable
private fun TvChip(
    label: String,
    icon: ImageVector,
    focus: FocusRequester,
    current: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val accent = if (focused) LocalLiquidEffects.current.title.titleColor else Color.Unspecified
    val tint = if (current) OrpheusColors.neonCyan else Color.White.copy(alpha = 0.9f)
    Row(
        modifier = Modifier
            .testTag(NavChipTag)
            .semantics { selected = current }
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .then(
                when {
                    focused -> Modifier.raisedAccentSurface(accent = accent, shape = TvChipShape)
                    current -> Modifier.background(TvChipFill, TvChipShape).border(2.dp, OrpheusColors.neonCyan, TvChipShape)
                    else -> Modifier.background(TvChipFill, TvChipShape)
                },
            )
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Tab, onClick = onClick)
            .defaultMinSize(minHeight = TvChipMinHeight)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(TvChipIconSize))
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelMedium,
            fontSize = 24.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
