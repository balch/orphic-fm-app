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
    val moods: List<Mood> get() = emptyList()
}

/**
 * Represents a specific musical mood or direction.
 */
data class Mood(
    val name: String,
    val initialPrompt: String,
    val evolutionPrompts: List<String>
)
