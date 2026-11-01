package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.LocalCanvasScale
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.CaptureField
import org.balch.orpheus.features.pulsar.bench.CaptureRow
import org.balch.orpheus.features.pulsar.bench.ChannelMeter
import org.balch.orpheus.features.pulsar.bench.EngineSlot
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot
import org.balch.orpheus.features.pulsar.bench.Scope
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
            assertTrue(shows(dbText(0f)))
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
            assertTrue(!shows(dbText(0f)), "the state value showed during the drag")
            pointer(PointerEventType.Release, Offset(x, target))
            frame()
            assertTrue(shows(dbText(0f)), "release should show the state value again")
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

    @Test
    fun stripsFitBesideChatOnFoldCanvas() {
        // The Fold's 1280dp canvas, less the 440dp chat column and the 16dp between them.
        val width = 1280 - 440 - 16
        mixerScene(width, 1000, FOLD_SCALE) {
            for (i in 0 until 8) {
                val w = bounds(stripTag(i)).width
                assertTrue(w >= 72f, "strip $i is ${w}dp")
            }
            assertTrue(bounds(BUS_TAG).right <= width, "the bus strip runs past the panel: ${bounds(BUS_TAG).right}")
        }
    }

    @Test
    fun ambientStripReplacesThreeMeters() {
        mixerScene(1280, 1000, 1f) {
            val ambient = bounds(AMBIENT_TAG)
            assertEquals(64f, ambient.width)
            val inside = texts().filter { (_, b) -> ambient.contains(b.center) }.map { it.first }
            assertTrue(inside.containsAll(listOf("AMBIENT", "S", "St", "Sp")), "ambient strip shows $inside")
            for (gone in listOf("STORM", "STREET", "SPEECH")) {
                assertTrue(texts().none { it.first == gone }, "$gone still has its own strip")
            }
        }
    }

    @Test
    fun noMixerTextBelowFloor() {
        val floor = LegibleText.MIXER_MIN / FOLD_SCALE
        fun Rig.check(where: String) {
            val small = mutableListOf<String>()
            var seen = 0
            for (n in nodes()) {
                val get = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: continue
                val results = mutableListOf<TextLayoutResult>()
                get(results)
                val input = results.singleOrNull()?.layoutInput ?: continue
                seen++
                val sizes = listOf(input.style.fontSize) + input.text.spanStyles.map { it.item.fontSize }.filter { it.isSpecified }
                for (size in sizes) {
                    if (!size.isSp || size.value < floor - 0.01f) small += "\"${input.text}\" at $size"
                }
            }
            assertTrue(seen > 30, "$where: only $seen text nodes")
            assertEquals(emptyList(), small, "$where: text below ${floor}sp")
        }
        mixerScene(1280 - 440 - 16, 1000, FOLD_SCALE) {
            check("strips")
            tap(HISTORY_TAG)
            assertTrue(texts().any { it.first.startsWith("History ·") }, "the history popover did not open")
            check("changes popover")
        }
        mixerScene(360, 780, FOLD_SCALE) { check("compact") }
    }

    @Test
    fun pinToggleCallsAction() {
        val pinned = mutableListOf<Int>()
        val feature = feature(actions = AiMixerActions.EMPTY.copy(togglePin = { pinned += it }))
        mixerScene(1280, 1000, 1f, feature) {
            assertEquals("Unpin BASS", description(pinTag(3)))
            assertEquals("Pin PAD", description(pinTag(5)))
            assertEquals(ToggleableState.On, node(pinTag(3)).config.getOrNull(SemanticsProperties.ToggleableState))
            assertEquals(ToggleableState.Off, node(pinTag(5)).config.getOrNull(SemanticsProperties.ToggleableState))
            assertEquals(Role.Switch, node(pinTag(5)).config.getOrNull(SemanticsProperties.Role))
            // Clipped bounds: the strip's rounded clip would trim a target that spilled past it.
            val target = bounds(pinTag(5))
            assertTrue(target.width >= 32f && target.height >= 32f, "the pin's touch target is $target")
            // Outside the 16dp icon (the target's top-right corner) but inside the target.
            tapAt(Offset(target.left + 6f, target.bottom - 6f))
            assertEquals(listOf(5), pinned)
        }
        mixerScene(360, 780, 1f, feature) {
            tap(pinTag(2))
            assertEquals(listOf(5, 2), pinned)
        }
    }

    @Test
    fun slotLinesShowEnginePickers() {
        // Four-letter, three-letter and two-letter names, in both slots.
        val engines = mapOf(
            3 to (OrpheusEngineId.TRIPLE_SAW to OrpheusEngineId.VOWEL_FOF),
            4 to (OrpheusEngineId.CSAW to OrpheusEngineId.WTB),
            5 to (OrpheusEngineId.VA to OrpheusEngineId.MST),
        )
        val labels = mapOf(
            3 to ("3SAW" to "VOW"), 4 to ("CSAW" to "WTB"), 5 to ("VA" to "MST"),
        )
        val tracks = mixState.tracks.map { t ->
            val (edm, space) = engines[t.index] ?: return@map t
            t.copy(edmEngine = edm.name, spaceEngine = space.name, edmEngineId = edm, spaceEngineId = space)
        }
        val feature = feature(state = mixState.copy(tracks = tracks))
        for ((w, scale) in listOf(1280 - 440 - 16 to FOLD_SCALE, 1280 to 1f)) {
            mixerScene(w, 1000, scale, feature) {
                for ((t, pair) in labels) {
                    // The pickers live in the track's sound pop-out.
                    tap(voiceButtonTag(t))
                    for ((slot, label) in listOf(EngineSlot.EDM to pair.first, EngineSlot.SPACE to pair.second)) {
                        val tag = enginePickTag(t, slot)
                        val picker = bounds(tag)
                        assertTrue(picker.width > 0f && picker.height > 0f, "$tag has no size at $w: $picker")
                        val shown = nodes().filter { n ->
                            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } && picker.contains(n.boundsInRoot.center)
                        }
                        assertEquals(1, shown.size, "$tag should show $label at $w")
                        val results = mutableListOf<TextLayoutResult>()
                        shown.single().config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(results)
                        val ink = results.single()
                        val natural = ink.multiParagraph.maxIntrinsicWidth
                        assertTrue(natural <= picker.width, "$label is clipped by its pill at $w: pill $picker text needs $natural")
                        assertTrue(picker.width > picker.height, "$tag is a pill: $picker")
                    }
                    // The pill stands at the height of the slot label, so the header grows no taller than before.
                    val pop = bounds(VOICE_POPOVER_TAG)
                    val slotText = texts().filter { (text, b) -> text.startsWith("•") && pop.contains(b.center) }
                    assertTrue(slotText.isNotEmpty(), "the heard dot stays at $w")
                    assertTrue(bounds(enginePickTag(t, EngineSlot.EDM)).height <= slotText.first().second.height + 1f, "pill taller than its line at $w")
                    assertTrue(texts().any { it.first == "• EDM" } && texts().any { it.first == "  SPC" }, "the pop-out keeps the slot labels")
                    assertTrue(texts().none { it.first.contains("VOWEL_FOF") })
                    tapAt(texts().first { it.first == "Done" }.second.center)
                    assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == VOICE_POPOVER_TAG }, "Done closes it")
                }
            }
        }
    }

    @Test
    fun agentEditFlashesStrip() {
        val edits = MutableSharedFlow<MixControl>(extraBufferCapacity = 8)
        mixerScene(1280, 1000, 1f, feature(agentEdits = edits)) {
            assertEquals(false, mark(stripTag(3), AgentFlashing))
            assertEquals(true, mark(stripTag(5), AgentGlow), "a strip the agent touched this turn keeps its glow")
            assertEquals(false, mark(stripTag(3), AgentGlow))
            edits.tryEmit(MixControl.Level(3))
            edits.tryEmit(MixControl.MacroKnob(Macro.MOOD))
            repeat(4) { frame() }
            assertEquals(true, mark(stripTag(3), AgentFlashing), "the agent's edit did not flash its strip")
            assertEquals(false, mark(stripTag(2), AgentFlashing))
            assertEquals(true, mark(macroTag(Macro.MOOD), AgentFlashing), "the agent's knob move did not flash the knob")
            repeat(50) { frame() }
            assertEquals(false, mark(stripTag(3), AgentFlashing), "the flash outlived its 600 ms")
        }
    }

    @Test
    fun agentResetSkipsPinnedStrips() {
        val edits = MutableSharedFlow<MixControl>(extraBufferCapacity = 8)
        mixerScene(1280, 1000, 1f, feature(agentEdits = edits)) {
            edits.tryEmit(MixControl.ClearAll)
            repeat(4) { frame() }
            assertEquals(false, mark(stripTag(3), AgentFlashing), "a reset skips the pinned strip, so it must not flash")
            assertEquals(true, mark(stripTag(2), AgentFlashing), "a reset should flash the unpinned strips")
        }
    }

    @Test
    fun historyPopoverClosesOnScrimAndEsc() {
        mixerScene(1280 - 440 - 16, 1000, 1f) {
            fun open() = texts().any { it.first.startsWith("History ·") }
            tap(HISTORY_TAG)
            assertTrue(open(), "the popover did not open")
            assertEquals("Close history", node(HISTORY_SCRIM_TAG).config.getOrNull(SemanticsActions.OnClick)?.label)
            tapAt(Offset(40f, 900f))
            assertTrue(!open(), "a tap outside should close the popover")
            tap(HISTORY_TAG)
            assertTrue(open())
            key(Key.Escape)
            assertTrue(!open(), "Esc should close the popover")
        }
    }

    @Test
    fun captureResultIsOneDismissableLine() {
        var dismissed = 0
        val long = mixState.copy(
            capture = MixerCapture(true, "", "/a/very/long/path/".repeat(8) + "capture.json", 2, stale = false),
        )
        mixerScene(1280, 1000, 1f, feature(AiMixerActions.EMPTY.copy(dismissCapture = { dismissed++ }), state = long)) {
            val line = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith("Wrote 2 rows") } }
            val results = mutableListOf<TextLayoutResult>()
            line.config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(results)
            assertEquals(1, results.single().lineCount)
            assertEquals("Dismiss", description(CAPTURE_DISMISS_TAG))
            tap(CAPTURE_DISMISS_TAG)
            assertEquals(1, dismissed)
        }
    }

    private fun feature(
        actions: AiMixerActions = AiMixerActions.EMPTY,
        agentEdits: Flow<MixControl> = emptyFlow(),
        state: AiMixerUiState = mixState,
    ): AiMixerFeature = object : AiMixerFeature {
        override val stateFlow: StateFlow<AiMixerUiState> = MutableStateFlow(state)
        override val actions = actions
        override val meters: Flow<MeterSnapshot> = MutableStateFlow(testMeters)
        override val agentEdits = agentEdits
    }

    private val testMeters = MeterSnapshot(
        lufsShort = -14.2,
        lufsIntegrated = -14.8,
        preClipPeakDb = -0.6,
        pastKneePct = 2.3,
        correlation = 0.82,
        channels = BenchMeters.CHANNEL_NAMES.mapIndexed { c, name ->
            ChannelMeter(name, relLu = -4.0 - c, peakDb = -3.0 - c * 2.5, pan = 0.0, subPct = 0.0, lowPct = 0.0, wave = FloatArray(0))
        },
        section = 1,
    )

    private val mixState = AiMixerUiState(
        vibeName = "Rust Belt Sunrise",
        sections = listOf("intro", "groove", "drop"),
        section = 1,
        loopSection = 1,
        sectionScopeAvailable = true,
        scope = Scope.SECTION,
        tracks = BenchMeters.CHANNEL_NAMES.take(8).mapIndexed { t, name ->
            MixerTrack(t, name, "SPK/MST", levelDb = -3f, pan = 0.2f, filePan = 0f, reverb = 0.3f, delay = 0.1f,
                muted = false, soloed = false, silenced = false)
        },
        macros = Macro.entries.map {
            MixerMacro(it, 0.6f, 0.5f, changed = it == Macro.ENERGY, note = if (it == Macro.SPACE) "heard 0.66 (x1.20)" else "")
        },
        changes = listOf(
            CaptureRow(Scope.VIBE, null, null, 3, CaptureField.VOLUME, null, 0.5f, 0.315f),
            CaptureRow(Scope.SECTION, 1, "groove", 4, CaptureField.VOLUME, null, 0.9f, 1f, clampedDb = 1.5f),
        ),
        capture = MixerCapture(ok = true, message = "", file = "capture.json", rows = 2, stale = false),
        notice = "This send is 0 here.",
        pins = setOf(3),
        agentTouched = setOf(5),
    )

    /** The panel at [w] x [h] canvas dp (density 1) with the canvas shrunk by [scale]. */
    private fun mixerScene(w: Int, h: Int, scale: Float, feature: AiMixerFeature = feature(), body: Rig.() -> Unit) {
        val scene = ImageComposeScene(w, h, Density(1f)) {
            CompositionLocalProvider(LocalCanvasScale provides scale) {
                OrpheusTheme { AiMixerPanel(feature, Modifier.fillMaxSize()) }
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

    private class Rig(scene: ImageComposeScene) {
        private val touch = Touch(scene)
        private val scene = scene

        fun frame() = touch.frame()

        fun nodes(): List<SemanticsNode> {
            val out = mutableListOf<SemanticsNode>()
            fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
            scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            return out
        }

        fun node(tag: String) = assertNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, tag)

        fun bounds(tag: String): Rect = node(tag).boundsInRoot

        fun texts(): List<Pair<String, Rect>> = nodes().flatMap { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text to n.boundsInRoot }
        }

        fun description(tag: String) = node(tag).config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

        fun <T> mark(tag: String, key: SemanticsPropertyKey<T>): T? = node(tag).config.getOrNull(key)

        fun tap(tag: String) = tapAt(bounds(tag).center)

        fun tapAt(at: Offset) {
            touch.pointer(PointerEventType.Press, at)
            touch.pointer(PointerEventType.Release, at)
            repeat(4) { frame() }
        }

        @OptIn(InternalComposeUiApi::class)
        fun key(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            repeat(4) { frame() }
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

    private companion object {
        /** The Fold inner screen's canvas shrink. */
        const val FOLD_SCALE = 0.652f
    }
}
