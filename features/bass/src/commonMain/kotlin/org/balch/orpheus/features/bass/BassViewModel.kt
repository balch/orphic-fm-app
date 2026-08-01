package org.balch.orpheus.features.bass

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.audio.BassEngine
import org.balch.orpheus.core.audio.BassScale
import org.balch.orpheus.core.audio.ClockDivision
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.enumSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.controller.intSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.plugin.symbols.BassSymbol

@Immutable
data class BassPanelActions(
    val setEngine: (BassEngine) -> Unit,
    val setRootNote: (Int) -> Unit,
    val setScale: (BassScale) -> Unit,
    val setClockDivision: (ClockDivision) -> Unit,
    val setStepCount: (Int) -> Unit,
    val setMutation: (Float) -> Unit,
    val setCutoff: (Float) -> Unit,
    val setResonance: (Float) -> Unit,
    val setEnvelope: (Float) -> Unit,
    val setOverdrive: (Float) -> Unit,
    val setCompressor: (Float) -> Unit,
    val setMix: (Float) -> Unit,
    val setLfoMix: (Float) -> Unit,
    val setAccentAmount: (Float) -> Unit,
    val setJitter: (Float) -> Unit,
    val setFxSend: (Float) -> Unit,
    val setTriggerSource: (Int) -> Unit,
    val setPitchSource: (Int) -> Unit,
    val setTimbreSource: (Int) -> Unit,
) {
    companion object {
        val EMPTY = BassPanelActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/** User intents for the Bass panel. */
private sealed interface BassIntent {
    data class SetEngine(val engine: BassEngine) : BassIntent
    data class SetRootNote(val value: Int) : BassIntent
    data class SetScale(val scale: BassScale) : BassIntent
    data class SetClockDivision(val division: ClockDivision) : BassIntent
    data class SetStepCount(val value: Int) : BassIntent
    data class SetMutation(val value: Float) : BassIntent
    data class SetCutoff(val value: Float) : BassIntent
    data class SetResonance(val value: Float) : BassIntent
    data class SetEnvelope(val value: Float) : BassIntent
    data class SetOverdrive(val value: Float) : BassIntent
    data class SetCompressor(val value: Float) : BassIntent
    data class SetMix(val value: Float) : BassIntent
    data class SetLfoMix(val value: Float) : BassIntent
    data class SetAccentAmount(val value: Float) : BassIntent
    data class SetJitter(val value: Float) : BassIntent
    data class SetFxSend(val value: Float) : BassIntent
    data class SetTriggerSource(val value: Int) : BassIntent
    data class SetPitchSource(val value: Int) : BassIntent
    data class SetTimbreSource(val value: Int) : BassIntent
}

interface BassFeature : SynthFeature<BassUiState, BassPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.BASS
            override val title = "Bass"

            override val markdown = """
        Independent monophonic bass voice with built-in step sequencer, four synthesis engines,
        filter, overdrive, and compressor. Syncs to the master clock (Grids tempo).
        Output feeds into delay and reverb send buses for rhythmic echo and spatial depth.

        ## Engines (bass_engine: integer 0-3)
        - **0 = VCF Acid** (default): Saw/square + sub oscillator → dual SVF low-pass with resonance. Classic 303-style acid bass. CUTOFF sweeps the filter, RESO adds squelch.
        - **1 = Phase Dist**: CZ-101-style phase distortion. Aggressive, gritty digital bass. CUTOFF controls harmonic brightness.
        - **2 = FM**: 2-operator FM with feedback. Classic DX-style electric bass and plucky tones. CUTOFF controls modulator index.
        - **3 = Bass Drum**: Enveloped 808+909 kick synthesis. Has its own built-in envelope (ENV knob controls internal decay instead). Great for hybrid kick-bass.

        ## Sequencer
        The bass has a built-in step sequencer that starts with a driving pattern (all gates ON, root on downbeats, random scale notes on off-beats, accents on 1 and 3).
        - **ROOT** (bass_root_note): Root note, MIDI 24-47 (C1-B2). Default C2 (36). Set to match your key.
        - **SCALE** (bass_scale): Quantization — 0=Chromatic, 1=Minor Pentatonic (default), 2=Minor, 3=Major, 4=Dorian, 5=Whole Tone.
        - **CLOCK** (bass_clock_div): Clock division — 0=1/4x (quarter), 1=1/2x (8th), 2=1x (16th, default), 3=2x (32nd), 4=4x (64th).
        - **STEPS** (bass_step_count): Sequence length — 4, 8, 12, or 16 steps. Default 16.
        - **MUTATION** (bass_mutation): Pattern evolution 0-1. At 0 the pattern is locked. 0-0.5 mutates pitches only. 0.5-1.0 also mutates gates and accents. At 1.0 fully random each cycle (still scale-quantized).

        ## Sound Shaping
        - **CUTOFF** (bass_cutoff): Filter cutoff 0-1. Controls Plaits timbre parameter. Low=dark, high=bright.
        - **RESO** (bass_resonance): Filter resonance 0-1. Controls Plaits harmonics. Adds squelch and bite.
        - **ENV** (bass_envelope): Envelope snap 0-1. 0=slow attack/long decay, 1=snappy attack/short decay. High values add quartic shaping for extra punch.
        - **DRIVE** (bass_overdrive): Overdrive saturation 0-1. 0=clean passthrough, 0.3-0.5=warm, 0.7+=heavy distortion.
        - **COMP** (bass_compressor): Compressor amount 0-1. Auto-threshold, adds body and sustain. 0=off, 0.5=punchy, 1=squashed.
        - **MIX** (bass_mix): Output level 0-1. 0=fully bypassed (zero CPU), controls bass volume in the master mix.
        - **LFO MIX** (bass_lfo_mix): LFO modulation depth 0-1. Scales how much the active LFO source modulates bass parameters. ch0→cutoff, ch1→resonance, ch2→pitch (±0.5 semitone), ch3→envelope.
        - **ACCENT** (bass_accent_amount): Accent intensity 0-1. Boosts velocity, filter cutoff flare (fast attack/slow decay), envelope snap, and overdrive on accented steps.
        - **JITTER** (bass_jitter): Timing humanization 0-1. Randomizes step timing ±15% and shortens gate hold duration (50-100%) for a looser, more human feel.

        ## Tips for AI
        - Start beats FIRST (beats_run=1), then set bass_mix to 0.5-0.8 to bring in the bassline.
        - Match bass_root_note to the key of your composition (e.g., 36=C2, 33=A1, 38=D2).
        - For acid: VCF engine, high RESO (0.6+), modulate CUTOFF over time.
        - For EDM: high ENV (0.8+), moderate DRIVE (0.3), COMP (0.5) for punchy transients.
        - For ambient: FM engine, low ENV (0.2), high MUTATION (0.5+) for evolving patterns.
        - MUTATION is great for evolution — slowly ramp from 0 to 0.3 over time for subtle drift.
        - Bass feeds delay and reverb — use delay_mix for rhythmic bounce, reverb for depth.
            """.trimIndent()

            override val portControlKeys = mapOf(
                BassSymbol.ENGINE.controlId.key to "Synthesis engine (integer: 0=VCF Acid, 1=Phase Dist, 2=FM, 3=Bass Drum)",
                BassSymbol.ROOT_NOTE.controlId.key to "Root note (integer MIDI note: 24=C1 to 47=B2, default 36=C2)",
                BassSymbol.SCALE.controlId.key to "Scale (integer: 0=Chromatic, 1=Minor Pent, 2=Minor, 3=Major, 4=Dorian, 5=Whole Tone)",
                BassSymbol.CLOCK_DIV.controlId.key to "Clock division (integer: 0=1/4x, 1=1/2x, 2=1x, 3=2x, 4=4x)",
                BassSymbol.STEP_COUNT.controlId.key to "Sequence steps (integer: 4, 8, 12, or 16)",
                BassSymbol.MUTATION.controlId.key to "Pattern mutation (0=locked, 0.5=pitch drift, 1=full random)",
                BassSymbol.CUTOFF.controlId.key to "Filter cutoff (0=dark, 1=bright)",
                BassSymbol.RESONANCE.controlId.key to "Filter resonance / squelch (0=clean, 1=screaming)",
                BassSymbol.ENVELOPE.controlId.key to "Envelope snap (0=slow/sustained, 1=snappy/punchy)",
                BassSymbol.OVERDRIVE.controlId.key to "Overdrive saturation (0=clean, 1=heavy distortion)",
                BassSymbol.COMPRESSOR.controlId.key to "Compressor amount (0=off, 1=squashed)",
                BassSymbol.MIX.controlId.key to "Output level / on-off (0=bypassed, 1=full volume)",
                BassSymbol.LFO_MIX.controlId.key to "LFO modulation depth (0=none, 1=full — modulates cutoff, resonance, pitch, envelope)",
                BassSymbol.ACCENT_AMOUNT.controlId.key to "Accent intensity (0=no accent, 0.5=default, 1=maximum accent — boosts filter cutoff, envelope punch, and overdrive on accented steps)",
                BassSymbol.JITTER.controlId.key to "Timing humanization (0=locked grid, 1=maximum jitter — randomizes step timing ±15% and gate hold duration 50-100%)",
                BassSymbol.FX_SEND.controlId.key to "Effects send level — routes bass to delay, reverb, and grains (0=off, 1=full send)",
            )
        }
    }
}

/**
 * ViewModel for the Bass voice panel.
 *
 * Uses MVI pattern with SynthController.controlFlow() for all engine interactions.
 */
@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(BassFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<BassFeature>())
class BassViewModel(
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
    scope: FeatureCoroutineScope,
) : BassFeature {

    // Control flows for Bass plugin ports
    private val engineId = synthController.controlFlow(BassSymbol.ENGINE.controlId)
    private val rootNoteId = synthController.controlFlow(BassSymbol.ROOT_NOTE.controlId)
    private val scaleId = synthController.controlFlow(BassSymbol.SCALE.controlId)
    private val clockDivId = synthController.controlFlow(BassSymbol.CLOCK_DIV.controlId)
    private val stepCountId = synthController.controlFlow(BassSymbol.STEP_COUNT.controlId)
    private val mutationId = synthController.controlFlow(BassSymbol.MUTATION.controlId)
    private val cutoffId = synthController.controlFlow(BassSymbol.CUTOFF.controlId)
    private val resonanceId = synthController.controlFlow(BassSymbol.RESONANCE.controlId)
    private val envelopeId = synthController.controlFlow(BassSymbol.ENVELOPE.controlId)
    private val overdriveId = synthController.controlFlow(BassSymbol.OVERDRIVE.controlId)
    private val compressorId = synthController.controlFlow(BassSymbol.COMPRESSOR.controlId)
    private val mixId = synthController.controlFlow(BassSymbol.MIX.controlId)
    private val lfoMixId = synthController.controlFlow(BassSymbol.LFO_MIX.controlId)
    private val accentAmountId = synthController.controlFlow(BassSymbol.ACCENT_AMOUNT.controlId)
    private val jitterId = synthController.controlFlow(BassSymbol.JITTER.controlId)
    private val fxSendId = synthController.controlFlow(BassSymbol.FX_SEND.controlId)
    private val triggerSourceId = synthController.controlFlow(BassSymbol.TRIGGER_SOURCE.controlId)
    private val pitchSourceId = synthController.controlFlow(BassSymbol.PITCH_SOURCE.controlId)
    private val timbreSourceId = synthController.controlFlow(BassSymbol.TIMBRE_SOURCE.controlId)

    override val actions = BassPanelActions(
        setEngine = engineId.enumSetter(),
        setRootNote = rootNoteId.intSetter(),
        setScale = scaleId.enumSetter(),
        setClockDivision = clockDivId.enumSetter(),
        setStepCount = stepCountId.intSetter(),
        setMutation = mutationId.floatSetter(),
        setCutoff = cutoffId.floatSetter(),
        setResonance = resonanceId.floatSetter(),
        setEnvelope = envelopeId.floatSetter(),
        setOverdrive = overdriveId.floatSetter(),
        setCompressor = compressorId.floatSetter(),
        setMix = mixId.floatSetter(),
        setLfoMix = lfoMixId.floatSetter(),
        setAccentAmount = accentAmountId.floatSetter(),
        setJitter = jitterId.floatSetter(),
        setFxSend = fxSendId.floatSetter(),
        setTriggerSource = triggerSourceId.intSetter(),
        setPitchSource = pitchSourceId.intSetter(),
        setTimbreSource = timbreSourceId.intSetter(),
    )

    // Control changes -> BassIntent
    private val controlIntents = merge(
        engineId.map {
            val engines = BassEngine.entries
            val index = it.asInt().coerceIn(0, engines.size - 1)
            BassIntent.SetEngine(engines[index])
        },
        rootNoteId.map { BassIntent.SetRootNote(it.asInt()) },
        scaleId.map {
            val scales = BassScale.entries
            val index = it.asInt().coerceIn(0, scales.size - 1)
            BassIntent.SetScale(scales[index])
        },
        clockDivId.map {
            val divisions = ClockDivision.entries
            val index = it.asInt().coerceIn(0, divisions.size - 1)
            BassIntent.SetClockDivision(divisions[index])
        },
        stepCountId.map { BassIntent.SetStepCount(it.asInt()) },
        mutationId.map { BassIntent.SetMutation(it.asFloat()) },
        cutoffId.map { BassIntent.SetCutoff(it.asFloat()) },
        resonanceId.map { BassIntent.SetResonance(it.asFloat()) },
        envelopeId.map { BassIntent.SetEnvelope(it.asFloat()) },
        overdriveId.map { BassIntent.SetOverdrive(it.asFloat()) },
        compressorId.map { BassIntent.SetCompressor(it.asFloat()) },
        mixId.map { BassIntent.SetMix(it.asFloat()) },
        lfoMixId.map { BassIntent.SetLfoMix(it.asFloat()) },
        accentAmountId.map { BassIntent.SetAccentAmount(it.asFloat()) },
        jitterId.map { BassIntent.SetJitter(it.asFloat()) },
        fxSendId.map { BassIntent.SetFxSend(it.asFloat()) },
        triggerSourceId.map { BassIntent.SetTriggerSource(it.asInt()) },
        pitchSourceId.map { BassIntent.SetPitchSource(it.asInt()) },
        timbreSourceId.map { BassIntent.SetTimbreSource(it.asInt()) },
    )

    override val stateFlow: StateFlow<BassUiState> =
        controlIntents
            .scan(BassUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = SynthFeature.sharingStrategy,
                initialValue = BassUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: BassUiState, intent: BassIntent): BassUiState =
        when (intent) {
            is BassIntent.SetEngine -> state.copy(engine = intent.engine)
            is BassIntent.SetRootNote -> state.copy(rootNote = intent.value)
            is BassIntent.SetScale -> state.copy(scale = intent.scale)
            is BassIntent.SetClockDivision -> state.copy(clockDivision = intent.division)
            is BassIntent.SetStepCount -> state.copy(stepCount = intent.value)
            is BassIntent.SetMutation -> state.copy(mutation = intent.value)
            is BassIntent.SetCutoff -> state.copy(cutoff = intent.value)
            is BassIntent.SetResonance -> state.copy(resonance = intent.value)
            is BassIntent.SetEnvelope -> state.copy(envelope = intent.value)
            is BassIntent.SetOverdrive -> state.copy(overdrive = intent.value)
            is BassIntent.SetCompressor -> state.copy(compressor = intent.value)
            is BassIntent.SetMix -> state.copy(mix = intent.value)
            is BassIntent.SetLfoMix -> state.copy(lfoMix = intent.value)
            is BassIntent.SetAccentAmount -> state.copy(accentAmount = intent.value)
            is BassIntent.SetJitter -> state.copy(jitter = intent.value)
            is BassIntent.SetFxSend -> state.copy(fxSend = intent.value)
            is BassIntent.SetTriggerSource -> state.copy(triggerSource = intent.value)
            is BassIntent.SetPitchSource -> state.copy(pitchSource = intent.value)
            is BassIntent.SetTimbreSource -> state.copy(timbreSource = intent.value)
        }

    companion object {

        fun previewFeature(state: BassUiState = BassUiState()): BassFeature =
            object : BassFeature {
                override val stateFlow: StateFlow<BassUiState> = MutableStateFlow(state)
                override val actions: BassPanelActions = BassPanelActions.EMPTY
            }
    }
}
