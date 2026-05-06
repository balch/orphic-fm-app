package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * A "band member" — a named group of tracks that solo together.
 * @param name Display name (e.g., "Rhythm Section", "Keys", "FX").
 * @param tracks Track indices this member controls (e.g., [0,1,2] for drums).
 * @param alwaysActive If true, this member never ducks (e.g., drums keep playing).
 * @param loudness Relative output level for this member, 0-1.
 * @param creativity How much this member varies from the base pattern, 0-1.
 * @param swing Timing swing amount for this member, 0-1.
 * @param drag Timing drag (behind the beat) for this member, 0-1.
 */
@Serializable
data class BandMember(
    val name: String,
    val tracks: List<Int>,
    val alwaysActive: Boolean = false,
    val loudness: Float = 0.5f,
    val creativity: Float = 0.5f,
    val swing: Float = 0.0f,
    val drag: Float = 0.0f,
)

/**
 * Build an NxN interaction matrix with labeled rows for readability.
 * Used for band member handoff and pull-in probabilities.
 *
 * ```
 * val handoff = bandMatrix(
 *     //          DRUM  BASS  KEYS  FX
 *     "Drummer" to row(0.00f, 0.35f, 0.35f, 0.10f),
 *     "Bassist" to row(0.25f, 0.00f, 0.40f, 0.15f),
 *     "Keys"    to row(0.20f, 0.35f, 0.00f, 0.20f),
 *     "FX"      to row(0.15f, 0.30f, 0.30f, 0.00f),
 * )
 * ```
 */
fun bandMatrix(vararg rows: Pair<String, List<Float>>): List<Float> {
    val n = rows.size
    val result = ArrayList<Float>(n * n)
    rows.forEach { (name, values) ->
        require(values.size == n) { "Row '$name' has ${values.size} values, expected $n" }
        result.addAll(values)
    }
    return result
}

fun row(vararg values: Float): List<Float> = values.toList()

/**
 * Build a 7x7 chord transition matrix with labeled rows.
 * Each row represents transition probabilities from one chord degree (I-VII)
 * to all others. Rows should sum to ~1.0.
 *
 * ```
 * val glacialDrift = chordMatrix(
 *     //    I     ii    iii   IV    V     vi    VII
 *     "I"   to row(0.70f, 0.05f, 0.02f, 0.05f, 0.03f, 0.05f, 0.10f),
 *     "ii"  to row(0.10f, 0.70f, 0.08f, 0.02f, 0.05f, 0.03f, 0.02f),
 *     ...
 * )
 * ```
 */
fun chordMatrix(vararg rows: Pair<String, List<Float>>): List<Float> {
    require(rows.size == 7) { "Chord matrix must have exactly 7 rows (I-VII), got ${rows.size}" }
    val result = ArrayList<Float>(49)
    rows.forEach { (name, values) ->
        require(values.size == 7) { "Row '$name' has ${values.size} values, expected 7" }
        result.addAll(values)
    }
    return result
}

/**
 * A reusable band definition — the cast of characters for a vibe.
 * Defines members, their track assignments, and interaction matrices.
 * Referenced by [Vibe.band], used by [SoloMode] sections in the arrangement.
 *
 * @param members The band members (named track groups with personality traits).
 * @param handoffMatrix NxN Markov matrix for lead handoff probabilities. Build with [bandMatrix].
 * @param pullInMatrix NxN matrix for pull-in probabilities. Build with [bandMatrix].
 * @param pullInBarsMin Minimum bars a pull-in lasts.
 * @param pullInBarsMax Maximum bars a pull-in lasts.
 * @param barsPerLeadMin Minimum bars each lead member plays before handoff.
 * @param barsPerLeadMax Maximum bars each lead member plays before handoff.
 */
@Serializable
data class Band(
    val members: List<BandMember>,
    val handoffMatrix: List<Float>,
    val pullInMatrix: List<Float>,
    val pullInBarsMin: Int = 2,
    val pullInBarsMax: Int = 4,
    val barsPerLeadMin: Int = 2,
    val barsPerLeadMax: Int = 6,
)
