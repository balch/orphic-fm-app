package org.balch.orpheus.core.presets

import kotlinx.serialization.Serializable
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.features.lfo.HyperLfoMode
import org.balch.orpheus.util.currentTimeMillis

/**
 * Represents a saved Orpheus Drone preset containing all synthesizer parameters.
 */
@Serializable
data class DronePreset(
    val name: String,

    // Voice parameters (8 voices)
    val voiceTunes: List<Float> = List(8) { 0.5f },
    val voiceModDepths: List<Float> = List(8) { 0.0f },
    val voiceEnvelopeSpeeds: List<Float> = List(8) { 0.0f },

    // Pair parameters (4 pairs)
    val pairSharpness: List<Float> = List(4) { 0.0f },
    val duoModSources: List<ModSource> = List(4) { ModSource.OFF },

    // Hyper LFO
    val hyperLfoA: Float = 0.0f,
    val hyperLfoB: Float = 0.0f,
    val hyperLfoMode: HyperLfoMode = HyperLfoMode.OFF,
    val hyperLfoLink: Boolean = false,

    // Delay
    val delayTime1: Float = 0.3f,
    val delayTime2: Float = 0.3f,
    val delayMod1: Float = 0.0f,
    val delayMod2: Float = 0.0f,
    val delayFeedback: Float = 0.5f,
    val delayMix: Float = 0.5f,
    val delayModSourceIsLfo: Boolean = true,
    val delayLfoWaveformIsTriangle: Boolean = true,

    // Global
    val masterVolume: Float = 0.7f,
    val drive: Float = 0.0f,
    val distortionMix: Float = 0.5f,

    // Advanced
    val fmStructureCrossQuad: Boolean = false,
    val totalFeedback: Float = 0.0f,

    // Metadata
    val createdAt: Long = currentTimeMillis()
)
