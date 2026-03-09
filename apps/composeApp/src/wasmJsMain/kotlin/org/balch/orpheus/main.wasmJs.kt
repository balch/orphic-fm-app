@file:OptIn(ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package org.balch.orpheus

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.diamondedge.logging.KmLogging
import dev.zacsweers.metro.createGraphFactory
import kotlinx.browser.document
import org.balch.orpheus.core.audio.dsp.DspWorkerProxy
import org.balch.orpheus.core.audio.dsp.buildDefaultWiringGraph
import org.balch.orpheus.core.audio.dsp.jsCreateDspWorker
import org.balch.orpheus.core.audio.dsp.jsSendSetPortCmd
import org.balch.orpheus.core.audio.dsp.jsSetupWorkerListener
import org.balch.orpheus.di.OrpheusGraph

/** Check if the URL has a ?noworker query parameter to disable Worker DSP */
private fun jsHasNoWorkerFlag(): Boolean =
    js("new URLSearchParams(window.location.search).has('noworker')")

fun main() {
    val graph = createGraphFactory<OrpheusGraph.Factory>().create()

    // Wire up logging to UI
    KmLogging.addLogger(graph.consoleLogger)

    val useWorker = !jsHasNoWorkerFlag()
    var workerProxy: DspWorkerProxy? = null

    if (useWorker) {
        // Create the Web Worker and set up message listener before user gesture
        jsCreateDspWorker("orpheus-dsp-worker.js")
        jsSetupWorkerListener()
        workerProxy = DspWorkerProxy()

        // Force DspSynthEngine creation so its init{} calls setDelegates()
        // before we override them. Metro creates graph properties lazily.
        graph.synthEngine

        // Capture original delegates from DspSynthEngine so we can keep
        // local state in sync (for UI reads) while also forwarding to Worker.
        val originalSetter = graph.synthController.pluginPortSetter!!
        val originalGetter = graph.synthController.pluginPortGetter!!

        // Override setter to update both local engine state AND Worker.
        // Keep original getter so UI reads correct values from local engine.
        graph.synthController.overrideDelegates(
            setter = { id, value ->
                originalSetter(id, value)
                jsSendSetPortCmd(id.uri, id.symbol, value.asFloat())
                true
            },
            getter = originalGetter
        )
    }

    // Start audio on first user interaction (browser autoplay policy)
    val graphBytes = if (useWorker) buildDefaultWiringGraph() else null
    val startAudio: () -> Unit = if (useWorker) {
        { workerProxy?.start(graphBytes); Unit }
    } else {
        { graph.synthEngine.start(); Unit }
    }

    document.addEventListener("click", { startAudio() })
    document.addEventListener("keydown", { startAudio() })
    document.addEventListener("touchstart", { startAudio() })

    val container = document.getElementById("ComposeTarget") ?: document.body!!
    ComposeViewport(container) {
        App(graph)
    }
}
