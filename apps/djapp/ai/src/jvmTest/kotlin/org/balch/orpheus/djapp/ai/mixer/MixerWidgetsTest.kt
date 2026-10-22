package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

class MixerWidgetsTest {
    @Test
    fun faderMapsRange() {
        assertEquals(-30f, faderToDb(0f))
        assertEquals(3.5f, faderToDb(1f))
        assertTrue(dbToFader(0f) in 0.89f..0.90f, "0 dB sits at ${dbToFader(0f)}")
        for (db in listOf(-30f, -12.3f, -4.9f, 0f, 1.2f, 3.5f)) {
            assertTrue(abs(faderToDb(dbToFader(db)) - db) <= 0.1f, "round trip of $db")
        }
    }

    @Test
    fun meterFillFloorsSilence() {
        assertEquals(0f, meterFill(-120.0))
        assertEquals(1f, meterFill(0.0))
        assertEquals(0.5f, meterFill(-30.0))
    }

    @Test
    fun dragHoldsLocalValue() {
        val scene = ImageComposeScene(300, 400, Density(1f)) {
            OrpheusTheme {
                Surface(color = OrpheusColors.deepPurple) {
                    Box(Modifier.padding(20.dp)) {
                        // The state never moves: the shown value during the drag is the local hold.
                        DbFader(db = 0f, onDbChange = {}, onReset = {}, index = 0, height = 200.dp)
                    }
                }
            }
        }
        try {
            var now = 0L
            fun frame() { now += 16; scene.render(now * 1_000_000) }
            fun nodes(): List<SemanticsNode> {
                val out = mutableListOf<SemanticsNode>()
                fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
                walk(scene.semanticsOwners.first().rootSemanticsNode)
                return out
            }
            fun shows(text: String) = nodes().any { n ->
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            }
            fun pointer(type: PointerEventType, at: Offset) {
                val button = if (type == PointerEventType.Move) null else PointerButton.Primary
                scene.sendPointerEvent(type, at, timeMillis = now, type = PointerType.Touch, button = button)
            }
            frame()
            assertTrue(shows("0.0 dB"))
            val track = assertNotNull(
                nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == faderTag(0) },
                "no fader track",
            ).boundsInRoot
            val x = track.center.x
            pointer(PointerEventType.Press, Offset(x, track.center.y))
            frame()
            val target = track.bottom - 0.75f * track.height
            pointer(PointerEventType.Move, Offset(x, target))
            frame()
            val dragged = dbText(faderToDb(0.75f))
            assertTrue(shows(dragged), "expected $dragged while dragging")
            assertTrue(!shows("0.0 dB"), "the state value showed during the drag")
            pointer(PointerEventType.Release, Offset(x, target))
            frame()
            assertTrue(shows("0.0 dB"), "release should show the state value again")
        } finally {
            scene.close()
        }
    }

    @Test
    fun verticalSwipeOnSliderDoesNotEdit() {
        val calls = mutableListOf<Float>()
        lateinit var scroll: ScrollState
        sliderScene(calls, onScroll = { scroll = it }) { slider ->
            val x = slider.left + 18f + 0.5f * (slider.width - 18f - 26f)
            val y0 = slider.center.y
            pointer(PointerEventType.Press, Offset(x, y0))
            for (k in 1..10) pointer(PointerEventType.Move, Offset(x, y0 - 12f * k))
            pointer(PointerEventType.Release, Offset(x, y0 - 120f))
            assertEquals(emptyList(), calls, "a vertical swipe edited the slider")
            assertTrue(scroll.value > 0, "the swipe should have scrolled the column")
        }
    }

    @Test
    fun verticalSwipeOnScrollingKnobWaitsForSlop() {
        val calls = mutableListOf<Float>()
        val scene = ImageComposeScene(300, 400, Density(1f)) {
            OrpheusTheme {
                Surface(color = OrpheusColors.deepPurple) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                        MixerMacroKnobs(
                            listOf(MixerMacro(Macro.ENERGY, 0.5f, 0.5f, changed = false, note = "")),
                            onSet = { _, v -> calls += v },
                            knobSize = 46.dp,
                        )
                        Spacer(Modifier.height(1000.dp))
                    }
                }
            }
        }
        try {
            val touch = Touch(scene)
            touch.frame()
            val knob = touch.bounds(macroTag(Macro.ENERGY))
            val dial = Offset(knob.center.x, knob.top + 23f)
            with(touch) {
                // A press released within slop: nothing turns.
                pointer(PointerEventType.Press, dial)
                pointer(PointerEventType.Move, dial + Offset(0f, 6f))
                pointer(PointerEventType.Release, dial + Offset(0f, 6f))
                assertEquals(emptyList(), calls, "a short press turned the knob")
                // Past the double-tap timeout, so the next press is not a reset.
                repeat(30) { frame() }
                // Sideways past slop first: left to the parent, and later vertical travel still turns nothing.
                pointer(PointerEventType.Press, dial)
                pointer(PointerEventType.Move, dial + Offset(30f, 4f))
                pointer(PointerEventType.Move, dial + Offset(30f, 44f))
                pointer(PointerEventType.Release, dial + Offset(30f, 44f))
                assertEquals(emptyList(), calls, "a sideways gesture turned the knob")
                repeat(30) { frame() }
            }
            // A deliberate 40px drag up turns it, counted from the slop's edge.
            with(touch) {
                pointer(PointerEventType.Press, dial)
                for (k in 1..4) pointer(PointerEventType.Move, dial - Offset(0f, 10f * k))
                pointer(PointerEventType.Release, dial - Offset(0f, 40f))
            }
            assertTrue(calls.isNotEmpty(), "a 40px vertical drag never turned the knob")
            assertTrue(calls.last() > 0.5f && calls.last() < 0.7f, "the drag landed at ${calls.last()}")
        } finally {
            scene.close()
        }
    }

    @Test
    fun tapSetsValue() {
        val calls = mutableListOf<Float>()
        sliderScene(calls) { slider ->
            val at = Offset(slider.left + 18f + 0.25f * (slider.width - 18f - 26f), slider.center.y)
            pointer(PointerEventType.Press, at)
            assertEquals(emptyList(), calls, "nothing should change on down")
            pointer(PointerEventType.Release, at)
            assertEquals(listOf(0.25f), calls)
        }
    }

    private class Touch(val scene: ImageComposeScene) {
        var now = 0L

        fun frame() {
            now += 16
            scene.render(now * 1_000_000)
        }

        fun pointer(type: PointerEventType, at: Offset) {
            val button = if (type == PointerEventType.Move) null else PointerButton.Primary
            scene.sendPointerEvent(type, at, timeMillis = now, type = PointerType.Touch, button = button)
            frame()
        }

        fun bounds(tag: String): Rect {
            val out = mutableListOf<SemanticsNode>()
            fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
            walk(scene.semanticsOwners.first().rootSemanticsNode)
            return assertNotNull(out.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, tag).boundsInRoot
        }
    }

    /** A 200 dp 0..1 slider (18 dp label, 26 dp value) at the top of a scrolling column taller than the scene. */
    private fun sliderScene(calls: MutableList<Float>, onScroll: (ScrollState) -> Unit = {}, body: Touch.(Rect) -> Unit) {
        val scene = ImageComposeScene(300, 400, Density(1f)) {
            OrpheusTheme {
                Surface(color = OrpheusColors.deepPurple) {
                    val state = rememberScrollState()
                    onScroll(state)
                    Column(Modifier.verticalScroll(state).padding(20.dp)) {
                        MixerSlider("R", 0.5f, 0f..1f, { calls += it }, ::f2, 0, Modifier.width(200.dp).testTag("slider"))
                        Spacer(Modifier.height(1000.dp))
                    }
                }
            }
        }
        try {
            val touch = Touch(scene)
            touch.frame()
            touch.body(touch.bounds("slider"))
        } finally {
            scene.close()
        }
    }
}
