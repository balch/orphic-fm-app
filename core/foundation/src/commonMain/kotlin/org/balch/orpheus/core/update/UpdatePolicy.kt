package org.balch.orpheus.core.update

/** The chosen update flow for an available Play update. */
enum class UpdateDecision { IMMEDIATE, FLEXIBLE, NONE }

/**
 * Maps a Play update's [priority] (0..5, set per release in Play Console) and how long it has
 * been available ([stalenessDays]) to an [UpdateDecision], then constrains the result to the
 * update types Play actually permits for this release ([immediateAllowed]/[flexibleAllowed]).
 *
 * Balanced policy:
 *   priority 5    -> Immediate
 *   priority 4    -> Immediate if stalenessDays >= 3, else Flexible
 *   priority 1..3 -> Flexible
 *   priority 0    -> Flexible if stalenessDays >= 14, else None
 *
 * Downgrade direction: a desired Immediate that Play won't allow falls back to Flexible (still
 * updates, gently); a desired Flexible that Play won't allow falls back to None (never escalates
 * a routine update into a forced one).
 *
 * Pure and deterministic — unit-tested on the JVM with no Android/Play dependencies.
 */
object UpdatePolicy {

    const val PRIORITY_IMMEDIATE_ALWAYS = 5
    const val PRIORITY_IMMEDIATE_WHEN_AGED = 4
    const val STALENESS_AGED_HIGH = 3
    const val STALENESS_LOWEST = 14

    fun decide(
        priority: Int,
        stalenessDays: Int?,
        immediateAllowed: Boolean,
        flexibleAllowed: Boolean,
    ): UpdateDecision {
        val staleness = stalenessDays ?: 0

        val desired = when {
            priority >= PRIORITY_IMMEDIATE_ALWAYS -> UpdateDecision.IMMEDIATE
            priority == PRIORITY_IMMEDIATE_WHEN_AGED ->
                if (staleness >= STALENESS_AGED_HIGH) UpdateDecision.IMMEDIATE else UpdateDecision.FLEXIBLE
            priority in 1 until PRIORITY_IMMEDIATE_WHEN_AGED -> UpdateDecision.FLEXIBLE
            else -> if (staleness >= STALENESS_LOWEST) UpdateDecision.FLEXIBLE else UpdateDecision.NONE
        }

        return when (desired) {
            UpdateDecision.IMMEDIATE -> when {
                immediateAllowed -> UpdateDecision.IMMEDIATE
                flexibleAllowed -> UpdateDecision.FLEXIBLE
                else -> UpdateDecision.NONE
            }
            UpdateDecision.FLEXIBLE -> if (flexibleAllowed) UpdateDecision.FLEXIBLE else UpdateDecision.NONE
            UpdateDecision.NONE -> UpdateDecision.NONE
        }
    }
}
