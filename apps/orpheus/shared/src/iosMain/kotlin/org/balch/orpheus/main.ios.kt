package org.balch.orpheus

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.diamondedge.logging.KmLogging
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.di.OrpheusGraphIos

fun MainViewController() = ComposeUIViewController {
    val graph = remember {
        createGraphFactory<OrpheusGraphIos.Factory>().create().also { g ->
            KmLogging.addLogger(g.consoleLogger)
        }
    }
    // Builds every @StartupRoot, then the graph's startup features.
    remember { graph.startupInitializer.run() }
    App(graph)
}
