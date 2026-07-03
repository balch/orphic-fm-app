package org.balch.orpheus.djapp.vibeinfo

import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.features.pulsar.PULSAR_NOTE_NAMES
import org.balch.orpheus.features.pulsar.PULSAR_SCALE_NAMES
import org.balch.orpheus.features.pulsar.PULSAR_TRACK_NAMES
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.Vibe

/**
 * Maps a [Vibe] and live playback state into a [VibeInfoUiModel] suitable for display.
 *
 * This is a pure function — no side effects, no coroutines, easily unit-testable.
 *
 * @param vibe              The active vibe (beat machine preset).
 * @param arrangement       Live arrangement state: current section index, bars elapsed, etc.
 * @param viz               Live visualization data: per-track audio levels, playhead, etc.
 * @param energy            Current energy macro value (0..1). Fallback selector when the live
 *                          active-engine id is unavailable: engineEdm (≥ 0.5) or engineSpace (< 0.5).
 *                          The live [PulsarVizData.activeEngines] id (what the DSP is actually
 *                          playing) is preferred when it matches one of the track's two slots.
 * @param trackLevelThreshold  Minimum peak audio level for a track to be considered "playing".
 *                          Default 0.02f.
 */
fun mapVibeInfo(
    vibe: Vibe,
    arrangement: PulsarArrangementState,
    viz: PulsarVizData,
    energy: Float,
    trackLevelThreshold: Float = 0.02f,
): VibeInfoUiModel {
    val sections = vibe.arrangement?.sections?.mapIndexed { index, section ->
        VibeInfoSection(
            name = section.name,
            isNowPlaying = index == arrangement.sectionIndex,
            isPast = index < arrangement.sectionIndex,
        )
    } ?: emptyList()

    val tracks = vibe.tracks.mapIndexed { i, trackVoice ->
        // Prefer the live active-engine id the DSP is actually playing; only fall back
        // to the energy threshold when it doesn't match either of the track's slots
        // (e.g. -1 unset, or short/default activeEngines array).
        val liveEngineId = viz.activeEngines.getOrElse(i) { -1 }
        val activeEngine = when (liveEngineId) {
            trackVoice.engineEdm.engineId.id -> trackVoice.engineEdm
            trackVoice.engineSpace.engineId.id -> trackVoice.engineSpace
            else -> if (energy >= 0.5f) trackVoice.engineEdm else trackVoice.engineSpace
        }
        VibeInfoTrack(
            label = PULSAR_TRACK_NAMES[i],
            role = when (trackVoice.role) {
                is TrackRole.Percussive -> "Drums/Perc"
                is TrackRole.Melodic   -> "Lead/Melodic"
                is TrackRole.Chordal   -> "Chords"
            },
            // SixOp FM engines (DX/DX2/DX3) load a specific patch selected by `harmonics` —
            // show that patch name ("Xylophone", "Br trumpet", …) instead of the generic bank name.
            instrument = FmPatchNames.patchNameFor(activeEngine.engineId, activeEngine.harmonics)
                ?: activeEngine.engineId.displayName,
            isPlaying = viz.trackLevels.getOrElse(i) { 0f } > trackLevelThreshold,
        )
    }

    return VibeInfoUiModel(
        name = vibe.name,
        bpm = vibe.bpm.toInt(),
        keyName = PULSAR_NOTE_NAMES[vibe.rootNote.noteIndex],
        scaleName = PULSAR_SCALE_NAMES[vibe.scaleType.scaleIndex],
        sections = sections,
        tracks = tracks,
        reverbPct = (vibe.effects.reverbSize * 100).toInt(),
        delayPct = (vibe.effects.delayFeedback * 100).toInt(),
    )
}
