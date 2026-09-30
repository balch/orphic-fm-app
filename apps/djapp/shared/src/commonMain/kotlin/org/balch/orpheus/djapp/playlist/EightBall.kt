package org.balch.orpheus.djapp.playlist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp

/** What the 8-ball shows: its ∞ (where a real one has its 8), or the die in its window with a phrase. */
internal sealed interface EightBallFace {
    data object Eight : EightBallFace
    data class Die(val phrase: String) : EightBallFace
}

private val BallSheen = Color(0xFF6A6A78)
private val BallBody = Color(0xFF1B1B22)
private val WindowTop = Color(0xFF0B1440)
private val WindowDeep = Color(0xFF02051A)
private val DieBlue = Color(0xFF2F4FD8)
private val DieText = Color(0xFFDFE8FF)

/**
 * A magic 8-ball lit from the upper left, like the dome it stands in for. [faceScale] (read in the
 * face's layer) shrinks the face away and back, which is how the reveal turns the ball over.
 */
@Composable
internal fun EightBall(
    face: EightBallFace,
    diameter: Dp,
    modifier: Modifier = Modifier,
    faceScale: () -> Float = { 1f },
) {
    Box(
        modifier.size(diameter).drawBehind {
            val r = size.minDimension / 2
            drawCircle(
                Brush.radialGradient(
                    0f to BallSheen, 0.38f to BallBody, 1f to Color.Black,
                    center = Offset(size.width * 0.32f, size.height * 0.28f), radius = r * 1.5f,
                ),
                r,
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        val faceModifier = Modifier.graphicsLayer {
            val s = faceScale()
            scaleX = s
            scaleY = s
        }
        when (face) {
            EightBallFace.Eight -> EightFace(diameter, faceModifier)
            is EightBallFace.Die -> DieFace(face.phrase, diameter, faceModifier)
        }
    }
}

@Composable
private fun EightFace(diameter: Dp, modifier: Modifier) {
    // The ∞ glyph is only letter-high, so it takes a bigger size than an 8 would to fill the circle.
    val fontSize = with(LocalDensity.current) { (diameter * 0.36f).toSp() }
    Box(modifier.size(diameter * 0.42f).background(Color(0xFFF2F2F2), CircleShape), contentAlignment = Alignment.Center) {
        // Infinity where a real ball has its 8: the playlist's rotation never ends.
        Text("∞", color = Color.Black, fontSize = fontSize, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun DieFace(phrase: String, diameter: Dp, modifier: Modifier) {
    val window = diameter * 0.62f
    val fontSize = with(LocalDensity.current) { (diameter * 0.047f).toSp() }
    Box(
        modifier.size(window).background(Brush.radialGradient(0f to WindowTop, 1f to WindowDeep), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // The die's face: a triangle pointing down, the phrase inside its wide top.
        Canvas(Modifier.size(window * 0.9f)) {
            val path = Path().apply {
                moveTo(0f, size.height * 0.12f)
                lineTo(size.width, size.height * 0.12f)
                lineTo(size.width / 2, size.height * 0.92f)
                close()
            }
            drawPath(path, DieBlue)
        }
        Text(
            text = phrase.uppercase(),
            color = DieText,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = fontSize * 1.05f,
            maxLines = 3,
            modifier = Modifier.width(window * 0.56f).padding(bottom = window * 0.16f),
        )
    }
}
