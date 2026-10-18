package org.balch.orpheus.ui.panels.slots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/**
 * Renders the recents strip, handle, rail and search overlay to PNGs for visual inspection.
 * Asserts nothing: a headless machine without skia natives must not fail the build.
 *
 * ./gradlew :apps:orpheus:shared:jvmTest --tests '*NavChromeRenderHarness*' --rerun
 */
class NavChromeRenderHarness {

    private val entries = PanelNavEntries.associateBy { it.id }

    private val colors: Map<PanelId, Color> = mapOf(
        PanelId.PULSAR to OrpheusColors.cosmicPurple,
        PanelId.BEATS to OrpheusColors.neonOrange,
        PanelId.DRUMS to OrpheusColors.neonMagenta,
        PanelId.BASS to OrpheusColors.electricBlue,
        PanelId.DJ to OrpheusColors.neonCyan,
        PanelId.SPEECH to OrpheusColors.speechRose,
        PanelId.LFO to OrpheusColors.synthGreen,
        PanelId.TIDES to OrpheusColors.neonCyan,
        PanelId.EVO to OrpheusColors.evoGold,
        PanelId.FLUX to OrpheusColors.neonMagenta,
        PanelId.FLUX_TRIGGERS to OrpheusColors.synthPink,
        PanelId.DISTORTION to OrpheusColors.warmGlow,
        PanelId.DELAY to OrpheusColors.electricBlue,
        PanelId.REVERB to OrpheusColors.cosmicPurple,
        PanelId.HORN to OrpheusColors.neonOrange,
        PanelId.RESONATOR to OrpheusColors.krakenTeal,
        PanelId.GRAINS to OrpheusColors.grainsRed,
        PanelId.WARPS to OrpheusColors.synthPink,
        PanelId.LOOPER to OrpheusColors.recordRed,
        PanelId.PRESETS to OrpheusColors.patchOrange,
        PanelId.MIDI to OrpheusColors.steelGrey,
        PanelId.VIZ to OrpheusColors.vizGreen,
        PanelId.CODE to OrpheusColors.aiRepl,
        PanelId.AI to OrpheusColors.aiChat,
        PanelId.VIBE_CREATE to OrpheusColors.neonMagenta,
        PanelId.ASL_MAESTRO to OrpheusColors.aiDrone,
        PanelId.TIMER to OrpheusColors.ninersGold,
        PanelId.RECORD to OrpheusColors.recordRed,
    )

    private val descriptions = mapOf(
        PanelId.PULSAR to "Generative beat machine",
        PanelId.DELAY to "Echo, feedback and time",
        PanelId.REVERB to "Room and tail",
        PanelId.DISTORTION to "Volume and drive",
        PanelId.TIDES to "Slope and shape modulation",
        PanelId.LFO to "Low frequency oscillators",
    )

    private val candidates = PanelNavEntries.map { entry ->
        val label = entry.id.id.replace('_', ' ').replaceFirstChar { it.uppercase() }
        SearchCandidate(entry, label, descriptions[entry.id] ?: "")
    }

    private val outDir get() = File("build/orpheus-nav-render").apply { mkdirs() }

    private fun render(name: String, widthDp: Int, heightDp: Int, content: @Composable () -> Unit) {
        runCatching {
            val scene = ImageComposeScene(widthDp, heightDp, Density(1f)) {
                OrpheusTheme { Box(Modifier.fillMaxSize().background(Color(0xFF0D0D1A))) { content() } }
            }
            try {
                scene.render()
                File(outDir, "$name.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure {
            println("[nav-render] $name skipped: $it")
        }
    }

    @Test
    fun renderStrips() {
        val slots = PanelSlots(
            open = listOf(OpenPanel(PanelId.PULSAR, 2), OpenPanel(PanelId.DELAY, 1)),
            pinned = setOf(PanelId.PULSAR),
            recents = listOf(PanelId.REVERB, PanelId.LFO, PanelId.TIDES, PanelId.HORN),
            lastUsed = listOf(PanelId.DELAY, PanelId.PULSAR),
            capacity = 4,
        )
        render("strip-open", 850, 60) {
            Column(Modifier.padding(top = 8.dp)) {
                RecentsStrip(entries, colors, slots, railVisible = true, {}, {}, {}, {})
            }
        }
        render("strip-overflow", 360, 60) {
            Column(Modifier.padding(top = 8.dp)) {
                RecentsStrip(entries, colors, slots, railVisible = false, {}, {}, {}, {})
            }
        }
        render("handle", 160, 52) {
            Box(Modifier.padding(8.dp)) { RecentsHandle({}, {}, {}) }
        }
    }

    @Test
    fun renderRail() {
        render("rail", 120, 400) {
            Row(Modifier.fillMaxHeight()) {
                PanelRail(
                    entries = PanelNavEntries,
                    colors = colors,
                    openIds = setOf(PanelId.PULSAR, PanelId.DELAY),
                    onOpen = {},
                )
            }
        }
    }

    @Test
    fun renderSearch() {
        render("search-blank", 700, 1560) {
            PanelSearchOverlay(candidates, colors, autoFocus = false, onPick = {}, onDismiss = {})
        }
        render("search-de", 700, 440) {
            PanelSearchOverlay(
                candidates, colors, autoFocus = false, onPick = {}, onDismiss = {}, initialQuery = "de",
            )
        }
    }
}
