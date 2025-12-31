package org.balch.orpheus.core.presets

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.features.lfo.HyperLfoMode

/**
 * Handles preset serialization/deserialization between DronePreset and feature ViewModels.
 */
@SingleIn(AppScope::class)
@Inject
class PresetLoader(
    private val engine: SynthEngine
) {

    // Shared flow to broadcast preset changes to ViewModels
    private val _presetFlow =
        MutableSharedFlow<DronePreset>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    val presetFlow: SharedFlow<DronePreset> = _presetFlow.asSharedFlow()

    /**
     * Apply a preset:
     * 1. Broadcast to ViewModels via Flow (for UI update)
     * 2. ViewModels will then update the Engine, OR we can update Engine here directly.
     *    To be safe and ensure UI/Engine sync, we emit to ViewModels and let them update the engine as they usually do,
     *    OR we update engine here and ViewModels just update their UI state.
     *    Adopting the "Reactive" approach: We emit the preset. ViewModels subscribe, update their state, AND push to engine.
     * 
     * Note: Master Volume is intentionally NOT applied from presets. It is only
     * controllable via direct user interaction.
     */
    fun applyPreset(preset: DronePreset) {
        _presetFlow.tryEmit(preset)
    }

    /**
     * Capture the current state directly from the Engine.
     */
    fun currentStateAsPreset(name: String): DronePreset {
        return DronePreset(
            name = name,
            voiceTunes = List(12) { i -> engine.getVoiceTune(i) },
            voiceModDepths = List(12) { i -> engine.getVoiceFmDepth(i) },
            voiceEnvelopeSpeeds = List(12) { i -> engine.getVoiceEnvelopeSpeed(i) },
            pairSharpness = List(6) { i -> engine.getPairSharpness(i) },
            duoModSources = List(6) { i -> engine.getDuoModSource(i) },
            hyperLfoA = engine.getHyperLfoFreq(0),
            hyperLfoB = engine.getHyperLfoFreq(1),
            hyperLfoMode =
                try {
                    HyperLfoMode.entries[engine.getHyperLfoMode()]
                } catch (e: Exception) {
                    HyperLfoMode.OFF
                },
            hyperLfoLink = engine.getHyperLfoLink(),
            delayTime1 = engine.getDelayTime(0),
            delayTime2 = engine.getDelayTime(1),
            delayMod1 = engine.getDelayModDepth(0),
            delayMod2 = engine.getDelayModDepth(1),
            delayFeedback = engine.getDelayFeedback(),
            delayMix = engine.getDelayMix(),
            delayModSourceIsLfo = engine.getDelayModSourceIsLfo(0), // Assumes coupled for UI preset typically
            delayLfoWaveformIsTriangle = engine.getDelayLfoWaveformIsTriangle(),
            masterVolume = engine.getMasterVolume(),
            drive = engine.getDrive(),
            distortionMix = engine.getDistortionMix(),
            fmStructureCrossQuad = engine.getFmStructureCrossQuad(),
            totalFeedback = engine.getTotalFeedback(),
            vibrato = engine.getVibrato(),
            voiceCoupling = engine.getVoiceCoupling(),
            quadGroupPitches = List(3) { i -> engine.getQuadPitch(i) },
            quadGroupHolds = List(3) { i -> engine.getQuadHold(i) }
        )
    }
}
