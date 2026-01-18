package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.ai.CompositionType
import org.balch.orpheus.features.ai.ModeChangeEventBus

/**
 * Arguments for starting a composition (song, drone, or jam session).
 * 
 * Each subclass represents a different way the user might want to start music.
 */
@Serializable
@LLMDescription("Arguments for starting a musical composition or jam session.")
data class StartCompositionArgs(

    @SerialName("name")
    @param:LLMDescription("Name for the song.")
    val name: String? = null,

    @SerialName("mood")
    @param:LLMDescription("Mood or style for the composition")
    val mood: String? = null,

    @SerialName("custom_direction")
    @param:LLMDescription("Optional custom direction")
    val customDirection: String? = null,

    @SerialName("atmosphere")
    @param:LLMDescription("Atmosphere description")
    val atmosphere: String? = null,

    @SerialName("song_reference")
    @param:LLMDescription("The song or style reference.")
    val songReference: String? = null,
)
/**
 * Result of starting a composition.
 */
@Serializable
data class StartCompositionResult(
    val success: Boolean,
    val message: String,
    val compositionType: String? = null,
    val songName: String? = null
)

/**
 * Tool for starting musical compositions (Solo or Drone mode).
 * 
 * This tool allows the OrpheusAgent to seamlessly transition from chat mode
 * to creating music based on user requests like:
 * - "Create a song named Midnight Dreams"
 * - "Let's jam"
 * - "Play me something relaxing"
 * - "Start a drone background"
 */
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
class StartCompositionTool @Inject constructor(
    private val modeChangeEventBus: ModeChangeEventBus
) : Tool<StartCompositionArgs, StartCompositionResult>(
    argsSerializer = StartCompositionArgs.serializer(),
    resultSerializer = StartCompositionResult.serializer(),
    name = "start_composition",
    description = """
        Start a musical composition or jam session. Use this tool when the user wants to:
        - Create a new song or composition
        - Start jamming or improvising
        - Have background ambient/drone music
        - Hear something specific played on the synth
        
        This will switch to Dashboard mode and start the AI composer.
        Choose the appropriate action based on user intent.
    """.trimIndent()
) {
    private val log = logging("StartCompositionTool")

    override suspend fun execute(args: StartCompositionArgs): StartCompositionResult {
        log.debug { "Starting composition: $args" }
        val songName = args.name ?: "Untitled Composition"
        val userRequest = buildString {
            append("Create a song")
            if (args.name != null) append(" named '${args.name}'")
            if (args.mood != null) append(" in the style of '${args.mood}'")
            if (args.customDirection != null) append(". Additional direction: ${args.customDirection}")
            if (args.songReference != null) append(" based on '${args.songReference}'")
            if (args.atmosphere != null) append(" with ${args.atmosphere} atmosphere")
        }

        modeChangeEventBus.startComposition(
            type = CompositionType.USER_PROMPTED,
            userRequest = userRequest,
            songName = songName,
            moodName = args.mood,
            customPrompt = args.customDirection
        )

        return StartCompositionResult(
            success = true,
            message = "Starting composition: $songName. Switching to Dashboard mode...",
            compositionType = "Solo",
            songName = songName
        )
    }
}
