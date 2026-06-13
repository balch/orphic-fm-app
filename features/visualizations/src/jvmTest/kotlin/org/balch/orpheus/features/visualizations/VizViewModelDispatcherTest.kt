package org.balch.orpheus.features.visualizations

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.Visualization
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ─── Fakes ───────────────────────────────────────────────────────────────────

/** A minimal [Visualization] stub with id "off" — VizViewModel uses it as initial viz. */
private class FakeOffViz : Visualization {
    override val id = "off"
    override val name = "Off"
    override val color = Color.Gray
    override val knob1Label = "N/A"
    override val knob2Label = "N/A"
    override val liquidEffects = VisualizationLiquidEffects.Default
    override fun setKnob1(value: Float) {}
    override fun setKnob2(value: Float) {}
    override fun onActivate() {}
    override fun onDeactivate() {}
    @Composable override fun Content(modifier: Modifier) {}
}

/**
 * Records calls to [onActivate] and [onDeactivate] to a shared [log].
 * The id must NOT be "off" so VizViewModel treats it as an active (non-off) visualization.
 */
private class TrackingViz(
    override val id: String,
    private val log: MutableList<String>
) : Visualization {
    override val name = "Tracking-$id"
    override val color = Color.Cyan
    override val knob1Label = "K1"
    override val knob2Label = "K2"
    override val liquidEffects = VisualizationLiquidEffects.Default
    override fun setKnob1(value: Float) {}
    override fun setKnob2(value: Float) {}
    override fun onActivate() { log += "activate:$id" }
    override fun onDeactivate() { log += "deactivate:$id" }
    @Composable override fun Content(modifier: Modifier) {}
}

private class FakePrefsRepo(
    prefs: AppPreferences = AppPreferences(randomVizMode = false)
) : BaseAppPreferencesRepository() {
    private var stored = prefs
    override suspend fun load() = stored
    override suspend fun save(preferences: AppPreferences) { stored = preferences }
}

// ─── Tests ────────────────────────────────────────────────────────────────────

/**
 * Regression guard for the threading race in VizViewModel:
 * `java.util.ConcurrentModificationException at LavaLampViz.updateBlobs` caused by
 * [Visualization.onDeactivate]/[Visualization.onActivate] being called from a background
 * thread while the main-thread Compose frame loop was iterating visualization state.
 *
 * **Strategy**: use a [StandardTestDispatcher] for `main` (requires explicit `advanceUntilIdle`
 * to drain) and an [UnconfinedTestDispatcher] for `default` (runs eagerly / immediately).
 * If lifecycle calls were incorrectly dispatched to `default`, they would execute before
 * we drain `main` — the "before advance" assertion would fail, catching the regression.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VizViewModelDispatcherTest {

    // main = StandardTestDispatcher: requires explicit draining via advanceUntilIdle().
    // default = UnconfinedTestDispatcher: runs coroutines eagerly / immediately.
    // This contrast lets the test distinguish the two dispatchers by observation timing.
    private val mainDispatcher = StandardTestDispatcher()
    private val defaultDispatcher = UnconfinedTestDispatcher()

    private val dispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcher
        override val io: CoroutineDispatcher = defaultDispatcher
        override val default: CoroutineDispatcher = defaultDispatcher
        override val unconfined: CoroutineDispatcher = defaultDispatcher
    }

    @BeforeTest
    fun setUp() {
        // FeatureCoroutineScope uses Dispatchers.Main.immediate; redirect it to our
        // StandardTestDispatcher so scope.launch(dispatcherProvider.main) is controlled.
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeVm(
        extraViz: Visualization,
        prefs: AppPreferences = AppPreferences(randomVizMode = false),
    ): VizViewModel {
        return VizViewModel(
            visualizations = setOf(FakeOffViz(), extraViz),
            appPreferencesRepository = FakePrefsRepo(prefs),
            synthController = SynthController(),
            dispatcherProvider = dispatcherProvider,
            scope = FeatureCoroutineScope(),
        )
    }

    @Test
    fun `selectVisualization does not call lifecycle before main dispatcher drains`() =
        runTest(mainDispatcher) {
            val log = mutableListOf<String>()
            val lava = TrackingViz(id = "lava", log)
            val vm = makeVm(lava)

            // Drain init (preference load on default runs eagerly; no viz switch because
            // lastVizId is null and randomVizMode is false).
            advanceUntilIdle()
            log.clear() // discard any init-time activations

            // selectVisualization posts a coroutine to main (StandardTestDispatcher).
            // Because main requires explicit draining, nothing runs yet.
            vm.selectVisualization(lava, save = false)

            assertTrue(log.isEmpty(),
                "Lifecycle calls must be queued on main and NOT execute before advanceUntilIdle(). " +
                "If this fails, onDeactivate/onActivate are running on the default (background) dispatcher.")

            // Drain main — the coroutine now runs.
            advanceUntilIdle()

            assertTrue(log.contains("activate:lava"),
                "onActivate must be called after main dispatcher drains. log=$log")
        }

    @Test
    fun `selectVisualization calls onDeactivate before onActivate on main dispatcher`() =
        runTest(mainDispatcher) {
            val log = mutableListOf<String>()
            val viz1 = TrackingViz(id = "viz1", log)
            val viz2 = TrackingViz(id = "viz2", log)

            // Build a VM with three vizzes: off (initial), viz1, viz2.
            val vm = VizViewModel(
                visualizations = setOf(FakeOffViz(), viz1, viz2),
                appPreferencesRepository = FakePrefsRepo(AppPreferences(randomVizMode = false)),
                synthController = SynthController(),
                dispatcherProvider = dispatcherProvider,
                scope = FeatureCoroutineScope(),
            )
            advanceUntilIdle() // drain init
            log.clear()

            // Switch from off → viz1.
            vm.selectVisualization(viz1, save = false)
            advanceUntilIdle()
            // onDeactivate of "off" is a no-op (FakeOffViz), so only activate:viz1 appears.
            assertEquals(listOf("activate:viz1"), log)
            log.clear()

            // Switch from viz1 → viz2; must see deactivate:viz1 before activate:viz2.
            vm.selectVisualization(viz2, save = false)

            // Still nothing yet — main hasn't been advanced.
            assertTrue(log.isEmpty(),
                "Neither deactivate:viz1 nor activate:viz2 should run before main drains. log=$log")

            advanceUntilIdle()

            assertEquals(listOf("deactivate:viz1", "activate:viz2"), log,
                "Deactivate of old viz must precede activate of new viz, both on main. log=$log")
        }
}
