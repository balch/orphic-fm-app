package org.balch.orpheus.features.ai

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Type of composition to start.
 */
enum class CompositionType {
    /** Full autonomous composition with melodic solos and evolving sound */
    SOLO,
    /** Background ambient drone layer */
    DRONE,
    /** user directed composition */
    USER_PROMPTED,
}

/**
 * Event requesting a mode change (start a composition).
 */
sealed class ModeChangeEvent {
    /**
     * Request to start a composition (Solo or Drone mode).
     * 
     * @param type The type of composition to start
     * @param userRequest The user's original request (e.g., "create a song named Midnight Dreams")
     * @param songName Optional name for the song/composition
     * @param moodName Optional mood name (for Solo mode, matches a Mood in SoloAgentConfig)
     * @param customPrompt Optional custom prompt to inject as initial direction
     */
    data class StartComposition(
        val type: CompositionType,
        val userRequest: String,
        val songName: String? = null,
        val moodName: String? = null,
        val customPrompt: String? = null
    ) : ModeChangeEvent()

    /**
     * Request to stop the current composition and return to chat mode.
     */
    data object StopComposition : ModeChangeEvent()
}

/**
 * Event bus for mode change requests.
 * 
 * This allows AI tools (used by OrpheusAgent) to request composition mode changes,
 * which are then handled by AiOptionsViewModel.
 */
@SingleIn(AppScope::class)
class ModeChangeEventBus @Inject constructor() {

    private val log = logging("ModeChangeEventBus")

    private val _events = MutableSharedFlow<ModeChangeEvent>(
        replay = 1,  // Replay last event for late subscribers
        extraBufferCapacity = 5
    )

    /**
     * Flow of mode change events.
     */
    val events: SharedFlow<ModeChangeEvent> = _events.asSharedFlow()

    /**
     * Request to start a composition.
     */
    suspend fun startComposition(
        type: CompositionType,
        userRequest: String,
        songName: String? = null,
        moodName: String? = null,
        customPrompt: String? = null
    ) {
        log.debug { "ModeChangeEventBus: START ${type.name} - '$userRequest'" }
        _events.emit(
            ModeChangeEvent.StartComposition(
                type = type,
                userRequest = userRequest,
                songName = songName,
                moodName = moodName,
                customPrompt = customPrompt
            )
        )
    }

    /**
     * Request to stop the current composition.
     */
    suspend fun stopComposition() {
        log.debug { "ModeChangeEventBus: STOP composition" }
        _events.emit(ModeChangeEvent.StopComposition)
    }
}
