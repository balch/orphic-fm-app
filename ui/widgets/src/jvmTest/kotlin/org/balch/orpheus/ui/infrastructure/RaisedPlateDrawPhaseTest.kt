package org.balch.orpheus.ui.infrastructure

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The raised plate with its accent read in draw looks exactly like the one handed a colour, and
 * follows a new accent without recomposing.
 *
 * ./gradlew :ui:widgets:jvmTest --tests '*RaisedPlateDrawPhaseTest*' --rerun
 */
class RaisedPlateDrawPhaseTest {
    private val shape = RoundedCornerShape(8.dp)
    private val pink = Color(0xFFFF5AA0)
    private val cyan = Color(0xFF00E5FF)

    private fun render(content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(200, 100, Density(2f)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF14141F)).padding(12.dp)) { content() }
        }
        return try {
            scene.render()
            scene.render().encodeToData()!!.bytes
        } finally {
            scene.close()
        }
    }

    @Test
    fun theDrawPhasePlateMatchesTheColourOne() {
        listOf(pink, cyan).forEach { accent ->
            val composed = render { Box(Modifier.size(72.dp, 26.dp).orpheusRaisedPlate(shape = shape, accent = accent)) }
            val drawn = render { Box(Modifier.size(72.dp, 26.dp).orpheusRaisedPlate(shape = shape, accent = { accent })) }
            assertContentEquals(composed, drawn, "the plates differ in $accent")
        }
    }

    @Test
    fun aNewAccentRedrawsThePlateWithoutRecomposing() {
        val accent = mutableStateOf(pink)
        var compositions = 0
        val scene = ImageComposeScene(200, 100, Density(2f)) {
            SideEffect { compositions++ }
            Box(Modifier.fillMaxSize().background(Color(0xFF14141F)).padding(12.dp)) {
                Box(Modifier.size(72.dp, 26.dp).orpheusRaisedPlate(shape = shape, accent = { accent.value }))
            }
        }
        try {
            scene.render()
            val before = scene.render().encodeToData()!!.bytes
            val composedBefore = compositions
            accent.value = cyan
            Snapshot.sendApplyNotifications()
            val after = scene.render().encodeToData()!!.bytes
            assertEquals(composedBefore, compositions, "a new accent recomposed the plate")
            assertFalse(before.contentEquals(after), "a new accent never reached the plate")
            val cyanPlate = render { Box(Modifier.size(72.dp, 26.dp).orpheusRaisedPlate(shape = shape, accent = cyan)) }
            assertContentEquals(cyanPlate, after, "the redrawn plate is not the cyan one")
        } finally {
            scene.close()
        }
    }
}
