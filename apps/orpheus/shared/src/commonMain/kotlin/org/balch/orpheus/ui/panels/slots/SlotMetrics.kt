package org.balch.orpheus.ui.panels.slots

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Width of one slot unit, today's minimum expanded panel width. */
val SlotUnitWidth = 320.dp

/** Height the desktop header gives panels in a 1280x800 window. */
val SlotDesignHeight = 330.dp

private const val MinSlotScale = 0.85f
private const val MaxSlotScale = 1.25f

/** Number of whole units that fit in [areaWidth], never fewer than one. */
fun slotCapacity(areaWidth: Dp): Int = max(1, floor(areaWidth / SlotUnitWidth).toInt())

/** Uniform scale applied inside a slot, from the tighter of its width and height. */
fun slotScale(slotWidth: Dp, slotHeight: Dp, units: Int): Float {
    val fit = min(slotWidth / (SlotUnitWidth * units), slotHeight / SlotDesignHeight)
    return fit.coerceIn(MinSlotScale, MaxSlotScale)
}
