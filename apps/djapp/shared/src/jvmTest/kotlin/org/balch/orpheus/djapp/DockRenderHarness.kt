package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.features.pulsar.MusicPulse
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.SongStory
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.features.visualizations.viz.GalaxyViz
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.VizBackground
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.test.Test

/**
 * The redesigned dock as DjAppScreen builds it, over GalaxyViz with Pulsar, DJ and Mix docked:
 * desktop windows at 1280x720, 1512x982 and 1920x1080 (playing and paused), television hardware at
 * 1280x720, and iPad-sized docks at 1032x1376 and 1366x1024. Each full render has close crops of the
 * top bar, the song band and the centre dome, and prints the dome, each docked panel and every
 * clickable within 8dp of the dome, in dp.
 *
 * Writes build/djapp-render/dock-*.png and asserts nothing.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*DockRenderHarness*' --rerun
 */
class DockRenderHarness {
    private val density = 2f

    private enum class Host { Desktop, Tablet, Television }

    private data class Case(
        val widthDp: Int,
        val heightDp: Int,
        val host: Host,
        val paused: Boolean,
        val focused: Boolean = false,
        // Held down on the ring as the frame is taken: the dome's round light.
        val pressed: Boolean = false,
        val name: String = BreakdownDropNav.currentName,
    ) {
        val tag = "dock-${widthDp}x$heightDp" + when (host) {
            Host.Desktop -> ""
            Host.Tablet -> "-tablet"
            Host.Television -> "-tv"
        } + (if (paused) "-paused" else "-playing") + (if (focused) "-focused" else "") + (if (pressed) "-pressed" else "") +
            (if (name == BreakdownDropNav.currentName) "" else "-" + name.filter { it.isLetter() }.lowercase())
    }

    @Test
    fun renderTheDock() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        buildList {
            listOf(1280 to 720, 1512 to 982, 1920 to 1080).forEach { (w, h) ->
                add(Case(w, h, Host.Desktop, paused = false))
                add(Case(w, h, Host.Desktop, paused = true))
            }
            add(Case(1512, 982, Host.Desktop, paused = false, focused = true))
            add(Case(1512, 982, Host.Desktop, paused = false, pressed = true))
            add(Case(1280, 720, Host.Desktop, paused = false, pressed = true))
            // A mid-size raised ring under a name far wider than it.
            add(Case(1400, 860, Host.Desktop, paused = false, name = "Space & Drift"))
            add(Case(1280, 720, Host.Television, paused = false))
            add(Case(1280, 720, Host.Television, paused = false, focused = true))
            add(Case(1032, 1376, Host.Tablet, paused = false))
            add(Case(1366, 1024, Host.Tablet, paused = false))
        }.forEach { case ->
            runCatching { render(case, outDir) }.onFailure {
                if (it is IllegalStateException) throw it
                println("[dock] ${case.tag} skipped: $it")
            }
        }
        println("[dock] wrote PNGs to ${outDir.absolutePath}")
    }

    /**
     * The top bar alone at every width the dock is laid out at, with the catalog's longest vibe name
     * and the longest ending style, Pulsar and Ends docked: what gives way first as the bar narrows.
     * Also the narrowest bar at the preview's own values, as `dock-topbar-900-preview.png`.
     */
    @Test
    fun renderTheTopBarAtEveryWidth() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val base = PulsarViewModel.previewFeature()
        val longest = object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> =
                MutableStateFlow(base.stateFlow.value.copy(vibe = base.vibeList.maxBy { it.name.length }))
            override val actions: PulsarPanelActions = base.actions.copy(
                songEndingEnabled = MutableStateFlow(true),
                transitionSpec = MutableStateFlow(TransitionSpec(style = TransitionStyle.CROSSFADE)),
            )
        }
        val cases = listOf(900, 1032, 1280, 1512, 1920).map { Triple(it, longest, "dock-topbar-$it") } +
            Triple(900, base, "dock-topbar-900-preview")
        cases.forEach { (width, pulsar, tag) ->
            runCatching {
                val scene = ImageComposeScene((width * density).toInt(), (80 * density).toInt(), Density(density)) {
                    OrpheusTheme {
                        Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                            DjTvTopBar(
                                panels = topBarPanels(largeScreenPanels()),
                                isDocked = { it == PulsarTab || it == EndsTab },
                                onToggle = {},
                                vizFeature = VizViewModel.previewFeature(),
                                pulsarFeature = pulsar,
                            )
                        }
                    }
                }
                try {
                    scene.render()
                    File(outDir, "$tag.png").writeBytes(scene.render().encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure {
                if (it is IllegalStateException) throw it
                println("[dock] top bar $width skipped: $it")
            }
        }
    }

    private fun pulsar(paused: Boolean, name: String): PulsarFeature {
        val base = PulsarViewModel.previewFeature()
        return object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(base.stateFlow.value.copy(globalPaused = paused))
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(BreakdownDropNav.copy(currentName = name))
            override val songStoryFlow: StateFlow<SongStory> = MutableStateFlow(BreakdownDropStory)
            override val musicPulseFlow: StateFlow<MusicPulse> =
                MutableStateFlow(MusicPulse(0.9f, FloatArray(8).also { it[0] = 0.9f; it[3] = 0.3f }, 0.25f, 469f))
        }
    }

    /** DesktopCanvasScale's density scale for a desktop window; tablets and TV lay out at their own dp. */
    private fun canvasScale(case: Case): Float = if (case.host != Host.Desktop) 1f else largeScreenDensityScale(
        case.widthDp.toFloat(), case.heightDp.toFloat(), minOf(case.widthDp, case.heightDp), isTelevision = false,
        minScale = DesktopMinimumDensityScale,
    )

    private fun render(case: Case, outDir: File) {
        val docked = listOf(PulsarTab, DjTab, MixTab)
        val panels = mutableMapOf<DjRoute, Rect>()
        val scheduler = TestCoroutineScheduler()
        val region = TvFocusRegionHolder()
        val pulsar = pulsar(case.paused, case.name)
        val tv = case.host == Host.Television
        var ring = 0.dp
        val scene = ImageComposeScene(
            (case.widthDp * density).toInt(), (case.heightDp * density).toInt(), Density(density), StandardTestDispatcher(scheduler),
        ) {
            OrpheusTheme {
                val liquidState = rememberLiquidState()
                CompositionLocalProvider(
                    LocalLiquidState provides liquidState,
                    LocalDensity provides Density(density * canvasScale(case)),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        VizBackground(
                            modifier = Modifier.fillMaxSize().liquefiable(liquidState),
                            selectedViz = GalaxyViz(RenderProbeSynthEngine(), DockRenderDispatchers),
                        )
                        DjAppTvChrome(
                            tvHardware = tv,
                            domeRingSize = dockDomeRingSize(television = tv).also { ring = it },
                            barGlass = !tv,
                            vizHidesPanelsWhenIdle = false,
                            focusRegion = region,
                            vizFeature = VizViewModel.previewFeature(),
                            pulsarFeature = pulsar,
                            timerFeature = TimerViewModel.previewFeature(),
                            onTogglePlayback = {},
                            dockablePanels = largeScreenPanels(),
                            dockedPanels = docked,
                            activeSheet = null,
                            tabs = djTabs,
                            onToggleDocked = {},
                            onActiveSheetChange = {},
                            stage = {
                                DjPanelDock(panels = docked, modifier = Modifier.fillMaxSize()) { route, mod ->
                                    PreviewRoutePanel(route, mod.onGloballyPositioned { panels[route] = it.boundsInRoot() })
                                }
                            },
                        )
                    }
                }
            }
        }
        try {
            // From about a second in: frame loops read a zero timestamp as unset. A second of frames
            // lets the wave rise and, paused, the zip get well into its pass.
            var t = 1_000_000_000L
            fun frames(count: Int) = repeat(count) {
                Snapshot.sendApplyNotifications()
                scheduler.advanceTimeBy(16)
                scheduler.runCurrent()
                scene.render(t).close()
                t += 16_000_000L
            }
            frames(60)
            // The launch focus ring, or the dock as it settles once the ring has faded.
            if (!case.focused) runBlocking { region.alpha.snapTo(0f) }
            val scale = density * canvasScale(case)
            if (case.pressed) {
                val box = checkNotNull(domeRect(scene, case.name)) { "no dome to press" }
                val ringCentre = Offset(box.center.x, box.top + (TransportPadding.value + ring.value / 2) * scale)
                scene.sendPointerEvent(PointerEventType.Press, ringCentre, timeMillis = t / 1_000_000, button = PointerButton.Primary)
                frames(8)
            }
            Snapshot.sendApplyNotifications()
            val png = scene.render(t).encodeToData()!!.bytes
            File(outDir, "${case.tag}.png").writeBytes(png)
            val full = ImageIO.read(ByteArrayInputStream(png))
            val dome = domeRect(scene, case.name)
            // The top bar, the band and a little of the stage under it.
            ImageIO.write(full.getSubimage(0, 0, full.width, (140 * scale).toInt().coerceAtMost(full.height)), "png", File(outDir, "${case.tag}-top.png"))
            // The centre dome, its name and the toggles either side, and the stage it rises into.
            if (dome != null) {
                val cropW = (560 * scale).toInt().coerceAtMost(full.width)
                val left = (dome.center.x - cropW / 2).toInt().coerceIn(0, full.width - cropW)
                val top = (dome.top - 24 * scale).toInt().coerceAtLeast(0)
                ImageIO.write(full.getSubimage(left, top, cropW, full.height - top), "png", File(outDir, "${case.tag}-dome.png"))
            }
            report(case, ring.value, scale, scene, panels)
        } finally {
            scene.close()
        }
    }

    private fun Rect.dp(scale: Float) = "(%.0f, %.0f)-(%.0f, %.0f)".format(left / scale, top / scale, right / scale, bottom / scale)

    private fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)

    private fun domeNode(scene: ImageComposeScene, name: String): SemanticsNode? =
        scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }.firstOrNull { n ->
            n.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == "Next vibe" } &&
                n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.endsWith(name) }
        }

    /** The dome's pointer column, ring and name, in px: position plus size, so unclipped. */
    private fun domeRect(scene: ImageComposeScene, name: String): Rect? = domeNode(scene, name)?.let { Rect(it.positionInRoot, it.size.toSize()) }

    /** Closest distance between two rects, 0 when they touch or overlap. */
    private fun gap(a: Rect, b: Rect): Float {
        val dx = maxOf(0f, a.left - b.right, b.left - a.right)
        val dy = maxOf(0f, a.top - b.bottom, b.top - a.bottom)
        return hypot(dx, dy)
    }

    private fun report(case: Case, ring: Float, scale: Float, scene: ImageComposeScene, panels: Map<DjRoute, Rect>) {
        val tag = case.tag
        val nodes = scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
        val domeNode = domeNode(scene, case.name) ?: return println("[dock] $tag: no dome")
        val dome = Rect(domeNode.positionInRoot, domeNode.size.toSize())
        println("[dock] $tag ring=${ring}dp dome=${dome.dp(scale)}")
        panels.forEach { (route, rect) -> println("[dock] $tag   panel ${route.label} ${rect.dp(scale)}") }
        panels.minByOrNull { (_, rect) -> gap(dome, rect) }?.let { (route, rect) ->
            println("[dock] $tag   nearest panel ${route.label}, %.0fdp from the dome".format(gap(dome, rect) / scale))
        }
        val reach = dome.inflate(8 * scale)
        nodes.filter { n ->
            n.id != domeNode.id &&
                (n.config.getOrNull(SemanticsActions.OnClick) != null ||
                    n.config.getOrNull(SemanticsActions.SetProgress) != null ||
                    n.config.getOrNull(SemanticsActions.CustomActions) != null) &&
                n.boundsInRoot.overlaps(reach)
        }.forEach { n ->
            val label = (n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString()
            println("[dock] $tag   near the dome: \"$label\" ${n.boundsInRoot.dp(scale)}")
        }
    }
}

private object DockRenderDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
