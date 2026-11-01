package org.balch.orpheus.djapp.variant

import org.balch.orpheus.djapp.AiTab

/** The DJ app's editions; [customizableNav] gates the drag-to-reorder bottom nav. */
enum class DjEdition(val customizableNav: Boolean) {
    OG(true),
    AI(true),
}

/** The AI edition is the one whose graph contributes the AI tab; the OG edition has none. */
fun djEditionOf(contributions: Set<DjTabContribution>): DjEdition =
    if (contributions.any { it.route == AiTab }) DjEdition.AI else DjEdition.OG
