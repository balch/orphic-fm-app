package org.balch.orpheus.features.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.balch.orpheus.SynthOrchestrator
import org.balch.orpheus.SynthScreen

/** Navigation destinations for Orpheus app */
enum class AppScreen {
    Synth,
    Settings
}

/**
 * Simple state-based navigation for Orpheus app. This provides a basic navigation structure that
 * works across all platforms. Can be upgraded to full Nav3 with SavedStateConfiguration later.
 *
 * The MetroViewModelFactory is provided by App.kt via CompositionLocalProvider, so all
 * metroViewModel() calls throughout the app share the same factory and VM instances.
 */
@Composable
fun AppNavigation(orchestrator: SynthOrchestrator) {
    var currentScreen by remember { mutableStateOf(AppScreen.Synth) }

    when (currentScreen) {
        AppScreen.Synth ->
            SynthScreen(orchestrator = orchestrator)
        AppScreen.Settings ->
            SettingsScreenPlaceholder(onBack = { currentScreen = AppScreen.Synth })
    }
}

// Placeholder composables - to be replaced with real implementations

@Composable
private fun SynthScreenPlaceholder(onSettingsClick: () -> Unit, onDebugClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Orpheus-8 Synthesizer",
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DebugScreenPlaceholder(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Debug",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
