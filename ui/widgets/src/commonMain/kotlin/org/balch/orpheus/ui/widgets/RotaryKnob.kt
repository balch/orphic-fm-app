package org.balch.orpheus.ui.widgets

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.balch.orpheus.ui.infrastructure.LocalTvFocusChrome
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.theme.proportional
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** Shared state holder for knob drag interaction. */
/** One D-pad press moves 2.5% of the range; 40 presses cross the whole knob. */
private const val StepFraction = 0.025f
private const val FineStepFraction = 0.005f

internal class KnobDragState(
    initialValue: Float,
    private val range: ClosedFloatingPointRange<Float>,
    private val sensitivity: Float,
) {
    var internalValue by mutableStateOf(initialValue)

    /**
     * Nudge by whole steps, for D-pad and arrow keys. Expressed as a fraction of the range
     * rather than pixels: a key press has no drag distance, and a value step also sidesteps
     * the density scaling that makes pixel deltas differ per screen.
     */
    fun applyStep(steps: Int, fine: Boolean = false): Float? {
        val fraction = if (fine) FineStepFraction else StepFraction
        val delta = steps * (range.endInclusive - range.start) * fraction
        val newValue = (internalValue + delta).coerceIn(range)
        return if (newValue != internalValue) {
            internalValue = newValue
            newValue
        } else null
    }

    fun applyDrag(dragAmount: Float, ctrlPressed: Boolean, fineTune: Boolean = false): Float? {
        val ctrlMultiplier = if (ctrlPressed) 20f else 1f
        val effectiveSensitivity = sensitivity * (if (fineTune) 10f else 1f) * ctrlMultiplier
        val delta = (-dragAmount) * (range.endInclusive - range.start) / effectiveSensitivity
        val newValue = (internalValue + delta).coerceIn(range)
        return if (newValue != internalValue) {
            internalValue = newValue
            newValue
        } else null
    }
}

@Composable
internal fun rememberKnobDragState(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    sensitivity: Float = 200f,
): KnobDragState {
    val state = remember(value) { KnobDragState(value, range, sensitivity) }
    return state
}

/**
 * The knob circle + arc drawing with drag interaction.
 * Shared between [RotaryKnob] and [HorizontalRotaryKnob].
 */
/**
 * Applies one step only while the knob is in adjust mode, and reports whether the key was
 * consumed. Outside adjust mode the arrow key is left alone so focus traversal still works.
 */
private fun stepIf(
    adjusting: Boolean,
    dragState: KnobDragState,
    steps: Int,
    fine: Boolean,
    onValueChange: (Float) -> Unit,
): Boolean {
    if (!adjusting) return false
    dragState.applyStep(steps, fine)?.let(onValueChange)
    return true
}

/**
 * The knob's vertical drag, in pixels. With [requireVerticalSlop] nothing is consumed or applied until the
 * pointer travels touch slop vertically; a lift or horizontal slop first leaves the gesture to a parent scroll.
 */
private suspend fun PointerInputScope.detectKnobDrag(
    requireVerticalSlop: Boolean,
    onDrag: (dragAmount: Float, ctrlPressed: Boolean) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var previousY = down.position.y
        var pending: PointerEvent? = null
        if (requireVerticalSlop) {
            pending = awaitVerticalSlop(down) ?: return@awaitEachGesture
            // Counted from the slop's edge, so the knob doesn't jump by the slop.
            val y = pending.changes.first { it.id == down.id }.position.y
            previousY += sign(y - previousY) * viewConfiguration.touchSlop
        }
        while (true) {
            val event = pending ?: awaitPointerEvent()
            pending = null
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            val dragAmount = change.position.y - previousY
            previousY = change.position.y
            if (dragAmount != 0f) {
                change.consume()
                onDrag(dragAmount, event.keyboardModifiers.isCtrlPressed)
            }
        }
    }
}

/** The event where [down]'s pointer passes vertical touch slop, or null if it lifts, is consumed or goes sideways first. */
private suspend fun AwaitPointerEventScope.awaitVerticalSlop(down: PointerInputChange): PointerEvent? {
    val slop = viewConfiguration.touchSlop
    var moved = Offset.Zero
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed || change.isConsumed) return null
        moved += change.positionChange()
        if (abs(moved.y) > slop) return event
        if (abs(moved.x) > slop) return null
    }
}

/** A small padlock centred on the knob body, drawn in place of the indicator notch. */
private fun DrawScope.drawPadlock(center: Offset, knobRadius: Float, strokeWidth: Float, color: Color) {
    val bodyW = knobRadius * 0.95f
    val bodyH = knobRadius * 0.7f
    val bodyTop = center.y - bodyH * 0.2f
    val r = bodyW * 0.3f
    val shackleTop = bodyTop - r * 0.4f
    val stroke = Stroke(width = strokeWidth * 0.55f, cap = StrokeCap.Round)
    drawArc(
        color = color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(center.x - r, shackleTop - r),
        size = Size(r * 2, r * 2),
        style = stroke,
    )
    drawLine(color, Offset(center.x - r, shackleTop), Offset(center.x - r, bodyTop), stroke.width)
    drawLine(color, Offset(center.x + r, shackleTop), Offset(center.x + r, bodyTop), stroke.width)
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - bodyW / 2, bodyTop),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(bodyW * 0.15f),
    )
}

/** A press that lifts within touch slop. Any drag past slop is not a tap. */
private suspend fun PointerInputScope.detectStillTap(onTap: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        var moved = Offset.Zero
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) {
                change.consume()
                onTap()
                return@awaitEachGesture
            }
            moved += change.positionChange()
            if (moved.getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
        }
    }
}

/** True from a pointer's press anywhere on the knob until every pointer lifts. Observes only. */
private suspend fun PointerInputScope.trackHeld(onHeld: (Boolean) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onHeld(true)
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        } finally {
            onHeld(false)
        }
    }
}

/** Places a popup centred above its anchor, kept inside the window. */
private class AboveAnchorPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.center.x - popupContentSize.width / 2
        val y = anchorBounds.top - popupContentSize.height - gap
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(x.coerceIn(0, maxX), y.coerceAtLeast(0))
    }
}

/** The knob's long label, shown above it while it is held. */
@Composable
private fun KnobLongLabel(text: String, color: Color) {
    val density = LocalDensity.current
    val provider = remember(density) { AboveAnchorPositionProvider(with(density) { 4.dp.roundToPx() }) }
    Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = OrpheusColors.pickerText,
            maxLines = 1,
            modifier = Modifier
                .background(OrpheusColors.pickerSurface, RoundedCornerShape(6.dp))
                .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * The value arc as (start, sweep) in degrees. Unipolar fills from the dial's start; bipolar runs
 * from 12 o'clock, the middle of the dial, toward the value, so its sweep is negative below centre.
 */
internal fun progressArc(normalized: Float, bipolar: Boolean, startAngle: Float, sweepAngle: Float): Pair<Float, Float> =
    if (bipolar) {
        (startAngle + sweepAngle / 2f) to sweepAngle * (normalized - 0.5f)
    } else {
        startAngle to sweepAngle * normalized
    }

/**
 * Sweep-gradient stops that run [from] to [to] along the dial. A sweep gradient starts at 3 o'clock,
 * so a dial that wraps past it gets the colour at 3 o'clock on both ends instead of a seam there.
 */
internal fun dialGradientStops(from: Color, to: Color, startAngle: Float, sweepAngle: Float): Array<Pair<Float, Color>> {
    val endAngle = startAngle + sweepAngle
    if (endAngle <= 360f) return arrayOf(startAngle / 360f to from, endAngle / 360f to to)
    val atThreeOClock = lerp(from, to, (360f - startAngle) / sweepAngle)
    return arrayOf(0f to atThreeOClock, (endAngle - 360f) / 360f to to, startAngle / 360f to from, 1f to atThreeOClock)
}

@Composable
internal fun RotaryKnobDial(
    dragState: KnobDragState,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    trackColor: Color = OrpheusColors.deepPurple,
    progressColor: Color = OrpheusColors.neonCyan,
    knobColor: Color = OrpheusColors.softPurple,
    indicatorColor: Color = OrpheusColors.neonCyan,
    isLearning: Boolean = false,
    enabled: Boolean = true,
    requireVerticalSlop: Boolean = false,
    locked: Boolean = false,
    onLockedClick: (() -> Unit)? = null,
    bipolar: Boolean = false,
    // Preview/render-harness seam only: forces the focus/adjust visuals without real input.
    // null (the default, used by every real call site) means "use live focus state" below.
    previewFocused: Boolean? = null,
    previewAdjusting: Boolean = false,
) {
    val sensitivity = 200f
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnLockedClick by rememberUpdatedState(onLockedClick)
    var liveFocused by remember { mutableStateOf(false) }
    // D-pad left/right is also how focus moves, so a knob may only consume those keys once
    // the user has explicitly entered adjust mode with select. Otherwise focus is trapped:
    // you can reach a knob with the remote and never leave it.
    var liveAdjusting by remember { mutableStateOf(false) }
    val isFocused = previewFocused ?: liveFocused
    val isAdjusting = if (previewFocused != null) previewAdjusting else liveAdjusting

    // TV: a thin ring is too subtle from couch distance and against a busy viz background, so
    // focus gets an opaque raised plate instead (see raisedAccentSurface's language). Gated so
    // every other platform's keyboard-focus ring (drawn below) is unchanged.
    val isTvFocusChrome = LocalTvFocusChrome.current
    // Read as a State, NOT via `by` here — destructuring with `by` reads .value right in this
    // composable body, so every pulse tick recomposed RotaryKnobDial (rebuilding the whole
    // modifier chain below) just to feed a value the draw lambda alone consumes. Deferring the
    // .value read into the Canvas draw lambda (see adjustPulseAlpha below) keeps the invariant
    // this file otherwise holds throughout: dragState.internalValue is read inside the draw
    // lambda for the same reason.
    val adjustPulseAlphaState: State<Float>? = if (isTvFocusChrome && isAdjusting) {
        val transition = rememberInfiniteTransition(label = "knobAdjustPulse")
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(tween(550), repeatMode = RepeatMode.Reverse),
            label = "knobAdjustPulseAlpha",
        )
    } else {
        null
    }

    Box(modifier = modifier.size(size)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // D-pad and arrow keys drive the knob by value steps. Focusable comes after
                // onKeyEvent so the handler is in the chain for the node that takes focus.
                .onFocusChanged {
                    liveFocused = it.isFocused
                    if (!it.isFocused) liveAdjusting = false
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || isLearning || !enabled) {
                        return@onKeyEvent false
                    }
                    // Locked: select opens whatever the lock points at; arrows keep moving focus.
                    if (locked) {
                        return@onKeyEvent when (event.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                currentOnLockedClick?.invoke()
                                currentOnLockedClick != null
                            }
                            else -> false
                        }
                    }
                    when (event.key) {
                        // Select toggles adjust mode; back leaves it without exiting the app.
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            liveAdjusting = !liveAdjusting
                            true
                        }
                        Key.Back, Key.Escape -> {
                            val wasAdjusting = liveAdjusting
                            liveAdjusting = false
                            wasAdjusting
                        }
                        Key.DirectionRight, Key.DirectionUp -> stepIf(
                            liveAdjusting, dragState, 1, event.isCtrlPressed, currentOnValueChange,
                        )
                        Key.DirectionLeft, Key.DirectionDown -> stepIf(
                            liveAdjusting, dragState, -1, event.isCtrlPressed, currentOnValueChange,
                        )
                        else -> false
                    }
                }
                .focusable(enabled = enabled && !isLearning)
                .pointerInput(sensitivity, range, isLearning, enabled, requireVerticalSlop, locked) {
                    if (isLearning || !enabled || locked) return@pointerInput
                    detectKnobDrag(requireVerticalSlop) { dragAmount, ctrl ->
                        dragState.applyDrag(dragAmount, ctrl)?.let { currentOnValueChange(it) }
                    }
                }
        ) {
            val strokeWidth = size.toPx() * 0.1f
            val radius = (size.toPx() - strokeWidth) / 2
            val center = Offset(size.toPx() / 2, size.toPx() / 2)
            val arcSize = Size(radius * 2, radius * 2)
            val topLeft = Offset(center.x - radius, center.y - radius)

            val startAngle = 135f
            val sweepAngle = 270f

            // Focus treatment: a full circle outside the dial, so a D-pad selection is legible
            // from across a room rather than a hairline outline.
            if (isFocused && isTvFocusChrome) {
                // TV: raised opaque plate — lit-top/dark-bottom bevel fill plus a drop shadow —
                // rather than a thin ring, so it reads as "lifted" even over a bright/busy viz.
                // Adjusting uses progressColor and adds a pulsing outer glow so the two states
                // are unmistakably different: a static plate means the D-pad moves focus, a
                // pulsing glow means left/right now changes the value.
                val plateAccent = if (isAdjusting) progressColor else indicatorColor
                val plateRadius = radius + strokeWidth * 1.8f

                drawCircle(
                    color = Color.Black.copy(alpha = 0.6f),
                    radius = plateRadius,
                    center = center.copy(y = center.y + strokeWidth * 0.35f),
                )
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            plateAccent.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.85f),
                        ),
                        startY = center.y - plateRadius,
                        endY = center.y + plateRadius,
                    ),
                    radius = plateRadius,
                    center = center,
                )
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(plateAccent, Color.Black.copy(alpha = 0.6f)),
                        startY = center.y - plateRadius,
                        endY = center.y + plateRadius,
                    ),
                    radius = plateRadius,
                    center = center,
                    style = Stroke(width = strokeWidth * 0.35f),
                )
                if (isAdjusting) {
                    // .value read here, inside the draw lambda, so the 60Hz pulse invalidates
                    // only this draw pass — see adjustPulseAlphaState above.
                    drawCircle(
                        color = progressColor.copy(alpha = adjustPulseAlphaState?.value ?: 0f),
                        radius = plateRadius + strokeWidth * 0.7f,
                        center = center,
                        style = Stroke(width = strokeWidth * 0.5f),
                    )
                }
            } else if (isFocused) {
                // Non-TV keyboard focus: unchanged from before this TV pass.
                drawCircle(
                    color = if (isAdjusting) progressColor else indicatorColor,
                    radius = radius + strokeWidth * 0.55f,
                    center = center,
                    style = Stroke(width = strokeWidth * (if (isAdjusting) 0.9f else 0.45f)),
                )
            }

            // Track Groove (Shadow)
            drawArc(
                color = Color.Black.copy(alpha = 0.5f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            drawArc(
                color = trackColor.copy(alpha = 0.3f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active Progress Arc with Glow; a locked knob dims its ring.
            val ring = if (locked) 0.35f else 1f
            val normalizedValue =
                (dragState.internalValue - range.start) / (range.endInclusive - range.start)
            val currentSweep = sweepAngle * normalizedValue
            val (arcStart, arcSweep) = progressArc(normalizedValue, bipolar, startAngle, sweepAngle)
            // A bipolar arc runs either way from 12 o'clock, so it is drawn solid: the sweep gradient fades its start.
            val glowBrush: Brush = if (bipolar) SolidColor(progressColor.copy(alpha = 0.45f * ring)) else Brush.sweepGradient(
                *dialGradientStops(progressColor.copy(alpha = 0.0f), progressColor.copy(alpha = 0.6f * ring), startAngle, sweepAngle),
                center = center,
            )
            val arcBrush: Brush = if (bipolar) SolidColor(progressColor.copy(alpha = ring)) else Brush.sweepGradient(
                *dialGradientStops(progressColor.copy(alpha = 0.5f * ring), progressColor.copy(alpha = ring), startAngle, sweepAngle),
                center = center,
            )

            drawArc(
                brush = glowBrush,
                startAngle = arcStart,
                sweepAngle = arcSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth * 1.5f, cap = StrokeCap.Round)
            )

            drawArc(
                brush = arcBrush,
                startAngle = arcStart,
                sweepAngle = arcSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Knob Body
            val knobRadius = radius * 0.7f
            val angleInDegrees = startAngle + currentSweep
            val angleInRadians = angleInDegrees.toDouble() * PI / 180.0

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent),
                    center = center.copy(y = center.y + 4.dp.toPx()),
                    radius = knobRadius + 4.dp.toPx()
                ),
                radius = knobRadius + 4.dp.toPx(),
                center = center.copy(y = center.y + 4.dp.toPx())
            )

            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        knobColor.copy(alpha = 0.8f),
                        trackColor,
                        Color.Black
                    ),
                    start = Offset(center.x - knobRadius, center.y - knobRadius),
                    end = Offset(center.x + knobRadius, center.y + knobRadius)
                ),
                radius = knobRadius,
                center = center
            )

            drawCircle(
                style = Stroke(width = 2.dp.toPx()),
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.3f),
                        Color.Black.copy(alpha = 0.6f)
                    ),
                    start = Offset(center.x - knobRadius, center.y - knobRadius),
                    end = Offset(center.x + knobRadius, center.y + knobRadius)
                ),
                radius = knobRadius,
                center = center
            )

            if (locked) {
                drawPadlock(center, knobRadius, strokeWidth, indicatorColor)
                return@Canvas
            }

            // Indicator (Notch)
            val indicatorLength = knobRadius * 0.5f
            val endX = center.x + indicatorLength * cos(angleInRadians).toFloat()
            val endY = center.y + indicatorLength * sin(angleInRadians).toFloat()

            drawLine(
                color = indicatorColor,
                start = center,
                end = Offset(endX, endY),
                strokeWidth = strokeWidth * 0.8f,
                cap = StrokeCap.Round
            )

            drawCircle(
                color = indicatorColor,
                radius = strokeWidth * 0.6f,
                center = Offset(endX, endY)
            )
        }
    }
}

/** How far the label grows at the top of its pulse, as a scale factor on the drawn text. */
private const val LabelPulseScale = 0.1f

/**
 * The knob's label. When [onLongPress] is set the label owns the mode switch, so the dial's
 * pointer stays drag-only: a still touch of the long-press timeout fires it, and on TV the
 * label takes D-pad focus and a select press fires it. While [pulse] is true the text breathes
 * between [color] and [pulseColor] and grows by [LabelPulseScale] every 700 ms. The size pulse
 * is a graphicsLayer scale, not a font size, so the knob column never relayouts. Its own
 * composable so the pulse recomposes this Text alone, not the whole knob.
 */
@Composable
private fun KnobLabel(
    text: String,
    style: TextStyle,
    color: Color,
    pulseColor: Color,
    pulse: Boolean,
    onLongPress: (() -> Unit)?,
) {
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    var focused by remember { mutableStateOf(false) }
    // State, not `by`: the draw-phase graphicsLayer lambda reads .value itself, so the scale
    // never recomposes anything. Only the colour read below recomposes this Text.
    val pulseState: State<Float>? = if (pulse) {
        val transition = rememberInfiniteTransition(label = "knobLabelPulse")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600), repeatMode = RepeatMode.Reverse),
            label = "knobLabelPulse",
        )
    } else {
        null
    }
    val shown = when {
        pulseState != null -> lerp(color, pulseColor, pulseState.value)
        focused -> pulseColor
        else -> color
    }
    Text(
        text = text,
        style = style,
        color = shown,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .graphicsLayer {
                val s = 1f + LabelPulseScale * (pulseState?.value ?: 0f)
                scaleX = s
                scaleY = s
            }
            .pointerInput(onLongPress != null) {
                if (onLongPress == null) return@pointerInput
                detectTapGestures(onLongPress = { currentOnLongPress?.invoke() })
            }
            // TV: the label is a focus stop only when it has something to fire, so the other
            // knob labels don't double every D-pad hop. Select fires on key UP, once, so a
            // held remote button's key repeat can't toggle the mode back and forth.
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (onLongPress == null || event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        currentOnLongPress?.invoke()
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled = onLongPress != null),
    )
}

/** Fine-tune value text with precision drag (10x slower). */
@Composable
internal fun KnobValueText(
    dragState: KnobDragState,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    progressColor: Color,
    valueFormatter: (Float) -> String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
    requireVerticalSlop: Boolean = false,
    locked: Boolean = false,
) {
    val sensitivity = 200f
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    Text(
        text = valueFormatter(dragState.internalValue),
        style = MaterialTheme.typography.labelMedium,
        color = progressColor.lighten(0.3f),
        textAlign = textAlign,
        maxLines = 1,
        modifier = modifier.pointerInput(range, sensitivity, requireVerticalSlop, locked) {
            if (locked) return@pointerInput
            detectKnobDrag(requireVerticalSlop) { dragAmount, ctrl ->
                dragState.applyDrag(dragAmount, ctrl, fineTune = true)?.let { currentOnValueChange(it) }
            }
        }
    )
}

@Composable
@Preview
fun RotaryKnobPreview() {
    OrpheusTheme {
        RotaryKnob(
            label = "Volume",
            value = 0.5f,
            onValueChange = {}
        )
    }
}

typealias KnobValueFormatter = (Float) -> String

/** Two decimals, the readout of a knob that has no unit of its own. */
val DefaultValueFormatter: KnobValueFormatter = { v -> ((v * 100).roundToInt() / 100.0).toString() }

/**
 * A synth-style rotary knob control with vertical layout (knob above, label below).
 * Supports vertical drag interaction for precision.
 *
 * @param controlId Optional ID for MIDI learn mode. If provided, this knob can be selected for CC mapping.
 * @param onLongPress Fires after a still press of the label for the platform long-press timeout,
 *   or a select press while the label holds D-pad focus. The dial itself stays drag-only.
 *   Null (the default) means no long press and the label is not focusable.
 * @param pulseLabel Pulses the label's colour toward [indicatorColor] and its size while true,
 *   for a mode this knob switches.
 * @param requireVerticalSlop For a knob inside a scrolling list: the dial and value text change
 *   nothing until a drag passes vertical touch slop, and a sideways or short gesture is left alone.
 *   False (the default) turns on the first pixel of movement.
 * @param locked Drags and arrow keys change nothing; the ring dims and a padlock replaces the
 *   notch. A click, or select while focused, fires [onLockedClick] instead.
 * @param caption A small second line under [label], such as the knob's glyph.
 * @param longLabel Shown above the knob while it is pressed, for a [label] too short to explain itself.
 * @param bipolar Draws the value arc from 12 o'clock toward the value, for a range centred on zero.
 */
@Composable
fun RotaryKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
    controlId: String? = null,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    size: Dp = 64.dp,
    trackColor: Color = OrpheusColors.deepPurple,
    progressColor: Color = OrpheusColors.neonCyan,
    knobColor: Color = OrpheusColors.softPurple,
    indicatorColor: Color = OrpheusColors.neonCyan,
    labelColor: Color = progressColor,
    enabled: Boolean = true,
    valueFormatter: KnobValueFormatter? = { value ->
        ((value * 100).roundToInt() / 100.0).toString()
    },
    onLongPress: (() -> Unit)? = null,
    pulseLabel: Boolean = false,
    requireVerticalSlop: Boolean = false,
    locked: Boolean = false,
    onLockedClick: (() -> Unit)? = null,
    caption: String? = null,
    longLabel: String? = null,
    bipolar: Boolean = false,
    // Preview/render-harness seam only: forces the focus/adjust visuals without real input.
    // Every production call site leaves this null and gets live focus behavior.
    previewFocused: Boolean? = null,
    previewAdjusting: Boolean = false,
) {
    val learnState = LocalLearnModeState.current
    val isLearning = controlId != null && learnState.isLearning(controlId)
    val highlightMod = if (controlId != null) Modifier.highlightable(controlId) else Modifier
    val dragState = rememberKnobDragState(value, range)
    val currentOnLockedClick by rememberUpdatedState(onLockedClick)
    // In learn mode a tap selects the knob for MIDI, so the lock's tap must not eat it.
    val learnMode = controlId != null && learnState.isActive
    val lockClickable = locked && enabled && !learnMode && onLockedClick != null
    var held by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .then(highlightMod)
            .then(
                if (controlId != null && learnState.isActive) {
                    Modifier.learnable(controlId, learnState)
                } else {
                    Modifier
                }
            )
            .then(
                if (lockClickable) {
                    Modifier
                        .pointerInput(Unit) { detectStillTap { currentOnLockedClick?.invoke() } }
                        .semantics {
                            role = Role.Button
                            onClick { currentOnLockedClick?.invoke(); true }
                        }
                } else {
                    Modifier
                }
            )
            .then(
                if (longLabel != null) {
                    Modifier.pointerInput(Unit) { trackHeld { held = it } }
                } else {
                    Modifier
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (held && longLabel != null) KnobLongLabel(longLabel, progressColor)
        RotaryKnobDial(
            dragState = dragState,
            onValueChange = onValueChange,
            size = size,
            range = range,
            trackColor = trackColor,
            progressColor = progressColor,
            knobColor = knobColor,
            indicatorColor = indicatorColor,
            isLearning = isLearning,
            enabled = enabled,
            requireVerticalSlop = requireVerticalSlop,
            locked = locked,
            onLockedClick = if (learnMode) null else onLockedClick,
            bipolar = bipolar,
            previewFocused = previewFocused,
            previewAdjusting = previewAdjusting,
        )

        if (label != null) {
            Spacer(modifier = Modifier.height(2.dp))
            KnobLabel(
                text = label,
                style = labelStyle,
                color = labelColor,
                pulseColor = indicatorColor,
                pulse = pulseLabel,
                onLongPress = if (enabled && !isLearning) onLongPress else null,
            )
        }

        if (caption != null) {
            Text(
                text = caption,
                style = labelStyle.copy(fontSize = 9.sp).proportional(),
                color = labelColor.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }

        if (valueFormatter != null) {
            KnobValueText(
                dragState = dragState,
                onValueChange = onValueChange,
                range = range,
                progressColor = progressColor,
                valueFormatter = valueFormatter,
                requireVerticalSlop = requireVerticalSlop,
                locked = locked,
            )
        }
    }
}

@Preview(name = "Mode pulse (Mode One on the Pulsar COMPLEXITY label)")
@Composable
private fun RotaryKnobPulseLabelPreview() {
    OrpheusTheme {
        RotaryKnob(
            label = "COMPLEXITY",
            value = 0.3f,
            onValueChange = {},
            size = 48.dp,
            progressColor = OrpheusColors.cosmicPurple,
            valueFormatter = null,
            pulseLabel = true,
        )
    }
}

@Preview(name = "Locked (DX patch) and captioned")
@Composable
private fun RotaryKnobLockedPreview() {
    OrpheusTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RotaryKnob(
                value = 0.02f,
                onValueChange = {},
                label = "E piano 1",
                caption = "\u2261",
                size = 28.dp,
                progressColor = OrpheusColors.warmGlow,
                locked = true,
                onLockedClick = {},
                valueFormatter = { "#1" },
            )
            RotaryKnob(
                value = 0.6f,
                onValueChange = {},
                label = "detune",
                caption = "\u03C8",
                longLabel = "duo detune, \u00B125 cents",
                size = 28.dp,
                progressColor = OrpheusColors.neonMagenta,
            )
        }
    }
}

// ==================== TV FOCUS PREVIEWS ====================
// previewFocused/previewAdjusting force the visuals for inspection; every real call site
// leaves them null and gets genuine D-pad/keyboard focus behavior.

@Preview(name = "TV — Focused (not adjusting)")
@Composable
private fun RotaryKnobTvFocusedPreview() {
    OrpheusTheme {
        CompositionLocalProvider(LocalTvFocusChrome provides true) {
            RotaryKnob(
                label = "ENERGY",
                value = 0.6f,
                onValueChange = {},
                progressColor = OrpheusColors.cosmicPurple,
                previewFocused = true,
            )
        }
    }
}

@Preview(name = "TV — Adjusting (pulsing glow)")
@Composable
private fun RotaryKnobTvAdjustingPreview() {
    OrpheusTheme {
        CompositionLocalProvider(LocalTvFocusChrome provides true) {
            RotaryKnob(
                label = "ENERGY",
                value = 0.6f,
                onValueChange = {},
                progressColor = OrpheusColors.cosmicPurple,
                previewFocused = true,
                previewAdjusting = true,
            )
        }
    }
}

@Preview(name = "Non-TV — Focused (unchanged ring)")
@Composable
private fun RotaryKnobNonTvFocusedPreview() {
    OrpheusTheme {
        RotaryKnob(
            label = "ENERGY",
            value = 0.6f,
            onValueChange = {},
            progressColor = OrpheusColors.cosmicPurple,
            previewFocused = true,
        )
    }
}
