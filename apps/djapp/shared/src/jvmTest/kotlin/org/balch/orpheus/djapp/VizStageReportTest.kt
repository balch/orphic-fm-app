package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.VizStage
import org.balch.orpheus.ui.viz.vizStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The stage is what the visualization scales its picture into, so what matters is that the rect
 * the screen reports leaves out the header and the navigation in every layout. Each case renders
 * the same structure `DjAppScreen` builds (the real header row, the real nav scaffold, the real
 * TV bars) and compares the reported stage against the measured bars.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*VizStageReportTest*' --rerun
 */
class VizStageReportTest {

    @Test
    fun `portrait reports the band between the header and the bottom nav`() {
        val stage = VizStage()
        var header = Rect.Zero
        var content = Rect.Zero
        val height = 644

        render(466, height) {
            DjAppNavScaffold(
                isSelected = { it == DjTab },
                onItemClick = {},
                layout = DjLayout.Portrait,
                pulsarFeature = PulsarViewModel.previewFeature(),
                timerFeature = TimerViewModel.previewFeature(),
                onTogglePlayback = {},
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { content = it.boundsInRoot() },
                ) {
                    DjAppHeaderRow(
                        vizFeature = VizViewModel.previewFeature(),
                        onInfoClick = {},
                        modifier = Modifier
                            .onGloballyPositioned { header = it.boundsInRoot() }
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Column(Modifier.weight(1f).fillMaxWidth().vizStage(stage)) {
                        filler(Modifier.weight(.6f).fillMaxWidth())
                        filler(Modifier.weight(.4f).fillMaxWidth())
                    }
                }
            }
        }

        val bounds = assertNotNull(stage.bounds, "portrait never reported a stage")
        assertTrue(header.height > 0f, "the header measured nothing")
        assertClose(header.bottom, bounds.top, "stage starts at the header's bottom")
        assertClose(content.bottom, bounds.bottom, "stage ends where the content area does")
        // The bottom nav is outside the scaffold's content, so the content itself stops short.
        assertTrue(
            content.bottom < height - 1f,
            "the bottom nav took no height: content ${content.bottom} of $height",
        )
    }

    /** The dock's rows, stacked as [DjAppTvChrome] stacks them, and the stage reported from inside. */
    private class DockRows(val topBar: Rect, val band: Rect, val stage: Rect, val bottomBar: Rect)

    private fun dockRows(tv: Boolean): DockRows {
        val stage = VizStage()
        var topBar = Rect.Zero
        var band = Rect.Zero
        var bottomBar = Rect.Zero
        render(1280, 720) {
            CompositionLocalProvider(LocalTelevisionHardware provides tv) {
                Column(Modifier.fillMaxSize()) {
                    DjTvTopBar(
                        panels = topBarPanels(largeScreenPanels()),
                        isDocked = { false },
                        onToggle = {},
                        vizFeature = VizViewModel.previewFeature(),
                        pulsarFeature = PulsarViewModel.previewFeature(),
                        modifier = Modifier.onGloballyPositioned { topBar = it.boundsInRoot() },
                    )
                    DockSongBand(PulsarViewModel.previewFeature(), Modifier.onGloballyPositioned { band = it.boundsInRoot() })
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        DjPanelDock(
                            panels = listOf(PulsarTab, DjTab),
                            modifier = Modifier.fillMaxSize().vizStage(stage),
                        ) { _, mod -> filler(mod) }
                    }
                    DjTvBottomBar(
                        panels = bottomBarPanels(largeScreenPanels()),
                        isDocked = { false },
                        onToggle = {},
                        timerFeature = TimerViewModel.previewFeature(),
                        pulsarFeature = PulsarViewModel.previewFeature(),
                        onTogglePlayback = {},
                        modifier = Modifier.onGloballyPositioned { bottomBar = it.boundsInRoot() },
                    )
                }
            }
        }
        return DockRows(topBar, band, assertNotNull(stage.bounds, "the dock never reported a stage (tv=$tv)"), bottomBar)
    }

    /** The stage the real [DjAppTvChrome] reports, so the stack above cannot drift from it. */
    private fun chromeStage(tv: Boolean): Rect {
        val stage = VizStage()
        render(1280, 720) {
            DjAppTvChrome(
                tvHardware = tv,
                domeRingSize = BarRingSize,
                barGlass = false,
                vizHidesPanelsWhenIdle = false,
                focusRegion = TvFocusRegionHolder(),
                vizFeature = VizViewModel.previewFeature(),
                pulsarFeature = PulsarViewModel.previewFeature(),
                timerFeature = TimerViewModel.previewFeature(),
                onTogglePlayback = {},
                dockablePanels = largeScreenPanels(),
                dockedPanels = listOf(PulsarTab, DjTab),
                activeSheet = null,
                tabs = djTabs,
                onToggleDocked = {},
                onActiveSheetChange = {},
                stage = {
                    DjPanelDock(
                        panels = listOf(PulsarTab, DjTab),
                        modifier = Modifier.fillMaxSize().vizStage(stage),
                    ) { _, mod -> filler(mod) }
                },
            )
        }
        return assertNotNull(stage.bounds, "the chrome never reported a stage (tv=$tv)")
    }

    // The song band is a 36dp row under the top bar, on TV hardware too, and the stage starts under it.
    @Test
    fun `the large screen reports the stage between the song band and the bottom bar`() {
        listOf(false, true).forEach { tv ->
            val rows = dockRows(tv)
            assertTrue(rows.topBar.height > 0f && rows.bottomBar.height > 0f, "a television bar measured nothing (tv=$tv)")
            assertClose(rows.topBar.bottom, rows.band.top, "the band starts under the top bar (tv=$tv)")
            assertClose(SongBandHeight.value, rows.band.height, "the band's height (tv=$tv)")
            assertClose(rows.band.bottom, rows.stage.top, "the stage starts under the band (tv=$tv)")
            assertClose(rows.bottomBar.top, rows.stage.bottom, "the stage ends above the bottom bar (tv=$tv)")
            val chrome = chromeStage(tv)
            assertClose(rows.stage.top, chrome.top, "the chrome's stage top (tv=$tv)")
            assertClose(rows.stage.bottom, chrome.bottom, "the chrome's stage bottom (tv=$tv)")
        }
    }

    /** Stands in for a real panel: the stage is about geometry, not about panel content. */
    @Composable
    private fun filler(modifier: Modifier) {
        Box(modifier.background(Color(0xFF203040)))
    }

    private fun render(width: Int, height: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            OrpheusTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) { content() }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
    }

    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertTrue(abs(expected - actual) < 1f, "$what: expected $expected, got $actual")
    }
}
