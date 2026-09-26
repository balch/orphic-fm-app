package org.balch.orpheus.features.pulsar.playback

/**
 * The vibe [step] places away from [current] in [names], wrapping at both ends. A [current] not in
 * the list (an AI-generated vibe) lands on the first entry going forward and the last going back.
 */
fun neighborVibe(names: List<String>, current: String, step: Int): String? {
    if (names.isEmpty()) return null
    val index = names.indexOf(current)
    if (index < 0) return if (step >= 0) names.first() else names.last()
    return names[(index + step).mod(names.size)]
}
