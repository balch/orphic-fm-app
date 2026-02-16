package org.balch.orpheus.features.voice

import androidx.compose.runtime.Immutable
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.VoiceState

@Immutable
data class VoiceUiState(
    val voiceStates: List<VoiceState> =
        List(12) { index -> VoiceState(index = index, tune = DEFAULT_TUNINGS.getOrElse(index) { 0.5f }) },
    val voiceModDepths: List<Float> = List(12) { 0.0f },
    val duoSharpness: List<Float> = List(6) { 0.0f },
    val voiceEnvelopeSpeeds: List<Float> = List(12) { 0.0f },
    val duoModSources: List<ModSource> = List(6) { ModSource.OFF },
    val quadGroupPitches: List<Float> = List(3) { 0.5f },
    val quadGroupHolds: List<Float> = List(3) { 0.0f },
    val quadGroupVolumes: List<Float> = List(3) { 1.0f },
    val fmStructureCrossQuad: Boolean = false,
    val totalFeedback: Float = 0.0f,
    val vibrato: Float = 0.0f,
    val voiceCoupling: Float = 0.0f,
    val masterVolume: Float = 0.7f,
    val peakLevel: Float = 0.0f,
    val bendPosition: Float = 0.0f,
    val bpm: Double = 120.0,
    val duoEngines: List<Int> = List(6) { 0 },
    val duoHarmonics: List<Float> = List(6) { 0.5f },
    val duoMorphs: List<Float> = List(6) { 0.0f },
    val duoModSourceLevels: List<Float> = List(6) { 0.0f },
    val quadTriggerSources: List<Int> = List(3) { 0 },
    val quadPitchSources: List<Int> = List(3) { 0 },
    val quadEnvelopeTriggerModes: List<Boolean> = listOf(false, false, false),
    val aiVoiceEngineHighlights: List<Boolean> = List(6) { false }
) {
    companion object {
        val DEFAULT_TUNINGS = listOf(0.20f, 0.27f, 0.34f, 0.40f, 0.47f, 0.54f, 0.61f, 0.68f, 0.75f, 0.82f, 0.89f, 0.96f)
    }
}

/** User intents for voice management. */
internal sealed interface VoiceIntent {
    // Voice-level intents
    data class Tune(val index: Int, val value: Float) : VoiceIntent
    data class ModDepth(val index: Int, val value: Float) : VoiceIntent
    data class EnvelopeSpeed(val index: Int, val value: Float) : VoiceIntent
    data class PulseStart(val index: Int) : VoiceIntent
    data class PulseEnd(val index: Int) : VoiceIntent
    data class Hold(val index: Int, val holding: Boolean) : VoiceIntent

    // Duo-level intents
    data class DuoSharpness(val duoIndex: Int, val value: Float) : VoiceIntent
    data class DuoModSource(val duoIndex: Int, val source: ModSource) : VoiceIntent
    data class DuoEngine(val duoIndex: Int, val engineOrdinal: Int) : VoiceIntent
    data class DuoHarmonics(val duoIndex: Int, val value: Float) : VoiceIntent
    data class DuoMorph(val duoIndex: Int, val value: Float) : VoiceIntent
    data class DuoModSourceLevel(val duoIndex: Int, val value: Float) : VoiceIntent

    // Quad-level intents
    data class QuadPitch(val quadIndex: Int, val value: Float) : VoiceIntent
    data class QuadHold(val quadIndex: Int, val value: Float) : VoiceIntent
    data class QuadVolume(val quadIndex: Int, val value: Float) : VoiceIntent
    data class QuadTriggerSource(val quadIndex: Int, val sourceIndex: Int) : VoiceIntent
    data class QuadPitchSource(val quadIndex: Int, val sourceIndex: Int) : VoiceIntent
    data class QuadEnvelopeTriggerMode(val quadIndex: Int, val enabled: Boolean) : VoiceIntent

    // AI feedback
    data class AiVoiceEngineHighlight(val duoIndex: Int, val show: Boolean) : VoiceIntent

    // Global intents
    data class FmStructure(val crossQuad: Boolean) : VoiceIntent
    data class TotalFeedback(val value: Float) : VoiceIntent
    data class Vibrato(val value: Float) : VoiceIntent
    data class VoiceCoupling(val value: Float) : VoiceIntent
    data class MasterVolume(val value: Float) : VoiceIntent
    data class PeakLevel(val value: Float) : VoiceIntent
    data class BendPosition(val value: Float) : VoiceIntent
    data class SetBpm(val value: Double) : VoiceIntent
}
