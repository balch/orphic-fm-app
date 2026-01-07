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
import androidx.compose.ui.focus.FocusRequester
import com.diamondedge.logging.logging
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.features.evo.EvoViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.screens.CompactLandscapeScreen
import org.balch.orpheus.ui.screens.CompactPortraitScreen
import org.balch.orpheus.ui.screens.DesktopSynthScreen
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
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val log = logging("SynthScreen")
        orchestrator.start()
        focusRequester.requestFocus()
        log.info { "Orpheus Ready \u2713" }
    }

    val peak by orchestrator.peakFlow.collectAsState()
    var isDialogActive by remember { mutableStateOf(false) }

    val voiceViewModel: VoiceViewModel = metroViewModel()
    val evoViewModel: EvoViewModel = metroViewModel()

    LearnModeProvider {
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
                    // Original desktop layout
                    DesktopSynthScreen(
                        voiceViewModel = voiceViewModel,
                        isDialogActive = isDialogActive,
                        onDialogActiveChange = { isDialogActive = it },
                        focusRequester = focusRequester,
                    )
                }
            }
        }
    }
}
