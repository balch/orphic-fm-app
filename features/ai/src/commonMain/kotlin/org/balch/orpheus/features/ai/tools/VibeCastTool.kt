package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.features.ai.composer.CastMode
import org.balch.orpheus.features.ai.composer.CastPin
import org.balch.orpheus.features.ai.composer.CastRequest
import org.balch.orpheus.features.ai.composer.STYLE_TAGS
import org.balch.orpheus.features.ai.composer.VibeCaster
import kotlin.random.Random

@LLMDescription("Holds one track of the cast to a part and/or an EDM engine.")
@Serializable
data class PinArg(
    @property:LLMDescription("Track index 0-7.")
    val track: Int,
    @property:LLMDescription("Optional part: kick, snare, hat, perc, bass, lead, comp, pad or texture.")
    val role: String? = null,
    @property:LLMDescription("Optional OrpheusEngineId name for the EDM voice, e.g. VOWEL_FOF.")
    val engine: String? = null,
)

@LLMDescription("Arguments for casting a new Pulsar vibe skeleton.")
@Serializable
data class VibeCastArgs(
    @property:LLMDescription("BED_FIXED or ALL_ROLLED. Omit to use the user's saved casting mode.")
    val mode: String? = null,
    @property:LLMDescription("Seed for the roll. Omit for a random one; the same seed and arguments rebuild the same skeleton.")
    val seed: Long? = null,
    @property:LLMDescription("Optional style tags: rock, electro, ambient, reggae, psych, funk.")
    val styleTags: List<String>? = null,
    @property:LLMDescription("Optional pins that hold tracks to a part or engine while the rest are rolled.")
    val pins: List<PinArg>? = null,
)

@LLMDescription("A cast vibe skeleton, or the reason the cast was refused.")
@Serializable
data class VibeCastResult(
    @property:LLMDescription("True if a skeleton was cast.")
    val ok: Boolean,
    @property:LLMDescription("Status, or the exact reason the request was refused so you can fix it.")
    val message: String,
    @property:LLMDescription("The seed used. Report it to the user.")
    val seed: Long = 0,
    @property:LLMDescription("The casting mode used.")
    val mode: String = "",
    @property:LLMDescription("One line per track. Report it to the user.")
    val summary: List<String> = emptyList(),
    @property:LLMDescription("The skeleton as vibe JSON: edit it, then pass it to pulsar_apply_vibe.")
    val vibeJson: String = "",
)

private fun refuse(message: String) = VibeCastResult(ok = false, message = message)

/** The skeleton without its default values: about half the size, and [vibeApplyJson] decodes it back the same. */
internal val vibeCastJson = Json(from = vibeApplyJson) { encodeDefaults = false }

/** Validates [args] against [savedMode] and casts; every refusal names the valid set. */
internal fun castVibe(args: VibeCastArgs, savedMode: String?, rollSeed: () -> Long = { Random.nextLong() }): VibeCastResult {
    val modes = CastMode.entries.joinToString(" or ") { it.name }
    val mode = if (args.mode != null) {
        CastMode.entries.firstOrNull { it.name.equals(args.mode.trim(), ignoreCase = true) }
            ?: return refuse("Unknown mode '${args.mode}'; use $modes.")
    } else {
        CastMode.entries.firstOrNull { it.name == savedMode } ?: CastMode.BED_FIXED
    }
    val tags = args.styleTags.orEmpty().map { it.trim().lowercase() }
    val unknown = tags.filter { it !in STYLE_TAGS }
    if (unknown.isNotEmpty()) {
        return refuse("Unknown style tags $unknown; use any of ${STYLE_TAGS.sorted()}.")
    }
    val pins = args.pins.orEmpty().map { p ->
        val engine = p.engine?.let { name ->
            OrpheusEngineId.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                ?: return refuse("Pin on track ${p.track}: '$name' is not an engine id.")
        }
        CastPin(p.track, p.role, engine)
    }
    val seed = args.seed ?: rollSeed()
    return try {
        val cast = VibeCaster.cast(CastRequest(mode, seed, tags.toSet(), pins))
        VibeCastResult(
            ok = true,
            message = "Cast ${cast.mode.name} with seed ${cast.seed}. Edit this skeleton (name, frame, arrangement, tweaks) and apply it.",
            seed = cast.seed,
            mode = cast.mode.name,
            summary = cast.summary,
            vibeJson = vibeCastJson.encodeToString(cast.vibe),
        )
    } catch (e: IllegalArgumentException) {
        refuse(e.message ?: "The cast request was refused.")
    } catch (e: IllegalStateException) {
        refuse(e.message ?: "The cast request was refused.")
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class VibeCastTool(
    private val preferences: AppPreferencesRepository,
) : ToolProvider {

    override val tool by lazy {
        object : Tool<VibeCastArgs, VibeCastResult>(
            argsType = typeToken<VibeCastArgs>(),
            resultType = typeToken<VibeCastResult>(),
            name = "pulsar_cast_vibe",
            description = """
                Call this FIRST for any new vibe, before pulsar_apply_vibe. It rolls an 8-track
                skeleton (voices, roles, registers, levels) that already obeys the theory rules.
                The skeleton is your starting point: edit it, do not start from a shipped vibe.
                Report the seed and the per-track summary to the user. The same seed rebuilds the
                same skeleton. Omit mode to use the user's saved casting mode.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: VibeCastArgs): VibeCastResult =
                castVibe(args, preferences.load().vibeCastMode)
        }
    }
}
