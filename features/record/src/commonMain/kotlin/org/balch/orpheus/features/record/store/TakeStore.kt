package org.balch.orpheus.features.record.store

import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.features.record.telemetry.TakeTelemetry

data class TakeInfo(
    val takeId: String,
    val durationSec: Float,
    val createdAtMs: Long,
    val audioPath: String,
)

/** One open take: audio streams in, then [finish] or [abort]. */
interface TakeWriter {
    fun writeAudio(interleaved: FloatArray, frames: Int)

    /** Patches the WAV sizes and writes `preset.json` and `telemetry.json`. */
    fun finish(preset: SynthPreset, telemetry: TakeTelemetry)

    /** Deletes the take folder. */
    fun abort()
}

interface TakeStore {
    /** Finished takes, newest first. */
    suspend fun list(): List<TakeInfo>
    suspend fun delete(takeId: String)
    fun open(takeId: String, sampleRate: Int): TakeWriter
}
