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
    /** If true, the agent will stop after completing all evolution prompts in a mood */
    val finishOnLastEvolution: Boolean get() = false
    /** Which quad indices (0-2) this agent uses. Used for fade in/out. Default: all quads */
    val activeQuads: List<Int> get() = listOf(0, 1, 2)
}

/**
 * Represents a specific musical mood or direction.
 */
data class Mood(
    val name: String,
    val initialPrompt: String,
    val evolutionPrompts: List<String>
)
