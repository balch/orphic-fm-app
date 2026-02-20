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
import org.balch.orpheus.core.LocalSynthFeatures
import org.balch.orpheus.core.SynthFeatureRegistry
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import org.balch.orpheus.di.OrpheusGraph
import org.balch.orpheus.features.ai.AiOptionsFeature
import org.balch.orpheus.features.ai.AiOptionsViewModel
import org.balch.orpheus.features.ai.chat.ChatDialog
import org.balch.orpheus.core.feature
import org.balch.orpheus.features.debug.DebugBottomBar
import org.balch.orpheus.features.debug.DebugFeature
import org.balch.orpheus.features.debug.DebugViewModel
import org.balch.orpheus.features.visualizations.VizFeature
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
    CompositionLocalProvider(
        LocalMetroViewModelFactory provides graph.metroViewModelFactory,
    ) {
        // Put registry into ViewModelStore so onCleared() fires on teardown
        val registry: SynthFeatureRegistry = metroViewModel()

        CompositionLocalProvider(LocalSynthFeatures provides registry) {
            val vizFeature: VizFeature = registry.feature<VizViewModel, VizFeature>()
            val vizState by vizFeature.stateFlow.collectAsState()
            val liquidState = rememberLiquidState()

            // Create shared liquid effects
            val liquidEffects = androidx.compose.runtime.remember { VisualizationLiquidEffects() }

            // Double liquid state: one for the viz effects, one for the dialog glass effect
            val dialogLiquidState = rememberLiquidState()

            // Get AI feature for chat dialog state
            val aiFeature: AiOptionsFeature = registry.feature<AiOptionsViewModel, AiOptionsFeature>()
            val aiState by aiFeature.stateFlow.collectAsState()
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
                                    debugFeature = registry.feature<DebugViewModel, DebugFeature>(),
                                )
                            }
                        }

                        // Chat dialog overlay - rendered at app level for proper z-order
                        if (showChatDialog) {
                            ChatDialog(
                                onClose = aiFeature.actions.onToggleChatDialog,
                                liquidState = dialogLiquidState,
                                position = dialogPosition,
                                onPositionChange = aiFeature.actions.onDialogPositionChange,
                                size = dialogSize,
                                onSizeChange = aiFeature.actions.onDialogSizeChange,
                                aiFeature = aiFeature,
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
}
