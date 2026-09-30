package org.balch.orpheus.djapp.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.djapp.BarRingSize
import org.balch.orpheus.djapp.DjAppNavScaffold
import org.balch.orpheus.djapp.DjAppTvChrome
import org.balch.orpheus.djapp.DjLayout
import org.balch.orpheus.djapp.DjLayoutBox
import org.balch.orpheus.djapp.djTabs
import org.balch.orpheus.djapp.largeScreenPanels
import org.balch.orpheus.djapp.usesLandscapeChrome
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.core.preferences.VibePlaylistPrefs
import org.balch.orpheus.features.pulsar.playback.PlaylistView
import org.balch.orpheus.features.pulsar.playback.albumListing
import org.balch.orpheus.features.pulsar.playback.VibeRotation
import org.balch.orpheus.features.pulsar.playback.applyPlaylistEdit
import org.balch.orpheus.features.pulsar.playback.rotationOf
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * The playlist sheet with the final mockup's data: its content alone, and the real sheet in the real
 * chrome's stage at each layout (the stage panel under the phone bar in portrait, the side sheet on the
 * rail and dock), open as after a reveal: the 8-ball in the ring and its phrase as the caption.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*PlaylistSheetRenderHarness*' --rerun
 */
class PlaylistSheetRenderHarness {
    private val outDir = File("build/djapp-render")
    private val order = listOf(
        "Rust Belt", "Dog House", "Fire Sky .5f", "Filter Funk", "Bell Tolls", "Fire Sky",
        "Space & Drums", "Techno Wobble", "Velvet Leash", "Voltage Strut", "Lost In Space", "Stay Asleep",
    )
    // The shipped albums, as the app shows them.
    private val listing = albumListing(order)

    // Techno Wobble and Stay Asleep set aside.
    private val view = PlaylistView(VibeRotation(order, order - setOf("Techno Wobble", "Stay Asleep")), listing)

    @Test
    fun renderSheet() {
        runCatching {
            outDir.mkdirs()
            render("playlist-sheet.png", 390, 844) {
                // The sheet's own surface and content color, as OrpheusSlideUpSheet sets them.
                Surface(color = OrpheusColors.deepPurple, contentColor = OrpheusColors.onSurfaceDark) {
                    PlaylistContent(view, "Dog House", "Outlook groovy", onEdit = {}, onShuffle = {}, onPlay = {})
                }
            }
        }
    }

    /** Anomalies queued under Dog House: its rows lifted, mid-flight, and settled at the top of Up next. */
    @Test
    fun renderAnAlbumQueuing() {
        runCatching {
            outDir.mkdirs()
            val prefs = VibePlaylistPrefs(order, removed = setOf("Techno Wobble", "Stay Asleep"))
            val scene = PlaylistScene(view, "Dog House", 390, 844, density = 2f)
            try {
                scene.click(Album.ANOMALIES.title)
                scene.idle(192)
                File(outDir, "playlist-queue-1-lifted.png").writeBytes(scene.png())
                scene.idle(64)
                val edit = scene.edits.single()
                scene.view = PlaylistView(rotationOf(order, applyPlaylistEdit(prefs, order, { listing[it].orEmpty() }, edit)), listing)
                scene.idle(112)
                File(outDir, "playlist-queue-2-midflight.png").writeBytes(scene.png())
                scene.idle(400)
                File(outDir, "playlist-queue-3-landed.png").writeBytes(scene.png())
                scene.settle()
                File(outDir, "playlist-queue-4-settled.png").writeBytes(scene.png())
            } finally {
                scene.close()
            }
        }.onFailure { println("[render-harness] album queue skipped: $it") }
    }

    private val pulsar = object : PulsarFeature by PulsarViewModel.previewFeature() {
        override val playlistFlow: StateFlow<PlaylistView> = MutableStateFlow(view)
        override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(VibeNavState(currentName = "Dog House"))
    }

    @Test
    fun renderSheetInTheChrome() {
        // Phone portrait, the rail, a narrow rail stage, and the dock.
        listOf(360 to 780, 780 to 360, 600 to 420, 1280 to 800).forEach { (w, h) ->
            runCatching {
                outDir.mkdirs()
                render("playlist-sheet-${w}x$h.png", w, h) { OpenInChrome() }
            }.onFailure { println("[render-harness] playlist ${w}x$h skipped: $it") }
        }
    }

    /** The phone's list scrolled to its end: its last rows and Reset order clear the ring and the caption. */
    @Test
    fun renderPortraitScrolledToTheEnd() {
        runCatching {
            outDir.mkdirs()
            render("playlist-sheet-360x780-end.png", 360, 780, scroll = Offset(360f, 1_000f)) { OpenInChrome() }
        }.onFailure { println("[render-harness] playlist scrolled skipped: $it") }
    }

    /** Open as after a reveal: the ball in the ring, its phrase the caption. */
    @Composable
    private fun OpenInChrome() {
        val ball = remember {
            EightBallRevealState(openSheet = {}, closeSheet = {}, random = Random(7)).apply { openNow() }
        }
        CompositionLocalProvider(LocalEightBall provides ball) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0D0D1A))) {
                DjLayoutBox(Modifier.fillMaxSize()) { layout -> SheetInChrome(layout, pulsar) }
            }
        }
    }

    /** DjAppScreen's own nesting: the sheet opens from the stage slot of the layout's chrome. */
    @Composable
    private fun SheetInChrome(layout: DjLayout, pulsar: PulsarFeature) {
        val stage: @Composable () -> Unit = {
            PlaylistSheet(pulsar, isLandscape = layout.usesLandscapeChrome(), open = true, onDismiss = {})
        }
        if (layout == DjLayout.LargeScreen) {
            DjAppTvChrome(
                tvHardware = false, domeRingSize = BarRingSize, barGlass = false, vizHidesPanelsWhenIdle = false,
                focusRegion = remember { TvFocusRegionHolder() }, vizFeature = VizViewModel.previewFeature(),
                pulsarFeature = pulsar, timerFeature = TimerViewModel.previewFeature(), onTogglePlayback = {},
                dockablePanels = largeScreenPanels(), dockedPanels = emptyList(), activeSheet = null, tabs = djTabs,
                onToggleDocked = {}, onActiveSheetChange = {}, stage = stage,
            )
        } else {
            DjAppNavScaffold(
                isSelected = { false }, onItemClick = {}, layout = layout, pulsarFeature = pulsar,
                timerFeature = TimerViewModel.previewFeature(), onTogglePlayback = {}, modifier = Modifier.fillMaxSize(),
                content = stage,
            )
        }
    }

    // Past the sheet's slide-in before the frame is kept; a [scroll] wheels the list at that pixel to its end.
    private fun render(name: String, w: Int, h: Int, scroll: Offset? = null, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(w * 2, h * 2, Density(2f)) { OrpheusTheme(content = content) }
        try {
            listOf(0L, 100L, 300L, 1_000L).forEach { ms -> scene.render(ms * 1_000_000L).close() }
            if (scroll != null) {
                // The wheel's scroll animates, so it runs on frames.
                (0 until 40).forEach { i ->
                    val ms = 1_016L + i * 16
                    scene.sendPointerEvent(PointerEventType.Scroll, scroll, scrollDelta = Offset(0f, 5f), timeMillis = ms)
                    scene.render(ms * 1_000_000L).close()
                }
                (0 until 40).forEach { i -> scene.render((1_700L + i * 7) * 1_000_000L).close() }
            }
            File(outDir, name).writeBytes(scene.render(2_000_000_000L).encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }
}
