package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SHAKE is a real mode and the fill row sits under the mode row in every mode.
 *
 * ./gradlew :features:cowbell:jvmTest --tests '*CowbellPanelShakeTest*'
 */
class CowbellPanelShakeTest {

    private fun CowbellScene.pill(label: String): SemanticsNode =
        assertNotNull(nodes().firstOrNull { it.text() == label }, "no '$label' pill")

    private fun CowbellScene.tap(node: SemanticsNode) {
        press(node.laidOut.center)
        release(node.laidOut.center)
    }

    private fun withPanel(state: CowbellUiState, recording: RecordingActions, body: (CowbellScene) -> Unit) {
        val feature = recording.feature(state)
        CowbellScene(320, 330) {
            CowbellPanelContent(feature = feature, modifier = Modifier.fillMaxSize(), isExpanded = true)
        }.use(body)
    }

    @Test
    fun shakeSelectable() {
        val recording = RecordingActions()
        withPanel(CowbellUiState(), recording) { s ->
            val shake = s.pill("SHAKE")
            assertFalse(shake.isDisabled(), "SHAKE is dimmed")
            s.tap(shake)
            assertEquals(listOf(CowbellMode.SHAKE), recording.modes)
        }
    }

    @Test
    fun fillRowDimmedOutsideShake() {
        for (mode in CowbellMode.entries) {
            val recording = RecordingActions()
            withPanel(CowbellUiState(mode = mode), recording) { s ->
                for (fill in CowbellFill.entries) {
                    val pill = s.pill(fill.name)
                    assertEquals(mode != CowbellMode.SHAKE, pill.isDisabled(), "$mode: ${fill.name} disabled is wrong")
                }
                if (mode == CowbellMode.SHAKE) {
                    s.tap(s.pill("SEEDS"))
                    assertEquals(listOf(CowbellFill.SEEDS), recording.fills)
                } else {
                    s.tap(s.pill("SEEDS"))
                    assertTrue(recording.fills.isEmpty(), "$mode: a dimmed fill pill still picked")
                }
            }
        }
    }

    @Test
    fun theBenderPanelHasNoFillRow() {
        val recording = RecordingActions()
        val feature = recording.feature(CowbellUiState(mode = CowbellMode.SHAKE))
        CowbellScene(380, 260) {
            CowbellBenderPanel(feature = feature, modifier = Modifier.fillMaxSize(), isExpanded = true, showCollapsedHeader = false)
        }.use { s ->
            assertTrue(s.nodes().none { it.text() == "SEEDS" }, "the bender panel shows the fill row")
        }
    }

    @Test
    fun theBellReportsMotionAvailability() {
        for (available in listOf(true, false)) {
            val recording = RecordingActions()
            val feature = recording.feature(CowbellUiState())
            val motion = FakeMotion(available = available)
            CowbellScene(320, 330) {
                CompositionLocalProvider(LocalCowbellMotion provides motion) {
                    CowbellPanelContent(feature = feature, modifier = Modifier.fillMaxSize(), isExpanded = true)
                }
            }.use {
                assertEquals(listOf(available), recording.motionAvailable)
            }
        }
    }
}
