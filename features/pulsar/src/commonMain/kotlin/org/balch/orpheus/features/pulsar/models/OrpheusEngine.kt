package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable
import org.balch.orpheus.core.audio.OrpheusEngineId

@Serializable
data class OrpheusEngine(
    val engineId: OrpheusEngineId,
    val volume: Float = .8f,
    val harmonics: Float = 0.5f,
    val timbre: Float = 0.5f,
    val morph: Float = 0.5f,
    val modLfoRate: Float = 0.2f,
    val modLfoDepth: Float = 0.0f,
    val modLfoShape: Float = 0.3f,
    val modLfoCoupling: Float = 0.2f,
    val holdProbability: Float = 0.0f,
    val holdLengthMin: Int = 2,
    val holdLengthMax: Int = 8,
    val delaySend: Float = 0.0f,
    val reverbSend: Float = 0.0f,
    val noteRangeLow: Int = 0,
    val noteRangeHigh: Int = 0,
    val reverbBrightness: Float = 0.5f,
    val delayFeedback: Float? = null,
    val glideRate: Float = 0.0f,
    val lpgMode: LpgMode = LpgMode.ENGINE_DEFAULT,
    val lpgDecay: Float = 0.5f,
    val lpgColour: Float = 0.5f,
)
