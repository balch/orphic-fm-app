package org.balch.orpheus

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.diamondedge.logging.KmLogging
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.di.OrpheusGraphIos

/**
 * The one app-wide graph, reachable from Swift as `OrpheusIos.shared.graph`.
 *
 * Built here rather than in a `remember` because AppDelegate hands the graph's own
 * `IosHandTracker` to the camera bridge. A second graph means frames go to a tracker nothing observes.
 */
object OrpheusIos {
    val graph: OrpheusGraphIos by lazy {
        createGraphFactory<OrpheusGraphIos.Factory>().create().also { g ->
            KmLogging.addLogger(g.consoleLogger)
        }
    }
}

fun MainViewController() = ComposeUIViewController {
    val graph = OrpheusIos.graph
    // Builds every @StartupRoot, then the graph's startup features.
    remember { graph.startupInitializer.run() }
    App(graph)
}
