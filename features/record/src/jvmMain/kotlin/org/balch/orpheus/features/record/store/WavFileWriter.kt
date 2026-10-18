package org.balch.orpheus.features.record.store

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Streams stereo 32-bit float PCM to a WAV file; [close] patches the RIFF and data sizes.
 * [write] and [close] share one lock, so they are safe from different threads; a write after close is a no-op.
 */
class WavFileWriter(file: File, sampleRate: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private val channel: FileChannel = raf.channel
    private val chunk = ByteBuffer.allocateDirect(CHUNK_FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
    private var dataBytes = 0L
    private var closed = false

    init {
        raf.setLength(0)
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(0).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16)
        header.putShort(FORMAT_IEEE_FLOAT).putShort(CHANNELS.toShort())
        header.putInt(sampleRate).putInt(sampleRate * BYTES_PER_FRAME)
        header.putShort(BYTES_PER_FRAME.toShort()).putShort(32)
        header.put("data".toByteArray()).putInt(0)
        header.flip()
        channel.write(header)
    }

    @Synchronized
    fun write(interleaved: FloatArray, frames: Int) {
        if (closed) return
        var done = 0
        while (done < frames) {
            val n = minOf(CHUNK_FRAMES, frames - done)
            chunk.clear()
            chunk.asFloatBuffer().put(interleaved, done * CHANNELS, n * CHANNELS)
            chunk.limit(n * BYTES_PER_FRAME)
            while (chunk.hasRemaining()) channel.write(chunk)
            done += n
        }
        dataBytes += frames.toLong() * BYTES_PER_FRAME
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            patchInt(4, (HEADER_BYTES - 8 + dataBytes).toInt())
            patchInt(40, dataBytes.toInt())
        } finally {
            raf.close()
        }
    }

    private fun patchInt(position: Long, value: Int) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        b.flip()
        channel.write(b, position)
    }

    private companion object {
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * 4
        const val HEADER_BYTES = 44
        const val CHUNK_FRAMES = 4096
        const val FORMAT_IEEE_FLOAT: Short = 3
    }
}
