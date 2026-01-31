package org.balch.orpheus.core.audio.dsp.lv2

/**
 * Interface for plugins that expose semantic control to AI agents.
 */
interface AiControllable {
    /**
     * Returns a natural language description of what this plugin allows the AI to do.
     * e.g. "Controls the delay time and feedback. Use 'feedback' to create infinite loops."
     */
    fun getAgentInstructions(): String

    /**
     * Returns a map of parameter names (symbols) to their current values and descriptions.
     */
    fun getParameterState(): Map<String, Float>

    /**
     * Allows the AI to set a parameter by symbol.
     */
    fun setParameter(symbol: String, value: Float)
}
