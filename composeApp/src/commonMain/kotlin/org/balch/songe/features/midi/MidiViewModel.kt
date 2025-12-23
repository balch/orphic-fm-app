package org.balch.songe.features.midi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.songe.core.coroutines.DispatcherProvider
import org.balch.songe.core.midi.LearnTarget
import org.balch.songe.core.midi.MidiController
import org.balch.songe.core.midi.MidiEventListener
import org.balch.songe.core.midi.MidiMappingRepository
import org.balch.songe.core.midi.MidiMappingState
import org.balch.songe.core.midi.MidiMappingStateHolder
import org.balch.songe.core.midi.MidiRouter
import org.balch.songe.util.Logger

/** UI state for the MIDI panel. */
data class MidiUiState(
    val deviceName: String? = null,
    val isConnected: Boolean = false,
    val isLearnModeActive: Boolean = false,
    val mappingState: MidiMappingState = MidiMappingState()
)

/**
 * ViewModel for the MIDI panel.
 *
 * Delegates mapping state to MidiMappingStateHolder (a singleton) so that 
 * MidiRouter and all MidiViewModel instances share the same state.
 */
@Inject
@ViewModelKey(MidiViewModel::class)
@ContributesIntoMap(AppScope::class)
class MidiViewModel(
    val midiController: MidiController,
    private val midiRepository: MidiMappingRepository,
    private val stateHolder: MidiMappingStateHolder,
    private val midiRouter: Lazy<MidiRouter>,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel() {

    // Local device connection state
    private val _deviceName = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val _isConnected = kotlinx.coroutines.flow.MutableStateFlow(false)

    // UI state combines local device state with shared mapping state
    val uiState: StateFlow<MidiUiState> = combine(
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

    // Backup state for cancellation
    private var mappingBeforeLearn: MidiMappingState? = null

    // MIDI polling job
    private var midiPollingJob: Job? = null

    // Last known device name for reconnection
    private var lastDeviceName: String? = null

    // External listener (lazy to break circular dependency)
    private val externalListener: MidiEventListener by lazy {
        midiRouter.value.createMidiEventListener()
    }

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
            Logger.info { "Available MIDI devices: $devices" }

            val deviceName =
                if (lastDeviceName != null && devices.contains(lastDeviceName)) {
                    lastDeviceName!!
                } else {
                    devices.first()
                }

            if (midiController.openDevice(deviceName)) {
                midiController.start(externalListener)
                lastDeviceName = deviceName

                _deviceName.value = deviceName
                _isConnected.value = true
                Logger.info { "MIDI initialized and listening on: $deviceName" }

                viewModelScope.launch(dispatcherProvider.io) {
                    midiRepository.load(deviceName)?.let { savedMapping ->
                        stateHolder.updateState(savedMapping)
                        Logger.info { "Loaded saved MIDI mappings for $deviceName" }
                    }
                }
                return true
            }
        } else {
            Logger.info { "No MIDI devices found" }
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
                        Logger.info { "MIDI device disconnected: $name" }
                        midiController.closeDevice()
                        _deviceName.value = null
                        _isConnected.value = false
                    } else if (!wasOpen) {
                        val devices = midiController.getAvailableDevices()
                        if (devices.isNotEmpty()) {
                            Logger.info { "MIDI device(s) available, attempting to connect..." }
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
}
