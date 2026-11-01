package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.cowbell.bell3d.BellCamera
import org.balch.orpheus.features.cowbell.bell3d.BellTransition
import org.balch.orpheus.features.cowbell.bell3d.PLAIN_TURN_SECONDS
import org.balch.orpheus.features.cowbell.bell3d.SHARDS_SECONDS
import org.balch.orpheus.features.cowbell.bell3d.ShardRenderer
import org.balch.orpheus.features.cowbell.bell3d.forMode
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The camera move on a mode change, on the scene's hand-driven clock. */
class CowbellBellMorphTest {

    private val background = Color(0xFF14141F)
    private val box = Rect(40f, 30f, 200f, 330f)

    private fun scene(
        recording: RecordingActions = RecordingActions(),
        style: BellMorphStyle = BellMorphStyle.SHARDS,
        mode: () -> CowbellMode,
    ) =
        CowbellScene(240, 360) {
            CowbellBell3D(
                mode = mode(),
                actions = recording.actions,
                bend = { 0f },
                morphStyle = style,
                modifier = Modifier.padding(start = 40.dp, top = 30.dp).size(160.dp, 300.dp),
            )
        }

    private fun CowbellScene.pixels(): PixelMap = scene.render(now * 1_000_000).toComposeImageBitmap().toPixelMap()

    /** A fresh scene resting in [mode], advanced to the same clock reading as [now]. */
    private fun restingAt(mode: CowbellMode, now: Long): PixelMap = scene { mode }.use { s ->
        s.frames(now - s.now)
        assertEquals(now, s.now, "the clocks must agree for the blobs to match")
        s.pixels()
    }

    private fun distance(a: Color, b: Color): Float = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    /** The share of pixels that changed between two frames. */
    private fun changed(a: PixelMap, b: PixelMap): Float {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (distance(a[x, y], b[x, y]) > 0.1f) n++
        return n.toFloat() / (a.width * a.height)
    }

    private fun PixelMap.assertNothingOutsideTheBox(at: String) {
        for (y in 0 until height) for (x in 0 until width) {
            if (x >= box.left && x < box.right && y >= box.top && y < box.bottom) continue
            assertTrue(distance(this[x, y], background) < 0.01f, "$at: pixel ($x, $y) is ${this[x, y]} outside the bell")
        }
    }

    private fun meanDifference(a: PixelMap, b: PixelMap): Float {
        var sum = 0.0
        for (y in 0 until a.height) for (x in 0 until a.width) sum += distance(a[x, y], b[x, y]) / 3f
        return (sum / (a.width * a.height)).toFloat()
    }

    /** Runs TAP to SYNTH in [style], checking the box every 160 ms, and compares frame [midMillis]. */
    private fun assertMorphDraws(style: BellMorphStyle, midMillis: Long) {
        var mode by mutableStateOf(CowbellMode.TAP)
        scene(style = style) { mode }.use { s ->
            val tapRest = s.pixels()
            mode = CowbellMode.SYNTH
            val start = s.now
            while (s.now - start + 160 <= midMillis) {
                s.frames(160)
                s.pixels().assertNothingOutsideTheBox("$style ${s.now - start} ms")
            }
            s.frames(midMillis - (s.now - start))
            val mid = s.pixels()
            mid.assertNothingOutsideTheBox("mid morph")
            val synthRest = restingAt(CowbellMode.SYNTH, s.now)
            val fromTap = changed(mid, tapRest)
            // The liquid, not just the new view at rest.
            val fromSynth = changed(mid, synthRest)
            println("[morph] $style pixels changed mid morph: from tap ${"%.3f".format(fromTap)}, from synth ${"%.3f".format(fromSynth)}")
            assertTrue(fromTap > 0.05f, "mid morph changed only $fromTap of the resting TAP frame")
            assertTrue(fromSynth > 0.05f, "mid morph changed only $fromSynth of a resting SYNTH frame")
        }
    }

    @Test
    fun `a mode change draws the liquid`() = assertMorphDraws(BellMorphStyle.LIQUID, midMillis = 1100)

    @Test
    fun `a mode change bursts into shards`() = assertMorphDraws(BellMorphStyle.SHARDS, midMillis = (SHARDS_SECONDS * 500).toLong())

    @Test
    fun `the liquid morph settles on the new view`() {
        var mode by mutableStateOf(CowbellMode.TAP)
        scene(style = BellMorphStyle.LIQUID) { mode }.use { s ->
            mode = CowbellMode.SYNTH
            s.frames(2500)
            val diff = meanDifference(s.pixels(), restingAt(CowbellMode.SYNTH, s.now))
            assertTrue(diff < 0.02f, "settled frame differs by $diff from a resting SYNTH bell")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the shards cover the bell through the whole burst`() {
        val width = 160
        val height = 300
        val shards = ShardRenderer()
        val transition = BellTransition(
            BellCamera.forMode(CowbellMode.TAP),
            BellCamera.forMode(CowbellMode.SHAKE),
            startSeconds = 0.0,
            durationSeconds = SHARDS_SECONDS,
        )
        for (p in listOf(0.05, 0.3, 0.5, 0.7, 0.95)) {
            val scene = ImageComposeScene(width, height, Density(1f)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawBellMorph(transition, SHARDS_SECONDS * p, 3.0, 0f, 0f, 0f, liquid = null, shards = shards)
                }
            }
            val pixels = try {
                scene.render().toComposeImageBitmap().toPixelMap()
            } finally {
                scene.close()
            }
            var opaque = 0
            for (y in 0 until height) for (x in 0 until width) if (pixels[x, y].alpha > 0.95f) opaque++
            val coverage = opaque.toFloat() / (width * height)
            println("[morph] shards at $p cover ${"%.3f".format(coverage)}")
            assertTrue(coverage > 0.02f, "the shards drew $coverage opaque pixels at p = $p")
        }
    }

    @Test
    fun `the shard morph settles on the new view`() {
        var mode by mutableStateOf(CowbellMode.TAP)
        scene { mode }.use { s ->
            mode = CowbellMode.SYNTH
            s.frames(2500)
            val settled = s.pixels()
            val diff = meanDifference(settled, restingAt(CowbellMode.SYNTH, s.now))
            println("[morph] settled mean difference ${"%.4f".format(diff)}")
            assertTrue(diff < 0.02f, "settled frame differs by $diff from a resting SYNTH bell")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `without a shader the bell turns plainly`() {
        val width = 160
        val height = 300
        val transition = BellTransition(
            BellCamera.forMode(CowbellMode.TAP),
            BellCamera.forMode(CowbellMode.SYNTH),
            startSeconds = 0.0,
            durationSeconds = PLAIN_TURN_SECONDS,
        )
        val scene = ImageComposeScene(width, height, Density(1f)) {
            Canvas(Modifier.fillMaxSize()) {
                drawBellMorph(
                    transition,
                    nowSeconds = PLAIN_TURN_SECONDS * 0.5,
                    blobSeconds = 3.0,
                    pull = 0f,
                    flash = 0f,
                    glow = 0f,
                    liquid = null,
                )
            }
        }
        val pixels = try {
            scene.render().toComposeImageBitmap().toPixelMap()
        } finally {
            scene.close()
        }
        var opaque = 0
        for (y in 0 until height) for (x in 0 until width) if (pixels[x, y].alpha > 0.95f) opaque++
        val coverage = opaque.toFloat() / (width * height)
        println("[morph] plain turn face coverage ${"%.3f".format(coverage)}")
        assertTrue(coverage > 0.10f, "the plain turn drew $coverage opaque face pixels")
    }

    @Test
    fun `a strike during the morph still sounds`() {
        val recording = RecordingActions()
        var mode by mutableStateOf(CowbellMode.SYNTH)
        scene(recording) { mode }.use { s ->
            mode = CowbellMode.TAP
            s.frames(1000)
            val at = s.bell().center
            s.press(at)
            s.release(at)
            assertEquals(1, recording.calls.count { it == "strike" }, "calls ${recording.calls}")
            val point = recording.strikePoints.single()
            assertTrue(point.x in 0f..1f && point.y in 0f..1f, "strike at $point")
        }
    }
}
