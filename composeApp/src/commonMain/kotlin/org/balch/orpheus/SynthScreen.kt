package org.balch.orpheus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.ui.VoiceGroupSection
import org.balch.orpheus.ui.panels.CenterControlPanel
import org.balch.orpheus.ui.panels.HeaderPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.LearnModeProvider
import org.balch.orpheus.util.Logger

@Composable
fun SynthScreen(
    orchestrator: SynthOrchestrator,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        orchestrator.start()
        focusRequester.requestFocus()
        Logger.info { "Orpheus Ready \u2713" }
    }

    val peak by orchestrator.peakFlow.collectAsState()
    var isDialogActive by remember { mutableStateOf(false) }

    val voiceViewModel: VoiceViewModel = metroViewModel()

    LearnModeProvider {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        SynthKeyboardHandler.handleKeyEvent(
                            keyEvent = event,
                            voiceState = voiceViewModel.uiState.value,
                            voiceViewModel = voiceViewModel,
                            isDialogActive = isDialogActive
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                    // Top panel row
                    HeaderPanel(onDialogActiveChange = { isDialogActive = it })

                    // Main section: Voice groups + center controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        VoiceGroupSection(
                            quadLabel = "1-4",
                            quadColor = OrpheusColors.neonMagenta,
                            voiceStartIndex = 0,
                            modifier = Modifier.weight(1f)
                        )

                        CenterControlPanel(modifier = Modifier.fillMaxHeight())

                        VoiceGroupSection(
                            quadLabel = "5-8",
                            quadColor = OrpheusColors.synthGreen,
                            voiceStartIndex = 4,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
        }
    }
}
