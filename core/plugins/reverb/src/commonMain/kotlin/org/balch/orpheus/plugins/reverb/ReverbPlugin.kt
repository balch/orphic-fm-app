package org.balch.orpheus.plugins.reverb

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspFactory
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.REVERB_URI
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol

/**
 * Reverb Plugin — Dattorro plate reverb ported from Mutable Instruments Rings.
 *
 * Parallel send effect alongside the delay. Both receive from distortion output,
 * both feed stereo sum independently.
 *
 * Controls: AMOUNT, TIME, DAMPING, DIFFUSION
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class ReverbPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Reverb",
        author = "Balch"
    )

    companion object {
        const val URI = REVERB_URI
    }

    // Core reverb unit (stereo)
    private val reverbUnit = dspFactory.createReverbUnit()

    // Internal state
    private var _amount = 0f
    private var _time = 0.5f
    private var _damping = 0.7f
    private var _diffusion = 0.625f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 4) {
        controlPort(ReverbSymbol.AMOUNT) {
            floatType {
                default = 0f
                get { _amount }
                set { _amount = it; reverbUnit.setAmount(it); reverbUnit.setBypass(it <= 0.001f) }
            }
        }

        controlPort(ReverbSymbol.TIME) {
            floatType {
                default = 0.5f
                get { _time }
                set { _time = it; reverbUnit.setTime(it) }
            }
        }

        controlPort(ReverbSymbol.DAMPING) {
            floatType {
                default = 0.7f
                get { _damping }
                set { _damping = it; reverbUnit.setLp(it) }
            }
        }

        controlPort(ReverbSymbol.DIFFUSION) {
            floatType {
                default = 0.625f
                get { _diffusion }
                set { _diffusion = it; reverbUnit.setDiffusion(it) }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "in_l"; name = "Input Left"; isInput = true }
        audioPort { index = 1; symbol = "in_r"; name = "Input Right"; isInput = true }
        audioPort { index = 2; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 3; symbol = "out_r"; name = "Output Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

    override val audioUnits: List<AudioUnit> = listOf(reverbUnit)

    override val inputs: Map<String, AudioInput> = mapOf(
        "inputLeft" to reverbUnit.inputLeft,
        "inputRight" to reverbUnit.inputRight
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "outputLeft" to reverbUnit.output,
        "outputRight" to reverbUnit.outputRight
    )

    override fun initialize() {
        // Apply initial parameter values
        reverbUnit.setAmount(_amount)
        reverbUnit.setTime(_time)
        reverbUnit.setLp(_damping)
        reverbUnit.setDiffusion(_diffusion)
        reverbUnit.setInputGain(0.5f)

        audioUnits.forEach { audioEngine.addUnit(it) }
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
