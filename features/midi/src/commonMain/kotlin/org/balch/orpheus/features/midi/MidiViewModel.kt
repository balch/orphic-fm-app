package org.balch.orpheus.features.midi

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiController
import org.balch.orpheus.core.midi.MidiInputHandler
import org.balch.orpheus.core.midi.MidiMappingRepository
import org.balch.orpheus.core.midi.MidiMappingState
import org.balch.orpheus.core.midi.MidiMappingStateHolder
import org.balch.orpheus.core.synthViewModel

/** UI state for the MIDI panel. */
@Immutable
data class MidiUiState(
    val deviceName: String? = null,
    val isConnected: Boolean = false,
    val isLearnModeActive: Boolean = false,
    val mappingState: MidiMappingState = MidiMappingState()
)

typealias MidiFeature = SynthFeature<MidiUiState, MidiPanelActions>

/**
 * ViewModel for the MIDI panel.
 *
 * Delegates mapping state to MidiMappingStateHolder (a singleton) so that 
 * SynthController and all MidiViewModel instances share the same state.
 */
@Inject
@ViewModelKey(MidiViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class MidiViewModel(
    val midiController: MidiController,
    private val midiRepository: MidiMappingRepository,
    private val stateHolder: MidiMappingStateHolder,
    private val midiInputHandler: MidiInputHandler,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel(), MidiFeature {

    private val log = logging("MidiViewModel")

    // Local device connection state
    private val _deviceName = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val _isConnected = kotlinx.coroutines.flow.MutableStateFlow(false)

    // UI state combines local device state with shared mapping state
    override val stateFlow: StateFlow<MidiUiState> = combine(
        _deviceName,
        _isConnected,
        stateHolder.isLearnModeActive,
        stateHolder.state
    ) { deviceName, isConnected, isLearnModeActive, mappingState ->
        MidiUiState(
            deviceName = deviceName,
            isConnected = isConnected,
            isLearnModeActive = isLearnModeActive,
            mappingState = mappingState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MidiUiState()
    )

    override val actions: MidiPanelActions = MidiPanelActions(
        toggleLearnMode = { toggleLearnMode() },
        saveLearnedMappings = { saveLearnedMappings() },
        cancelLearnMode = { cancelLearnMode() },
        selectControlForLearning = { id -> selectControlForLearning(id) },
        selectVoiceForLearning = { idx -> selectVoiceForLearning(idx) },
        isControlBeingLearned = { id -> isControlBeingLearned(id) },
        isVoiceBeingLearned = { idx -> isVoiceBeingLearned(idx) }
    )

    // Backup state for cancellation
    private var mappingBeforeLearn: MidiMappingState? = null

    // MIDI polling job
    private var midiPollingJob: Job? = null

    // Last known device name for reconnection
    private var lastDeviceName: String? = null

    init {
        viewModelScope.launch(dispatcherProvider.io) {
            tryConnectMidi()
            startMidiPolling()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun toggleLearnMode() {
        val isActive = stateHolder.isLearnModeActive.value
        if (isActive) {
            cancelLearnMode()
        } else {
            mappingBeforeLearn = stateHolder.state.value
            stateHolder.setLearnModeActive(true)
        }
    }

    fun saveLearnedMappings() {
        // Clear learn target and exit learn mode
        stateHolder.updateState { it.cancelLearn() }
        stateHolder.setLearnModeActive(false)
        mappingBeforeLearn = null

        // Persist to storage
        midiController.currentDeviceName?.let { deviceName ->
            viewModelScope.launch(dispatcherProvider.io) {
                midiRepository.save(deviceName, stateHolder.state.value)
            }
        }
    }

    fun cancelLearnMode() {
        mappingBeforeLearn?.let { backup -> stateHolder.updateState(backup) }
        stateHolder.setLearnModeActive(false)
        mappingBeforeLearn = null
    }

    fun selectControlForLearning(controlId: String) {
        if (stateHolder.isLearnModeActive.value) {
            stateHolder.updateState { it.startLearnControl(controlId) }
        }
    }

    fun isControlBeingLearned(controlId: String): Boolean {
        return stateHolder.state.value.isLearningControl(controlId)
    }

    fun selectVoiceForLearning(voiceIndex: Int) {
        if (stateHolder.isLearnModeActive.value) {
            stateHolder.updateState { it.startLearnVoice(voiceIndex) }
        }
    }

    fun isVoiceBeingLearned(voiceIndex: Int): Boolean {
        return stateHolder.state.value.isLearningVoice(voiceIndex)
    }

    // ═══════════════════════════════════════════════════════════
    // MAPPING LOOKUPS (delegate to stateHolder)
    // ═══════════════════════════════════════════════════════════

    fun getControlForCC(cc: Int): String? = stateHolder.getControlForCC(cc)
    fun getVoiceForNote(note: Int): Int? = stateHolder.getVoiceForNote(note)
    fun getControlForNote(note: Int): String? = stateHolder.getControlForNote(note)

    // ═══════════════════════════════════════════════════════════
    // MIDI CONNECTION MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    private fun tryConnectMidi(): Boolean {
        val devices = midiController.getAvailableDevices()
        if (devices.isNotEmpty()) {
            log.debug { "Available MIDI devices: $devices" }

            val deviceName =
                if (lastDeviceName != null && devices.contains(lastDeviceName)) {
                    lastDeviceName!!
                } else {
                    devices.first()
                }

            if (midiController.openDevice(deviceName)) {
                midiController.start(midiInputHandler)
                lastDeviceName = deviceName

                _deviceName.value = deviceName
                _isConnected.value = true
                log.debug { "MIDI initialized and listening on: $deviceName" }

                viewModelScope.launch(dispatcherProvider.io) {
                    midiRepository.load(deviceName)?.let { savedMapping ->
                        stateHolder.updateState(savedMapping)
                        log.debug { "Loaded saved MIDI mappings for $deviceName" }
                    }
                }
                return true
            }
        } else {
            log.debug { "No MIDI devices found" }
        }
        return false
    }

    private fun startMidiPolling() {
        midiPollingJob?.cancel()
        midiPollingJob =
            viewModelScope.launch(dispatcherProvider.io) {
                while (isActive) {
                    delay(2000)

                    val wasOpen = midiController.isOpen
                    val stillAvailable = midiController.isCurrentDeviceAvailable()

                    if (wasOpen && !stillAvailable) {
                        val name = midiController.currentDeviceName
                        log.debug { "MIDI device disconnected: $name" }
                        midiController.closeDevice()
                        _deviceName.value = null
                        _isConnected.value = false
                    } else if (!wasOpen) {
                        val devices = midiController.getAvailableDevices()
                        if (devices.isNotEmpty()) {
                            log.debug { "MIDI device(s) available, attempting to connect..." }
                            tryConnectMidi()
                        }
                    }
                }
            }
    }

    fun stop() {
        midiPollingJob?.cancel()
        midiPollingJob = null
        midiController.stop()
        midiController.closeDevice()
        _deviceName.value = null
        _isConnected.value = false
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
    
    companion object {
        fun previewFeature(state: MidiUiState = MidiUiState(isConnected = true, deviceName = "Mock Device")): MidiFeature =
            object : MidiFeature {
                override val stateFlow: StateFlow<MidiUiState> = MutableStateFlow(state)
                override val actions: MidiPanelActions = MidiPanelActions(
                    toggleLearnMode = {},
                    saveLearnedMappings = {},
                    cancelLearnMode = {},
                    selectControlForLearning = {},
                    selectVoiceForLearning = {},
                    isControlBeingLearned = { false },
                    isVoiceBeingLearned = { false }
                )
            }

        @Composable
        fun feature(): MidiFeature =
            synthViewModel<MidiViewModel, MidiFeature>()
    }

}

@Immutable
data class MidiPanelActions(
    val toggleLearnMode: () -> Unit,
    val saveLearnedMappings: () -> Unit,
    val cancelLearnMode: () -> Unit,
    val selectControlForLearning: (String) -> Unit,
    val selectVoiceForLearning: (Int) -> Unit,
    val isControlBeingLearned: (String) -> Boolean,
    val isVoiceBeingLearned: (Int) -> Boolean
)
