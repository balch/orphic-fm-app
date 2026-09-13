package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/**
 * Renders the Pulsar macro knobs with the COMPLEXITY knob in Mode One, at 3x, so the label
 * pulse can be judged as pixels rather than imagined. Writes PNGs under `build/widgets-render`,
 * asserts nothing, and swallows every throwable so a headless box without skia natives can't
 * fail the build. Run:
 *
 *   ./gradlew :ui:widgets:jvmTest --tests '*RotaryKnobRenderHarness*' --rerun
 */
class RotaryKnobRenderHarness {

    @Test
    fun renderOneModeGlow() {
        try {
            val outDir = File("build/widgets-render").apply { mkdirs() }
            // The pulse is an infinite transition that starts on the scene's first frame, so one
            // scene is rendered twice: at its first frame (dim end) and 700 ms in (bright end).
            run {
                val scene = ImageComposeScene(900, 330, Density(3f)) {
                    OrpheusTheme {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0xFF14141F))
                                .padding(16.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RotaryKnob(
                                    value = 0.5f, onValueChange = {}, label = "ENERGY",
                                    size = 48.dp, progressColor = OrpheusColors.cosmicPurple,
                                    valueFormatter = null,
                                )
                                RotaryKnob(
                                    value = 0.3f, onValueChange = {}, label = "COMPLEXITY",
                                    size = 48.dp, progressColor = OrpheusColors.cosmicPurple,
                                    valueFormatter = null,
                                    pulseLabel = true,
                                )
                                RotaryKnob(
                                    value = 0.5f, onValueChange = {}, label = "MOOD",
                                    size = 48.dp, progressColor = OrpheusColors.cosmicPurple,
                                    valueFormatter = null,
                                )
                            }
                        }
                    }
                }
                try {
                    File(outDir, "one-mode-pulse-dim.png")
                        .writeBytes(scene.render(nanoTime = 0L).encodeToData()!!.bytes)
                    File(outDir, "one-mode-pulse-bright.png")
                        .writeBytes(scene.render(nanoTime = 700_000_000L).encodeToData()!!.bytes)
                } finally {
                    scene.close()
                }
            }
        } catch (t: Throwable) {
            println("RotaryKnobRenderHarness skipped: $t")
        }
    }
}
