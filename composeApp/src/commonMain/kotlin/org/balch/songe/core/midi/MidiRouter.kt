package org.balch.songe

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.songe.core.audio.ModSource
import org.balch.songe.core.midi.MidiEventListener
import org.balch.songe.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.songe.features.delay.DelayViewModel
import org.balch.songe.features.distortion.DistortionViewModel
import org.balch.songe.features.lfo.HyperLfoMode
import org.balch.songe.features.lfo.LfoViewModel
import org.balch.songe.features.midi.MidiViewModel
import org.balch.songe.features.voice.VoiceViewModel
import org.balch.songe.util.Logger
import kotlin.math.roundToInt

/**
 * Routes MIDI events to the appropriate feature ViewModels.
 * Handles CC value tracking, button toggling, and cycle control logic.
 */
@SingleIn(AppScope::class)
@Inject
class MidiRouter(
    private val voiceViewModel: VoiceViewModel,
    private val delayViewModel: DelayViewModel,
    private val lfoViewModel: LfoViewModel,
    private val distortionViewModel: DistortionViewModel,
    private val midiViewModel: MidiViewModel
) {
    // Track last CC values for button toggle detection
    private val lastCcValues = mutableMapOf<String, Float>()
    private val lastRawCcValues = mutableMapOf<String, Float>()

    fun createMidiEventListener(): MidiEventListener {
        return object : MidiEventListener {
            override fun onNoteOn(note: Int, velocity: Int) {
                // Voice trigger
                midiViewModel.getVoiceForNote(note)?.let { voiceIndex ->
                    voiceViewModel.onPulseStart(voiceIndex)
                }

                // Control trigger (for buttons mapped to notes)
                midiViewModel.getControlForNote(note)?.let { controlId ->
                    if (velocity > 0) {
                        if (isCycleControl(controlId)) {
                            cycleControl(controlId, 3)
                        } else {
                            toggleControl(controlId)
                        }
                    }
                }
            }

            override fun onNoteOff(note: Int) {
                midiViewModel.getVoiceForNote(note)?.let { voiceIndex ->
                    voiceViewModel.onPulseEnd(voiceIndex)
                }
            }

            override fun onControlChange(controller: Int, value: Int) {
                val normalized = value / 127f
                midiViewModel.getControlForCC(controller)?.let { controlId ->
                    applyCCToControl(controlId, normalized)
                }

                // Legacy: CC1 = Mod wheel → vibrato (if not mapped)
                if (controller == 1 && midiViewModel.getControlForCC(1) == null) {
                    voiceViewModel.onVibratoChange(normalized)
                }
            }

            override fun onPitchBend(value: Int) {
                // Could apply to quad pitch or other parameter
            }
        }
    }

    private fun applyCCToControl(controlId: String, value: Float) {
        val isCycleControl =
            controlId == ControlIds.HYPER_LFO_MODE ||
                    (controlId.startsWith("pair_") && controlId.endsWith("_mod_source"))

        var effectiveValue = value

        if (!isCycleControl) {
            val lastRaw = lastRawCcValues[controlId] ?: 0f
            val isJumpUp = value >= 0.9f && lastRaw < 0.5f
            val isJumpDown = value < 0.1f && lastRaw > 0.5f
            val lastEffective = lastCcValues[controlId] ?: 0f

            effectiveValue =
                when {
                    isJumpUp -> if (lastEffective > 0.5f) 0f else 1f
                    isJumpDown -> lastEffective
                    else -> value
                }
        }

        dispatchControlChange(controlId, effectiveValue)
        lastCcValues[controlId] = effectiveValue
        lastRawCcValues[controlId] = value
    }

    private fun toggleControl(controlId: String) {
        val lastValue = lastCcValues[controlId] ?: 0f
        val newValue = if (lastValue > 0.5f) 0f else 1f
        lastCcValues[controlId] = newValue
        dispatchControlChange(controlId, newValue)
    }

    private fun cycleControl(controlId: String, numStates: Int) {
        val lastValue = lastCcValues[controlId] ?: 0f
        val currentIndex = (lastValue * (numStates - 1)).roundToInt()
        val nextIndex = (currentIndex + 1) % numStates
        val newValue = nextIndex.toFloat() / (numStates - 1)
        lastCcValues[controlId] = newValue
        dispatchControlChange(controlId, newValue)
    }

    private fun isCycleControl(controlId: String): Boolean {
        return controlId == ControlIds.HYPER_LFO_MODE ||
                (controlId.startsWith("pair_") && controlId.endsWith("_mod_source"))
    }

    private fun dispatchControlChange(controlId: String, value: Float) {
        when (controlId) {
            // Voice tunes (delegate to VoiceViewModel)
            ControlIds.voiceTune(0) -> voiceViewModel.onVoiceTuneChange(0, value)
            ControlIds.voiceTune(1) -> voiceViewModel.onVoiceTuneChange(1, value)
            ControlIds.voiceTune(2) -> voiceViewModel.onVoiceTuneChange(2, value)
            ControlIds.voiceTune(3) -> voiceViewModel.onVoiceTuneChange(3, value)
            ControlIds.voiceTune(4) -> voiceViewModel.onVoiceTuneChange(4, value)
            ControlIds.voiceTune(5) -> voiceViewModel.onVoiceTuneChange(5, value)
            ControlIds.voiceTune(6) -> voiceViewModel.onVoiceTuneChange(6, value)
            ControlIds.voiceTune(7) -> voiceViewModel.onVoiceTuneChange(7, value)

            // Voice FM depths
            ControlIds.voiceFmDepth(0),
            ControlIds.voiceFmDepth(1) -> voiceViewModel.onDuoModDepthChange(0, value)

            ControlIds.voiceFmDepth(2), ControlIds.voiceFmDepth(3) ->
                voiceViewModel.onDuoModDepthChange(1, value)

            ControlIds.voiceFmDepth(4), ControlIds.voiceFmDepth(5) ->
                voiceViewModel.onDuoModDepthChange(2, value)

            ControlIds.voiceFmDepth(6), ControlIds.voiceFmDepth(7) ->
                voiceViewModel.onDuoModDepthChange(3, value)

            // Pair sharpness
            ControlIds.pairSharpness(0) -> voiceViewModel.onPairSharpnessChange(0, value)
            ControlIds.pairSharpness(1) -> voiceViewModel.onPairSharpnessChange(1, value)
            ControlIds.pairSharpness(2) -> voiceViewModel.onPairSharpnessChange(2, value)
            ControlIds.pairSharpness(3) -> voiceViewModel.onPairSharpnessChange(3, value)

            // Delay (delegate to DelayViewModel)
            ControlIds.DELAY_TIME_1 -> delayViewModel.onTime1Change(value)
            ControlIds.DELAY_TIME_2 -> delayViewModel.onTime2Change(value)
            ControlIds.DELAY_MOD_1 -> delayViewModel.onMod1Change(value)
            ControlIds.DELAY_MOD_2 -> delayViewModel.onMod2Change(value)
            ControlIds.DELAY_FEEDBACK -> delayViewModel.onFeedbackChange(value)
            ControlIds.DELAY_MIX -> delayViewModel.onMixChange(value)
            ControlIds.DELAY_MOD_SOURCE -> delayViewModel.onSourceChange(value >= 0.5f)
            ControlIds.DELAY_LFO_WAVEFORM -> delayViewModel.onWaveformChange(value >= 0.5f)

            // LFO (delegate to LfoViewModel)
            ControlIds.HYPER_LFO_A -> lfoViewModel.onLfoAChange(value)
            ControlIds.HYPER_LFO_B -> lfoViewModel.onLfoBChange(value)
            ControlIds.HYPER_LFO_MODE -> {
                val lastRaw = lastRawCcValues[controlId] ?: 0f
                if (value >= 0.9f && lastRaw < 0.5f) {
                    val modes = HyperLfoMode.values()
                    val currentMode = lfoViewModel.uiState.value.mode
                    val nextIndex = (currentMode.ordinal + 1) % modes.size
                    lfoViewModel.onModeChange(modes[nextIndex])
                } else {
                    val modes = HyperLfoMode.values()
                    val index = (value * (modes.size - 1)).roundToInt()
                    lfoViewModel.onModeChange(modes[index])
                }
            }

            ControlIds.HYPER_LFO_LINK -> lfoViewModel.onLinkChange(value >= 0.5f)

            // Distortion (delegate to DistortionViewModel)
            ControlIds.MASTER_VOLUME -> distortionViewModel.onVolumeChange(value)
            ControlIds.DRIVE -> distortionViewModel.onDriveChange(value)
            ControlIds.DISTORTION_MIX -> distortionViewModel.onMixChange(value)

            // Advanced FM
            ControlIds.VIBRATO -> voiceViewModel.onVibratoChange(value)
            ControlIds.VOICE_COUPLING -> voiceViewModel.onVoiceCouplingChange(value)
            ControlIds.TOTAL_FEEDBACK -> voiceViewModel.onTotalFeedbackChange(value)

            // Quad controls
            ControlIds.quadPitch(0) -> voiceViewModel.onQuadPitchChange(0, value)
            ControlIds.quadPitch(1) -> voiceViewModel.onQuadPitchChange(1, value)
            ControlIds.quadHold(0) -> voiceViewModel.onQuadHoldChange(0, value)
            ControlIds.quadHold(1) -> voiceViewModel.onQuadHoldChange(1, value)
            else -> handleDynamicControlId(controlId, value)
        }
    }

    private fun handleDynamicControlId(controlId: String, value: Float) {
        when {
            controlId.startsWith("voice_") && controlId.endsWith("_tune") -> {
                val index = controlId.removePrefix("voice_").removeSuffix("_tune").toIntOrNull()
                if (index != null) voiceViewModel.onVoiceTuneChange(index, value)
            }

            controlId.startsWith("voice_") && controlId.endsWith("_fm_depth") -> {
                val index = controlId.removePrefix("voice_").removeSuffix("_fm_depth").toIntOrNull()
                if (index != null) voiceViewModel.onVoiceModDepthChange(index, value)
            }

            controlId.startsWith("voice_") && controlId.endsWith("_env_speed") -> {
                val index =
                    controlId.removePrefix("voice_").removeSuffix("_env_speed").toIntOrNull()
                if (index != null) voiceViewModel.onVoiceEnvelopeSpeedChange(index, value)
            }

            controlId.startsWith("voice_") && controlId.endsWith("_hold") -> {
                val index = controlId.removePrefix("voice_").removeSuffix("_hold").toIntOrNull()
                if (index != null) voiceViewModel.onHoldChange(index, value >= 0.5f)
            }

            controlId.startsWith("pair_") && controlId.endsWith("_sharpness") -> {
                val index = controlId.removePrefix("pair_").removeSuffix("_sharpness").toIntOrNull()
                if (index != null) voiceViewModel.onPairSharpnessChange(index, value)
            }

            controlId.startsWith("pair_") && controlId.endsWith("_mod_source") -> {
                val index =
                    controlId.removePrefix("pair_").removeSuffix("_mod_source").toIntOrNull()
                if (index != null) {
                    val lastRaw = lastRawCcValues[controlId] ?: 0f
                    if (value >= 0.9f && lastRaw < 0.5f) {
                        val sources = ModSource.values()
                        val current = voiceViewModel.uiState.value.duoModSources[index]
                        val nextIndex = (current.ordinal + 1) % sources.size
                        voiceViewModel.onDuoModSourceChange(index, sources[nextIndex])
                    } else if (value != lastRaw && !(value >= 0.9f && lastRaw < 0.5f)) {
                        val sources = ModSource.values()
                        val srcIndex = (value * (sources.size - 1)).roundToInt()
                        voiceViewModel.onDuoModSourceChange(index, sources[srcIndex])
                    }
                }
            }

            else -> Logger.warn { "Unknown control ID for CC mapping: $controlId" }
        }
    }
}
