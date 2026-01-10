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

        // Get the DI graph from Application (survives configuration changes)
        val graph = (application as OrpheusApplication).graph
        
        setContent {
            App(
                graph = graph,
                onFullyDrawn = {
                    // Report to benchmarking library that the app is fully drawn
                    // This is required for Macrobenchmark to detect startup completion
                    reportFullyDrawn()
                }
            )
        }
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun AppAndroidPreview() {
    // Note: Preview won't work - needs Application context
}