package org.balch.songe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import dev.zacsweers.metro.createGraphFactory
import org.balch.songe.core.audio.AndroidSongeEngine
import org.balch.songe.core.preferences.AppPreferencesRepository
import org.balch.songe.core.presets.DronePresetRepository
import org.balch.songe.di.SongeGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize repositories context
        DronePresetRepository.appContext = applicationContext
        AppPreferencesRepository.appContext = applicationContext
        
        val engine = AndroidSongeEngine()
        // Create the dependency graph
        val graph = createGraphFactory<SongeGraph.Factory>().create(engine)

        setContent {
            App(engine, graph)
        }
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun AppAndroidPreview() {
    val engine = AndroidSongeEngine()
    // Note: This might fail in interactive preview if DI generation isn't complete
    val graph = createGraphFactory<SongeGraph.Factory>().create(engine)
    App(engine, graph)
}