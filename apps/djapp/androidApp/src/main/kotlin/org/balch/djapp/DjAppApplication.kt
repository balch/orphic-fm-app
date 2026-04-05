package org.balch.djapp

import android.app.Application
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.core.media.ForegroundServiceController
import org.balch.orpheus.djapp.di.DjAppGraph

class DjAppApplication : Application() {

    lateinit var graph: DjAppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = createGraphFactory<DjAppGraph.Factory>().create(
            this,
            NoOpForegroundServiceController,
        )
    }
}

private object NoOpForegroundServiceController : ForegroundServiceController {
    override var actionHandler: ((String) -> Unit)? = null
    override fun start() {}
    override fun stop() {}
    override fun updatePlaybackState(isPlaying: Boolean) {}
    override fun updateMetadata(title: String, mode: String, modeDisplayName: String, isPlaying: Boolean) {}
}
