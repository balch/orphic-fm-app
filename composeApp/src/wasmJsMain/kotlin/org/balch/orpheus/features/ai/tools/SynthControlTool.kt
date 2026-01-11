package org.balch.orpheus.features.ai.tools

import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.routing.SynthController

actual class SynthControlTool @Inject constructor(
    private val synthController: SynthController
) {
    actual suspend fun execute(args: SynthControlArgs): SynthControlResult {
        return SynthControlResult(false, "AI not available on Web")
    }
}
