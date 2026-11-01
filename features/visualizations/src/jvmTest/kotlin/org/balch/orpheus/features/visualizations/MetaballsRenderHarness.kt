package org.balch.orpheus.features.visualizations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.balch.orpheus.features.visualizations.viz.shader.MetaballsCanvas
import org.balch.orpheus.features.visualizations.viz.shader.MetaballsConfig
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Blob
import java.io.File
import kotlin.test.Test

/**
 * Renders the Shader Lamp metaballs to build/metaballs-render/ for eyeballing the shading.
 * Asserts nothing; skipped unless -PrenderHarness is set.
 */
class MetaballsRenderHarness {

    private val blobs = listOf(
        Blob(id = 0, x = 0.35f, y = 0.62f, radius = 0.22f, color = OrpheusColors.neonMagenta, energy = 1f),
        Blob(id = 1, x = 0.55f, y = 0.55f, radius = 0.20f, color = OrpheusColors.neonCyan, energy = 1f),
        Blob(id = 2, x = 0.48f, y = 0.33f, radius = 0.18f, color = OrpheusColors.electricBlue, energy = 0.9f),
        Blob(id = 3, x = 0.72f, y = 0.30f, radius = 0.14f, color = OrpheusColors.synthGreen, energy = 0.8f),
        Blob(id = 4, x = 0.25f, y = 0.25f, radius = 0.12f, color = OrpheusColors.neonMagenta, energy = 0.7f),
    )

    @Test
    fun renderShaderLamp() {
        val dir = File("build/metaballs-render").apply { mkdirs() }
        runCatching {
            val scene = ImageComposeScene(720, 720, Density(2f), content = {
                Box(Modifier.fillMaxSize().background(Color(0xFF07060C))) {
                    MetaballsCanvas(
                        modifier = Modifier.fillMaxSize(),
                        blobs = blobs,
                        config = MetaballsConfig(maxBalls = blobs.size, threshold = 1f, glowIntensity = 0.4f),
                        lfoModulation = 0f,
                        masterEnergy = 0.6f,
                        time = 1.5f,
                    )
                }
            })
            try {
                File(dir, "shader-lamp.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { println("MetaballsRenderHarness skipped: $it") }
    }
}
