package org.balch.orpheus.core.speech

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WasmTtsGenerator @Inject constructor() : TtsGenerator {
    override val isAvailable: Boolean = false
    override suspend fun generate(text: String, voice: String?, speakingRate: Int?): TtsAudioResult? = null
    override suspend fun listVoices(): List<String> = emptyList()
}
