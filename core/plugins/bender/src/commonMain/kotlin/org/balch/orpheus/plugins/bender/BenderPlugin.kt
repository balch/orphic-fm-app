package org.balch.orpheus.plugins.bender

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioPort
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspFactory
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.PluginInfo
import org.balch.orpheus.core.audio.dsp.Port
import org.balch.orpheus.core.audio.dsp.PortSymbol
import org.balch.orpheus.core.audio.dsp.PortValue
import org.balch.orpheus.core.audio.dsp.Symbol
import org.balch.orpheus.core.audio.dsp.ports
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exhaustive enum of all Bender plugin port symbols.
 */
enum class BenderSymbol(
    override val symbol: Symbol,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    BEND("bend", "Bend"),
    MAX_BEND("max_bend", "Max Bend Semitones"),
    RANDOM_DEPTH("random_depth", "Random Depth"),
    TIMBRE_MOD("timbre_mod", "Timbre Modulation"),
    SPRING_VOL("spring_vol", "Spring Volume"),
    TENSION_VOL("tension_vol", "Tension Volume")
}

/**
 * Bender Plugin (Pitch and timbre bending with spring/tension effects).
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class BenderPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.bender",
        name = "Bender",
        author = "Balch"
    )

    // Control signal path
    private val bendInputProxy = dspFactory.createPassThrough()
    private val bendDepthGain = dspFactory.createMultiply()
    private val nonlinearMixer = dspFactory.createMultiplyAdd()
    
    private val randomLfo = dspFactory.createSineOscillator()
    private val randomDepthGain = dspFactory.createMultiply()
    private val randomMixer = dspFactory.createAdd()
    
    private val timbreModGain = dspFactory.createMultiply()
    
    private val pitchOutputProxy = dspFactory.createPassThrough()
    private val timbreOutputProxy = dspFactory.createPassThrough()
    
    private val bendMonitor = dspFactory.createPeakFollower()
    
    // Tension sound
    private val tensionOsc = dspFactory.createSineOscillator()
    private val tensionEnvelope = dspFactory.createEnvelope()
    private val tensionVca = dspFactory.createMultiply()
    private val tensionGain = dspFactory.createMultiply()
    
    // Spring sound
    private val springOsc = dspFactory.createSineOscillator()
    private val springEnvelope = dspFactory.createEnvelope()
    private val springVca = dspFactory.createMultiply()
    private val springFreqBase = dspFactory.createMultiplyAdd()
    private val wobbleLfo = dspFactory.createSineOscillator()
    private val wobbleDepth = dspFactory.createMultiply()
    private val wobbleMixer = dspFactory.createAdd()
    private val springGain = dspFactory.createMultiply()
    
    private val audioMixer = dspFactory.createAdd()
    private val audioOutputProxy = dspFactory.createPassThrough()

    // Internal state
    private var _bendAmount = 0.0f
    private var _maxBendSemitones = 24.0f
    private var _randomDepth = 0.1f
    private var _timbreModulation = 0.3f
    private var _springVolume = 0.4f
    private var _tensionVolume = 0.015f
    private var _wasActive = false

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 3) {
        float(BenderSymbol.BEND) {
            default = 0f; min = -1f; max = 1f
            get { _bendAmount }
            set {
                val wasActive = _bendAmount.absoluteValue > 0.05f
                _bendAmount = it.coerceIn(-1f, 1f)
                val isActive = _bendAmount.absoluteValue > 0.05f
                
                val normalizedBend = _bendAmount
                val tensionCurve = normalizedBend * (1.0 + normalizedBend.absoluteValue * 0.5)
                val semitones = tensionCurve * _maxBendSemitones
                val frequencyMultiplier = 2.0.pow(semitones / 12.0) - 1.0
                
                bendInputProxy.input.set(normalizedBend.toDouble())
                bendDepthGain.inputB.set(frequencyMultiplier)
                
                val randomIntensity = _randomDepth * normalizedBend.absoluteValue * 0.1
                randomDepthGain.inputB.set(randomIntensity.toDouble())
                
                val timbreAmount = normalizedBend.absoluteValue * _timbreModulation
                timbreModGain.inputB.set(timbreAmount.toDouble())
                
                val lfoRate = 1.5 + (normalizedBend.absoluteValue * 3.0)
                randomLfo.frequency.set(lfoRate)
                
                val tensionFreq = 300.0 + (normalizedBend.absoluteValue * 200.0)
                tensionOsc.frequency.set(tensionFreq)
                
                val tensionLevel = normalizedBend.absoluteValue * _tensionVolume
                tensionGain.inputB.set(tensionLevel.toDouble())
                
                if (isActive && !wasActive) {
                    tensionEnvelope.input.set(1.0)
                    _wasActive = true
                } else if (!isActive && wasActive) {
                    tensionEnvelope.input.set(0.0)
                }
                
                if (!isActive && _wasActive) {
                    springEnvelope.input.set(1.0)
                    springEnvelope.input.set(0.0)
                    _wasActive = false
                }
            }
        }
        
        float(BenderSymbol.MAX_BEND) {
            default = 24f; min = 1f; max = 48f
            get { _maxBendSemitones }
            set { _maxBendSemitones = it }
        }
        
        float(BenderSymbol.RANDOM_DEPTH) {
            default = 0.1f
            get { _randomDepth }
            set { _randomDepth = it }
        }
        
        float(BenderSymbol.TIMBRE_MOD) {
            default = 0.3f
            get { _timbreModulation }
            set { _timbreModulation = it }
        }
        
        float(BenderSymbol.SPRING_VOL) {
            default = 0.4f
            get { _springVolume }
            set {
                _springVolume = it
                springGain.inputB.set(it.toDouble())
            }
        }
        
        float(BenderSymbol.TENSION_VOL) {
            default = 0.015f; min = 0f; max = 0.1f
            get { _tensionVolume }
            set { _tensionVolume = it }
        }
    }

    private val audioPorts = listOf(
        AudioPort(0, "pitch_out", "Pitch Output", false),
        AudioPort(1, "timbre_out", "Timbre Output", false),
        AudioPort(2, "audio_out", "Audio Output", false)
    )

    override val ports: List<Port> = audioPorts + portDefs.ports

    override val audioUnits: List<AudioUnit> = listOf(
        bendInputProxy, bendDepthGain, nonlinearMixer,
        randomLfo, randomDepthGain, randomMixer,
        timbreModGain, pitchOutputProxy, timbreOutputProxy,
        bendMonitor,
        tensionOsc, tensionEnvelope, tensionVca, tensionGain,
        springOsc, springEnvelope, springVca, springFreqBase,
        wobbleLfo, wobbleDepth, wobbleMixer, springGain,
        audioMixer, audioOutputProxy
    )

    override val inputs: Map<String, AudioInput> = mapOf(
        "bend" to bendInputProxy.input
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "pitchOutput" to pitchOutputProxy.output,
        "timbreOutput" to timbreOutputProxy.output,
        "audioOutput" to audioOutputProxy.output
    )

    override fun initialize() {
        randomLfo.frequency.set(1.5 + Random.nextDouble() * 1.5)
        randomLfo.amplitude.set(1.0)
        randomLfo.output.connect(randomDepthGain.inputA)
        randomDepthGain.inputB.set(0.0)
        
        bendInputProxy.output.connect(nonlinearMixer.inputA)
        nonlinearMixer.inputB.set(1.5)
        nonlinearMixer.inputC.set(0.0)
        
        nonlinearMixer.output.connect(randomMixer.inputA)
        randomDepthGain.output.connect(randomMixer.inputB)
        
        randomMixer.output.connect(bendDepthGain.inputA)
        bendDepthGain.inputB.set(0.0)
        bendDepthGain.output.connect(pitchOutputProxy.input)
        
        bendInputProxy.output.connect(timbreModGain.inputA)
        timbreModGain.inputB.set(0.0)
        timbreModGain.output.connect(timbreOutputProxy.input)
        
        pitchOutputProxy.output.connect(bendMonitor.input)
        bendMonitor.setHalfLife(0.016)
        
        // Tension
        tensionOsc.frequency.set(300.0)
        tensionOsc.amplitude.set(0.1)
        tensionEnvelope.setAttack(0.1)
        tensionEnvelope.setDecay(0.1)
        tensionEnvelope.setSustain(0.6)
        tensionEnvelope.setRelease(0.2)
        tensionOsc.output.connect(tensionVca.inputA)
        tensionEnvelope.output.connect(tensionVca.inputB)
        tensionVca.output.connect(tensionGain.inputA)
        tensionGain.inputB.set(0.0)
        
        // Spring
        springOsc.amplitude.set(0.2)
        wobbleLfo.frequency.set(8.0)
        wobbleLfo.amplitude.set(1.0)
        wobbleLfo.output.connect(wobbleDepth.inputA)
        wobbleDepth.inputB.set(80.0)
        springFreqBase.inputB.set(-150.0)
        springFreqBase.inputC.set(350.0)
        springFreqBase.output.connect(wobbleMixer.inputA)
        wobbleDepth.output.connect(wobbleMixer.inputB)
        wobbleMixer.output.connect(springOsc.frequency)
        springEnvelope.setAttack(0.003)
        springEnvelope.setDecay(0.4)
        springEnvelope.setSustain(0.0)
        springEnvelope.setRelease(0.3)
        springEnvelope.output.connect(springVca.inputB)
        springEnvelope.output.connect(springFreqBase.inputA)
        springOsc.output.connect(springVca.inputA)
        springVca.output.connect(springGain.inputA)
        springGain.inputB.set(0.4)
        
        // Mix
        tensionGain.output.connect(audioMixer.inputA)
        springGain.output.connect(audioMixer.inputB)
        audioMixer.output.connect(audioOutputProxy.input)

        audioUnits.forEach { audioEngine.addUnit(it) }
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    // Legacy setters for backward compatibility
    fun setBend(amount: Float) = portDefs.setValue(BenderSymbol.BEND, PortValue.FloatValue(amount))
    fun setMaxBendSemitones(semitones: Float) = portDefs.setValue(BenderSymbol.MAX_BEND, PortValue.FloatValue(semitones))
    fun setRandomDepth(depth: Float) = portDefs.setValue(BenderSymbol.RANDOM_DEPTH, PortValue.FloatValue(depth))
    fun setTimbreModulation(amount: Float) = portDefs.setValue(BenderSymbol.TIMBRE_MOD, PortValue.FloatValue(amount))
    fun setSpringVolume(volume: Float) = portDefs.setValue(BenderSymbol.SPRING_VOL, PortValue.FloatValue(volume))
    fun setTensionVolume(volume: Float) = portDefs.setValue(BenderSymbol.TENSION_VOL, PortValue.FloatValue(volume))
    
    fun getBend(): Float = _bendAmount
    fun getCurrentValue(): Float = bendMonitor.getCurrent().toFloat().coerceIn(-1f, 1f)
}
