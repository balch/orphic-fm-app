package org.balch.orpheus

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.diamondedge.logging.KmLogging
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.di.OrpheusGraph

fun MainViewController() = ComposeUIViewController {
    val graph = remember {
        createGraphFactory<OrpheusGraph.Factory>().create().also { g ->
            KmLogging.addLogger(g.consoleLogger)
        }
    }
    App(graph)
}
