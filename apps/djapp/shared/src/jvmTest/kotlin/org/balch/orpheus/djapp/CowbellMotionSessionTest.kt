package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.features.cowbell.CowbellFeature
import org.balch.orpheus.features.cowbell.CowbellPanelActions
import org.balch.orpheus.features.cowbell.CowbellUiState
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Motion's lifetime: the screen's saved state, which a tab switch and process death keep and a quit drops. */
class CowbellMotionSessionTest {

    private class Cowbell(motion: Boolean = false) : CowbellFeature {
        override val stateFlow = MutableStateFlow(CowbellUiState(motion = motion))
        override val actions = CowbellPanelActions.EMPTY.copy(
            onMotion = { on -> stateFlow.value = stateFlow.value.copy(motion = on) },
        )
        val motion get() = stateFlow.value.motion
    }

    /** The screen's `rememberSaveable` over the tab, which a tab switch takes out of composition. */
    @OptIn(InternalComposeUiApi::class)
    private fun screen(
        registry: SaveableStateRegistry,
        cowbell: Cowbell,
        tabShown: MutableState<Boolean> = mutableStateOf(true),
    ) = ImageComposeScene(360, 400, Density(1f)) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
            var saved by rememberSaveable { mutableStateOf(false) }
            OrpheusTheme {
                if (tabShown.value) {
                    CowbellRoutePanel(
                        synthEngine = RenderProbeSynthEngine(),
                        modifier = Modifier.fillMaxSize(),
                        docked = false,
                        lift = 0.dp,
                        savedMotion = saved,
                        onSaveMotion = { saved = it },
                        feature = cowbell,
                    )
                }
            }
        }
    }

    private fun ImageComposeScene.settle() = repeat(4) { i -> render(i * 16_000_000L) }

    private fun registry(restored: Map<String, List<Any?>>? = null) = SaveableStateRegistry(restored) { true }

    @Test
    fun aKilledProcessComesBackWithMotionOn() {
        val registry = registry()
        val before = Cowbell()
        val first = screen(registry, before)
        first.settle()
        before.actions.onMotion(true)
        first.settle()
        val bundle = registry.performSave()
        first.close()

        // A new process: the feature is new and only the saved state remains.
        val after = Cowbell()
        val second = screen(registry(bundle), after)
        second.settle()
        second.close()
        assertTrue(after.motion)
    }

    @Test
    fun turningItOffBeforeTheKillStaysOff() {
        val registry = registry()
        val before = Cowbell()
        val first = screen(registry, before)
        first.settle()
        before.actions.onMotion(true)
        first.settle()
        before.actions.onMotion(false)
        first.settle()
        val bundle = registry.performSave()
        first.close()

        val after = Cowbell()
        val second = screen(registry(bundle), after)
        second.settle()
        second.close()
        assertFalse(after.motion)
    }

    /** The media service can keep the process and its singleton feature alive through a quit. */
    @Test
    fun aFreshLaunchStartsOffEvenIfTheProcessLived() {
        val alive = Cowbell(motion = true)
        val scene = screen(registry(), alive)
        scene.settle()
        scene.close()
        assertFalse(alive.motion)
    }

    @Test
    fun aTabSwitchKeepsIt() {
        val tabShown = mutableStateOf(true)
        val cowbell = Cowbell()
        val scene = screen(registry(), cowbell, tabShown)
        scene.settle()
        cowbell.actions.onMotion(true)
        scene.settle()
        tabShown.value = false
        scene.settle()
        tabShown.value = true
        scene.settle()
        scene.close()
        assertTrue(cowbell.motion)
    }
}
