package org.balch.orpheus.features.speech

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.diamondedge.logging.logging
import org.balch.orpheus.core.di.FeatureScope
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.Key
import org.balch.orpheus.core.FeatureCoroutineScope
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.input.KeyAction
import org.balch.orpheus.core.input.KeyBinding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.TTS_URI
import org.balch.orpheus.core.plugin.symbols.TtsSymbol
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.speech.SpeechEvent
import org.balch.orpheus.core.speech.SpeechEventBus
import org.balch.orpheus.core.speech.TtsGenerator
import org.balch.orpheus.core.synthFeature

@Immutable
data class SpeechUiState(
    val rate: Float = 0.5f,
    val speed: Float = 0.5f,
    val volume: Float = 0.5f,
    val reverb: Float = 0f,
    val phaser: Float = 0f,
    val feedback: Float = 0f,
    val textInput: String = "",
    val speechText: String = "",
    val isSpeaking: Boolean = false,
    val isGenerating: Boolean = false,
    val wordIndex: Int = 0,
    val totalWords: Int = 0,
    val ttsAvailable: Boolean = false,
    val selectedVoice: String = "",
    val availableVoices: List<String> = emptyList(),
    val spacebarTrigger: Boolean = false,
)

@Immutable
data class SpeechPanelActions(
    val setRate: (Float) -> Unit,
    val setSpeed: (Float) -> Unit,
    val setVolume: (Float) -> Unit,
    val setReverb: (Float) -> Unit,
    val setPhaser: (Float) -> Unit,
    val setFeedback: (Float) -> Unit,
    val setTextInput: (String) -> Unit,
    val setSelectedVoice: (String) -> Unit,
    val setSpacebarTrigger: (Boolean) -> Unit,
    val speak: () -> Unit,
    val stop: () -> Unit,
) {
    companion object {
        val EMPTY = SpeechPanelActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

private sealed interface SpeechIntent {
    data class Rate(val value: Float) : SpeechIntent
    data class Speed(val value: Float) : SpeechIntent
    data class Volume(val value: Float) : SpeechIntent
    data class Reverb(val value: Float) : SpeechIntent
    data class Phaser(val value: Float) : SpeechIntent
    data class Feedback(val value: Float) : SpeechIntent
    data class TextInput(val value: String) : SpeechIntent
    data class SelectedVoice(val value: String) : SpeechIntent
    data class SpacebarTrigger(val enabled: Boolean) : SpeechIntent
    data class Generating(val active: Boolean) : SpeechIntent
    data class SpeechStatus(val event: SpeechEvent) : SpeechIntent
    data class VoicesLoaded(val voices: List<String>) : SpeechIntent
}

interface SpeechFeature : SynthFeature<SpeechUiState, SpeechPanelActions> {
    override val sharingStrategy: SharingStarted
        get() = SharingStarted.Eagerly

    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.SPEECH
            override val title = "Speech"

            override val markdown = """
        Text-to-speech synthesizer with pitch, speed, volume, reverb, phaser, and feedback controls. Generates spoken audio from text input.

        ## Controls
        - **RATE**: Playback rate / pitch of the speech output.
        - **SPEED**: Words-per-minute speed of the generated speech.
        - **VOLUME**: Output volume level of the speech synthesizer.
        - **REVERB**: Amount of reverb effect applied to the speech.
        - **PHASER**: Amount of phaser effect applied to the speech.
        - **FEEDBACK**: Feedback amount for the effects chain.

        ## Tips
        - Adjust RATE to change the pitch character of the spoken output.
        - Combine REVERB and PHASER for atmospheric spoken-word textures.
            """.trimIndent()

            override val portControlKeys = mapOf(
                TtsSymbol.RATE.controlId.key to "Playback rate / pitch",
                TtsSymbol.SPEED.controlId.key to "Words-per-minute speed",
                TtsSymbol.VOLUME.controlId.key to "Speech output volume",
                TtsSymbol.REVERB.controlId.key to "Reverb effect amount",
                TtsSymbol.PHASER.controlId.key to "Phaser effect amount",
                TtsSymbol.FEEDBACK.controlId.key to "Effects chain feedback",
            )

        }
    }
}

@Inject
@ClassKey(SpeechViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class SpeechViewModel(
    synthController: SynthController,
    private val synthEngine: SynthEngine,
    private val ttsGenerator: TtsGenerator,
    private val speechEventBus: SpeechEventBus,
    private val presetLoader: PresetLoader,
    dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope,
) : SpeechFeature {

    private val log = logging("SpeechViewModel")

    private val rateFlow = synthController.controlFlow(TtsSymbol.RATE.controlId)
    private val speedFlow = synthController.controlFlow(TtsSymbol.SPEED.controlId)
    private val volumeFlow = synthController.controlFlow(TtsSymbol.VOLUME.controlId)
    private val reverbFlow = synthController.controlFlow(TtsSymbol.REVERB.controlId)
    private val phaserFlow = synthController.controlFlow(TtsSymbol.PHASER.controlId)
    private val feedbackFlow = synthController.controlFlow(TtsSymbol.FEEDBACK.controlId)

    private val textInputFlow = MutableStateFlow("")
    private val selectedVoiceFlow = MutableStateFlow("")
    private val spacebarTriggerFlow = MutableStateFlow(false)
    private val generatingFlow = MutableStateFlow(false)
    private val voicesLoadedFlow = MutableStateFlow<List<String>>(emptyList())
    private var speakJob: Job? = null

    override val actions = SpeechPanelActions(
        setRate = rateFlow.floatSetter(),
        setSpeed = speedFlow.floatSetter(),
        setVolume = volumeFlow.floatSetter(),
        setReverb = reverbFlow.floatSetter(),
        setPhaser = phaserFlow.floatSetter(),
        setFeedback = feedbackFlow.floatSetter(),
        setTextInput = { textInputFlow.value = it },
        setSelectedVoice = { selectedVoiceFlow.value = it },
        setSpacebarTrigger = { spacebarTriggerFlow.value = it },
        speak = ::speak,
        stop = ::stopSpeaking,
    )

    override val keyBindings: List<KeyBinding> = listOf(
        KeyBinding(Key.Spacebar, "Spacebar", "Toggle speak/stop (when spacebar trigger enabled)",
            action = KeyAction.Trigger {
                val state = stateFlow.value
                if (state.spacebarTrigger) {
                    if (state.isSpeaking || state.isGenerating) stopSpeaking() else speak()
                    true
                } else {
                    false
                }
            }),
    )

    private val controlIntents = merge(
        rateFlow.map { SpeechIntent.Rate(it.asFloat()) },
        speedFlow.map { SpeechIntent.Speed(it.asFloat()) },
        volumeFlow.map { SpeechIntent.Volume(it.asFloat()) },
        reverbFlow.map { SpeechIntent.Reverb(it.asFloat()) },
        phaserFlow.map { SpeechIntent.Phaser(it.asFloat()) },
        feedbackFlow.map { SpeechIntent.Feedback(it.asFloat()) },
        textInputFlow.map { SpeechIntent.TextInput(it) },
        selectedVoiceFlow.map { SpeechIntent.SelectedVoice(it) },
        spacebarTriggerFlow.map { SpeechIntent.SpacebarTrigger(it) },
        generatingFlow.map { SpeechIntent.Generating(it) },
        speechEventBus.events.map { SpeechIntent.SpeechStatus(it) },
        voicesLoadedFlow.map { SpeechIntent.VoicesLoaded(it) }
    )

    override val stateFlow: StateFlow<SpeechUiState> =
        controlIntents
            .scan(SpeechUiState(ttsAvailable = ttsGenerator.isAvailable)) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = this.sharingStrategy,
                initialValue = SpeechUiState(ttsAvailable = ttsGenerator.isAvailable)
            )

    init {
        scope.launch {
            val voices = ttsGenerator.listVoices()
            voicesLoadedFlow.value = voices
            if (voices.isNotEmpty() && selectedVoiceFlow.value.isEmpty()) {
                selectedVoiceFlow.value = voices.first()
            }
        }

        // Restore non-plugin state from loaded presets
        scope.launch {
            presetLoader.presetFlow.collect { preset ->
                preset.getString(KEY_TEXT_INPUT).takeIf { it.isNotEmpty() }
                    ?.let { textInputFlow.value = it }
                preset.getString(KEY_SELECTED_VOICE).takeIf { it.isNotEmpty() }
                    ?.let { selectedVoiceFlow.value = it }
                spacebarTriggerFlow.value = preset.getBool(KEY_SPACEBAR_TRIGGER)
            }
        }

        // Sync non-plugin state to PresetLoader for save
        scope.launch {
            textInputFlow.collect {
                presetLoader.setFeatureValue(KEY_TEXT_INPUT, PortValue.StringValue(it))
            }
        }
        scope.launch {
            selectedVoiceFlow.collect {
                presetLoader.setFeatureValue(KEY_SELECTED_VOICE, PortValue.StringValue(it))
            }
        }
        scope.launch {
            spacebarTriggerFlow.collect {
                presetLoader.setFeatureValue(KEY_SPACEBAR_TRIGGER, PortValue.BoolValue(it))
            }
        }
    }

    private fun reduce(state: SpeechUiState, intent: SpeechIntent): SpeechUiState =
        when (intent) {
            is SpeechIntent.Rate -> state.copy(rate = intent.value)
            is SpeechIntent.Speed -> state.copy(speed = intent.value)
            is SpeechIntent.Volume -> state.copy(volume = intent.value)
            is SpeechIntent.Reverb -> state.copy(reverb = intent.value)
            is SpeechIntent.Phaser -> state.copy(phaser = intent.value)
            is SpeechIntent.Feedback -> state.copy(feedback = intent.value)
            is SpeechIntent.TextInput -> state.copy(textInput = intent.value)
            is SpeechIntent.SelectedVoice -> state.copy(selectedVoice = intent.value)
            is SpeechIntent.SpacebarTrigger -> state.copy(spacebarTrigger = intent.enabled)
            is SpeechIntent.Generating -> state.copy(isGenerating = intent.active)
            is SpeechIntent.VoicesLoaded -> state.copy(availableVoices = intent.voices)
            is SpeechIntent.SpeechStatus -> when (intent.event) {
                is SpeechEvent.Speaking -> state.copy(
                    isSpeaking = true,
                    isGenerating = false,
                    speechText = intent.event.text,
                    wordIndex = intent.event.wordIndex,
                    totalWords = intent.event.totalWords,
                )
                is SpeechEvent.Done -> state.copy(
                    isSpeaking = false,
                    speechText = intent.event.fullText
                )
                is SpeechEvent.Failed -> state.copy(
                    isSpeaking = false,
                    isGenerating = false,
                    speechText = "Error: ${intent.event.error}"
                )
                is SpeechEvent.Idle -> state.copy(
                    isSpeaking = false,
                    isGenerating = false,
                )
            }
        }

    private fun speak() {
        val text = textInputFlow.value.trim()
        if (text.isEmpty()) return

        speakJob?.cancel()
        speakJob = scope.launch {
            try {
                generatingFlow.value = true
                log.info { "Generating TTS for: $text" }

                val voice = selectedVoiceFlow.value.ifEmpty { null }
                val speedWpm = speedToWpm(stateFlow.value.speed)
                val result = ttsGenerator.generate(text, voice, speedWpm)
                if (result == null) {
                    speechEventBus.emitFailed("TTS not available on this platform")
                    return@launch
                }

                synthEngine.loadTtsAudio(result.samples, result.sampleRate)

                val words = text.split(" ").filter { it.isNotEmpty() }
                val totalWords = words.size
                val durationMs = (result.samples.size.toFloat() / result.sampleRate * 1000f / stateFlow.value.rate).toLong()

                // Weight per-word timing by character count
                val totalChars = words.sumOf { it.length }.coerceAtLeast(1)
                val wordDurations = words.map { word ->
                    (durationMs * word.length / totalChars).coerceAtLeast(50L)
                }

                speechEventBus.emitSpeaking(text, 0, totalWords)
                synthEngine.playTts()

                // Emit word-by-word progress events with weighted timing
                for (i in 1 until totalWords) {
                    delay(wordDurations[i - 1])
                    speechEventBus.emitSpeaking(text, i, totalWords)
                }
                // Wait for the last word to finish
                delay(wordDurations.last())

                speechEventBus.emitDone(text)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    log.warn { "Speech failed: ${e.message}" }
                    speechEventBus.emitFailed(e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun stopSpeaking() {
        speakJob?.cancel()
        speakJob = null
        synthEngine.stopTts()
        scope.launch {
            speechEventBus.emitIdle()
        }
    }

    companion object {
        private const val KEY_TEXT_INPUT = "$TTS_URI:text_input"
        private const val KEY_SELECTED_VOICE = "$TTS_URI:selected_voice"
        private const val KEY_SPACEBAR_TRIGGER = "$TTS_URI:spacebar_trigger"

        /** Map 0.0-1.0 to 80-300 WPM */
        fun speedToWpm(speed: Float): Int = (80 + speed * 220).toInt()

        fun previewFeature(state: SpeechUiState = SpeechUiState()): SpeechFeature =
            object : SpeechFeature {
                override val stateFlow: StateFlow<SpeechUiState> = MutableStateFlow(state)
                override val actions: SpeechPanelActions = SpeechPanelActions.EMPTY
            }

        @Composable
        fun feature(): SpeechFeature =
            synthFeature<SpeechViewModel, SpeechFeature>()
    }
}
