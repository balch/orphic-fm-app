package org.balch.orpheus.core.speech

data class TtsAudioResult(val samples: FloatArray, val sampleRate: Int)

interface TtsGenerator {
    val isAvailable: Boolean
    suspend fun generate(text: String, voice: String? = null, speakingRate: Int? = null): TtsAudioResult?
    suspend fun listVoices(): List<String>
}
