package org.balch.orpheus.djapp.ai.chat

import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.features.ai.usage.AiTurnUsage
import kotlin.math.roundToLong

/** The muted line under a reply, e.g. "≈ $0.042 · 52.1k in (48.0k cached) · 1.2k out · Opus 5.5 · 3 calls". */
internal fun turnCostLabel(u: AiTurnUsage): String = buildString {
    append(u.costUsd?.let { "≈ ${usd(it)}" } ?: "≈ ? (no price)")
    val t = u.tokens
    append(" · ").append(tokens(t.promptTokens)).append(" in")
    if (t.cacheReadTokens > 0) append(" (").append(tokens(t.cacheReadTokens)).append(" cached)")
    append(" · ").append(tokens(t.outputTokens)).append(" out")
    append(" · ").append(AiModel.entries.firstOrNull { it.llmModel.id == u.modelId }?.displayName ?: u.modelId)
    if (u.calls > 1) append(" · ").append(u.calls).append(" calls")
}

/**
 * The header's cost after the model name: what [modelId] has spent since launch, plus the total across models once
 * another model has spent too, e.g. " · $0.210 · total $0.380". Null before anything is spent.
 */
internal fun sessionCostLabel(byModel: Map<String, Double>, modelId: String): String? {
    if (byModel.isEmpty()) return null
    val mine = byModel[modelId]
    val total = byModel.values.sum()
    return buildString {
        if (mine != null) append(" · ").append(usd(mine))
        if (byModel.keys.any { it != modelId }) append(" · total ").append(usd(total))
    }
}

/** Cents get three places, fractions of a cent four, so a cheap Haiku turn never reads as $0.000. */
internal fun usd(v: Double): String {
    val places = if (v < 0.01) 4 else 3
    var scale = 1L
    repeat(places) { scale *= 10 }
    val n = (v * scale).roundToLong()
    return "$" + (n / scale) + "." + (n % scale).toString().padStart(places, '0')
}

internal fun tokens(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> oneDecimal(n / 1_000.0) + "k"
    else -> oneDecimal(n / 1_000_000.0) + "M"
}

private fun oneDecimal(v: Double): String {
    val tenths = (v * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}
