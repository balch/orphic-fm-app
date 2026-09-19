package org.balch.orpheus.core.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android TTS engines hand back a WAV file, not raw PCM, and they are not all well behaved
 * about it. These cases are the ones seen in the wild.
 */
class WavDecoderTest {

    @Test
    fun `decodes 16-bit mono pcm`() {
        val wav = buildWav(sampleRate = 22050, channels = 1, samples = shortArrayOf(0, 16384, -16384, 32767))

        val result = assertNotNull(WavDecoder.decode(wav))

        assertEquals(22050, result.sampleRate)
        assertEquals(4, result.samples.size)
        assertEquals(0f, result.samples[0], 1e-4f)
        assertEquals(0.5f, result.samples[1], 1e-4f)
        assertEquals(-0.5f, result.samples[2], 1e-4f)
        assertTrue(result.samples[3] > 0.99f)
    }

    @Test
    fun `skips unknown chunks between fmt and data`() {
        val wav = buildWav(
            sampleRate = 24000,
            channels = 1,
            samples = shortArrayOf(16384, 16384),
            extraChunks = listOf("LIST" to ByteArray(10) { 7 }),
        )

        val result = assertNotNull(WavDecoder.decode(wav))

        assertEquals(24000, result.sampleRate)
        assertEquals(2, result.samples.size)
        assertEquals(0.5f, result.samples[0], 1e-4f)
    }

    @Test
    fun `downmixes stereo to mono`() {
        // Interleaved L/R: (1.0, 0.0) then (-0.5, -0.5) -> 0.5, -0.5
        val wav = buildWav(
            sampleRate = 16000,
            channels = 2,
            samples = shortArrayOf(32767, 0, -16384, -16384),
        )

        val result = assertNotNull(WavDecoder.decode(wav))

        assertEquals(2, result.samples.size)
        assertEquals(0.5f, result.samples[0], 1e-3f)
        assertEquals(-0.5f, result.samples[1], 1e-3f)
    }

    @Test
    fun `clamps a data chunk that overruns the file`() {
        // Streaming engines write a placeholder data size they never go back and fix.
        val wav = buildWav(sampleRate = 22050, channels = 1, samples = shortArrayOf(16384, -16384))
        val lying = wav.copyOf()
        // The data chunk is last: 2 samples = 4 payload bytes, so its size field sits 8 from the end.
        writeIntLe(lying, lying.size - 8, 0x7FFFFFFF)

        val result = assertNotNull(WavDecoder.decode(lying))

        assertEquals(2, result.samples.size)
        assertEquals(0.5f, result.samples[0], 1e-4f)
    }

    @Test
    fun `treats an unsigned placeholder data size as the rest of the file`() {
        // 0xFFFFFFFF reads back as -1; the clip is the remaining bytes, not silence.
        val wav = buildWav(sampleRate = 22050, channels = 1, samples = shortArrayOf(16384, -16384))
        val lying = wav.copyOf()
        writeIntLe(lying, lying.size - 8, -1)

        val result = assertNotNull(WavDecoder.decode(lying))

        assertEquals(2, result.samples.size)
        assertEquals(0.5f, result.samples[0], 1e-4f)
    }

    @Test
    fun `returns null for a non-riff file`() {
        assertNull(WavDecoder.decode(ByteArray(64) { 3 }))
    }

    @Test
    fun `returns null when the data chunk is missing`() {
        val wav = buildWav(sampleRate = 22050, channels = 1, samples = shortArrayOf(1, 2))
        // Truncate to the end of the fmt chunk: RIFF header (12) + fmt id/size (8) + fmt body (16)
        assertNull(WavDecoder.decode(wav.copyOf(36)))
    }

    @Test
    fun `returns null for 8-bit audio`() {
        val wav = buildWav(sampleRate = 22050, channels = 1, samples = shortArrayOf(1, 2), bitsPerSample = 8)

        assertNull(WavDecoder.decode(wav))
    }

    @Test
    fun `returns an empty result for a zero-length data chunk`() {
        val wav = buildWav(sampleRate = 22050, channels = 1, samples = shortArrayOf())

        val result = assertNotNull(WavDecoder.decode(wav))

        assertEquals(0, result.samples.size)
    }
}

// -- test WAV construction ------------------------------------------------------------

private fun buildWav(
    sampleRate: Int,
    channels: Int,
    samples: ShortArray,
    bitsPerSample: Int = 16,
    extraChunks: List<Pair<String, ByteArray>> = emptyList(),
): ByteArray {
    val dataBytes = ByteArray(samples.size * 2)
    samples.forEachIndexed { i, s ->
        dataBytes[i * 2] = (s.toInt() and 0xFF).toByte()
        dataBytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
    }

    val out = ArrayList<Byte>()
    fun ascii(s: String) = s.forEach { out.add(it.code.toByte()) }
    fun int32(v: Int) = repeat(4) { out.add(((v shr (it * 8)) and 0xFF).toByte()) }
    fun int16(v: Int) = repeat(2) { out.add(((v shr (it * 8)) and 0xFF).toByte()) }

    ascii("RIFF")
    int32(0) // patched below
    ascii("WAVE")

    ascii("fmt ")
    int32(16)
    int16(1)                                    // PCM
    int16(channels)
    int32(sampleRate)
    int32(sampleRate * channels * bitsPerSample / 8)
    int16(channels * bitsPerSample / 8)
    int16(bitsPerSample)

    extraChunks.forEach { (id, body) ->
        ascii(id)
        int32(body.size)
        body.forEach { out.add(it) }
    }

    ascii("data")
    int32(dataBytes.size)
    dataBytes.forEach { out.add(it) }

    val bytes = out.toByteArray()
    writeIntLe(bytes, 4, bytes.size - 8)
    return bytes
}

private fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
    repeat(4) { bytes[offset + it] = ((value shr (it * 8)) and 0xFF).toByte() }
}
