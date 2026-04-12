package org.balch.djapp

import android.app.Application
import android.content.Context
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.djapp.di.DjAppGraph

class DjAppApplication : Application() {

    lateinit var graph: DjAppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = createGraphFactory<DjAppGraph.Factory>().create(
            this,
            DjForegroundServiceControllerImpl(this),
        )
    }

    companion object {
        fun getGraph(context: Context): DjAppGraph =
            (context.applicationContext as DjAppApplication).graph
    }
}
