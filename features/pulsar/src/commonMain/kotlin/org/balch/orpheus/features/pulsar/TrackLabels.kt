package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.audio.FmPatchNames
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.Vibe

/** Display label for one Pulsar track: its fixed [name] and the live [instrument]. */
data class TrackLabel(val name: String, val instrument: String)

/**
 * Label for [track], using the engine the DSP is actually playing ([activeEngineId]) when it
 * matches one of the track's two slots, else EDM at `energy >= 0.5f`, otherwise Space.
 */
fun trackLabel(vibe: Vibe, track: Int, activeEngineId: Int, energy: Float): TrackLabel {
    val voice = vibe.tracks[track]
    val engine: OrpheusEngine = when (activeEngineId) {
        voice.engineEdm.engineId.id -> voice.engineEdm
        voice.engineSpace.engineId.id -> voice.engineSpace
        else -> if (energy >= 0.5f) voice.engineEdm else voice.engineSpace
    }
    // SixOp FM engines show their patch name ("Xylophone", "Br trumpet") instead of the bank name.
    return TrackLabel(
        name = PULSAR_TRACK_NAMES[track],
        instrument = FmPatchNames.patchNameFor(engine.engineId, engine.harmonics)
            ?: engine.engineId.displayName,
    )
}

/** Labels for every track of [vibe]; a missing entry in [activeEngines] counts as unset (-1). */
fun trackLabels(vibe: Vibe, activeEngines: List<Int>, energy: Float): List<TrackLabel> =
    vibe.tracks.indices.map { i -> trackLabel(vibe, i, activeEngines.getOrElse(i) { -1 }, energy) }
