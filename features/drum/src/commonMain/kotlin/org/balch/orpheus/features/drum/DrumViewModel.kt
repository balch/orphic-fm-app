package org.balch.orpheus.features.drum

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import androidx.compose.ui.input.key.Key
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.input.KeyAction
import org.balch.orpheus.core.input.KeyBinding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.enumSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.controller.intSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.core.triggers.DrumTriggerSource

@Immutable
data class DrumUiState(
    // Bass Drum
    val bdFrequency: Float = 0.3f,
    val bdTone: Float = 0.5f,
    val bdDecay: Float = 0.5f,
    val bdP4: Float = 0.5f,
    val bdP5: Float = 0.5f,
    val bdTriggerSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,
    val bdPitchSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,

    // Snare Drum
    val sdFrequency: Float = 0.4f,
    val sdTone: Float = 0.5f,
    val sdDecay: Float = 0.5f,
    val sdP4: Float = 0.5f,
    val sdTriggerSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,
    val sdPitchSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,

    // Hi-Hat
    val hhFrequency: Float = 0.6f,
    val hhTone: Float = 0.5f,
    val hhDecay: Float = 0.5f,
    val hhP4: Float = 0.5f,
    val hhTriggerSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,
    val hhPitchSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,

    // Engine Selection (PlaitsEngineId ordinals)
    val bdEngine: Int = 0,
    val sdEngine: Int = 1,
    val hhEngine: Int = 2,

    // Trigger States (Visual Feedback)
    val isBdActive: Boolean = false,
    val isSdActive: Boolean = false,
    val isHhActive: Boolean = false,
    val drumsBypass: Boolean = true
)

data class DrumPanelActions(
    val setBdFrequency: (Float) -> Unit,
    val setBdTone: (Float) -> Unit,
    val setBdDecay: (Float) -> Unit,
    val setBdP4: (Float) -> Unit,
    val setBdTriggerSource: (DrumTriggerSource) -> Unit,
    val setBdPitchSource: (DrumTriggerSource) -> Unit,
    val startBdTrigger: () -> Unit,
    val stopBdTrigger: () -> Unit,

    val setSdFrequency: (Float) -> Unit,
    val setSdTone: (Float) -> Unit,
    val setSdDecay: (Float) -> Unit,
    val setSdP4: (Float) -> Unit,
    val setSdTriggerSource: (DrumTriggerSource) -> Unit,
    val setSdPitchSource: (DrumTriggerSource) -> Unit,
    val startSdTrigger: () -> Unit,
    val stopSdTrigger: () -> Unit,

    val setHhFrequency: (Float) -> Unit,
    val setHhTone: (Float) -> Unit,
    val setHhDecay: (Float) -> Unit,
    val setHhP4: (Float) -> Unit,
    val setHhTriggerSource: (DrumTriggerSource) -> Unit,
    val setHhPitchSource: (DrumTriggerSource) -> Unit,
    val startHhTrigger: () -> Unit,
    val stopHhTrigger: () -> Unit,

    val setBdEngine: (Int) -> Unit,
    val setSdEngine: (Int) -> Unit,
    val setHhEngine: (Int) -> Unit,

    val setDrumsBypass: (Boolean) -> Unit
) {
    companion object {
        val EMPTY = DrumPanelActions(
            {}, {}, {}, {}, {}, {}, {}, {},
            {}, {}, {}, {}, {}, {}, {}, {},
            {}, {}, {}, {}, {}, {}, {}, {},
            {}, {}, {}, {}
        )
    }
}

/** User intents for the Drum panel. */
private sealed interface DrumIntent {
    data class BdFrequency(val value: Float) : DrumIntent
    data class BdTone(val value: Float) : DrumIntent
    data class BdDecay(val value: Float) : DrumIntent
    data class BdP4(val value: Float) : DrumIntent
    data class BdP5(val value: Float) : DrumIntent
    data class BdTriggerSource(val source: DrumTriggerSource) : DrumIntent
    data class BdPitchSource(val source: DrumTriggerSource) : DrumIntent
    data class BdTrigger(val active: Boolean) : DrumIntent

    data class SdFrequency(val value: Float) : DrumIntent
    data class SdTone(val value: Float) : DrumIntent
    data class SdDecay(val value: Float) : DrumIntent
    data class SdP4(val value: Float) : DrumIntent
    data class SdTriggerSource(val source: DrumTriggerSource) : DrumIntent
    data class SdPitchSource(val source: DrumTriggerSource) : DrumIntent
    data class SdTrigger(val active: Boolean) : DrumIntent

    data class HhFrequency(val value: Float) : DrumIntent
    data class HhTone(val value: Float) : DrumIntent
    data class HhDecay(val value: Float) : DrumIntent
    data class HhP4(val value: Float) : DrumIntent
    data class HhTriggerSource(val source: DrumTriggerSource) : DrumIntent
    data class HhPitchSource(val source: DrumTriggerSource) : DrumIntent
    data class HhTrigger(val active: Boolean) : DrumIntent

    data class BdEngine(val ordinal: Int) : DrumIntent
    data class SdEngine(val ordinal: Int) : DrumIntent
    data class HhEngine(val ordinal: Int) : DrumIntent

    data class Bypass(val active: Boolean) : DrumIntent
}

interface DrumFeature : SynthFeature<DrumUiState, DrumPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.DRUMS
            override val title = "Drums"

            override val markdown = """
                808-style analog drum synthesizer with three voices (Bass Drum, Snare, Hi-Hat). Each voice has frequency, tone, and decay controls plus engine-specific parameters.

                ## Voices
                - **Bass Drum (BD)**: Deep, punchy kicks. FREQ controls fundamental pitch, TONE shapes body, DECAY sets tail length. P4/P5 are engine-specific parameters.
                - **Snare Drum (SD)**: Crispy snares. FREQ sets pitch, TONE blends noise/tonal, DECAY sets snap length. P4 is engine-specific.
                - **Hi-Hat (HH)**: Metallic cymbals. FREQ controls pitch, TONE adjusts brightness, DECAY sets open/closed character. P4 is engine-specific.

                ## Engine Selection
                Each voice can use a different synthesis engine, changing its tonal character.

                ## Tips
                - Lower BD FREQ for deep sub-bass kicks; raise for punchy, clicky attacks.
                - Combine with Beats for algorithmic pattern generation.
                - Use BYPASS to mute drums without losing your parameter settings.
            """.trimIndent()

            override val portControlKeys = mapOf(
                DrumSymbol.BD_FREQ.controlId.key to "Bass drum frequency / pitch",
                DrumSymbol.BD_TONE.controlId.key to "Bass drum tone / body shape",
                DrumSymbol.BD_DECAY.controlId.key to "Bass drum decay length",
                DrumSymbol.BD_P4.controlId.key to "Bass drum engine-specific parameter 4",
                DrumSymbol.BD_P5.controlId.key to "Bass drum engine-specific parameter 5",
                DrumSymbol.SD_FREQ.controlId.key to "Snare drum frequency / pitch",
                DrumSymbol.SD_TONE.controlId.key to "Snare drum tone / noise blend",
                DrumSymbol.SD_DECAY.controlId.key to "Snare drum decay length",
                DrumSymbol.SD_P4.controlId.key to "Snare drum engine-specific parameter 4",
                DrumSymbol.HH_FREQ.controlId.key to "Hi-hat frequency / pitch",
                DrumSymbol.HH_TONE.controlId.key to "Hi-hat tone / brightness",
                DrumSymbol.HH_DECAY.controlId.key to "Hi-hat decay length",
                DrumSymbol.HH_P4.controlId.key to "Hi-hat engine-specific parameter 4",
                DrumSymbol.BD_ENGINE.controlId.key to "Bass drum synthesis engine selection",
                DrumSymbol.SD_ENGINE.controlId.key to "Snare drum synthesis engine selection",
                DrumSymbol.HH_ENGINE.controlId.key to "Hi-hat synthesis engine selection",
                DrumSymbol.MIX.controlId.key to "Drum mix level",
                DrumSymbol.BYPASS.controlId.key to "Bypass drums on/off",
            )

        }
    }
}

/**
 * ViewModel for the Drum Synth panel.
 *
 * Uses SynthController.controlFlow() for port-based engine interactions.
 * Keeps SynthEngine for triggerDrum() (imperative operation).
 */
@Inject
@ViewModelKey(DrumViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ContributesIntoSet(AppScope::class, binding = binding<SynthFeature<*, *>>())
class DrumViewModel(
    private val synthEngine: SynthEngine,
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), DrumFeature {

    // Control flows for Drum plugin ports
    private val bdFreqId = synthController.controlFlow(DrumSymbol.BD_FREQ.controlId)
    private val bdToneId = synthController.controlFlow(DrumSymbol.BD_TONE.controlId)
    private val bdDecayId = synthController.controlFlow(DrumSymbol.BD_DECAY.controlId)
    private val bdP4Id = synthController.controlFlow(DrumSymbol.BD_P4.controlId)
    private val bdP5Id = synthController.controlFlow(DrumSymbol.BD_P5.controlId)
    private val bdTrigSrcId = synthController.controlFlow(DrumSymbol.BD_TRIGGER_SRC.controlId)
    private val bdPitchSrcId = synthController.controlFlow(DrumSymbol.BD_PITCH_SRC.controlId)

    private val sdFreqId = synthController.controlFlow(DrumSymbol.SD_FREQ.controlId)
    private val sdToneId = synthController.controlFlow(DrumSymbol.SD_TONE.controlId)
    private val sdDecayId = synthController.controlFlow(DrumSymbol.SD_DECAY.controlId)
    private val sdP4Id = synthController.controlFlow(DrumSymbol.SD_P4.controlId)
    private val sdTrigSrcId = synthController.controlFlow(DrumSymbol.SD_TRIGGER_SRC.controlId)
    private val sdPitchSrcId = synthController.controlFlow(DrumSymbol.SD_PITCH_SRC.controlId)

    private val hhFreqId = synthController.controlFlow(DrumSymbol.HH_FREQ.controlId)
    private val hhToneId = synthController.controlFlow(DrumSymbol.HH_TONE.controlId)
    private val hhDecayId = synthController.controlFlow(DrumSymbol.HH_DECAY.controlId)
    private val hhP4Id = synthController.controlFlow(DrumSymbol.HH_P4.controlId)
    private val hhTrigSrcId = synthController.controlFlow(DrumSymbol.HH_TRIGGER_SRC.controlId)
    private val hhPitchSrcId = synthController.controlFlow(DrumSymbol.HH_PITCH_SRC.controlId)

    private val bdEngineId = synthController.controlFlow(DrumSymbol.BD_ENGINE.controlId)
    private val sdEngineId = synthController.controlFlow(DrumSymbol.SD_ENGINE.controlId)
    private val hhEngineId = synthController.controlFlow(DrumSymbol.HH_ENGINE.controlId)

    private val bypassId = synthController.controlFlow(DrumSymbol.BYPASS.controlId)

    // UI-only intents for triggers (imperative, not port-based)
    private val uiIntents = MutableSharedFlow<DrumIntent>(extraBufferCapacity = 64)

    override val actions = DrumPanelActions(
        setBdFrequency = bdFreqId.floatSetter(),
        setBdTone = bdToneId.floatSetter(),
        setBdDecay = bdDecayId.floatSetter(),
        setBdP4 = bdP4Id.floatSetter(),
        setBdTriggerSource = bdTrigSrcId.enumSetter(),
        setBdPitchSource = bdPitchSrcId.enumSetter(),
        startBdTrigger = ::startBdTrigger,
        stopBdTrigger = ::stopBdTrigger,

        setSdFrequency = sdFreqId.floatSetter(),
        setSdTone = sdToneId.floatSetter(),
        setSdDecay = sdDecayId.floatSetter(),
        setSdP4 = sdP4Id.floatSetter(),
        setSdTriggerSource = sdTrigSrcId.enumSetter(),
        setSdPitchSource = sdPitchSrcId.enumSetter(),
        startSdTrigger = ::startSdTrigger,
        stopSdTrigger = ::stopSdTrigger,

        setHhFrequency = hhFreqId.floatSetter(),
        setHhTone = hhToneId.floatSetter(),
        setHhDecay = hhDecayId.floatSetter(),
        setHhP4 = hhP4Id.floatSetter(),
        setHhTriggerSource = hhTrigSrcId.enumSetter(),
        setHhPitchSource = hhPitchSrcId.enumSetter(),
        startHhTrigger = ::startHhTrigger,
        stopHhTrigger = ::stopHhTrigger,

        setBdEngine = bdEngineId.intSetter(),
        setSdEngine = sdEngineId.intSetter(),
        setHhEngine = hhEngineId.intSetter(),

        setDrumsBypass = bypassId.boolSetter()
    )

    override val keyBindings: List<KeyBinding> = listOf(
        // Left-hand drum keys
        KeyBinding(Key.Q, "Q", "Bass Drum trigger",
            action = KeyAction.Gate(GATE_ID_BD, onDown = ::startBdTrigger, onUp = ::stopBdTrigger)),
        KeyBinding(Key.W, "W", "Snare Drum trigger",
            action = KeyAction.Gate(GATE_ID_SD, onDown = ::startSdTrigger, onUp = ::stopSdTrigger)),
        KeyBinding(Key.E, "E", "Hi-Hat trigger",
            action = KeyAction.Gate(GATE_ID_HH, onDown = ::startHhTrigger, onUp = ::stopHhTrigger)),
        // Right-hand drum keys share gate IDs with left-hand keys for repeat-guard.
        // Trade-off: if Q and I are held simultaneously, releasing one fires stopBdTrigger
        // even though the other is still held. Acceptable because simultaneous left/right
        // triggers for the same drum voice is not a realistic playing scenario.
        KeyBinding(Key.I, "I", "Bass Drum trigger",
            action = KeyAction.Gate(GATE_ID_BD, onDown = ::startBdTrigger, onUp = ::stopBdTrigger)),
        KeyBinding(Key.O, "O", "Snare Drum trigger",
            action = KeyAction.Gate(GATE_ID_SD, onDown = ::startSdTrigger, onUp = ::stopSdTrigger)),
        KeyBinding(Key.P, "P", "Hi-Hat trigger",
            action = KeyAction.Gate(GATE_ID_HH, onDown = ::startHhTrigger, onUp = ::stopHhTrigger)),
    )

    // Port-based control changes -> intents
    private val controlIntents = merge(
        bdFreqId.map { DrumIntent.BdFrequency(it.asFloat()) },
        bdToneId.map { DrumIntent.BdTone(it.asFloat()) },
        bdDecayId.map { DrumIntent.BdDecay(it.asFloat()) },
        bdP4Id.map { DrumIntent.BdP4(it.asFloat()) },
        bdP5Id.map { DrumIntent.BdP5(it.asFloat()) },
        bdTrigSrcId.map {
            val sources = DrumTriggerSource.entries
            DrumIntent.BdTriggerSource(sources.getOrElse(it.asInt()) { DrumTriggerSource.INTERNAL })
        },
        bdPitchSrcId.map {
            val sources = DrumTriggerSource.entries
            DrumIntent.BdPitchSource(sources.getOrElse(it.asInt()) { DrumTriggerSource.INTERNAL })
        },

        sdFreqId.map { DrumIntent.SdFrequency(it.asFloat()) },
        sdToneId.map { DrumIntent.SdTone(it.asFloat()) },
        sdDecayId.map { DrumIntent.SdDecay(it.asFloat()) },
        sdP4Id.map { DrumIntent.SdP4(it.asFloat()) },
        sdTrigSrcId.map {
            val sources = DrumTriggerSource.entries
            DrumIntent.SdTriggerSource(sources.getOrElse(it.asInt()) { DrumTriggerSource.INTERNAL })
        },
        sdPitchSrcId.map {
            val sources = DrumTriggerSource.entries
            DrumIntent.SdPitchSource(sources.getOrElse(it.asInt()) { DrumTriggerSource.INTERNAL })
        },

        hhFreqId.map { DrumIntent.HhFrequency(it.asFloat()) },
        hhToneId.map { DrumIntent.HhTone(it.asFloat()) },
        hhDecayId.map { DrumIntent.HhDecay(it.asFloat()) },
        hhP4Id.map { DrumIntent.HhP4(it.asFloat()) },
        hhTrigSrcId.map {
            val sources = DrumTriggerSource.entries
            DrumIntent.HhTriggerSource(sources.getOrElse(it.asInt()) { DrumTriggerSource.INTERNAL })
        },
        hhPitchSrcId.map {
            val sources = DrumTriggerSource.entries
            DrumIntent.HhPitchSource(sources.getOrElse(it.asInt()) { DrumTriggerSource.INTERNAL })
        },

        bdEngineId.map { DrumIntent.BdEngine(it.asInt()) },
        sdEngineId.map { DrumIntent.SdEngine(it.asInt()) },
        hhEngineId.map { DrumIntent.HhEngine(it.asInt()) },

        bypassId.map { DrumIntent.Bypass(it.asBoolean()) }
    )

    override val stateFlow: StateFlow<DrumUiState> =
        merge(controlIntents, uiIntents)
            .scan(DrumUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = this.sharingStrategy,
                initialValue = DrumUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: DrumUiState, intent: DrumIntent): DrumUiState =
        when (intent) {
            is DrumIntent.BdFrequency -> state.copy(bdFrequency = intent.value)
            is DrumIntent.BdTone -> state.copy(bdTone = intent.value)
            is DrumIntent.BdDecay -> state.copy(bdDecay = intent.value)
            is DrumIntent.BdP4 -> state.copy(bdP4 = intent.value)
            is DrumIntent.BdP5 -> state.copy(bdP5 = intent.value)
            is DrumIntent.BdTriggerSource -> state.copy(bdTriggerSource = intent.source)
            is DrumIntent.BdPitchSource -> state.copy(bdPitchSource = intent.source)
            is DrumIntent.BdTrigger -> state.copy(isBdActive = intent.active)

            is DrumIntent.SdFrequency -> state.copy(sdFrequency = intent.value)
            is DrumIntent.SdTone -> state.copy(sdTone = intent.value)
            is DrumIntent.SdDecay -> state.copy(sdDecay = intent.value)
            is DrumIntent.SdP4 -> state.copy(sdP4 = intent.value)
            is DrumIntent.SdTriggerSource -> state.copy(sdTriggerSource = intent.source)
            is DrumIntent.SdPitchSource -> state.copy(sdPitchSource = intent.source)
            is DrumIntent.SdTrigger -> state.copy(isSdActive = intent.active)

            is DrumIntent.HhFrequency -> state.copy(hhFrequency = intent.value)
            is DrumIntent.HhTone -> state.copy(hhTone = intent.value)
            is DrumIntent.HhDecay -> state.copy(hhDecay = intent.value)
            is DrumIntent.HhP4 -> state.copy(hhP4 = intent.value)
            is DrumIntent.HhTriggerSource -> state.copy(hhTriggerSource = intent.source)
            is DrumIntent.HhPitchSource -> state.copy(hhPitchSource = intent.source)
            is DrumIntent.HhTrigger -> state.copy(isHhActive = intent.active)

            is DrumIntent.BdEngine -> state.copy(bdEngine = intent.ordinal)
            is DrumIntent.SdEngine -> state.copy(sdEngine = intent.ordinal)
            is DrumIntent.HhEngine -> state.copy(hhEngine = intent.ordinal)

            is DrumIntent.Bypass -> state.copy(drumsBypass = intent.active)
        }

    fun startBdTrigger() {
        synthEngine.triggerDrum(0, 1.0f)
        uiIntents.tryEmit(DrumIntent.BdTrigger(true))
    }
    fun stopBdTrigger() { uiIntents.tryEmit(DrumIntent.BdTrigger(false)) }

    fun startSdTrigger() {
        synthEngine.triggerDrum(1, 1.0f)
        uiIntents.tryEmit(DrumIntent.SdTrigger(true))
    }
    fun stopSdTrigger() { uiIntents.tryEmit(DrumIntent.SdTrigger(false)) }

    fun startHhTrigger() {
        synthEngine.triggerDrum(2, 1.0f)
        uiIntents.tryEmit(DrumIntent.HhTrigger(true))
    }
    fun stopHhTrigger() { uiIntents.tryEmit(DrumIntent.HhTrigger(false)) }

    companion object {
        private const val GATE_ID_BD = 100
        private const val GATE_ID_SD = 101
        private const val GATE_ID_HH = 102

        fun previewFeature(state: DrumUiState = DrumUiState()): DrumFeature =
            object : DrumFeature {
                override val stateFlow: StateFlow<DrumUiState> = MutableStateFlow(state)
                override val actions: DrumPanelActions = DrumPanelActions.EMPTY
            }

        @Composable
        fun feature(): DrumFeature =
            synthViewModel<DrumViewModel, DrumFeature>()
    }
}
