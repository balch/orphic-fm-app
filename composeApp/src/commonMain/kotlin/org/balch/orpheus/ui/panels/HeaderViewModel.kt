package org.balch.orpheus.ui.panels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.features.ai.PanelExpansionEventBus
import org.balch.orpheus.features.ai.PanelId

/**
 * UI state for the HeaderPanel, containing expansion states for all panels.
 */
@Immutable
data class HeaderPanelUiState(
    val expandedPanels: PersistentMap<PanelId, Boolean> = DEFAULT_EXPANSION_STATE
) {
    /**
     * Check if a panel is expanded.
     */
    fun isExpanded(panelId: PanelId): Boolean = expandedPanels[panelId] ?: false
    
    companion object {
        /**
         * Default expansion state for panels.
         * VIZ, LFO, DELAY, and DISTORTION are expanded by default.
         */
        val DEFAULT_EXPANSION_STATE: PersistentMap<PanelId, Boolean> = persistentMapOf(
            PanelId.PRESETS to false,
            PanelId.MIDI to false,
            PanelId.STEREO to false,
            PanelId.VIZ to true,
            PanelId.EVO to false,
            PanelId.LFO to false,
            PanelId.DELAY to false,
            PanelId.DISTORTION to true,
            PanelId.CODE to false,
            PanelId.AI to false,
            PanelId.DRUMS to false,
            PanelId.LOOPER to false,
            PanelId.PATTERN to false,
            PanelId.WARPS to false
        )
    }
}

/**
 * Actions for the HeaderPanel.
 */
data class HeaderPanelActions(
    val setExpanded: (PanelId, Boolean) -> Unit
) {
    companion object {
        val EMPTY = HeaderPanelActions(
            setExpanded = { _, _ -> }
        )
    }
}

typealias HeaderFeature = SynthFeature<HeaderPanelUiState, HeaderPanelActions>

/**
 * ViewModel for the HeaderPanel, managing panel expansion/collapse state.
 */
@Inject
@ViewModelKey(HeaderViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class HeaderViewModel(
    private val panelExpansionEventBus: PanelExpansionEventBus
) : ViewModel(), HeaderFeature {

    private val log = logging("HeaderViewModel")

    private val _uiState = MutableStateFlow(HeaderPanelUiState())
    override val stateFlow: StateFlow<HeaderPanelUiState> = _uiState.asStateFlow()

    override val actions = HeaderPanelActions(
        setExpanded = ::setExpanded
    )

    init {
        log.debug { "HeaderViewModel: Subscribing to PanelExpansionEventBus" }
        // Subscribe to panel expansion events
        viewModelScope.launch {
            panelExpansionEventBus.events.collect { event ->
                log.debug { "HeaderViewModel: ${event.panelId.displayName} -> ${if (event.expand) "EXPAND" else "COLLAPSE"}" }
                setExpanded(event.panelId, event.expand)
            }
        }
    }

    /**
     * Set the expansion state of a panel.
     */
    fun setExpanded(panelId: PanelId, expanded: Boolean) {
        log.debug { "HeaderViewModel: setExpanded(${panelId.displayName}, $expanded)" }
        _uiState.update { state ->
            state.copy(
                expandedPanels = state.expandedPanels.put(panelId, expanded)
            )
        }
    }

    companion object {
        fun previewFeature(state: HeaderPanelUiState = HeaderPanelUiState()): HeaderFeature =
            object : HeaderFeature {
                override val stateFlow: StateFlow<HeaderPanelUiState> = MutableStateFlow(state)
                override val actions: HeaderPanelActions = HeaderPanelActions.EMPTY
            }

        @Composable
        fun feature(): HeaderFeature =
            synthViewModel<HeaderViewModel, HeaderFeature>()
    }
}

