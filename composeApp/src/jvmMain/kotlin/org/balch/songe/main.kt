package org.balch.songe

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.zacsweers.metro.createGraphFactory
import org.balch.songe.core.audio.JvmSongeEngine
import org.balch.songe.di.SongeGraph

fun main() = application {
    val engine = remember { JvmSongeEngine() }
    val graph = remember(engine) { createGraphFactory<SongeGraph.Factory>().create(engine) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Songe",
        state = rememberWindowState(width = 1280.dp, height = 800.dp)
    ) {
        App(engine, graph)
    }
}