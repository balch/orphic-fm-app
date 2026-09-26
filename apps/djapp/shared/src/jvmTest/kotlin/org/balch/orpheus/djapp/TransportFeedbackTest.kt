package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.skia.Image
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone bar's and the rail's dome take the dock's feedback: hover and a press lighten a circle
 * on the ring, never the node's square corners or the name, and keyboard focus wears the focus
 * mark, which a pointer press takes away and a key brings back.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*TransportFeedbackTest*' --rerun
 */
@OptIn(InternalComposeUiApi::class)
class TransportFeedbackTest {
    private val density = 2f
    private val background = Color(0xFF14141F)

    // Paused with no progress and a name that fits: nothing moves unless the test moves it.
    private val pulsar: PulsarFeature = run {
        val base = PulsarViewModel.previewFeature()
        object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(base.stateFlow.value.copy(globalPaused = true))
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(VibeNavState("Drift", "Dog House", "Stay Asleep"))
        }
    }

    private inner class Nav(val layout: DjLayout, widthDp: Int, heightDp: Int) {
        private var now = 1_000L
        val scene = ImageComposeScene(widthDp * 2, heightDp * 2, Density(density)) {
            OrpheusTheme {
                DjAppNavScaffold(
                    isSelected = { it == DjTab }, onItemClick = {}, layout = layout, pulsarFeature = pulsar,
                    timerFeature = TimerViewModel.previewFeature(), onTogglePlayback = {},
                    modifier = Modifier.fillMaxSize().background(background),
                ) { Box(Modifier.fillMaxSize()) }
            }
        }

        fun frame(): ByteArray {
            now += 16
            return scene.render(now * 1_000_000).encodeToData()!!.bytes
        }

        fun pixels(): PixelMap = Image.makeFromEncoded(frame()).toComposeImageBitmap().toPixelMap()

        private fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)

        private val transport: SemanticsNode
            get() = scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }.first { n ->
                n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.endsWith(", Drift") }
            }

        val box: Rect get() = Rect(transport.positionInRoot, transport.size.toSize())
        val focused: Boolean get() = transport.config.getOrNull(SemanticsProperties.Focused) == true

        /** 4dp under the node's top, then half the ring. */
        val ringCentre: Offset get() = Offset(box.center.x, box.top + (TransportPadding.value + ringSize.value / 2) * density)

        val ringSize get() = if (layout == DjLayout.Portrait) BarRingSize else RailRingSize

        fun pointer(type: PointerEventType, at: Offset) {
            val button = if (type == PointerEventType.Press || type == PointerEventType.Release) PointerButton.Primary else null
            scene.sendPointerEvent(type, at, timeMillis = now, button = button)
            frame()
        }

        fun key(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            frame()
        }

        init {
            repeat(3) { frame() }
        }

        fun close() = scene.close()
    }

    private val chromes = listOf(Triple(DjLayout.Portrait, 360, 780), Triple(DjLayout.Landscape, 800, 360))

    private operator fun PixelMap.get(at: Offset): Color = this[at.x.toInt(), at.y.toInt()]

    private fun Color.near(other: Color) = abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue) < 0.03f

    @Test
    fun hoverAndAPressLightenACircleOnTheRingAlone() {
        for ((layout, w, h) in chromes) {
            val nav = Nav(layout, w, h)
            try {
                val centre = nav.ringCentre
                // The dome's body below the glyph, the node's corner outside the ring and its mark, and the name.
                val body = Offset(centre.x, centre.y + nav.ringSize.value * 0.28f * density)
                val corner = Offset(nav.box.left + 4 * density, nav.box.top + 6 * density)
                val name = Offset(centre.x, nav.box.bottom - 10 * density)
                val rest = nav.pixels()

                nav.pointer(PointerEventType.Move, centre)
                repeat(12) { nav.frame() }
                val hovered = nav.pixels()
                assertTrue(hovered[body].luminance() > rest[body].luminance() + 0.01f, "$layout: hover did not lighten the dome")
                assertTrue(hovered[corner].near(rest[corner]), "$layout: hover reached the node's square corner")

                nav.pointer(PointerEventType.Press, centre)
                repeat(8) { nav.frame() }
                val pressed = nav.pixels()
                assertTrue(pressed[body].luminance() > hovered[body].luminance(), "$layout: a press did not lighten the dome")
                assertTrue(pressed[corner].near(rest[corner]), "$layout: a press reached the node's square corner ${pressed[corner]} vs ${rest[corner]}")
                assertTrue(pressed[name].near(rest[name]), "$layout: a press lit the name under the ring")
                nav.pointer(PointerEventType.Release, centre)
            } finally {
                nav.close()
            }
        }
    }

    /** Pixels on the focus mark's circle at the ring's sides that differ from [rest]. */
    private fun Nav.markPixels(rest: PixelMap): Int {
        val px = pixels()
        val centre = ringCentre
        val radius = domeFocusRadius(ringSize).value * density
        var count = 0
        for (y in (centre.y - 12).toInt()..(centre.y + 12).toInt()) {
            for (x in (centre.x - radius - 4).toInt()..(centre.x + radius + 4).toInt()) {
                val r = hypot(x + 0.5f - centre.x, y + 0.5f - centre.y)
                if (r >= radius - 2f && r <= radius + 2f && !px[x, y].near(rest[x, y])) count++
            }
        }
        return count
    }

    @Test
    fun keyboardFocusWearsTheMarkThatAPressTakesAwayAndAKeyBringsBack() {
        for ((layout, w, h) in chromes) {
            val nav = Nav(layout, w, h)
            try {
                val rest = nav.pixels()
                assertEquals(0, nav.markPixels(rest), "sanity: $layout, the mark shows before any focus")
                var tabs = 0
                while (!nav.focused && tabs < 10) {
                    nav.key(Key.Tab)
                    tabs++
                }
                assertTrue(nav.focused, "sanity: $layout, Tab never reached the dome")
                assertTrue(nav.markPixels(rest) > 40, "$layout: the keyboard's focus wore no mark")
                nav.pointer(PointerEventType.Press, nav.ringCentre)
                nav.pointer(PointerEventType.Release, nav.ringCentre)
                repeat(20) { nav.frame() }
                assertTrue(nav.focused, "sanity: $layout, the press took focus off the dome")
                assertEquals(0, nav.markPixels(rest), "$layout: a press left the mark showing")
                nav.key(Key.DirectionRight)
                assertTrue(nav.markPixels(rest) > 40, "$layout: a key did not bring the mark back")
            } finally {
                nav.close()
            }
        }
    }
}
