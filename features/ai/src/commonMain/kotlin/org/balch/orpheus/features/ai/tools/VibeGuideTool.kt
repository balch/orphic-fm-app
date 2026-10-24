package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.anonmalies.Anomaly
import org.balch.orpheus.features.pulsar.anonmalies.CrossfadeAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.CutAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.FilterAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.LickAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.ScratchAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.FogHornAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.StormAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.SwellAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.TapeAnomaly
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.anonmalies.VoidAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
import kotlin.math.roundToInt

/**
 * Render a macro or a dB trim to a single decimal place. commonMain has no String.format, so we use the
 * codebase's roundToInt idiom (cf. DistortionPanel's `(x * 100).roundToInt() / 100.0`).
 */
internal fun oneDecimal(f: Float): String = ((f * 10).roundToInt() / 10.0).toString()

/** Short catalog tag for an anomaly, e.g. "void" / "lick". Mirrors each type's @SerialName. */
internal fun anomalyTag(a: Anomaly): String = when (a) {
    is VoidAnomaly -> "void"
    is LickAnomaly -> "lick"
    is WahAnomaly -> "wah"
    is CrossfadeAnomaly -> "crossfade"
    is CutAnomaly -> "cut"
    is SwellAnomaly -> "swell"
    is TapeAnomaly -> "tape"
    is ScratchAnomaly -> "scratch"
    is FilterAnomaly -> "filter"
    is StormAnomaly -> "storm"
    is FogHornAnomaly -> "fogHorn"
}

/**
 * One catalog line per vibe, auto-derived from its data — no curated blurbs. Segments are omitted
 * when the underlying field is absent (no arrangement / band / lick). The lines are ideas to borrow
 * from; new vibes start from a cast, never from one of these.
 */
internal fun vibeFingerprint(vibe: Vibe): String {
    val parts = buildList {
        add("${vibe.bpm.toInt()}bpm")
        add("${vibe.rootNote.name} ${vibe.scaleType.name}")
        add(vibe.genre.progressionStyle.name)
        add("swing ${(vibe.genre.swingAmount * 100).toInt()}%")
        add(
            "energy ${oneDecimal(vibe.energy)} complexity ${oneDecimal(vibe.complexity)} " +
                "space ${oneDecimal(vibe.space)} mood ${oneDecimal(vibe.mood)}",
        )
        // A catalog vibe's loudness trim, so the agent sees that shipped vibes are levelled.
        if (vibe.levelDb != 0f) add("level ${oneDecimal(vibe.levelDb)} dB")
        add("engines [" + vibe.tracks.map { it.engineEdm.engineId.name }.distinct().joinToString("/") + "]") // slash-separated distinct engine ids, e.g. BD/SD/HH
        vibe.arrangement?.let { add("${it.sections.size} sections") }
        vibe.band?.let { add("band(${it.members.size})") }
        if (vibe.lick != null) add("lick")
        vibe.lickRotation?.let { add("lick-rotation(${it.pool.size})") }
        if (vibe.anomalies.isNotEmpty()) {
            add("anomalies[" + vibe.anomalies.joinToString("/") { anomalyTag(it) } + "]")
        }
    }
    return "${vibe.name} — " + parts.joinToString(" · ")
}

/** The dynamic menu: a short header plus one bulleted fingerprint per injected vibe. */
internal fun catalogSection(vibes: List<Vibe>): String {
    val header = "Shipped vibes, for ideas only, never a template:"
    return header + "\n" + vibes.joinToString("\n") { "- " + vibeFingerprint(it) }
}

/** The full guide string the tool returns: static composition guide, then the live vibe catalog. */
internal suspend fun buildGuide(vibes: List<Vibe>): String =
    loadStaticGuide().trim() + "\n\n" + catalogSection(vibes)

@LLMDescription("Arguments for fetching the Pulsar vibe guide. Takes no input — pass an empty object.")
@Serializable
class VibeGuideArgs

@LLMDescription("How Pulsar vibe controls shape the sound, feel->settings recipes, and a catalog of existing vibes.")
@Serializable
data class VibeGuideResult(
    @property:LLMDescription("The composition guide plus a catalog of the shipped vibes, for ideas only. Read it, then call pulsar_cast_vibe.")
    val guide: String,
)

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class VibeGuideTool(
    // Lazy provider, not a direct PulsarFeature — see VibeReadTool: an eager inject closes the
    // Pulsar -> PlaybackController -> AiOptionsViewModel -> OrpheusAgent -> tools -> Pulsar cycle.
    private val pulsarFeatureProvider: () -> PulsarFeature,
) : ToolProvider {

    override val tool by lazy {
        object : Tool<VibeGuideArgs, VibeGuideResult>(
            argsType = typeToken<VibeGuideArgs>(),
            resultType = typeToken<VibeGuideResult>(),
            name = "pulsar_vibe_guide",
            description = """
                Call this FIRST when asked to make or change a Pulsar beat-machine vibe. It returns how
                each control shapes the sound, feel->settings recipes, and a catalog of the shipped
                vibes with their key/tempo/progression/engines. Use it to learn the controls; the
                catalog is for ideas only. Then call pulsar_cast_vibe for a new vibe's skeleton.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: VibeGuideArgs): VibeGuideResult =
                VibeGuideResult(buildGuide(pulsarFeatureProvider().vibeList))
        }
    }
}
