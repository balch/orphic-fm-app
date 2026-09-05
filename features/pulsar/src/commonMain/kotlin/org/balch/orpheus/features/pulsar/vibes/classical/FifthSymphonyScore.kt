package org.balch.orpheus.features.pulsar.vibes.classical

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.NotatedScore
import org.balch.orpheus.features.pulsar.models.NotatedScoreProvider
import org.jetbrains.compose.resources.ExperimentalResourceApi
import orpheus.features.pulsar.generated.resources.Res

/**
 * The Fifth's four-note motif, played from the actual written notes instead of Pulsar's
 * generative lick engine. Source: Mutopia Project's public-domain, LilyPond-engraved
 * Symphony No. 5 (`Symphony5_1.mid`), MIDI track 12 (contrabass) -- the one track in that
 * file that is genuinely monophonic (meanPolyphony 1.00), so it survives phase A's
 * one-note-at-a-time scheduler. Transposed +24 (two octaves, key-preserving since the
 * piece and `FifthSymphonyVibe` both sit in C minor) and truncated to the first 160 beats,
 * which is what keeps the line inside track 4's 55..79 note-range window. See
 * `scores/source/fifth-lead.json` for the mapping and full provenance.
 *
 * `name` is the resolution key `Score.notatedScoreId` matches against -- deliberately not
 * shared with `FifthSymphonyVibe.name`, since the two are looked up through different
 * catalogs and giving them the same string would make debugging a lookup miss ambiguous.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<NotatedScoreProvider>())
class FifthSymphonyScore : NotatedScoreProvider {
    override val name = "fifth-lead"

    private var cached: NotatedScore? = null

    @OptIn(ExperimentalResourceApi::class)
    override suspend fun score(): NotatedScore = cached ?: run {
        val bytes = Res.readBytes("files/scores/fifth-lead.json")
        json.decodeFromString<NotatedScore>(bytes.decodeToString()).also { cached = it }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
