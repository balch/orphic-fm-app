package org.balch.orpheus.djapp.vibepresets

import org.balch.orpheus.core.preferences.VibePreset
import org.balch.orpheus.core.preferences.VibePresetSlot

const val MAX_PRESETS = 5

val VibePresetSlot.isFull: Boolean get() = presets.size >= MAX_PRESETS

val VibePresetSlot.selected: VibePreset? get() = presets.firstOrNull { it.id == selectedId }

/** Appends a preset, or replaces [replaceId] in place. The new preset becomes the selected one. */
fun VibePresetSlot.capture(
    name: String,
    atMs: Long,
    values: Map<String, Float>,
    originals: Map<String, Float>,
    replaceId: Int? = null,
): VibePresetSlot {
    val preset = VibePreset(nextId, name, atMs, values, originals)
    val replaced = replaceId != null && presets.any { it.id == replaceId }
    require(replaced || !isFull) { "Preset book is full ($MAX_PRESETS); pass replaceId" }
    val updated = if (replaced) presets.map { if (it.id == replaceId) preset else it } else presets + preset
    return copy(presets = updated, selectedId = preset.id, nextId = nextId + 1)
}

fun VibePresetSlot.rename(id: Int, name: String): VibePresetSlot =
    copy(presets = presets.map { if (it.id == id) it.copy(name = name) else it })

fun VibePresetSlot.delete(id: Int): VibePresetSlot =
    copy(
        presets = presets.filterNot { it.id == id },
        selectedId = selectedId.takeUnless { it == id },
    )

fun VibePresetSlot.select(id: Int?): VibePresetSlot = copy(selectedId = id)
