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
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** ./gradlew :apps:djapp:shared:jvmTest --tests '*NavTransportRenderHarness*' --rerun */
class NavTransportRenderHarness {
    // A fifth tab stands in for the AI edition's tab, which lives in another module.
    private val withAi = djTabs + VibeInfoTab

    // The preview feature's nav state is empty; name a vibe so the label has something to show.
    // Before Task 9 the scaffold ignores this flow, which is what the baseline wants.
    private val pulsar: PulsarFeature = object : PulsarFeature by PulsarViewModel.previewFeature() {
        override val vibeNavFlow: StateFlow<VibeNavState> =
            MutableStateFlow(VibeNavState("Space & Drift", "Dog House", "Stay Asleep", progress = 0.62f))
    }

    // Playing, so the ring draws its wave (phase 0, full amplitude on the first frame) in the slot.
    private val playingPulsar: PulsarFeature = object : PulsarFeature by pulsar {
        override val stateFlow: StateFlow<PulsarUiState> =
            MutableStateFlow(pulsar.stateFlow.value.copy(globalPaused = false))
    }

    private fun render(
        tag: String, w: Int, h: Int, layout: DjLayout, tabs: List<DjRoute>, feature: PulsarFeature = pulsar,
        fontScale: Float = 1f,
    ) = runCatching {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val scene = ImageComposeScene(w * 2, h * 2, Density(2f, fontScale)) {
            OrpheusTheme {
                DjAppNavScaffold(
                    isSelected = { it == DjTab },
                    onItemClick = {},
                    layout = layout,
                    pulsarFeature = feature,
                    timerFeature = TimerViewModel.previewFeature(),
                    onTogglePlayback = {},
                    tabs = tabs,
                    modifier = Modifier.fillMaxSize().background(Color(0xFF14141F)),
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        try {
            // The rail picks its ring from the height it measured last frame.
            scene.render()
            File(outDir, "nav-$tag.png").writeBytes(scene.render().encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }.onFailure { println("[render-harness] nav $tag skipped: $it") }

    @Test
    fun renderNavLayouts() {
        render("portrait-360", 360, 780, DjLayout.Portrait, djTabs)
        // Six slots at 360dp are narrower than the 64dp ring: it overhangs its slot's gaps.
        render("portrait-360-ai", 360, 780, DjLayout.Portrait, withAi)
        render("portrait-412-ai", 412, 915, DjLayout.Portrait, withAi)
        render("portrait-412", 412, 915, DjLayout.Portrait, djTabs)
        // The Fold 8's cover screen upright at its 1.3 font scale: the name stays on the tab labels' line.
        render("portrait-360-fold", 360, 780, DjLayout.Portrait, djTabs, fontScale = 1.3f)
        render("portrait-360-playing-fold", 360, 780, DjLayout.Portrait, djTabs, playingPulsar, fontScale = 1.3f)
        render("rail-737x606", 737, 606, DjLayout.Landscape, djTabs)
        render("rail-915x412-ai", 915, 412, DjLayout.Landscape, withAi)
        render("rail-915x412", 915, 412, DjLayout.Landscape, djTabs)
        render("portrait-360-playing", 360, 780, DjLayout.Portrait, djTabs, playingPulsar)
        render("rail-737x606-playing", 737, 606, DjLayout.Landscape, djTabs, playingPulsar)
        render("rail-915x412-playing", 915, 412, DjLayout.Landscape, djTabs, playingPulsar)
        // The Fold 8's cover screen sideways at its 1.3 font scale: the compact ring keeps the title whole.
        render("rail-840x360-fold", 840, 360, DjLayout.Landscape, djTabs, fontScale = 1.3f)
        render("rail-737x606-fold", 737, 606, DjLayout.Landscape, djTabs, fontScale = 1.3f)
        render("rail-915x412-fold", 915, 412, DjLayout.Landscape, djTabs, fontScale = 1.3f)
    }

    // Long enough to overflow the rail's ~72dp label and the phone bar's weighted slot.
    private val longNamePulsar: PulsarFeature = object : PulsarFeature by playingPulsar {
        override val vibeNavFlow: StateFlow<VibeNavState> =
            MutableStateFlow(VibeNavState("Kaleidoscope Drift", "Dog House", "Stay Asleep", progress = 0.62f))
    }

    // The same name paused: it scrolls as it does playing, while the ring holds and zips.
    private val pausedLongNamePulsar: PulsarFeature = object : PulsarFeature by pulsar {
        override val vibeNavFlow: StateFlow<VibeNavState> = longNamePulsar.vibeNavFlow
    }

    /**
     * The transport's resting label marquee-scrolling, playing and paused: rail (737x606) rest +
     * mid-scroll, and the phone bar (360) rest + mid-scroll. Rest is before the first pass, where
     * the first letter must be crisp. Stepped by hand so the marquee's animation actually progresses.
     */
    @Test
    fun renderMarqueeScrolling() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        listOf(
            Triple("rail-737x606", 737, 606) to DjLayout.Landscape,
            Triple("portrait-360", 360, 780) to DjLayout.Portrait,
        ).flatMap { listOf(it to "", it to "-paused") }.forEach { (screen, state) ->
            val (dims, layout) = screen
            val (screenTag, w, h) = dims
            val tag = screenTag + state
            val feature = if (state.isEmpty()) longNamePulsar else pausedLongNamePulsar
            runCatching {
                val scene = ImageComposeScene(w * 2, h * 2, Density(2f)) {
                    OrpheusTheme {
                        DjAppNavScaffold(
                            isSelected = { it == DjTab },
                            onItemClick = {},
                            layout = layout,
                            pulsarFeature = feature,
                            timerFeature = TimerViewModel.previewFeature(),
                            onTogglePlayback = {},
                            tabs = djTabs,
                            modifier = Modifier.fillMaxSize().background(Color(0xFF14141F)),
                        ) { Box(Modifier.fillMaxSize()) }
                    }
                }
                try {
                    (0L..1_088L step 16).forEach { scene.render(it * 1_000_000) }
                    File(outDir, "nav-$tag-marquee-rest.png")
                        .writeBytes(scene.render(1_104L * 1_000_000).encodeToData()!!.bytes)
                    (1_120L..2_000L step 16).forEach { scene.render(it * 1_000_000) }
                    File(outDir, "nav-$tag-marquee-scroll.png")
                        .writeBytes(scene.render(2_016L * 1_000_000).encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure { println("[render-harness] nav marquee $tag skipped: $it") }
        }
    }

    /**
     * The transport held down (the dome's round light, where the theme's square ripple was) and held
     * by the keyboard (the focus mark), in the phone bar and the rail, each cropped around the
     * transport at 2x: `nav-*-pressed.png` and `nav-*-focused.png`.
     */
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun renderPressedAndFocused() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        listOf(Triple("portrait-360", 360, 780) to DjLayout.Portrait, Triple("rail-737x606", 737, 606) to DjLayout.Landscape)
            .flatMap { listOf(it to "pressed", it to "focused") }
            .forEach { (screen, state) ->
                val (dims, layout) = screen
                val (screenTag, w, h) = dims
                runCatching {
                    val scene = ImageComposeScene(w * 2, h * 2, Density(2f)) {
                        OrpheusTheme {
                            DjAppNavScaffold(
                                isSelected = { it == DjTab }, onItemClick = {}, layout = layout, pulsarFeature = pulsar,
                                timerFeature = TimerViewModel.previewFeature(), onTogglePlayback = {}, tabs = djTabs,
                                modifier = Modifier.fillMaxSize().background(Color(0xFF14141F)),
                            ) { Box(Modifier.fillMaxSize()) }
                        }
                    }
                    try {
                        var t = 1_000L
                        fun frames(count: Int) = repeat(count) { t += 16; scene.render(t * 1_000_000).close() }
                        frames(3)
                        fun transport(): SemanticsNode {
                            fun collect(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::collect)
                            return scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }.first { n ->
                                n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.endsWith(", Space & Drift") }
                            }
                        }
                        val box = transport().let { Rect(it.positionInRoot, it.size.toSize()) }
                        val ring = if (layout == DjLayout.Portrait) BarRingSize else RailRingSize
                        val centre = Offset(box.center.x, box.top + (TransportPadding.value + ring.value / 2) * 2)
                        if (state == "pressed") {
                            scene.sendPointerEvent(PointerEventType.Press, centre, timeMillis = t, button = PointerButton.Primary)
                        } else {
                            var tabs = 0
                            while (transport().config.getOrNull(SemanticsProperties.Focused) != true && tabs++ < 8) {
                                scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyDown))
                                scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyUp))
                                frames(1)
                            }
                        }
                        frames(8)
                        val full = ImageIO.read(ByteArrayInputStream(scene.render(t * 1_000_000).encodeToData()!!.bytes))
                        val crop = box.inflate(24f * 2)
                        val left = crop.left.toInt().coerceAtLeast(0)
                        val top = crop.top.toInt().coerceAtLeast(0)
                        ImageIO.write(
                            full.getSubimage(left, top, minOf(crop.width.toInt(), full.width - left), minOf(crop.height.toInt(), full.height - top)),
                            "png", File(outDir, "nav-$screenTag-$state.png"),
                        )
                    } finally {
                        scene.close()
                    }
                }.onFailure { println("[render-harness] nav $screenTag $state skipped: $it") }
            }
    }
}
