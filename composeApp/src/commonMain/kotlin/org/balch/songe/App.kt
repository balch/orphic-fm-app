package org.balch.songe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.balch.songe.navigation.SongeNavigation
import org.balch.songe.ui.components.PlasmaBackground
import org.balch.songe.ui.theme.SongeTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App(engine: org.balch.songe.audio.SongeEngine) {
    SongeTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            PlasmaBackground()
            SongeNavigation(engine = engine)
        }
    }
}