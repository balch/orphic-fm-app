package org.balch.orpheus.djapp.vibepresets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.features.pulsar.PulsarPanel
import org.balch.orpheus.features.pulsar.PulsarSelectors
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/** Pulsar's transport row with the PRESETS toggle off and on. ./gradlew :apps:djapp:shared:jvmTest --tests '*PresetsToggleRenderHarness*' --rerun -PrenderHarness */
class PresetsToggleRenderHarness {
    @Test
    fun renderPresetsToggle() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        // 355dp is the phone width in the user's report, where the row nearly fills the panel.
        listOf(false, true).forEach { shown ->
            runCatching {
                val feature = object : VibePresetsFeature {
                    override val stateFlow = MutableStateFlow(VibePresetsUiState(available = true, barShown = shown))
                    override val actions = VibePresetsActions.EMPTY
                }
                val scene = ImageComposeScene(710, 520, Density(2f)) {
                    OrpheusTheme {
                        Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                            PulsarPanel(
                                pulsar = PulsarViewModel.previewFeature(),
                                isExpanded = true,
                                showCollapsedHeader = false,
                                fillHeight = false,
                                selectors = PulsarSelectors.None,
                                transportLead = rememberPresetsTransportLead(feature),
                            )
                        }
                    }
                }
                try {
                    File(outDir, "presets-toggle-${if (shown) "on" else "off"}.png").writeBytes(scene.render().encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure { println("[render-harness] presets toggle skipped: $it") }
        }
    }

    @Test
    fun renderPresetBar() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val presets = listOf(PresetRow(1, "Quiet Night", 0L), PresetRow(2, "Deep Bass", 0L), PresetRow(3, "Loud Drums", 0L))
        mapOf(
            "preset" to VibePresetsUiState(available = true, barShown = true, presets = presets, selectedId = 2, changed = true),
            "original" to VibePresetsUiState(available = true, barShown = true, presets = presets, changed = true),
            "empty" to VibePresetsUiState(available = true, barShown = true),
        ).forEach { (name, state) ->
            runCatching {
                val feature = object : VibePresetsFeature {
                    override val stateFlow = MutableStateFlow(state)
                    override val actions = VibePresetsActions.EMPTY
                }
                val scene = ImageComposeScene(720, 140, Density(2f)) {
                    OrpheusTheme {
                        Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                            VibePresetBar(feature = feature, onManage = {})
                        }
                    }
                }
                try {
                    File(outDir, "preset-bar-$name.png").writeBytes(scene.render().encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure { println("[render-harness] preset bar skipped: $it") }
        }
    }
}
