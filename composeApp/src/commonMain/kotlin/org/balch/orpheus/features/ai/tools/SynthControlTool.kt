package org.balch.orpheus.features.ai.tools

import kotlinx.serialization.Serializable

@Serializable
data class SynthControlArgs(val controlId: String, val value: Float)

@Serializable
data class SynthControlResult(val success: Boolean, val message: String)

expect class SynthControlTool {
    suspend fun execute(args: SynthControlArgs): SynthControlResult
}
