package org.balch.orpheus.ui.widgets

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * State holder for the AI control-highlight system.
 * When the agent wants to visually point at specific controls, it populates this set.
 */
data class HighlightState(
    val highlightedControlIds: Set<String> = emptySet()
) {
    fun isHighlighted(controlId: String): Boolean = controlId in highlightedControlIds
}

/**
 * CompositionLocal for providing highlight state down the composition tree.
 */
val LocalHighlightState = compositionLocalOf { HighlightState() }

/**
 * Provider composable that makes highlight state available to all children.
 */
@Composable
fun HighlightProvider(
    highlightedControlIds: Set<String>,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalHighlightState provides HighlightState(highlightedControlIds)
    ) {
        content()
    }
}

/** Gold/amber color used for AI highlight glow. */
private val HighlightGold = Color(0xFFFFB300)

/**
 * Modifier extension that applies a pulsing gold/amber glow when this control is highlighted.
 * Purely visual -- does not intercept touch events.
 *
 * @param controlId Unique identifier matching the widget's controlId
 */
@Composable
fun Modifier.highlightable(controlId: String): Modifier {
    val highlightState = LocalHighlightState.current
    if (!highlightState.isHighlighted(controlId)) return this

    val transition = rememberInfiniteTransition(label = "highlight_$controlId")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "highlight_alpha_$controlId"
    )

    return this
        .padding(1.dp)
        .drawBehind {
            drawRect(
                color = HighlightGold.copy(alpha = alpha * 0.3f),
                size = size
            )
        }
        .border(
            width = 2.dp,
            color = HighlightGold.copy(alpha = alpha),
            shape = RoundedCornerShape(4.dp)
        )
}

/**
 * Wrapper composable that applies a pulsing gold highlight glow when this control is highlighted.
 * Purely visual -- does not intercept touch events.
 */
@Composable
fun Highlightable(
    controlId: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val highlightState = LocalHighlightState.current
    if (!highlightState.isHighlighted(controlId)) {
        Box(modifier = modifier) { content() }
        return
    }

    val transition = rememberInfiniteTransition(label = "highlight_$controlId")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "highlight_alpha_$controlId"
    )

    Box(
        modifier = modifier
            .padding(1.dp)
            .drawBehind {
                drawRect(
                    color = HighlightGold.copy(alpha = alpha * 0.3f),
                    size = size
                )
            }
            .border(
                width = 2.dp,
                color = HighlightGold.copy(alpha = alpha),
                shape = RoundedCornerShape(4.dp)
            )
    ) {
        content()
    }
}
