package org.balch.songe

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.remember

fun main() = application {
    val engine = remember { org.balch.songe.audio.JvmSongeEngine() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Songe",
    ) {
        App(engine)
    }
}