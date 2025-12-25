package org.balch.orpheus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.core.audio.AndroidSynthEngine
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.DronePresetRepository
import org.balch.orpheus.di.OrpheusGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize repositories context
        DronePresetRepository.appContext = applicationContext
        AppPreferencesRepository.appContext = applicationContext
        
        val engine = AndroidSynthEngine()
        // Create the dependency graph
        val graph = createGraphFactory<OrpheusGraph.Factory>().create(engine)

        setContent {
            App(engine, graph)
        }
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun AppAndroidPreview() {
    val engine = AndroidSynthEngine()
    // Note: This might fail in interactive preview if DI generation isn't complete
    val graph = createGraphFactory<OrpheusGraph.Factory>().create(engine)
    App(engine, graph)
}