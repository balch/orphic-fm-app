package org.balch.orpheus.ui.widgets.patch

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.foundation.focusable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

private const val TURN_DEGREES = 45f
private const val CLICK_WOBBLE_DEGREES = 15f

/**
 * A slotted pot between a voice row's A and B jacks: a horizontal bar bridges them, vertical splits.
 * A click turns it, and so does a drag of more than 45 degrees around its centre. Focused, Enter
 * or Space turns it. A press released off the switch changes nothing.
 */
@Composable
fun RotaryBridgeSwitch(
    bridged: Boolean,
    onBridgedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = PatchColors.input,
) {
    val angle by animateFloatAsState(if (bridged) 0f else 90f, tween(160))
    val latestBridged by rememberUpdatedState(bridged)
    val latestOnChange by rememberUpdatedState(onBridgedChange)
    var focused by remember { mutableStateOf(false) }
    Canvas(
        modifier
            .size(size)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val turn = event.key == Key.Enter || event.key == Key.NumPadEnter ||
                    event.key == Key.Spacebar || event.key == Key.DirectionCenter
                if (turn && event.type == KeyEventType.KeyDown) latestOnChange(!latestBridged)
                turn
            }
            .focusable()
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(bridged)
                stateDescription = if (bridged) "bridged" else "split"
                onClick { latestOnChange(!latestBridged); true }
            }
            .pointerInput(Unit) {
                val inner = this.size.width * 0.2f
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Mouse: only the primary button turns it.
                    if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                    down.consume()
                    val centre = Offset(this.size.width / 2f, this.size.height / 2f)
                    fun degreesAt(p: Offset) = (atan2(p.y - centre.y, p.x - centre.x) * 180f / PI.toFloat())
                    // The angle starts once the pointer reaches the rim; a press at the dead centre has none.
                    var last: Float? = down.position.takeIf { (it - centre).getDistance() >= inner }?.let(::degreesAt)
                    var turned = 0f
                    var widest = 0f
                    var toggled = false
                    var releasedInside = true
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        // Near the centre the angle swings wildly, so only the rim turns the pot.
                        if ((change.position - centre).getDistance() >= inner) {
                            val now = degreesAt(change.position)
                            var step = now - (last ?: now)
                            if (step > 180f) step -= 360f
                            if (step < -180f) step += 360f
                            turned += step
                            last = now
                            widest = maxOf(widest, abs(turned))
                        }
                        change.consume()
                        if (!toggled && abs(turned) > TURN_DEGREES) {
                            toggled = true
                            latestOnChange(!latestBridged)
                        }
                        if (!change.pressed) {
                            val p = change.position
                            releasedInside = p.x in 0f..this.size.width.toFloat() && p.y in 0f..this.size.height.toFloat()
                            break
                        }
                    }
                    if (!toggled && releasedInside && widest < CLICK_WOBBLE_DEGREES) latestOnChange(!latestBridged)
                }
            },
    ) {
        val r = this.size.minDimension / 2f
        if (focused) drawCircle(color.copy(alpha = 0.35f), r + 3.dp.toPx())
        drawCircle(OrpheusColors.pickerField, r)
        drawCircle(color, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        val barLength = r * 1.6f
        val barThickness = r * 0.42f
        rotate(angle) {
            drawRoundRect(
                color = color.copy(alpha = if (bridged) 1f else 0.65f),
                topLeft = Offset(center.x - barLength / 2f, center.y - barThickness / 2f),
                size = Size(barLength, barThickness),
                cornerRadius = CornerRadius(barThickness / 2f),
            )
        }
    }
}
