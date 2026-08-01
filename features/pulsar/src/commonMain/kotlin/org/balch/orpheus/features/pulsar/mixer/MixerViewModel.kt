package org.balch.orpheus.features.pulsar.mixer

import androidx.compose.runtime.Composable
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.FeatureStatePersistence
import org.balch.orpheus.core.features.RestoreStrategy
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.engagement.EngagementAction
import org.balch.orpheus.core.engagement.EngagementTracker
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.features.pulsar.PulsarFeature

private const val DEFAULT_DRIVE = 0.30f

/** Internal MVI intents for the Mixer panel. */
private sealed interface MixerIntent {
    data class GainChange(val group: MixerGroup, val value: Float) : MixerIntent
    data class DriveChange(val value: Float) : MixerIntent
    data class PeakChange(val value: Float) : MixerIntent
    data class MuteListChange(val perGroup: List<Boolean>) : MixerIntent
    data class PlayingChange(val playing: Boolean) : MixerIntent
}

/**
 * ViewModel for the band mixer panel.
 *
 * Each fader writes a dedicated per-band gain port that the C++ render loop
 * multiplies on top of the section-driven `pulsar_track_volume`:
 * - PERC fader → [PulsarSymbol.PERC_MIX]   (multiplies into tracks 0-2).
 * - BASS fader → [PulsarSymbol.BASS_GAIN]  (multiplies into TRACK_3_VOLUME).
 * - KEYS fader → [PulsarSymbol.KEYS_GAIN]  (multiplies into TRACK_4_VOLUME).
 * - FX   fader → [PulsarSymbol.FX_GAIN]    (multiplies into TRACK_5/6/7_VOLUME).
 *
 * This keeps section transitions free to write `pulsar_track_volume` for the
 * vibe contour without clobbering the user's faders — both compose at render.
 *
 * Fader values are echoed back from the [SynthController.controlFlow]s. DIST
 * drives both [DistortionSymbol.DRIVE] and [DistortionSymbol.MIX] together so
 * the stage is bypassed only when MIX=0. Peak meter mirrors the engine peak
 * flow used by DistortionPanel.
 */
@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(MixerFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<MixerFeature>())
class MixerViewModel(
    engine: SynthEngine,
    synthController: SynthController,
    pulsarFeature: PulsarFeature,
    persistence: FeatureStatePersistence,
    private val engagementTracker: EngagementTracker,
    private val restoreStrategy: RestoreStrategy,
    dispatcherProvider: DispatcherProvider,
    scope: FeatureCoroutineScope,
) : MixerFeature {

    private val driveId = synthController.controlFlow(DistortionSymbol.DRIVE.controlId)
    private val mixId = synthController.controlFlow(DistortionSymbol.MIX.controlId)
    private val percMixId  = synthController.controlFlow(PulsarSymbol.PERC_MIX.controlId)
    private val bassGainId = synthController.controlFlow(PulsarSymbol.BASS_GAIN.controlId)
    private val keysGainId = synthController.controlFlow(PulsarSymbol.KEYS_GAIN.controlId)
    private val fxGainId   = synthController.controlFlow(PulsarSymbol.FX_GAIN.controlId)

    override val actions = MixerPanelActions(
        setGroupGain = ::onGroupGainChanged,
        setDrive = ::onDriveChanged,
    )

    // Peak monitoring (existing flow, same source as DistortionPanel today).
    private val peakIntents = engine.peakFlow.map { MixerIntent.PeakChange(it) }

    // DIST fader echoes engine drive port back into UI state.
    private val driveIntents = driveId.map { MixerIntent.DriveChange(it.asFloat()) }

    // PERC fader echoes the dedicated PERC_MIX port — auto-syncs with PulsarPanel.
    private val percMixIntents = percMixId.map {
        MixerIntent.GainChange(MixerGroup.PERC, it.asFloat())
    }

    // BASS fader echoes the dedicated BASS_GAIN port.
    private val bassMixIntents = bassGainId.map {
        MixerIntent.GainChange(MixerGroup.BASS, it.asFloat())
    }

    // KEYS fader echoes the dedicated KEYS_GAIN port.
    private val keysMixIntents = keysGainId.map {
        MixerIntent.GainChange(MixerGroup.KEYS, it.asFloat())
    }

    // FX fader echoes the dedicated FX_GAIN port.
    private val fxMixIntents = fxGainId.map {
        MixerIntent.GainChange(MixerGroup.FX, it.asFloat())
    }

    // Pulsar's UI state carries trackMuted; map to per-group.
    private val muteIntents = pulsarFeature.stateFlow.map { pulsarState ->
        MixerIntent.MuteListChange(
            MixerGroup.entries.map { isGroupMuted(it, pulsarState.trackMuted) }
        )
    }

    // Mirror Pulsar's playing state so the meter loop can fast-decay to 0 when stopped.
    // distinctUntilChanged on the upstream boolean avoids redundant emissions.
    private val playingIntents = pulsarFeature.stateFlow
        .map { it.playing }
        .distinctUntilChanged()
        .map { MixerIntent.PlayingChange(it) }

    override val stateFlow: StateFlow<MixerUiState> =
        merge(
            peakIntents,
            driveIntents,
            percMixIntents,
            bassMixIntents,
            keysMixIntents,
            fxMixIntents,
            muteIntents,
            playingIntents,
        )
            .scan(MixerUiState()) { state, intent -> reduce(state, intent) }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = SynthFeature.sharingStrategy,
                initialValue = MixerUiState(),
            )

    init {
        persistence.bind(
            stateFlow = stateFlow,
            serializer = MixerUiState.serializer(),
            reader = { it.lastMixerJson },
            writer = { prefs, json -> prefs.copy(lastMixerJson = json) },
            restoreStrategy = restoreStrategy,
            stripTransient = {
                // Per-band fader values are NOT owned by the mixer — they're echoes
                // of dedicated gain ports (PERC_MIX, BASS_GAIN, KEYS_GAIN, FX_GAIN).
                // Console-fader convention: 0.75 = unity. The C++ atomics all
                // default to 0.75, so the UI placeholders here match the "unity"
                // point until the echo flows deliver real values. Only the DIST
                // drive belongs to us; everything else is transient.
                it.copy(
                    peak = 0f,
                    groupMuted = listOf(false, false, false, false),
                    playing = false,
                    groupGains = listOf(0.75f, 0.75f, 0.75f, 0.75f),
                )
            },
            onRestore = { saved ->
                // Restore only the DIST fader. Per-band gains come from their own
                // engine atomics (default 1.0) + the controlFlow echo path.
                val drive = saved.drive.coerceIn(0f, 1f)
                driveId.value = FloatValue(drive)
                mixId.value = FloatValue(drive)
            },
        )
        // Default drive for new users — persistence.bind() restore is async,
        // so this value sticks for new users and gets overwritten for existing ones.
        driveId.value = FloatValue(DEFAULT_DRIVE)
        mixId.value = FloatValue(DEFAULT_DRIVE)
    }

    private fun onGroupGainChanged(group: MixerGroup, value: Float) {
        engagementTracker.record(EngagementAction.GAIN_ADJUST)
        val clamped = value.coerceIn(0f, 1f)
        when (group) {
            MixerGroup.PERC -> percMixId.value  = FloatValue(clamped)
            MixerGroup.BASS -> bassGainId.value = FloatValue(clamped)
            MixerGroup.KEYS -> keysGainId.value = FloatValue(clamped)
            MixerGroup.FX   -> fxGainId.value   = FloatValue(clamped)
        }
        // No tryEmit — the per-port echo flows above will fire and update state.
    }

    private fun onDriveChanged(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        // Distortion's MIX defaults to 0 (bypassed). The MixerPanel exposes only
        // a single DIST fader so we drive both DRIVE and MIX 1:1 — zero MIX means
        // silent distortion stage regardless of DRIVE.
        driveId.value = FloatValue(clamped)
        mixId.value = FloatValue(clamped)
        // driveIntents echo will update UI state.
    }

    private fun reduce(state: MixerUiState, intent: MixerIntent): MixerUiState =
        when (intent) {
            is MixerIntent.GainChange -> state.copy(
                groupGains = state.groupGains.toMutableList().also {
                    it[intent.group.ordinal] = intent.value
                }
            )
            is MixerIntent.DriveChange -> state.copy(drive = intent.value)
            is MixerIntent.PeakChange -> state.copy(peak = intent.value)
            is MixerIntent.MuteListChange -> state.copy(groupMuted = intent.perGroup)
            is MixerIntent.PlayingChange -> state.copy(playing = intent.playing)
        }

    companion object {
        fun previewFeature(state: MixerUiState = MixerUiState()): MixerFeature =
            object : MixerFeature {
                override val stateFlow: StateFlow<MixerUiState> = MutableStateFlow(state)
                override val actions: MixerPanelActions = MixerPanelActions.EMPTY
            }

        @Composable
        fun feature(): MixerFeature =
            synthFeature<MixerFeature>()
    }
}
