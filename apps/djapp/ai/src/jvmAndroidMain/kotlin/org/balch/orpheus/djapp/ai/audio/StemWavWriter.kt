package org.balch.orpheus.djapp.ai.audio

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.balch.orpheus.core.audio.dsp.StemLayout

/**
 * One stem as a stereo IEEE-float32 WAV: a 44-byte header whose sizes are patched on [close].
 * Not features/record's WavFileWriter: depending on :features:record would merge its FeatureScope contributions into the DJ graph.
 */
internal class StemWavWriter(file: File, sampleRate: Int) : Closeable {
    private val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    private val buf = ByteBuffer.allocate(BUFFER_FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
    private var dataBytes = 0L
    private var closed = false

    init {
        val h = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(0).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16)
            .putShort(FORMAT_IEEE_FLOAT).putShort(CHANNELS.toShort()).putInt(sampleRate)
            .putInt(sampleRate * BYTES_PER_FRAME).putShort(BYTES_PER_FRAME.toShort()).putShort(32)
        h.put("data".toByteArray()).putInt(0)
        h.flip()
        // A relative write, so the samples follow at the channel's position.
        try {
            while (h.hasRemaining()) channel.write(h)
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }

    /** Appends [stem]'s L and R from [count] frames interleaved per [StemLayout]. */
    fun write(frames: FloatArray, count: Int, stem: Int) {
        var o = 2 * stem
        for (i in 0 until count) {
            if (buf.remaining() < BYTES_PER_FRAME) flush()
            buf.putFloat(frames[o]).putFloat(frames[o + 1])
            o += StemLayout.FLOATS_PER_FRAME
        }
        dataBytes += count.toLong() * BYTES_PER_FRAME
    }

    override fun close() {
        if (closed) return
        closed = true
        channel.use {
            flush()
            val sizes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            patch(sizes.putInt(0, (HEADER_BYTES - 8 + dataBytes).toInt()), 4)
            patch(sizes.putInt(0, dataBytes.toInt()), 40)
        }
    }

    private fun flush() {
        buf.flip()
        while (buf.hasRemaining()) channel.write(buf)
        buf.clear()
    }

    private fun patch(b: ByteBuffer, at: Long) {
        b.rewind()
        var pos = at
        while (b.hasRemaining()) pos += channel.write(b, pos)
    }

    private companion object {
        const val HEADER_BYTES = 44
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 8
        const val BUFFER_FRAMES = 8192
        const val FORMAT_IEEE_FLOAT: Short = 3
    }
}
