package org.balch.orpheus.core.audio

data class VoiceState(
    val index: Int,
    val tune: Float = 0.5f,
    val feedback: Float = 0.0f,
    val pulse: Boolean = false,
    val isHolding: Boolean = false,
    val groupPitch: Float = 0.5f, // Shared 1-4 or 5-8
    val groupFm: Float = 0.0f     // Shared 1-4 or 5-8
)

data class GlobalState(
    val drive: Float = 0.0f,
    val delayTime: Float = 0.5f,
    val delayFeedback: Float = 0.3f,
    val distortion: Float = 0.0f
)
