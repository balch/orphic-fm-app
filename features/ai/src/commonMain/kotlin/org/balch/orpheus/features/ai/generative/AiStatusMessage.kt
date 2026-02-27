package org.balch.orpheus.features.ai.generative

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

/** Creates a pre-loaded flow for Compose previews. Messages are replayed immediately when collected. */
internal fun previewStatusFlow(vararg messages: AiStatusMessage): Flow<AiStatusMessage> {
    val flow = MutableSharedFlow<AiStatusMessage>(replay = messages.size)
    messages.forEach { flow.tryEmit(it) }
    return flow
}
