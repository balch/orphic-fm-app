package org.balch.orpheus.plugins.pulsar

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.symbols.PULSAR_URI
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class PulsarPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Pulsar",
        author = "Orpheus"
    )

    companion object {
        const val URI = PULSAR_URI
    }

    private var _playing = 0
    private var _scene = 0
    private var _energy = 0.5f
    private var _complexity = 0.3f
    private var _space = 0.4f
    private var _mood = 0.5f
    private var _bpm = 120.0f
    private var _delaySend = 0.0f
    private var _reverbSend = 0.0f
    private var _rootNote = 0
    private var _scaleIndex = 0
    private var _mix = 0.0f
    private var _percMix = 0.7f
    private var _envelopeMode = 0  // 0=AD, 1=Tides, 2=Blend (energy-driven)
    private var _trackEdm = intArrayOf(20, 17, 23, 9, 6, 14, 11, 20)
    private var _trackSpace = intArrayOf(20, 18, 23, 19, 6, 19, 13, 19)

    private val portDefs = ports(startIndex = 0) {
        controlPort(PulsarSymbol.PLAYING) {
            intType { default = 0; get { _playing }; set { _playing = it } }
        }
        controlPort(PulsarSymbol.SCENE) {
            intType { default = 0; get { _scene }; set { _scene = it } }
        }
        controlPort(PulsarSymbol.ENERGY) {
            floatType { default = 0.5f; get { _energy }; set { _energy = it } }
        }
        controlPort(PulsarSymbol.COMPLEXITY) {
            floatType { default = 0.3f; get { _complexity }; set { _complexity = it } }
        }
        controlPort(PulsarSymbol.SPACE) {
            floatType { default = 0.4f; get { _space }; set { _space = it } }
        }
        controlPort(PulsarSymbol.MOOD) {
            floatType { default = 0.5f; get { _mood }; set { _mood = it } }
        }
        controlPort(PulsarSymbol.BPM) {
            floatType { default = 120.0f; min = 40f; max = 300f; get { _bpm }; set { _bpm = it } }
        }
        controlPort(PulsarSymbol.DELAY_SEND) {
            floatType { default = 0.0f; min = 0f; max = 1f; get { _delaySend }; set { _delaySend = it } }
        }
        controlPort(PulsarSymbol.REVERB_SEND) {
            floatType { default = 0.0f; min = 0f; max = 1f; get { _reverbSend }; set { _reverbSend = it } }
        }
        controlPort(PulsarSymbol.ROOT_NOTE) {
            intType { default = 0; get { _rootNote }; set { _rootNote = it } }
        }
        controlPort(PulsarSymbol.SCALE) {
            intType { default = 0; get { _scaleIndex }; set { _scaleIndex = it } }
        }
        controlPort(PulsarSymbol.MIX) {
            floatType { default = 0.0f; min = 0f; max = 1f; get { _mix }; set { _mix = it } }
        }
        controlPort(PulsarSymbol.PERC_MIX) {
            floatType { default = 0.7f; min = 0f; max = 1f; get { _percMix }; set { _percMix = it } }
        }
        controlPort(PulsarSymbol.ENVELOPE_MODE) {
            intType { default = 0; get { _envelopeMode }; set { _envelopeMode = it } }
        }
        controlPort(PulsarSymbol.TRACK_0_ENGINE_EDM) {
            intType { default = 20; get { _trackEdm[0] }; set { _trackEdm[0] = it } }
        }
        controlPort(PulsarSymbol.TRACK_0_ENGINE_SPACE) {
            intType { default = 20; get { _trackSpace[0] }; set { _trackSpace[0] = it } }
        }
        controlPort(PulsarSymbol.TRACK_1_ENGINE_EDM) {
            intType { default = 17; get { _trackEdm[1] }; set { _trackEdm[1] = it } }
        }
        controlPort(PulsarSymbol.TRACK_1_ENGINE_SPACE) {
            intType { default = 18; get { _trackSpace[1] }; set { _trackSpace[1] = it } }
        }
        controlPort(PulsarSymbol.TRACK_2_ENGINE_EDM) {
            intType { default = 23; get { _trackEdm[2] }; set { _trackEdm[2] = it } }
        }
        controlPort(PulsarSymbol.TRACK_2_ENGINE_SPACE) {
            intType { default = 23; get { _trackSpace[2] }; set { _trackSpace[2] = it } }
        }
        controlPort(PulsarSymbol.TRACK_3_ENGINE_EDM) {
            intType { default = 9; get { _trackEdm[3] }; set { _trackEdm[3] = it } }
        }
        controlPort(PulsarSymbol.TRACK_3_ENGINE_SPACE) {
            intType { default = 19; get { _trackSpace[3] }; set { _trackSpace[3] = it } }
        }
        controlPort(PulsarSymbol.TRACK_4_ENGINE_EDM) {
            intType { default = 6; get { _trackEdm[4] }; set { _trackEdm[4] = it } }
        }
        controlPort(PulsarSymbol.TRACK_4_ENGINE_SPACE) {
            intType { default = 6; get { _trackSpace[4] }; set { _trackSpace[4] = it } }
        }
        controlPort(PulsarSymbol.TRACK_5_ENGINE_EDM) {
            intType { default = 14; get { _trackEdm[5] }; set { _trackEdm[5] = it } }
        }
        controlPort(PulsarSymbol.TRACK_5_ENGINE_SPACE) {
            intType { default = 19; get { _trackSpace[5] }; set { _trackSpace[5] = it } }
        }
        controlPort(PulsarSymbol.TRACK_6_ENGINE_EDM) {
            intType { default = 11; get { _trackEdm[6] }; set { _trackEdm[6] = it } }
        }
        controlPort(PulsarSymbol.TRACK_6_ENGINE_SPACE) {
            intType { default = 13; get { _trackSpace[6] }; set { _trackSpace[6] = it } }
        }
        controlPort(PulsarSymbol.TRACK_7_ENGINE_EDM) {
            intType { default = 20; get { _trackEdm[7] }; set { _trackEdm[7] = it } }
        }
        controlPort(PulsarSymbol.TRACK_7_ENGINE_SPACE) {
            intType { default = 19; get { _trackSpace[7] }; set { _trackSpace[7] = it } }
        }
    }

    override val ports: List<Port> = portDefs.ports

    override fun initialize() {}
    override fun onStart() {}

    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
