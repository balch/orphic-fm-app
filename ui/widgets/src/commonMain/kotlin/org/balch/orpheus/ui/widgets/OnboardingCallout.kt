package org.balch.orpheus.ui.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors

const val OnboardingCalloutFadeMillis = 400

private val CalloutCorner = 8.dp
private val CalloutShape = RoundedCornerShape(CalloutCorner)
private val CalloutText = Color(0xFF06343C)
private val CaretWidth = 14.dp
private val CaretDepth = 7.dp

/** Which way a callout's caret points, out of its bottom edge or its left edge. */
enum class CalloutCaret { Down, Left }

/**
 * A hint for a new user, fading in as it arrives and out when [visible] goes false. A [caret] points
 * at what it means: [caretAlong] says how far along that edge, in pixels, or null for the middle.
 */
@Composable
fun OnboardingCallout(
    text: String,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    caret: CalloutCaret? = null,
    caretAlong: () -> Float? = { null },
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        alpha.animateTo(if (visible) 1f else 0f, tween(OnboardingCalloutFadeMillis))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = CalloutText,
        textAlign = TextAlign.Center,
        modifier = modifier
            .graphicsLayer { this.alpha = alpha.value }
            .absolutePadding(
                left = if (caret == CalloutCaret.Left) CaretDepth else 0.dp,
                bottom = if (caret == CalloutCaret.Down) CaretDepth else 0.dp,
            )
            .drawBehind { if (caret != null) drawCaret(caret, caretAlong()) }
            .background(OrpheusColors.neonCyan, CalloutShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Where a caret's centre sits along an edge of [length], clear of the bubble's rounded corners. */
internal fun caretCentre(along: Float?, length: Float, inset: Float): Float =
    if (length <= inset * 2) length / 2 else (along ?: (length / 2)).coerceIn(inset, length - inset)

private fun DrawScope.drawCaret(caret: CalloutCaret, along: Float?) {
    val halfWidth = CaretWidth.toPx() / 2
    val depth = CaretDepth.toPx()
    val inset = CalloutCorner.toPx() + halfWidth
    val path = Path()
    // Each starts a pixel inside the bubble, so no seam shows where they meet.
    when (caret) {
        CalloutCaret.Down -> {
            val x = caretCentre(along, size.width, inset)
            path.moveTo(x - halfWidth, size.height - 1f)
            path.lineTo(x, size.height + depth)
            path.lineTo(x + halfWidth, size.height - 1f)
        }
        CalloutCaret.Left -> {
            val y = caretCentre(along, size.height, inset)
            path.moveTo(1f, y - halfWidth)
            path.lineTo(-depth, y)
            path.lineTo(1f, y + halfWidth)
        }
    }
    path.close()
    drawPath(path, OrpheusColors.neonCyan)
}
