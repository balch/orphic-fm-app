package org.balch.orpheus.features.horn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.FeatureStatePersistence
import org.balch.orpheus.core.features.RestoreStrategy
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import org.balch.orpheus.core.plugin.symbols.HornSymbol
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A horn the user cannot see must not run. The DJ app's AI edition replaces the Horn tab,
 * yet the persisted panel state still restores on launch: a mix of 1.0 saved from another
 * edition put a 0.7 Hz Leslie on every vibe with nothing on screen to explain the wobble.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HornHiddenPanelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val ports = mutableMapOf<String, PortValue>()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private val savedHorn = """{"speed":0.35,"ratio":0.45,"depth":0.46,"mix":1.0,"brake":false}"""

    private fun makeVm(visible: Boolean): HornViewModel {
        val controller = SynthController().apply {
            setDelegates(
                setter = { id, value -> ports[id.symbol] = value; true },
                getter = { id -> ports[id.symbol] },
            )
        }
        val scope = FeatureCoroutineScope()
        val persistence = FeatureStatePersistence(
            appPreferencesRepository = FakePrefs(AppPreferences(lastHornJson = savedHorn)),
            dispatcherProvider = UnconfinedDispatchers,
            scope = scope,
        )
        return HornViewModel(
            synthController = controller,
            dispatcherProvider = UnconfinedDispatchers,
            scope = scope,
            persistence = persistence,
            restoreStrategy = RestoreStrategy.USER_PREFERENCES,
            panelAvailability = HornPanelAvailability { visible },
        )
    }

    private fun mixPort(): Float? = (ports[HornSymbol.MIX.symbol] as? PortValue.FloatValue)?.value

    @Test
    fun `a visible panel restores the saved mix`() = runTest(testDispatcher) {
        val vm = makeVm(visible = true)
        advanceUntilIdle()
        assertEquals(1.0f, mixPort(), "the saved mix reaches the engine")
        assertEquals(1.0f, vm.stateFlow.value.mix)
    }

    @Test
    fun `a hidden panel keeps the horn off whatever was saved`() = runTest(testDispatcher) {
        val vm = makeVm(visible = false)
        advanceUntilIdle()
        assertEquals(0.0f, mixPort(), "a horn no screen can reach must not run")
        assertEquals(0.0f, vm.stateFlow.value.mix)
        assertEquals(0.35f, vm.stateFlow.value.speed, "the other knobs still restore for when the panel returns")
    }
}

private object UnconfinedDispatchers : DispatcherProvider {
    override val main = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
    override val unconfined = Dispatchers.Unconfined
}

private class FakePrefs(private var prefs: AppPreferences) : BaseAppPreferencesRepository() {
    override suspend fun load() = prefs
    override suspend fun save(preferences: AppPreferences) { prefs = preferences }
}
