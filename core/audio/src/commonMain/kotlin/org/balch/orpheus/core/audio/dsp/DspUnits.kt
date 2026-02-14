package org.balch.orpheus.core.audio.dsp

/**
 * Waveform types for oscillators.
 */
enum class WaveformType {
    SINE,
    TRIANGLE,
    SQUARE,
    SAWTOOTH
}

/**
 * Audio oscillator that generates periodic waveforms.
 */
interface Oscillator : AudioUnit {
    /** Frequency input in Hz */
    val frequency: AudioInput

    /** Amplitude input (0.0 - 1.0 typically) */
    val amplitude: AudioInput
}

interface SineOscillator : Oscillator {
    interface Factory { fun create(): SineOscillator }
}
interface TriangleOscillator : Oscillator {
    interface Factory { fun create(): TriangleOscillator }
}
interface SquareOscillator : Oscillator {
    interface Factory { fun create(): SquareOscillator }
}
interface SawtoothOscillator : Oscillator {
    interface Factory { fun create(): SawtoothOscillator }
}

/**
 * DAHDSR Envelope generator.
 */
interface Envelope : AudioUnit {
    /** Gate input (>0 = on, 0 = off) */
    val input: AudioInput

    /** Set attack time in seconds */
    fun setAttack(seconds: Double)

    /** Set decay time in seconds */
    fun setDecay(seconds: Double)

    /** Set sustain level (0.0 - 1.0) */
    fun setSustain(level: Double)

    /** Set release time in seconds */
    fun setRelease(seconds: Double)

    interface Factory { fun create(): Envelope }
}

/**
 * Delay line for audio signals.
 */
interface DelayLine : AudioUnit {
    /** Audio input */
    val input: AudioInput

    /** Delay time input in seconds */
    val delay: AudioInput

    /** Allocate buffer for maximum delay in samples */
    fun allocate(maxSamples: Int)
    
    interface Factory {
        fun create(): DelayLine
    }
}

/**
 * Peak follower / envelope follower.
 */
interface PeakFollower : AudioUnit {
    /** Audio input to track */
    val input: AudioInput

    /** Set half-life decay time in seconds */
    fun setHalfLife(seconds: Double)

    /** Get current peak value */
    fun getCurrent(): Double

    interface Factory { fun create(): PeakFollower }
}

/**
 * Soft limiter with drive control.
 */
interface Limiter : AudioUnit {
    /** Audio input */
    val input: AudioInput

    /** Drive amount input */
    val drive: AudioInput

    interface Factory { fun create(): Limiter }
}

interface Multiply : AudioUnit {
    val inputA: AudioInput
    val inputB: AudioInput
    interface Factory { fun create(): Multiply }
}

interface Add : AudioUnit {
    val inputA: AudioInput
    val inputB: AudioInput
    interface Factory { fun create(): Add }
}

interface MultiplyAdd : AudioUnit {
    val inputA: AudioInput
    val inputB: AudioInput
    val inputC: AudioInput
    interface Factory { fun create(): MultiplyAdd }
}

interface PassThrough : AudioUnit {
    val input: AudioInput
    interface Factory { fun create(): PassThrough }
}

interface Minimum : AudioUnit {
    val inputA: AudioInput
    val inputB: AudioInput
    interface Factory { fun create(): Minimum }
}

interface Maximum : AudioUnit {
    val inputA: AudioInput
    val inputB: AudioInput
    interface Factory { fun create(): Maximum }
}

/**
 * Linear ramp for smooth parameter transitions.
 * Prevents zipper noise by interpolating values over time.
 */
interface LinearRamp : AudioUnit {
    /** Target value input */
    val input: AudioInput
    
    /** Ramp time in seconds */
    val time: AudioInput

    interface Factory { fun create(): LinearRamp }
}

/**
 * Player for automation curves.
 */
interface AutomationPlayer : AudioUnit {
    /** Output signal (control voltage) */
    override val output: AudioOutput

    /** 
     * Upload a path.
     * @param times Normalized time points (0.0 to 1.0)
     * @param values Normalized values (0.0 to 1.0)
     * @param count Number of points
     */
    fun setPath(times: FloatArray, values: FloatArray, count: Int)

    /** Set duration of the sequence in seconds */
    fun setDuration(seconds: Float)

    /** Set playback mode: 0=Once, 1=Loop, 2=PingPong */
    fun setMode(mode: Int)
    
    /** Start/Resume playback */
    fun play()
    
    /** Stop/Pause playback */
    fun stop()
    
    /** Reset to start */
    fun reset()

    interface Factory { fun create(): AutomationPlayer }
}

/**
 * Universal Plaits-style synthesis unit.
 * Wraps a swappable synthesis engine with audio-rate trigger input.
 */
interface PlaitsUnit : AudioUnit {
    /** Audio-rate trigger input for sequencer connectivity */
    val triggerInput: AudioInput

    /** Set the active engine. Null means silence. */
    fun setEngine(engine: Any?)

    /** Get the current engine reference. */
    fun getEngine(): Any?

    /** Set MIDI note number */
    fun setNote(note: Float)

    /** Set timbre parameter (0..1) */
    fun setTimbre(timbre: Float)

    /** Set morph parameter (0..1) */
    fun setMorph(morph: Float)

    /** Set harmonics parameter (0..1) */
    fun setHarmonics(harmonics: Float)

    /** Set accent level (0..1) */
    fun setAccent(accent: Float)

    /** Trigger a note with given accent */
    fun trigger(accent: Float)

    /**
     * Enable built-in percussive decay envelope for non-enveloped engines.
     * Used when hosting pitched engines in drum slots where no external VCA exists.
     */
    fun setPercussiveMode(enabled: Boolean) {}

    /** Set speech prosody amount (0..1). Only meaningful for Speech engine. */
    fun setSpeechProsody(value: Float) {}

    /** Set speech speed (0..1). Only meaningful for Speech engine. */
    fun setSpeechSpeed(value: Float) {}

    /** Per-voice envelope speed. Used by Speech engine to override morph with per-voice word selection. */
    fun setEnvelopeSpeed(value: Float) {}

    /** Audio-rate frequency input (Hz). When connected, overrides control-rate setNote(). */
    val frequencyInput: AudioInput

    /** Audio-rate timbre modulation input (additive, -1..1 range) */
    val timbreInput: AudioInput

    /** Audio-rate morph modulation input (additive, -1..1 range) */
    val morphInput: AudioInput

    interface Factory { fun create(): PlaitsUnit }
}

/**
 * Specialized drum synthesis unit.
 */
interface DrumUnit : AudioUnit {
    /** 
     * Trigger a drum hit.
     * @param type 0=Kick, 1=Snare, 2=HiHat
     * @param frequency f0 in Hz
     */
    fun trigger(
        type: Int,
        accent: Float,
        frequency: Float,
        tone: Float,
        decay: Float,
        param4: Float,
        param5: Float
    )
    
    /**
     * Set drum parameters without triggering.
     * @param type 0=Kick, 1=Snare, 2=HiHat
     */
    fun setParameters(
        type: Int,
        frequency: Float,
        tone: Float,
        decay: Float,
        param4: Float,
        param5: Float
    )

    /**
     * Trigger a drum hit using stored parameters.
     */
    fun trigger(type: Int, accent: Float)
    
    /** Trigger Input for Bass Drum (Audio Rate) */
    val triggerInputBd: AudioInput
    
    /** Trigger Input for Snare Drum (Audio Rate) */
    val triggerInputSd: AudioInput
    
    /** Trigger Input for Hi-Hat (Audio Rate) */
    val triggerInputHh: AudioInput

    interface Factory { fun create(): DrumUnit }
}

/**
 * Modal/String resonator synthesis unit (ported from Mutable Instruments Rings).
 * 
 * Processes audio through either modal synthesis (SVF filter bank) or
 * Karplus-Strong string synthesis.
 */
interface ResonatorUnit : AudioUnit {
    /** Audio input for external excitation */
    val input: AudioInput
    
    /** Secondary output (even partials / aux out) */
    val auxOutput: AudioOutput
    
    /** Enable/disable resonator processing */
    fun setEnabled(enabled: Boolean)
    
    /** Set mode: 0=Modal, 1=String, 2=Sympathetic */
    fun setMode(mode: Int)
    
    /** Set structure/inharmonicity (0-1) */
    fun setStructure(value: Float)
    
    /** Set brightness (0-1) */  
    fun setBrightness(value: Float)
    
    /** Set damping/decay (0-1) */
    fun setDamping(value: Float)
    
    /** Set excitation position (0-1) */
    fun setPosition(value: Float)
    
    /** Trigger strum at specific frequency */
    fun strum(frequency: Float)

    interface Factory { fun create(): ResonatorUnit }
}

/**
 * Mutable Instruments Clouds Texture Synthesizer Unit.
 * 
 * Simplified port focusing on Looping Delay mode.
 */
interface GrainsUnit : AudioUnit {
    /** Stereo Audio Input */
    val inputLeft: AudioInput
    val inputRight: AudioInput
    
    /** Stereo Audio Output (Left is 'output') */
    val outputRight: AudioOutput
    
    // Parameters
    val position: AudioInput // Delay time / Loop length
    val size: AudioInput     // Grain size / Diffusion
    val pitch: AudioInput    // Pitch shifting
    val density: AudioInput  // Feedback / Decay
    val texture: AudioInput  // Filter / Color
    val dryWet: AudioInput   // Mix
    
    val freeze: AudioInput   // > 0.5 = Freeze
    val trigger: AudioInput  // Trigger loop/granular
    
    /** Configure mode (0 = Granular, 1 = Stretch, 2 = Looping Delay, 3 = Spectral) */
    fun setMode(mode: Int)

    /** Enable/disable bypass (zeroes output, preserves state) */
    fun setBypass(bypass: Boolean) {}

    interface Factory { fun create(): GrainsUnit }
}

/**
 * Simple Native Audio Looper.
 * Records into an internal buffer and plays it back.
 */
interface LooperUnit : AudioUnit {
    /** Audio Input L to record */
    val inputLeft: AudioInput
    
    /** Audio Input R to record */
    val inputRight: AudioInput
    
    /** Loop Playback Output */
    override val output: AudioOutput
    val outputRight: AudioOutput
    
    /** 
     * Record continuously when > 0.5.
     * Overwrites existing buffer content.
     */
    val recordGate: AudioInput
    
    /** 
     * Play continuously when > 0.5.
     * Retains buffer content.
     */
    val playGate: AudioInput
    
    /** Start/Stop recording manually */
    fun setRecording(active: Boolean)
    
    /** Start/Stop playback manually */
    fun setPlaying(active: Boolean)

    /** Allocate buffer for max loop time */
    fun allocate(maxSeconds: Double)
    
    /** Clear buffer contents */
    fun clear()
    
    /** Get current loop position (0.0 - 1.0) for UI */
    fun getPosition(): Float
    
    /** Get total recorded loop duration in seconds */
    fun getLoopDuration(): Double

    interface Factory { fun create(): LooperUnit }
}

/**
 * Dattorro plate reverb unit (ported from Mutable Instruments Rings).
 * Stereo in/out reverb with time, damping, diffusion, and amount controls.
 */
interface ReverbUnit : AudioUnit {
    /** Stereo audio inputs */
    val inputLeft: AudioInput
    val inputRight: AudioInput

    /** Stereo audio outputs (main output is 'output') */
    val outputRight: AudioOutput

    /** Set wet/dry amount (0=dry, 1=fully wet) */
    fun setAmount(amount: Float)

    /** Set reverb time / decay (0-1) */
    fun setTime(time: Float)

    /** Set high-frequency damping (0=bright, 1=dark) */
    fun setDiffusion(diffusion: Float)

    /** Set low-pass filter coefficient for decay (0-1) */
    fun setLp(lp: Float)

    /** Set input gain (0-1) */
    fun setInputGain(gain: Float)

    /** Clear reverb buffer */
    fun clear()

    /** Enable/disable bypass (zeroes output, preserves state) */
    fun setBypass(bypass: Boolean) {}

    interface Factory { fun create(): ReverbUnit }
}

/**
 * TTS sample player unit.
 * Loads synthesized speech audio and plays it through the effects chain.
 */
interface TtsPlayerUnit : AudioUnit {
    override val output: AudioOutput
    val outputRight: AudioOutput
    fun loadAudio(samples: FloatArray, sampleRate: Int)
    fun play()
    fun stop()
    fun isPlaying(): Boolean
    fun setRate(rate: Float)   // 0.25-2.0, affects speed+pitch
    fun setVolume(volume: Float) // 0.0+
    interface Factory { fun create(): TtsPlayerUnit }
}

/**
 * Dedicated speech effects chain unit.
 * Provides phaser, feedback delay, and reverb effects independent of main effects.
 */
interface SpeechEffectsUnit : AudioUnit {
    val inputLeft: AudioInput
    val inputRight: AudioInput
    val outputRight: AudioOutput

    /** Phaser intensity 0-1 (0=bypass) */
    fun setPhaserIntensity(intensity: Float)
    /** Feedback delay amount 0-1 (0=bypass) */
    fun setFeedbackAmount(amount: Float)
    /** Reverb amount 0-1 (0=bypass) */
    fun setReverbAmount(amount: Float)

    interface Factory { fun create(): SpeechEffectsUnit }
}

/**
 * Mutable Instruments Warps Meta-Modulator Unit.
 * 
 * Blends and combines two audio signals using various cross-modulation
 * algorithms, including a vocoder.
 */
interface WarpsUnit : AudioUnit {
    /** Carrier Audio Input */
    val inputLeft: AudioInput
    /** Modulator Audio Input */
    val inputRight: AudioInput
    
    /** Stereo Audio Output (Main sum is 'output') */
    val outputRight: AudioOutput
    
    // Parameters
    val algorithm: AudioInput // Algorithm selection (0.0 to 1.0)
    val timbre: AudioInput    // Timbre / Modulation parameter
    val level1: AudioInput    // Carrier drive
    val level2: AudioInput    // Modulator drive

    /** Enable/disable bypass (zeroes output, preserves state) */
    fun setBypass(bypass: Boolean) {}

    interface Factory { fun create(): WarpsUnit }
}

/**
 * Precision clock generator.
 * Outputs pulses at a set frequency (BPM / 60 * PPQN).
 */
interface ClockUnit : AudioUnit {
    /** Frequency input in Hz (e.g. 2.0 = 120 BPM) */
    val frequency: AudioInput
    
    /** Pulse width input (0.01 - 0.99) */
    val pulseWidth: AudioInput
    
    /** Trigger output (0.0 or 1.0 pulses) */
    override val output: AudioOutput

    interface Factory { fun create(): ClockUnit }
}

/**
 * Flux / Marbles Random Generator Unit.
 */
interface FluxUnit : AudioUnit {
    /** Clock input for triggering new values */
    val clock: AudioInput
    
    /** Spread/Probability distribution width (0.0 - 1.0) */
    val spread: AudioInput
    
    /** Bias/Offset (0.0 - 1.0) */
    val bias: AudioInput
    
    /** Steps/Quantization amount (0.0 - 1.0) */
    val steps: AudioInput
    
    /** Deja Vu/Loop probability (0.0 - 1.0) */
    val dejaVu: AudioInput
    
    /** Length of the loop (mapped to 1-16) */
    val length: AudioInput
    
    /** Clock rate/division (0.0 - 1.0) maps to dividers */
    val rate: AudioInput
    
    /** Jitter amount (0.0 - 1.0) */
    val jitter: AudioInput
    
    /** Gate Probability Bias (0.0 = Favors T3, 1.0 = Favors T1) */
    val probability: AudioInput

    /** Gate Length (0.0 = Trigger, 1.0 = Full step) */
    val gateLength: AudioInput
    
    /** Master Output (X2 - Main Melody) */
    override val output: AudioOutput
    
    /** Secondary Output (X1) */
    val outputX1: AudioOutput
    
    /** Tertiary Output (X3) */
    val outputX3: AudioOutput
    
    /** Gate Output T2 (Main Clock) */
    val outputT2: AudioOutput
    
    /** Gate Output T1 (Probabilistic) */
    val outputT1: AudioOutput
    
    /** Gate Output T3 (Probabilistic) */
    val outputT3: AudioOutput
    
    /**
     * Set the musical scale index.
     * @param index 0=Major, 1=Minor, 2=Pentatonic, etc.
     */
    fun setScale(index: Int)

    /** Set T-generator model (0-6). */
    fun setTModel(index: Int) {}

    /** Set T-generator range (0=0.25x, 1=1x, 2=4x). */
    fun setTRange(index: Int) {}

    /** Set pulse width (0.0-1.0). */
    fun setPulseWidth(value: Float) {}

    /** Set pulse width randomness (0.0-1.0). */
    fun setPulseWidthStd(value: Float) {}

    /** Set control mode (0=Identical, 1=Bump, 2=Tilt). */
    fun setControlMode(index: Int) {}

    /** Set voltage range (0=Narrow, 1=Positive, 2=Full). */
    fun setVoltageRange(index: Int) {}

    /** Set output mix level (0=bypass, 1=full) */
    fun setMix(mix: Float) {}

    /** Bypass processing — zero all outputs to save CPU when Flux is not in use. */
    fun setBypass(bypass: Boolean) {}

    interface Factory { fun create(): FluxUnit }
}
