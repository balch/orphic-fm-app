package org.balch.orpheus.djapp.ai

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.djapp.DjAppHost
import org.balch.orpheus.djapp.di.IosDjGraph

/**
 * iOS graph for the AI edition. Declared here, not in `:apps:djapp:shared`, so Metro generates it where
 * this module's contributions (the AI tab, the Mixer agent, the saved-vibe folder) are visible, the same
 * reason `DjAppGraphDesktop` and `DjAppGraphAndroid` live in their app modules.
 */
@DependencyGraph(AppScope::class)
interface DjAppGraphIosAi : IosDjGraph {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): DjAppGraphIosAi
    }
}

/** The AI app's launch call, in place of `DjAppHost.bootGraph()`: the same host, built on [DjAppGraphIosAi]. */
object DjAiHost {
    fun bootGraph() {
        if (!DjAppHost.isGraphAlive) DjAppHost.useGraph { createGraphFactory<DjAppGraphIosAi.Factory>().create() }
        DjAppHost.bootGraph()
    }

    /** Hands a tab left by the share extension to the inbox. Call after [bootGraph], on each scene activation. */
    fun collectSharedTab() {
        readAndDeleteSharedTab()?.let { DjAppHost.requireGraph().sharedTextInbox.offer(it) }
    }
}
