package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.SongStory
import org.balch.orpheus.features.pulsar.StoryBeat
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test

/**
 * Filmstrips of an endless song's blazing gap, on the ring and the song band, playing, frames 150ms
 * apart, next to a song that will still end (plain dim gap) for comparison.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*EndlessGapRenderHarness*' --rerun
 */
class EndlessGapRenderHarness {
    private val scale = 4f
    private val outDir = File("build/djapp-render")

    private fun position(endless: Boolean) =
        SongPosition("Rust Belt", 250_000L, 280_000L, final = false, estimateMs = 217_000L, endless = endless)

    private fun nav(endless: Boolean) = VibeNavState(
        "Rust Belt", "Dog House", "Stay Asleep", progress = 0.89f, positionMs = 250_000L, durationMs = 280_000L,
        durationFinal = false, estimateMs = 217_000L, endless = endless,
    )

    private fun pulsar(nav: VibeNavState): PulsarFeature {
        val base = PulsarViewModel.previewFeature()
        return object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(base.stateFlow.value.copy(globalPaused = false))
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(nav)
            override val songStoryFlow: StateFlow<SongStory> = MutableStateFlow(
                SongStory(List(400) { StoryBeat(0.3f + (it % 7) / 10f, FloatArray(8).also { e -> e[it % 8] = 1f }, it * 500L, 500L) }, 200_000L),
            )
            override val actions: PulsarPanelActions = base.actions
        }
    }

    /** [frames] frames of [content], [gapMs] apart once it has run [warmMs], side by side. */
    private fun strip(name: String, widthDp: Int, heightDp: Int, frames: Int, gapMs: Long, warmMs: Long = 600L, content: @Composable () -> Unit) {
        val w = (widthDp * scale).toInt()
        val h = (heightDp * scale).toInt()
        val scene = ImageComposeScene(w, h, Density(scale)) {
            OrpheusTheme { Box(Modifier.fillMaxSize().background(Color(0xFF14141F)), contentAlignment = Alignment.Center) { content() } }
        }
        try {
            var nowMs = 0L
            fun at(ms: Long): Image {
                while (nowMs < ms - 16) { nowMs += 16; scene.render(nowMs * 1_000_000).close() }
                nowMs += 16
                return scene.render(nowMs * 1_000_000)
            }
            val shots = (0 until frames).map { at(warmMs + it * gapMs) }
            val gap = (4 * scale).toInt()
            val surface = Surface.makeRasterN32Premul((w + gap) * frames - gap, h)
            surface.canvas.clear(0xFF5A5A70.toInt())
            shots.forEachIndexed { i, shot ->
                surface.canvas.drawImageRect(shot, Rect.makeXYWH(0f, 0f, w.toFloat(), h.toFloat()), Rect.makeXYWH(i * (w + gap).toFloat(), 0f, w.toFloat(), h.toFloat()))
                shot.close()
            }
            outDir.mkdirs()
            File(outDir, name).writeBytes(surface.makeImageSnapshot().encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderRing() {
        runCatching {
            for (endless in listOf(true, false)) {
                strip("endless-ring-${if (endless) "blazing" else "plain"}.png", 96, 96, frames = if (endless) 6 else 1, gapMs = 150L) {
                    VibeTransportRing(paused = false, progress = 0.89f, ringSize = 80.dp, position = position(endless))
                }
            }
        }.onFailure { println("[render-harness] endless ring skipped: $it") }
    }

    @Test
    fun renderBand() {
        runCatching {
            for (endless in listOf(true, false)) {
                strip("endless-band-${if (endless) "blazing" else "plain"}.png", 240, 40, frames = if (endless) 4 else 1, gapMs = 150L) {
                    DockSongBand(pulsar(nav(endless)))
                }
            }
        }.onFailure { println("[render-harness] endless band skipped: $it") }
    }
}
