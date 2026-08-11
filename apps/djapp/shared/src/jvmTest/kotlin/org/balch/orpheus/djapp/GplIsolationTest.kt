package org.balch.orpheus.djapp

import kotlin.test.Test
import kotlin.test.fail

/**
 * This app ships commercially and must not link the MI Grids pattern ROM, which is GPL-3.0
 * (Grids is AVR firmware; the STM32F modules this project also uses are MIT).
 *
 * Class loading is the check rather than a source grep because GPL attaches to what is
 * distributed, not what is called -- a class ships whether or not anything references it.
 */
class GplIsolationTest {

    @Test
    fun `no gpl grids code on the runtime classpath`() {
        val leaked = FORBIDDEN.filter { it.isOnClasspath() }
        if (leaked.isNotEmpty()) {
            fail(
                "GPL-3.0 classes reachable from this app: ${leaked.joinToString()}\n" +
                    "Look for a new api(...) edge reaching :core:plugins:drum-patterns."
            )
        }
    }

    private companion object {
        val FORBIDDEN = listOf(
            // Pre-split home, kept so the check still fails if the move is reverted.
            "org.balch.orpheus.plugins.drum.GridsPatternData",
            "org.balch.orpheus.plugins.drum.DrumBeatsGenerator",
            // Post-split home.
            "org.balch.orpheus.plugins.drumpatterns.GridsPatternData",
            "org.balch.orpheus.plugins.drumpatterns.DrumBeatsGenerator",
        )

        // initialize=false: presence is the question, running static initialisers is not.
        fun String.isOnClasspath(): Boolean =
            try {
                Class.forName(this, false, GplIsolationTest::class.java.classLoader)
                true
            } catch (_: ClassNotFoundException) {
                false
            }
    }
}
