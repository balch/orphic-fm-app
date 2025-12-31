package org.balch.orpheus.ui.panels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.balch.orpheus.features.ai.PanelExpansionEventBus
import org.balch.orpheus.features.ai.PanelId

/**
 * ViewModel for the HeaderPanel, managing panel expansion/collapse state.
 */
@Inject
@ViewModelKey(HeaderViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class HeaderViewModel(
    private val panelExpansionEventBus: PanelExpansionEventBus
) : ViewModel() {

    private val log = logging("HeaderViewModel")

    // ============================================================
    // Panel Expansion States
    // ============================================================

    private val _presetExpanded = MutableStateFlow(false)
    val presetExpanded: StateFlow<Boolean> = _presetExpanded.asStateFlow()

    private val _midiExpanded = MutableStateFlow(false)
    val midiExpanded: StateFlow<Boolean> = _midiExpanded.asStateFlow()

    private val _stereoExpanded = MutableStateFlow(false)
    val stereoExpanded: StateFlow<Boolean> = _stereoExpanded.asStateFlow()
    
    // Default to true as per original implementation
    private val _vizExpanded = MutableStateFlow(true)
    val vizExpanded: StateFlow<Boolean> = _vizExpanded.asStateFlow()

    private val _lfoExpanded = MutableStateFlow(true)
    val lfoExpanded: StateFlow<Boolean> = _lfoExpanded.asStateFlow()

    private val _delayExpanded = MutableStateFlow(true)
    val delayExpanded: StateFlow<Boolean> = _delayExpanded.asStateFlow()

    private val _distortionExpanded = MutableStateFlow(true)
    val distortionExpanded: StateFlow<Boolean> = _distortionExpanded.asStateFlow()
    
    private val _codeExpanded = MutableStateFlow(false)
    val codeExpanded: StateFlow<Boolean> = _codeExpanded.asStateFlow()
    
    private val _aiExpanded = MutableStateFlow(false)
    val aiExpanded: StateFlow<Boolean> = _aiExpanded.asStateFlow()

    init {
        log.debug { "HeaderViewModel: Subscribing to PanelExpansionEventBus" }
        // Subscribe to panel expansion events
        viewModelScope.launch {
            panelExpansionEventBus.events.collect { event ->
                log.info { "HeaderViewModel: ${event.panelId.displayName} -> ${if (event.expand) "EXPAND" else "COLLAPSE"}" }
                setPanelExpanded(event.panelId, event.expand)
            }
        }
    }

    // ============================================================
    // Actions
    // ============================================================

    fun setPresetExpanded(expanded: Boolean) { _presetExpanded.value = expanded }
    fun setMidiExpanded(expanded: Boolean) { _midiExpanded.value = expanded }
    fun setStereoExpanded(expanded: Boolean) { _stereoExpanded.value = expanded }
    fun setVizExpanded(expanded: Boolean) { _vizExpanded.value = expanded }
    fun setLfoExpanded(expanded: Boolean) { _lfoExpanded.value = expanded }
    fun setDelayExpanded(expanded: Boolean) { _delayExpanded.value = expanded }
    fun setDistortionExpanded(expanded: Boolean) { _distortionExpanded.value = expanded }
    fun setCodeExpanded(expanded: Boolean) { 
        log.debug { "HeaderViewModel: setCodeExpanded=$expanded" }
        _codeExpanded.value = expanded 
    }
    fun setAiExpanded(expanded: Boolean) { _aiExpanded.value = expanded }
    
    private fun setPanelExpanded(panelId: PanelId, expanded: Boolean) {
        when (panelId) {
            PanelId.PRESETS -> _presetExpanded.value = expanded
            PanelId.MIDI -> _midiExpanded.value = expanded
            PanelId.STEREO -> _stereoExpanded.value = expanded
            PanelId.VIZ -> _vizExpanded.value = expanded
            PanelId.LFO -> _lfoExpanded.value = expanded
            PanelId.DELAY -> _delayExpanded.value = expanded
            PanelId.DISTORTION -> _distortionExpanded.value = expanded
            PanelId.CODE -> _codeExpanded.value = expanded
            PanelId.AI -> _aiExpanded.value = expanded
        }
    }
}
