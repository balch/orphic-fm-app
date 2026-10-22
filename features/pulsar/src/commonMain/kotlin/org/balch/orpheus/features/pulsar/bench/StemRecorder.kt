package org.balch.orpheus.features.pulsar.bench

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.audio.dsp.NoStemTap
import org.balch.orpheus.core.audio.dsp.StemLayout
import org.balch.orpheus.core.audio.dsp.StemTap
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * Receives drained frames, interleaved per StemLayout, on the recorder's io dispatcher. [frames] is reused after the call.
 * Frames may already have been written when `record` returns ok=false, so a sink that persists must clean up on failure.
 */
fun interface StemSink {
    fun write(frames: FloatArray, count: Int)
}

/** How a [StemRecorder.record] went; [message] is meant for the user. */
data class StemRecording(
    val ok: Boolean,
    val message: String,
    val frames: Int = 0,
    val droppedFrames: Long = 0,
    val vibe: String = "",
    val sectionAtStart: String = "",
    /** Per track 0..7: the vibe's engine display name, "edm / space" when the two slots differ. */
    val trackLabels: List<String> = emptyList(),
)

/**
 * Records the next N seconds of the stem ring into a [StemSink], one capture at a time across all callers.
 * The taps are held on for the whole capture and the ring exists only while it runs. The native calls block
 * briefly, so the capture runs on [dispatchers]' io dispatcher.
 */
class StemRecorder(
    private val controller: MixBenchController,
    private val pulsarProvider: () -> PulsarFeature,
    private val tap: StemTap,
    val sampleRate: Int,
    private val dispatchers: DispatcherProvider,
    private val pollMs: Long = 20,
) {
    private val busy = Mutex()
    @Volatile private var closed = false

    /** False where the engine has no stem ring. */
    val supported: Boolean get() = tap !== NoStemTap

    /**
     * Ends a running capture with ok=false, waits until it has disabled the ring, and refuses every later one.
     * App exit calls it before the controller stops and the engine closes. Call it once.
     */
    suspend fun close() {
        closed = true
        // Never unlocked: the running capture sees [closed] at its next poll and releases the lock to us.
        busy.lock()
    }

    /** The message [record] would refuse with right now, or null; lets a caller skip its setup. [record] still decides. */
    fun refusal(): String? = when {
        closed -> CLOSED
        busy.isLocked -> BUSY
        !supported -> UNSUPPORTED
        !pulsarProvider().stateFlow.value.playing -> STOPPED
        else -> null
    }

    suspend fun record(
        seconds: Float,
        sink: StemSink,
        progress: suspend (done: Float) -> Unit = {},
    ): StemRecording {
        if (closed) return fail(CLOSED)
        if (!busy.tryLock()) return fail(if (closed) CLOSED else BUSY)
        try {
            if (!supported) return fail(UNSUPPORTED)
            val pulsar = pulsarProvider()
            if (!pulsar.stateFlow.value.playing) return fail(STOPPED)
            if (controller.vibeLoadInFlight()) return fail(LOADING)
            val total = (seconds * sampleRate).roundToInt()
            require(total > 0) { "seconds must be positive: $seconds" }
            return controller.holdingTaps {
                withContext(dispatchers.io) { capture(pulsar, seconds, total, sink, progress) }
            }
        } finally {
            busy.unlock()
        }
    }

    private suspend fun capture(
        pulsar: PulsarFeature,
        seconds: Float,
        total: Int,
        sink: StemSink,
        progress: suspend (Float) -> Unit,
    ): StemRecording {
        // The count first: a load landing before the vibe read then aborts instead of mislabelling.
        val loads = pulsar.vibeLoadCount.value
        val vibe = pulsar.vibeFlow.value
        val section = vibe.arrangement?.sections?.getOrNull(pulsar.arrangementStateFlow.value.sectionIndex)?.name ?: ""
        fun result(ok: Boolean, message: String, frames: Int = 0, dropped: Long = 0) =
            StemRecording(ok, message, frames, dropped, vibe.name, section, labels(vibe))

        // The ring records only while the bench meters, so a stopped controller would never count down.
        if (!controller.isMetering) return result(false, NOT_METERING)
        if (!tap.setEnabled(true)) return result(false, STALE)
        try {
            val buf = FloatArray(DRAIN_FRAMES * StemLayout.FLOATS_PER_FRAME)
            // A re-arm keeps undrained frames, so start from an empty ring and this enable's drop count.
            while (tap.drain(buf) > 0) Unit
            val droppedBase = tap.dropped()
            if (!tap.arm(total)) return result(false, ARM_FAILED)

            var recorded = 0
            var elapsedMs = 0L
            var lastProgressMs = 0L
            var lastRemaining = total
            var stalledMs = 0L
            while (true) {
                if (closed) return result(false, CLOSED, recorded)
                if (pulsar.vibeLoadCount.value != loads) return result(false, VIBE_CHANGED, recorded)
                // The bench still renders while stopped, so a capture would go on recording silence.
                if (!pulsar.stateFlow.value.playing) return result(false, PLAYBACK_STOPPED, recorded)
                // Read before draining: once it is 0 nothing more arrives, so an empty drain ends the capture.
                val remaining = tap.remaining()
                do {
                    val n = tap.drain(buf)
                    if (n > 0) {
                        sink.write(buf, n)
                        recorded += n
                    }
                } while (n > 0)
                if (remaining == 0) break

                stalledMs = if (remaining == lastRemaining) stalledMs + pollMs else 0
                lastRemaining = remaining
                if (stalledMs >= STALL_MS) return result(false, NO_AUDIO, recorded)
                if (elapsedMs - lastProgressMs >= PROGRESS_MS) {
                    lastProgressMs = elapsedMs
                    progress((total - remaining).toFloat() / total)
                }
                delay(pollMs)
                elapsedMs += pollMs
            }
            // A load or a stop during the last poll still spoils the stems.
            if (pulsar.vibeLoadCount.value != loads) return result(false, VIBE_CHANGED, recorded)
            if (!pulsar.stateFlow.value.playing) return result(false, PLAYBACK_STOPPED, recorded)
            val dropped = tap.dropped() - droppedBase
            // Every armed frame is recorded or dropped; a closed engine reads remaining 0 with frames missing.
            if (recorded + dropped != total.toLong()) return result(false, CUT_SHORT, recorded)
            val message = if (dropped > 0) {
                "recorded ${seconds.trimmed()} s; $dropped frames dropped (the app fell behind reading the audio)"
            } else {
                "recorded ${seconds.trimmed()} s"
            }
            return result(true, message, recorded, dropped)
        } finally {
            tap.setEnabled(false)
        }
    }

    private fun fail(message: String) = StemRecording(ok = false, message = message)

    private fun labels(vibe: Vibe): List<String> = vibe.tracks.map { t ->
        val edm = t.engineEdm.engineId
        val space = t.engineSpace.engineId
        if (edm == space) edm.displayName else "${edm.displayName} / ${space.displayName}"
    }

    private companion object {
        const val DRAIN_FRAMES = 4096
        const val PROGRESS_MS = 500L
        /** No frame counted down for this long means the engine isn't rendering. */
        const val STALL_MS = 2_000L

        const val BUSY = "a capture is already running"
        const val CLOSED = "the app is closing"
        const val UNSUPPORTED = "raw audio isn't available on this platform yet"
        const val STOPPED = "press play first (transport play)"
        const val LOADING = "the vibe just changed; try again in a moment"
        const val NOT_METERING = "the mix bench isn't running, so no stems can be recorded"
        const val STALE = "the native audio library has no stem ring; rebuild it"
        const val ARM_FAILED = "the stem ring could not start recording"
        const val NO_AUDIO = "no audio reached the recorder; is the audio engine running?"
        const val VIBE_CHANGED = "the vibe changed during the capture"
        const val PLAYBACK_STOPPED = "playback stopped during the capture"
        const val CUT_SHORT = "the capture was cut short; the audio engine stopped before it finished"

        /** 0.5 -> "0.5", 10.0 -> "10", 2.25 -> "2.25". */
        fun Float.trimmed(): String {
            val c = (this * 100).roundToInt()
            val frac = c % 100
            return when {
                frac == 0 -> "${c / 100}"
                frac % 10 == 0 -> "${c / 100}.${frac / 10}"
                else -> "${c / 100}.${frac.toString().padStart(2, '0')}"
            }
        }
    }
}
