package org.balch.orpheus.features.viz.shader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-specific Blackhole Sun renderer.
 * 
 * Each platform implements this using available shader APIs:
 * - JVM/Desktop: Skiko RuntimeShaderBuilder
 * - Android 13+: AGSL RuntimeShader
 * - Android <13: Falls back to Canvas
 * - WASM: Skiko shaders
 */
expect class BlackholeSunRenderer() {
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
 * Composable that renders Blackhole Sun using platform-specific shaders.
 * Falls back to Canvas-based rendering if shaders aren't supported.
 * 
 * @param modifier Modifier for the canvas
 * @param emitters List of emitter data for rendering
 * @param config Plasma emitters configuration
 * @param lfoModulation LFO value for effects (-1 to 1)
 * @param masterEnergy Overall energy level (0 to 1)
 * @param orbitSpeed Signed orbit speed (-1 to 1, sign = direction)
 * @param trailLength Trail length (0 to 1)
 * @param time Animation time in seconds
 */
@Composable
expect fun BlackholeSunCanvas(
    modifier: Modifier,
    emitters: List<PlasmaEmitterData>,
    config: PlasmaEmittersConfig,
    lfoModulation: Float,
    masterEnergy: Float,
    orbitSpeed: Float,
    trailLength: Float,
    time: Float
)
