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
expect interface Oscillator : AudioUnit {
    /** Frequency input in Hz */
    val frequency: AudioInput

    /** Amplitude input (0.0 - 1.0 typically) */
    val amplitude: AudioInput
}

/**
 * DAHDSR Envelope generator.
 */
expect interface Envelope : AudioUnit {
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
}

/**
 * Delay line for audio signals.
 */
expect interface DelayLine : AudioUnit {
    /** Audio input */
    val input: AudioInput

    /** Delay time input in seconds */
    val delay: AudioInput

    /** Allocate buffer for maximum delay in samples */
    fun allocate(maxSamples: Int)
}

/**
 * Peak follower / envelope follower.
 */
expect interface PeakFollower : AudioUnit {
    /** Audio input to track */
    val input: AudioInput

    /** Set half-life decay time in seconds */
    fun setHalfLife(seconds: Double)

    /** Get current peak value */
    fun getCurrent(): Double
}

/**
 * Soft limiter with drive control.
 */
expect interface Limiter : AudioUnit {
    /** Audio input */
    val input: AudioInput

    /** Drive amount input */
    val drive: AudioInput
}

/**
 * Linear ramp for smooth parameter transitions.
 * Prevents zipper noise by interpolating values over time.
 */
expect interface LinearRamp : AudioUnit {
    /** Target value input */
    val input: AudioInput
    
    /** Ramp time in seconds */
    val time: AudioInput
}

/**
 * Player for automation curves.
 */
expect interface AutomationPlayer : AudioUnit {
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
}

/**
 * Specialized drum synthesis unit.
 */
expect interface DrumUnit : AudioUnit {
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
}

/**
 * Modal/String resonator synthesis unit (ported from Mutable Instruments Rings).
 * 
 * Processes audio through either modal synthesis (SVF filter bank) or
 * Karplus-Strong string synthesis.
 */
expect interface ResonatorUnit : AudioUnit {
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
}

/**
 * Mutable Instruments Clouds Texture Synthesizer Unit.
 * 
 * Simplified port focusing on Looping Delay mode.
 */
expect interface GrainsUnit : AudioUnit {
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
}

/**
 * Simple Native Audio Looper.
 * Records into an internal buffer and plays it back.
 */
expect interface LooperUnit : AudioUnit {
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
}

/**
 * Mutable Instruments Warps Meta-Modulator Unit.
 * 
 * Blends and combines two audio signals using various cross-modulation
 * algorithms, including a vocoder.
 */
expect interface WarpsUnit : AudioUnit {
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
}
