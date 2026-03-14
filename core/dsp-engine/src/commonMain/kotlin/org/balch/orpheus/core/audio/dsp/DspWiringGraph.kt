package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Manages the static wiring of the DSP graph.
 *
 * Dead code — C++ handles all audio routing. Pending deletion in a later task.
 */
@SingleIn(AppScope::class)
@Inject
class DspWiringGraph(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory,
    private val pluginProvider: DspPluginProvider
) {
    private val log = logging("DspWiringGraph")

    fun initialize(voiceManager: DspVoiceManager) {
        log.debug { "DspWiringGraph.initialize() — no-op, C++ handles all audio routing" }
        // Plugins still need initialize() called for state setup
        pluginProvider.plugins.forEach { it.initialize() }
    }
}
