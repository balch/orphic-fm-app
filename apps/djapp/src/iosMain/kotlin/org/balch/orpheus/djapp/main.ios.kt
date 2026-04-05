package org.balch.orpheus.djapp

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.djapp.di.DjAppGraph

fun MainViewController() = ComposeUIViewController {
    val graph = remember {
        createGraphFactory<DjAppGraph.Factory>().create()
    }
    DjApp(graph)
}
