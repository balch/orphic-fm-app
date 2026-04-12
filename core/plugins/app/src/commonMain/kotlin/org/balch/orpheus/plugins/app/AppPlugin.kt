package org.balch.orpheus.plugins.app

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
import org.balch.orpheus.core.plugin.symbols.APP_URI
import org.balch.orpheus.core.plugin.symbols.AppSymbol

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class AppPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "App Controls",
        author = "Balch"
    )

    companion object {
        const val URI = APP_URI
    }

    private var _muted = true

    private val portDefs = ports(startIndex = 0) {
        controlPort(AppSymbol.MUTED) {
            boolType {
                default = true
                get { _muted }
                set { _muted = it }
            }
        }
    }

    override val ports: List<Port> = portDefs.controlPorts

    override fun onStart() {}
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
