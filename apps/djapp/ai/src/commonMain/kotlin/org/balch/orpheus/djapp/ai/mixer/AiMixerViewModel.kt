package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.runtime.Composable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.djapp.ai.tools.VIBE_LOADING
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.MixCaptureService
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.SendEdit

/** The VM's own overlay on the controller-derived state. */
private data class Local(val capture: MixerCapture? = null, val capturing: Boolean = false, val notice: String? = null)

/**
 * The mixer panel's state and edits over the shared [MixBenchController]. The controller is resolved lazily
 * (flows, edit consumer), never in the constructor, to avoid the DI cycle [org.balch.orpheus.djapp.ai.tools.MixReadTool] documents.
 */
@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(AiMixerFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<AiMixerFeature>())
class AiMixerViewModel(
    private val controllerProvider: () -> MixBenchController,
    private val pulsarFeature: PulsarFeature,
    private val captureService: MixCaptureService,
    private val scope: FeatureCoroutineScope,
    private val dispatchers: DispatcherProvider,
) : AiMixerFeature {
    private val log = logging("AiMixerViewModel")
    private val local = MutableStateFlow(Local())
    private val edits = Channel<suspend MixBenchController.() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(dispatchers.default) {
            val controller by lazy(controllerProvider)
            for (edit in edits) {
                // One bad edit must not end the consumer: the scope is a SupervisorJob, so later edits would drop silently.
                try {
                    controller.edit()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "mixer edit failed" }
                }
            }
        }
    }

    override val stateFlow: StateFlow<AiMixerUiState> = flow {
        val c = controllerProvider()
        val sections = pulsarFeature.arrangementStateFlow.map { it.sectionIndex }.distinctUntilChanged()
        val knobs = pulsarFeature.stateFlow
            .map { Macro.entries.associateWith(pulsarFeature::macroKnob) }
            .distinctUntilChanged()
        val bench = combine(c.layers, c.scope, c.muted, c.soloed, c.loopSection) { layers, sc, muted, soloed, loop ->
            Bench(layers, sc, muted, soloed, loop)
        }
        emitAll(
            combine(bench, pulsarFeature.vibeFlow, sections, knobs, local) { b, vibe, section, k, l ->
                mixerState(vibe, section, b.layers, b.scope, b.muted, b.soloed, b.loop, k)
                    .copy(capture = l.capture, capturing = l.capturing, notice = l.notice)
            },
        )
    }
        .flowOn(dispatchers.default)
        .stateIn(scope, SynthFeature.sharingStrategy, AiMixerUiState())

    override val meters: Flow<MeterSnapshot> = flow {
        val c = controllerProvider()
        while (true) {
            emit(c.readMeters())
            delay(METER_POLL_MS)
        }
    }.flowOn(dispatchers.default)

    override val actions = AiMixerActions(
        setLevelDb = { t, db -> edit { refuse(setHeardLevelDb(t, db.coerceIn(MIN_DB, MAX_DB))) } },
        setPan = { t, p -> edit { refuse(setPan(t, p)) } },
        setReverb = { t, v -> edit { sendNotice(setHeardSend(t, v, reverb = true)) } },
        setDelay = { t, v -> edit { sendNotice(setHeardSend(t, v, reverb = false)) } },
        toggleMute = { t -> edit { refuse(toggleMute(t)) } },
        toggleSolo = { t -> edit { refuse(toggleSolo(t)) } },
        setMacro = { m, v -> edit { refuse(setMacro(m, v)) } },
        setScope = { s ->
            edit {
                val before = scope.value
                setScope(s)
                // The zero-send notice asks for section scope, so a scope change answers it.
                if (scope.value != before) local.update { if (it.notice == ZERO_SEND) it.copy(notice = null) else it }
            }
        },
        jumpTo = { s -> edit { jumpTo(s) } },
        setLoop = { on -> edit { setLoop(on) } },
        reset = { edit { resetToFile() } },
        capture = ::capture,
        dismissNotice = { local.update { it.copy(notice = null) } },
    )

    private fun edit(block: suspend MixBenchController.() -> Unit) {
        edits.trySend(block)
    }

    private fun refuse(applied: Boolean) {
        if (!applied) local.update { it.copy(notice = VIBE_LOADING) } else clearNotice()
    }

    private fun sendNotice(result: SendEdit) {
        when (result) {
            SendEdit.APPLIED -> clearNotice()
            SendEdit.VIBE_LOADING -> local.update { it.copy(notice = VIBE_LOADING) }
            SendEdit.ZERO_SEND -> local.update { it.copy(notice = ZERO_SEND) }
        }
    }

    /** A landed edit answers only the loading notice; the zero-send one stays until dismissed or the scope changes. */
    private fun clearNotice() {
        local.update { if (it.notice == VIBE_LOADING) it.copy(notice = null) else it }
    }

    private fun capture() {
        if (local.value.capturing) return
        local.update { it.copy(capturing = true) }
        clearNotice()
        scope.launch(dispatchers.default) {
            val result = try {
                val o = captureService.capture()
                MixerCapture(o.ok, o.message, o.file, o.rows.size, o.stale, o.appliedToAiVibe)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "capture failed" }
                MixerCapture(false, e.message ?: "capture failed", null, 0, null, false)
            }
            local.update { it.copy(capture = result, capturing = false) }
        }
    }

    private data class Bench(
        val layers: BenchLayers,
        val scope: Scope,
        val muted: Set<Int>,
        val soloed: Set<Int>,
        val loop: Int?,
    )

    companion object {
        private const val METER_POLL_MS = 50L
        private const val MIN_DB = -30f
        private const val MAX_DB = 3.5f
        private const val ZERO_SEND = "This send is 0 here, so a whole-vibe edit can't scale it; switch to section scope."

        fun previewFeature(state: AiMixerUiState = AiMixerUiState(), meters: MeterSnapshot): AiMixerFeature =
            object : AiMixerFeature {
                override val stateFlow: StateFlow<AiMixerUiState> = MutableStateFlow(state)
                override val actions: AiMixerActions = AiMixerActions.EMPTY
                override val meters: Flow<MeterSnapshot> = MutableStateFlow(meters)
            }

        @Composable
        fun feature(): AiMixerFeature = synthFeature<AiMixerFeature>()
    }
}
