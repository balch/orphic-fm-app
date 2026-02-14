package org.balch.orpheus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import org.balch.orpheus.di.OrpheusGraph
import org.balch.orpheus.features.ai.AiOptionsViewModel
import org.balch.orpheus.features.ai.chat.ChatDialog
import org.balch.orpheus.features.debug.DebugBottomBar
import org.balch.orpheus.features.debug.DebugViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.LocalDialogLiquidState
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.VizBackground

@Composable
fun App(
    graph: OrpheusGraph,
    onFullyDrawn: () -> Unit = {}
) {
    CompositionLocalProvider(LocalMetroViewModelFactory provides graph.metroViewModelFactory) {
        val vizViewModel: VizViewModel = metroViewModel()
        val vizState by vizViewModel.stateFlow.collectAsState()
        val liquidState = rememberLiquidState()
        
        // Create shared liquid effects
        val liquidEffects = androidx.compose.runtime.remember { VisualizationLiquidEffects() }
        
        
        // Double liquid state: one for the viz effects, one for the dialog glass effect
        val dialogLiquidState = rememberLiquidState()
        
        // Get AI ViewModel for chat dialog state
        val aiViewModel: AiOptionsViewModel = metroViewModel()
        val aiState by aiViewModel.stateFlow.collectAsState()
        val showChatDialog = aiState.showChatDialog
        val dialogPosition = aiState.dialogPosition
        val dialogSize = aiState.dialogSize


        OrpheusTheme {
            CompositionLocalProvider(
                LocalLiquidState provides liquidState,
                LocalDialogLiquidState provides dialogLiquidState,
                LocalLiquidEffects provides liquidEffects,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Wrap main content in a liquefiable box for the dialog to "see" through
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .liquefiable(dialogLiquidState)
                    ) {
                        VizBackground(
                            modifier = Modifier
                                .fillMaxSize()
                                .liquefiable(liquidState),
                            selectedViz = vizState.selectedViz
                        )

                        Column(modifier = Modifier.fillMaxSize()) {
                            // Main Content
                            Box(modifier = Modifier.weight(1f)) {
                                SynthScreen(
                                    orchestrator = graph.synthOrchestrator,
                                    controlHighlightEventBus = graph.controlHighlightEventBus,
                                    onFullyDrawn = onFullyDrawn
                                )
                            }

                            // Persistent Debug Bar
                            DebugBottomBar(
                                debugFeature = DebugViewModel.feature(),
                            )
                        }
                    }
                    
                    // Chat dialog overlay - rendered at app level for proper z-order
                    if (showChatDialog) {
                        ChatDialog(
                            onClose = aiViewModel.actions.onToggleChatDialog,
                            liquidState = dialogLiquidState,
                            position = dialogPosition,
                            onPositionChange = aiViewModel::updateDialogPosition,
                            size = dialogSize,
                            onSizeChange = aiViewModel::updateDialogSize,
                            aiViewModel = aiViewModel,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 16.dp, bottom = 60.dp)
                        )
                    }
                }
            }
        }
    }
}