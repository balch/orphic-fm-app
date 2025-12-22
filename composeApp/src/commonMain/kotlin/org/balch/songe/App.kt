package org.balch.songe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.balch.songe.core.audio.SongeEngine
import org.balch.songe.features.debug.DebugBottomBar
import org.balch.songe.features.navigation.SongeNavigation
import org.balch.songe.ui.preview.PreviewSongeEngine
import org.balch.songe.ui.theme.SongeTheme
import org.balch.songe.ui.widgets.PlasmaBackground
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview(widthDp = 1080, heightDp = 720)
fun App(engine: SongeEngine = PreviewSongeEngine()) {
    SongeTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            PlasmaBackground()
            
            Column(modifier = Modifier.fillMaxSize()) {
                // Main Content
                Box(modifier = Modifier.weight(1f)) {
                    SongeNavigation(engine = engine)
                }
                
                // Persistent Debug Bar
                DebugBottomBar(engine = engine)
            }
        }
    }
}