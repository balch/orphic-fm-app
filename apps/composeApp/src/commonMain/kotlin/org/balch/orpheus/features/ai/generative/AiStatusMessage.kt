package org.balch.orpheus.features.ai.generative

data class AiStatusMessage(
    val text: String,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val timestamp: Long = 0L
)
