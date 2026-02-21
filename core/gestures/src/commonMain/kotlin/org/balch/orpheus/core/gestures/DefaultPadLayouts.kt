package org.balch.orpheus.core.gestures

/**
 * Factory for default pad layouts.
 * Layouts use normalized [0,1] coordinates and include small gaps between pads.
 */
object DefaultPadLayouts {

    /**
     * Keyboard-style layout with tall key-shaped pads:
     * - Row 0: Voices 0-3 (Quad 0)
     * - Row 1: Voices 4-7 (Quad 1)
     * - Row 2: BD, SD, HH (shorter, wider drum keys)
     *
     * Voice keys are ~15% wide × 18% tall (key proportions).
     * Drum keys are ~15% wide × 10% tall (shorter pedal feel).
     */
    fun grid4x4(): List<GesturePad> {
        val padW = 0.15f
        val padH = 0.18f
        val drumH = 0.10f
        val gapX = 0.02f
        val gapY = 0.015f
        val cols = 4

        // Center the 4-column block horizontally
        val blockW = cols * padW + (cols - 1) * gapX
        val offsetX = (1f - blockW) / 2f

        // Start rows from 12% down to leave room for controls at top
        val startY = 0.12f

        val pads = mutableListOf<GesturePad>()

        // Voice pads: 2 rows x 4 columns (8 voices)
        for (row in 0 until 2) {
            for (col in 0 until 4) {
                val voiceIndex = row * 4 + col
                val left = offsetX + col * (padW + gapX)
                val top = startY + row * (padH + gapY)
                pads += GesturePad(
                    id = "voice_$voiceIndex",
                    type = PadType.VOICE,
                    voiceIndex = voiceIndex,
                    drumType = null,
                    bounds = NormalizedRect(left, top, left + padW, top + padH),
                    label = "${voiceIndex + 1}",
                    sizeMode = PadSizeMode.LARGE,
                )
            }
        }

        // Drum pads: row 3, centered 3 round pads (square bounds for circular rendering)
        val drumSize = 0.14f
        val drumCols = 3
        val drumGapX = 0.03f
        val drumBlockW = drumCols * drumSize + (drumCols - 1) * drumGapX
        val drumOffsetX = (1f - drumBlockW) / 2f
        val drumTop = startY + 2 * (padH + gapY) + 0.01f

        val drumLabels = listOf("BD", "SD", "HH")
        for (col in 0 until 3) {
            val left = drumOffsetX + col * (drumSize + drumGapX)
            pads += GesturePad(
                id = "drum_${drumLabels[col].lowercase()}",
                type = PadType.DRUM,
                voiceIndex = null,
                drumType = col,
                bounds = NormalizedRect(left, drumTop, left + drumSize, drumTop + drumSize),
                label = drumLabels[col],
                sizeMode = PadSizeMode.LARGE,
            )
        }

        return pads
    }
}
