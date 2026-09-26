package org.balch.orpheus.djapp

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import io.github.fletchmckee.liquid.rememberLiquidState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Neither of the dock's bars recomposes when the chrome recomposes for something that is not its
 * business: a visualization changing its accent, which Galaxy, Fireworks and Swirly do every frame.
 * The bars take the accent in draw, and their glass, the title and the pickers read the effects in
 * leaves of their own, so neither bar is ever handed a new modifier.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*DockChromeRecompositionTest*' --rerun
 */
class DockChromeRecompositionTest {

    // Glass on (desktop, with a live LiquidState as DjApp provides one) and off (television hardware).
    @Test
    fun anAccentChangeRecomposesTheChromeButNeitherBar() {
        listOf(true, false).forEach { glass ->
            val scopes = ScopeCounter()
            var bottomBarRuns = 0
            var topBarRuns = 0
            // Each bar's body asks isDocked of each of its toggles once per run, and the chrome's
            // isDocked asks this list first. The bottom bar's toggles are djTabs; the top bar's are not.
            val base = largeScreenPanels()
            val top = topBarPanels(base)
            val dockable = object : List<DjRoute> by base {
                override fun contains(element: DjRoute): Boolean {
                    if (element in djTabs) bottomBarRuns++
                    if (element in top) topBarRuns++
                    return base.contains(element)
                }
            }
            // Built once, as DjAppScreen holds them across its recompositions.
            val docked = listOf(PulsarTab, DjTab)
            val region = TvFocusRegionHolder()
            val viz = VizViewModel.previewFeature()
            val pulsar = PulsarViewModel.previewFeature()
            val timer = TimerViewModel.previewFeature()
            var effects by mutableStateOf(VisualizationLiquidEffects.Default)
            val scene = ImageComposeScene(1512, 982, Density(1f)) {
                ObserveScopes(scopes)
                OrpheusTheme {
                    CompositionLocalProvider(
                        LocalLiquidState provides rememberLiquidState(),
                        LocalLiquidEffects provides effects,
                    ) {
                        DjAppTvChrome(
                            tvHardware = !glass,
                            domeRingSize = DockDomeRingSize,
                            barGlass = glass,
                            vizHidesPanelsWhenIdle = false,
                            focusRegion = region,
                            vizFeature = viz,
                            pulsarFeature = pulsar,
                            timerFeature = timer,
                            onTogglePlayback = {},
                            dockablePanels = dockable,
                            dockedPanels = docked,
                            activeSheet = null,
                            tabs = djTabs,
                            onToggleDocked = {},
                            onActiveSheetChange = {},
                            stage = {},
                        )
                    }
                }
            }
            var now = 1_000L
            fun frames(count: Int) = repeat(count) {
                Snapshot.sendApplyNotifications()
                scene.render(now * 1_000_000)
                now += 16
            }
            try {
                frames(4)
                assertTrue(scopes.observed, "the composition could not be observed, so this proves nothing")
                val runs = bottomBarRuns
                val topRuns = topBarRuns
                assertTrue(runs > 0, "sanity: the bottom bar never composed (glass=$glass)")
                assertTrue(topRuns > 0, "sanity: the top bar never composed (glass=$glass)")
                val before = scopes.entered

                effects = VisualizationLiquidEffects(title = CenterPanelStyle(titleColor = Color(0xFFFF5AA0)))
                frames(4)

                assertTrue(scopes.entered > before, "the chrome never recomposed, so this proves nothing (glass=$glass)")
                assertEquals(runs, bottomBarRuns, "an accent change recomposed DjTvBottomBar (glass=$glass)")
                assertEquals(topRuns, topBarRuns, "an accent change recomposed DjTvTopBar (glass=$glass)")
            } finally {
                scene.close()
            }
        }
    }
}
