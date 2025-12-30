package org.balch.orpheus.features.ai.generative

/**
 * Configuration for the SynthControlAgent.
 */
sealed interface SynthControlAgentConfig {
    val name: String
    val systemPrompt: String
    val initialPrompt: String
    val initialMoodPrompts: List<String>
    val evolutionIntervalMs: Long
    val throttleIntervalMs: Long
}
