package org.balch.orpheus.djapp.ai.usage

import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.ai.AiPrices
import org.balch.orpheus.core.ai.AiTokenUsage
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.djapp.ai.OrphicFiles
import org.balch.orpheus.features.ai.usage.AiCallSource
import org.balch.orpheus.features.ai.usage.AiTurnOutcome
import org.balch.orpheus.features.ai.usage.AiTurnSink
import org.balch.orpheus.features.ai.usage.AiTurnUsage
import org.balch.orpheus.features.pulsar.PulsarFeature
import java.io.File

/** One line of `ai-usage/turns.jsonl`: a turn, tagged with the vibe that played when it ended. */
@Serializable
data class AiCostRecord(
    val atEpochMs: Long,
    val song: String,
    val modelId: String,
    val calls: Int,
    val inputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
    val costUsd: Double?,
    val outcome: AiTurnOutcome,
    val source: AiCallSource = AiCallSource.AGENT,
    /** The [AiPrices] table [costUsd] came from, so a later price change can be told apart. */
    val pricesAsOf: String,
) {
    val tokens: AiTokenUsage get() = AiTokenUsage(inputTokens, cacheReadTokens, cacheWriteTokens, outputTokens)

    companion object {
        fun of(turn: AiTurnUsage, song: String) = AiCostRecord(
            turn.atEpochMs, song, turn.modelId, turn.calls,
            turn.tokens.inputTokens, turn.tokens.cacheReadTokens, turn.tokens.cacheWriteTokens, turn.tokens.outputTokens,
            turn.costUsd, turn.outcome, turn.source, AiPrices.AS_OF,
        )
    }
}

/**
 * One song on one model: what its agent [turns] and [ideasCalls] spent. [costUsd] covers both and leaves out the
 * unpriced rows; [ideasUsd] is the ideas share.
 */
@Serializable
data class SongModelCost(
    val song: String,
    val modelId: String,
    val turns: Int,
    val ideasCalls: Int,
    val unpricedRows: Int,
    val tokens: AiTokenUsage,
    val costUsd: Double,
    val ideasUsd: Double,
    /** The average priced agent turn, without the ideas. */
    val usdPerTurn: Double,
)

/** Rolls records up per song per model, costliest first. */
fun rollUp(records: List<AiCostRecord>): List<SongModelCost> =
    records.groupBy { it.song to it.modelId }.map { (key, rs) ->
        val (ideas, agent) = rs.partition { it.source == AiCallSource.IDEAS }
        val pricedAgent = agent.mapNotNull { it.costUsd }
        SongModelCost(
            song = key.first,
            modelId = key.second,
            turns = agent.size,
            ideasCalls = ideas.size,
            unpricedRows = rs.count { it.costUsd == null },
            tokens = rs.fold(AiTokenUsage()) { acc, r -> acc + r.tokens },
            costUsd = rs.sumOf { it.costUsd ?: 0.0 },
            ideasUsd = ideas.sumOf { it.costUsd ?: 0.0 },
            usdPerTurn = if (pricedAgent.isEmpty()) 0.0 else pricedAgent.sum() / pricedAgent.size,
        )
    }.sortedByDescending { it.costUsd }

/**
 * Appends every in-app agent turn to `turns.jsonl` under [OrphicFiles.root] (`~/.config/orpheus-dj/ai-usage/` on
 * desktop), so cost per song survives relaunch. Append-only: a crash loses at most the line being written.
 */
@SingleIn(FeatureScope::class)
@ContributesIntoSet(FeatureScope::class, binding = binding<AiTurnSink>())
class AiCostLedger internal constructor(
    private val file: File,
    private val song: () -> String,
) : AiTurnSink {
    // Lazy provider: injecting PulsarFeature eagerly would build it with the agent.
    @Inject
    constructor(files: OrphicFiles, pulsarProvider: () -> PulsarFeature) :
        this(File(files.root, "$DIR/$FILE"), { pulsarProvider().stateFlow.value.vibeName })

    private val log = logging("AiCostLedger")

    override fun record(turn: AiTurnUsage) {
        val record = AiCostRecord.of(turn, runCatching(song).getOrNull()?.ifBlank { null } ?: NO_SONG)
        runCatching {
            synchronized(this) {
                file.parentFile?.mkdirs()
                file.appendText(json.encodeToString(AiCostRecord.serializer(), record) + "\n")
            }
        }.onFailure { log.error(it) { "Could not record AI turn cost" } }
    }

    /** Every record, oldest first; a line that does not parse is skipped. */
    fun read(): List<AiCostRecord> = synchronized(this) {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { line ->
            line.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(AiCostRecord.serializer(), it) }.getOrNull() }
        }
    }

    companion object {
        const val DIR = "ai-usage"
        const val FILE = "turns.jsonl"
        const val NO_SONG = "(no vibe)"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
