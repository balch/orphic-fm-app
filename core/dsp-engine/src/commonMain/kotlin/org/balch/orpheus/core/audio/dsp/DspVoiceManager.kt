package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.ModSource
import kotlin.math.pow

/**
 * Manages state caches and forwards voice parameters to C++ via pluginProvider.
 * No longer owns DspVoice instances or manipulates the Kotlin audio graph.
 */
@SingleIn(AppScope::class)
@Inject
class DspVoiceManager(
    private val pluginProvider: DspPluginProvider,
) {
    // State Caches
    private val _voiceTune = FloatArray(12) { 0.5f }
    private val _voiceFmDepth = FloatArray(12)
    private val _voiceEnvelopeSpeed = FloatArray(12)
    private val _duoSharpness = FloatArray(6)
    private val _duoModSource = Array(6) { ModSource.OFF }
    private val _quadPitch = FloatArray(3) { 0.5f }
    private val _quadHold = FloatArray(3)
    private val _quadVolume = FloatArray(3) { 1.0f }

    // Internal caches for frequency calc
    private val voiceTuneCache = DoubleArray(12) { 0.5 }
    private val quadPitchOffsets = DoubleArray(3) { 0.5 }

    // Configuration
    private var _fmStructureCrossQuad = false
    private var _totalFeedback = 0.0f
    private var _voiceCoupling = 0.0f

    // Plaits engine selection
    private val _duoEngine = IntArray(6)  // 0 = default oscillators
    private val _duoHarmonics = FloatArray(6) { 0.0f }
    private val _duoProsody = FloatArray(6) { 0.5f }
    private val _duoSpeed = FloatArray(6) { 0.0f }
    private val _duoMorph = FloatArray(6) { 0.0f }
    private val _duoModSourceLevel = FloatArray(6) { 0.0f }

    // Voice idle tracking
    private val _voiceIdle = BooleanArray(12) { false }

    // Quad sources
    private val quadPitchSources = IntArray(3) { 0 }
    private val quadTriggerSources = IntArray(3) { 0 }
    private val quadEnvelopeTriggerModes = BooleanArray(3)

    fun initialize() {
        // Main voices (0-7) start enabled; REPL voices (8-11) start idle.
        for (i in 0 until 8) { setVoiceIdle(i, false) }
        for (i in 8 until 12) { setVoiceIdle(i, true) }
    }

    // ═══════════════════════════════════════════════════════════
    // Voice Idle Control
    // ═══════════════════════════════════════════════════════════

    fun isVoiceIdle(index: Int): Boolean = _voiceIdle[index]

    fun setVoiceIdle(index: Int, idle: Boolean) {
        if (_voiceIdle[index] == idle) return
        _voiceIdle[index] = idle
    }

    // ═══════════════════════════════════════════════════════════
    // Voice Parameter Control
    // ═══════════════════════════════════════════════════════════

    fun setVoiceTune(index: Int, tune: Float) {
        _voiceTune[index] = tune
        voiceTuneCache[index] = tune.toDouble()
        pluginProvider.voicePlugin.setTune(index, tune)

        // Update string pluck frequency for the primary voice (A) of a duo
        if (index % 2 == 0) {
            val stringIndex = index / 2
            val quadIndex = index / 4
            val quadPitch = quadPitchOffsets[quadIndex]
            val baseFreq = 55.0 * 2.0.pow(tune * 4.0)
            val pitchMultiplier = 2.0.pow((quadPitch - 0.5) * 2.0)
            val finalFreq = baseFreq * pitchMultiplier
            pluginProvider.perStringBenderPlugin.setStringFrequency(stringIndex, finalFreq)
        }
    }

    fun setVoiceGate(index: Int, active: Boolean) {
        if (active && _voiceIdle[index]) setVoiceIdle(index, false)
    }

    fun setVoiceFmDepth(index: Int, amount: Float) {
        _voiceFmDepth[index] = amount
        pluginProvider.voicePlugin.setModDepth(index, amount)
    }

    fun setVoiceEnvelopeSpeed(index: Int, speed: Float) {
        _voiceEnvelopeSpeed[index] = speed
        pluginProvider.voicePlugin.setEnvSpeed(index, speed)
    }

    fun setDuoSharpness(duoIndex: Int, sharpness: Float) {
        _duoSharpness[duoIndex] = sharpness
        pluginProvider.voicePlugin.setDuoSharpness(duoIndex, sharpness)
    }

    fun setQuadPitch(quadIndex: Int, pitch: Float) {
        _quadPitch[quadIndex] = pitch
        quadPitchOffsets[quadIndex] = pitch.toDouble()

        // Update string pluck frequencies for primary voices in this quad
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            if (i % 2 == 0) {
                val stringIndex = i / 2
                val tune = voiceTuneCache[i]
                val baseFreq = 55.0 * 2.0.pow(tune * 4.0)
                val pitchMultiplier = 2.0.pow((pitch - 0.5) * 2.0)
                val finalFreq = baseFreq * pitchMultiplier
                pluginProvider.perStringBenderPlugin.setStringFrequency(stringIndex, finalFreq)
            }
        }

        pluginProvider.voicePlugin.setQuadPitch(quadIndex, pitch)
    }

    fun setQuadHold(quadIndex: Int, amount: Float) {
        _quadHold[quadIndex] = amount
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            if (amount > 0.001f && _voiceIdle[i]) setVoiceIdle(i, false)
        }
        pluginProvider.voicePlugin.setQuadHold(quadIndex, amount)
    }

    fun setQuadVolume(quadIndex: Int, volume: Float) {
        _quadVolume[quadIndex] = volume
        pluginProvider.voicePlugin.setQuadVolume(quadIndex, volume)
    }

    fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float) {
        _quadVolume[quadIndex] = targetVolume
        // No-op: Kotlin DSP volume ramp removed. C++ handles volume directly.
    }

    fun setVoiceHold(index: Int, amount: Float) {
        if (amount > 0.001f && _voiceIdle[index]) setVoiceIdle(index, false)
    }

    fun setVoiceWobble(index: Int, wobbleOffset: Float, range: Float) {
        // No-op: Kotlin DSP wobble removed. C++ handles wobble directly if needed.
    }

    fun setDuoModSource(duoIndex: Int, source: ModSource) {
        _duoModSource[duoIndex] = source
        pluginProvider.voicePlugin.setDuoModSource(duoIndex, source.ordinal)
    }

    fun setFmStructure(crossQuad: Boolean) {
        _fmStructureCrossQuad = crossQuad
        pluginProvider.voicePlugin.setFmStructure(crossQuad)
    }

    fun setTotalFeedback(amount: Float) {
        _totalFeedback = amount
        pluginProvider.voicePlugin.setTotalFeedback(amount)
    }

    fun setVoiceCoupling(amount: Float) {
        _voiceCoupling = amount
        pluginProvider.voicePlugin.setCoupling(amount)
    }

    fun setQuadPitchSource(quadIndex: Int, sourceIndex: Int) {
        if (quadIndex !in 0..2) return
        quadPitchSources[quadIndex] = sourceIndex
        pluginProvider.voicePlugin.setQuadPitchSource(quadIndex, sourceIndex)
    }

    fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int) {
        if (quadIndex !in 0..2) return
        quadTriggerSources[quadIndex] = sourceIndex
        pluginProvider.voicePlugin.setQuadTriggerSource(quadIndex, sourceIndex)

        // Wake voices if connecting external trigger
        val voiceIndices = (quadIndex * 4) until ((quadIndex + 1) * 4)
        for (i in voiceIndices) {
            if (sourceIndex != 0 && _voiceIdle[i]) setVoiceIdle(i, false)
        }
    }

    fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean) {
        if (quadIndex !in 0..2) return
        quadEnvelopeTriggerModes[quadIndex] = enabled
        pluginProvider.voicePlugin.setQuadEnvTriggerMode(quadIndex, enabled)
    }

    fun setDuoEngine(duoIndex: Int, engineOrdinal: Int) {
        if (duoIndex !in 0..5) return
        _duoEngine[duoIndex] = engineOrdinal
        pluginProvider.voicePlugin.setDuoEngine(duoIndex, engineOrdinal)
    }

    fun setDuoHarmonics(duoIndex: Int, value: Float) {
        if (duoIndex !in 0..5) return
        _duoHarmonics[duoIndex] = value
        pluginProvider.voicePlugin.setDuoHarmonics(duoIndex, value)
    }

    fun getDuoHarmonics(duoIndex: Int) = _duoHarmonics.getOrElse(duoIndex) { 0.0f }

    fun setDuoProsody(duoIndex: Int, value: Float) {
        if (duoIndex !in 0..5) return
        _duoProsody[duoIndex] = value
        pluginProvider.voicePlugin.setDuoProsody(duoIndex, value)
    }

    fun getDuoProsody(duoIndex: Int) = _duoProsody.getOrElse(duoIndex) { 0.5f }

    fun setDuoSpeed(duoIndex: Int, value: Float) {
        if (duoIndex !in 0..5) return
        _duoSpeed[duoIndex] = value
        pluginProvider.voicePlugin.setDuoSpeed(duoIndex, value)
    }

    fun getDuoSpeed(duoIndex: Int) = _duoSpeed.getOrElse(duoIndex) { 0.0f }

    fun setDuoMorph(duoIndex: Int, value: Float) {
        if (duoIndex !in 0..5) return
        _duoMorph[duoIndex] = value
        pluginProvider.voicePlugin.setDuoMorph(duoIndex, value)
    }

    fun getDuoMorph(duoIndex: Int) = _duoMorph.getOrElse(duoIndex) { 0.0f }

    fun setDuoModSourceLevel(duoIndex: Int, value: Float) {
        if (duoIndex !in 0..5) return
        _duoModSourceLevel[duoIndex] = value
        val voiceA = duoIndex * 2
        val voiceB = voiceA + 1
        _voiceFmDepth[voiceA] = value
        _voiceFmDepth[voiceB] = value
        pluginProvider.voicePlugin.setDuoModSourceLevel(duoIndex, value)
    }

    fun getDuoModSourceLevel(duoIndex: Int) = _duoModSourceLevel.getOrElse(duoIndex) { 0.0f }

    fun getDuoEngine(duoIndex: Int) = _duoEngine.getOrElse(duoIndex) { 0 }

    // Getters
    fun getVoiceTune(index: Int) = _voiceTune[index]
    fun getVoiceFmDepth(index: Int) = _voiceFmDepth[index]
    fun getVoiceEnvelopeSpeed(index: Int) = _voiceEnvelopeSpeed[index]
    fun getDuoSharpness(duoIndex: Int) = _duoSharpness[duoIndex]
    fun getDuoModSource(duoIndex: Int) = _duoModSource[duoIndex]
    fun getQuadPitch(quadIndex: Int) = _quadPitch[quadIndex]
    fun getQuadHold(quadIndex: Int) = _quadHold[quadIndex]
    fun getQuadVolume(quadIndex: Int) = _quadVolume[quadIndex]
    fun getFmStructureCrossQuad() = _fmStructureCrossQuad
    fun getTotalFeedback() = _totalFeedback
    fun getVoiceCoupling() = _voiceCoupling
    fun getQuadPitchSource(quadIndex: Int) = quadPitchSources.getOrElse(quadIndex) { 0 }
    fun getQuadTriggerSource(quadIndex: Int) = quadTriggerSources.getOrElse(quadIndex) { 0 }
    fun getQuadEnvelopeTriggerMode(quadIndex: Int) = quadEnvelopeTriggerModes.getOrElse(quadIndex) { false }

    // Automation helpers
    fun getVoiceTuneArrayCopy() = _voiceTune.copyOf()
    fun getQuadPitchArrayCopy() = _quadPitch.copyOf()
    fun getQuadHoldArrayCopy() = _quadHold.copyOf()
}
