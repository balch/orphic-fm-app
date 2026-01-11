package org.balch.orpheus.core.controller

import org.balch.orpheus.core.routing.ControlEvent

/**
 * Interface for plugins that extend SynthController functionality.
 * Plugins can handle specialized control logic, routing, or event processing.
 */
interface SynthControllerPlugin {
    /**
     * Unique identifier for this plugin.
     */
    val id: String

    /**
     * Handle a control change event.
     * Returns true if the event was handled by this plugin.
     */
    fun onControlChange(event: ControlEvent): Boolean = false

    /**
     * Handle a pulse start event for a voice.
     */
    fun onPulseStart(voiceIndex: Int) {}

    /**
     * Handle a pulse end event for a voice.
     */
    fun onPulseEnd(voiceIndex: Int) {}
}
