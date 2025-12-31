package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.balch.orpheus.features.viz.Blob
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Visualization

/**
 * Background component that renders the active visualization.
 */
@Composable
fun VizBackground(
    modifier: Modifier = Modifier,
    selectedViz: Visualization,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OrpheusColors.darkVoid) // Base background color
    ) {
        // Render the active visualization's content
        selectedViz.Content(modifier = Modifier.fillMaxSize())
    }
}

// Keeping this for LavaLampViz to use as a helper composable
@Composable
fun VizBackground(
    modifier: Modifier = Modifier,
    blobs: List<Blob>,
    lfoModulation: Float,
    masterEnergy: Float
) {
    // Shared drawing logic for Lava Lamp style blobs
    Canvas(modifier = modifier.fillMaxSize()) {
        // Subtle gradient background
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    OrpheusColors.darkVoid,
                    Color(0xFF151020)
                )
            )
        )
        
        // Draw ambient glow based on master energy
        if (masterEnergy > 0.01f) {
            drawAmbientGlow(
                width = size.width,
                height = size.height,
                energy = masterEnergy,
                lfoMod = lfoModulation
            )
        }

        blobs.forEach { blob ->
            drawBlob(blob, size.width, size.height)
        }
        
        // Optional overlay based on master energy
        if (masterEnergy > 0.1f) {
            drawRect(
                color = Color.White.copy(alpha = masterEnergy * 0.05f)
            )
        }
    }
}

/**
 * Draw ambient background glow that pulses with master energy.
 */
private fun DrawScope.drawAmbientGlow(
    width: Float,
    height: Float,
    energy: Float,
    lfoMod: Float
) {
    // LFO shifts the glow position slightly
    val offsetX = width * 0.5f + (lfoMod * width * 0.1f)
    val offsetY = height * 0.6f

    // Pulsing glow based on total energy
    val glowAlpha = (energy * 0.08f).coerceIn(0.02f, 0.1f)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                OrpheusColors.neonMagenta.copy(alpha = glowAlpha),
                OrpheusColors.electricBlue.copy(alpha = glowAlpha * 0.5f),
                Color.Transparent
            ),
            center = Offset(offsetX, offsetY),
            radius = width * 0.6f
        ),
        radius = width * 0.6f,
        center = Offset(offsetX, offsetY),
        blendMode = BlendMode.Plus
    )
}

private fun DrawScope.drawBlob(blob: Blob, width: Float, height: Float) {
    val screenX = blob.x * width
    val screenY = (1f - blob.y) * height // Flip Y so 0 is bottom
    val screenRadius = blob.radius * height // Scale radius relative to height

    // Skip tiny blobs
    if (screenRadius < 2f) return

    // Calculate alpha based on blob state
    val effectiveAlpha = (blob.alpha * blob.energy.coerceIn(0.3f, 1f)).coerceIn(0f, 1f)

    // Brighter core with color saturation
    val brightCore = blob.color.copy(alpha = effectiveAlpha * 0.9f)
    val coreColor = blob.color.copy(alpha = effectiveAlpha * 0.7f)
    // Outer glow (softer edge)
    val glowColor = blob.color.copy(alpha = effectiveAlpha * 0.35f)

    // Draw the blob with radial gradient - more saturated colors
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to brightCore,  // Bright colored core
                0.3f to coreColor,
                0.6f to glowColor,
                1f to Color.Transparent
            ),
            center = Offset(screenX, screenY),
            radius = screenRadius * 1.5f
        ),
        radius = screenRadius * 1.5f,
        center = Offset(screenX, screenY),
        blendMode = BlendMode.Plus
    )

    // Add a subtle colored highlight instead of white (preserves color at top)
    val highlightColor = blob.color.copy(
        red = (blob.color.red + 0.3f).coerceAtMost(1f),
        green = (blob.color.green + 0.3f).coerceAtMost(1f),
        blue = (blob.color.blue + 0.3f).coerceAtMost(1f),
        alpha = effectiveAlpha * 0.15f
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                highlightColor,
                Color.Transparent
            ),
            center = Offset(screenX - screenRadius * 0.2f, screenY - screenRadius * 0.2f),
            radius = screenRadius * 0.4f
        ),
        radius = screenRadius * 0.4f,
        center = Offset(screenX - screenRadius * 0.2f, screenY - screenRadius * 0.2f),
        blendMode = BlendMode.Plus
    )
}
