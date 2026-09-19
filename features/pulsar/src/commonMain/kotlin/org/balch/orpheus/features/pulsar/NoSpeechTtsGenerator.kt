package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.tts.TtsAudioResult
import org.balch.orpheus.core.tts.TtsGenerator

/** [PulsarViewModel]'s default when no platform TTS is bound: vibes with speech stay silent. */
internal object NoSpeechTtsGenerator : TtsGenerator {
    override val isAvailable: Boolean = false
    override suspend fun generate(text: String, voice: String?, speakingRate: Int?): TtsAudioResult? = null
    override suspend fun listVoices(): List<String> = emptyList()
}
