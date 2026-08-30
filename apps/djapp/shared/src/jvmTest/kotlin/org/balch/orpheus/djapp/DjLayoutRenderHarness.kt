package org.balch.orpheus.djapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.features.dj.DjPanel
import org.balch.orpheus.features.dj.DjViewModel
import org.balch.orpheus.features.horn.HornPanel
import org.balch.orpheus.features.horn.HornViewModel
import org.balch.orpheus.features.pulsar.EndsPanel
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanel
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.mixer.MixerPanel
import org.balch.orpheus.features.pulsar.mixer.MixerViewModel
import org.balch.orpheus.features.timer.TimerPanel
import org.balch.orpheus.djapp.vibeinfo.VibeInfoPanel
import org.balch.orpheus.features.timer.TimerStatus
import org.balch.orpheus.features.timer.TimerTransportButtonId
import org.balch.orpheus.features.timer.TimerUiState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.infrastructure.LocalTvFocusChrome
import org.balch.orpheus.ui.infrastructure.TvGlassEnabled
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.HorizontalRotaryKnob
import org.balch.orpheus.ui.widgets.RotaryKnob
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Renders the TV dock geometry to PNGs so the layout can be inspected instead of guessed at.
 * Writes to build/djapp-render/ and asserts nothing: a headless machine without skia natives
 * must not fail the build over a diagnostic.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*DjLayoutRenderHarness*' --rerun
 */
class DjLayoutRenderHarness {

    @Test
    fun renderDockAtEveryPanelCount() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val panels = largeScreenPanels()

        // 960x540dp is what a 1080p Android TV actually reports; the second size covers a
        // desktop window well above the threshold.
        val sizes = listOf(960 to 540, 1440 to 810)

        for ((w, h) in sizes) {
            for (count in 0..panels.size) {
                runCatching {
                    renderDock(
                        panels = panels.take(count),
                        widthDp = w,
                        heightDp = h,
                        outFile = File(outDir, "dock-${w}x$h-$count.png"),
                    )
                }.onFailure {
                    println("[render-harness] skipped ${w}x$h count=$count: ${it.message}")
                }
            }
        }
        println("[render-harness] wrote PNGs to ${outDir.absolutePath}")
    }

    /**
     * Same geometry, but with the real panels rather than labelled boxes, so the docked
     * layout can be judged on content density rather than rectangles. Preview features stand
     * in for the DI graph; if they need more than that, the run is skipped, not failed.
     */
    @Test
    fun renderDockWithRealPanels() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        // largeScreenPanels() is now [Pulsar, DJ, Mix, Horn, Timer, Vibe Info, Ends] (7 total) —
        // both Vibe Info and Ends are genuine dock toggles, no longer sheet overlays appended by
        // hand.
        listOf(2, 4, 7).forEach { count ->
            runCatching {
                val scene = ImageComposeScene(960, 540, Density(1f)) {
                    OrpheusTheme {
                        Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                            DjPanelDock(
                                panels = largeScreenPanels().take(count),
                                modifier = Modifier.fillMaxSize(),
                            ) { route, mod -> PreviewRoutePanel(route, mod) }
                        }
                    }
                }
                try {
                    File(outDir, "real-960x540-$count.png")
                        .writeBytes(scene.render().encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure {
                // A layout contract violation (nested scrollers) must not be swallowed here:
                // it is a crash in the running app, not a headless-skia limitation.
                if (it is IllegalStateException) throw it
                println("[render-harness] real panels count=$count skipped: $it")
            }
        }
    }

    /**
     * Renders the whole TV screen — top bar, dock, bottom bar — at 1280x720dp, the canvas
     * [tvDensityScale] widens a real 960x540dp television to. Catches anything the dock-only
     * harnesses above can't: bar/dock overlap, insets landing on the wrong edge, bottom-bar
     * item order. Wrapped in `LocalTvFocusChrome` AND `LocalTelevisionHardware provides true`,
     * exactly as DjAppScreen provides them on real TV hardware, so this also renders the real
     * TvGlassEnabled appearance on every docked panel — not the non-TV/desktop (always-glass)
     * look a `LocalTvFocusChrome`-only render would show.
     */
    @Test
    fun renderFullTvScreen() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val allPanels = largeScreenPanels()
        // 0 = nothing docked, 3 = a typical mix, 7 = everything including Vibe Info and Ends.
        listOf(0, 3, 7).forEach { count ->
            runCatching {
                val docked = allPanels.take(count)
                val dockedSet = docked.toSet()
                val scene = ImageComposeScene(1280, 720, Density(1f)) {
                    OrpheusTheme {
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalTvFocusChrome provides true,
                            LocalTelevisionHardware provides true,
                        ) {
                            Column(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                                DjTvTopBar(
                                    vizFeature = VizViewModel.previewFeature(),
                                    pulsarFeature = PulsarViewModel.previewFeature(),
                                    onTogglePlayback = {},
                                )
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    DjPanelDock(
                                        panels = docked,
                                        modifier = Modifier.fillMaxSize(),
                                    ) { route, mod -> PreviewRoutePanel(route, mod) }
                                }
                                DjTvBottomBar(
                                    panels = bottomBarPanels(allPanels),
                                    isDocked = { it in dockedSet },
                                    onToggle = {},
                                    timerFeature = TimerViewModel.previewFeature(),
                                    pulsarFeature = PulsarViewModel.previewFeature(),
                                )
                            }
                        }
                    }
                }
                try {
                    File(outDir, "full-1280x720-$count.png")
                        .writeBytes(scene.render().encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure {
                if (it is IllegalStateException) throw it
                println("[render-harness] full screen count=$count skipped: $it")
            }
        }
        println("[render-harness] wrote full-screen PNGs to ${outDir.absolutePath}")
    }

    /**
     * Renders [DjTvBottomBar] and [DjTvTopBar] with one item forced focused, against both a
     * flat dark ground and [busyVizBackdrop], so the raised-plate focus treatment can be judged
     * against the same conditions a real television sees (bright, busy visualization behind
     * mostly-transparent chrome).
     */
    @Test
    fun renderTvNavFocusStates() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val allPanels = largeScreenPanels()

        listOf(false, true).forEach { busy ->
            val tag = if (busy) "busy" else "dark"
            runCatching {
                val scene = ImageComposeScene(1280, 260, Density(1f)) {
                    OrpheusTheme {
                        Box(Modifier.fillMaxSize()) {
                            if (busy) busyVizBackdrop() else Box(
                                Modifier.fillMaxSize().background(Color(0xFF14141F)),
                            )
                            Column(Modifier.fillMaxSize()) {
                                DjTvTopBar(
                                    vizFeature = VizViewModel.previewFeature(),
                                    pulsarFeature = PulsarViewModel.previewFeature(),
                                    onTogglePlayback = {},
                                    previewFocusedButton = TvTopBarButtonId.VIZ_PICKER,
                                )
                                DjTvBottomBar(
                                    panels = bottomBarPanels(allPanels),
                                    isDocked = { it == PulsarTab || it == DjTab },
                                    onToggle = {},
                                    timerFeature = TimerViewModel.previewFeature(),
                                    pulsarFeature = PulsarViewModel.previewFeature(),
                                    previewFocusedRoute = HornTab,
                                )
                            }
                        }
                    }
                }
                try {
                    File(outDir, "nav-focus-$tag.png")
                        .writeBytes(scene.render().encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure {
                if (it is IllegalStateException) throw it
                println("[render-harness] nav focus ($tag) skipped: $it")
            }
        }
        println("[render-harness] wrote nav focus PNGs to ${outDir.absolutePath}")
    }

    /**
     * [DjTvTopBar]'s idle plate (title, Play/Pause, both pickers) should re-theme with the
     * selected visualization instead of wearing one fixed purple/cyan regardless of it. Renders
     * [VizTestPalettes] against both a flat ground and [busyVizBackdrop], so re-theming and
     * legibility over a bright/busy background are both judged from the same PNGs.
     */
    @Test
    fun renderTvTopBarVizPalettes() {
        val outDir = File("build/djapp-render").apply { mkdirs() }

        listOf(false, true).forEach { busy ->
            val bgTag = if (busy) "busy" else "dark"
            VizTestPalettes.forEach { (paletteTag, effects) ->
                runCatching {
                    val scene = ImageComposeScene(1280, 140, Density(1f)) {
                        OrpheusTheme {
                            Box(Modifier.fillMaxSize()) {
                                if (busy) busyVizBackdrop() else Box(
                                    Modifier.fillMaxSize().background(Color(0xFF14141F)),
                                )
                                CompositionLocalProvider(LocalLiquidEffects provides effects) {
                                    DjTvTopBar(
                                        vizFeature = VizViewModel.previewFeature(),
                                        pulsarFeature = PulsarViewModel.previewFeature(),
                                        onTogglePlayback = {},
                                    )
                                }
                            }
                        }
                    }
                    try {
                        File(outDir, "top-bar-viz-palette-$paletteTag-$bgTag.png")
                            .writeBytes(scene.render().encodeToData()!!.bytes)
                    } finally {
                        scene.close()
                    }
                }.onFailure {
                    if (it is IllegalStateException) throw it
                    println("[render-harness] top bar viz palette ($paletteTag, $bgTag) skipped: $it")
                }
            }
        }
        println("[render-harness] wrote top bar viz palette PNGs to ${outDir.absolutePath}")
    }

    /**
     * [DjTvBottomBar]'s docked/focused accent should re-theme with [VizTestPalettes] exactly like
     * the top bar, so the two read as one piece of chrome. Docks Pulsar+DJ, focuses Horn, and arms
     * Ends all in the same render so the three independent channels (docked wash, focused plate,
     * armed ring) can be judged for separability under each palette, not just under the old fixed
     * neonCyan — including that the armed ring (fixed cosmicPurple, unlike the other two) never
     * blends into whichever hue the palette happens to be.
     */
    @Test
    fun renderTvBottomBarVizPalettes() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val allPanels = largeScreenPanels()
        val armedActions = PulsarPanelActions(
            songEndingEnabled = MutableStateFlow(true),
            transitionSpec = MutableStateFlow(TransitionSpec(style = TransitionStyle.TAPE)),
            outroArmed = MutableStateFlow(true),
        )
        val basePulsar = PulsarViewModel.previewFeature()
        val armedPulsar = object : PulsarFeature by basePulsar {
            override val actions: PulsarPanelActions = armedActions
        }

        listOf(false, true).forEach { busy ->
            val bgTag = if (busy) "busy" else "dark"
            VizTestPalettes.forEach { (paletteTag, effects) ->
                runCatching {
                    val scene = ImageComposeScene(1560, 200, Density(1f)) {
                        OrpheusTheme {
                            Box(Modifier.fillMaxSize()) {
                                if (busy) busyVizBackdrop() else Box(
                                    Modifier.fillMaxSize().background(Color(0xFF14141F)),
                                )
                                CompositionLocalProvider(LocalLiquidEffects provides effects) {
                                    DjTvBottomBar(
                                        panels = bottomBarPanels(allPanels),
                                        isDocked = { it == PulsarTab || it == DjTab || it == EndsTab },
                                        onToggle = {},
                                        timerFeature = TimerViewModel.previewFeature(),
                                        pulsarFeature = armedPulsar,
                                        previewFocusedRoute = HornTab,
                                    )
                                }
                            }
                        }
                    }
                    try {
                        File(outDir, "bottom-bar-viz-palette-$paletteTag-$bgTag.png")
                            .writeBytes(scene.render().encodeToData()!!.bytes)
                    } finally {
                        scene.close()
                    }
                }.onFailure {
                    if (it is IllegalStateException) throw it
                    println("[render-harness] bottom bar viz palette ($paletteTag, $bgTag) skipped: $it")
                }
            }
        }
        println("[render-harness] wrote bottom bar viz palette PNGs to ${outDir.absolutePath}")
    }

    /**
     * [org.balch.orpheus.ui.panels.CollapsibleColumnPanel]'s thick region-focus border (widened so
     * it reads from couch distance) should also re-theme with [VizTestPalettes], via the
     * previewRegionFocused seam. Two panels side by side, only the first focused, so the "exactly
     * one border visible" contract stays visible in the same shot.
     */
    @Test
    fun renderPanelFocusBorderVizPalettes() {
        val outDir = File("build/djapp-render").apply { mkdirs() }

        listOf(false, true).forEach { busy ->
            val bgTag = if (busy) "busy" else "dark"
            VizTestPalettes.forEach { (paletteTag, effects) ->
                runCatching {
                    val scene = ImageComposeScene(900, 320, Density(1f)) {
                        OrpheusTheme {
                            Box(Modifier.fillMaxSize()) {
                                if (busy) busyVizBackdrop() else Box(
                                    Modifier.fillMaxSize().background(Color(0xFF14141F)),
                                )
                                CompositionLocalProvider(LocalLiquidEffects provides effects) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                                    ) {
                                        org.balch.orpheus.ui.panels.CollapsibleColumnPanel(
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            title = "PULSE",
                                            color = OrpheusColors.cosmicPurple,
                                            isExpanded = true,
                                            onExpandedChange = {},
                                            showCollapsedHeader = false,
                                            fillHeight = true,
                                            previewRegionFocused = true,
                                        ) {
                                            Text("Focused panel", color = Color.White, fontSize = 14.sp)
                                        }
                                        org.balch.orpheus.ui.panels.CollapsibleColumnPanel(
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            title = "MIX",
                                            color = OrpheusColors.neonCyan,
                                            isExpanded = true,
                                            onExpandedChange = {},
                                            showCollapsedHeader = false,
                                            fillHeight = true,
                                        ) {
                                            Text("Not focused", color = Color.White, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    try {
                        File(outDir, "panel-focus-border-viz-palette-$paletteTag-$bgTag.png")
                            .writeBytes(scene.render().encodeToData()!!.bytes)
                    } finally {
                        scene.close()
                    }
                }.onFailure {
                    if (it is IllegalStateException) throw it
                    println("[render-harness] panel focus border viz palette ($paletteTag, $bgTag) skipped: $it")
                }
            }
        }
        println("[render-harness] wrote panel focus border viz palette PNGs to ${outDir.absolutePath}")
    }

    /**
     * The idle-fade this task adds ([org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder.alpha])
     * animates a value that can't be driven for real inside a headless jvmTest scene (no running
     * frame clock), so this renders the mid-fade LOOK via the previewRegionFocusAlpha seam instead
     * — 1.0 (just shown), 0.5 (mid-fade), 0.15 (nearly gone), 0.0 (fully faded) — on the top bar,
     * bottom bar, and a docked panel, to judge whether a partially-transparent border ever looks
     * broken (a stray thin line, color banding) rather than a clean fade.
     *
     * Each row ALSO forces one item/panel focused (VIZ_PICKER / Horn / the panel itself) at FULL
     * strength throughout, independent of the fading region border — this is the "mixed state" the
     * task specifically asked to have checked: does it look broken to have the border gone while
     * the item's own focus plate stays lit? (Per the task's own design: only region borders fade;
     * per-item focus plates intentionally never do, so a returning viewer can still see the cursor.)
     */
    @Test
    fun renderTvFocusFadeStates() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val allPanels = largeScreenPanels()
        val alphas = listOf(1f, 0.5f, 0.15f, 0f)

        runCatching {
            // Tall: 4 top-bar + 4 panel + 4 bottom-bar rows stacked (the bottom bar alone needs
            // TvBottomBarMinHeight=148dp per row) — a 720dp canvas silently clipped the last group
            // off the bottom edge with no error, since ImageComposeScene doesn't fail on overflow.
            val scene = ImageComposeScene(1280, 1500, Density(1f)) {
                OrpheusTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                        Column(Modifier.fillMaxSize()) {
                            alphas.forEach { alpha ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(
                                        "alpha=$alpha",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        modifier = Modifier.width(70.dp),
                                    )
                                    DjTvTopBar(
                                        vizFeature = VizViewModel.previewFeature(),
                                        pulsarFeature = PulsarViewModel.previewFeature(),
                                        onTogglePlayback = {},
                                        previewFocusedButton = TvTopBarButtonId.VIZ_PICKER,
                                        previewRegionFocused = true,
                                        previewRegionFocusAlpha = alpha,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            alphas.forEach { alpha ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(
                                        "alpha=$alpha",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        modifier = Modifier.width(70.dp),
                                    )
                                    org.balch.orpheus.ui.panels.CollapsibleColumnPanel(
                                        modifier = Modifier.weight(1f).height(90.dp),
                                        title = "PULSE",
                                        color = OrpheusColors.cosmicPurple,
                                        isExpanded = true,
                                        onExpandedChange = {},
                                        showCollapsedHeader = false,
                                        fillHeight = false,
                                        previewRegionFocused = true,
                                        previewRegionFocusAlpha = alpha,
                                    ) {
                                        Text("Panel", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                            alphas.forEach { alpha ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(
                                        "alpha=$alpha",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        modifier = Modifier.width(70.dp),
                                    )
                                    DjTvBottomBar(
                                        panels = bottomBarPanels(allPanels),
                                        isDocked = { it == PulsarTab || it == DjTab },
                                        onToggle = {},
                                        timerFeature = TimerViewModel.previewFeature(),
                                        pulsarFeature = PulsarViewModel.previewFeature(),
                                        previewFocusedRoute = HornTab,
                                        previewRegionFocused = true,
                                        previewRegionFocusAlpha = alpha,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            try {
                File(outDir, "tv-focus-fade-states.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure {
            if (it is IllegalStateException) throw it
            println("[render-harness] tv focus fade states skipped: $it")
        }
        println("[render-harness] wrote fade state PNGs to ${outDir.absolutePath}")
    }

    /**
     * Renders the exclusive region-focus border ([tvFocusRegionBorder]) on each of the three
     * container kinds it applies to — top bar, bottom bar, a docked panel — one at a time, via
     * the previewRegionFocused seam (real Compose focus can't be driven across this test's module
     * boundary; TvFocusRegionHolder's own exclusivity guarantee is a single nullable field, not
     * something a screenshot can additionally prove). Confirms the border reads as a secondary,
     * subtle signal next to the item-level raised plate, in both flat-dark and busy conditions.
     */
    @Test
    fun renderTvRegionFocusBorder() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val allPanels = largeScreenPanels()

        listOf(false, true).forEach { busy ->
            val tag = if (busy) "busy" else "dark"
            runCatching {
                val scene = ImageComposeScene(1280, 720, Density(1f)) {
                    OrpheusTheme {
                        Box(Modifier.fillMaxSize()) {
                            if (busy) busyVizBackdrop() else Box(
                                Modifier.fillMaxSize().background(Color(0xFF14141F)),
                            )
                            Column(Modifier.fillMaxSize()) {
                                // Top bar "focused": only its border shows.
                                DjTvTopBar(
                                    vizFeature = VizViewModel.previewFeature(),
                                    pulsarFeature = PulsarViewModel.previewFeature(),
                                    onTogglePlayback = {},
                                    previewRegionFocused = true,
                                )
                                Row(Modifier.weight(1f).fillMaxWidth()) {
                                    // A docked panel "focused": only ITS border shows.
                                    org.balch.orpheus.ui.panels.CollapsibleColumnPanel(
                                        modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp),
                                        title = "PULSE",
                                        color = OrpheusColors.cosmicPurple,
                                        isExpanded = true,
                                        onExpandedChange = {},
                                        showCollapsedHeader = false,
                                        fillHeight = true,
                                        previewRegionFocused = true,
                                    ) {
                                        Text("Docked panel, region-focused", color = Color.White, fontSize = 14.sp)
                                    }
                                    org.balch.orpheus.ui.panels.CollapsibleColumnPanel(
                                        modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp),
                                        title = "MIX",
                                        color = OrpheusColors.neonCyan,
                                        isExpanded = true,
                                        onExpandedChange = {},
                                        showCollapsedHeader = false,
                                        fillHeight = true,
                                    ) {
                                        Text("Another docked panel, not focused", color = Color.White, fontSize = 14.sp)
                                    }
                                }
                                // Bottom bar "focused": only its border shows.
                                DjTvBottomBar(
                                    panels = bottomBarPanels(allPanels),
                                    isDocked = { it == PulsarTab || it == DjTab },
                                    onToggle = {},
                                    timerFeature = TimerViewModel.previewFeature(),
                                    pulsarFeature = PulsarViewModel.previewFeature(),
                                    previewRegionFocused = true,
                                )
                            }
                        }
                    }
                }
                try {
                    File(outDir, "region-focus-border-$tag.png")
                        .writeBytes(scene.render().encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure {
                if (it is IllegalStateException) throw it
                println("[render-harness] region focus border ($tag) skipped: $it")
            }
        }
        println("[render-harness] wrote region focus border PNGs to ${outDir.absolutePath}")
    }

    /**
     * Renders [RotaryKnob]/[HorizontalRotaryKnob] in every focus permutation — ambient (no
     * focus), non-TV keyboard focus (must be the old thin ring, unchanged), TV focus (raised
     * plate), and TV adjusting (pulsing glow) — against both a flat dark ground and
     * [busyVizBackdrop]. Uses Pulsar's own [OrpheusColors.cosmicPurple] accent so the colors
     * match what ships in the real ENERGY/COMPLEXITY/MOOD/SPACE/MIX knobs.
     */
    @Test
    fun renderRotaryKnobFocusStates() {
        val outDir = File("build/djapp-render").apply { mkdirs() }

        listOf(false, true).forEach { busy ->
            val tag = if (busy) "busy" else "dark"
            runCatching {
                val scene = ImageComposeScene(900, 260, Density(1f)) {
                    OrpheusTheme {
                        Box(Modifier.fillMaxSize()) {
                            if (busy) busyVizBackdrop() else Box(
                                Modifier.fillMaxSize().background(Color(0xFF14141F)),
                            )
                            Row(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                horizontalArrangement = Arrangement.spacedBy(32.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                labeledKnob("Ambient") {
                                    RotaryKnob(
                                        value = 0.6f,
                                        onValueChange = {},
                                        progressColor = OrpheusColors.cosmicPurple,
                                    )
                                }
                                labeledKnob("Non-TV\nfocused") {
                                    RotaryKnob(
                                        value = 0.6f,
                                        onValueChange = {},
                                        progressColor = OrpheusColors.cosmicPurple,
                                        previewFocused = true,
                                    )
                                }
                                CompositionLocalProvider(LocalTvFocusChrome provides true) {
                                    labeledKnob("TV\nfocused") {
                                        RotaryKnob(
                                            value = 0.6f,
                                            onValueChange = {},
                                            progressColor = OrpheusColors.cosmicPurple,
                                            previewFocused = true,
                                        )
                                    }
                                    labeledKnob("TV\nadjusting") {
                                        RotaryKnob(
                                            value = 0.6f,
                                            onValueChange = {},
                                            progressColor = OrpheusColors.cosmicPurple,
                                            previewFocused = true,
                                            previewAdjusting = true,
                                        )
                                    }
                                    labeledKnob("TV horizontal\nadjusting") {
                                        HorizontalRotaryKnob(
                                            label = "MIX",
                                            value = 0.6f,
                                            onValueChange = {},
                                            progressColor = OrpheusColors.cosmicPurple,
                                            previewFocused = true,
                                            previewAdjusting = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                try {
                    File(outDir, "knob-focus-$tag.png")
                        .writeBytes(scene.render().encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }.onFailure {
                if (it is IllegalStateException) throw it
                println("[render-harness] knob focus ($tag) skipped: $it")
            }
        }
        println("[render-harness] wrote knob focus PNGs to ${outDir.absolutePath}")
    }

    /**
     * Side-by-side proof of [org.balch.orpheus.ui.infrastructure.TvGlassEnabled]: the identical
     * panel content, once with glass forced on (non-TV — every phone/tablet/desktop panel today,
     * unchanged, including a wide/fullscreen desktop window that enters the same TV/LargeScreen
     * layout) and once under real TV hardware (`LocalTvFocusChrome` AND `LocalTelevisionHardware`
     * both `true`, glass follows the switch). Both sit over [busyVizBackdrop] so a translucent
     * fill visibly lets it bleed through — exactly the symptom reported on-device — while an
     * opaque fill blocks it outright. The right column needs `LocalTelevisionHardware` too, not
     * just `LocalTvFocusChrome`: the gate is hardware-based now, so without it this column would
     * always render glass ON regardless of the switch and the comparison would prove nothing.
     */
    @Test
    fun renderGlassSwitchComparison() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        runCatching {
            val scene = ImageComposeScene(900, 320, Density(1f)) {
                OrpheusTheme {
                    Box(Modifier.fillMaxSize()) {
                        busyVizBackdrop()
                        Row(
                            modifier = Modifier.fillMaxSize().padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Text("Glass ON — non-TV (unchanged)", color = Color.White, fontSize = 12.sp)
                                org.balch.orpheus.ui.panels.CollapsibleColumnPanel(
                                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                                    title = "PULSE",
                                    color = OrpheusColors.cosmicPurple,
                                    isExpanded = true,
                                    onExpandedChange = {},
                                    showCollapsedHeader = false,
                                    fillHeight = true,
                                ) {
                                    Text("Docked panel content", color = Color.White, fontSize = 13.sp)
                                }
                            }
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Text("TV hardware — TvGlassEnabled = $TvGlassEnabled", color = Color.White, fontSize = 12.sp)
                                CompositionLocalProvider(
                                    LocalTvFocusChrome provides true,
                                    LocalTelevisionHardware provides true,
                                ) {
                                    org.balch.orpheus.ui.panels.CollapsibleColumnPanel(
                                        modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                                        title = "PULSE",
                                        color = OrpheusColors.cosmicPurple,
                                        isExpanded = true,
                                        onExpandedChange = {},
                                        showCollapsedHeader = false,
                                        fillHeight = true,
                                    ) {
                                        Text("Docked panel content", color = Color.White, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            try {
                File(outDir, "glass-switch-comparison.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure {
            if (it is IllegalStateException) throw it
            println("[render-harness] glass switch comparison skipped: $it")
        }
    }

    /**
     * [EndsPanel] on its own, close up: PLAYS (song-ending disabled) and TAPE selected
     * (enabled) — the two states its bottom-bar toggle label must always match exactly.
     */
    @Test
    fun renderEndsPanel() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        runCatching {
            val scene = ImageComposeScene(700, 360, Density(1f)) {
                OrpheusTheme {
                    Row(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF14141F)).padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        org.balch.orpheus.features.pulsar.EndsPanel(
                            spec = org.balch.orpheus.core.audio.TransitionSpec(
                                style = org.balch.orpheus.core.audio.TransitionStyle.FADE,
                            ),
                            enabled = false,
                            onSetEnabled = {},
                            onStyleChange = {},
                            onHandoffMsChange = {},
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            isExpanded = true,
                            showCollapsedHeader = false,
                            fillHeight = true,
                        )
                        org.balch.orpheus.features.pulsar.EndsPanel(
                            spec = org.balch.orpheus.core.audio.TransitionSpec(
                                style = org.balch.orpheus.core.audio.TransitionStyle.TAPE,
                            ),
                            enabled = true,
                            onSetEnabled = {},
                            onStyleChange = {},
                            onHandoffMsChange = {},
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            isExpanded = true,
                            showCollapsedHeader = false,
                            fillHeight = true,
                        )
                    }
                }
            }
            try {
                File(outDir, "ends-panel.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure {
            if (it is IllegalStateException) throw it
            println("[render-harness] ends panel skipped: $it")
        }
    }

    /**
     * Close-up of the bottom bar's Ends item across all three of its independent signals:
     * idle, docked, focused, and armed (a song ending is actively in progress) — including the
     * combinations that must all stay readable at once per the user's explicit requirement. Then
     * the full 7-item bar with the same armed SCRATCH state, at the bar's real (unconstrained)
     * sizing, to confirm the longer style name never clips in practice.
     */
    @Test
    fun renderEndsBottomBarSignals() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        fun endsOnlyBar(armed: Boolean): PulsarFeature {
            val base = PulsarViewModel.previewFeature()
            val actions = PulsarPanelActions(
                songEndingEnabled = MutableStateFlow(true),
                transitionSpec = MutableStateFlow(TransitionSpec(style = TransitionStyle.SCRATCH)),
                outroArmed = MutableStateFlow(armed),
            )
            return object : PulsarFeature by base {
                override val actions: PulsarPanelActions = actions
            }
        }
        runCatching {
            val scene = ImageComposeScene(1560, 560, Density(1f)) {
                OrpheusTheme {
                    Column(Modifier.fillMaxSize().background(Color(0xFF14141F))) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            listOf(
                                Triple(false, false, false) to "idle",
                                Triple(true, false, false) to "docked",
                                Triple(false, true, false) to "focused",
                                Triple(true, true, false) to "docked+focused",
                                Triple(false, false, true) to "armed",
                                Triple(true, false, true) to "docked+armed",
                                Triple(true, true, true) to "docked+focused+armed",
                            ).forEach { (state, tag) ->
                                val (docked, focused, armed) = state
                                // DjTvBottomBar fillMaxWidth()s internally, so each swatch needs
                                // a fixed width here rather than a plain (unweighted) Column —
                                // otherwise every swatch fights for the full Row width at once.
                                // 200dp is generously wider than the item's own natural content
                                // width purely so this diagnostic grid doesn't clip its own
                                // caption text — the real bar below is unconstrained.
                                Column(
                                    modifier = Modifier.width(200.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(tag, color = Color.White, fontSize = 11.sp)
                                    DjTvBottomBar(
                                        panels = listOf(EndsTab),
                                        isDocked = { docked },
                                        onToggle = {},
                                        timerFeature = TimerViewModel.previewFeature(),
                                        pulsarFeature = endsOnlyBar(armed),
                                        previewFocusedRoute = if (focused) EndsTab else null,
                                    )
                                }
                            }
                        }
                        Text(
                            "Real 7-item bar, unconstrained width, SCRATCH armed:",
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                        )
                        DjTvBottomBar(
                            panels = bottomBarPanels(largeScreenPanels()),
                            isDocked = { it == PulsarTab || it == EndsTab },
                            onToggle = {},
                            timerFeature = TimerViewModel.previewFeature(),
                            pulsarFeature = endsOnlyBar(armed = true),
                        )
                    }
                }
            }
            try {
                File(outDir, "ends-bottombar-signals.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure {
            if (it is IllegalStateException) throw it
            println("[render-harness] ends bottom bar signals skipped: $it")
        }
    }

    /**
     * [TimerPanel]'s transport buttons in every focus permutation this task added: ambient
     * (non-TV, unchanged), TV idle, TV Start/Stop focused (both IDLE and RUNNING, to prove the
     * persistent RUNNING wash and the focus plate read as separate signals), and TV Reset
     * focused.
     */
    @Test
    fun renderTimerTransportFocusStates() {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        runCatching {
            val scene = ImageComposeScene(1400, 260, Density(1f)) {
                OrpheusTheme {
                    Row(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF14141F)).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        labeledTimer("Non-TV\n(unchanged)", TimerStatus.RUNNING, null)
                        CompositionLocalProvider(LocalTvFocusChrome provides true) {
                            labeledTimer("TV idle,\nrunning", TimerStatus.RUNNING, null)
                            labeledTimer(
                                "TV Stop\nfocused (running)",
                                TimerStatus.RUNNING,
                                TimerTransportButtonId.START_STOP,
                            )
                            labeledTimer(
                                "TV Start\nfocused (idle)",
                                TimerStatus.IDLE,
                                TimerTransportButtonId.START_STOP,
                            )
                            labeledTimer(
                                "TV Reset\nfocused",
                                TimerStatus.RUNNING,
                                TimerTransportButtonId.RESET,
                            )
                        }
                    }
                }
            }
            try {
                File(outDir, "timer-transport-focus.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }.onFailure {
            if (it is IllegalStateException) throw it
            println("[render-harness] timer transport focus skipped: $it")
        }
    }

    @Composable
    private fun RowScope.labeledTimer(
        caption: String,
        status: TimerStatus,
        focused: TimerTransportButtonId?,
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(caption, color = Color.White, fontSize = 11.sp)
            TimerPanel(
                feature = TimerViewModel.previewFeature(
                    TimerUiState(
                        initialTime = 45.minutes,
                        remainingTime = 42.minutes.plus(13.seconds),
                        status = status,
                    ),
                ),
                showCollapsedHeader = false,
                previewFocusedButton = focused,
            )
        }
    }

    private fun renderDock(panels: List<DjRoute>, widthDp: Int, heightDp: Int, outFile: File) {
        val scene = ImageComposeScene(
            width = widthDp,
            height = heightDp,
            density = Density(1f),
        ) {
            // Stand-in for the VizBackground sibling so it is obvious which area stays clear.
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF14141F)),
                contentAlignment = Alignment.Center,
            ) {
                Text("VIZ", color = Color(0xFF00E5FF), fontSize = 22.sp)

                DjPanelDock(panels = panels, modifier = Modifier.fillMaxSize()) { route, mod ->
                    Box(
                        modifier = mod
                            .background(Color(0xFF32324A))
                            .border(1.dp, Color(0xFF00E5FF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(route.label, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
        try {
            outFile.writeBytes(scene.render().encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }
}

private val emptyVizFlow = MutableStateFlow(FloatArray(0))
private val emptyPulsarVizFlow = MutableStateFlow(PulsarVizData())
private val emptyTrackVizFlows = List(8) { MutableStateFlow(FloatArray(0)) }

/**
 * Shared stand-in visualization palettes for every "does the chrome follow the viz" test —
 * HeartbeatViz's real pink and LavaLampViz's real green (the exact pairing named when this was
 * specced: "obvious at a glance under Heartbeat vs. Lava Lamp"), a third vivid orange as a
 * non-pink/green data point, and a pale near-white one specifically to stress-test text/plate
 * contrast — the failure mode called out for turning an idle-plate wash up.
 */
private val VizTestPalettes = listOf(
    "pink" to VisualizationLiquidEffects(
        title = CenterPanelStyle(
            titleColor = OrpheusColors.synthPink,
            borderColor = OrpheusColors.synthPink.copy(alpha = 0.45f),
        ),
    ),
    "green" to VisualizationLiquidEffects(
        title = CenterPanelStyle(
            titleColor = OrpheusColors.synthGreen,
            borderColor = OrpheusColors.neonMagenta.copy(alpha = 0.4f),
        ),
    ),
    "orange" to VisualizationLiquidEffects(
        title = CenterPanelStyle(
            titleColor = OrpheusColors.neonOrange,
            borderColor = OrpheusColors.neonOrange.copy(alpha = 0.3f),
        ),
    ),
    "pale" to VisualizationLiquidEffects(
        title = CenterPanelStyle(
            titleColor = OrpheusColors.sterlingSilver,
            borderColor = OrpheusColors.sterlingSilver.copy(alpha = 0.35f),
        ),
    ),
)

/**
 * Stand-in for the real VizBackground: a bright sunset gradient with a scatter of soft bokeh
 * discs, so the raised-plate focus treatment can be judged against the worst case the task
 * calls out — "bright and busy... a sunset scene with bokeh in places" — not just flat black.
 */
@Composable
private fun busyVizBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFFFF7A45), Color(0xFFB4438C), Color(0xFF2E1A47)),
            ),
        )
        val bokeh = listOf(
            Triple(Offset(size.width * 0.15f, size.height * 0.25f), size.minDimension * 0.22f, Color(0xFFFFE1B0)),
            Triple(Offset(size.width * 0.45f, size.height * 0.6f), size.minDimension * 0.30f, Color(0xFFFF9E6B)),
            Triple(Offset(size.width * 0.75f, size.height * 0.2f), size.minDimension * 0.18f, Color(0xFFFFF2D6)),
            Triple(Offset(size.width * 0.9f, size.height * 0.7f), size.minDimension * 0.26f, Color(0xFFE85D8A)),
            Triple(Offset(size.width * 0.3f, size.height * 0.85f), size.minDimension * 0.20f, Color(0xFFFFC98A)),
        )
        bokeh.forEach { (center, radius, color) ->
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}

/** One knob with a caption underneath, for the focus-state showcase grid. */
@Composable
private fun labeledKnob(caption: String, knob: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        knob()
        Text(caption, color = Color.White, fontSize = 11.sp)
    }
}

/** Renders one route with its preview feature, titles on, as the dock shows it. */
@Composable
private fun PreviewRoutePanel(route: DjRoute, modifier: Modifier) {
    when (route) {
        PulsarTab -> PulsarPanel(
            pulsar = PulsarViewModel.previewFeature(),
            vizFlow = emptyPulsarVizFlow,
            trackVizFlows = emptyTrackVizFlows,
            modifier = modifier,
            isExpanded = true,
            onExpandedChange = {},
            showCollapsedHeader = false,
            showExpandedTitle = false,
            fillHeight = false,
            // Mirrors DjAppScreen's real TV dock: the "Ends" bottom-bar button owns this now.
            showEndingControl = false,
        )
        DjTab -> DjPanel(
            feature = DjViewModel.previewFeature(),
            vizFlowA = emptyVizFlow,
            vizFlowB = emptyVizFlow,
            outVizFlow = emptyVizFlow,
            modifier = modifier,
            isExpanded = true,
            onExpandedChange = {},
            showCollapsedHeader = false,
            showExpandedTitle = false,
            fillHeight = false,
        )
        // Docked, Mix is the mixer alone — no reverb strip. Mirrors DjAppScreen.
        MixTab -> MixerPanel(
            feature = MixerViewModel.previewFeature(),
            trackVizFlows = emptyTrackVizFlows,
            masterOutVizFlow = emptyVizFlow,
            modifier = modifier,
            isExpanded = true,
            onExpandedChange = {},
            showCollapsedHeader = false,
            showExpandedTitle = false,
            fillHeight = false,
        )
        HornTab -> HornPanel(
            feature = HornViewModel.previewFeature(),
            modifier = modifier,
            isExpanded = true,
            onExpandedChange = {},
            showCollapsedHeader = false,
            showExpandedTitle = false,
            fillHeight = false,
        )
        VibeInfoTab -> VibeInfoPanel(
            pulsar = PulsarViewModel.previewFeature(),
            vizFlow = emptyPulsarVizFlow,
            modifier = modifier,
        )
        EndsTab -> EndsPanel(
            spec = org.balch.orpheus.core.audio.TransitionSpec(
                style = org.balch.orpheus.core.audio.TransitionStyle.TAPE,
            ),
            enabled = true,
            onSetEnabled = {},
            onStyleChange = {},
            onHandoffMsChange = {},
            modifier = modifier,
            isExpanded = true,
            onExpandedChange = {},
            showCollapsedHeader = false,
            showExpandedTitle = false,
            fillHeight = false,
        )
        TimerTab -> TimerPanel(
            feature = TimerViewModel.previewFeature(),
            modifier = modifier,
            showCollapsedHeader = false,
            showExpandedTitle = false,
            fillHeight = false,
        )
        else -> Unit
    }
}
