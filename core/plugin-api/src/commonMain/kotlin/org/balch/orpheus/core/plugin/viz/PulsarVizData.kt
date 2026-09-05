package org.balch.orpheus.core.plugin.viz

const val PULSAR_NUM_TRACKS = 8
// The viz step-grid EXPORT width — MUST equal C++ kPulsarVizSteps (orpheus_engine.h).
// Decoupled from the sequencer/lick cap (kMaxPulsarSteps, 64): a >32-step pattern plays
// fully but the on-screen grid shows only the first PULSAR_MAX_STEPS. The native producer
// clamps to this; raising the grid to 64 must change BOTH sides in lockstep.
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
 * @param trackLevels 8 tracks — per-track peak audio level 0..1
 * @param voidGain Live Void Anomaly gain (1.0 = idle/no duck, dips toward the
 *   arc's floor while active). Rides the same fast viz-ring transport as
 *   [trackLevels]; drives the VIBE dropdown's glow in PulsarPanel.
 * @param activeEngines Live per-track engine id the DSP is actually playing
 *   (reflects the random crossfade + section energy overrides). -1 = unset.
 */
data class PulsarVizData(
    val stepGates: Array<BooleanArray> = Array(NUM_TRACKS) { BooleanArray(MAX_STEPS) },
    val stepVelocities: Array<FloatArray> = Array(NUM_TRACKS) { FloatArray(MAX_STEPS) },
    val playheads: IntArray = IntArray(NUM_TRACKS) { -1 },
    val stepCounts: IntArray = IntArray(NUM_TRACKS) { 16 },
    val trackLevels: FloatArray = FloatArray(NUM_TRACKS),  // per-track peak audio level 0..1
    val voidGain: Float = 1f,
    val activeEngines: IntArray = IntArray(NUM_TRACKS) { -1 },
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PulsarVizData) return false
        if (!stepGates.contentDeepEquals(other.stepGates)) return false
        if (!stepVelocities.contentDeepEquals(other.stepVelocities)) return false
        if (!playheads.contentEquals(other.playheads)) return false
        if (!stepCounts.contentEquals(other.stepCounts)) return false
        if (!trackLevels.contentEquals(other.trackLevels)) return false
        if (voidGain != other.voidGain) return false
        if (!activeEngines.contentEquals(other.activeEngines)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = stepGates.contentDeepHashCode()
        result = 31 * result + stepVelocities.contentDeepHashCode()
        result = 31 * result + playheads.contentHashCode()
        result = 31 * result + stepCounts.contentHashCode()
        result = 31 * result + trackLevels.contentHashCode()
        result = 31 * result + voidGain.hashCode()
        result = 31 * result + activeEngines.contentHashCode()
        return result
    }
}

/**
 * Live Pulsar arrangement snapshot, polled at 5Hz (see SynthEngineMonitor) and NOT gated
 * on UI visibility -- background consumers (song auto-advance, media-session metadata)
 * depend on it staying current.
 *
 * [scoreTick] and [scoreHeld] mirror the notated-score clock (`pulsar_score_pos_tick`/
 * `pulsar_score_any_held`) that score-driven consumers read. At the C-getter level they
 * are independent of [sectionIndex]/[barsElapsed]/[barsTotal] above -- populated even with
 * no Pulsar arrangement configured. At the monitor layer, though, the whole state is gated
 * on an active arrangement (sectionIdx >= 0), so consumers only see these fields while an
 * arrangement runs -- a score-only piece would need that gate revisited. [scoreTick] also
 * changes on essentially every poll while a score plays, which makes this data class emit
 * on every 5Hz tick during playback instead of only on a section change; intentional, since
 * the conducting ribbon needs a live tick, not an occasional event.
 */
data class PulsarArrangementState(
    val sectionIndex: Int,
    val barsElapsed: Int,
    val barsTotal: Int,
    val soloActive: Boolean,
    val soloTrack: Int,
    val soloMode: Int,
    val bandSolo: Boolean = false,
    val bandMemberNames: List<String> = emptyList(),
    /**
     * Ticks elapsed since the current vibe/score was loaded; free-runs regardless of
     * whether a score is armed. Do not use scoreTick==0 to detect absence of a score.
     */
    val scoreTick: Int = 0,
    /** True while any score-driven track is parked on a hold event. */
    val scoreHeld: Boolean = false,
)

val ARRANGEMENT_STATE_UNKNOWN = PulsarArrangementState(-1, 0, 0, false, -1, 0)
