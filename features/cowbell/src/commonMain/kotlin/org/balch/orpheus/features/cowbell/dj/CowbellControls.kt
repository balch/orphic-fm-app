package org.balch.orpheus.features.cowbell.dj

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.plugin.symbols.CowbellSymbol
import org.balch.orpheus.features.cowbell.CowbellEnginePicker
import org.balch.orpheus.features.cowbell.CowbellPanelActions
import org.balch.orpheus.features.cowbell.CowbellPanelColor
import org.balch.orpheus.features.cowbell.CowbellUiState
import org.balch.orpheus.features.cowbell.label
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.Learnable
import org.balch.orpheus.ui.widgets.engine.enginePickerTestTag

internal const val CowbellCtlModeTag = "cowbell-ctl-mode"
internal const val CowbellCtlEngineTag = "cowbell-ctl-engine"
internal const val CowbellCtlOctaveTag = "cowbell-ctl-octave"
internal const val CowbellCtlLevelTag = "cowbell-ctl-level"
internal const val CowbellCtlLockTag = "cowbell-ctl-lock"
internal const val CowbellCtlBendTag = "cowbell-ctl-bend"
internal const val CowbellCtlMotionTag = "cowbell-ctl-motion"
internal const val CowbellCtlSliderTag = "cowbell-ctl-slider"
internal const val CowbellFillDropdownTag = "cowbell-fill-dropdown"

/** Every chip is this tall: six of them and the collapse pill still fit the 205dp Duo slot at 1.3. */
internal val ControlChipHeight = 26.dp

/** The press-and-slide rail. Its labels sit inside it, so the rail is the whole track. */
internal val SliderRailWidth = 48.dp
internal val SliderRailHeight = 140.dp

/** Tight, so the closed Duo at 1.3 still has room for the collapse pill over the mode chip. */
private val ChipGap = 2.dp

/** The collapse pill over the chips, as wide as they are: its top margin and height. The chips start under it. */
internal val CollapsePillTop = 6.dp
internal val CollapsePillHeight = 22.dp
private val ControlsTopPadding = CollapsePillTop + CollapsePillHeight + 4.dp

/** Beside the chip, clear of the finger that holds it. */
private val SliderGap = 6.dp
private val WindowMargin = 8.dp

/** One arrow press on the open slider moves it this far. */
private const val KEY_STEP = 0.05f

private val OCTAVES = listOf(-2, -1, 0, 1, 2)

private val ChipText = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)

private fun percent(v: Float): Int = (v * 100f).roundToInt()

/** Where max sits on the Level slider: below it LEVEL 0..1, above it MORE 0..1. */
internal const val MoreNotch = 0.75f

/** Positions this close to the notch (a fraction of the travel) are a dead zone: exactly max, no MORE. */
internal const val MoreDetent = 0.03f

/** Where the Level slider's thumb sits for [level] and [more], 0 at the bottom. Max sits at the dead zone's centre. */
internal fun moreSliderPosition(level: Float, more: Float): Float {
    val low = MoreNotch - MoreDetent
    val high = MoreNotch + MoreDetent
    if (more > 0f) return high + more.coerceIn(0f, 1f) * (1f - high)
    if (level >= 1f) return MoreNotch
    return level.coerceIn(0f, 1f) * low
}

/**
 * The (level, more) a thumb at [position] writes. Inside the dead zone it is exactly (1, 0); below
 * it LEVEL falls from 1 to 0 and above it MORE rises from 0 to 1, each starting at the zone's edge.
 */
internal fun moreSliderValues(position: Float): Pair<Float, Float> {
    val p = if (position.isNaN()) 0f else position.coerceIn(0f, 1f)
    val low = MoreNotch - MoreDetent
    val high = MoreNotch + MoreDetent
    return when {
        p < low -> (p / low) to 0f
        p <= high -> 1f to 0f
        else -> 1f to ((p - high) / (1f - high))
    }
}

/** "Vol NN" up to max, then "More NN" from 100 to 200 once MORE is raised. */
internal fun levelChipText(level: Float, more: Float): String =
    if (more > 0f) "More ${100 + percent(more)}" else "Vol ${percent(level)}"

private fun octaveText(octave: Int): String = if (octave > 0) "+$octave" else "$octave"

/** The engine's short code, or NONE for the bare impulse. */
private fun engineCode(engine: Int): String =
    OrpheusEngineId.entries.getOrNull(engine)?.let { EngineCatalog.codeFor(it) } ?: "NONE"

private fun CowbellFill.title(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private enum class Menu { Mode, Octave }

private enum class SliderKind { Level, Lock }

/** An open slider. [byPointer] sessions follow the held finger and end at its lift; the rest stay until dismissed. */
private class SliderSession(val kind: SliderKind, val byPointer: Boolean)

/**
 * Where the open slider's rail sits in its chip's own coordinates, written when the popup lays out.
 * The chip reads it to turn the held finger's position into a value.
 */
private class SlideTrack {
    var top = 0f
    var height = 1f
}

/**
 * The DJ cowbell's first column: a mode dropdown over small Engine, Octave, Level, Lock and Bend
 * chips. On a phone with sensors the mode dropdown ends in a Motion toggle. Level and Lock also open a vertical slider (see [slideOnLongPress]). The popups sit over
 * this column and never over the bell, so a hand that is playing is not interrupted.
 */
@Composable
internal fun CowbellControlColumn(
    state: CowbellUiState,
    actions: CowbellPanelActions,
    hasNativeEngine: Boolean,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf<Menu?>(null) }
    var slider by remember { mutableStateOf<SliderSession?>(null) }
    // Back closes a dropdown or slider before anything behind it.
    NavigationBackHandler(
        rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = menu != null || slider != null,
        onBackCompleted = {
            menu = null
            slider = null
        },
    )
    // Centred, so a tall slot puts room above the mode chip; the closed Duo at 1.3 has none to give.
    Column(modifier.padding(top = ControlsTopPadding), verticalArrangement = Arrangement.spacedBy(ChipGap, Alignment.CenterVertically)) {
        Learnable(CowbellSymbol.MODE.controlId.key) {
            ControlChip(
                text = "${state.mode.label} ▾",
                tag = CowbellCtlModeTag,
                // Lit until the menu has been opened a few times, so Motion in it gets found.
                highlighted = menu == Menu.Mode || state.modeHint,
                onClick = {
                    actions.onModeMenuOpened()
                    menu = Menu.Mode
                },
            ) {
                ChoiceMenu(
                    expanded = menu == Menu.Mode,
                    entries = CowbellMode.entries,
                    selected = state.mode,
                    label = { it.label },
                    onPick = { actions.onMode(it) },
                    onDismiss = { menu = null },
                    footer = if (state.motionAvailable) {
                        { MotionItem(state, actions.onMotion) { menu = null } }
                    } else {
                        null
                    },
                )
            }
        }
        Learnable(CowbellSymbol.ENGINE.controlId.key) {
            CowbellEnginePicker(
                engine = state.engine,
                onEngine = actions.onEngine,
                hasNativeEngine = hasNativeEngine,
                modifier = Modifier.fillMaxWidth().enginePickerTestTag(CowbellCtlEngineTag),
                label = "Eng ${engineCode(state.engine)}",
                labelStyle = ChipText,
            )
        }
        Learnable(CowbellSymbol.OCTAVE.controlId.key) {
            ControlChip(
                text = "Oct ${octaveText(state.octave)}",
                tag = CowbellCtlOctaveTag,
                highlighted = menu == Menu.Octave,
                onClick = { menu = Menu.Octave },
            ) {
                ChoiceMenu(
                    expanded = menu == Menu.Octave,
                    entries = OCTAVES,
                    selected = state.octave,
                    label = ::octaveText,
                    onPick = { actions.onOctave(it) },
                    onDismiss = { menu = null },
                )
            }
        }
        Learnable(CowbellSymbol.LEVEL.controlId.key) {
            SliderChip(
                kind = SliderKind.Level,
                text = levelChipText(state.level, state.more),
                tag = CowbellCtlLevelTag,
                value = moreSliderPosition(state.level, state.more),
                onValue = { position ->
                    val (level, more) = moreSliderValues(position)
                    actions.onLevel(level)
                    actions.onMore(more)
                },
                topLabel = "MORE",
                bottomLabel = "Mute",
                notchLabel = "100",
                // Past max the chip takes the accent treatment.
                highlighted = state.more > 0f,
                session = slider,
                onSession = {
                    menu = null
                    slider = it
                },
                onTap = { slider = SliderSession(SliderKind.Level, byPointer = false) },
            )
        }
        Learnable(CowbellSymbol.GRID_STRENGTH.controlId.key) {
            SliderChip(
                kind = SliderKind.Lock,
                text = if (state.grid) "Lock ${percent(state.lock)}" else "Lock off",
                tag = CowbellCtlLockTag,
                value = state.lock,
                onValue = actions.onLock,
                topLabel = "Locked",
                bottomLabel = "Off",
                session = slider,
                onSession = {
                    menu = null
                    slider = it
                },
                highlighted = state.grid,
                // Off keeps its strength, so the tap brings LOCK back to where it was.
                onTap = { actions.onGrid(!state.grid) },
            )
        }
        // The VM does not watch a CC-driven BEND_SCALE, so Bend stays out of MIDI learn.
        ControlChip(
            text = "Bend ${if (state.bendScale) "Scale" else "Free"}",
            tag = CowbellCtlBendTag,
            highlighted = state.bendScale,
            onClick = { actions.onBendScale(!state.bendScale) },
        )
    }
}

/**
 * The Fill dropdown for SHAKE: Marble, Beads, Seeds, Jingles. The panel puts it under the bell.
 */
@Composable
internal fun FillDropdown(
    fill: CowbellFill,
    onFill: (CowbellFill) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    NavigationBackHandler(
        rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = expanded,
        onBackCompleted = { expanded = false },
    )
    Learnable(CowbellSymbol.FILL.controlId.key, modifier) {
        ControlChip(
            text = "${fill.title()} ▾",
            tag = CowbellFillDropdownTag,
            highlighted = expanded,
            onClick = { expanded = true },
        ) {
            ChoiceMenu(
                expanded = expanded,
                entries = CowbellFill.entries,
                selected = fill,
                label = { it.title() },
                onPick = onFill,
                onDismiss = { expanded = false },
            )
        }
    }
}

/** A small value chip. [popups] compose in the chip's box, so they anchor to it. */
@Composable
private fun ControlChip(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    gestures: Modifier = Modifier,
    onClick: () -> Unit,
    popups: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(modifier.fillMaxWidth().height(ControlChipHeight)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                // Before clickable, so the press is seen first and a long press can take it over.
                .then(gestures)
                .clip(shape)
                .background(CowbellPanelColor.copy(alpha = if (highlighted) 0.32f else 0.15f))
                .border(
                    if (highlighted) 1.5.dp else 1.dp,
                    CowbellPanelColor.copy(alpha = if (highlighted) 0.9f else 0.45f),
                    shape,
                )
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(tag)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, style = ChipText, color = CowbellPanelColor, maxLines = 1)
        }
        popups()
    }
}

/** A dropdown of [entries] under its chip. */
@Composable
private fun <T> ChoiceMenu(
    expanded: Boolean,
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = OrpheusColors.deepPurple,
    ) {
        for (entry in entries) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = label(entry),
                        style = ChipText,
                        color = CowbellPanelColor,
                        fontWeight = if (entry == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = {
                    onPick(entry)
                    onDismiss()
                },
            )
        }
        footer?.invoke(this)
    }
}

/** The phone's tilt, jolts and shakes, for every mode. */
@Composable
private fun ColumnScope.MotionItem(state: CowbellUiState, onMotion: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val on = state.motion
    HorizontalDivider(color = CowbellPanelColor.copy(alpha = 0.3f))
    DropdownMenuItem(
        text = {
            Text(
                text = "Motion ${if (on) "on" else "off"}",
                style = ChipText,
                color = CowbellPanelColor,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            )
        },
        onClick = {
            onMotion(!state.motion)
            onDismiss()
        },
        modifier = Modifier.testTag(CowbellCtlMotionTag),
    )
}

/**
 * A chip that opens a vertical slider: on a long press (press-and-slide, one gesture), on a held
 * OK, or on a tap when [onTap] opens one. [onValue] writes live and the value stays where it was let go.
 */
@Composable
private fun SliderChip(
    kind: SliderKind,
    text: String,
    tag: String,
    value: Float,
    onValue: (Float) -> Unit,
    topLabel: String,
    bottomLabel: String,
    session: SliderSession?,
    onSession: (SliderSession?) -> Unit,
    onTap: () -> Unit,
    highlighted: Boolean = false,
    notchLabel: String? = null,
) {
    val track = remember { SlideTrack() }
    val open = session?.takeIf { it.kind == kind }
    val gestures = Modifier
        .slideOnLongPress(
            track = track,
            onStart = { onSession(SliderSession(kind, byPointer = true)) },
            onValue = onValue,
            onEnd = { onSession(null) },
        )
        .holdOk(sliderOpen = open != null, onHold = { onSession(SliderSession(kind, byPointer = false)) }, onTap = onTap)
    ControlChip(
        text = text,
        tag = tag,
        highlighted = highlighted || open != null,
        gestures = gestures,
        onClick = onTap,
    ) {
        if (open != null) {
            SliderPopup(
                session = open,
                track = track,
                value = value,
                onValue = onValue,
                topLabel = topLabel,
                bottomLabel = bottomLabel,
                notchLabel = notchLabel,
                onDismiss = { onSession(null) },
            )
        }
    }
}

@Composable
private fun SliderPopup(
    session: SliderSession,
    track: SlideTrack,
    value: Float,
    onValue: (Float) -> Unit,
    topLabel: String,
    bottomLabel: String,
    notchLabel: String?,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    // Placed once, with the thumb at the chip, so the rail does not move under the finger.
    val positioner = remember(session) {
        SliderPositioner(track, value, with(density) { SliderGap.roundToPx() }, with(density) { WindowMargin.roundToPx() })
    }
    // The shown value follows what was written at once, ahead of the state coming back around.
    var shown by remember(session) { mutableFloatStateOf(value) }
    LaunchedEffect(value) { shown = value }
    val write = { v: Float ->
        shown = v
        onValue(v)
    }
    val focus = remember { FocusRequester() }
    Popup(
        popupPositionProvider = positioner,
        onDismissRequest = onDismiss,
        // A held finger keeps its gesture, so that popup takes no focus; the rest read the arrows.
        properties = PopupProperties(focusable = !session.byPointer),
    ) {
        val keys = if (session.byPointer) {
            Modifier
        } else {
            Modifier
                .focusRequester(focus)
                .onKeyEvent { event ->
                    val step = when (event.key) {
                        Key.DirectionUp -> KEY_STEP
                        Key.DirectionDown -> -KEY_STEP
                        // The OK that opened this may still be down and repeating.
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> 0f
                        else -> return@onKeyEvent false
                    }
                    if (step != 0f && event.type == KeyEventType.KeyDown) write((shown + step).coerceIn(0f, 1f))
                    true
                }
                .focusable()
        }
        CowbellVerticalSlider(
            value = shown,
            onValue = write,
            topLabel = topLabel,
            bottomLabel = bottomLabel,
            notchLabel = notchLabel,
            modifier = keys,
        )
    }
    if (!session.byPointer) LaunchedEffect(session) { focus.requestFocus() }
}

/** Beside the chip on the bell side, its thumb level with the chip; kept in the window. */
private class SliderPositioner(
    private val track: SlideTrack,
    private val value: Float,
    private val gap: Int,
    private val margin: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val h = popupContentSize.height
        val thumbAtChip = anchorBounds.center.y - ((1f - value) * h).roundToInt()
        val y = thumbAtChip.coerceIn(margin, (windowSize.height - h - margin).coerceAtLeast(margin))
        val right = anchorBounds.right + gap
        val x = if (right + popupContentSize.width <= windowSize.width - margin) {
            right
        } else {
            (anchorBounds.left - gap - popupContentSize.width).coerceAtLeast(margin)
        }
        track.top = (y - anchorBounds.top).toFloat()
        track.height = h.toFloat()
        return IntOffset(x, y)
    }
}

/**
 * A vertical rail: the value is the height of the finger on it, 0 at the bottom. [topLabel] and
 * [bottomLabel] sit inside the rail, so the rail is the whole track. Pressing or dragging on it sets the value.
 * A [notchLabel] marks the max notch at [MoreNotch] with a line and that label under it.
 */
@Composable
internal fun CowbellVerticalSlider(
    value: Float,
    onValue: (Float) -> Unit,
    topLabel: String,
    bottomLabel: String,
    modifier: Modifier = Modifier,
    notchLabel: String? = null,
) {
    val latest by rememberUpdatedState(onValue)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(SliderRailWidth, SliderRailHeight)
            .clip(shape)
            .background(OrpheusColors.deepPurple)
            .drawBehind {
                val v = value.coerceIn(0f, 1f)
                val top = size.height * (1f - v)
                drawRect(CowbellPanelColor.copy(alpha = 0.3f), Offset(0f, top), Size(size.width, size.height - top))
                drawLine(
                    CowbellPanelColor,
                    Offset(6.dp.toPx(), top.coerceIn(1.5.dp.toPx(), size.height - 1.5.dp.toPx())),
                    Offset(size.width - 6.dp.toPx(), top.coerceIn(1.5.dp.toPx(), size.height - 1.5.dp.toPx())),
                    strokeWidth = 3.dp.toPx(),
                )
                if (notchLabel != null) {
                    val y = size.height * (1f - MoreNotch)
                    drawLine(
                        CowbellPanelColor.copy(alpha = 0.8f),
                        Offset(0f, y),
                        Offset(size.width, y),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
                drawRoundRect(
                    CowbellPanelColor.copy(alpha = 0.6f),
                    size = size,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(1.dp.toPx()),
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun at(y: Float) = (1f - y / size.height).coerceIn(0f, 1f)
                    latest(at(down.position.y))
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        latest(at(change.position.y))
                        change.consume()
                    }
                }
            }
            .testTag(CowbellCtlSliderTag),
    ) {
        Text(
            text = topLabel,
            style = ChipText,
            color = CowbellPanelColor,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
        )
        if (notchLabel != null) {
            Text(
                text = notchLabel,
                style = ChipText,
                color = CowbellPanelColor,
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = SliderRailHeight * (1f - MoreNotch) + 2.dp),
            )
        }
        Text(
            text = bottomLabel,
            style = ChipText,
            color = CowbellPanelColor,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
        )
    }
}

/**
 * Press and hold opens the slider at the timeout, with the finger still down; the same finger
 * then slides it. The press may drift while held (a finger rolls), and only leaving the chip
 * cancels it, as the DJ nav's long press does. Lifting ends the slide.
 */
@Composable
private fun Modifier.slideOnLongPress(
    track: SlideTrack,
    onStart: () -> Unit,
    onValue: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val start by rememberUpdatedState(onStart)
    val write by rememberUpdatedState(onValue)
    val end by rememberUpdatedState(onEnd)
    return pointerInput(track) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Released, taken by another handler, or off the chip before the timeout: an ordinary tap or scroll.
            var last = down.position
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull
                    if (!change.pressed || change.isConsumed || !change.isOverChip(size.width, size.height, viewConfiguration.touchSlop)) {
                        return@withTimeoutOrNull
                    }
                    last = change.position
                }
            } == null
            if (!held) return@awaitEachGesture

            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            start()
            try {
                // Nothing is written until the finger moves, so a still hold changes nothing.
                var moved = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!moved && abs(change.position.y - last.y) > viewConfiguration.touchSlop) moved = true
                    // The track is a 1px stand-in until the popup first lays out.
                    if (moved && track.height > 1f) {
                        write((1f - (change.position.y - track.top) / track.height).coerceIn(0f, 1f))
                    }
                    if (!change.pressed) break
                }
            } finally {
                end()
            }
        }
    }
}

private fun PointerInputChange.isOverChip(width: Int, height: Int, margin: Float): Boolean =
    position.x >= -margin && position.y >= -margin && position.x <= width + margin && position.y <= height + margin

/**
 * The remote's version of a tap and a long press. A key-down that repeats while OK is held is the
 * long press, and the lift after it is not a tap. OK never reaches the chip's own click, so a hold
 * cannot also fire it.
 */
@Composable
private fun Modifier.holdOk(sliderOpen: Boolean, onHold: () -> Unit, onTap: () -> Unit): Modifier {
    val hold by rememberUpdatedState(onHold)
    val tap by rememberUpdatedState(onTap)
    val state = remember { OkState() }
    // The slider popup takes focus and eats the lift, so the slider closing ends the press.
    LaunchedEffect(sliderOpen) {
        if (!sliderOpen) {
            state.down = false
            state.held = false
        }
    }
    return onPreviewKeyEvent { event ->
        if (!event.isOk()) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (!state.down) {
                    state.down = true
                    state.held = false
                } else if (!state.held) {
                    state.held = true
                    hold()
                }
                true
            }
            KeyEventType.KeyUp -> {
                val wasDown = state.down
                val wasHeld = state.held
                state.down = false
                state.held = false
                if (wasDown && !wasHeld) tap()
                wasDown
            }
            else -> false
        }
    }
}

private class OkState {
    var down = false
    var held = false
}

private fun KeyEvent.isOk() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
