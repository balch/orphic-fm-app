package org.balch.orpheus.djapp.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test

/**
 * The 8-ball's faces at the dome's, the reveal's and the sheet header's sizes.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*EightBallRenderHarness*' --rerun
 */
class EightBallRenderHarness {
    private val outDir = File("build/djapp-render")

    @Test
    fun renderFaces() {
        runCatching {
            outDir.mkdirs()
            val longest = EightBallPhrases.maxBy { it.length }
            val scene = ImageComposeScene(900, 440, Density(2f)) {
                OrpheusTheme {
                    Row(
                        Modifier.fillMaxSize().background(Color(0xFF0D0D1A)).padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EightBall(EightBallFace.Eight, 56.dp)
                        EightBall(EightBallFace.Die(longest), 160.dp)
                        EightBall(EightBallFace.Die("Outlook groovy"), 38.dp)
                    }
                }
            }
            try {
                File(outDir, "eightball-faces.png").writeBytes(scene.render().encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }
    }
}
