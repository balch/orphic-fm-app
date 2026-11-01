package org.balch.orpheus.ui.widgets

import androidx.compose.animation.core.Easing

/** One leg of the bender spring-back: animate to [target] over [durationMillis]. */
data class SpringStage(val target: Float, val durationMillis: Int)

/** Ease out quad, shared by every spring-back leg. */
val BenderSpringEasing: Easing = Easing { t -> t * (2 - t) }

/**
 * The overshoot, bounce and settle legs of a released bender.
 * [start] is the pulled position in any unit and [pull] is how far it was pulled, 0..1.
 * The total is 400 to 1200 ms, split 40/30/30.
 */
fun benderSpringStages(start: Float, pull: Float): List<SpringStage> {
    val total = (400 + (pull * 800)).toInt()
    val overshoot = -start * 0.3f
    return listOf(
        SpringStage(overshoot, (total * 0.4f).toInt()),
        SpringStage(-overshoot * 0.4f, (total * 0.3f).toInt()),
        SpringStage(0f, (total * 0.3f).toInt()),
    )
}
