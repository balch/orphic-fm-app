package org.balch.songe.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.balch.songe.audio.SongeEngine
import org.balch.songe.ui.theme.SongeColors

@Composable
fun DebugAudioPanel(
    engine: SongeEngine
) {
    // Start engine when this screen opens, stop when it closes
    DisposableEffect(Unit) {
        engine.start()
        onDispose {
            engine.stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Audio Debug",
            style = MaterialTheme.typography.headlineMedium,
            color = SongeColors.neonCyan
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = { engine.playTestTone(440f) }
        ) {
            Text("Play 440Hz Tone")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = { engine.stopTestTone() }
        ) {
            Text("Stop Tone")
        }
    }
}
