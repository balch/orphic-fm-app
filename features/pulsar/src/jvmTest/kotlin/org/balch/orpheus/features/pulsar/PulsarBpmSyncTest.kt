package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.tempo.GlobalTempo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PulsarBpmSyncTest {

    private class TestAudioEngine : AudioEngine {
        override fun start() {}
        override fun stop() {}
        override val isRunning: Boolean = false
        override val sampleRate: Int = 44100
        override fun getCpuLoad(): Float = 0f
        override fun getCurrentTime(): Double = 0.0
    }

    private class TestDispatcherProvider(
        private val dispatcher: CoroutineDispatcher
    ) : DispatcherProvider {
        override val main get() = dispatcher
        override val io get() = dispatcher
        override val default get() = dispatcher
        override val unconfined get() = dispatcher
    }

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Creates a SynthController with a port store.
     * @param presetBpm If non-null, pre-seeds the BPM port to simulate the engine
     *   having the plugin default (120) already stored — as happens in the real app.
     */
    private fun createSynthController(presetBpm: Float? = null): SynthController {
        val ports = mutableMapOf<String, PortValue>()
        if (presetBpm != null) {
            val bpmKey = "${PulsarSymbol.BPM.controlId.uri}:${PulsarSymbol.BPM.controlId.symbol}"
            ports[bpmKey] = FloatValue(presetBpm)
        }
        val controller = SynthController()
        controller.setDelegates(
            setter = { id, value ->
                ports["${id.uri}:${id.symbol}"] = value
                true
            },
            getter = { id ->
                ports["${id.uri}:${id.symbol}"]
            }
        )
        return controller
    }

    @Test
    fun `controlFlow seeds from engine getter - returns null when no stored value`() = runTest(testDispatcher) {
        val controller = createSynthController()

        val bpmFlow = controller.controlFlow(PulsarSymbol.BPM.controlId)
        println("BPM flow initial value: ${bpmFlow.value.asFloat()}")

        // controlFlow seeds from engine getter, which returns null -> defaults to FloatValue(0.5f)
        assertEquals(0.5f, bpmFlow.value.asFloat(), "controlFlow defaults to 0.5 when engine has no stored value")
    }

    @Test
    fun `GlobalTempo set to 90 before ViewModel creation - Pulsar state should show 90`() = runTest(testDispatcher) {
        val controller = createSynthController()
        val globalTempo = GlobalTempo(TestAudioEngine())

        // Simulate: user sets BPM to 90 via DrumBeats BEFORE Pulsar ViewModel is created
        globalTempo.setBpm(90.0)
        assertEquals(90.0, globalTempo.getBpm(), "GlobalTempo should be 90")

        val scope = FeatureCoroutineScope()
        val dispatchers = TestDispatcherProvider(testDispatcher)

        val vm = PulsarViewModel(
            synthController = controller,
            globalTempo = globalTempo,
            dispatcherProvider = dispatchers,
            scope = scope,
        )

        // Advance coroutines so the init block's collect runs
        advanceUntilIdle()

        val state = vm.stateFlow.value
        println("State BPM after construction: ${state.bpm}")
        println("GlobalTempo BPM: ${globalTempo.getBpm()}")
        println("BPM controlFlow value: ${controller.controlFlow(PulsarSymbol.BPM.controlId).value.asFloat()}")

        assertEquals(90f, state.bpm, "Pulsar BPM should sync to GlobalTempo's 90 at startup")
    }

    @Test
    fun `GlobalTempo change after ViewModel creation propagates to Pulsar state`() = runTest(testDispatcher) {
        val controller = createSynthController()
        val globalTempo = GlobalTempo(TestAudioEngine())

        val scope = FeatureCoroutineScope()
        val dispatchers = TestDispatcherProvider(testDispatcher)

        val vm = PulsarViewModel(
            synthController = controller,
            globalTempo = globalTempo,
            dispatcherProvider = dispatchers,
            scope = scope,
        )

        advanceUntilIdle()
        println("Initial state BPM: ${vm.stateFlow.value.bpm}")

        // Now change GlobalTempo externally
        globalTempo.setBpm(90.0)
        advanceUntilIdle()

        val state = vm.stateFlow.value
        println("State BPM after GlobalTempo change to 90: ${state.bpm}")

        assertEquals(90f, state.bpm, "Pulsar BPM should update when GlobalTempo changes")
    }

    @Test
    fun `RACE - GlobalTempo at 90 before VM creation - stateFlow initialValue must be correct`() = runTest(testDispatcher) {
        val controller = createSynthController()
        val globalTempo = GlobalTempo(TestAudioEngine())
        globalTempo.setBpm(90.0)

        val scope = FeatureCoroutineScope()
        val dispatchers = TestDispatcherProvider(testDispatcher)

        val vm = PulsarViewModel(
            synthController = controller,
            globalTempo = globalTempo,
            dispatcherProvider = dispatchers,
            scope = scope,
        )

        // DO NOT advance - check the stateFlow.value IMMEDIATELY after construction
        // This is what the UI sees on the first frame
        val immediateState = vm.stateFlow.value
        println("IMMEDIATE state BPM (no advance): ${immediateState.bpm}")
        println("BPM controlFlow value: ${controller.controlFlow(PulsarSymbol.BPM.controlId).value.asFloat()}")

        assertEquals(90f, immediateState.bpm,
            "Pulsar stateFlow.value must be 90 IMMEDIATELY - the UI reads this on first frame")
    }

    @Test
    fun `REAL APP - engine has plugin default 120 but GlobalTempo is 90 - Pulsar must show 90`() = runTest(testDispatcher) {
        // This simulates the real app scenario:
        // 1. PulsarPlugin initializes with _bpm = 120 (plugin default)
        // 2. Engine stores this value
        // 3. Preset loads and sets GlobalTempo to 90
        // 4. PulsarViewModel is created lazily when panel renders
        // 5. controlFlow seeds bpmId from engine (120)
        // 6. But GlobalTempo is already at 90 — Pulsar must show 90

        val controller = createSynthController(presetBpm = 120f) // engine has plugin default
        val globalTempo = GlobalTempo(TestAudioEngine())
        globalTempo.setBpm(90.0) // preset already loaded

        val scope = FeatureCoroutineScope()
        val dispatchers = TestDispatcherProvider(testDispatcher)

        val vm = PulsarViewModel(
            synthController = controller,
            globalTempo = globalTempo,
            dispatcherProvider = dispatchers,
            scope = scope,
        )

        // Check IMMEDIATELY - no advanceUntilIdle
        val immediateState = vm.stateFlow.value
        println("Engine BPM port: ${controller.controlFlow(PulsarSymbol.BPM.controlId).value.asFloat()}")
        println("GlobalTempo BPM: ${globalTempo.getBpm()}")
        println("IMMEDIATE state BPM: ${immediateState.bpm}")

        assertEquals(90f, immediateState.bpm,
            "Even though engine has 120, Pulsar must show GlobalTempo's 90 on first frame")
    }

    @Test
    fun `Pulsar setBpm action propagates to GlobalTempo`() = runTest(testDispatcher) {
        val controller = createSynthController()
        val globalTempo = GlobalTempo(TestAudioEngine())

        val scope = FeatureCoroutineScope()
        val dispatchers = TestDispatcherProvider(testDispatcher)

        val vm = PulsarViewModel(
            synthController = controller,
            globalTempo = globalTempo,
            dispatcherProvider = dispatchers,
            scope = scope,
        )

        advanceUntilIdle()

        // User adjusts Pulsar BPM knob to 140
        vm.actions.setBpm(140f)
        advanceUntilIdle()

        println("GlobalTempo after Pulsar setBpm(140): ${globalTempo.getBpm()}")
        assertEquals(140.0, globalTempo.getBpm(), "GlobalTempo should update when Pulsar BPM knob changes")

        println("Pulsar state BPM: ${vm.stateFlow.value.bpm}")
        assertEquals(140f, vm.stateFlow.value.bpm, "Pulsar state should reflect the new BPM")
    }
}
