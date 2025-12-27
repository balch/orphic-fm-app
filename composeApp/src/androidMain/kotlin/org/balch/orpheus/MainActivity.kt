package org.balch.orpheus

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.zacsweers.metro.createGraph
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.DronePresetRepository
import org.balch.orpheus.di.OrpheusGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge to edge
        enableEdgeToEdge()
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Full screen / Hide status bars
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        // Initialize repositories context
        DronePresetRepository.appContext = applicationContext
        AppPreferencesRepository.appContext = applicationContext
        
        // Create the dependency graph - SynthEngine is now fully DI-wired
        val graph = createGraph<OrpheusGraph>()

        setContent {
            App(graph)
        }
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun AppAndroidPreview() {
    // Note: Preview may not work due to platform-specific DI
    val graph = createGraph<OrpheusGraph>()
    App(graph)
}