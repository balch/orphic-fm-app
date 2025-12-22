package org.balch.songe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import org.balch.songe.core.audio.AndroidSongeEngine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = AndroidSongeEngine()

        setContent {
            App(engine)
        }
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun AppAndroidPreview() {
    App(AndroidSongeEngine())
}