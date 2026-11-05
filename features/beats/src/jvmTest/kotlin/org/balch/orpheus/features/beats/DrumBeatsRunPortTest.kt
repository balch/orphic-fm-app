package org.balch.orpheus.features.beats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol
import org.balch.orpheus.core.tempo.GlobalTempo
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DrumBeatsRunPortTest {
    /** The sequencer only reads the clock and fires drums, so everything else can stay blank. */
    private val synthEngine = Proxy.newProxyInstance(
        SynthEngine::class.java.classLoader,
        arrayOf(SynthEngine::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Integer.TYPE -> 0
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    } as SynthEngine

    private val audioEngine = object : AudioEngine {
        override fun start() {}
        override fun stop() {}
        override val isRunning = false
        override val sampleRate = 48000
        override fun getCpuLoad() = 0f
        override fun getCurrentTime() = 0.0
    }

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Default
        override val io = Dispatchers.Default
        override val default = Dispatchers.Default
        override val unconfined = Dispatchers.Unconfined
    }

    private val ports = mutableMapOf<PluginControlId, PortValue>()
    private val controller = SynthController().apply {
        setDelegates(
            setter = { id, value -> ports[id] = value; true },
            getter = { ports[it] ?: PortValue.BoolValue(false) },
        )
    }
    private val scope = FeatureCoroutineScope()
    private val viewModel = DrumBeatsViewModel(synthEngine, controller, dispatchers, GlobalTempo(audioEngine), scope)

    @AfterTest
    fun stopTheClock() = scope.cancel()

    private fun awaitRunning(running: Boolean) = runBlocking {
        withTimeout(5_000) { viewModel.stateFlow.first { it.isRunning == running } }
    }

    @Test
    fun aRunPortWriteStartsAndStopsTheSequencer() {
        val run = BeatsSymbol.RUN.controlId
        controller.setPluginControl(run, PortValue.FloatValue(1f), ControlEventOrigin.MIDI)
        awaitRunning(true)
        controller.setPluginControl(run, PortValue.FloatValue(0f), ControlEventOrigin.MIDI)
        awaitRunning(false)
    }

    @Test
    fun thePanelWritesThePortSoALearnedToggleSeesIt() {
        viewModel.actions.setRunning(true)
        awaitRunning(true)
        assertEquals(PortValue.BoolValue(true), ports[BeatsSymbol.RUN.controlId])
    }
}
