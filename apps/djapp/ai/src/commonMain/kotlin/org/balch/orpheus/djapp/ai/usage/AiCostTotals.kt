package org.balch.orpheus.djapp.ai.usage

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.ai.usage.AiTurnSink
import org.balch.orpheus.features.ai.usage.AiTurnUsage

/**
 * Estimated USD the in-app AI has spent, for the chat header and the Mixer title.
 * [sessionByModel] counts this app run's turns by model id, on every platform. [songs] is each vibe's all-time
 * total by name: the desktop and Android ledger seeds it from `turns.jsonl` and adds each turn; iOS has no ledger
 * yet, so there it stays empty.
 */
@SingleIn(FeatureScope::class)
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<AiTurnSink>())
class AiCostTotals : AiTurnSink {
    private val _songs = MutableStateFlow<Map<String, Double>>(emptyMap())
    val songs: StateFlow<Map<String, Double>> = _songs.asStateFlow()

    private val _sessionByModel = MutableStateFlow<Map<String, Double>>(emptyMap())
    val sessionByModel: StateFlow<Map<String, Double>> = _sessionByModel.asStateFlow()

    override fun record(turn: AiTurnUsage) {
        turn.costUsd?.let { usd -> _sessionByModel.update { it.plusUsd(turn.modelId, usd) } }
    }

    fun seedSongs(totals: Map<String, Double>) = _songs.update { current ->
        // A turn added before the seed landed stays on top of the file's totals.
        (totals.keys + current.keys).associateWith { (totals[it] ?: 0.0) + (current[it] ?: 0.0) }
    }

    fun addSong(song: String, usd: Double) = _songs.update { it.plusUsd(song, usd) }

    private fun Map<String, Double>.plusUsd(key: String, usd: Double) = this + (key to ((this[key] ?: 0.0) + usd))
}
