package org.balch.orpheus.ui.widgets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A momentary push button that generates a pulse.
 * The longer it's held, the stronger the pulse visual becomes.
 * Supports wobble tracking for finger movement modulation.
 *
 * @param onPulseStart Called when the button is pressed down, with initial pointer position
 * @param onPulseEnd Called when the button is released
 * @param onWobbleMove Called continuously while pressed as finger moves (x, y coordinates)
 * @param isLearnMode If true, clicking selects this for MIDI learning
 * @param isLearning If true, this button is currently selected for learning
 * @param onLearnSelect Called when clicked in learn mode to select for MIDI mapping
 */
@Composable
@Preview
fun PulseButtonPreview() {
    OrpheusTheme {
        PulseButton(onPulseStart = {}, onPulseEnd = {})
    }
}

@Composable
fun PulseButton(
    onPulseStart: () -> Unit,
    onPulseEnd: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "PULSE",
    size: Dp = 48.dp,
    activeColor: Color = OrpheusColors.neonMagenta,
    isActive: Boolean = false,  // External trigger active (MIDI/keyboard)
    isLearnMode: Boolean = false,
    isLearning: Boolean = false,
    onLearnSelect: () -> Unit = {},
    onPulseStartWithPosition: ((Float, Float) -> Unit)? = null,  // Enhanced start with position
    onWobbleMove: ((Float, Float) -> Unit)? = null  // Called during drag for wobble tracking
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    
    // Track pressed state manually for wobble support
    val isPressedState = remember { mutableStateOf(false) }
    val isPressed = isPressedState.value

    // Animate glow size based on press state, active state, or learning state
    val showGlow = isPressed || isActive || isLearning
    val glowAlpha by animateFloatAsState(targetValue = if (showGlow) 0.8f else 0f)
    val glowRadius by animateFloatAsState(targetValue = if (showGlow) 10f else 0f)

    // Use magenta for learning state, activeColor for normal active
    val glowColor = if (isLearning) OrpheusColors.neonMagenta else activeColor

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .pointerInput(isLearnMode) {
                    awaitEachGesture {
                        // Wait for initial press
                        val down = awaitFirstDown()
                        val startPosition = down.position
                        
                        if (isLearnMode) {
                            // In learn mode, just select for learning
                            onLearnSelect()
                        } else {
                            // Normal pulse operation with wobble tracking
                            isPressedState.value = true
                            
                            // Emit press interaction for visual feedback
                            val press = PressInteraction.Press(startPosition)
                            scope.launch { interactionSource.emit(press) }
                            
                            // Call start callbacks
                            onPulseStart()
                            onPulseStartWithPosition?.invoke(startPosition.x, startPosition.y)
                            
                            // Track movement while pressed (for wobble)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Move) {
                                        // Report movement for wobble calculation
                                        event.changes.firstOrNull()?.let { change ->
                                            onWobbleMove?.invoke(change.position.x, change.position.y)
                                        }
                                    }
                                    
                                    // Check if all pointers are up
                                    if (event.changes.all { !it.pressed }) {
                                        break
                                    }
                                }
                            } finally {
                                // Release
                                isPressedState.value = false
                                scope.launch { interactionSource.emit(PressInteraction.Release(press)) }
                                onPulseEnd()
                            }
                        }
                    }
                }
                .drawBehind {
                    val radius = size.toPx() / 2

                    // 1. Touch/Learn Glow (Underneath)
                    if (glowAlpha > 0f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    glowColor.copy(alpha = glowAlpha),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = radius + glowRadius.dp.toPx() + 10f
                            ),
                            radius = radius + glowRadius.dp.toPx() + 10f
                        )
                    }

                    // Learning outline
                    if (isLearning) {
                        drawCircle(
                            color = OrpheusColors.neonMagenta,
                            radius = radius + 3f,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                        )
                    }

                    // 2. Metal Contact Body (Sensor)
                    // Silver/Steel look
                    val metalGradient = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFE0E0E0), // Bright Silver
                            Color(0xFFA0A0A0), // Standard Steel
                            Color(0xFF505050)  // Dark Shadow
                        ),
                        start = Offset(center.x - radius, center.y - radius),
                        end = Offset(center.x + radius, center.y + radius)
                    )

                    drawCircle(
                        brush = metalGradient,
                        radius = radius
                    )

                    // 3. Inner Detail (Concentric rings to look like machined metal)
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.2f),
                        radius = radius * 0.8f,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.2f),
                        radius = radius * 0.5f,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                    )

                    // 4. Pressed/Active Overlay (Darkens when touched or active)
                    if ((isPressed || isActive) && !isLearnMode) {
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.3f),
                            radius = radius
                        )
                    }
                }
        )
    }
}
