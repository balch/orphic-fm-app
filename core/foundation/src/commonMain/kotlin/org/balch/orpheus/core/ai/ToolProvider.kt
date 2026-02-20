package org.balch.orpheus.core.ai

import ai.koog.agents.core.tools.Tool

/**
 * Interface for providing tools to the AI agent
 */
interface ToolProvider {
    val tool: Tool<*, *>
}