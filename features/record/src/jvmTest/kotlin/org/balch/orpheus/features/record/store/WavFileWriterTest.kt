package org.balch.orpheus.features.record.store

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class WavFileWriterTest {

    @Test
    fun wavHeaderIsFloatStereo() {
        val file = File.createTempFile("wav-header", ".wav")
        try {
            val frames = 480
            val samples = FloatArray(frames * 2) { it / 1000f }
            WavFileWriter(file, 48000).apply {
                write(samples, frames)
                close()
            }

            val bytes = file.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            assertEquals(3, buf.getShort(20).toInt())
            assertEquals(2, buf.getShort(22).toInt())
            assertEquals(48000, buf.getInt(24))
            assertEquals(32, buf.getShort(34).toInt())
            assertEquals(frames * 8, buf.getInt(40))
            assertEquals(bytes.size - 8, buf.getInt(4))
            assertEquals(44 + frames * 8, bytes.size)
            assertEquals(samples[7], buf.getFloat(44 + 7 * 4))
        } finally {
            file.delete()
        }
    }

    @Test
    fun writesLargeChunksAcrossMultipleWrites() {
        val file = File.createTempFile("wav-chunks", ".wav")
        try {
            WavFileWriter(file, 48000).apply {
                write(FloatArray(9000 * 2) { 0.25f }, 9000)
                write(FloatArray(10 * 2) { -0.5f }, 10)
                close()
            }
            val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(9010 * 8, buf.getInt(40))
            assertEquals(0.25f, buf.getFloat(44 + 8999 * 8))
            assertEquals(-0.5f, buf.getFloat(44 + 9000 * 8))
        } finally {
            file.delete()
        }
    }
}
