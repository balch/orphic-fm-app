package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.plugin.viz.PulsarArrangementState

/** Who is soloing, or null: the band member's name when the vibe has a band, else the track name. */
fun PulsarArrangementState.soloistName(): String? {
    if (!soloActive || soloTrack < 0) return null
    return if (bandSolo) {
        bandMemberNames.getOrElse(soloTrack) { "?" }
    } else {
        PULSAR_TRACK_NAMES.getOrElse(soloTrack) { "?" }
    }
}
