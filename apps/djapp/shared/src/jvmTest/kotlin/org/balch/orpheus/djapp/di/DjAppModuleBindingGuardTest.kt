package org.balch.orpheus.djapp.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PlaybackController takes `overlayProducer: OverlaySubtitleProducer? = null`, and Metro keys `T`
 * and `T?` apart. A non-null binding compiles and runs, and silently drops the sleep-timer
 * countdown. Booting the real graph needs native audio, so this pins it.
 */
class DjAppModuleBindingGuardTest {
    private val source =
        File("src/commonMain/kotlin/org/balch/orpheus/djapp/di/DjAppModule.kt").readText()

    @Test
    fun overlayBindingKeepsTheNullableKey() {
        val binding = Regex(
            """@Binds\s+val\s+TimerOverlayProducer\.bindOverlayProducer:\s*OverlaySubtitleProducer\?""",
        )
        assertTrue(binding.containsMatchIn(source), "bind TimerOverlayProducer to OverlaySubtitleProducer?")
    }
}
