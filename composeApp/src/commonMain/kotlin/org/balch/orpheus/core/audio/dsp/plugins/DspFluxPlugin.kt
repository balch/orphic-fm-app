package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspFactory

@Inject
@ContributesIntoSet(AppScope::class)
class DspFluxPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    val flux = dspFactory.createFluxUnit()
    
    // Internal state tracking
    private var _spread = 0.5f
    private var _bias = 0.5f
    private var _steps = 0.5f
    private var _dejaVu = 0.0f
    private var _length = 8
    private var _scale = 0
    private var _rate = 0.5f
    private var _jitter = 0.0f
    private var _probability = 0.5f
    private var _gateLength = 0.5f

    override val audioUnits: List<AudioUnit> = listOf(flux)
    
    override val inputs: Map<String, AudioInput> = mapOf(
        "clock" to flux.clock,
        "spread" to flux.spread,
        "bias" to flux.bias,
        "steps" to flux.steps,
        "dejaVu" to flux.dejaVu,
        "length" to flux.length,
        "rate" to flux.rate,
        "jitter" to flux.jitter,
        "probability" to flux.probability,
        "gateLength" to flux.gateLength
    )
    
    override val outputs: Map<String, AudioOutput> = mapOf(
        "output" to flux.output,
        "outputX1" to flux.outputX1,
        "outputX3" to flux.outputX3,
        "outputT1" to flux.outputT1,
        "outputT2" to flux.outputT2,
        "outputT3" to flux.outputT3
    )
    
    override fun initialize() {
        // Default settings
        setSpread(0.5f)
        setBias(0.5f)
        setSteps(0.5f)
        setDejaVu(0.0f)
        setLength(8)
        setScale(0)
        setRate(0.5f)
        setJitter(0.0f)
        setProbability(0.5f)
        setGateLength(0.5f)
    }
    
    fun setRate(value: Float) {
        _rate = value
        flux.rate.set(value.toDouble())
    }
    
    fun setSpread(value: Float) {
        _spread = value
        flux.spread.set(value.toDouble())
    }
    
    fun setBias(value: Float) {
        _bias = value
        flux.bias.set(value.toDouble())
    }
    
    fun setSteps(value: Float) {
        _steps = value
        flux.steps.set(value.toDouble())
    }
    
    fun setDejaVu(value: Float) {
        _dejaVu = value
        flux.dejaVu.set(value.toDouble())
    }
    
    fun setLength(value: Int) {
        _length = value
        flux.length.set(value.toDouble())
    }
    
    fun setScale(index: Int) {
        _scale = index
        flux.setScale(index)
    }
    
    fun setJitter(value: Float) {
        _jitter = value
        flux.jitter.set(value.toDouble())
    }
    
    fun setProbability(value: Float) {
        _probability = value
        flux.probability.set(value.toDouble())
    }
    
    fun setGateLength(value: Float) {
        _gateLength = value
        flux.gateLength.set(value.toDouble())
    }
    
    // Getters for state perstistence/UI
    fun getSpread() = _spread
    fun getBias() = _bias
    fun getSteps() = _steps
    fun getDejaVu() = _dejaVu
    fun getLength() = _length
    fun getScale() = _scale
    fun getRate() = _rate
    fun getJitter() = _jitter
    fun getProbability() = _probability
}
