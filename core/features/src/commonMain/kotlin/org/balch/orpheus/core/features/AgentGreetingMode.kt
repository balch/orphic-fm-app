package org.balch.orpheus.core.features

/**
 * Controls when the AI agent issues its first LLM request in each app context.
 */
enum class AgentGreetingMode {
    /** Agent greets immediately on construction, issuing an initial LLM request (Orpheus chat app). */
    ON_START,
    /**
     * Agent stays SILENT until the user submits their first prompt (DJ vibe panel): merely opening
     * the panel must issue no LLM request. The first run uses the user's prompt, not a greeting.
     */
    ON_FIRST_PROMPT,
}
