package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * The sound a written part plays with. Mirrored onto the engine's per-part atomics and
 * applied by the score-voice mirror loop; defaults equal the engine's own defaults so an
 * absent block sounds exactly like before this type existed. [colorResponse] scales how
 * far the conductor's color hand can move [timbre] live.
 */
@Serializable
data class PartTimbre(
    val engineIndex: Int = 0,
    val harmonics: Float = 0.5f,
    val timbre: Float = 0.5f,
    val morph: Float = 0.5f,
    val decay: Float = 0.5f,
    val level: Float = 1f,
    val colorResponse: Float = 0f,
) {
    init {
        require(engineIndex >= 0) { "PartTimbre.engineIndex must be >= 0, got $engineIndex" }
        listOf("harmonics" to harmonics, "timbre" to timbre, "morph" to morph,
               "decay" to decay, "colorResponse" to colorResponse).forEach { (n, v) ->
            require(v in 0f..1f) { "PartTimbre.$n must be 0..1, got $v" }
        }
        require(level in 0f..2f) { "PartTimbre.level must be 0..2, got $level" }
    }
}

/**
 * One note in a notated part. [tick] and [durationTicks] are integer ticks at
 * [NotatedScore.PPQ]; nothing in the model uses floating-point positions.
 *
 * [hold] pauses the scheduler at this event until released — the per-note conducting hook.
 * [bandRelease] flags bit 1 on the wire — clears the engine's band hold, cueing the backing in.
 * No authored event sets these in phase A.
 */
@Serializable
data class ScoreEvent(
    val tick: Int,
    val durationTicks: Int,
    val pitch: Int,
    val velocity: Int,
    val hold: Boolean = false,
    val bandRelease: Boolean = false,
) {
    init {
        require(tick >= 0) { "ScoreEvent.tick must be >= 0, got $tick" }
        require(durationTicks > 0) { "ScoreEvent.durationTicks must be > 0, got $durationTicks" }
        require(pitch in 0..127) { "ScoreEvent.pitch must be 0..127, got $pitch" }
        require(velocity in 0..127) { "ScoreEvent.velocity must be 0..127, got $velocity" }
    }
}

/**
 * One written part, bound to the Pulsar track it drives. That track keeps its whole
 * TrackVoice configuration — engine pair, envelope, macros, pan, sends, range, glide.
 * Only the choice of which note and when comes from here.
 */
@Serializable
data class NotatedPart(
    val trackIndex: Int,
    val name: String,
    val events: List<ScoreEvent>,
    val timbre: PartTimbre = PartTimbre(),
) {
    init {
        require(trackIndex in 0..7) { "NotatedPart.trackIndex must be 0..7, got $trackIndex" }
        require(events.isNotEmpty()) { "NotatedPart '$name' has no events" }
        require(events.size <= NotatedScore.MAX_SCORE_EVENTS) {
            "NotatedPart '$name' has ${events.size} events, exceeding " +
                "MAX_SCORE_EVENTS=${NotatedScore.MAX_SCORE_EVENTS}"
        }
        // Non-descending, not strictly ascending: events sharing a tick ARE a chord, and
        // score_collect_due returns them together for the voice pool to allocate. Ordering
        // is still required because the C++ cursor walks forward and never sorts.
        for (i in 1 until events.size) {
            require(events[i].tick >= events[i - 1].tick) {
                "NotatedPart '$name' events must not descend by tick; index $i " +
                    "is ${events[i].tick} after ${events[i - 1].tick}"
            }
        }
    }
}

/** A whole written piece: one part per driven track. */
@Serializable
data class NotatedScore(
    val name: String,
    val ppq: Int = PPQ,
    val parts: List<NotatedPart>,
) {
    init {
        require(parts.isNotEmpty()) { "NotatedScore '$name' has no parts" }
        require(ppq == PPQ) { "NotatedScore.ppq must be $PPQ, got $ppq" }
        val tracks = parts.map { it.trackIndex }
        require(tracks.toSet().size == tracks.size) {
            "NotatedScore '$name' has more than one part on a track: $tracks"
        }
    }

    companion object {
        /** Ticks per quarter note. Divisible by 3, 4, 6 and 8 so triplets and 32nds are exact. */
        const val PPQ = 96

        /** MUST equal kMaxScoreEvents in liborpheus_dsp/src/pulsar_limits.h. */
        const val MAX_SCORE_EVENTS = 4096
    }
}
