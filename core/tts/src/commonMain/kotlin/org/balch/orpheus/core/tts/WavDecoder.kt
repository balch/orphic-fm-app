package org.balch.orpheus.core.tts

/**
 * Decodes the 16-bit PCM WAV that Android's `TextToSpeech.synthesizeToFile` writes.
 * Lives in commonMain because it is pure byte math, which keeps it under `jvmTest`.
 */
internal object WavDecoder {

    fun decode(bytes: ByteArray): TtsAudioResult? {
        if (bytes.size < MIN_HEADER || !tagIs(bytes, 0, "RIFF") || !tagIs(bytes, 8, "WAVE")) return null

        var channels = 0
        var sampleRate = 0
        var pos = MIN_HEADER
        while (pos + CHUNK_HEADER <= bytes.size) {
            val body = pos + CHUNK_HEADER
            val remaining = bytes.size - body
            // Streaming writers leave a placeholder size behind, sometimes 0xFFFFFFFF (negative
            // as a signed Int). Either way the chunk is however many bytes actually landed.
            val declared = intLe(bytes, pos + 4)
            val size = if (declared < 0 || declared > remaining) remaining else declared

            when {
                tagIs(bytes, pos, "fmt ") -> {
                    if (size < FMT_BODY) return null
                    val format = shortLe(bytes, body)
                    channels = shortLe(bytes, body + 2)
                    sampleRate = intLe(bytes, body + 4)
                    val bits = shortLe(bytes, body + 14)
                    if (format != PCM_FORMAT || bits != 16 || channels < 1 || sampleRate <= 0) return null
                }
                tagIs(bytes, pos, "data") -> {
                    if (channels < 1) return null
                    return TtsAudioResult(toMono(bytes, body, size, channels), sampleRate)
                }
            }
            pos = body + size + (size and 1) // chunk bodies pad to an even length
        }
        return null
    }

    /** Interleaved 16-bit LE frames to mono floats, averaging channels. */
    private fun toMono(bytes: ByteArray, offset: Int, size: Int, channels: Int): FloatArray {
        val frames = size / (2 * channels)
        val out = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (ch in 0 until channels) {
                sum += shortLeSigned(bytes, offset + (frame * channels + ch) * 2) / 32768f
            }
            out[frame] = sum / channels
        }
        return out
    }

    private fun tagIs(bytes: ByteArray, offset: Int, tag: String): Boolean =
        offset + 4 <= bytes.size && (0 until 4).all { bytes[offset + it].toInt().toChar() == tag[it] }

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        (0 until 4).fold(0) { acc, i -> acc or ((bytes[offset + i].toInt() and 0xFF) shl (i * 8)) }

    private fun shortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun shortLeSigned(bytes: ByteArray, offset: Int): Int = shortLe(bytes, offset).toShort().toInt()

    private const val MIN_HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val FMT_BODY = 16
    private const val PCM_FORMAT = 1
}
