package org.balch.orpheus.features.ai.tools

import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.tidal.TidalRepl
import org.balch.orpheus.features.ai.PanelExpansionEventBus
import org.balch.orpheus.features.ai.ReplCodeEventBus

// Stub for Wasm
class ToolStub<A, R>(val name: String)

actual class ReplExecuteTool @Inject constructor(
    private val tidalRepl: TidalRepl,
    private val replCodeEventBus: ReplCodeEventBus,
    private val panelExpansionEventBus: PanelExpansionEventBus
) {
    actual suspend fun execute(args: ReplExecuteArgs): ReplExecuteResult {
        return ReplExecuteResult(false, "AI not available on Web")
    }
}
