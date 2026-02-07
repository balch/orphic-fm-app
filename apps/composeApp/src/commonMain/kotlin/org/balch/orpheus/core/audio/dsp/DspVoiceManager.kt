package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.plugins.plaits.PlaitsEngineFactory
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import kotlin.math.log2
import kotlin.math.pow

/**
 * Manages the lifecycle, state, and parameters of the 12 DSP voices.
 */
@SingleIn(AppScope::class)
class DspVoiceManager @Inject constructor(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory,
    private val pluginProvider: DspPluginProvider,
    private val engineFactory: PlaitsEngineFactory
) {
    // 8 Voices with pitch ranges (0.5=bass, 1.0=mid, 2.0=high)
    val voices = listOf(
        DspVoice(audioEngine, dspFactory, pitchMultiplier = 0.5),
        DspVoice(audioEngine, dspFactory, pitchMultiplier = 0.5),
        DspVoice(audioEngine, dspFactory, pitchMultiplier = 1.0),
        DspVoice(audioEngine, dspFactory, pitchMultiplier = 1.0),
        DspVoice(audioEngine, dspFactory, pitchMultiplier = 1.0),
        DspVoice(audioEngine, dspFactory, pitchMultiplier = 1.0),
        DspVoice(audioEngine, dspFactory, pitchMultiplier = 2.0),
        DspVoice(audioEngine, dspFactory, pitchMultiplier = 2.0),
        // REPL Voices (Quad 3)
        DspVoice(audioEngine, dspFactory, pitchMultiplier = 1.0),
        DspVoice(audioEngine, dspFactory, pitchMultiplier = 1.0),
        DspVoice(audioEngine, dspFactory, pitchMultiplier = 1.0),
        DspVoice(audioEngine, dspFactory, pitchMultiplier = 1.0)
    )

    // State Caches
    private val _voiceTune = FloatArray(12) { 0.5f }
    private val _voiceFmDepth = FloatArray(12)
    private val _voiceEnvelopeSpeed = FloatArray(12)
    private val _pairSharpness = FloatArray(6)
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
    private val _pairEngine = IntArray(6)  // 0 = default oscillators

    // Quad sources
    private val quadPitchSources = IntArray(3) { 0 }
    private val quadTriggerSources = IntArray(3) { 0 }
    private val quadEnvelopeTriggerModes = BooleanArray(3)

    fun initialize() {
        // Set defaults
        voices.forEach { it.couplingDepth.set(0.0) }
        
        // Wire voice coupling (default structure)
        for (pairIndex in 0 until 6) {
            val voiceA = voices[pairIndex * 2]
            val voiceB = voices[pairIndex * 2 + 1]
            voiceA.envelopeOutput.connect(voiceB.couplingInput)
            voiceB.envelopeOutput.connect(voiceA.couplingInput)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Voice Parameter Control
    // ═══════════════════════════════════════════════════════════

    fun setVoiceTune(index: Int, tune: Float) {
        _voiceTune[index] = tune
        voiceTuneCache[index] = tune.toDouble()
        updateVoiceFrequency(index)
        pluginProvider.voicePlugin.setTune(index, tune)
    }

    private fun updateVoiceFrequency(index: Int) {
        val tune = voiceTuneCache[index]
        val quadIndex = index / 4
        val quadPitch = quadPitchOffsets[quadIndex]
        val baseFreq = 55.0 * 2.0.pow(tune * 4.0)
        val pitchMultiplier = 2.0.pow((quadPitch - 0.5) * 2.0)
        val finalFreq = baseFreq * pitchMultiplier
        voices[index].frequency.set(finalFreq)

        // Update Plaits note (control-rate)
        if (_pairEngine[index / 2] != 0) {
            val midiNote = 69.0 + 12.0 * log2(finalFreq / 440.0)
            voices[index].plaits.setNote(midiNote.toFloat())
        }

        // Update string pluck frequency if this is the primary voice (A) of a pair
        if (index % 2 == 0) {
            val stringIndex = index / 2
            pluginProvider.perStringBenderPlugin.setStringFrequency(stringIndex, finalFreq)
        }
    }

    fun setVoiceGate(index: Int, active: Boolean) {
        voices[index].gate.set(if (active) 1.0 else 0.0)
    }

    fun setVoiceFmDepth(index: Int, amount: Float) {
        _voiceFmDepth[index] = amount
        voices[index].fmDepth.set(amount.toDouble())
        pluginProvider.voicePlugin.setModDepth(index, amount)
    }

    fun setVoiceEnvelopeSpeed(index: Int, speed: Float) {
        _voiceEnvelopeSpeed[index] = speed
        voices[index].setEnvelopeSpeed(speed)
        pluginProvider.voicePlugin.setEnvSpeed(index, speed)
    }

    fun setPairSharpness(pairIndex: Int, sharpness: Float) {
        _pairSharpness[pairIndex] = sharpness
        val voiceA = pairIndex * 2
        val voiceB = voiceA + 1
        voices[voiceA].sharpness.set(sharpness.toDouble())
        voices[voiceB].sharpness.set(sharpness.toDouble())
        updateVoiceTimbre(voiceA)
        updateVoiceTimbre(voiceB)
        pluginProvider.voicePlugin.setPairSharpness(pairIndex, sharpness)
    }

    fun setQuadPitch(quadIndex: Int, pitch: Float) {
        _quadPitch[quadIndex] = pitch
        quadPitchOffsets[quadIndex] = pitch.toDouble()
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            updateVoiceFrequency(i)
        }
        pluginProvider.voicePlugin.setQuadPitch(quadIndex, pitch)
    }

    fun setQuadHold(quadIndex: Int, amount: Float) {
        _quadHold[quadIndex] = amount
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            voices[i].setHoldLevel(amount.toDouble())
        }
        pluginProvider.voicePlugin.setQuadHold(quadIndex, amount)
    }

    fun setQuadVolume(quadIndex: Int, volume: Float) {
        _quadVolume[quadIndex] = volume
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            voices[i].setVolume(volume.toDouble())
        }
        pluginProvider.voicePlugin.setQuadVolume(quadIndex, volume)
    }

    fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float) {
        _quadVolume[quadIndex] = targetVolume
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            voices[i].fadeVolume(targetVolume.toDouble(), durationSeconds.toDouble())
        }
    }

    fun setVoiceHold(index: Int, amount: Float) {
        voices[index].setHoldLevel(amount.toDouble())
    }

    fun setVoiceWobble(index: Int, wobbleOffset: Float, range: Float) {
        val multiplier = 1.0 + (wobbleOffset * range)
        voices[index].setWobbleMultiplier(multiplier.coerceIn(0.0, 2.0))
    }

    fun setDuoModSource(duoIndex: Int, source: ModSource) {
        _duoModSource[duoIndex] = source
        pluginProvider.voicePlugin.setDuoModSource(duoIndex, source.ordinal)
        val voiceA = duoIndex * 2
        val voiceB = voiceA + 1

        voices[voiceA].modInput.disconnectAll()
        voices[voiceB].modInput.disconnectAll()

        when (source) {
            ModSource.OFF -> { }
            ModSource.LFO -> {
                pluginProvider.hyperLfo.output.connect(voices[voiceA].modInput)
                pluginProvider.hyperLfo.output.connect(voices[voiceB].modInput)
            }
            ModSource.VOICE_FM -> {
                if (_fmStructureCrossQuad) {
                    when (duoIndex) {
                        0 -> {
                            voices[6].output.connect(voices[voiceA].modInput)
                            voices[7].output.connect(voices[voiceB].modInput)
                        }
                        1 -> {
                            voices[voiceA].output.connect(voices[voiceB].modInput)
                            voices[voiceB].output.connect(voices[voiceA].modInput)
                        }
                        2 -> {
                            voices[2].output.connect(voices[voiceA].modInput)
                            voices[3].output.connect(voices[voiceB].modInput)
                        }
                        3 -> {
                            voices[voiceA].output.connect(voices[voiceB].modInput)
                            voices[voiceB].output.connect(voices[voiceA].modInput)
                        }
                        else -> {
                            voices[voiceA].output.connect(voices[voiceB].modInput)
                            voices[voiceB].output.connect(voices[voiceA].modInput)
                        }
                    }
                } else {
                    voices[voiceA].output.connect(voices[voiceB].modInput)
                    voices[voiceB].output.connect(voices[voiceA].modInput)
                }
            }
            ModSource.FLUX -> {
                pluginProvider.fluxPlugin.outputs["output"]?.connect(voices[voiceA].modInput)
                pluginProvider.fluxPlugin.outputs["output"]?.connect(voices[voiceB].modInput)
            }
        }
    }

    fun setFmStructure(crossQuad: Boolean) {
        _fmStructureCrossQuad = crossQuad
        pluginProvider.voicePlugin.setFmStructure(crossQuad)
        // Refresh mod sources to apply new structure if using VOICE_FM
        for (i in 0 until 6) {
            if (_duoModSource[i] == ModSource.VOICE_FM) {
                setDuoModSource(i, ModSource.VOICE_FM)
            }
        }
    }

    fun setTotalFeedback(amount: Float) {
        _totalFeedback = amount
        pluginProvider.voicePlugin.setTotalFeedback(amount)
        // Total feedback gain is in DspSynthEngine/Graph, we need to expose it or handle it there? 
        // The instruction was to move "voice logic". Total Feedback wraps around the whole engine usually, 
        // but in DspSynthEngine it feeds `totalFbGain` which goes to HyperLFO.
        // So this state might belong in VoiceManager (as it's a voice/global knob), 
        // but the actuation is on `totalFbGain`.
        // I will keep the state here and let DspSynthEngine observe it or provide a callback/method to update the gain.
        // Actually, DspSynthEngine has the `totalFbGain` unit. 
        // Let's assume DspSynthEngine will query this or we pass a callback?
        // Better: DspVoiceManager purely manages Voices. TotalFeedback is global. 
        // However, the `setTotalFeedback` method was in the list to be moved.
        // I'll keep the state here but the caller (Engine) will need to update the gain unit.
    }

    fun setVoiceCoupling(amount: Float) {
        _voiceCoupling = amount
        val depthHz = amount * 30.0
        voices.forEach { voice ->
            voice.couplingDepth.set(depthHz)
        }
        pluginProvider.voicePlugin.setCoupling(amount)
    }

    fun setQuadPitchSource(quadIndex: Int, sourceIndex: Int) {
        if (quadIndex !in 0..2) return
        quadPitchSources[quadIndex] = sourceIndex
        pluginProvider.voicePlugin.setQuadPitchSource(quadIndex, sourceIndex)
        
        val voiceIndices = (quadIndex * 4) until ((quadIndex + 1) * 4)
        
        for (i in voiceIndices) {
            val voice = voices[i]
            voice.cvPitchInput.disconnectAll()
            
            // 0=None, 1=X1, 2=X2, 3=X3
            when (sourceIndex) {
                1 -> {
                    pluginProvider.fluxPlugin.outputs["outputX1"]?.connect(voice.cvPitchInput)
                    voice.cvPitchDepth.set(200.0)
                }
                2 -> {
                    pluginProvider.fluxPlugin.outputs["output"]?.connect(voice.cvPitchInput)
                    voice.cvPitchDepth.set(200.0)
                }
                3 -> {
                    pluginProvider.fluxPlugin.outputs["outputX3"]?.connect(voice.cvPitchInput)
                    voice.cvPitchDepth.set(200.0)
                }
                else -> {
                    voice.cvPitchDepth.set(0.0)
                }
            }
        }
    }
    
    fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int) {
        if (quadIndex !in 0..2) return
        quadTriggerSources[quadIndex] = sourceIndex
        pluginProvider.voicePlugin.setQuadTriggerSource(quadIndex, sourceIndex)
        
        val voiceIndices = (quadIndex * 4) until ((quadIndex + 1) * 4)
        
        for (i in voiceIndices) {
            val voiceIn = voices[i].gate
            voiceIn.disconnectAll()
            
            when (sourceIndex) {
                1 -> pluginProvider.fluxPlugin.outputs["outputT1"]?.connect(voiceIn)
                2 -> pluginProvider.fluxPlugin.outputs["outputT2"]?.connect(voiceIn)
                3 -> pluginProvider.fluxPlugin.outputs["outputT3"]?.connect(voiceIn)
                else -> { /* Internal/MIDI */ }
            }
        }
    }

    fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean) {
        if (quadIndex !in 0..2) return
        quadEnvelopeTriggerModes[quadIndex] = enabled
        pluginProvider.voicePlugin.setQuadEnvTriggerMode(quadIndex, enabled)
        val voiceIndices = (quadIndex * 4) until ((quadIndex + 1) * 4)
        for (i in voiceIndices) {
            voices[i].setTriggerMode(enabled)
        }
    }

    fun setPairEngine(pairIndex: Int, engineOrdinal: Int) {
        if (pairIndex !in 0..5) return
        _pairEngine[pairIndex] = engineOrdinal
        val voiceA = pairIndex * 2
        val voiceB = voiceA + 1

        if (engineOrdinal == 0) {
            voices[voiceA].setEngineActive(false)
            voices[voiceB].setEngineActive(false)
            voices[voiceA].plaits.setEngine(null)
            voices[voiceB].plaits.setEngine(null)
        } else {
            val engineId = PlaitsEngineId.entries[engineOrdinal - 1]
            voices[voiceA].plaits.setEngine(engineFactory.create(engineId))
            voices[voiceB].plaits.setEngine(engineFactory.create(engineId))
            voices[voiceA].setEngineActive(true)
            voices[voiceB].setEngineActive(true)
            updateVoiceFrequency(voiceA)
            updateVoiceFrequency(voiceB)
            updateVoiceTimbre(voiceA)
            updateVoiceTimbre(voiceB)
        }
        pluginProvider.voicePlugin.setPairEngine(pairIndex, engineOrdinal)
    }

    private fun updateVoiceTimbre(index: Int) {
        if (_pairEngine[index / 2] != 0) {
            voices[index].plaits.setTimbre(_pairSharpness[index / 2])
        }
    }

    fun getPairEngine(pairIndex: Int) = _pairEngine.getOrElse(pairIndex) { 0 }

    // Getters
    fun getVoiceTune(index: Int) = _voiceTune[index]
    fun getVoiceFmDepth(index: Int) = _voiceFmDepth[index]
    fun getVoiceEnvelopeSpeed(index: Int) = _voiceEnvelopeSpeed[index]
    fun getPairSharpness(pairIndex: Int) = _pairSharpness[pairIndex]
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
