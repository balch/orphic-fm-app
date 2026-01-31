package org.balch.orpheus

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.diamondedge.logging.KmLogging
import dev.zacsweers.metro.createGraphFactory
import kotlinx.browser.document
import org.balch.orpheus.di.OrpheusGraph

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val graph = createGraphFactory<OrpheusGraph.Factory>().create()
    
    // Wire up logging to UI
    KmLogging.addLogger(graph.consoleLogger)

    // Ensure AudioContext is resumed on first user interaction
    val resumeAudio = { 
        graph.synthEngine.start() 
        Unit 
    }
    document.addEventListener("click", { resumeAudio() })
    document.addEventListener("keydown", { resumeAudio() })
    document.addEventListener("touchstart", { resumeAudio() })
    
    val container = document.getElementById("ComposeTarget") ?: document.body!!
    ComposeViewport(container) {
        App(graph)
    }
}
