package org.balch.orpheus.features.ai.tools

import kotlinx.serialization.Serializable

@Serializable
data class ReplExecuteArgs(val code: String)

@Serializable
data class ReplExecuteResult(val success: Boolean, val message: String, val activeSlots: List<String> = emptyList())

expect class ReplExecuteTool {
    suspend fun execute(args: ReplExecuteArgs): ReplExecuteResult
}
