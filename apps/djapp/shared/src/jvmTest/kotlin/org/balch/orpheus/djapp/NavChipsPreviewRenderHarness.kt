package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.balch.orpheus.core.onboarding.OnboardingSequencer
import org.balch.orpheus.core.preferences.OnboardingProgress
import org.balch.orpheus.djapp.variant.DjEdition
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/** ./gradlew :apps:djapp:shared:jvmTest --tests '*NavChipsPreviewRenderHarness*' --rerun -PrenderHarness */
@OptIn(ExperimentalCoroutinesApi::class)
class NavChipsPreviewRenderHarness {
    /** One launch's bar, written out at each of [shots]: millis since the bar showed, to a name. */
    private fun film(tag: String, preferences: MemoryPreferences, shots: Map<Long, String>, w: Int = 390, h: Int = 420, layout: DjLayout = DjLayout.Portrait) {
        val outDir = File("build/djapp-render").apply { mkdirs() }
        val scheduler = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val onboarding = OnboardingSequencer(djOnboardingQueue(longPressTour = true), preferences, CoroutineScope(dispatcher))
        val scene = ImageComposeScene(w * 2, h * 2, Density(2f), dispatcher) {
            CompositionLocalProvider(LocalOnboarding provides onboarding) {
                OrpheusTheme {
                    DjAppNavScaffold(
                        isSelected = { it == DjTab }, onItemClick = {}, layout = layout,
                        pulsarFeature = PulsarViewModel.previewFeature(),
                        timerFeature = TimerViewModel.previewFeature(), onTogglePlayback = {},
                        navOrder = defaultNavOrder(navPanels(DjEdition.OG)),
                        navActions = NavActions({ _, _ -> }, { _, _ -> }, {}),
                        modifier = Modifier.fillMaxSize().background(Color(0xFF0B0E14)),
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        try {
            var now = 0L
            while (now <= shots.keys.max()) {
                now += 16
                scheduler.advanceTimeBy(16)
                scheduler.runCurrent()
                val image = scene.render(now * 1_000_000)
                shots.entries.firstOrNull { now - 16 < it.key && it.key <= now }?.let { (_, name) ->
                    File(outDir, "chips-preview-$tag-$name.png").writeBytes(image.encodeToData()!!.bytes)
                }
            }
        } finally {
            scene.close()
        }
    }

    private val beat = NavChipsPreviewDelayMillis
    private val pressed = beat + NavChipsPreviewPressMillis

    @Test
    fun renderFirstLaunch() = film(
        "first", MemoryPreferences(),
        mapOf(
            beat + 200 to "1-press-start",
            pressed - 50 to "2-pressed",
            pressed + NavChipsPreviewLiftMillis + 500 to "3-raised",
            pressed + NavChipsPreviewLiftMillis + NavChipsPreviewHoldMillis + NavChipsPreviewSettleMillis / 2 to "4-settling",
            pressed + NavChipsPreviewLiftMillis + NavChipsPreviewHoldMillis + NavChipsPreviewSettleMillis + 100 to "5-settled",
        ),
    )

    @Test
    fun renderLaterLaunch() = film(
        "later",
        MemoryPreferences().apply {
            prefs = prefs.copy(onboarding = mapOf(NavLongPressStep.id to OnboardingProgress(shows = 1)))
        },
        mapOf(beat + 1_000 to "reminder"),
    )

    @Test
    fun renderRail() = film(
        "rail", MemoryPreferences(),
        mapOf(pressed + NavChipsPreviewLiftMillis + 500 to "raised"),
        w = 800, h = 360, layout = DjLayout.Landscape,
    )
}
