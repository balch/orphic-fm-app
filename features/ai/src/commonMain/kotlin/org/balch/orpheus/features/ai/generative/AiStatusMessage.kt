package org.balch.orpheus.features.ai.generative

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

data class AiStatusMessage @OptIn(ExperimentalAtomicApi::class) constructor(
    val text: String,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val isReasoning: Boolean = false,
    val id: Long = nextId.fetchAndAdd(1).toLong(),
) {
    @OptIn(ExperimentalAtomicApi::class)
    companion object {
        private val nextId = AtomicInt(0)
    }
}
