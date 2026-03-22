package org.balch.orpheus.plugins.dj

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.symbols.DJ_URI
import org.balch.orpheus.core.plugin.symbols.DjSource
import org.balch.orpheus.core.plugin.symbols.DjSymbol

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class DjPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "DJ Turntable",
        author = "Balch"
    )

    companion object {
        const val URI = DJ_URI
    }

    private var _mix = 0f
    private var _sourceA = DjSource.SYNTH.index
    private var _sourceB = DjSource.BASS.index
    private var _velocityA = 1f
    private var _velocityB = 1f
    private var _frozenA = false
    private var _frozenB = false
    private var _crossfader = 0.5f
    private var _delaySend = 0f
    private var _reverbSend = 0f

    private val portDefs = ports(startIndex = 2) {
        controlPort(DjSymbol.MIX) {
            floatType {
                min = 0f; max = 1f
                default = 0f
                get { _mix }
                set { _mix = it }
            }
        }
        controlPort(DjSymbol.SOURCE_A) {
            intType {
                options = DjSource.entries.map { it.label }
                default = DjSource.SYNTH.index
                get { _sourceA }
                set { _sourceA = it }
            }
        }
        controlPort(DjSymbol.SOURCE_B) {
            intType {
                options = DjSource.entries.map { it.label }
                default = DjSource.BASS.index
                get { _sourceB }
                set { _sourceB = it }
            }
        }
        controlPort(DjSymbol.VELOCITY_A) {
            floatType {
                min = -3f; max = 3f
                default = 1f
                get { _velocityA }
                set { _velocityA = it }
            }
        }
        controlPort(DjSymbol.VELOCITY_B) {
            floatType {
                min = -3f; max = 3f
                default = 1f
                get { _velocityB }
                set { _velocityB = it }
            }
        }
        controlPort(DjSymbol.FROZEN_A) {
            boolType {
                default = false
                get { _frozenA }
                set { _frozenA = it }
            }
        }
        controlPort(DjSymbol.FROZEN_B) {
            boolType {
                default = false
                get { _frozenB }
                set { _frozenB = it }
            }
        }
        controlPort(DjSymbol.CROSSFADER) {
            floatType {
                min = 0f; max = 1f
                default = 0.5f
                get { _crossfader }
                set { _crossfader = it }
            }
        }
        controlPort(DjSymbol.DELAY_SEND) {
            floatType {
                min = 0f; max = 1f
                default = 0f
                get { _delaySend }
                set { _delaySend = it }
            }
        }
        controlPort(DjSymbol.REVERB_SEND) {
            floatType {
                min = 0f; max = 1f
                default = 0f
                get { _reverbSend }
                set { _reverbSend = it }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 1; symbol = "out_r"; name = "Output Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

    override fun onStart() {}
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
