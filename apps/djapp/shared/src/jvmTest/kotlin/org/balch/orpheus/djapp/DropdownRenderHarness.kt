package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.pulsar.PulsarPanel
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.DropdownCycleMinWidth
import org.balch.orpheus.ui.widgets.DropdownValueText
import org.balch.orpheus.ui.widgets.EnumDropdown
import org.balch.orpheus.ui.widgets.LabeledDropdown
import java.io.File
import kotlin.test.Test

/**
 * Renders the dropdowns on their own at 3x so an inset change can be judged at a readable size.
 * Inside a full panel render they are a few pixels tall and any such change is invisible.
 *
 * Uses the labels Pulsar and Bass really pass, so a long value ("Rusted Coast") and the
 * narrowest ("D") are both covered. Note it is not the widest the catalog carries — four live
 * vibes are 13 characters ("Voltage Strut", "Space & Drums", "Techno Wobble", "Lost In Space")
 * against this one's 12, so a row that fits here can still be one character short in the app.
 */
class DropdownRenderHarness {

    @Test
    fun renderDropdownChips() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val scene = ImageComposeScene(1700, 180, Density(3f)) {
            OrpheusTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF14141F))
                        .padding(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnumDropdown(
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
                        // The two menu-less ones, beside the menu-backed ones they have to match.
                        // Both were hand-rolled, and ENDING had drifted to half the insets.
                        LabeledDropdown(
                            label = "ENV",
                            onClick = {},
                            minWidth = DropdownCycleMinWidth,
                        ) {
                            DropdownValueText(
                                text = "AD",
                                color = OrpheusColors.cosmicPurple,
                            )
                        }
                        LabeledDropdown(
                            label = "ENDING",
                            onClick = {},
                            minWidth = DropdownCycleMinWidth,
                        ) {
                            DropdownValueText(
                                text = "PLAYS",
                                color = OrpheusColors.cosmicPurple,
                            )
                        }
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
     * The real panel. ENV and ENDING are not EnumDropdowns, so this is what catches either one
     * falling out of step with the rest of the row.
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

    /**
     * The panel squeezed to a phone-width column, once per vibe-name length. 1100px at 3x is
     * roughly 366dp, near the narrowest column the panel ever gets.
     *
     * Both are needed. A short name has to leave the row on ONE line and only a long one may
     * push onto a second, so either render alone is satisfied by always-wrap or never-wrap.
     */
    @Test
    fun renderPulsarSelectorRowNarrow() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        // previewFeature's catalog is a single "Preview" vibe. Rename a copy to the longest name
        // a real catalog carries.
        val previewVibe = PulsarViewModel.previewFeature().vibeList.first()
        val cases = listOf(
            "pulsar-panel-narrow-short" to previewVibe,
            "pulsar-panel-narrow-long" to previewVibe.copy(name = "Kaleidoscope Drift"),
        )
        for ((name, vibe) in cases) {
            val scene = ImageComposeScene(1100, 1400, Density(3f)) {
                OrpheusTheme {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF14141F))
                            .padding(8.dp),
                    ) {
                        PulsarPanel(
                            pulsar = PulsarViewModel.previewFeature(PulsarUiState(vibe = vibe)),
                            isExpanded = true,
                            showCollapsedHeader = false,
                        )
                    }
                }
            }
            try {
                File(outDir, "$name.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }
    }
}
