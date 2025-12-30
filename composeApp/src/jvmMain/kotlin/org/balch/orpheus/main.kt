package org.balch.orpheus

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.diamondedge.logging.KmLogging
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.core.config.AppConfig
import org.balch.orpheus.di.OrpheusGraph

fun main() = application {
    val graph = remember { createGraphFactory<OrpheusGraph.Factory>().create() }
    
    // Wire up logging to UI
    remember(graph) {
        KmLogging.addLogger(graph.consoleLogger)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = AppConfig.APP_DISPLAY_NAME,
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
        icon = BitmapPainter(useResource("icon.png", ::loadImageBitmap))
    ) {
        App(graph)
    }
}