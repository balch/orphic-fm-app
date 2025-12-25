package org.balch.orpheus

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.core.audio.JvmSynthEngine
import org.balch.orpheus.di.OrpheusGraph

fun main() = application {
    val engine = remember { JvmSynthEngine() }
    val graph = remember(engine) { createGraphFactory<OrpheusGraph.Factory>().create(engine) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Orpheus-8",
        state = rememberWindowState(width = 1280.dp, height = 800.dp)
    ) {
        App(engine, graph)
    }
}