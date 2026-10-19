package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.PULSAR_TRACK_NAMES
import org.balch.orpheus.features.pulsar.PULSAR_TRACK_STRIP_NAMES
import org.balch.orpheus.features.pulsar.mixer.LocalBusMeterFrameProbe
import org.balch.orpheus.features.pulsar.mixer.MixerFeature
import org.balch.orpheus.features.pulsar.mixer.MixerPanelActions
import org.balch.orpheus.features.pulsar.mixer.MixerUiState
import org.balch.orpheus.features.pulsar.mixer.TrackTuning
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.LocalTvFocusChrome
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The TRACKS view across every DJ app form factor: fit, names, D-pad, semantics and meter cost. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class TracksLayoutTest {
    private val mutes = mutableListOf<Int>()
    private val levels = mutableListOf<Pair<Int, Float>>()
    private var resets = 0

    private val names = PULSAR_TRACK_STRIP_NAMES

    private fun feature(
        tuning: TrackTuning = TrackTuning().withLevel(0, 1.0f),
        state: StateFlow<MixerUiState> = MutableStateFlow(MixerUiState(tuning = tuning)),
    ) = object : MixerFeature {
        override val stateFlow = state
        override val actions = MixerPanelActions(
            setGroupGain = { _, _ -> },
            setDrive = {},
            toggleTrackMute = { mutes += it },
            setTrackLevel = { t, v -> levels += t to v },
            resetTracks = { resets++ },
        )
    }

    private fun scene(
        width: Int,
        height: Int,
        feature: MixerFeature = feature(),
        tv: Boolean = false,
        fontScale: Float = 1f,
        fillHeight: Boolean = false,
        content: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ) = ImageComposeScene(width, height, Density(1f, fontScale)) {
        OrpheusTheme {
            CompositionLocalProvider(LocalShowTuning provides true, LocalTvFocusChrome provides tv) {
                content {
                    DjMixPanel(
                        feature = feature,
                        trackVizFlows = List(8) { MutableStateFlow(FloatArray(0)) },
                        showExpandedTitle = false,
                        fillHeight = fillHeight,
                        startOnTracks = true,
                    )
                }
            }
        }
    }

    /**
     * What a TV platform does with an arrow no control consumed: search focus that way. The Compose
     * desktop scene does not, so an unconsumed arrow reaching this root moves focus geometrically.
     */
    @Composable
    private fun TvRemote(content: @Composable () -> Unit) {
        val focus = LocalFocusManager.current
        Box(
            Modifier.onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> focus.moveFocus(FocusDirection.Right)
                    Key.DirectionLeft -> focus.moveFocus(FocusDirection.Left)
                    Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                    Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                    else -> false
                }
            },
        ) { content() }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }

    private fun SemanticsNode.description() = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun SemanticsNode.described(prefix: String) = description().any { it.startsWith(prefix) }

    private fun SemanticsNode.layout(): TextLayoutResult? {
        val out = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.firstOrNull()
    }

    /** True when the ellipsis took over part of the text. */
    private fun TextLayoutResult.isCut() = getLineEnd(0, visibleEnd = true) < layoutInput.text.length

    /** True when the line's glyphs end inside the text's box, i.e. nothing is clipped. */
    private fun TextLayoutResult.fitsItsBox() = getLineRight(0) <= size.width + 0.5f

    private fun ImageComposeScene.tap(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, at); render()
        sendPointerEvent(PointerEventType.Release, at); render()
    }

    private fun ImageComposeScene.key(key: Key) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); render()
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); render()
    }

    private fun ImageComposeScene.focusedKey(): String? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?.let { it.description().firstOrNull() ?: it.text() }

    /** Tabs to a control; later steps use only the arrows a TV remote has. */
    private fun ImageComposeScene.focusOn(key: String) {
        var guard = 0
        while (focusedKey() != key) {
            check(guard++ < 80) { "never focused $key (at ${focusedKey()})" }
            key(Key.Tab)
        }
    }

    private fun save(scene: ImageComposeScene, name: String) = runCatching {
        val dir = File("build/tracks-render").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(scene.render().encodeToData()!!.bytes)
    }

    private fun assertDisjoint(rects: List<Pair<String, Rect>>) {
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val (an, a) = rects[i]
            val (bn, b) = rects[j]
            assertTrue(!a.overlaps(b), "$an $a overlaps $bn $b")
        }
    }

    /** Every labelled node sits whole inside a [height]dp slot: nothing below the edge or squeezed flat. */
    private fun ImageComposeScene.assertNothingClips(height: Int, what: String) {
        val labelled = nodes().filter { it.text().isNotBlank() || it.description().isNotEmpty() }
        // BAND is the smaller view: 5 strips' labels and values, faders and the view toggle.
        assertTrue(labelled.size > 10, "$what: only ${labelled.size} labelled nodes")
        for (n in labelled) {
            val b = n.boundsInRoot
            val name = n.text().ifBlank { n.description().first() }
            assertTrue(b.top >= -0.5f && b.bottom <= height + 0.5f, "$what: '$name' $b leaves the $height dp slot")
            assertTrue(b.height > 4f, "$what: '$name' is squeezed to ${b.height} px")
        }
    }

    /** The slot the DJ app gives Mix: a filling panel (as the nav destination) inside a [height]dp box. */
    private fun slotScene(height: Int, fontScale: Float = 1f) =
        scene(466, height, fontScale = fontScale, fillHeight = true)

    @Test
    fun tracksFitTheShortestRealMixSlot() {
        // 205dp is the closed iPhone Duo's Mix slot; 160dp is the bottom of the compact sweep.
        for (height in listOf(205, 160)) {
            val scene = slotScene(height)
            try {
                scene.render()
                save(scene, "tracks-slot-$height")
                scene.assertNothingClips(height, "TRACKS at $height")
                assertEquals(8, scene.nodes().count { it.described("Level ") })
                assertTrue(scene.nodes().filter { it.described("Level ") }.all { it.boundsInRoot.height >= 24f })
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun busFitsTheShortestRealMixSlot() {
        for (height in listOf(205, 160)) {
            val scene = slotScene(height)
            try {
                scene.render()
                scene.tap(scene.nodes().first { it.text() == "BAND" })
                save(scene, "bus-slot-$height")
                scene.assertNothingClips(height, "BAND at $height")
                assertTrue(scene.nodes().any { it.text() == "GAIN" })
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun largeFontStillFitsTheDuoSlot() {
        val scene = slotScene(205, fontScale = 1.3f)
        try {
            scene.render()
            save(scene, "tracks-slot-205-font1.3")
            scene.assertNothingClips(205, "TRACKS at 205, 1.3x font")
            scene.tap(scene.nodes().first { it.text() == "BAND" })
            scene.assertNothingClips(205, "BAND at 205, 1.3x font")
        } finally {
            scene.close()
        }
    }

    /** The real Landscape branch with Mix in its .4 slot, as the nav destination fills it. */
    private fun landscapeScene(width: Int, height: Int, fontScale: Float, mix: MixerFeature = feature()) =
        ImageComposeScene(width, height, Density(1f, fontScale)) {
            OrpheusTheme {
                CompositionLocalProvider(LocalShowTuning provides true) {
                    val pulsar = PulsarViewModel.previewFeature()
                    DjAppNavScaffold(
                        isSelected = { it == MixTab },
                        onItemClick = {},
                        layout = DjLayout.Landscape,
                        pulsarFeature = pulsar,
                        timerFeature = TimerViewModel.previewFeature(),
                        onTogglePlayback = {},
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        DjAppMainContent(
                            layout = DjLayout.Landscape,
                            dockedPanels = emptyList(),
                            pairPanels = emptyList(),
                            pulsarFeature = pulsar,
                            synthEngine = RenderProbeSynthEngine(),
                            vizFeature = VizViewModel.previewFeature(),
                            onShowVibeInfo = {},
                            routePanel = { _, _, _ -> },
                            navContent = { mod ->
                                Column(modifier = mod) {
                                    DjMixPanel(
                                        feature = mix,
                                        trackVizFlows = List(8) { MutableStateFlow(FloatArray(0)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        showExpandedTitle = false,
                                        startOnTracks = true,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

    @Test
    fun tracksAndBusFitTheRealLandscapeSlot() {
        // 780x360 is a phone sideways; 840x360 at 1.3x is the Fold cover screen sideways.
        for ((w, h, font) in listOf(Triple(780, 360, 1f), Triple(840, 360, 1.3f))) {
            val scene = landscapeScene(w, h, font)
            try {
                repeat(3) { scene.render() }
                save(scene, "landscape-screen-${w}x$h")
                var strips = scene.nodes().filter { it.described("Strip ") }
                assertEquals(8, strips.size, "${w}x$h")
                for (view in listOf("TRACKS", "BAND")) {
                    scene.tap(scene.nodes().first { it.text() == view })
                    repeat(2) { scene.render() }
                    val mix = scene.nodes().filter { n ->
                        (n.text() in listOf("1.0", "1.0x", "GAIN", "RESET") || n.described("Level ") || n.described("Mute "))
                    }
                    assertTrue(mix.isNotEmpty(), "$view: no Mix nodes")
                    for (n in mix) {
                        val b = n.boundsInRoot
                        assertTrue(b.bottom <= h.toFloat() + 0.5f && b.height > 4f, "$view ${w}x$h: ${n.text().ifBlank { n.description().first() }} $b")
                    }
                }
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun trackNamesFitTheirStripsAt360dp() {
        val scene = scene(360, 360)
        try {
            scene.render()
            save(scene, "phone-360")
            val nodes = scene.nodes()
            // Every strip name shows whole, never cut or clipped.
            for (name in names) {
                val label = nodes.first { it.text() == name && it.layout() != null }
                val layout = label.layout()!!
                assertTrue(layout.fitsItsBox() && !layout.isCut(), "$name is cut off at 360dp")
            }
            // No strip overlaps a neighbour, and none run off the screen.
            val strips = nodes.filter { it.described("Strip ") }
            assertEquals(8, strips.size)
            assertDisjoint(strips.map { it.description().first() to it.boundsInRoot })
            assertTrue(strips.all { it.boundsInRoot.left >= 0f && it.boundsInRoot.right <= 360f })
            val scroll = nodes.first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
                .config[SemanticsProperties.HorizontalScrollAxisRange]
            assertEquals(0f, scroll.maxValue(), "the strips scroll at 360dp")
        } finally {
            scene.close()
        }
    }

    @Test
    fun largeFontKeepsNamesInsideTheirStrips() {
        val scene = scene(360, 360, fontScale = 1.3f)
        try {
            scene.render()
            save(scene, "phone-360-font1.3")
            val nodes = scene.nodes()
            for (label in nodes.filter { it.text() in names && it.layout() != null }) {
                assertTrue(label.layout()!!.fitsItsBox(), "${label.text()} is clipped at 1.3x font")
            }
            val strips = nodes.filter { it.described("Strip ") }
            assertEquals(8, strips.size)
            assertDisjoint(strips.map { it.description().first() to it.boundsInRoot })
            assertTrue(strips.all { it.boundsInRoot.left >= 0f && it.boundsInRoot.right <= 360f })
        } finally {
            scene.close()
        }
    }

    @Test
    fun fitsLandscapePhone() {
        val scene = scene(780, 360)
        try {
            scene.render()
            save(scene, "landscape-780x360")
            val nodes = scene.nodes()
            val toggle = nodes.first { it.text() == "TRACKS" }.boundsInRoot
            val reset = nodes.first { it.text() == "RESET" }.boundsInRoot
            val faders = nodes.filter { it.described("Level ") }
            assertEquals(8, faders.size)
            assertTrue(toggle.top >= 0f)
            assertTrue(reset.bottom <= 360f, "RESET ends at ${reset.bottom}")
            assertTrue(faders.all { it.boundsInRoot.bottom <= 360f }, "a fader runs below a 360dp screen")
            val labels = nodes.filter { it.text() in names }
            assertTrue(labels.all { it.boundsInRoot.bottom <= 360f })
        } finally {
            scene.close()
        }
    }

    @Test
    fun fitsUnfoldedAndTablet() {
        // The last is a television canvas, with its focus chrome on.
        for ((w, h) in listOf(840 to 900, 1280 to 800, 1280 to 720)) {
            val scene = scene(w, h, tv = h == 720)
            try {
                scene.render()
                save(scene, "wide-${w}x$h")
                val strips = scene.nodes().filter { it.described("Strip ") }
                assertEquals(8, strips.size, "${w}x$h")
                assertDisjoint(strips.map { it.description().first() to it.boundsInRoot })
                assertTrue(strips.all { it.boundsInRoot.right <= w && it.boundsInRoot.bottom <= h }, "${w}x$h")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun dpadReachesEveryControl() {
        // A touched mix, so RESET is enabled and focusable.
        val scene = scene(360, 360, feature = feature(tuning = TrackTuning().toggleMute(2)), tv = true)
        try {
            scene.render()
            val seen = mutableSetOf<String>()
            fun focused() = scene.nodes().filter { it.config.getOrNull(SemanticsProperties.Focused) == true }
            // Tab is the generic "next" traversal a D-pad remote shares with a keyboard.
            repeat(80) {
                scene.key(Key.Tab)
                focused().forEach { n ->
                    seen += n.description().firstOrNull() ?: n.text()
                }
            }
            // Semantics keep the full track names; only the visible strip label is short.
            for (name in PULSAR_TRACK_NAMES) {
                assertTrue("Mute $name" in seen, "M of $name unreachable: $seen")
                assertTrue("Solo $name" in seen, "S of $name unreachable")
                assertTrue("Level $name" in seen, "fader of $name unreachable")
            }
            assertTrue("RESET" in seen || "Reset tracks" in seen, "RESET unreachable: $seen")
        } finally {
            scene.close()
        }
    }

    @Test
    fun arrowsWalkAcrossAStrip() {
        val scene = scene(360, 360, tv = true) { panel -> TvRemote(panel) }
        try {
            scene.render()
            scene.focusOn("Mute KICK")
            scene.key(Key.DirectionRight)
            assertEquals("Solo KICK", scene.focusedKey())
            scene.key(Key.DirectionRight)
            assertEquals("Mute PERC", scene.focusedKey())
            scene.key(Key.DirectionLeft)
            assertEquals("Solo KICK", scene.focusedKey())
        } finally {
            scene.close()
        }
    }

    @Test
    fun downFromTheToggleRowReachesAStrip() {
        val scene = scene(360, 360, tv = true) { panel -> TvRemote(panel) }
        try {
            scene.render()
            scene.focusOn("TRACKS")
            scene.key(Key.DirectionDown)
            val focus = scene.focusedKey().orEmpty()
            assertTrue(
                focus.startsWith("Mute ") || focus.startsWith("Solo ") || focus.startsWith("Level "),
                "Down from the toggle row landed on '$focus'",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun downFromMuteAndSoloReachesTheFader() {
        val scene = scene(360, 360, tv = true) { panel -> TvRemote(panel) }
        try {
            scene.render()
            scene.focusOn("Mute KICK")
            scene.key(Key.DirectionDown)
            assertEquals("Level KICK", scene.focusedKey())
            scene.key(Key.DirectionUp)
            assertEquals("Mute KICK", scene.focusedKey())
            scene.focusOn("Solo KICK")
            scene.key(Key.DirectionDown)
            assertEquals("Level KICK", scene.focusedKey())
        } finally {
            scene.close()
        }
    }

    @Test
    fun rightFromAnIdleFaderMovesFocusAndLeavesTheLevel() {
        val scene = scene(360, 360, tv = true) { panel -> TvRemote(panel) }
        try {
            scene.render()
            scene.focusOn("Level KICK")
            levels.clear()
            scene.key(Key.DirectionRight)
            val focus = scene.focusedKey().orEmpty()
            assertTrue(focus != "Level KICK" && focus.endsWith("PERC"), "focus stayed or jumped: '$focus'")
            assertTrue(levels.isEmpty(), "an idle fader changed level: $levels")
        } finally {
            scene.close()
        }
    }

    @Test
    fun theLastStripIsNotAFocusTrap() {
        val scene = scene(360, 360, tv = true) { panel -> TvRemote(panel) }
        try {
            scene.render()
            scene.focusOn("Level FX")
            scene.key(Key.DirectionRight)
            scene.key(Key.DirectionRight)
            // Right may have nowhere to go, but focus must still be free to leave upward.
            val before = scene.focusedKey()
            scene.key(Key.DirectionUp)
            assertTrue(scene.focusedKey() != before, "Up could not leave '$before'")
            scene.key(Key.DirectionLeft)
            assertTrue(scene.focusedKey() != null)
        } finally {
            scene.close()
        }
    }

    @Test
    fun rightReachesStripEightInANarrowSlotAndScrollsItIn() {
        val scene = scene(360, 360, tv = true) { panel ->
            TvRemote { Box(Modifier.width(300.dp)) { panel() } }
        }
        try {
            scene.render()
            save(scene, "slot-300")
            val scrollNode = scene.nodes().first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
            val scroll = scrollNode.config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue(scroll.maxValue() > 0f, "the strips do not scroll in a 300dp slot")
            assertTrue(scrollNode.boundsInRoot.right <= 300f + 0.5f, "the page grew past the slot: ${scrollNode.boundsInRoot}")
            scene.focusOn("Level KICK")
            var guard = 0
            while (scene.focusedKey()?.endsWith("FX") != true) {
                check(guard++ < 40) { "Right never reached strip 8 (at ${scene.focusedKey()})" }
                scene.key(Key.DirectionRight)
            }
            // The bring-into-view scroll animates, so give it a second of frames.
            var t = System.nanoTime()
            repeat(60) { t += 16_000_000L; scene.render(t) }
            assertTrue(scene.nodes().first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
                .config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f, "focus did not scroll strip 8 into view")
            val strip = scene.nodes().first { it.described("Strip FX") }
            assertTrue(strip.boundsInRoot.right <= 300f + 0.5f, "strip 8 is still off the slot: ${strip.boundsInRoot}")
        } finally {
            scene.close()
        }
    }

    @Test
    fun aDpadStepAcrossUnityLandsOnUnity() {
        // KICK sits just below unity; one step up would pass 1.0 by a hair.
        val scene = scene(360, 360, feature = feature(tuning = TrackTuning().withLevel(0, 0.98f)), tv = true)
        try {
            scene.render()
            scene.focusOn("Level KICK")
            scene.key(Key.DirectionCenter)
            levels.clear()
            scene.key(Key.DirectionUp)
            assertEquals(TrackTuning.UNITY, levels.single().second, 1e-4f, "the step skipped past unity")
        } finally {
            scene.close()
        }
    }

    @Test
    fun dpadAdjustsATrackFaderOnlyInAdjustMode() {
        // Counts Escape presses the fader let bubble up, as the window's Back handler would see them.
        var escapesReachingRoot = 0
        val scene = scene(360, 360, tv = true) { panel ->
            Box(
                Modifier.onKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) escapesReachingRoot++
                    false
                },
            ) { panel() }
        }
        try {
            scene.render()
            // Focused but not adjusting, the fader leaves the arrows to focus traversal.
            scene.focusOn("Level KICK")
            levels.clear()
            scene.key(Key.DirectionUp)
            assertTrue(levels.isEmpty(), "an arrow moved the fader before adjust mode: $levels")
            // Center enters adjust mode; up raises the KICK level by a fraction of the 0..1.5 range.
            scene.key(Key.DirectionCenter)
            save(scene, "tv-adjusting-360")
            levels.clear()
            scene.key(Key.DirectionUp)
            assertEquals(1, levels.size)
            val (track, level) = levels.single()
            assertEquals(0, track)
            assertTrue(level > 1.0f && level <= 1.5f, "level $level")
            scene.key(Key.DirectionDown)
            assertTrue(levels.last().second < 1.0f + 0.001f && levels.last().second > 0.9f)
            // Escape leaves adjust mode and is consumed; a second one is not.
            scene.key(Key.Escape)
            assertEquals(0, escapesReachingRoot, "Escape that left adjust mode was not consumed")
            levels.clear()
            scene.key(Key.DirectionUp)
            assertTrue(levels.isEmpty(), "adjust mode survived Escape")
            scene.focusOn("Level KICK")
            scene.key(Key.Escape)
            assertEquals(1, escapesReachingRoot, "a second Escape was swallowed")
        } finally {
            scene.close()
        }
    }

    @Test
    fun semanticsDescribeStrips() {
        val tuning = TrackTuning().toggleMute(0).toggleSolo(1).withLevel(2, 0.75f)
        val scene = scene(360, 360, feature = feature(tuning = tuning))
        try {
            scene.render()
            val nodes = scene.nodes()
            fun state(desc: String) = nodes.first { it.description().contains(desc) }
                .config.getOrNull(SemanticsProperties.StateDescription)
            assertEquals("on", state("Mute KICK"))
            assertEquals("off", state("Mute PERC"))
            assertEquals("on", state("Solo PERC"))
            assertEquals("off", state("Solo KICK"))
            val fader = nodes.first { it.description().contains("Level HIHAT") }
            val range = fader.config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(0.75f, range.current, 0.001f)
            assertEquals(0f..TrackTuning.MAX_LEVEL, range.range)
            val strip = nodes.first { it.described("Strip KICK") }
            assertEquals(listOf("Strip KICK"), strip.description())
        } finally {
            scene.close()
        }
    }

    @Test
    fun muteChipsLiveOnTracksOnly() {
        val scene = scene(360, 360)
        try {
            scene.render()
            val onTracks = scene.nodes().flatMap { it.description() }.filter { it.startsWith("Mute ") }
            scene.tap(scene.nodes().first { it.text() == "BAND" })
            val onBus = scene.nodes().flatMap { it.description() }.filter { it.startsWith("Mute ") }
            assertEquals(8, onTracks.size)
            assertTrue(onBus.isEmpty(), "BAND shows mute chips: $onBus")
        } finally {
            scene.close()
        }
    }

    @Test
    fun resetShowsOnBusOnlyOnceTheMixIsTouched() {
        // Untouched: RESET belongs to TRACKS alone.
        val clean = scene(360, 360)
        try {
            clean.render()
            clean.tap(clean.nodes().first { it.text() == "BAND" })
            assertTrue(clean.nodes().none { it.text() == "RESET" }, "RESET on an untouched BAND")
        } finally {
            clean.close()
        }
        // Touched: a TRACKS edit stays resettable from BAND, and the tap resets.
        val touched = scene(360, 360, feature = feature(tuning = TrackTuning().toggleMute(2)))
        try {
            touched.render()
            touched.tap(touched.nodes().first { it.text() == "BAND" })
            val reset = touched.nodes().first { it.text() == "RESET" }
            resets = 0
            touched.tap(reset)
            assertEquals(1, resets)
        } finally {
            touched.close()
        }
    }

    @Test
    fun busMeterLoopStopsOnTracks() {
        var busFrames = 0
        val scene = scene(360, 360) { panel ->
            CompositionLocalProvider(LocalBusMeterFrameProbe provides { busFrames++ }) { panel() }
        }
        try {
            var t = 0L
            fun frames(n: Int) = repeat(n) { t += 16_000_000L; scene.render(t) }
            frames(30)
            assertEquals(0, busFrames, "the BAND meter loop runs while TRACKS shows")
            scene.tap(scene.nodes().first { it.text() == "BAND" })
            frames(30)
            assertTrue(busFrames > 10, "the BAND meter loop never ran on BAND ($busFrames)")
            val onBus = busFrames
            scene.tap(scene.nodes().first { it.text() == "TRACKS" })
            frames(30)
            assertTrue(busFrames - onBus <= 2, "the BAND loop kept running after TRACKS returned")
        } finally {
            scene.close()
        }
    }
}
