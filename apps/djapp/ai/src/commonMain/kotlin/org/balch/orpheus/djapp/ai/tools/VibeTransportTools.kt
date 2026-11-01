package org.balch.orpheus.djapp.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.djapp.ai.collab.DjVibeApplyGuard
import org.balch.orpheus.features.pulsar.PulsarFeature

@LLMDescription("The vibe catalog.")
@Serializable
data class VibeListResult(
    val ok: Boolean,
    val message: String,
    @property:LLMDescription("The vibe playing now.")
    val current: String = "",
    val vibes: List<String> = emptyList(),
)

@LLMDescription("Arguments for vibe_load.")
@Serializable
data class VibeLoadArgs(
    @property:LLMDescription("A vibe name from vibe_list, matched ignoring case.")
    val name: String,
)

@LLMDescription("The vibe now loaded.")
@Serializable
data class VibeLoadResult(
    val ok: Boolean,
    val message: String,
    val vibe: String? = null,
    @property:LLMDescription("WIPE_UNCONFIRMED when the load would clear the user's live mix: ask them, they confirm with a button.")
    val refusal: String? = null,
)

@LLMDescription("Arguments for transport.")
@Serializable
data class TransportArgs(
    @property:LLMDescription("play, pause or state.")
    val action: String,
)

@LLMDescription("Playback state after the action.")
@Serializable
data class TransportResult(
    val ok: Boolean,
    val message: String,
    @property:LLMDescription("playing, paused or stopped.")
    val state: String = "",
)

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class VibeListTool(private val pulsarFeatureProvider: () -> PulsarFeature) : ToolProvider {
    override val tool: Tool<NoArgs, VibeListResult> by lazy {
        object : Tool<NoArgs, VibeListResult>(
            argsType = typeToken<NoArgs>(),
            resultType = typeToken<VibeListResult>(),
            name = "vibe_list",
            description = "List the Pulsar vibe names vibe_load accepts, and the one playing now.",
        ) {
            override suspend fun execute(args: NoArgs): VibeListResult = guarded({ VibeListResult(false, it) }) {
                val pulsar = pulsarFeatureProvider()
                VibeListResult(true, "ok", pulsar.vibeFlow.value.name, pulsar.vibeNames)
            }
        }
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class VibeLoadTool(
    private val pulsarFeatureProvider: () -> PulsarFeature,
    private val dispatchers: DispatcherProvider,
    private val wipeGuard: DjVibeApplyGuard,
) : ToolProvider {
    override val tool: Tool<VibeLoadArgs, VibeLoadResult> by lazy {
        object : Tool<VibeLoadArgs, VibeLoadResult>(
            argsType = typeToken<VibeLoadArgs>(),
            resultType = typeToken<VibeLoadResult>(),
            name = "vibe_load",
            description = """
                Load a vibe by name (see vibe_list) at once, with no transition. Clears pins, mutes and solos; the
                History keeps each vibe's mix edits and brings them back when it loads again.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: VibeLoadArgs): VibeLoadResult = guarded({ VibeLoadResult(false, it) }) {
                val pulsar = pulsarFeatureProvider()
                val name = pulsar.vibeNames.firstOrNull { it.equals(args.name.trim(), ignoreCase = true) }
                    ?: badArg("no vibe named \"${args.name}\"; see vibe_list")
                wipeGuard.beforeApply()
                val loaded = runCatching { withContext(dispatchers.main) { pulsar.applyVibeByName(name) } }
                    .onFailure { wipeGuard.applyFailed() }
                    .getOrThrow()
                if (!loaded) {
                    wipeGuard.applyFailed()
                    badArg("vibe \"$name\" did not load")
                }
                VibeLoadResult(true, "Loaded \"$name\".", name)
            }
        }
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class TransportTool(
    // Lazy, like the feature: PlaybackController sits on the feature graph's DI path.
    private val playbackProvider: () -> PlaybackController,
    private val dispatchers: DispatcherProvider,
) : ToolProvider {
    override val tool: Tool<TransportArgs, TransportResult> by lazy {
        object : Tool<TransportArgs, TransportResult>(
            argsType = typeToken<TransportArgs>(),
            resultType = typeToken<TransportResult>(),
            name = "transport",
            description = "action=play starts playback, pause pauses it, state only reports it.",
        ) {
            override suspend fun execute(args: TransportArgs): TransportResult = guarded({ TransportResult(false, it) }) {
                val playback = playbackProvider()
                when (args.action.trim().lowercase()) {
                    "play" -> withContext(dispatchers.main) { playback.play() }
                    "pause" -> withContext(dispatchers.main) { playback.pause() }
                    "state" -> Unit
                    else -> badArg("action must be play, pause or state, not \"${args.action}\"")
                }
                val state = when (playback.state.value) {
                    PlaybackState.Playing -> "playing"
                    PlaybackState.Paused -> "paused"
                    PlaybackState.Stopped -> "stopped"
                }
                TransportResult(true, "ok", state)
            }
        }
    }
}
