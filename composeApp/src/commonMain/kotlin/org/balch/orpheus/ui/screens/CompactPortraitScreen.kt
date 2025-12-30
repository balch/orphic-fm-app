package org.balch.orpheus.ui.screens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.jetbrains.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactPortraitScreen(
    modifier: Modifier = Modifier,
    voiceViewModel: VoiceViewModel = metroViewModel(),
    presetViewModel: PresetsViewModel = metroViewModel(),
) {
    val voiceState by voiceViewModel.uiState.collectAsState()
    val presetState by presetViewModel.uiState.collectAsState()
    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current
    val shape = RoundedCornerShape(12.dp)

    // Focus handling for keyboard input
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

}

@Composable
private fun CompactPortraitLayout(
    modifier: Modifier = Modifier,
) {

    /* AI TODO - Requirement

    1. Liquid title bar at top
    2. Rest of body is also liquid with the selected viz effect being applied underneath
    3. Rest of screen is two sections
        - Synth Panels at top
        - AI Chat and Dashboard at bottom
        - user should be able to control the height by dragging a divider in the middle
           - use a sizer pointer
   4. Synth Panels Section
       - need a mobile version of most panels in the system
       - only one will be visible at a time
       - used to set synth parameters and control the sound output
       - need a cool UI to be able to switch between panels
   5. AI Chat and Dashboard Section
       - Selectable Drone - Solo - Tidal buttons at the top
          - Tidal Button will auto open and populate the REPL panel at the top
       - AI Chat Window or Dashboard next
       - user input at the bottom
   */
}

@Preview
@Composable
private fun CompactPortraitLayoutPreview() {
// AI TODO - fill this out using liquid preview container

    CompactPortraitLayout()

}
