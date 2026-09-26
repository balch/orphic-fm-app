package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.pulsar.MusicPulse
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/** ./gradlew :apps:djapp:shared:jvmTest --tests '*VibeTransportRenderHarness*' --rerun */
class VibeTransportRenderHarness {
    // A mid-wave phase so one frame shows the travelling wave; a paused ring holds the same shape.
    private val wavePhase = 1f

    // A loud kick-led beat, so a held wave keeps the music's colour and height.
    private val loud = MusicPulse(0.9f, FloatArray(8).also { it[0] = 0.9f; it[3] = 0.3f }, 0.25f, 469f)

    /** A paused frame's zip, mid-pass. */
    private val zipMidPass = 900L

    @Test
    fun renderTransportStates() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        runCatching {
            // 560x200dp: six 72dp items with gaps, each a 64dp ring and its label.
            val scene = ImageComposeScene(1120, 400, Density(2f)) {
                OrpheusTheme {
                    Row(
                        Modifier.fillMaxSize().background(Color(0xFF14141F)),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val item: @Composable (Float?, Boolean, Float) -> Unit = { p, paused, drag ->
                            VibeTransportItem(
                                name = "Space & Drift", previousName = "Dog House", nextName = "Stay Asleep",
                                progress = p, paused = paused, onTogglePlayback = {}, onNext = {}, onPrevious = {},
                                modifier = Modifier.width(72.dp), previewDragDp = drag,
                                pulse = { loud }, previewWavePhase = wavePhase, previewZipMs = zipMidPass,
                                previewDomeTilt = domeTiltForDrag(drag),
                            )
                        }
                        item(null, true, 0f)
                        item(0.62f, true, 0f)
                        item(0.62f, false, 0f)
                        // Left peeks the previous vibe, right the next: in this order they slide apart, not into each other.
                        item(0.62f, false, -24f)
                        item(0.62f, false, 24f)
                        item(0.3f, false, 0f)
                    }
                }
            }
            try {
                File(outDir, "transport-states.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { println("[render-harness] transport skipped: $it") }
    }

    /** The bar ring alone at 6x: null, paused 62% (held wave, zip mid-pass), playing 62%, playing 5%, playing 100%. */
    @Test
    fun renderRingCloseUp() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        runCatching {
            val scene = ImageComposeScene(380 * 6, 80 * 6, Density(6f)) {
                OrpheusTheme {
                    Row(
                        Modifier.fillMaxSize().background(Color(0xFF14141F)),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VibeTransportRing(paused = true, progress = null, previewWavePhase = wavePhase)
                        VibeTransportRing(
                            paused = true, progress = 0.62f, pulse = { loud }, previewWavePhase = wavePhase, previewZipMs = zipMidPass,
                        )
                        VibeTransportRing(paused = false, progress = 0.62f, pulse = { loud }, previewWavePhase = wavePhase)
                        VibeTransportRing(paused = false, progress = 0.05f, pulse = { loud }, previewWavePhase = wavePhase)
                        VibeTransportRing(paused = false, progress = 1f, pulse = { loud }, previewWavePhase = wavePhase)
                    }
                }
            }
            try {
                File(outDir, "transport-ring-closeup.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { println("[render-harness] ring close-up skipped: $it") }
    }

    private val tilts = listOf(-1f, -0.5f, 0f, 0.5f, 1f)
    private val dark = SolidColor(Color(0xFF14141F))

    // The viz bars from LandscapePolishRenderHarness: where a purple glyph used to vanish.
    private val busyBars = Brush.horizontalGradient(List(12) { if (it % 2 == 0) Color(0xFFFF2BD6) else Color(0xFF7B3CFF) })

    /**
     * Whole items at bar (64dp, 72dp wide) and rail (64dp, 72dp inside the 80dp rail) size: rows are
     * bar playing, bar paused (zip mid-pass), rail playing, rail paused across tilts −1..1, then
     * peeks: bar right (next), bar left (previous), rail right, rail left.
     */
    @Test
    fun renderDomeTilts() {
        renderDomeGrid("dark", dark)
        renderDomeGrid("busy", busyBars)
    }

    private fun renderDomeGrid(tag: String, background: Brush) = runCatching {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val scene = ImageComposeScene(480 * 3, 520 * 3, Density(3f)) {
            OrpheusTheme {
                Column(
                    Modifier.fillMaxSize().background(background),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val item: @Composable (Boolean, Boolean, Float, Float) -> Unit = { rail, paused, tilt, drag ->
                        VibeTransportItem(
                            name = "Space & Drift", previousName = "Dog House", nextName = "Stay Asleep",
                            progress = 0.62f, paused = paused, onTogglePlayback = {}, onNext = {}, onPrevious = {},
                            modifier = Modifier.width(72.dp), centerLabel = rail, previewDragDp = drag,
                            pulse = { loud }, previewWavePhase = wavePhase, previewZipMs = zipMidPass,
                            ringSize = if (rail) RailRingSize else BarRingSize, previewDomeTilt = tilt,
                        )
                    }
                    listOf(false to false, false to true, true to false, true to true).forEach { (rail, paused) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            tilts.forEach { item(rail, paused, it, 0f) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf(false to 24f, false to -24f, true to 24f, true to -24f).forEach { (rail, drag) ->
                            item(rail, false, domeTiltForDrag(drag), drag)
                        }
                    }
                }
            }
        }
        try {
            File(outDir, "transport-dome-$tag.png").writeBytes(scene.render().encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }.onFailure { println("[render-harness] dome $tag skipped: $it") }

    /** The ring and dome alone at 6x: bar playing, then rail paused (zip mid-pass), across tilts −1..1. */
    @Test
    fun renderDomeCloseUp() {
        renderDomeCloseUp("dark", dark)
        renderDomeCloseUp("busy", busyBars)
    }

    private fun renderDomeCloseUp(tag: String, background: Brush) = runCatching {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val scene = ImageComposeScene(400 * 6, 160 * 6, Density(6f)) {
            OrpheusTheme {
                Column(
                    Modifier.fillMaxSize().background(background),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf(BarRingSize to false, RailRingSize to true).forEach { (size, paused) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            tilts.forEach { tilt ->
                                VibeTransportRing(
                                    paused = paused, progress = 0.62f, ringSize = size, domeTilt = { tilt },
                                    pulse = { loud }, previewWavePhase = wavePhase, previewZipMs = zipMidPass,
                                )
                            }
                        }
                    }
                }
            }
        }
        try {
            File(outDir, "transport-dome-closeup-$tag.png").writeBytes(scene.render().encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }.onFailure { println("[render-harness] dome close-up $tag skipped: $it") }
}
