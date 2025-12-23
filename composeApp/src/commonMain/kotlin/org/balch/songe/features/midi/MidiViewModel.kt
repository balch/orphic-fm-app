package org.balch.songe.features.midi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.songe.MidiRouter
import org.balch.songe.core.coroutines.DispatcherProvider
import org.balch.songe.core.midi.LearnTarget
import org.balch.songe.core.midi.MidiController
import org.balch.songe.core.midi.MidiEventListener
import org.balch.songe.core.midi.MidiMappingRepository
import org.balch.songe.core.midi.MidiMappingState
import org.balch.songe.util.Logger

/** UI state for the MIDI panel. */
data class MidiUiState(
    val deviceName: String? = null,
    val isConnected: Boolean = false,
    val isLearnModeActive: Boolean = false,
    val mappingState: MidiMappingState = MidiMappingState()
)

/** User intents for the MIDI panel. */
private sealed interface MidiIntent {
    data class SetDevice(val name: String?, val connected: Boolean) : MidiIntent
    data class SetLearnMode(val active: Boolean) : MidiIntent
    data class SetMappingState(val state: MidiMappingState) : MidiIntent
}

/**
 * ViewModel for the MIDI panel.
 *
 * Uses MVI pattern: intents flow through a reducer (scan) to produce state.
 */
@Inject
@ViewModelKey(MidiViewModel::class)
@ContributesIntoMap(AppScope::class)
class MidiViewModel(
    val midiController: MidiController,
    private val midiRepository: MidiMappingRepository,
    private val midiRouter: Lazy<MidiRouter>,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel() {

    private val intents =
        MutableSharedFlow<MidiIntent>(
            replay = 1,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val uiState: StateFlow<MidiUiState> =
        intents
            .scan(MidiUiState()) { state, intent -> reduce(state, intent) }
            .stateIn(
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
        tryConnectMidi()
        startMidiPolling()
    }

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: MidiUiState, intent: MidiIntent): MidiUiState =
        when (intent) {
            is MidiIntent.SetDevice ->
                state.copy(deviceName = intent.name, isConnected = intent.connected)

            is MidiIntent.SetLearnMode ->
                state.copy(isLearnModeActive = intent.active)

            is MidiIntent.SetMappingState ->
                state.copy(mappingState = intent.state)
        }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun toggleLearnMode() {
        val current = uiState.value
        if (current.isLearnModeActive) {
            cancelLearnMode()
        } else {
            mappingBeforeLearn = current.mappingState
            intents.tryEmit(MidiIntent.SetLearnMode(true))
            Logger.info { "Entered MIDI Learn Mode" }
        }
    }

    fun saveLearnedMappings() {
        val current = uiState.value
        intents.tryEmit(MidiIntent.SetMappingState(current.mappingState.cancelLearn()))
        intents.tryEmit(MidiIntent.SetLearnMode(false))
        mappingBeforeLearn = null

        // Persist to storage
        midiController.currentDeviceName?.let { deviceName ->
            viewModelScope.launch(dispatcherProvider.io) {
                midiRepository.save(deviceName, uiState.value.mappingState)
            }
        }
        Logger.info { "Saved MIDI mappings" }
    }

    fun cancelLearnMode() {
        mappingBeforeLearn?.let { backup -> intents.tryEmit(MidiIntent.SetMappingState(backup)) }
        intents.tryEmit(MidiIntent.SetLearnMode(false))
        mappingBeforeLearn = null
        Logger.info { "Cancelled MIDI Learn Mode" }
    }

    fun selectControlForLearning(controlId: String) {
        if (uiState.value.isLearnModeActive) {
            intents.tryEmit(
                MidiIntent.SetMappingState(
                    uiState.value.mappingState.startLearnControl(controlId)
                )
            )
            Logger.info { "Learning MIDI CC for: $controlId" }
        }
    }

    fun isControlBeingLearned(controlId: String): Boolean {
        return uiState.value.mappingState.isLearningControl(controlId)
    }

    fun selectVoiceForLearning(voiceIndex: Int) {
        if (uiState.value.isLearnModeActive) {
            intents.tryEmit(
                MidiIntent.SetMappingState(
                    uiState.value.mappingState.startLearnVoice(voiceIndex)
                )
            )
            Logger.info { "Learning MIDI note for Voice ${voiceIndex + 1}" }
        }
    }

    fun isVoiceBeingLearned(voiceIndex: Int): Boolean {
        return uiState.value.mappingState.isLearningVoice(voiceIndex)
    }

    // ═══════════════════════════════════════════════════════════
    // MIDI EVENT HANDLING
    // ═══════════════════════════════════════════════════════════

    internal fun handleNoteOn(note: Int, velocity: Int): Boolean {
        val learnTarget = uiState.value.mappingState.learnTarget

        return when (learnTarget) {
            is LearnTarget.Voice -> {
                intents.tryEmit(
                    MidiIntent.SetMappingState(
                        uiState.value.mappingState.assignNoteToVoice(
                            note,
                            learnTarget.index
                        )
                    )
                )
                Logger.info {
                    "Assigned MIDI note ${MidiMappingState.noteName(note)} to Voice ${learnTarget.index + 1}"
                }
                true
            }

            is LearnTarget.Control -> {
                intents.tryEmit(
                    MidiIntent.SetMappingState(
                        uiState.value.mappingState.assignNoteToControl(
                            note,
                            learnTarget.controlId
                        )
                    )
                )
                Logger.info { "Assigned MIDI note $note to Control ${learnTarget.controlId}" }
                true
            }

            else -> false
        }
    }

    internal fun handleControlChange(controller: Int): Boolean {
        val learnTarget = uiState.value.mappingState.learnTarget

        if (learnTarget is LearnTarget.Control) {
            intents.tryEmit(
                MidiIntent.SetMappingState(
                    uiState.value.mappingState.assignCCToControl(
                        controller,
                        learnTarget.controlId
                    )
                )
            )
            Logger.info { "Assigned CC$controller to ${learnTarget.controlId}" }
            return true
        }
        return false
    }

    fun getControlForCC(cc: Int): String? = uiState.value.mappingState.getControlForCC(cc)
    fun getVoiceForNote(note: Int): Int? = uiState.value.mappingState.getVoiceForNote(note)
    fun getControlForNote(note: Int): String? = uiState.value.mappingState.getControlForNote(note)

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
                val internalListener = createInternalListener()
                midiController.start(internalListener)
                lastDeviceName = deviceName

                intents.tryEmit(MidiIntent.SetDevice(deviceName, true))
                Logger.info { "MIDI initialized and listening on: $deviceName" }

                viewModelScope.launch(dispatcherProvider.io) {
                    midiRepository.load(deviceName)?.let { savedMapping ->
                        intents.tryEmit(MidiIntent.SetMappingState(savedMapping))
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

    private fun createInternalListener(): MidiEventListener {
        return object : MidiEventListener {
            override fun onNoteOn(note: Int, velocity: Int) {
                if (!handleNoteOn(note, velocity)) {
                    externalListener.onNoteOn(note, velocity)
                }
            }

            override fun onNoteOff(note: Int) {
                externalListener.onNoteOff(note)
            }

            override fun onControlChange(controller: Int, value: Int) {
                if (!handleControlChange(controller)) {
                    externalListener.onControlChange(controller, value)
                }
            }

            override fun onPitchBend(value: Int) {
                externalListener.onPitchBend(value)
            }
        }
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
                        intents.tryEmit(MidiIntent.SetDevice(null, false))
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
        intents.tryEmit(MidiIntent.SetDevice(null, false))
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}
