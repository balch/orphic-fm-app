package org.balch.orpheus.djapp.vibeinfo

data class VibeInfoSection(
    val name: String,
    val isNowPlaying: Boolean,
    val isPast: Boolean,
)

data class VibeInfoTrack(
    /** Human label from PULSAR_TRACK_NAMES: "KICK", "PERC", "HIHAT", … */
    val label: String,
    /** Role display string: "Drums/Perc", "Lead/Melodic", or "Chords". */
    val role: String,
    /** Display name of the currently active engine (energy-selected). */
    val instrument: String,
    /** True when this track's peak audio level exceeds the threshold. */
    val isPlaying: Boolean,
)

data class VibeInfoUiModel(
    val name: String,
    val bpm: Int,
    val keyName: String,
    val scaleName: String,
    val sections: List<VibeInfoSection>,
    val tracks: List<VibeInfoTrack>,
    /** (reverbSize * 100).toInt() */
    val reverbPct: Int,
    /** (delayFeedback * 100).toInt() */
    val delayPct: Int,
)
