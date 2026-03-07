package org.balch.orpheus.core.audio.dsp

import kotlin.concurrent.Volatile
import kotlin.math.exp

/**
 * Global sample rate for all DSP processing.
 * Set by the platform AudioEngine before [DspGraphScheduler.allocate] is called.
 * Default is 48kHz; overwritten at runtime with the actual hardware rate.
 */
var dspSampleRate = 48000f

/**
 * Float-native audio input port for the DSP backend.
 * Supports multiple connected sources (summed during prepare).
 * Falls back to constant value when no sources are connected.
 *
 * When [smoothed] is true, constant-value changes ramp via a one-pole
 * exponential filter (~5ms) instead of jumping instantly, preventing
 * clicks at buffer boundaries.
 *
 * Thread safety: [disconnectAll] and [connectFrom] may be called from the UI
 * thread at any time. They publish an immutable snapshot array,
 * which the audio thread reads in [prepare]. This is lock-free and safe for
 * runtime rewiring (e.g., modulation source changes, DuoLFO mode switching).
 */
class DspAudioInput(
    private val name: String = "",
    private val smoothed: Boolean = false,
) : AudioInput {
    private var buffer = FloatArray(0)
    @Volatile private var constantValue = 0f
    @Volatile private var constantDirty = false  // true when set() called but buffer not yet filled
    private var smoothedValue = 0f  // current interpolated value (audio thread only)

    // Mutable staging list — only touched by wiring thread (UI).
    // No synchronization needed: connectFrom() and disconnectAll() are called sequentially
    // from the UI thread during graph wiring (before audio starts or while audio is paused).
    // The volatile sourcesSnapshot publish provides happens-before for the audio thread.
    private val sourcesList = mutableListOf<DspAudioOutput>()
    // Immutable snapshot — published by wiring thread, read by audio thread
    @Volatile private var sourcesSnapshot: Array<DspAudioOutput> = emptyArray()

    fun allocate(maxFrames: Int) {
        buffer = FloatArray(maxFrames)
        // Re-fill with constant value so init-time set() calls survive allocation
        if (sourcesSnapshot.isEmpty()) {
            if (smoothed) {
                smoothedValue = constantValue
            }
            if (constantValue != 0f) {
                buffer.fill(constantValue)
            }
        }
        constantDirty = false
    }

    override fun set(value: Double) {
        constantValue = value.toFloat()
        if (sourcesSnapshot.isEmpty()) {
            constantDirty = true  // Mark dirty — audio thread will fill on next prepare()
        }
    }

    override fun disconnectAll() {
        sourcesList.clear()
        sourcesSnapshot = emptyArray()
        constantDirty = true
    }

    internal fun connectFrom(output: DspAudioOutput) {
        sourcesList.add(output)
        sourcesSnapshot = sourcesList.toTypedArray()
    }

    /**
     * Copy from connected source(s), summing if multiple.
     * If no sources and smoothed, ramp toward target value per-sample.
     * If no sources and not smoothed, only refill buffer when dirty.
     */
    fun prepare(numFrames: Int) {
        if (buffer.isEmpty()) return // Not yet allocated
        val snap = sourcesSnapshot // Single read
        when (snap.size) {
            0 -> {
                if (smoothed) {
                    val target = constantValue
                    var sv = smoothedValue
                    for (i in 0 until numFrames) {
                        sv += SMOOTH_COEFF * (target - sv)
                        buffer[i] = sv
                    }
                    smoothedValue = sv
                    constantDirty = false
                } else if (constantDirty) {
                    buffer.fill(constantValue, 0, numFrames)
                    constantDirty = false
                }
            }
            1 -> {
                // Fast path: single source, just copy
                val src = snap[0].getBuffer()
                if (src.size >= numFrames) {
                    src.copyInto(buffer, 0, 0, numFrames)
                }
            }
            else -> {
                // Multiple sources: sum them
                val first = snap[0].getBuffer()
                if (first.size >= numFrames) {
                    first.copyInto(buffer, 0, 0, numFrames)
                } else {
                    buffer.fill(0f, 0, numFrames)
                }
                for (s in 1 until snap.size) {
                    val src = snap[s].getBuffer()
                    if (src.size >= numFrames) {
                        for (i in 0 until numFrames) {
                            buffer[i] += src[i]
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** One-pole coefficient for ~5ms smoothing, recomputed when sample rate changes. */
        private inline val SMOOTH_COEFF get() = 1f - exp(-1f / (0.005f * dspSampleRate))
    }

    fun getBuffer(): FloatArray = buffer
    fun getValue(index: Int): Float = buffer[index]
    fun hasSource(): Boolean = sourcesSnapshot.isNotEmpty()

    /** Returns current sources for graph topology building (called before audio starts). */
    internal fun getSources(): List<DspAudioOutput> = sourcesList
}

/**
 * Float-native audio output port for the DSP backend.
 */
class DspAudioOutput(private val name: String = "") : AudioOutput {
    private var buffer = FloatArray(0)

    fun allocate(maxFrames: Int) {
        buffer = FloatArray(maxFrames)
    }

    override fun connect(input: AudioInput) {
        if (input is DspAudioInput) {
            input.connectFrom(this)
        }
    }

    override fun connect(channel: Int, input: AudioInput, inputChannel: Int) {
        connect(input)
    }

    fun getBuffer(): FloatArray = buffer
}
