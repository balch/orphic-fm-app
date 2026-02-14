package org.balch.orpheus

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.diamondedge.logging.logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.midi.LearnTarget
import org.balch.orpheus.features.ai.ControlHighlightEventBus
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.ui.screens.CompactLandscapeScreen
import org.balch.orpheus.ui.screens.CompactPortraitScreen
import org.balch.orpheus.ui.screens.DesktopSynthScreen
import org.balch.orpheus.ui.widgets.HighlightProvider
import org.balch.orpheus.ui.widgets.LearnModeProvider

/**
 * Layout mode for the synth screen based on window size.
 */
private enum class LayoutMode {
    /** Standard desktop/tablet layout with full controls */
    Desktop,
    /** Compact landscape: 2x4 voice grid with simplified controls */
    CompactLandscape,
    /** Compact portrait: Strummable strings interface */
    CompactPortrait
}

/**
 * Determines the appropriate layout mode based on window constraints.
 * 
 * Compact thresholds:
 * - Width < 600dp OR Height < 400dp -> Compact mode
 * - In compact mode: Width > Height -> Landscape, otherwise Portrait
 */
private fun determineLayoutMode(widthDp: Float, heightDp: Float): LayoutMode {
    val isCompact = widthDp < 600f || heightDp < 400f
    return if (isCompact) {
        if (widthDp > heightDp) LayoutMode.CompactLandscape else LayoutMode.CompactPortrait
    } else {
        LayoutMode.Desktop
    }
}

@Composable
fun SynthScreen(
    orchestrator: SynthOrchestrator,
    controlHighlightEventBus: ControlHighlightEventBus,
    onFullyDrawn: () -> Unit = {}
) {
    LaunchedEffect(Unit) {
        val log = logging("SynthScreen")
        // Start audio engine on background thread to avoid blocking UI
        withContext(Dispatchers.Default) {
            orchestrator.start()
        }
        log.info { "Orpheus Ready \u2713" }
        onFullyDrawn()
    }

    var isDialogActive by remember { mutableStateOf(false) }

    val midiFeature = MidiViewModel.feature()
    val midiState by midiFeature.stateFlow.collectAsState()

    val highlightedControls by controlHighlightEventBus.highlightedControls.collectAsState()

    HighlightProvider(highlightedControlIds = highlightedControls) {
        LearnModeProvider(
            isLearnModeActive = midiState.isLearnModeActive,
            selectedControlId = (midiState.mappingState.learnTarget as? LearnTarget.Control)?.controlId,
            onSelectControl = { id -> midiFeature.actions.selectControlForLearning(id) },
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layoutMode = determineLayoutMode(maxWidth.value, maxHeight.value)
            
            when (layoutMode) {
                LayoutMode.CompactLandscape -> {
                    CompactLandscapeScreen(
                        modifier = Modifier.fillMaxSize()
                    )
                }
                LayoutMode.CompactPortrait -> {
                    CompactPortraitScreen(
                        modifier = Modifier.fillMaxSize()
                    )
                }
                LayoutMode.Desktop -> {
                    DesktopSynthScreen(
                        isDialogActive = isDialogActive,
                        onDialogActiveChange = { isDialogActive = it },
                    )
                }
            }
        }
    }
    }
}
