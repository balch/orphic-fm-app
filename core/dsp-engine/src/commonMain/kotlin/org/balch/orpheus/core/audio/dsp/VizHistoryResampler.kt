package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.core.plugin.viz.VizHistory
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

/**
 * Writes one poll's viz reads into [VizHistory] rings at a fixed [VizHistory.RATE] ticks a second.
 * The engine writes a sample per audio callback, so its rate follows the device's buffer: about 94
 * a second at 512 frames on desktop, 500 at an Android phone's 96-frame bursts. Resampled, a scroll
 * keeps one speed everywhere.
 *
 * Blocks map onto ticks along a line that bends only at poll ends, at the block rate measured over
 * the last couple of seconds. A tick keeps the sample furthest from 0 among its blocks; a tick that
 * no block reaches (blocks slower than ticks) holds the one before.
 */
internal class VizHistoryResampler(channels: IntRange, channelCount: Int) {
    val rings = Array(channelCount) { if (it in channels) FloatArray(VizHistory.RING_SIZE) else EMPTY }

    /** Each channel's tick count just past its newest tick. */
    val ends = IntArray(channelCount)

    private var anchored = false
    private var anchorBlock = 0
    private var anchorTick = 0.0
    private var ticksPerBlock = 0.0
    private var lastSeconds = 0.0
    private var blocksSum = 0.0
    private var secondsSum = 0.0

    // The newest block any channel read this poll.
    private var pollEnd = 0
    private var pollRead = false

    /** Maps [channel]'s [count] new samples in [src], ending just before engine block [end], onto ticks. */
    fun write(channel: Int, src: FloatArray, count: Int, end: Int) {
        if (!pollRead || end - pollEnd > 0) pollEnd = end
        pollRead = true
        val ring = rings[channel]
        // Until two polls measure the block rate there is no line to map along.
        if (ring.isEmpty() || ticksPerBlock == 0.0) return
        val mask = ring.size - 1
        var last = ends[channel] - 1
        for (i in 0 until count) {
            val since = end - count + i - anchorBlock
            // An engine restart counting from 0 again: [endPoll] re-anchors.
            if (since < -RESTART_BLOCKS) continue
            val tick = maxOf(floor(anchorTick + since * ticksPerBlock).toInt(), last)
            val v = src[i]
            if (tick == last) {
                if (abs(v) > abs(ring[tick and mask])) ring[tick and mask] = v
                continue
            }
            // A stalled poll's long gap clears rather than smearing one sample across it.
            val hold = if (tick - last <= HOLD_TICKS) ring[last and mask] else 0f
            for (t in maxOf(last + 1, tick - mask) until tick) ring[t and mask] = hold
            ring[tick and mask] = v
            last = tick
        }
        ends[channel] = last + 1
    }

    /** Closes a poll taken at [seconds]: measures the block rate and moves the line's anchor to its end. */
    fun endPoll(seconds: Double) {
        if (!pollRead) return
        pollRead = false
        val blocks = pollEnd - anchorBlock
        // Every channel's blocks come from the same callbacks, so only a restart ends a poll behind the last.
        if (!anchored || blocks < 0) {
            // The first poll, or a restarted engine: ticks carry on from the newest.
            anchored = true
            anchorBlock = pollEnd
            anchorTick = maxOf(anchorTick, (ends.maxOrNull() ?: 0).toDouble())
            lastSeconds = seconds
            return
        }
        val gap = seconds - lastSeconds
        val mapped = ticksPerBlock
        if (gap > 0.0 && blocks > 0) {
            val decay = exp(-gap / RATE_WINDOW_SECONDS)
            blocksSum = blocksSum * decay + blocks
            secondsSum = secondsSum * decay + gap
            ticksPerBlock = VizHistory.RATE * secondsSum / blocksSum
        }
        // The line moves on at the slope this poll's blocks were mapped with, so it never jumps.
        anchorTick += blocks * (if (mapped == 0.0) ticksPerBlock else mapped)
        anchorBlock = pollEnd
        lastSeconds = seconds
    }

    private companion object {
        val EMPTY = FloatArray(0)

        /** Blocks before the anchor that still count as this run's: one engine ring. */
        const val RESTART_BLOCKS = 480

        /** The longest gap a held tick bridges, past what 44.1 kHz at 882 frames (50 blocks a second) leaves. */
        const val HOLD_TICKS = 8

        /** How long the measured block rate remembers, so a reopened stream's new buffer size shows quickly. */
        const val RATE_WINDOW_SECONDS = 2.0
    }
}
