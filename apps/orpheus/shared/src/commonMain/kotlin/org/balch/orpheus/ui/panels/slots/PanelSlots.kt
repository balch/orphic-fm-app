package org.balch.orpheus.ui.panels.slots

import androidx.compose.runtime.Immutable
import org.balch.orpheus.core.features.PanelId

private const val MaxRecents = 12
private const val MinCapacityForWide = 3

/** A panel occupying [units] consecutive slot units. */
@Immutable
data class OpenPanel(val id: PanelId, val units: Int)

/**
 * Which panels fill the desktop slot row.
 *
 * [open] is in slot order. [recents] holds closed panels, [lastUsed] open ones, both most recent
 * first. A [capacity] of 0 means the width is not known yet: unbounded, one unit per panel.
 */
@Immutable
data class PanelSlots(
    val open: List<OpenPanel> = emptyList(),
    val pinned: Set<PanelId> = emptySet(),
    val recents: List<PanelId> = emptyList(),
    val lastUsed: List<PanelId> = emptyList(),
    val capacity: Int = 0,
) {
    val openIds: List<PanelId> get() = open.map { it.id }

    val freeUnits: Int
        get() = if (capacity == 0) 0 else (capacity - open.sumOf { it.units }).coerceAtLeast(0)

    fun isOpen(id: PanelId): Boolean = open.any { it.id == id }

    /** Pins may never cover every unit, so one unpinned slot always remains to replace. */
    fun canPin(id: PanelId): Boolean {
        val panel = open.firstOrNull { it.id == id } ?: return false
        if (capacity == 0) return false
        val others = open.filter { it.id in pinned && it.id != id }.sumOf { it.units }
        return others + panel.units <= capacity - 1
    }
}

private fun List<PanelId>.pushedToFront(id: PanelId): List<PanelId> = listOf(id) + (this - id)

private fun List<PanelId>.recentsWith(ids: List<PanelId>): List<PanelId> =
    (ids + (this - ids.toSet())).distinct().take(MaxRecents)

private fun PanelSlots.canTakeWide(freeUnits: Int) =
    capacity >= MinCapacityForWide && freeUnits >= 2

/** Appends [id] into free units, widening it only when a second unit is free. */
private fun PanelSlots.append(id: PanelId, isWide: (PanelId) -> Boolean): PanelSlots {
    val units = if (isWide(id) && canTakeWide(freeUnits)) 2 else 1
    return copy(open = open + OpenPanel(id, units), recents = recents - id)
}

private fun PanelSlots.hasRoom() = capacity == 0 || freeUnits > 0

fun PanelSlots.open(id: PanelId, isWide: (PanelId) -> Boolean): PanelSlots {
    if (isOpen(id)) return touch(id)
    if (hasRoom()) {
        val appended = append(id, isWide)
        return appended.copy(lastUsed = lastUsed.pushedToFront(id))
    }
    val victim = lastUsed.lastOrNull { it !in pinned } ?: return this
    val index = open.indexOfFirst { it.id == victim }
    val victimUnits = open[index].units
    val units = if (isWide(id) && capacity >= MinCapacityForWide && victimUnits == 2) 2 else 1
    return copy(
        open = open.toMutableList().also { it[index] = OpenPanel(id, units) },
        recents = (recents - id).recentsWith(listOf(victim)),
        lastUsed = (lastUsed - victim).pushedToFront(id),
    )
}

fun PanelSlots.close(id: PanelId): PanelSlots {
    if (!isOpen(id)) return this
    return copy(
        open = open.filterNot { it.id == id },
        pinned = pinned - id,
        recents = recents.recentsWith(listOf(id)),
        lastUsed = lastUsed - id,
    )
}

fun PanelSlots.touch(id: PanelId): PanelSlots =
    if (isOpen(id)) copy(lastUsed = lastUsed.pushedToFront(id)) else this

fun PanelSlots.togglePin(id: PanelId): PanelSlots = when {
    id in pinned -> copy(pinned = pinned - id)
    canPin(id) -> copy(pinned = pinned + id)
    else -> this
}

/** The first capacity (from 0) fits and widens only, so slots the user left empty stay empty. */
fun PanelSlots.withCapacity(units: Int, isWide: (PanelId) -> Boolean): PanelSlots {
    if (units == capacity) return this
    val fitted = copy(capacity = units).fitToCapacity()
    return when {
        capacity == 0 -> fitted.widen(isWide)
        units > capacity -> fitted.widen(isWide).refill(isWide)
        else -> fitted
    }
}

/** Opens [ids] in order into the free units. Unpinned panels are closed first and nothing is evicted. */
fun PanelSlots.applySet(ids: List<PanelId>, isWide: (PanelId) -> Boolean): PanelSlots {
    var next = open.map { it.id }.filter { it !in pinned }.fold(this) { s, id -> s.close(id) }
    for (id in ids) {
        if (!next.hasRoom()) break
        if (next.isOpen(id)) continue
        next = next.append(id, isWide).let { it.copy(lastUsed = it.lastUsed.pushedToFront(id)) }
    }
    return next
}

/** Trims to the current capacity: pinned panels by recency first, then unpinned ones. */
private fun PanelSlots.fitToCapacity(): PanelSlots {
    if (capacity == 0) return copy(open = open.map { it.copy(units = 1) })

    val sized = if (capacity < MinCapacityForWide) open.map { it.copy(units = 1) } else open
    val unitsById = sized.associate { it.id to it.units }

    var keepPinned = pinned.filter { it in unitsById }.toSet()
    val byRecency = lastUsed.filter { it in unitsById }
    while (keepPinned.sumOf { unitsById.getValue(it) } > capacity - 1) {
        keepPinned = keepPinned - byRecency.last { it in keepPinned }
    }

    val preference = byRecency.filter { it in keepPinned } + byRecency.filter { it !in keepPinned }
    var used = 0
    val kept = mutableSetOf<PanelId>()
    for (id in preference) {
        val units = unitsById.getValue(id)
        if (used + units > capacity) continue
        used += units
        kept += id
    }

    val dropped = preference.filter { it !in kept }
    return copy(
        open = sized.filter { it.id in kept },
        pinned = keepPinned.filter { it in kept }.toSet(),
        recents = recents.recentsWith(dropped),
        lastUsed = lastUsed.filter { it in kept },
    )
}

/** Widens wide panels into free units. Pins stay under capacity - 1. */
private fun PanelSlots.widen(isWide: (PanelId) -> Boolean): PanelSlots {
    if (capacity < MinCapacityForWide) return this

    var free = freeUnits
    var pinnedUnits = open.filter { it.id in pinned }.sumOf { it.units }
    val widened = open.map { panel ->
        val pinCost = if (panel.id in pinned) 1 else 0
        val widen = panel.units == 1 && isWide(panel.id) && free > 0 && pinnedUnits + pinCost <= capacity - 1
        if (widen) {
            free--
            pinnedUnits += pinCost
            panel.copy(units = 2)
        } else {
            panel
        }
    }
    return copy(open = widened)
}

/** Fills free units from recents, most recent first. */
private fun PanelSlots.refill(isWide: (PanelId) -> Boolean): PanelSlots {
    var next = this
    for (id in recents) {
        if (next.freeUnits == 0) break
        next = next.append(id, isWide).let { it.copy(lastUsed = it.lastUsed + id) }
    }
    return next
}
