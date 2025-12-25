package org.balch.orpheus.features.viz.shader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.balch.orpheus.features.viz.Blob

/**
 * Platform-specific metaballs renderer.
 * 
 * Each platform implements this using available shader APIs:
 * - JVM/Desktop: Skiko RuntimeShaderBuilder
 * - Android 13+: AGSL RuntimeShader
 * - Android <13: Falls back to Canvas
 * - WASM: Skiko shaders
 */
expect class MetaballsRenderer() {
    /**
     * Whether shader-based rendering is supported on this platform.
     */
    fun isSupported(): Boolean
    
    /**
     * Dispose of shader resources.
     */
    fun dispose()
}

/**
 * Composable that renders metaballs using platform-specific shaders.
 * Falls back to Canvas-based rendering if shaders aren't supported.
 * 
 * @param modifier Modifier for the canvas
 * @param blobs List of blobs to render as metaballs
 * @param config Metaballs configuration
 * @param lfoModulation LFO value for color effects (-1 to 1)
 * @param masterEnergy Overall energy level (0 to 1)
 * @param time Animation time in seconds
 */
@Composable
expect fun MetaballsCanvas(
    modifier: Modifier,
    blobs: List<Blob>,
    config: MetaballsConfig,
    lfoModulation: Float,
    masterEnergy: Float,
    time: Float
)

/**
 * Converts a Blob to MetaballData for shader consumption.
 */
fun Blob.toMetaballData(): MetaballData = MetaballData(
    x = x,
    y = y,
    radius = radius,
    color = color.copy(alpha = alpha),
    energy = energy
)
