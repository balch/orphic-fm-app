package org.balch.orpheus.core.controller

import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue

/**
 * Save-on-first-write state manager for plugin controls.
 *
 * Captures the pre-modification value of each control the first time it is
 * written during a session (e.g., REPL playback, camera gesture tracking).
 * When the session ends, [restore] replays the original values, returning the
 * synth to its pre-session state.
 *
 * Thread-safety: callers are responsible for confining access to a single
 * coroutine / thread (both TidalScheduler and MediaPipeViewModel already do).
 */
class ControlStateSnapshot(
    private val synthController: SynthController,
) {
    private val saved = mutableMapOf<PluginControlId, PortValue>()

    /**
     * Set a plugin control, saving its current value on first write.
     */
    fun setPluginControl(id: PluginControlId, value: PortValue, origin: ControlEventOrigin) {
        if (id !in saved) {
            saved[id] = synthController.getPluginControl(id) ?: PortValue.FloatValue(0f)
        }
        synthController.setPluginControl(id, value, origin)
    }

    /**
     * Restore all controls to their pre-session values and clear the snapshot.
     */
    fun restore(origin: ControlEventOrigin) {
        for ((id, value) in saved) {
            synthController.setPluginControl(id, value, origin)
        }
        saved.clear()
    }

    /** True when no controls have been captured yet. */
    val isEmpty: Boolean get() = saved.isEmpty()
}
