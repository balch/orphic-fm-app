package org.balch.orpheus.features.record.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.features.record.store.TakeInfo
import org.balch.orpheus.features.record.store.WavHeader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays a take through a streaming [AudioTrack], bypassing the synth engine. Every call hops onto
 * one IO thread, so the track, file and counters are only ever touched from there.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AudioTrackTakePlayer : TakePlayer {

    private val log = logging("AudioTrackTakePlayer")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var track: AudioTrack? = null
    private var file: RandomAccessFile? = null
    private var header: WavHeader? = null
    private var loadedId: String? = null
    private var ticker: Job? = null
    private var writer: Job? = null
    private var writerDone = false

    /** Next frame to read from the file; advances only by frames the track accepted. */
    private var readFrame = 0L

    /** Frame the current write segment began at, and the track's head position at that moment. */
    private var segmentStart = 0L
    private var headAtSegmentStart = 0L

    override fun play(take: TakeInfo) {
        scope.launch {
            if (loadedId != take.takeId && !load(take)) return@launch
            val t = track ?: return@launch
            t.play()
            mutableState.value = mutableState.value.copy(playing = true)
            startWriter()
            startTicker()
        }
    }

    override fun pause() {
        scope.launch {
            ticker?.cancel()
            writer?.cancel()
            runCatching { track?.pause() }
            mutableState.value = mutableState.value.copy(playing = false)
        }
    }

    override fun seek(fraction: Float) {
        scope.launch {
            val t = track ?: return@launch
            val h = header ?: return@launch
            val wasPlaying = mutableState.value.playing
            val last = (h.frames - 1).coerceAtLeast(0)
            val target = (fraction.coerceIn(0f, 1f) * h.frames).toLong().coerceAtMost(last)
            repositionTo(t, target)
            if (wasPlaying) {
                t.play()
                startWriter()
            }
            mutableState.value = mutableState.value.copy(positionSec = positionSec())
        }
    }

    override fun stop() {
        scope.launch {
            release()
            mutableState.value = PlaybackState()
        }
    }

    private fun load(take: TakeInfo): Boolean {
        release()
        return try {
            val wav = File(take.audioPath)
            val h = requireNotNull(WavHeader.read(wav)) { "Unreadable WAV header" }
            require(h.channels == CHANNELS) { "Expected stereo, found ${h.channels} channels" }
            track = buildTrack(h.sampleRate)
            file = RandomAccessFile(wav, "r")
            header = h
            loadedId = take.takeId
            readFrame = 0
            segmentStart = 0
            headAtSegmentStart = 0
            mutableState.value = PlaybackState(take.takeId, false, 0f, h.frames / h.sampleRate.toFloat())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error { "Could not play ${take.takeId}: ${e.message}" }
            release()
            mutableState.value = PlaybackState()
            false
        }
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes, CHUNK_FRAMES * BYTES_PER_FRAME * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Pauses and flushes [t] so [target] becomes the start of a fresh write segment. */
    private fun repositionTo(t: AudioTrack, target: Long) {
        writer?.cancel()
        t.pause()
        t.flush()
        readFrame = target
        segmentStart = target
        headAtSegmentStart = headPosition(t)
        writerDone = false
    }

    /**
     * Feeds the track from [readFrame] without blocking, so pause and seek calls still reach the
     * IO thread between writes.
     */
    private fun startWriter() {
        val t = track ?: return
        val h = header ?: return
        val f = file ?: return
        writer?.cancel()
        writerDone = readFrame >= h.frames
        writer = scope.launch {
            try {
                val bytes = ByteArray(CHUNK_FRAMES * BYTES_PER_FRAME)
                val floatView = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val floats = FloatArray(CHUNK_FRAMES * CHANNELS)
                while (isActive && readFrame < h.frames) {
                    val frames = minOf(CHUNK_FRAMES.toLong(), h.frames - readFrame).toInt()
                    f.seek(h.dataOffset + readFrame * BYTES_PER_FRAME)
                    f.readFully(bytes, 0, frames * BYTES_PER_FRAME)
                    floatView.clear()
                    floatView.get(floats, 0, frames * CHANNELS)
                    writeFully(t, floats, frames * CHANNELS) { readFrame += it / CHANNELS }
                }
                writerDone = readFrame >= h.frames
                // The mixer only pulls full periods, so pad with silence until the ticker sees the end.
                floats.fill(0f)
                while (isActive) writeFully(t, floats, floats.size) {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.error { "Playback failed for $loadedId: ${e.message}" }
                release()
                mutableState.value = PlaybackState()
            }
        }
    }

    /** Writes [total] floats without blocking, calling [onWritten] with each accepted count. */
    private suspend fun writeFully(t: AudioTrack, floats: FloatArray, total: Int, onWritten: (Int) -> Unit) {
        var offset = 0
        while (currentCoroutineContext().isActive && offset < total) {
            val written = t.write(floats, offset, total - offset, AudioTrack.WRITE_NON_BLOCKING)
            check(written >= 0) { "AudioTrack write failed: $written" }
            if (written == 0) {
                delay(WRITE_RETRY_MS)
            } else {
                offset += written
                onWritten(written)
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val t = track ?: return@launch
                val h = header ?: return@launch
                if (writerDone && playedFrames(t) >= h.frames) {
                    repositionTo(t, 0)
                    mutableState.value = mutableState.value.copy(playing = false, positionSec = 0f)
                    return@launch
                }
                mutableState.value = mutableState.value.copy(positionSec = positionSec())
            }
        }
    }

    private fun playedFrames(t: AudioTrack) = segmentStart + (headPosition(t) - headAtSegmentStart)

    private fun positionSec(): Float {
        val t = track ?: return 0f
        val h = header ?: return 0f
        return playedFrames(t).coerceIn(0L, h.frames) / h.sampleRate.toFloat()
    }

    /** The head position is an unsigned 32-bit counter exposed as an Int. */
    private fun headPosition(t: AudioTrack) = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    private fun release() {
        ticker?.cancel()
        ticker = null
        writer?.cancel()
        writer = null
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        file?.let { runCatching { it.close() } }
        track = null
        file = null
        header = null
        loadedId = null
        writerDone = false
    }

    private companion object {
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * 4
        const val CHUNK_FRAMES = 4096
        const val TICK_MS = 50L
        const val WRITE_RETRY_MS = 10L
    }
}
