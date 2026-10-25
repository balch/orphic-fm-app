package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.balch.orpheus.djapp.LocalCanvasScale
import org.balch.orpheus.features.pulsar.bench.CaptureField
import org.balch.orpheus.features.pulsar.bench.CaptureRow
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.ui.theme.OrpheusTheme

class HistoryPanelTest {
    private val rows = listOf(
        ChangeRow(1, "you", "KICK pan", "0.00", "0.04", "whole vibe", atMs = 10_000L, on = true, covered = false),
        ChangeRow(2, "Orpheus", "TEXT pan", "0.40", "0.34", "whole vibe", atMs = 20_000L, on = false, covered = false),
        PointRow(3, "Point 1", atMs = 30_000L),
        ChangeRow(4, "Claude Code", "KICK reverb", "0.08", "0.85", "Overhead", atMs = 40_000L, on = true, covered = true),
    )

    private val base = AiMixerUiState(vibeName = "Sonar Hymn", history = rows, onCount = 2, loadedAtMs = 0L)

    private class Calls {
        val log = mutableListOf<String>()
        val actions = AiMixerActions.EMPTY.copy(
            toggleChange = { log += "toggle $it" },
            hear = { log += "hear $it" },
            endHear = { log += "endHear" },
            rollBack = { log += "rollBack $it" },
        )
    }

    private class Rig(val scene: ImageComposeScene) {
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

        fun nodes(): List<SemanticsNode> {
            val out = mutableListOf<SemanticsNode>()
            fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
            scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            return out
        }

        fun byDescription(d: String) = assertNotNull(
            nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(d) == true },
            d,
        )

        fun byTag(tag: String) = assertNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, tag)

        fun byText(prefix: String) = assertNotNull(
            nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith(prefix) } },
            prefix,
        )

        fun hasText(prefix: String) =
            nodes().any { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith(prefix) } }

        fun tap(node: SemanticsNode) {
            val at = node.boundsInRoot.center
            pointer(PointerEventType.Press, at)
            pointer(PointerEventType.Release, at)
            repeat(4) { frame() }
        }
    }

    private fun scene(state: AiMixerUiState, calls: Calls, shown: MutableState<Boolean> = mutableStateOf(true), body: Rig.() -> Unit) {
        val scene = ImageComposeScene(600, 900, Density(1f)) {
            CompositionLocalProvider(LocalCanvasScale provides 1f) {
                OrpheusTheme {
                    Box(Modifier.fillMaxSize()) {
                        if (shown.value) HistoryTimeline(state, calls.actions, nowMs = 100_000L, modifier = Modifier.width(560.dp))
                    }
                }
            }
        }
        try {
            val rig = Rig(scene)
            repeat(4) { rig.frame() }
            rig.body()
        } finally {
            scene.close()
        }
    }

    @Test
    fun tappingANodeTogglesThatChange() {
        val calls = Calls()
        scene(base, calls) {
            tap(byDescription("Turn off KICK pan"))
            assertEquals(listOf("toggle 1"), calls.log)
            tap(byDescription("Turn on TEXT pan"))
            assertEquals(listOf("toggle 1", "toggle 2"), calls.log)
        }
    }

    @Test
    fun newestIsOnTop() {
        scene(base, Calls()) {
            val tops = listOf("Now", "KICK reverb", "Point 1", "TEXT pan", "KICK pan", "File").map { byText(it).boundsInRoot.top }
            assertEquals(tops.sorted(), tops, "Now, then newest to oldest, then File: $tops")
        }
    }

    @Test
    fun writesOpensThatChangesFileValues() {
        val pan = CaptureRow(Scope.VIBE, null, null, 0, CaptureField.PAN, null, 0f, 0.04f)
        val state = base.copy(history = rows.map { if (it is ChangeRow && it.id == 1) it.copy(writes = listOf(pan)) else it })
        val calls = Calls()
        scene(state, calls) {
            assertTrue(hasText("▸ writes 1 value"))
            assertFalse(hasText("+ pan"))
            tap(byTag(historyWritesTag(1)))
            assertTrue(hasText("+ pan = 0.040"), "the change's file value is listed under it")
            assertEquals(emptyList(), calls.log, "opening the list never toggles the change")
        }
    }

    @Test
    fun historyPanelRendersVibeRow() {
        val on = VibeEditRow(5, "chorus · KICK morph", "0.20", "0.80", "Orpheus", on = true, captured = true, atMs = 50_000L)
        val off = VibeEditRow(6, "bpm", "120", "96", "Claude Code", on = false, captured = false, atMs = 60_000L)
        val state = base.copy(history = rows + on + off)
        val calls = Calls()
        scene(state, calls) {
            assertTrue(hasText("chorus · KICK morph  0.20 → 0.80"))
            assertTrue(hasText("captured"), "a captured vibe row carries the tag")
            assertTrue(hasText("vibe · now"), "the age line names what the row edits")
            assertTrue(hasText("vibe · now · off"), "an off row says so")
            tap(byDescription("Turn off chorus · KICK morph"))
            tap(byDescription("Turn on bpm"))
            assertEquals(listOf("toggle 5", "toggle 6"), calls.log)
            val tops = listOf("bpm", "chorus · KICK morph", "KICK reverb").map { byText(it).boundsInRoot.top }
            assertEquals(tops.sorted(), tops, "vibe rows sit in the timeline, newest first")
            tap(byTag(historyRollBackTag(3)))
            assertTrue(hasText("Throw away 3 changes after this?"), "vibe rows count as changes after a point")
        }
    }

    @Test
    fun tappingTheRowElsewhereDoesNothing() {
        val calls = Calls()
        scene(base, calls) {
            tap(byText("KICK pan"))
            assertEquals(emptyList(), calls.log)
        }
    }

    @Test
    fun holdingHearPreviewsAndReleaseEnds() {
        val calls = Calls()
        scene(base, calls) {
            val at = byTag(historyHearTag(3)).boundsInRoot.center
            pointer(PointerEventType.Press, at)
            repeat(4) { frame() }
            assertEquals(listOf("hear 3"), calls.log)
            pointer(PointerEventType.Release, at)
            repeat(4) { frame() }
            assertEquals(listOf("hear 3", "endHear"), calls.log)
        }
    }

    @Test
    fun hearEndsWhenTheRowLeavesMidPress() {
        val calls = Calls()
        val shown = mutableStateOf(true)
        scene(base, calls, shown) {
            pointer(PointerEventType.Press, byTag(historyHearTag(3)).boundsInRoot.center)
            repeat(4) { frame() }
            shown.value = false
            repeat(4) { frame() }
            assertEquals(listOf("hear 3", "endHear"), calls.log)
        }
    }

    @Test
    fun rollBackAsksFirst() {
        val calls = Calls()
        scene(base, calls) {
            tap(byTag(historyRollBackTag(3)))
            assertEquals(emptyList(), calls.log)
            assertTrue(hasText("Throw away 1 change after this?"), "asks before throwing away")
            tap(byTag(historyKeepTag(3)))
            assertEquals(emptyList(), calls.log)
            assertFalse(hasText("Throw away"))
            tap(byTag(historyRollBackTag(3)))
            tap(byTag(historyConfirmTag(3)))
            assertEquals(listOf("rollBack 3"), calls.log)
        }
    }

    @Test
    fun offRowsAreStruckThrough() {
        scene(base, Calls()) {
            fun struck(prefix: String): Boolean {
                val results = mutableListOf<TextLayoutResult>()
                byText(prefix).config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(results)
                return results.single().layoutInput.style.textDecoration?.contains(TextDecoration.LineThrough) == true
            }
            assertTrue(struck("TEXT pan"))
            assertFalse(struck("KICK pan"))
            assertTrue(hasText("Now"))
            assertTrue(hasText("Now · 2 of 3 changes on"))
        }
    }
}
