package org.balch.orpheus.ui.panels.slots

import androidx.compose.runtime.Immutable
import org.balch.orpheus.core.features.PanelId

/** Side rail sections, in display order. */
enum class PanelGroup(val title: String) {
    Rhythm("RHYTHM"),
    Mod("MOD"),
    Fx("FX"),
    Tools("TOOLS"),
}

/** One panel's place in the rail: its group, short rail label and slot width class. */
@Immutable
data class PanelNavEntry(
    val id: PanelId,
    val shortLabel: String,
    val group: PanelGroup,
    val wide: Boolean = false,
)

/** Every desktop panel, in rail order (group by group). */
val PanelNavEntries: List<PanelNavEntry> = listOf(
    // Rhythm
    PanelNavEntry(id = PanelId.PULSAR, shortLabel = "PULSE", group = PanelGroup.Rhythm, wide = true),
    PanelNavEntry(id = PanelId.BEATS, shortLabel = "BEATS", group = PanelGroup.Rhythm, wide = true),
    PanelNavEntry(id = PanelId.DRUMS, shortLabel = "808", group = PanelGroup.Rhythm),
    PanelNavEntry(id = PanelId.BASS, shortLabel = "BASS", group = PanelGroup.Rhythm),
    PanelNavEntry(id = PanelId.DJ, shortLabel = "DJ", group = PanelGroup.Rhythm),
    PanelNavEntry(id = PanelId.SPEECH, shortLabel = "TALK", group = PanelGroup.Rhythm),
    // Mod
    PanelNavEntry(id = PanelId.LFO, shortLabel = "LFO", group = PanelGroup.Mod),
    PanelNavEntry(id = PanelId.TIDES, shortLabel = "Waves", group = PanelGroup.Mod),
    PanelNavEntry(id = PanelId.EVO, shortLabel = "EVO", group = PanelGroup.Mod),
    PanelNavEntry(id = PanelId.FLUX, shortLabel = "FLUX", group = PanelGroup.Mod),
    PanelNavEntry(id = PanelId.FLUX_TRIGGERS, shortLabel = "TRIGG", group = PanelGroup.Mod),
    // Fx
    PanelNavEntry(id = PanelId.DISTORTION, shortLabel = "VOL", group = PanelGroup.Fx),
    PanelNavEntry(id = PanelId.DELAY, shortLabel = "DELAY", group = PanelGroup.Fx),
    PanelNavEntry(id = PanelId.REVERB, shortLabel = "VERB", group = PanelGroup.Fx),
    PanelNavEntry(id = PanelId.HORN, shortLabel = "HORN", group = PanelGroup.Fx),
    PanelNavEntry(id = PanelId.RESONATOR, shortLabel = "REZO", group = PanelGroup.Fx),
    PanelNavEntry(id = PanelId.GRAINS, shortLabel = "GRAIN", group = PanelGroup.Fx),
    PanelNavEntry(id = PanelId.WARPS, shortLabel = "BLEND", group = PanelGroup.Fx),
    PanelNavEntry(id = PanelId.LOOPER, shortLabel = "LOOP", group = PanelGroup.Fx),
    // Tools
    PanelNavEntry(id = PanelId.PRESETS, shortLabel = "PATCH", group = PanelGroup.Tools),
    PanelNavEntry(id = PanelId.MIDI, shortLabel = "MIDI", group = PanelGroup.Tools),
    PanelNavEntry(id = PanelId.VIZ, shortLabel = "VIZ", group = PanelGroup.Tools),
    PanelNavEntry(id = PanelId.CODE, shortLabel = "CODE", group = PanelGroup.Tools),
    PanelNavEntry(id = PanelId.AI, shortLabel = "AI", group = PanelGroup.Tools),
    PanelNavEntry(id = PanelId.VIBE_CREATE, shortLabel = "VIBE", group = PanelGroup.Tools),
    PanelNavEntry(id = PanelId.ASL_MAESTRO, shortLabel = "ASL", group = PanelGroup.Tools),
    PanelNavEntry(id = PanelId.TIMER, shortLabel = "Timer", group = PanelGroup.Tools),
    PanelNavEntry(id = PanelId.RECORD, shortLabel = "REC", group = PanelGroup.Tools),
)

/** True for panels that take a double-width slot. */
fun isWidePanel(id: PanelId): Boolean = PanelNavEntries.any { it.id == id && it.wide }

/** A searchable panel: its catalog entry plus the display label and description to match against. */
@Immutable
data class SearchCandidate(
    val entry: PanelNavEntry,
    val label: String,
    val description: String,
)

/**
 * Ranks [candidates] against [query] (trimmed, case-insensitive): short-label prefix, then label
 * prefix, then substring anywhere. Input order is kept within a tier; blank returns everything.
 */
fun searchPanels(query: String, candidates: List<SearchCandidate>): List<PanelNavEntry> {
    val q = query.trim()
    if (q.isEmpty()) return candidates.map { it.entry }

    val shortPrefix = candidates.filter { it.entry.shortLabel.startsWith(q, ignoreCase = true) }
    val labelPrefix = candidates.filter {
        it !in shortPrefix && it.label.startsWith(q, ignoreCase = true)
    }
    val contains = candidates.filter {
        it !in shortPrefix && it !in labelPrefix &&
            (it.entry.shortLabel.contains(q, ignoreCase = true) ||
                it.label.contains(q, ignoreCase = true) ||
                it.entry.id.id.contains(q, ignoreCase = true) ||
                it.description.contains(q, ignoreCase = true))
    }
    return (shortPrefix + labelPrefix + contains).map { it.entry }
}
