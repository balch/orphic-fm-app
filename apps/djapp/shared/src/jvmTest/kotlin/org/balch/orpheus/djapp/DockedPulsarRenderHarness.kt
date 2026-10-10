package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.balch.orpheus.features.pulsar.PulsarPanel
import org.balch.orpheus.features.pulsar.PulsarSelectors
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/** Pulsar under each [PulsarSelectors] row. ./gradlew :apps:djapp:shared:jvmTest --tests '*DockedPulsarRenderHarness*' --rerun */
class DockedPulsarRenderHarness {
    @Test
    fun renderPulsarSelectorRows() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        PulsarSelectors.entries.forEach { selectors ->
            runCatching {
                val scene = ImageComposeScene(520, 460, Density(1f)) {
                    OrpheusTheme {
                        Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                            PulsarPanel(pulsar = PulsarViewModel.previewFeature(), fillHeight = false, selectors = selectors)
                        }
                    }
                }
                try {
                    File(outDir, "pulsar-selectors-$selectors.png").writeBytes(scene.render().encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure { println("[render-harness] docked pulsar skipped: $it") }
        }
    }
}
