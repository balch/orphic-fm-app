package org.balch.songe.ui.widgets

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import org.balch.songe.ui.theme.SongeColors
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated plasma/lava lamp background that creates an organic, flowing effect.
 * The animation is subtle to avoid distracting from the synthesizer controls.
 */
@Preview(widthDp = 1080, heightDp = 720)
@Composable
fun PlasmaBackground(
    modifier: Modifier = Modifier,
    baseColor1: Color = SongeColors.deepPurple,
    baseColor2: Color = SongeColors.darkVoid,
    accentColor1: Color = SongeColors.neonMagenta.copy(alpha = 0.15f),
    accentColor2: Color = SongeColors.neonCyan.copy(alpha = 0.1f),
    accentColor3: Color = SongeColors.electricBlue.copy(alpha = 0.12f)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "plasma")
    
    // Slow, organic movement phases
    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase1"
    )
    
    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
    )
    
    val phase3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 25000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase3"
    )
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        
        // Base gradient
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(baseColor1, baseColor2)
            )
        )
        
        // Animated blob 1 (magenta)
        val blob1X = width * 0.3f + cos(Math.toRadians(phase1.toDouble())).toFloat() * width * 0.2f
        val blob1Y = height * 0.4f + sin(Math.toRadians(phase1.toDouble() * 0.7)).toFloat() * height * 0.15f
        
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accentColor1, Color.Transparent),
                center = Offset(blob1X, blob1Y),
                radius = width * 0.4f
            ),
            radius = width * 0.4f,
            center = Offset(blob1X, blob1Y)
        )
        
        // Animated blob 2 (cyan)
        val blob2X = width * 0.7f + cos(Math.toRadians(phase2.toDouble() + 120)).toFloat() * width * 0.15f
        val blob2Y = height * 0.6f + sin(Math.toRadians(phase2.toDouble() * 0.8)).toFloat() * height * 0.2f
        
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accentColor2, Color.Transparent),
                center = Offset(blob2X, blob2Y),
                radius = width * 0.35f
            ),
            radius = width * 0.35f,
            center = Offset(blob2X, blob2Y)
        )
        
        // Animated blob 3 (blue)
        val blob3X = width * 0.5f + cos(Math.toRadians(phase3.toDouble() + 240)).toFloat() * width * 0.25f
        val blob3Y = height * 0.3f + sin(Math.toRadians(phase3.toDouble() * 0.6)).toFloat() * height * 0.1f
        
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accentColor3, Color.Transparent),
                center = Offset(blob3X, blob3Y),
                radius = width * 0.3f
            ),
            radius = width * 0.3f,
            center = Offset(blob3X, blob3Y)
        )
    }
}
