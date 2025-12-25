package org.balch.orpheus

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.util.Logger

/**
 * Orchestrates the lifecycle of the synthesizer engine.
 * 
 * Manages engine start/stop to ensure proper audio lifecycle management
 * independent of UI composition.
 */
@SingleIn(AppScope::class)
@Inject
class SynthOrchestrator(
    private val engine: SynthEngine
) {
    private var isStarted = false

    fun start() {
        if (!isStarted) {
            engine.start()
            isStarted = true
            Logger.info { "SynthOrchestrator: Engine started" }
        }
    }

    fun stop() {
        if (isStarted) {
            engine.stop()
            isStarted = false
            Logger.info { "SynthOrchestrator: Engine stopped" }
        }
    }

    val peakFlow get() = engine.peakFlow
}
