package org.balch.orpheus.djapp.ai.collab

import com.diamondedge.logging.logging
import org.balch.orpheus.features.pulsar.bench.VibeFieldChange
import org.balch.orpheus.features.pulsar.bench.diffVibe
import org.balch.orpheus.features.pulsar.bench.withMixerFieldsOf
import org.balch.orpheus.features.pulsar.models.Vibe

private val log = logging("CaptureVibeRows")

/**
 * The on vibe rows as field changes against [file], without the mixer-owned fields (a whole-container row can carry
 * them; the mix rows hold those). Empty, warned, when the rows no longer fit [file].
 */
internal fun MixHistory.captureVibeRows(file: Vibe): List<VibeFieldChange> =
    try {
        diffVibe(file, effective(file).withMixerFieldsOf(file))
    } catch (e: Exception) {
        log.warn(e) { "vibe rows no longer fit the file; capturing none" }
        emptyList()
    }
