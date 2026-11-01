package org.balch.orpheus.core.audio.patch

const val PATCH_URI = "org.balch.orpheus.plugins.patch"
const val PATCH_CELLS = 2 * 16 * 6

fun patchCellIndex(voice: Int, source: PatchSource, dest: PatchDest): Int =
    (voice * 16 + source.ordinal) * 6 + dest.ordinal

fun patchCellSymbol(duo: Int, cell: Int): String =
    "p${duo}_${cell / 96}_${cell % 96 / 6}_${cell % 6}"

/** One amount per (voice, source, dest) cell: BOTH fills both voices, parked cables give 0. */
fun DuoPatch.cellAmounts(): FloatArray {
    val cells = FloatArray(PATCH_CELLS)
    for (c in cables) {
        if (c.parked) continue
        val voices = when (c.slot) {
            VoiceSlot.BOTH -> 0..1
            VoiceSlot.A -> 0..0
            VoiceSlot.B -> 1..1
        }
        for (v in voices) cells[patchCellIndex(v, c.source, c.dest)] = c.amount
    }
    return cells
}

fun duoPatchKey(duo: Int): String = "org.balch.orpheus.plugins.voice:duo_patch_$duo"

fun duoModSourceLegacyKey(duo: Int): String = "org.balch.orpheus.plugins.voice:duo_mod_source_$duo"
