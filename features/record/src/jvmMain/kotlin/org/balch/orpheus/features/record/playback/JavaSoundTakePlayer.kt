package org.balch.orpheus.features.record.playback

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.features.record.store.TakeInfo
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine

/**
 * Plays a take through a Java Sound [Clip], bypassing the synth engine. Every call hops onto one
 * IO thread, so the clip is only ever touched from there.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@OptIn(ExperimentalCoroutinesApi::class)
class JavaSoundTakePlayer : TakePlayer {

    private val log = logging("JavaSoundTakePlayer")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var clip: Clip? = null
    private var loadedId: String? = null
    private var ticker: Job? = null

    override fun play(take: TakeInfo) {
        scope.launch {
            if (loadedId != take.takeId && !load(take)) return@launch
            val c = clip ?: return@launch
            c.start()
            mutableState.value = mutableState.value.copy(playing = true)
            startTicker(c)
        }
    }

    override fun pause() {
        scope.launch {
            ticker?.cancel()
            clip?.stop()
            mutableState.value = mutableState.value.copy(playing = false)
        }
    }

    override fun seek(fraction: Float) {
        scope.launch {
            val c = clip ?: return@launch
            val last = (c.frameLength - 1).coerceAtLeast(0)
            c.framePosition = (fraction.coerceIn(0f, 1f) * c.frameLength).toInt().coerceAtMost(last)
            mutableState.value = mutableState.value.copy(positionSec = positionSec(c))
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
            val c = openClip(File(take.audioPath))
            clip = c
            loadedId = take.takeId
            mutableState.value = PlaybackState(take.takeId, false, 0f, c.microsecondLength / MICROS)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error { "Could not play ${take.takeId}: ${e.message}" }
            mutableState.value = PlaybackState()
            false
        }
    }

    /** Many mixers refuse float PCM, so fall back to 16-bit when the file's format is unsupported. */
    private fun openClip(file: File): Clip {
        AudioSystem.getAudioInputStream(file).use { source ->
            val playable = if (AudioSystem.isLineSupported(DataLine.Info(Clip::class.java, source.format))) {
                source
            } else {
                AudioSystem.getAudioInputStream(pcm16(source.format), source)
            }
            playable.use { stream ->
                val c = AudioSystem.getClip()
                try {
                    c.open(stream)
                } catch (e: Throwable) {
                    c.close()
                    throw e
                }
                return c
            }
        }
    }

    private fun pcm16(from: AudioFormat) = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED, from.sampleRate, 16, from.channels,
        from.channels * 2, from.sampleRate, false,
    )

    private fun startTicker(c: Clip) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val atEnd = !c.isRunning || c.framePosition >= c.frameLength
                if (atEnd) {
                    c.stop()
                    c.framePosition = 0
                    mutableState.value = mutableState.value.copy(playing = false, positionSec = 0f)
                    return@launch
                }
                mutableState.value = mutableState.value.copy(positionSec = positionSec(c))
            }
        }
    }

    private fun positionSec(c: Clip) = c.microsecondPosition / MICROS

    private fun release() {
        ticker?.cancel()
        ticker = null
        clip?.let { runCatching { it.stop() }; runCatching { it.close() } }
        clip = null
        loadedId = null
    }

    private companion object {
        const val TICK_MS = 50L
        const val MICROS = 1_000_000f
    }
}
