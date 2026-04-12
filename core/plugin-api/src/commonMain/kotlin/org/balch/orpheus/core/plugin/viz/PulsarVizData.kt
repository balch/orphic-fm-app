package org.balch.orpheus.core.plugin.viz

const val PULSAR_NUM_TRACKS = 8
const val PULSAR_MAX_STEPS = 32

private const val NUM_TRACKS = PULSAR_NUM_TRACKS
private const val MAX_STEPS = PULSAR_MAX_STEPS

/**
 * Visualization data for the Pulsar step grid.
 *
 * @param stepGates 8 tracks x 32 steps — true if the step is active
 * @param stepVelocities 8 tracks x 32 steps — velocity 0..1
 * @param playheads Current playhead position per track (0-based, -1 = off)
 * @param stepCounts Number of active steps per track
 */
data class PulsarVizData(
    val stepGates: Array<BooleanArray> = Array(NUM_TRACKS) { BooleanArray(MAX_STEPS) },
    val stepVelocities: Array<FloatArray> = Array(NUM_TRACKS) { FloatArray(MAX_STEPS) },
    val playheads: IntArray = IntArray(NUM_TRACKS) { -1 },
    val stepCounts: IntArray = IntArray(NUM_TRACKS) { 16 },
    val trackLevels: FloatArray = FloatArray(NUM_TRACKS),  // per-track peak audio level 0..1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PulsarVizData) return false
        if (!stepGates.contentDeepEquals(other.stepGates)) return false
        if (!stepVelocities.contentDeepEquals(other.stepVelocities)) return false
        if (!playheads.contentEquals(other.playheads)) return false
        if (!stepCounts.contentEquals(other.stepCounts)) return false
        if (!trackLevels.contentEquals(other.trackLevels)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = stepGates.contentDeepHashCode()
        result = 31 * result + stepVelocities.contentDeepHashCode()
        result = 31 * result + playheads.contentHashCode()
        result = 31 * result + stepCounts.contentHashCode()
        result = 31 * result + trackLevels.contentHashCode()
        return result
    }
}

data class PulsarArrangementState(
    val sectionIndex: Int,
    val barsElapsed: Int,
    val barsTotal: Int,
    val soloActive: Boolean,
    val soloTrack: Int,
    val soloMode: Int,
    val bandSolo: Boolean = false,
    val bandMemberNames: List<String> = emptyList(),
)

val ARRANGEMENT_STATE_UNKNOWN = PulsarArrangementState(-1, 0, 0, false, -1, 0)
