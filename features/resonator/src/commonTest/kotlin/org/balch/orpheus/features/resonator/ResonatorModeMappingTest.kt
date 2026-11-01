package org.balch.orpheus.features.resonator

import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.enumSetter
import org.balch.orpheus.core.plugin.ControlPort
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol
import org.balch.orpheus.plugins.resonator.ResonatorPlugin
import kotlin.test.Test
import kotlin.test.assertEquals

class ResonatorModeMappingTest {

    @Test
    fun `each mode sends the int the C++ resonator switch expects`() {
        // OrpheusResonator::process: 0 = Modal, 1 = Sympathetic, 2 = String
        val expected = mapOf(
            ResonatorMode.MODAL to 0,
            ResonatorMode.SYMPATHETIC to 1,
            ResonatorMode.STRING to 2,
        )
        val port = MutableStateFlow<PortValue>(PortValue.IntValue(-1))
        val setMode = port.enumSetter<ResonatorMode>()
        expected.forEach { (mode, engineValue) ->
            setMode(mode)
            assertEquals(PortValue.IntValue(engineValue), port.value, "mode $mode")
        }
    }

    @Test
    fun `mode labels match the plugin port options`() {
        val modePort = ResonatorPlugin(NoOpAudioEngine).ports
            .filterIsInstance<ControlPort>()
            .single { it.symbol == ResonatorSymbol.MODE.symbol }
        assertEquals(modePort.enumLabels, ResonatorMode.entries.map { it.displayName })
    }
}

private object NoOpAudioEngine : AudioEngine {
    override fun start() {}
    override fun stop() {}
    override val isRunning = false
    override val sampleRate = 48_000
    override fun getCpuLoad() = 0f
    override fun getCurrentTime() = 0.0
    override fun setPort(uri: String, symbol: String, value: Float) {}
}
