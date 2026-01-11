package org.balch.orpheus.features.ai.generative

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.GeminiKeyProvider
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.features.ai.tools.ReplExecuteTool
import org.balch.orpheus.features.ai.tools.SynthControlTool


actual class SynthControlAgent(
    private val config: SynthControlAgentConfig,
) {
    actual val state: StateFlow<SynthAgentState> = MutableStateFlow(SynthAgentState.Idle).asStateFlow()
    actual val statusMessages: SharedFlow<AiStatusMessage> = MutableSharedFlow<AiStatusMessage>().asSharedFlow()
    actual val inputLog: SharedFlow<AiStatusMessage> = MutableSharedFlow<AiStatusMessage>().asSharedFlow()
    actual val controlLog: SharedFlow<AiStatusMessage> = MutableSharedFlow<AiStatusMessage>().asSharedFlow()
    actual val completed: SharedFlow<Unit> = MutableSharedFlow<Unit>().asSharedFlow()
    
    actual fun injectUserPrompt(text: String) {}
    actual fun start() {}
    actual fun stop() {}

    @Inject
    actual class Factory(
        private val geminiKeyProvider: GeminiKeyProvider,
        private val aiModelProvider: AiModelProvider,
        private val synthControlTool: SynthControlTool,
        private val replExecuteTool: ReplExecuteTool,
        private val synthEngine: SynthEngine,
        private val synthController: SynthController,
    ) {
        actual fun create(config: SynthControlAgentConfig): SynthControlAgent {
            return SynthControlAgent(config)
        }
    }
}
