package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.pulsar.PulsarPanel
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.EnumDropdown
import java.io.File
import kotlin.test.Test

/**
 * Renders the dropdown chips on their own at 3x so a padding change can be judged at a
 * readable size. Inside a full panel render these are a few pixels tall and any change to
 * their insets is invisible.
 *
 * Uses the same labels the Pulsar and Bass panels pass so a long real chip ("Rusted Coast")
 * and the narrowest ("D") are both represented. The true widest live labels are 13 chars
 * ("Voltage Strut", "Space & Drums"); widthIn(max = 120.dp) is what actually bounds them.
 */
class DropdownRenderHarness {

    @Test
    fun renderDropdownChips() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val scene = ImageComposeScene(900, 180, Density(3f)) {
            OrpheusTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF14141F))
                        .padding(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnumDropdown(
                            modifier = Modifier.widthIn(max = 120.dp),
                            label = "VIBE",
                            selectedDisplay = "Rusted Coast",
                            entries = listOf("Rusted Coast"),
                            displayName = { it },
                            onSelected = {},
                            color = OrpheusColors.cosmicPurple,
                            menuWidth = 200.dp,
                        )
                        EnumDropdown(
                            label = "ROOT",
                            selectedDisplay = "D",
                            entries = listOf("D"),
                            displayName = { it },
                            onSelected = {},
                            color = OrpheusColors.cosmicPurple,
                            menuWidth = 112.dp,
                        )
                        EnumDropdown(
                            label = "SCALE",
                            selectedDisplay = "Dorian",
                            entries = listOf("Dorian"),
                            displayName = { it },
                            onSelected = {},
                            color = OrpheusColors.cosmicPurple,
                            menuWidth = 140.dp,
                        )
                    }
                }
            }
        }
        try {
            File(outDir, "dropdowns.png").writeBytes(scene.render().encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }

    /**
     * The chips in their real neighbourhood. ENV sits in the same row and is a hand-rolled
     * chip rather than an EnumDropdown, so this is what catches it falling out of step with
     * the dropdowns' insets.
     */
    @Test
    fun renderPulsarSelectorRow() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val scene = ImageComposeScene(1500, 1260, Density(3f)) {
            OrpheusTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF14141F))
                        .padding(8.dp),
                ) {
                    PulsarPanel(
                        pulsar = PulsarViewModel.previewFeature(),
                        isExpanded = true,
                        showCollapsedHeader = false,
                    )
                }
            }
        }
        try {
            File(outDir, "pulsar-panel.png").writeBytes(scene.render().encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }
}
