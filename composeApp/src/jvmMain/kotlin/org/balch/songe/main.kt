package org.balch.songe

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.remember

fun main() = application {
    val engine = remember { org.balch.songe.audio.JvmSongeEngine() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Songe",
        state = rememberWindowState(width = 1280.dp, height = 800.dp)
    ) {
        App(engine)
    }
}