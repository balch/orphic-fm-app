package org.balch.orpheus.features.pulsar.mixer

/**
 * True iff every track in [group] is muted in [trackMuted].
 * Tracks beyond the list bounds are treated as not muted.
 */
internal fun isGroupMuted(group: MixerGroup, trackMuted: List<Boolean>): Boolean {
    if (group.tracks.isEmpty()) return false
    for (trackIdx in group.tracks) {
        val muted = trackMuted.getOrNull(trackIdx) ?: return false
        if (!muted) return false
    }
    return true
}
