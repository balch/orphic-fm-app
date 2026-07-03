package org.balch.orpheus.features.ai.generative

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Creates a pre-loaded flow for Compose previews. Messages are replayed immediately when collected. */
internal fun previewStatusFlow(vararg messages: AiStatusMessage): Flow<AiStatusMessage> {
    val flow = MutableSharedFlow<AiStatusMessage>(replay = messages.size)
    messages.forEach { flow.tryEmit(it) }
    return flow
}
