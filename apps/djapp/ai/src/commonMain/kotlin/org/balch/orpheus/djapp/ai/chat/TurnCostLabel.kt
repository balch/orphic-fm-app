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
