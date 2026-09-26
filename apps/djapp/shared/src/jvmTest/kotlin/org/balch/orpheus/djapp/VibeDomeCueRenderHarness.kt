package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test

/**
 * Filmstrips of the phone bar's dome moving by itself, each frame a crop around the transport at 3x.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*VibeDomeCueRenderHarness*' --rerun
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VibeDomeCueRenderHarness {
    private val scale = 3f
    private val widthDp = 360
    private val heightDp = 780

    // Around the transport's slot: the raised ring above the bar, and the name on the tab labels' line.
    private val cropWidthDp = 120
    private val cropHeightDp = 124

    /** The phone bar under [rig]'s cues, stepped 16ms at a time on [scheduler]'s virtual clock. */
    private inner class PhoneBar(rig: VibeDomeRig, private val scheduler: TestCoroutineScheduler) {
        private var nowMs = 1_000L
        val scene = ImageComposeScene(
            (widthDp * scale).toInt(), (heightDp * scale).toInt(), Density(scale), UnconfinedTestDispatcher(scheduler),
        ) {
            CompositionLocalProvider(LocalVibeDomeCues provides rig.cues) {
                OrpheusTheme {
                    DjAppNavScaffold(
                        isSelected = { it == DjTab }, onItemClick = {}, layout = DjLayout.Portrait,
                        pulsarFeature = rig.feature, timerFeature = TimerViewModel.previewFeature(), onTogglePlayback = {},
                        modifier = Modifier.fillMaxSize().background(Color(0xFF14141F)),
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
        }

        /** Steps to [atMs] after [fromMs] on the virtual clock and returns that frame. */
        fun frameAt(fromMs: Long, atMs: Long): Image {
            while (nowMs < fromMs + atMs - 16) step().close()
            return step()
        }

        fun step(): Image {
            nowMs += 16
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            return scene.render(nowMs * 1_000_000)
        }

        val now: Long get() = nowMs

        fun close() = scene.close()
    }

    /** [frames] cropped round the transport and laid side by side, a light rule between each. */
    private fun strip(frames: List<Image>, name: String) {
        val w = cropWidthDp * scale
        val h = cropHeightDp * scale
        val gap = 4 * scale
        val left = (widthDp - cropWidthDp) / 2f * scale
        val top = (heightDp - cropHeightDp) * scale
        val surface = Surface.makeRasterN32Premul(((w + gap) * frames.size - gap).toInt(), h.toInt())
        surface.canvas.clear(0xFF5A5A70.toInt())
        frames.forEachIndexed { i, frame ->
            surface.canvas.drawImageRect(frame, Rect.makeXYWH(left, top, w, h), Rect.makeXYWH(i * (w + gap), 0f, w, h))
        }
        val outDir = File("build/djapp-render").apply { mkdirs() }
        File(outDir, name).writeBytes(surface.makeImageSnapshot().encodeToData()!!.bytes)
    }

    /**
     * The "swipe me" wiggle on a first launch, in eight frames from its shown time: at rest, tipping
     * left, held left peeking the previous vibe, crossing, tipping right, held right peeking the
     * next, springing home, at rest.
     */
    @Test
    fun renderWiggle() {
        runCatching {
            val scheduler = TestCoroutineScheduler()
            val rig = VibeDomeRig(UnconfinedTestDispatcher(scheduler), preferences = MemoryPreferences())
            val bar = PhoneBar(rig, scheduler)
            try {
                // The bar was shown as the scene composed, at 1000ms on its clock.
                val frames = listOf(1_488L, 1_616L, 1_808L, 1_968L, 2_112L, 2_304L, 2_432L, 2_800L).map { bar.frameAt(1_000L, it) }
                strip(frames, "dome-wiggle.png")
                frames.forEach { it.close() }
            } finally {
                bar.close()
                rig.close()
            }
        }.onFailure { println("[render-harness] dome wiggle skipped: $it") }
    }

    /**
     * The song ends and the next vibe follows: the dome rolls right as the transition starts, while
     * the old name still shows. Tipping right, near the edge, the new face coming round, at rest.
     */
    @Test
    fun renderRollOnAdvance() {
        runCatching {
            val scheduler = TestCoroutineScheduler()
            val rig = VibeDomeRig(UnconfinedTestDispatcher(scheduler), swapAfterMs = 2_000L)
            val bar = PhoneBar(rig, scheduler)
            val advancer = CoroutineScope(UnconfinedTestDispatcher(scheduler))
            try {
                repeat(3) { bar.step().close() }
                val start = bar.now
                advancer.launch { rig.navigator.advance(RigVibeNames[1], "Stay Asleep", TransitionSpec(TransitionStyle.FADE)) }
                val frames = listOf(64L, 128L, 192L, 480L).map { bar.frameAt(start, it) }
                strip(frames, "dome-roll-on-advance.png")
                frames.forEach { it.close() }
            } finally {
                advancer.cancel()
                bar.close()
                rig.close()
            }
        }.onFailure { println("[render-harness] dome roll skipped: $it") }
    }
}
