package org.balch.orpheus.features.pulsar.anonmalies

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.pulsar.models.WahParams

/**
 * Config for the Wah Anomaly: a rare "the lead voices swing under a sweeping
 * tempo-synced wah for a few bars" moment, while the drums, chords, and bass line
 * stay dry. An [Anomaly] subtype: list it in
 * [org.balch.orpheus.features.pulsar.models.Vibe.anomalies] to enable it. Auto-fires per section at [probability]; also
 * force-fired by the manual anomaly trigger on any vibe that declares it (the
 * wah only arms while a section graph is active, which also gates the live
 * trigger).
 *
 * **Lead tracks only.** This is a per-track insert, not a master-bus effect. A track
 * is eligible when all three hold: its role is `TrackRole.Melodic`, its
 * `lickSource` is `LickSource.LEAD`, and it is not the bass-line channel (track
 * index 3, which is the bass on every vibe). Percussive and chordal tracks are never
 * filtered. A vibe whose tracks are all ineligible simply never fires the wah:
 * there is deliberately no whole-mix fallback, since filtering the drums is the bug
 * this scoping replaced.
 *
 * **Takeover, never stacking.** An eligible track that already carries the standing
 * lick-wah insert (`Vibe.lickWah` plus `TrackRole.Melodic.wahLick`) keeps running
 * that one filter, and these [voice] params displace the standing ones for the armed
 * duration, easing in at the start and back out at the end. Every track runs at most
 * one bandpass at any instant, never two in series. So a [voice] that barely differs
 * from the vibe's `lickWah` will barely read on that track. Give it contrast, a
 * faster [WahParams.rateDivision] or a tighter [WahParams.resonanceQ], if the moment
 * should land.
 *
 * Durations are in musical bars (16 steps); the armed length is drawn from
 * [durationBarsMin]..[durationBarsMax].
 */
@Serializable
@SerialName("wah")
data class WahAnomaly(
    val probability: Float = 0.03f,   // auto-roll chance per section entry (ship value)
    val durationBarsMin: Float = 2f,
    val durationBarsMax: Float = 4f,
    val voice: WahParams = WahParams(),
) : Anomaly {
    init {
        require(probability in 0f..1f) { "probability must be 0..1, got $probability" }
        require(durationBarsMax >= durationBarsMin) {
            "durationBars range invalid: $durationBarsMin..$durationBarsMax"
        }
    }
}
