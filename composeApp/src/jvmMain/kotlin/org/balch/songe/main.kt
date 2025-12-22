package org.balch.songe

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val engine = remember { org.balch.songe.core.audio.JvmSongeEngine() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Songe",
        state = rememberWindowState(width = 1280.dp, height = 800.dp)
    ) {
        App(engine)
    }
}