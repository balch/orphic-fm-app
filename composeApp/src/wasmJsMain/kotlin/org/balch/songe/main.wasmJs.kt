package org.balch.songe

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.zacsweers.metro.createGraphFactory
import kotlinx.browser.document
import org.balch.songe.core.audio.WasmSongeEngine
import org.balch.songe.di.SongeGraph

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val engine = WasmSongeEngine()
    val graph = createGraphFactory<SongeGraph.Factory>().create(engine)

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
