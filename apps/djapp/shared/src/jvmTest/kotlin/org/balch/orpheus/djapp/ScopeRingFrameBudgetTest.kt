package org.balch.orpheus.djapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.pulsar.MusicPulse
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Frame budgets for the ring tracing the scope, beside FrameBudgetTest's S2 and S9: JVM bytes
 * allocated and composable scopes recomposed per frame with a new scope window every frame.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*ScopeRingFrameBudgetTest*' --rerun
 */
class ScopeRingFrameBudgetTest {
    private val songPosition = SongPosition("Rust Belt", positionMs = 74_400L, durationMs = 120_000L)
    private val pulse = MusicPulse(0.6f, FloatArray(8) { if (it == 0 || it == 3) 0.5f else 0f }, 0.25f, 500f)

    // Pre-built, so publishing one allocates nothing: tones, a chord, and every fourth untriggered.
    private val windows = Array(16) { i -> if (i % 5 == 4) chordWindow(0.05f + i / 40f) else sineWindow(2f + i % 5, 0.02f + i / 20f) }
    private val step = { scope: TestScopeFrame -> BeforeFrame { scope.publish(windows[it % windows.size], it % 4 != 3) } }

    private fun report(line: String) = println("[frame-budget] $line")

    private fun <T> FrameMeter.use(block: (FrameMeter) -> T): T = try { block(this) } finally { close() }

    private fun ringMeter(ringSize: Dp, scope: TestScopeFrame?): FrameMeter {
        val scopes = ScopeCounter()
        val size = ((ringSize + 8.dp).value * 2).toInt()
        val scene = ImageComposeScene(size, size, Density(2f)) {
            ObserveScopes(scopes)
            val feed = if (scope != null) ScopeFeed(scope) {} else null
            CompositionLocalProvider(LocalScopeFeed provides feed, LocalTelevisionHardware provides false) {
                VibeTransportRing(paused = false, progress = 0.62f, ringSize = ringSize, position = songPosition, pulse = { pulse })
            }
        }
        return FrameMeter(scene, scopes = scopes)
    }

    /** A scope that published one window and then no more: the ring holds that trace, still redrawn as progress runs. */
    private fun heldScope() = TestScopeFrame().apply { publish(windows[0], triggered = true) }

    @Test
    fun theScopeRingTracesEachNewWindowWithoutGarbageOrRecomposing() {
        for (ringSize in listOf(BarRingSize, 144.dp)) {
            val held = ringMeter(ringSize, heldScope()).use { it.bytesPerFrame() }
            val scope = TestScopeFrame()
            ringMeter(ringSize, scope).use { ring ->
                val bytes = ring.bytesPerFrame(before = step(scope))
                val scopes = ring.scopesPerFrame(before = step(scope))
                report(
                    "S2s ${ringSize.value.toInt()}dp scope ring, a new window every frame: ${bytes - baseline} B/frame over S0, " +
                        "${bytes - held} over a held trace, $scopes scopes/frame",
                )
                assertEquals(0.0, scopes, "a new scope window recomposed the ${ringSize.value}dp ring")
                assertTrue(bytes - baseline < ScopeRingFrameBudget, "a ${ringSize.value}dp scope ring frame allocated ${bytes - baseline} B over S0")
                assertTrue(bytes - held < ScopeReadBudget, "a new window cost the ${ringSize.value}dp ring ${bytes - held} B/frame over a held trace")
            }
        }
    }

    private fun playing(): PulsarFeature {
        val base = PulsarViewModel.previewFeature()
        return object : PulsarFeature by base {
            override val stateFlow: StateFlow<PulsarUiState> = MutableStateFlow(base.stateFlow.value.copy(globalPaused = false))
            override val vibeNavFlow: StateFlow<VibeNavState> = MutableStateFlow(
                VibeNavState("Rust", "Dog", "Sky", progress = 0.62f, positionMs = 74_400L, durationMs = 120_000L),
            )
            override val musicPulseFlow: StateFlow<MusicPulse> = MutableStateFlow(pulse)
        }
    }

    // The phone bar and the rail as the app draws them: the feed at the root, glass over a backdrop.
    private fun navMeter(layout: DjLayout, widthDp: Int, heightDp: Int, scope: TestScopeFrame?): FrameMeter {
        val scopes = ScopeCounter()
        val pulsar = playing()
        val scene = ImageComposeScene(widthDp * 2, heightDp * 2, Density(2f)) {
            ObserveScopes(scopes)
            val feed = if (scope != null) ScopeFeed(scope) {} else null
            CompositionLocalProvider(LocalTelevisionHardware provides false, LocalScopeFeed provides feed) {
                OrpheusTheme {
                    val liquid = rememberLiquidState()
                    CompositionLocalProvider(LocalLiquidState provides liquid) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF203050)).liquefiable(liquid))
                            DjAppNavScaffold(
                                isSelected = { it == DjTab }, onItemClick = {}, layout = layout, pulsarFeature = pulsar,
                                timerFeature = TimerViewModel.previewFeature(), onTogglePlayback = {}, modifier = Modifier.fillMaxSize(),
                            ) { Box(Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
        }
        return FrameMeter(scene, scopes = scopes)
    }

    @Test
    fun aNewWindowEveryFrameNeverRecomposesThePhoneBarOrTheRail() {
        listOf(Triple(DjLayout.Portrait, 360, 780), Triple(DjLayout.Landscape, 800, 360)).forEach { (layout, w, h) ->
            val held = navMeter(layout, w, h, heldScope()).use { it.bytesPerFrame() }
            val scope = TestScopeFrame()
            navMeter(layout, w, h, scope).use { nav ->
                val bytes = nav.bytesPerFrame(before = step(scope))
                val scopes = nav.scopesPerFrame(before = step(scope))
                report("S9s $layout nav, a new scope window every frame: ${bytes - held} B/frame over a held trace, $scopes scopes/frame")
                assertEquals(0.0, scopes, "a new scope window recomposed the $layout nav")
                assertTrue(bytes - held < ScopeReadBudget, "a new window cost the $layout nav ${bytes - held} B/frame over a held trace")
            }
        }
    }

    companion object {
        /** S0 as FrameBudgetTest measures it: a bare frame loop and one redrawn Canvas. */
        private val baseline: Long by lazy {
            val value = mutableFloatStateOf(0f)
            FrameMeter(
                ImageComposeScene(128, 128, Density(2f)) {
                    LaunchedEffect(Unit) { while (true) withFrameNanos { } }
                    Canvas(Modifier.size(64.dp)) { drawCircle(Color.White, 8f + value.floatValue) }
                },
            ).let { meter ->
                try {
                    meter.bytesPerFrame { value.floatValue = (it % 16).toFloat() }
                } finally {
                    meter.close()
                }
            }.also { println("[frame-budget] S0 baseline: $it B/frame") }
        }

        /**
         * A playing scope ring's frame over S0, a new window every frame: measured 312 B, in the same
         * full-suite run as FrameBudgetTest's; 1.5x, as S2's ring budget, below the ring's 529 B before.
         */
        private const val ScopeRingFrameBudget = 470L

        /** A new window against a held trace: JIT noise only, as S9's pulse read. */
        private const val ScopeReadBudget = 150L
    }
}
