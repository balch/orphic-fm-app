package org.balch.orpheus.features.visualizations.viz.face

import kotlin.math.pow

/** Fraction of the song that stays fully human before anything starts to turn. */
private const val HOLD = 0.10f

/** 1 turns at a steady rate. Higher stays subtle for longer, then rushes to the skull. */
private const val PACE = 1.5f

/** The most a sustained loud passage can add, as a share of what is left to turn. */
private const val SURGE_AMOUNT = 0.5f

/** How much of that surge an untouched face gets. 0 keeps a loud intro human, 1 lets it jump. */
private const val EARLY_REACH = 0.25f

/**
 * How far the face has irreversibly turned at a given point in the song. Must be monotonic
 * with shape(0) == 0 and shape(1) == 1. The director ratchets it, so it never runs backwards.
 */
internal fun shape(progress: Float): Float {
    val turning = ((progress - HOLD) / (1f - HOLD)).coerceIn(0f, 1f)
    return turning.pow(PACE)
}

/**
 * How far loud music pushes the face past its floor, as an amount added to [floor]. Must be
 * >= 0, and 0 when intensity is 0 or the floor is already 1.
 */
internal fun surge(intensity: Float, floor: Float): Float {
    val i = intensity.coerceIn(0f, 1f)
    val f = floor.coerceIn(0f, 1f)
    // Eased so the surge has no hard threshold to dither across from frame to frame.
    val loudness = i * i * (3f - 2f * i)
    // Surges grow as the face turns, and (1 - f) stops them overshooting a finished face.
    val reach = EARLY_REACH + (1f - EARLY_REACH) * f
    return SURGE_AMOUNT * loudness * reach * (1f - f)
}
