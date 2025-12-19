package org.balch.songe.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Navigation destinations for Songe app
 */
enum class SongeScreen {
    Synth,
    Settings,
    Debug
}

/**
 * Simple state-based navigation for Songe app.
 * This provides a basic navigation structure that works across all platforms.
 * Can be upgraded to full Nav3 with SavedStateConfiguration later.
 */
@Composable
fun SongeNavigation(engine: org.balch.songe.audio.SongeEngine) {
    var currentScreen by remember { mutableStateOf(SongeScreen.Synth) }
    
    when (currentScreen) {
        SongeScreen.Synth -> org.balch.songe.ui.synth.SongeSynthScreen(
            engine = engine
        )
        SongeScreen.Settings -> SettingsScreenPlaceholder(
            onBack = { currentScreen = SongeScreen.Synth }
        )
        SongeScreen.Debug -> org.balch.songe.ui.debug.DebugAudioPanel(engine = engine)
    }
    

}

// Placeholder composables - to be replaced with real implementations

@Composable
private fun SynthScreenPlaceholder(
    onSettingsClick: () -> Unit,
    onDebugClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Songe-8 Synthesizer",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Coming Soon",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SettingsScreenPlaceholder(onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DebugScreenPlaceholder(onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Debug",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
