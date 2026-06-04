package org.balch.orpheus.playback

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.media.PlaybackMode
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.features.ai.AiOptionsFeature
import org.balch.orpheus.features.pulsar.playback.PulsarMetadataProducer

/**
 * Orpheus's primary metadata producer: when an Orpheus-side activity is
 * running (DRONE/SOLO/REPL via AI, or audio Evo), advertise that. Otherwise
 * delegate to Pulsar's metadata.
 *
 * Evo is observed via MediaSessionStateManager (the existing source of truth)
 * rather than added to AiOptionsFeature so the AI module stays unaware of Evo.
 *
 * Priority when both AI mode and Evo are active: AI wins. Drone/Solo are the
 * loudest signals; Evo is parameter motion underneath whatever else is going.
 */
@SingleIn(AppScope::class)
@Inject
class OrpheusMetadataProducer(
    private val aiFeature: AiOptionsFeature,
    private val pulsarMetadata: PulsarMetadataProducer,
    private val mediaSessionStateManager: MediaSessionStateManager,
    private val scope: AppCoroutineScope,
) : MetadataProducer {

    // The "displayed" Orpheus mode: AI mode wins if non-USER, else fall back
    // to EVO if Evo is active, else USER (which delegates to Pulsar).
    private fun resolveMode(aiMode: PlaybackMode, isEvoActive: Boolean): PlaybackMode = when {
        aiMode != PlaybackMode.USER -> aiMode
        isEvoActive -> PlaybackMode.EVO
        else -> PlaybackMode.USER
    }

    private val orpheusMode: StateFlow<PlaybackMode> =
        combine(aiFeature.currentMode, mediaSessionStateManager.isEvoActive, ::resolveMode)
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                resolveMode(aiFeature.currentMode.value, mediaSessionStateManager.isEvoActive.value),
            )

    // Initial values are computed from the current state of the inputs so the
    // notification reflects the active mode immediately at app launch — without
    // this, users would briefly see a hardcoded "Orpheus" / "" before the
    // combine collector caught up.
    private fun computeTitle(mode: PlaybackMode, pulsarTitle: String): String =
        if (mode == PlaybackMode.USER) pulsarTitle else "Orpheus"

    private fun computeSubtitle(mode: PlaybackMode, pulsarSubtitle: String): String =
        if (mode == PlaybackMode.USER) pulsarSubtitle else mode.displayName

    override val titleFlow: StateFlow<String> =
        combine(orpheusMode, pulsarMetadata.titleFlow, ::computeTitle)
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                computeTitle(orpheusMode.value, pulsarMetadata.titleFlow.value),
            )

    override val subtitleFlow: StateFlow<String> =
        combine(orpheusMode, pulsarMetadata.subtitleFlow, ::computeSubtitle)
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                computeSubtitle(orpheusMode.value, pulsarMetadata.subtitleFlow.value),
            )

    // Pass Pulsar's procedural artwork through unchanged. When AI / Evo modes
    // are active, we still surface Pulsar's vibe artwork — there isn't an
    // Orpheus-side image to advertise, and showing the underlying vibe gives
    // the listener visual continuity.
    override val artworkPngFlow: StateFlow<ByteArray?> = pulsarMetadata.artworkPngFlow
}
