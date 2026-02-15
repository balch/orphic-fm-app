package org.balch.orpheus.core.audio.dsp


import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * WASM actual implementations of DSP units using Web Audio API.
 */

class WebAudioEnvelope(private val context: AudioContext) : Envelope {
    private val gainNode = context.createGain().also { it.gain.value = 0f }
    
    private var attackTime = 0.01
    private var decayTime = 0.1
    private var sustainLevel = 0.7
    private var releaseTime = 0.3
    
    private var isGateOpen = false
    private var lastGateValue = 0.0
    
    // Constant source to feed the envelope gain node
    private val constantSource = context.createConstantSource().also {
        it.offset.value = 1.0f
        it.connect(gainNode)
        it.start()
    }
    
    override val input: AudioInput = WebAudioManualInput(context) { value ->
        val wasOpen = lastGateValue > 0.5
        val isOpen = value > 0.5
        lastGateValue = value
        
        if (isOpen && !wasOpen) {
            triggerAttack()
        } else if (!isOpen && wasOpen) {
            triggerRelease()
        }
    }
    
    override val output: AudioOutput = WebAudioNodeOutput(gainNode)
    
    private fun triggerAttack() {
        isGateOpen = true
        val now = context.currentTime
        gainNode.gain.cancelScheduledValues(now)
        gainNode.gain.setValueAtTime(gainNode.gain.value, now)
        gainNode.gain.linearRampToValueAtTime(1.0f, now + attackTime)
        gainNode.gain.linearRampToValueAtTime(sustainLevel.toFloat(), now + attackTime + decayTime)
    }
    
    private fun triggerRelease() {
        isGateOpen = false
        val now = context.currentTime
        gainNode.gain.cancelScheduledValues(now)
        gainNode.gain.setValueAtTime(gainNode.gain.value, now)
        gainNode.gain.linearRampToValueAtTime(0.0001f, now + releaseTime)
        gainNode.gain.linearRampToValueAtTime(0f, now + releaseTime + 0.001)
    }
    
    override fun setAttack(seconds: Double) { attackTime = seconds.coerceAtLeast(0.001) }
    override fun setDecay(seconds: Double) { decayTime = seconds.coerceAtLeast(0.001) }
    override fun setSustain(level: Double) { sustainLevel = level.coerceIn(0.0, 1.0) }
    override fun setRelease(seconds: Double) { releaseTime = seconds.coerceAtLeast(0.001) }
    
    val webAudioNode: GainNode get() = gainNode
}

class WebAudioDelayLine(private val context: AudioContext) : DelayLine {
    private var delayNode = context.createDelay(2.0)
    
    override val input: AudioInput = WebAudioNodeInput(delayNode, 0, context)
    override val delay: AudioInput = WebAudioParamInput(delayNode.delayTime, context)
    override val output: AudioOutput = WebAudioNodeOutput(delayNode)
    
    override fun allocate(maxSamples: Int) {
        val maxSeconds = maxSamples.toDouble() / context.sampleRate
        if (maxSeconds > 2.0) {
            delayNode = context.createDelay(maxSeconds)
        }
    }
}

class WebAudioPeakFollower(private val context: AudioContext) : PeakFollower {
    private val analyser = context.createAnalyser().also {
        it.fftSize = 2048
        it.smoothingTimeConstant = 0f
        it.minDecibels = -100f
        it.maxDecibels = 0f
    }
    
    private val absShaper = context.createWaveShaper().apply {
        val samples = 1024
        val curve = Float32Array(samples)
        for (i in 0 until samples) {
            val x = (i.toFloat() / (samples - 1)) * 2 - 1
            curve[i] = abs(x).toFloat()
        }
        this.curve = curve
    }
    
    private val followerFilter = context.createBiquadFilter().apply {
        type = "lowpass"
        frequency.value = 10f
    }

    private val dataArray = Float32Array(2048)
    
    override val input: AudioInput = WebAudioNodeInput(analyser, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(followerFilter)
    
    private var currentPeak = 0.0
    private var halfLifeSeconds = 0.1
    
    init {
        analyser.connect(absShaper)
        absShaper.connect(followerFilter)
    }
    
    override fun setHalfLife(seconds: Double) {
        halfLifeSeconds = seconds.coerceAtLeast(0.001)
        val tau = halfLifeSeconds / 0.69314718056
        val cutoff = 1.0 / (2.0 * 3.14159265359 * tau)
        followerFilter.frequency.value = cutoff.toFloat().coerceIn(0.1f, 1000f)
    }
    
    override fun getCurrent(): Double {
        analyser.getFloatTimeDomainData(dataArray)
        var instantPeak = 0f
        for (i in 0 until dataArray.length) {
            val absSample = abs(dataArray[i])
            if (absSample > instantPeak) instantPeak = absSample
        }
        
        if (instantPeak > currentPeak) {
            currentPeak = instantPeak.toDouble()
        } else {
            val bufferDuration = dataArray.length.toDouble() / context.sampleRate
            val decayFactor = 0.5.pow(bufferDuration / halfLifeSeconds)
            currentPeak *= decayFactor
            if (currentPeak < instantPeak) currentPeak = instantPeak.toDouble()
        }
        return currentPeak.coerceIn(0.0, 1.0)
    }
}

class WebAudioLimiter(private val context: AudioContext) : Limiter {
    private val shaper = context.createWaveShaper()
    private val driveGain = context.createGain().also { it.gain.value = 1f }
    private val postGain = context.createGain().also { it.gain.value = 1f }
    
    init {
        driveGain.connect(shaper)
        shaper.connect(postGain)
        updateCurve(1.0f)
    }
    
    private fun updateCurve(drive: Float) {
        val samples = 1024
        val curve = Float32Array(samples)
        for (i in 0 until samples) {
            val x = (i.toFloat() / (samples - 1)) * 2 - 1
            curve[i] = tanh(x * drive).toFloat()
        }
        shaper.curve = curve
        shaper.oversample = "2x"

        val drv = drive.toDouble().coerceIn(1.0, 50.0)
        val compensation = 1.0 / tanh(drv.coerceAtMost(3.0))
        postGain.gain.value = compensation.coerceAtMost(1.5).toFloat()
    }
    
    override val input: AudioInput = WebAudioNodeInput(driveGain, 0, context)
    override val drive: AudioInput = WebAudioManualInput(context) { value ->
        updateCurve(value.toFloat())
    }
    override val output: AudioOutput = WebAudioNodeOutput(postGain)
}

class WebAudioLinearRamp(private val context: AudioContext) : LinearRamp {
    private val source = context.createConstantSource().also {
        it.offset.value = 0f
        it.start()
    }
    private var rampTime = 0.02

    override val input: AudioInput = WebAudioManualInput(context) { value ->
         val now = context.currentTime
         source.offset.cancelScheduledValues(now)
         source.offset.setValueAtTime(source.offset.value, now)
         source.offset.linearRampToValueAtTime(value.toFloat(), now + rampTime)
    }
    
    override val time: AudioInput = WebAudioManualInput(context) { value ->
        rampTime = value.coerceAtLeast(0.001)
    }
    
    override val output: AudioOutput = WebAudioNodeOutput(source)
}

class WebAudioAutomationPlayer(private val context: AudioContext) : AutomationPlayer {
    private val outputGain = context.createGain()
    private var sourceNode: AudioBufferSourceNode? = null
    private var buffer: AudioBuffer? = null
    
    private val BUFFER_SIZE = 1024
    private var durationSeconds = 1.0f
    private var mode = 0
    
    private var lastTimes: FloatArray? = null
    private var lastValues: FloatArray? = null
    private var lastCount: Int = 0

    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    
    override fun setPath(times: FloatArray, values: FloatArray, count: Int) {
         if (count < 2) return
         this.lastTimes = times.copyOf()
         this.lastValues = values.copyOf()
         this.lastCount = count
         if (durationSeconds > 0) bake()
    }
    
    private fun bake() {
        val times = lastTimes ?: return
        val values = lastValues ?: return
        val count = lastCount
        val duration = durationSeconds
        
        val newBuffer = context.createBuffer(1, BUFFER_SIZE, context.sampleRate.toFloat())
        val channelData = newBuffer.getChannelData(0)
        
        fun getValueAt(s: Float): Float {
            if (s <= times[0]) return values[0]
            if (s >= times[count - 1]) return values[count - 1]
            for (i in 0 until count - 1) {
                if (s >= times[i] && s <= times[i+1]) {
                    val t1 = times[i]
                    val t2 = times[i+1]
                    val v1 = values[i]
                    val v2 = values[i+1]
                    if (t2 == t1) return v1
                    val fraction = (s - t1) / (t2 - t1)
                    return v1 + fraction * (v2 - v1)
                }
            }
            return values[count - 1]
        }
         
         for (i in 0 until BUFFER_SIZE) {
             val s = (i.toFloat() / (BUFFER_SIZE - 1)) * duration
             channelData[i] = getValueAt(s)
         }
         this.buffer = newBuffer
    }
    
    override fun setDuration(seconds: Float) {
        this.durationSeconds = seconds
        if (lastTimes != null) bake()
        updateRate()
    }
    
    private fun updateRate() {
        sourceNode?.let { node ->
             val bufferDuration = BUFFER_SIZE.toFloat() / context.sampleRate
             val rate = if (durationSeconds > 0) bufferDuration / durationSeconds else 1f
             node.playbackRate.value = rate
        }
    }
    
    override fun setMode(mode: Int) {
        this.mode = mode
        sourceNode?.loop = (mode != 0)
    }
    
    override fun play() {
        stop()
        val buf = buffer ?: return
        val source = context.createBufferSource()
        source.buffer = buf
        source.loop = (mode != 0)
        val bufferDuration = BUFFER_SIZE.toFloat() / context.sampleRate
        val rate = if (durationSeconds > 0) bufferDuration / durationSeconds else 1f
        source.playbackRate.value = rate
        source.connect(outputGain)
        source.start()
        this.sourceNode = source
    }
    
    override fun stop() {
        try { sourceNode?.stop(); sourceNode?.disconnect() } catch (e: Throwable) {}
        sourceNode = null
    }
    override fun reset() { stop() }
}

class WebAudioDrumUnit(private val context: AudioContext) : DrumUnit {
    private val outputGain = context.createGain().also { it.gain.value = 0.5f }
    
    private var bdF0 = 55f
    private var bdTone = 0.5f
    private var bdDecay = 0.5f
    private var bdP4 = 0.5f
    private var bdP5 = 0.5f
    
    private var sdF0 = 180f
    private var sdTone = 0.5f
    private var sdDecay = 0.5f
    private var sdP4 = 0.5f
    
    private var hhF0 = 400f
    private var hhTone = 0.5f
    private var hhDecay = 0.5f
    private var hhP4 = 0.5f

    override val triggerInputBd: AudioInput = WebAudioManualInput(context) { if(it > 0.5) trigger(0, 1f) }
    override val triggerInputSd: AudioInput = WebAudioManualInput(context) { if(it > 0.5) trigger(1, 1f) }
    override val triggerInputHh: AudioInput = WebAudioManualInput(context) { if(it > 0.5) trigger(2, 1f) }

    
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    
    override fun trigger(type: Int, accent: Float, frequency: Float, tone: Float, decay: Float, param4: Float, param5: Float) {
        setParameters(type, frequency, tone, decay, param4, param5)
        trigger(type, accent)
    }
    
    override fun setParameters(type: Int, frequency: Float, tone: Float, decay: Float, param4: Float, param5: Float) {
        when (type) {
            0 -> { bdF0 = frequency; bdTone = tone; bdDecay = decay; bdP4 = param4; bdP5 = param5 }
            1 -> { sdF0 = frequency; sdTone = tone; sdDecay = decay; sdP4 = param4 }
            2 -> { hhF0 = frequency; hhTone = tone; hhDecay = decay; hhP4 = param4 }
        }
    }
    
    override fun trigger(type: Int, accent: Float) {
        val now = context.currentTime
        when (type) {
            0 -> triggerKick(now, accent)
            1 -> triggerSnare(now, accent)
            2 -> triggerHiHat(now, accent)
        }
    }
    
    private fun triggerKick(now: Double, accent: Float) {
        val osc = context.createOscillator()
        osc.type = "sine"
        val gain = context.createGain()
        gain.gain.value = 0f
        osc.connect(gain)
        gain.connect(outputGain)
        val startFreq = bdF0 * (1f + bdP4 * 2f)
        osc.frequency.setValueAtTime(startFreq, now)
        osc.frequency.exponentialRampToValueAtTime(bdF0.coerceAtLeast(20f), now + 0.05)
        val decayTime = 0.1 + bdDecay * 0.9
        gain.gain.setValueAtTime(accent * 0.8f, now)
        gain.gain.exponentialRampToValueAtTime(0.001f, now + decayTime)
        osc.start(now)
        osc.stop(now + decayTime + 0.01)
    }
    
    private fun triggerSnare(now: Double, accent: Float) {
        val osc = context.createOscillator()
        osc.type = "triangle"
        osc.frequency.value = sdF0
        val oscGain = context.createGain()
        oscGain.gain.value = 0f
        osc.connect(oscGain)
        oscGain.connect(outputGain)
        val decayTime = 0.05 + sdDecay * 0.3
        oscGain.gain.setValueAtTime(accent * 0.5f, now)
        oscGain.gain.exponentialRampToValueAtTime(0.001f, now + decayTime)
        osc.start(now)
        osc.stop(now + decayTime + 0.01)
        val noise = context.createOscillator()
        noise.type = "sawtooth"
        noise.frequency.value = 5000f + sdP4 * 3000f
        val noiseGain = context.createGain()
        noiseGain.gain.value = 0f
        noise.connect(noiseGain)
        noiseGain.connect(outputGain)
        noiseGain.gain.setValueAtTime(accent * sdP4 * 0.3f, now)
        noiseGain.gain.exponentialRampToValueAtTime(0.001f, now + decayTime * 0.7)
        noise.start(now)
        noise.stop(now + decayTime)
    }
    
    private fun triggerHiHat(now: Double, accent: Float) {
        val osc1 = context.createOscillator()
        osc1.type = "square"
        osc1.frequency.value = hhF0 * 10f
        val osc2 = context.createOscillator()
        osc2.type = "square"
        osc2.frequency.value = hhF0 * 10f * 1.414f
        val gain = context.createGain()
        gain.gain.value = 0f
        osc1.connect(gain)
        osc2.connect(gain)
        gain.connect(outputGain)
        val decayTime = 0.02 + hhDecay * 0.3
        gain.gain.setValueAtTime(accent * 0.3f, now)
        gain.gain.exponentialRampToValueAtTime(0.001f, now + decayTime)
        osc1.start(now)
        osc2.start(now)
        osc1.stop(now + decayTime + 0.01)
        osc2.stop(now + decayTime + 0.01)
    }
}

class WebAudioResonatorUnit(private val context: AudioContext) : ResonatorUnit {
    private val inputGain = context.createGain().also { it.gain.value = 1f }
    private val outputGain = context.createGain().also { it.gain.value = 1f }
    private val auxGain = context.createGain().also { it.gain.value = 0f }
    
    private val filter = context.createBiquadFilter().also {
        it.type = "bandpass"
        it.frequency.value = 440f
        it.Q.value = 10f
    }
    
    private var enabled = false
    
    init {
        inputGain.connect(filter)
        filter.connect(outputGain)
        filter.connect(auxGain)
    }
    
    override val input: AudioInput = WebAudioNodeInput(inputGain, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    override val auxOutput: AudioOutput = WebAudioNodeOutput(auxGain)
    
    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        outputGain.gain.value = if (enabled) 1f else 0f
    }
    
    override fun setMode(mode: Int) {
        filter.type = when (mode) {
            0 -> "bandpass"
            1 -> "lowpass"
            else -> "bandpass"
        }
    }
    
    override fun setStructure(value: Float) { filter.Q.value = 5f + value * 20f }
    override fun setBrightness(value: Float) { filter.frequency.value = 200f + value * 4000f }
    override fun setDamping(value: Float) { filter.Q.value = filter.Q.value * (1f - value * 0.5f) }
    override fun setPosition(value: Float) { auxGain.gain.value = value * 0.5f }
    override fun strum(frequency: Float) { filter.frequency.value = frequency }
}

class WebAudioLooperUnit(private val context: AudioContext) : LooperUnit {
    override val inputLeft: AudioInput = WebAudioManualInput(context) {}
    override val inputRight: AudioInput = WebAudioManualInput(context) {}
    override val output: AudioOutput = WebAudioNodeOutput(context.createGain())
    override val outputRight: AudioOutput = WebAudioNodeOutput(context.createGain())
    override val recordGate: AudioInput = WebAudioManualInput(context) {}
    override val playGate: AudioInput = WebAudioManualInput(context) {}

    override fun setRecording(active: Boolean) {}
    override fun setPlaying(active: Boolean) {}
    override fun allocate(maxSeconds: Double) {}
    override fun clear() {}
    override fun getPosition(): Float = 0f
    override fun getLoopDuration(): Double = 0.0
}

class WebAudioGrainsUnit(private val context: AudioContext) : GrainsUnit {
    private val inputGain = context.createGain().also { it.gain.value = 1f }
    private val outputGain = context.createGain().also { it.gain.value = 1f }
    
    private val delayL = context.createDelay(2.0)
    private val delayR = context.createDelay(2.0)
    private val fbL = context.createGain().also { it.gain.value = 0.3f }
    private val fbR = context.createGain().also { it.gain.value = 0.3f }
    private val wetL = context.createGain().also { it.gain.value = 0.5f }
    private val wetR = context.createGain().also { it.gain.value = 0.5f }
    
    private val splitter = context.createChannelSplitter(2)
    private val merger = context.createChannelMerger(2)
    
    init {
        inputGain.connect(splitter)
        splitter.connect(delayL, 0)
        delayL.connect(wetL)
        wetL.connect(merger, 0, 0)
        delayL.connect(fbL)
        fbL.connect(delayL)
        splitter.connect(delayR, 1)
        delayR.connect(wetR)
        wetR.connect(merger, 0, 1)
        delayR.connect(fbR)
        fbR.connect(delayR)
        inputGain.connect(outputGain) // Dry
        merger.connect(outputGain)    // Wet
    }
    
    override val inputLeft: AudioInput = WebAudioNodeInput(inputGain, 0, context)
    override val inputRight: AudioInput = WebAudioNodeInput(inputGain, 0, context)
    
    override val position: AudioInput = WebAudioParamInput(delayL.delayTime, context)
    override val density: AudioInput = WebAudioParamInput(fbL.gain, context)
    override val dryWet: AudioInput = WebAudioParamInput(wetL.gain, context)
    
    override val size: AudioInput = WebAudioManualInput(context) {}
    override val pitch: AudioInput = WebAudioManualInput(context) {}
    override val texture: AudioInput = WebAudioManualInput(context) {}
    override val freeze: AudioInput = WebAudioManualInput(context) {}
    override val trigger: AudioInput = WebAudioManualInput(context) {}
    
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    override val outputRight: AudioOutput = WebAudioNodeOutput(outputGain)
    
    override fun setMode(mode: Int) {}
}

class WebAudioReverbUnit(private val context: AudioContext) : ReverbUnit {
    private val outputGain = context.createGain()
    private val inL = context.createGain().also { it.connect(outputGain) }
    private val inR = context.createGain().also { it.connect(outputGain) }

    override val inputLeft: AudioInput = WebAudioNodeInput(inL, 0, context)
    override val inputRight: AudioInput = WebAudioNodeInput(inR, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    override val outputRight: AudioOutput = WebAudioNodeOutput(outputGain)

    override fun setAmount(amount: Float) {}
    override fun setTime(time: Float) {}
    override fun setDiffusion(diffusion: Float) {}
    override fun setLp(lp: Float) {}
    override fun setInputGain(gain: Float) {}
    override fun clear() {}
}

class WebAudioWarpsUnit(private val context: AudioContext) : WarpsUnit {
    private val outputGain = context.createGain()
    private val inL = context.createGain().also { it.connect(outputGain) }
    private val inR = context.createGain().also { it.connect(outputGain) }

    override val inputLeft: AudioInput = WebAudioNodeInput(inL, 0, context)
    override val inputRight: AudioInput = WebAudioNodeInput(inR, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    override val outputRight: AudioOutput = WebAudioNodeOutput(outputGain)

    override val algorithm: AudioInput = WebAudioManualInput(context) {}
    override val timbre: AudioInput = WebAudioManualInput(context) {}
    override val level1: AudioInput = WebAudioManualInput(context) {}
    override val level2: AudioInput = WebAudioManualInput(context) {}
}

class WebAudioClockUnit(private val context: AudioContext) : ClockUnit {
    private val osc = context.createOscillator().also {
        it.type = "sawtooth"
        it.start()
    }
    
    private val pwScaler = context.createGain().also { it.gain.value = 2f }
    private val minusOne = context.createConstantSource().also {
        it.offset.value = -1f
        it.start()
    }
    
    private val comparatorSum = context.createGain().also { it.gain.value = 1f }
    
    private fun createSharpCurve(): Float32Array {
        val size = 256
        val curve = Float32Array(size)
        val center = size / 2
        for (i in 0 until size) {
             curve[i] = if (i < center) 0f else 1f
        }
        return curve
    }
    
    private val comparatorShaper = context.createWaveShaper().also {
        it.curve = createSharpCurve()
        it.oversample = "4x"
    }
    
    init {
        osc.connect(comparatorSum)
        minusOne.connect(comparatorSum)
        pwScaler.connect(comparatorSum)
        comparatorSum.connect(comparatorShaper)
    }

    override val frequency: AudioInput = WebAudioParamInput(osc.frequency, context)
    override val pulseWidth: AudioInput = WebAudioNodeInput(pwScaler, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(comparatorShaper)
}

class WebAudioTtsPlayerUnit(private val context: AudioContext) : TtsPlayerUnit {
    private val outputGain = context.createGain().also { it.gain.value = 0f }

    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    override val outputRight: AudioOutput = WebAudioNodeOutput(outputGain)

    override fun loadAudio(samples: FloatArray, sampleRate: Int) {}
    override fun play() {}
    override fun stop() {}
    override fun isPlaying(): Boolean = false
    override fun setRate(rate: Float) {}
    override fun setVolume(volume: Float) {}
}

class WebAudioSpeechEffectsUnit(private val context: AudioContext) : SpeechEffectsUnit {
    private val outputGain = context.createGain()
    private val inL = context.createGain().also { it.connect(outputGain) }
    private val inR = context.createGain().also { it.connect(outputGain) }

    override val inputLeft: AudioInput = WebAudioNodeInput(inL, 0, context)
    override val inputRight: AudioInput = WebAudioNodeInput(inR, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)
    override val outputRight: AudioOutput = WebAudioNodeOutput(outputGain)

    override fun setPhaserIntensity(intensity: Float) {}
    override fun setFeedbackAmount(amount: Float) {}
    override fun setReverbAmount(amount: Float) {}
}

class WebAudioPlaitsUnit(private val context: AudioContext) : PlaitsUnit {
    private val outputGain = context.createGain().also { it.gain.value = 0f }

    override val triggerInput: AudioInput = WebAudioManualInput(context) {}
    override val frequencyInput: AudioInput = WebAudioManualInput(context) {}
    override val timbreInput: AudioInput = WebAudioManualInput(context) {}
    override val morphInput: AudioInput = WebAudioManualInput(context) {}
    override val output: AudioOutput = WebAudioNodeOutput(outputGain)

    override fun setEngine(engine: Any?) {}
    override fun getEngine(): Any? = null
    override fun setNote(note: Float) {}
    override fun setTimbre(timbre: Float) {}
    override fun setMorph(morph: Float) {}
    override fun setHarmonics(harmonics: Float) {}
    override fun setAccent(accent: Float) {}
    override fun trigger(accent: Float) {}
}
