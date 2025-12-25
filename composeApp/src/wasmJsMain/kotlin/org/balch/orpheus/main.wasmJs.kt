package org.balch.orpheus

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.zacsweers.metro.createGraphFactory
import kotlinx.browser.document
import org.balch.orpheus.core.audio.WasmSynthEngine
import org.balch.orpheus.di.OrpheusGraph

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val engine = WasmSynthEngine()
    val graph = createGraphFactory<OrpheusGraph.Factory>().create(engine)

    // Ensure AudioContext is resumed on first user interaction
    val resumeAudio = { 
        engine.start() 
        Unit 
    }
    document.addEventListener("click", { resumeAudio() })
    document.addEventListener("keydown", { resumeAudio() })
    document.addEventListener("touchstart", { resumeAudio() })
    
    val container = document.getElementById("ComposeTarget") ?: document.body!!
    ComposeViewport(container) {
        App(engine, graph)
    }
}
