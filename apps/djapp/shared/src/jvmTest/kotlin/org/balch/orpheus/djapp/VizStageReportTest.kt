package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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

    @Test
    fun `landscape reports the band right of the rail and below the header`() {
        val stage = VizStage()
        val tracker = LandscapeStageTracker(stage)
        var header = Rect.Zero
        var row = Rect.Zero

        render(780, 360) {
            DjAppNavScaffold(
                isSelected = { it == DjTab },
                onItemClick = {},
                layout = DjLayout.Landscape,
                pulsarFeature = PulsarViewModel.previewFeature(),
                timerFeature = TimerViewModel.previewFeature(),
                onTogglePlayback = {},
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            row = it.boundsInRoot()
                            tracker.onContent(row)
                        },
                ) {
                    filler(Modifier.weight(.5f).fillMaxHeight())
                    Column(Modifier.weight(.5f).fillMaxHeight()) {
                        DjAppHeaderRow(
                            vizFeature = VizViewModel.previewFeature(),
                            onInfoClick = {},
                            modifier = Modifier
                                .onGloballyPositioned {
                                    header = it.boundsInRoot()
                                    tracker.onHeader(header)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalPadding = 0.dp,
                        )
                        filler(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }

        val bounds = assertNotNull(stage.bounds, "landscape never reported a stage")
        assertTrue(header.height > 0f, "the header measured nothing")
        assertClose(header.bottom, bounds.top, "stage starts below the header")
        assertClose(row.left, bounds.left, "stage starts at the content's left edge")
        assertClose(row.bottom, bounds.bottom, "stage ends where the content does")
        // The rail is outside the scaffold's content, so the content starts right of zero.
        assertTrue(row.left > 1f, "the nav rail took no width: content starts at ${row.left}")
    }

    @Test
    fun `the large screen reports the band between the two television bars`() {
        val stage = VizStage()
        var topBar = Rect.Zero
        var bottomBar = Rect.Zero

        render(1280, 720) {
            Column(Modifier.fillMaxSize()) {
                DjTvTopBar(
                    vizFeature = VizViewModel.previewFeature(),
                    pulsarFeature = PulsarViewModel.previewFeature(),
                    onTogglePlayback = {},
                    modifier = Modifier.onGloballyPositioned { topBar = it.boundsInRoot() },
                )
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
                    modifier = Modifier.onGloballyPositioned { bottomBar = it.boundsInRoot() },
                )
            }
        }

        val bounds = assertNotNull(stage.bounds, "the large screen never reported a stage")
        assertTrue(topBar.height > 0f && bottomBar.height > 0f, "a television bar measured nothing")
        assertClose(topBar.bottom, bounds.top, "stage starts under the top bar")
        assertClose(bottomBar.top, bounds.bottom, "stage ends above the bottom bar")
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
