package org.balch.orpheus.features.record

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.balch.orpheus.core.audio.dsp.OutputCapture
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.features.record.store.TakeStore
import org.balch.orpheus.features.record.store.TakeWriter
import org.balch.orpheus.features.record.telemetry.TakeEventRecorder
import org.balch.orpheus.features.record.telemetry.TakeSnapshot
import org.balch.orpheus.features.record.telemetry.TakeTelemetry
import org.balch.orpheus.features.record.telemetry.snapshotOf
import org.balch.orpheus.features.record.telemetry.soundingMidiNote
import kotlin.time.Clock

sealed interface RecordState {
    data object Idle : RecordState
    data class Recording(val takeId: String, val elapsedSec: Float) : RecordState
}

/** Seam over PresetLoader and PresetsRepository so the session can be tested without an engine. */
interface PresetSnapshotter {
    fun capture(name: String): SynthPreset
    suspend fun save(preset: SynthPreset)
}

/**
 * Runs one take at a time: opens a take, drains the engine capture to the writer, records
 * telemetry, and finishes or aborts. Never touches playback; the caller stops it first.
 */
@SingleIn(AppScope::class)
class RecordSession internal constructor(
    private val capture: OutputCapture,
    private val store: TakeStore,
    private val presets: PresetSnapshotter,
    private val synthController: SynthController,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        capture: OutputCapture,
        store: TakeStore,
        presets: PresetSnapshotter,
        synthController: SynthController,
        dispatcherProvider: DispatcherProvider,
    ) : this(
        capture, store, presets, synthController, Clock.System,
        CoroutineScope(SupervisorJob() + dispatcherProvider.io),
        dispatcherProvider.io,
    )

    private class ActiveTake(
        val takeId: String,
        val writer: TakeWriter,
        val startPreset: SynthPreset,
        val startSnapshot: TakeSnapshot,
        val droppedBaseline: Long,
        val buffer: FloatArray = FloatArray(DRAIN_FRAMES * 2),
    ) {
        var framesWritten = 0L
        var job: Job? = null
    }

    private val log = logging("RecordSession")
    private val recorder = TakeEventRecorder { nowMs() }
    private val mutex = Mutex()
    private var active: ActiveTake? = null

    private val _state = MutableStateFlow<RecordState>(RecordState.Idle)
    val state: StateFlow<RecordState> = _state.asStateFlow()

    private val _takesChanged = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val takesChanged: SharedFlow<String> = _takesChanged.asSharedFlow()

    init {
        collectSafely(synthController.onPulseStart) { voice ->
            if (recorder.isActive && voice in 0 until VOICE_COUNT) {
                recorder.gateOn(voice, pitchOf(voice))
            }
        }
        collectSafely(synthController.onPulseEnd) { voice -> recorder.gateOff(voice) }
        collectSafely(synthController.onControlChange) { recorder.control(it.controlId, it.value) }
        collectSafely(synthController.onMidiNote) { recorder.midiNote(it) }
    }

    /**
     * Unconfined subscribes now (replays arrive while inactive) and handles each event on emit.
     * A throwing handler is logged so it cannot end capture for the rest of the app's life.
     */
    private fun <T> collectSafely(flow: Flow<T>, handler: (T) -> Unit) {
        scope.launch(Dispatchers.Unconfined) {
            flow.collect { value ->
                try {
                    handler(value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.error { "Recorder event dropped: ${e.message}" }
                }
            }
        }
    }

    suspend fun start() {
        mutex.withLock {
            if (active != null) return
            withContext(ioDispatcher) { startLocked() }
        }
    }

    /** Opens the take before saving the preset, so an existing take folder is never touched. */
    private suspend fun startLocked() {
        var writer: TakeWriter? = null
        var captureStarted = false
        try {
            val baseId = takeIdFor(clock.now().toLocalDateTime(TimeZone.currentSystemDefault()))
            val (takeId, opened) = openTake(baseId)
            writer = opened
            val preset = presets.capture("rec-$takeId")
            presets.save(preset)
            val baseline = capture.droppedFrames()
            capture.start()
            captureStarted = true
            recorder.start()

            val take = ActiveTake(takeId, writer, preset, snapshotOf(preset), baseline)
            active = take
            _state.value = RecordState.Recording(takeId, 0f)
            take.job = scope.launch { runDrainLoop(take) }
        } catch (e: Throwable) {
            if (e !is CancellationException) log.error { "Could not start take: ${e.message}" }
            if (captureStarted) runCatching { capture.stop() }
            runCatching { recorder.stop() }
            runCatching { writer?.abort() }
            active = null
            _state.value = RecordState.Idle
            if (e is CancellationException) throw e
        }
    }

    /** The store refuses an existing folder, so a same-second restart retries as `-2`, `-3`, ... */
    private fun openTake(baseId: String): Pair<String, TakeWriter> {
        var failure: IllegalStateException? = null
        for (attempt in 1..MAX_OPEN_ATTEMPTS) {
            val id = if (attempt == 1) baseId else "$baseId-$attempt"
            try {
                return id to store.open(id, capture.sampleRate)
            } catch (e: IllegalStateException) {
                failure = e
            }
        }
        throw failure!!
    }

    suspend fun stop() {
        val take = mutex.withLock { active } ?: return
        take.job?.cancelAndJoin()
        // Only the take seen above: a cap-finished take must not make this finish a newer one.
        mutex.withLock { if (active === take) finishLocked(take) }
    }

    private suspend fun runDrainLoop(take: ActiveTake) {
        val capFrames = MAX_SECONDS.toLong() * capture.sampleRate
        while (true) {
            delay(DRAIN_INTERVAL_MS)
            try {
                drainAll(take)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                mutex.withLock { if (active === take) abortLocked(take, e) }
                return
            }
            _state.value = RecordState.Recording(take.takeId, take.framesWritten.toFloat() / capture.sampleRate)
            if (take.framesWritten >= capFrames) {
                mutex.withLock { if (active === take) finishLocked(take) }
                return
            }
        }
    }

    /** Drains until the capture runs dry; it may return nothing when the engine is stopped. */
    private fun drainAll(take: ActiveTake) {
        while (true) {
            val frames = capture.drain(take.buffer)
            if (frames <= 0) return
            take.writer.writeAudio(take.buffer, frames)
            take.framesWritten += frames
        }
    }

    /**
     * Once capture has stopped the audio is complete, so nothing here aborts: telemetry
     * failures fall back to best-effort values and only a failed finish deletes the take.
     */
    private suspend fun finishLocked(take: ActiveTake) {
        withContext(NonCancellable + ioDispatcher) {
            runCatching { capture.stop() }.onFailure { log.error { "Capture stop failed: ${it.message}" } }
            try {
                drainAll(take)
            } catch (e: Throwable) {
                log.error { "Final drain failed for ${take.takeId}: ${e.message}" }
            }
            val events = runCatching { recorder.stop() }.getOrElse {
                log.error { "Recorder stop failed: ${it.message}" }
                emptyList()
            }
            val endSnapshot = try {
                snapshotOf(presets.capture("rec-${take.takeId}"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.error { "End snapshot failed, using start snapshot: ${e.message}" }
                take.startSnapshot
            }
            try {
                val telemetry = TakeTelemetry(
                    takeId = take.takeId,
                    sampleRate = capture.sampleRate,
                    durationSec = take.framesWritten.toFloat() / capture.sampleRate,
                    droppedFrames = capture.droppedFrames() - take.droppedBaseline,
                    start = take.startSnapshot,
                    end = endSnapshot,
                    events = events,
                )
                take.writer.finish(take.startPreset, telemetry)
                active = null
                _state.value = RecordState.Idle
                _takesChanged.tryEmit(take.takeId)
            } catch (e: Throwable) {
                abortCleanup(take, e)
            }
        }
    }

    private suspend fun abortLocked(take: ActiveTake, cause: Throwable) {
        withContext(NonCancellable + ioDispatcher) { abortCleanup(take, cause) }
    }

    private fun abortCleanup(take: ActiveTake, cause: Throwable) {
        log.error { "Take ${take.takeId} aborted: ${cause.message}" }
        runCatching { capture.stop() }
        runCatching { recorder.stop() }
        runCatching { take.writer.abort() }
        active = null
        _state.value = RecordState.Idle
    }

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    /** Live tune and quad pitch from the engine, falling back to the port defaults. */
    private fun pitchOf(voice: Int): Float {
        val tune = synthController.getPluginControl(VoiceSymbol.tune(voice).controlId)?.asFloat() ?: 0.5f
        val quadPitch = synthController.getPluginControl(VoiceSymbol.quadPitch(voice / 4).controlId)
            ?.asFloat() ?: 0.5f
        return soundingMidiNote(voice, tune, quadPitch)
    }

    companion object {
        const val MAX_SECONDS = 300
        const val DRAIN_INTERVAL_MS = 40L
        private const val DRAIN_FRAMES = 4096
        private const val VOICE_COUNT = 12
        private const val MAX_OPEN_ATTEMPTS = 9
    }
}
