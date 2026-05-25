package org.balch.orpheus.core.preferences

import kotlinx.serialization.Serializable
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle

@Serializable
data class AppPreferences(
    val lastVizId: String? = null,
    val lastPresetName: String? = null,
    /** User-provided API keys. Maps provider ID (e.g., "google") to API key. */
    val userApiKeys: Map<String, String> = emptyMap(),
    /** Selected AI model ID (e.g., "flash_25", "pro_25"). */
    val selectedAiModel: String? = null,
    /** Per-panel signal visualization toggle (disabled by default). */
    val signalVizEnabled: Boolean = false,
    /** Master switch for Pulsar song-ending behavior (auto-end + advance). */
    val pulsarSongEndingEnabled: Boolean = true,
    /**
     * Default song-to-song transition for Pulsar. Overridden per-vibe via
     * `Arrangement.transitionOut` when authored. Default: TAPE with style default handoff.
     */
    val pulsarTransitionDefault: TransitionSpec = TransitionStyle.default,
    /** Serialized DJ UI state JSON for cross-session persistence. */
    val lastDjJson: String? = null,
    /** Serialized Pulsar UI state JSON for cross-session persistence. */
    val lastPulsarJson: String? = null,
    /** Serialized panel expansion overrides: Map of panelId -> expanded. */
    val lastExpandedPanelsJson: String? = null,
    /** Serialized effect UI states for DJ app cross-session persistence. */
    val lastTimerJson: String? = null,
    val lastReverbJson: String? = null,
    val lastHornJson: String? = null,
    val lastDistortionJson: String? = null,
    val lastMixerJson: String? = null,
    /** When true, a new random visualization is selected on each vibe transition. */
    val randomVizMode: Boolean = true,
)


