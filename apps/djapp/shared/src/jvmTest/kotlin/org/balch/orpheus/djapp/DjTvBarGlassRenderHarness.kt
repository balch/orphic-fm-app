package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.features.visualizations.viz.GalaxyViz
import org.balch.orpheus.features.visualizations.viz.SwirlyViz
import org.balch.orpheus.features.visualizations.viz.fish.AquariumViz
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.infrastructure.LocalTvFocusChrome
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.Visualization
import org.balch.orpheus.ui.widgets.VizBackground
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Before/after PNGs of the chrome-bar glass over three real visualizations, plus the measurement
 * that decides whether it earns its place: mean and spread on the bare backdrop inside each bar.
 *
 * Those numbers are the point. The full-bleed lens this replaced moved the field behind the bars
 * by only +2.4/255, which is why it was dropped; a treatment aimed at the bars has to do better
 * than that where the text actually sits.
 *
 * What the pair of statistics separates: MEAN rising means the tint built a surface where the
 * viz had left nearly nothing, and SPREAD falling means the frost flattened detail the text would
 * have competed with. Measured across all three vizzes the first effect is large (+18 to +22) and
 * the second is small (under -1.8), because none of them put high-frequency detail in the bar
 * regions — Galaxy and Swirly are sparse there and Aquarium's water is a smooth gradient. Do not
 * read that as the frost being useless in general; read it as untested by these three.
 *
 * [LiquidHeadlessProbeTest] is the precondition for any of this meaning anything: it establishes
 * that the liquid RenderEffect resolves under headless Skia at all.
 *
 * Asserts nothing — a headless machine without skia natives must not fail the build over a
 * diagnostic.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*DjTvBarGlassRenderHarness*' --rerun
 */
class DjTvBarGlassRenderHarness {

    // 1280x720dp: the TV canvas width the layout targets, and a plausible fullscreen desktop
    // window, which is the only case this treatment ships to.
    private val widthPx = 1280
    private val heightPx = 720

    // Bare-backdrop patches INSIDE each bar, chosen to miss every control: the gap between the
    // centred title and the Vibe dropdown, and the empty run left of the first bottom-bar item.
    //
    // Measuring the whole strip instead would be dominated by the buttons' own bright pills and
    // white labels, which swamp the variance the frost is supposed to be flattening. These boxes
    // isolate the surface the text actually sits against.
    private val topStrip = intArrayOf(745, 12, 900, 60)
    private val bottomStrip = intArrayOf(10, heightPx - 150, 230, heightPx - 10)

    @Test
    fun renderBarGlassBeforeAfter() {
        val outDir = File("build/djapp-render").apply { mkdirs() }

        // Aquarium is the control case. Galaxy and Swirly are sparse near-black fields with
        // little detail in the bar regions to flatten, so they cannot show whether the FROST
        // does anything — only whether the tint builds a surface. A busy, mid-bright viz can.
        val cases = listOf(
            "galaxy" to { newGalaxy() },
            "swirly" to { newSwirly() },
            "aquarium" to { newAquarium() },
        )
        cases.forEach { (name, make) ->
            val measured = mutableMapOf<String, Pair<Strip, Strip>>()
            listOf(false, true).forEach { glass ->
                val tag = if (glass) "after" else "before"
                runCatching {
                    val bytes = renderChrome(make(), glass)
                    File(outDir, "barglass-$name-$tag.png").writeBytes(bytes)
                    measured[tag] = stripStats(bytes, topStrip) to
                        stripStats(bytes, bottomStrip)
                }.onFailure { println("[bar-glass] $name/$tag skipped: $it") }
            }
            val before = measured["before"]
            val after = measured["after"]
            if (before != null && after != null) {
                report("$name top", before.first, after.first)
                report("$name bottom", before.second, after.second)
            }
        }
        println("[bar-glass] wrote PNGs to ${outDir.absolutePath}")
    }

    /**
     * Mirrors DjApp's tree: the visualization is the liquefiable SOURCE and the chrome is its
     * SIBLING. `LocalTelevisionHardware provides false` is the fullscreen-desktop case, the only
     * one the bar glass ships to.
     */
    private fun renderChrome(viz: Visualization, glass: Boolean): ByteArray {
        val allPanels = largeScreenPanels()
        val docked = allPanels.take(3)
        val scene = ImageComposeScene(widthPx, heightPx, Density(1f)) {
            OrpheusTheme {
                val liquidState = rememberLiquidState()
                CompositionLocalProvider(
                    LocalLiquidState provides liquidState,
                    LocalTvFocusChrome provides true,
                    LocalTelevisionHardware provides false,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        VizBackground(
                            modifier = Modifier.fillMaxSize().liquefiable(liquidState),
                            selectedViz = viz,
                        )
                        Column(Modifier.fillMaxSize()) {
                            DjTvTopBar(
                                panels = topBarPanels(allPanels),
                                isDocked = { it in docked },
                                onToggle = {},
                                vizFeature = VizViewModel.previewFeature(),
                                pulsarFeature = PulsarViewModel.previewFeature(),
                                glass = glass,
                            )
                            DockSongBand(PulsarViewModel.previewFeature())
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                DjPanelDock(
                                    panels = docked,
                                    modifier = Modifier.fillMaxSize(),
                                ) { route, mod -> BarGlassProbePanel(route, mod) }
                            }
                            DjTvBottomBar(
                                panels = bottomBarPanels(allPanels),
                                isDocked = { it in docked.toSet() },
                                onToggle = {},
                                timerFeature = TimerViewModel.previewFeature(),
                                pulsarFeature = PulsarViewModel.previewFeature(),
                                onTogglePlayback = {},
                                glass = glass,
                            )
                        }
                    }
                }
            }
        }
        return try {
            // Step the clock: these vizzes animate from a withFrameNanos loop whose lastNanos == 0
            // is an "unset" sentinel, so a single render at t=0 never advances them.
            var t = 1_000_000_000L
            repeat(8) {
                Snapshot.sendApplyNotifications()
                scene.render(t).close()
                t += 16_000_000L
            }
            Snapshot.sendApplyNotifications()
            scene.render(t).encodeToData()!!.bytes
        } finally {
            scene.close()
        }
    }

    /** Mean luminance and its standard deviation over a strip. */
    private data class Strip(val mean: Double, val sd: Double)

    /**
     * Standard deviation is the number that matters, not the mean.
     *
     * Glass does not make chrome legible by darkening it — the tint actually RAISES mean
     * luminance here. It works by frosting the backdrop, which flattens the high-frequency
     * star/spiral detail the text would otherwise compete with. That flattening is a drop in
     * spread, so a treatment that helps shows up as a falling sd, and one that merely tints
     * shows up as a moving mean with the sd intact.
     */
    private fun stripStats(png: ByteArray, box: IntArray): Strip {
        val img = ImageIO.read(ByteArrayInputStream(png))
        val values = ArrayList<Double>((box[3] - box[1]) * (box[2] - box[0]))
        for (y in box[1] until box[3]) {
            for (x in box[0] until box[2]) {
                val p = img.getRGB(x, y)
                values += 0.2126 * ((p shr 16) and 0xFF) +
                    0.7152 * ((p shr 8) and 0xFF) +
                    0.0722 * (p and 0xFF)
            }
        }
        val mean = values.average()
        val sd = kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        return Strip(mean, sd)
    }

    private fun report(label: String, before: Strip, after: Strip) {
        println(
            "[bar-glass] %-14s mean %6.2f -> %6.2f (%+6.2f)   sd %6.2f -> %6.2f (%+6.2f)".format(
                label, before.mean, after.mean, after.mean - before.mean,
                before.sd, after.sd, after.sd - before.sd,
            )
        )
    }

    private fun newGalaxy() = GalaxyViz(RenderProbeSynthEngine(), BarGlassDispatchers)
    private fun newSwirly() = SwirlyViz(RenderProbeSynthEngine(), BarGlassDispatchers)
    private fun newAquarium() = AquariumViz(RenderProbeSynthEngine())
}

/** Stand-in docked panel: real panel chrome, cheap content. */
@Composable
private fun BarGlassProbePanel(route: DjRoute, modifier: Modifier) {
    CollapsibleColumnPanel(
        modifier = modifier,
        title = route.label,
        color = OrpheusColors.cosmicPurple,
        isExpanded = true,
        onExpandedChange = {},
        showCollapsedHeader = false,
        fillHeight = true,
    ) {
        Text(route.label, color = Color.White, fontSize = 13.sp)
    }
}

private object BarGlassDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
