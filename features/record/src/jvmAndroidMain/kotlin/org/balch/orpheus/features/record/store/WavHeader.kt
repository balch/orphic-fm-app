package org.balch.orpheus.features.record.store

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The fields of a take's 44-byte WAV header that playback and duration need. */
data class WavHeader(val sampleRate: Int, val channels: Int, val dataOffset: Long, val frames: Long) {
    companion object {
        private const val HEADER_BYTES = 44
        private const val BYTES_PER_SAMPLE = 4
        private const val FORMAT_IEEE_FLOAT = 3
        private const val BITS_PER_SAMPLE = 32

        /** Null when [file] is shorter than a header, carries no sample rate, or is not 32-bit float PCM. */
        fun read(file: File): WavHeader? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length < HEADER_BYTES) return null
                val bytes = ByteArray(HEADER_BYTES).also { raf.readFully(it) }
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                if (buf.getShort(20).toInt() != FORMAT_IEEE_FLOAT || buf.getShort(34).toInt() != BITS_PER_SAMPLE) return null
                val channels = buf.getShort(22).toInt()
                val rate = buf.getInt(24)
                if (rate <= 0 || channels <= 0) return null
                val frameBytes = channels * BYTES_PER_SAMPLE
                WavHeader(rate, channels, HEADER_BYTES.toLong(), (length - HEADER_BYTES) / frameBytes)
            }
        }.getOrNull()
    }
}
