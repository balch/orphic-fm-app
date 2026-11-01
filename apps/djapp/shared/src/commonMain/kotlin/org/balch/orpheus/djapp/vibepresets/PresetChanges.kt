package org.balch.orpheus.djapp.vibepresets

import kotlin.math.abs
import kotlin.math.roundToInt

data class PresetChange(val field: PresetField, val from: Float, val to: Float)

private const val CHANGE_EPSILON = 1e-3f

internal val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** Fields present in both maps that differ, in [PresetField] order. Unknown keys are skipped. */
fun changesBetween(reference: Map<String, Float>, current: Map<String, Float>): List<PresetChange> =
    PresetField.entries.mapNotNull { field ->
        val from = reference[field.key] ?: return@mapNotNull null
        val to = current[field.key] ?: return@mapNotNull null
        if (abs(to - from) > CHANGE_EPSILON) PresetChange(field, from, to) else null
    }

fun PresetChange.chipText(): String = "${field.module.label}: ${field.label} ${format(from)}→${format(to)}"

private fun PresetChange.format(v: Float): String = when {
    field == PresetField.PULSAR_ROOT -> NOTE_NAMES[v.roundToInt().mod(NOTE_NAMES.size)]
    field == PresetField.PULSAR_BPM ||
        field == PresetField.PULSAR_SCALE ||
        field == PresetField.PULSAR_ENVELOPE ||
        field == PresetField.DJ_SOURCE_A ||
        field == PresetField.DJ_SOURCE_B -> v.roundToInt().toString()
    field == PresetField.HORN_BRAKE ||
        field.key.endsWith(".mute") ||
        field.key.endsWith(".solo") -> if (v >= 0.5f) "on" else "off"
    else -> "${(v * 100f).roundToInt()}%"
}
