package org.balch.songe.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.balch.songe.ui.components.RotaryKnob
import org.balch.songe.ui.theme.SongeColors

@Composable
fun DistortionPanel(
    drive: Float, onDriveChange: (Float) -> Unit,
    volume: Float, onVolumeChange: (Float) -> Unit,
    mix: Float, onMixChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    CollapsibleColumnPanel(
        title = "DISTORT",
        color = SongeColors.neonMagenta,
        initialExpanded = true,
        expandedWidth = 140.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                RotaryKnob(value = drive, onValueChange = onDriveChange, label = "DRIVE", controlId = null, size = 40.dp, progressColor = SongeColors.neonMagenta)
                RotaryKnob(value = volume, onValueChange = onVolumeChange, label = "VOL", controlId = null, size = 40.dp, progressColor = SongeColors.electricBlue)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RotaryKnob(value = mix, onValueChange = onMixChange, label = "MIX", controlId = null, size = 40.dp, progressColor = SongeColors.warmGlow)
            }
        }
    }
}
