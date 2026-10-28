package org.balch.orpheus.djapp.vibepresets

import kotlin.math.abs
import kotlin.math.roundToInt

// The wording table: edit this block to change how a change is named.
private fun PresetChange.word(): String {
    val up = to > from
    val on = to >= 0.5f
    val key = field.key
    val track = key.substringAfter("tracks.", "").substringBefore('.')
    return when {
        field == PresetField.PULSAR_ROOT ->
            "${NOTE_NAMES[to.roundToInt().mod(NOTE_NAMES.size)]} Key"
        key.endsWith(".mute") -> if (on) "Muted T$track" else "Unmuted T$track"
        key.endsWith(".solo") -> if (on) "Solo T$track" else "Unsolo T$track"
        field == PresetField.PULSAR_BPM -> "${if (up) "Fast" else "Slow"} BPM"
        field == PresetField.HORN_SPEED -> "${if (up) "Fast" else "Slow"} Horn"
        field == PresetField.MIX_DIST -> "${if (up) "Heavy" else "Light"} Drive"
        field == PresetField.MIX_BASS -> if (up) "Bright Bass" else "Deep Bass"
        field == PresetField.MIX_PERC || field == PresetField.MIX_KEYS || field == PresetField.MIX_FX ->
            "${if (up) "Bright" else "Quiet"} ${field.label.titleCase()}"
        else -> "${if (up) "More" else "Less"} ${field.label.titleCase()}"
    }
}

private fun String.titleCase(): String =
    split(' ').joinToString(" ") { word ->
        if (word.length > 1 && word.any { it.isDigit() }) word else word.lowercase().replaceFirstChar { it.uppercase() }
    }

private const val BPM_SPAN = 40f

// Fields that jump between values (switches, note and scale indexes) count as a full move.
private fun PresetChange.size(): Float = when {
    field == PresetField.PULSAR_BPM -> abs(to - from) / BPM_SPAN
    field == PresetField.PULSAR_ROOT ||
        field == PresetField.PULSAR_SCALE ||
        field == PresetField.PULSAR_ENVELOPE ||
        field == PresetField.DJ_SOURCE_A ||
        field == PresetField.DJ_SOURCE_B ||
        field == PresetField.HORN_BRAKE ||
        field.key.endsWith(".mute") ||
        field.key.endsWith(".solo") -> 1f
    else -> abs(to - from)
}

/**
 * Names a preset after its two biggest-moving modules, e.g. "Slow Horn + Deep Bass".
 * No changes gives "Original"; a name in [taken] gets " 2", " 3" and so on.
 */
fun suggestPresetName(changes: List<PresetChange>, taken: Set<String>): String {
    val words = changes
        .groupBy { it.field.module }
        .values
        .map { group -> group.maxBy { it.size() } }
        .sortedByDescending { it.size() }
        .take(2)
        .map { it.word() }
    val base = if (words.isEmpty()) "Original" else words.joinToString(" + ")
    if (base !in taken) return base
    var n = 2
    while ("$base $n" in taken) n++
    return "$base $n"
}
