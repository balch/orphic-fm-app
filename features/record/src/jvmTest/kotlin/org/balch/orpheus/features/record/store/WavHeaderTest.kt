package org.balch.orpheus.features.record.store

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WavHeaderTest {

    private fun writeTake(file: File, frames: Int) {
        WavFileWriter(file, 44100).apply {
            write(FloatArray(frames * 2) { 0.1f }, frames)
            close()
        }
    }

    @Test
    fun readsFieldsOfAWrittenTake() {
        val file = File.createTempFile("wav-header-read", ".wav")
        try {
            writeTake(file, 1000)
            val header = WavHeader.read(file)
            assertEquals(WavHeader(44100, 2, 44L, 1000L), header)
        } finally {
            file.delete()
        }
    }

    @Test
    fun clampsFramesToWhatTheFileHolds() {
        val file = File.createTempFile("wav-header-trunc", ".wav")
        try {
            writeTake(file, 1000)
            RandomAccessFile(file, "rw").use { it.setLength(44L + 8 * 10 + 3) }
            assertEquals(10L, WavHeader.read(file)?.frames)
        } finally {
            file.delete()
        }
    }

    @Test
    fun returnsNullForAFileShorterThanAHeader() {
        val file = File.createTempFile("wav-header-short", ".wav")
        try {
            file.writeBytes(ByteArray(20))
            assertNull(WavHeader.read(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun returnsNullForNonFloatOrNon32BitFormats() {
        val file = File.createTempFile("wav-header-pcm16", ".wav")
        try {
            writeTake(file, 100)
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(20)
                raf.write(byteArrayOf(1, 0)) // PCM format tag
                raf.seek(34)
                raf.write(byteArrayOf(16, 0)) // 16 bits per sample
            }
            assertNull(WavHeader.read(file))
        } finally {
            file.delete()
        }
    }
}
