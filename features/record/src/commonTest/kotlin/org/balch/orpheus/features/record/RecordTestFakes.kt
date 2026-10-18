package org.balch.orpheus.features.record

import org.balch.orpheus.core.audio.dsp.OutputCapture
import org.balch.orpheus.core.presets.SynthPreset
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.features.record.playback.PlaybackState
import org.balch.orpheus.features.record.playback.TakePlayer
import org.balch.orpheus.features.record.store.TakeInfo
import org.balch.orpheus.features.record.store.TakeStore
import org.balch.orpheus.features.record.store.TakeWriter
import org.balch.orpheus.features.record.telemetry.TakeTelemetry

internal class FakeOutputCapture : OutputCapture {
    override var sampleRate = 48_000
    override var isCapturing = true

    /** When set, [start] moves the live rate to this, like a route change during the arm. */
    var rateAfterStart: Int? = null
    val chunks = ArrayDeque<Int>()
    var dropped = 0L
    var running = false
    var stopCount = 0
    var startCount = 0

    /** Queues [total] frames as full-size chunks. */
    fun queueFrames(total: Long) {
        var left = total
        while (left > 0) {
            val n = minOf(left, 4096L).toInt()
            chunks += n
            left -= n
        }
    }

    override fun start() {
        running = true
        startCount++
        rateAfterStart?.let { sampleRate = it }
    }
    override fun stop() { running = false; stopCount++ }
    override fun drain(into: FloatArray): Int {
        val frames = minOf(chunks.removeFirstOrNull() ?: return 0, into.size / 2)
        return frames
    }
    override fun droppedFrames() = dropped
}

internal class FakeTakeWriter : TakeWriter {
    var frames = 0
    var throwOnWrite = false
    var aborted = false
    var onAbort: () -> Unit = {}
    var finishedPreset: SynthPreset? = null
    var telemetry: TakeTelemetry? = null

    override fun writeAudio(interleaved: FloatArray, frames: Int) {
        if (throwOnWrite) error("disk full")
        this.frames += frames
    }
    override fun finish(preset: SynthPreset, telemetry: TakeTelemetry) {
        finishedPreset = preset
        this.telemetry = telemetry
    }
    override fun abort() { aborted = true; onAbort() }
}

/** Like the file store, an opened take shows up in [list] at once, before it is finished. */
internal class FakeTakeStore : TakeStore {
    val writer = FakeTakeWriter()
    val takes = mutableListOf<TakeInfo>()
    val deleted = mutableListOf<String>()
    var openedId: String? = null
    var throwOnOpen = false
    val existingIds = mutableSetOf<String>()
    val openAttempts = mutableListOf<String>()
    val openedRates = mutableListOf<Int>()

    override suspend fun list(): List<TakeInfo> = takes.toList()
    override suspend fun delete(takeId: String) {
        deleted += takeId
        takes.removeAll { it.takeId == takeId }
    }
    override fun open(takeId: String, sampleRate: Int): TakeWriter {
        openAttempts += takeId
        openedRates += sampleRate
        if (throwOnOpen || takeId in existingIds) error("take folder exists")
        openedId = takeId
        takes.add(0, TakeInfo(takeId, 0f, 0L, "/takes/$takeId/audio.wav"))
        writer.onAbort = { takes.removeAll { it.takeId == takeId } }
        return writer
    }
}

internal class FakePresetSnapshotter : PresetSnapshotter {
    val saved = mutableListOf<SynthPreset>()
    var throwOnSave = false
    var throwOnCapture: Throwable? = null
    override fun capture(name: String): SynthPreset {
        throwOnCapture?.let { throw it }
        return SynthPreset(name = name)
    }
    override suspend fun save(preset: SynthPreset) {
        if (throwOnSave) error("preset save failed")
        saved += preset
    }
}

internal class FakeTakePlayer : TakePlayer {
    override val state = MutableStateFlow(PlaybackState())
    val played = mutableListOf<String>()
    var stopCount = 0
    var onStop: () -> Unit = {}

    /** When true, play() is recorded but the take never reports as loaded, like a slow load. */
    var loadPending = false

    override fun play(take: TakeInfo) {
        played += take.takeId
        if (loadPending) return
        state.value = PlaybackState(take.takeId, playing = true, durationSec = take.durationSec)
    }
    override fun pause() { state.value = state.value.copy(playing = false) }
    override fun seek(fraction: Float) {
        state.value = state.value.copy(positionSec = fraction * state.value.durationSec)
    }
    override fun stop() {
        stopCount++
        onStop()
        state.value = PlaybackState()
    }
}
