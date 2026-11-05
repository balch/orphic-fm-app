package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.balch.orpheus.djapp.variant.DjEdition
import org.balch.orpheus.core.onboarding.OnboardingSequencer
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test

/**
 * The two callouts on the wide bar, to look at: PNGs in build/nav-callout-render/.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*NavCalloutRenderHarness*' -PrenderHarness
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class NavCalloutRenderHarness {
    private val bar = navBar(defaultNavOrder(navPanels(DjEdition.OG)), navDrops(DjLayout.LargeScreen), 4)

    private fun render(width: Int, name: String, millis: Long) = runDesktopComposeUiTest(width, 300) {
        mainClock.autoAdvance = false
        val onboarding = OnboardingSequencer(djOnboardingQueue(longPressTour = true), MemoryPreferences(), CoroutineScope(Dispatchers.Unconfined))
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalOnboarding provides onboarding,
                LocalTelevisionHardware provides false,
            ) {
                OrpheusTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1A1E28)), contentAlignment = Alignment.BottomCenter) {
                        DjTvBottomBar(
                            nav = bar,
                            navActions = NavActions({ _, _ -> }, { _, _ -> }, {}),
                            isDocked = { false },
                            onToggle = {},
                            pulsarFeature = PulsarViewModel.previewFeature(),
                            onTogglePlayback = {},
                            modifier = Modifier.width(width.dp),
                        )
                    }
                }
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(millis)
        waitForIdle()
        val out = File("build/nav-callout-render").apply { mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    private val press = NavChipsPreviewDelayMillis + NavChipsPreviewPressMillis / 2 + OnboardingFade
    private val row = NavChipsPreviewDelayMillis + NavChipsPreviewPressMillis + NavChipsPreviewLiftMillis +
        NavChipsPreviewHoldMillis + OnboardingFade

    @Test
    fun wide() {
        render(1000, "wide_press", press)
        render(1000, "wide_row", row)
    }

    private companion object {
        const val OnboardingFade = 450L
    }
}
