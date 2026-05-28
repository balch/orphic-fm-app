package org.balch.orpheus.core.playback

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionActionHandler
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.media.PlaybackMetadata

/**
 * Single source of truth for app-wide playback state. See
 * docs/superpowers/specs/2026-04-28-playback-controller-design.md.
 *
 * All play/pause/stop inputs (notification button, in-app UI, system
 * controls, lifecycle stop) flow through this class's mutators.
 * All consumers (mute, MediaSession, lifecycle broadcast) are driven *out*.
 * No back-channels. The dual-writer race is structurally impossible.
 */
@SingleIn(AppScope::class)
@Inject
class PlaybackController(
    private val mediaSessionManager: MediaSessionManager,
    private val mediaSessionStateManager: MediaSessionStateManager,
    private val playbackLifecycleManager: PlaybackLifecycleManager,
    private val muteSink: MuteSink,
    private val metadataProducer: MetadataProducer,
    private val scope: AppCoroutineScope,
    private val overlayProducer: OverlaySubtitleProducer? = null,
    private val skipHandler: SkipHandler? = null,
    private val playFromMediaIdHandler: PlayFromMediaIdHandler? = null,
) : MediaSessionActionHandler {
    private val log = logging("PlaybackController")

    // _state is written ONLY from this class's mutators (play/pause/stop), all of
    // which are expected to be called from a single thread (Android MediaSession's
    // handler thread or the main thread). MutableStateFlow.value writes are
    // @Volatile on JVM; concurrent double-fires of the same mutator are harmless
    // because muteSink.apply() and updatePlaybackState() are idempotent.
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Stopped)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun play() {
        if (_state.value == PlaybackState.Playing) return
        log.info { "play() (was ${_state.value})" }
        // Synchronously gate on audio focus BEFORE unmuting. On Android, a
        // denied request used to leak audio out through an unmuted sink with
        // no foreground service; rolling back to a no-op keeps the previous
        // state and lets the user retry. Non-Android actuals return true.
        if (!mediaSessionManager.requestPlaybackFocus()) {
            log.warn { "play() blocked — audio focus denied; staying ${_state.value}" }
            return
        }
        _state.value = PlaybackState.Playing
        muteSink.apply(PlaybackState.Playing)
        mediaSessionManager.activate()
        mediaSessionManager.updatePlaybackState(true)
    }

    fun pause() {
        // Always signal user-intent, even on no-op. If a transient focus loss
        // had already paused us, the user tapping pause again here is their
        // way of saying "stay paused, don't auto-resume on focus gain."
        mediaSessionManager.notifyUserPaused()
        if (_state.value != PlaybackState.Playing) return
        log.info { "pause() (was Playing)" }
        _state.value = PlaybackState.Paused
        muteSink.apply(PlaybackState.Paused)
        mediaSessionManager.updatePlaybackState(false)
    }

    fun stop() {
        mediaSessionManager.notifyUserPaused()
        if (_state.value == PlaybackState.Stopped) return
        log.info { "stop() (was ${_state.value})" }
        _state.value = PlaybackState.Stopped
        muteSink.apply(PlaybackState.Stopped)
        // Deactivate the media session synchronously so any downstream emissions
        // (the metadata combine collector below reacting to _state, or
        // tryRequestStopAll → SynthOrchestrator → clearAll ricochets) become
        // no-ops via the isActive guard inside MediaSessionManager.
        //
        // Without this, the prior updatePlaybackState(false) call would queue
        // a context.startService() intent that re-launched the foreground
        // service we are about to stop — visible as the notification reappearing
        // immediately after the user tapped Stop in the notification.
        mediaSessionManager.deactivate()
        playbackLifecycleManager.tryRequestStopAll()
    }

    // MediaSessionActionHandler — installed by wireMediaSessionActions() in Task 7
    override fun onPlay() = play()
    override fun onPause() = pause()
    override fun onStop() = stop()
    override fun onSkipNext() { skipHandler?.onSkip(SkipDirection.NEXT) }
    override fun onSkipPrevious() { skipHandler?.onSkip(SkipDirection.PREVIOUS) }
    override fun onPlayFromMediaId(mediaId: String) { playFromMediaIdHandler?.onPlay(mediaId) }

    /**
     * Platform-initiated transient pause (e.g., Android AUDIOFOCUS_LOSS_TRANSIENT).
     * Same effect as pause() on local state, but does NOT call notifyUserPaused —
     * pausedByTransient must stay set so the matching AUDIOFOCUS_GAIN auto-resumes.
     */
    override fun onPauseFromFocusLoss() {
        if (_state.value != PlaybackState.Playing) return
        log.info { "onPauseFromFocusLoss (was Playing)" }
        _state.value = PlaybackState.Paused
        muteSink.apply(PlaybackState.Paused)
        mediaSessionManager.updatePlaybackState(false)
    }

    private val overlayFlow: StateFlow<String?> =
        overlayProducer?.overlayFlow ?: MutableStateFlow(null)

    private fun snapshotOf(
        title: String,
        primarySubtitle: String,
        overlay: String?,
        state: PlaybackState,
        artworkPng: ByteArray?,
    ): MetadataSnapshot = MetadataSnapshot(
        title = title.ifEmpty { "Orpheus" },
        subtitle = overlay ?: primarySubtitle,
        isPlaying = state == PlaybackState.Playing,
        artworkPng = artworkPng,
    )

    // Materialized as a StateFlow so the activation handler can read the
    // current snapshot synchronously and force-push it after activate().
    // Without that force-push, the first vibe goes missing on platforms whose
    // updateMetadata is a no-op while inactive (Android — no replay cache):
    // the combine would emit once at startup, get discarded, and then
    // distinctUntilChanged would suppress every identical re-emit until the
    // user changed vibes.
    private val metadataSnapshotFlow: StateFlow<MetadataSnapshot> =
        combine(
            metadataProducer.titleFlow,
            metadataProducer.subtitleFlow,
            overlayFlow,
            _state,
            metadataProducer.artworkPngFlow,
        ) { title, primarySubtitle, overlay, state, artworkPng ->
            snapshotOf(title, primarySubtitle, overlay, state, artworkPng)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            snapshotOf(
                metadataProducer.titleFlow.value,
                metadataProducer.subtitleFlow.value,
                overlayFlow.value,
                _state.value,
                metadataProducer.artworkPngFlow.value,
            ),
        )

    private fun pushMetadata(snapshot: MetadataSnapshot) {
        mediaSessionManager.updateMetadata(
            PlaybackMetadata(
                title = snapshot.title,
                isPlaying = snapshot.isPlaying,
                subtitle = snapshot.subtitle,
                artworkPng = snapshot.artworkPng,
            )
        )
    }

    init {
        mediaSessionManager.setActionHandler(this)
        scope.launch {
            mediaSessionStateManager.isMediaSessionNeeded.collect { needed ->
                if (needed) {
                    mediaSessionManager.activate()
                    // Force-push current metadata so the now-playing widget
                    // shows the active vibe even if the metadata combine fired
                    // before we became active and was discarded by the platform
                    // actual. Idempotent on platforms whose actuals already
                    // cache + replay (iOS / JVM).
                    pushMetadata(metadataSnapshotFlow.value)
                    // If a system command (Auto bind, Bluetooth) needs us active
                    // while we're Stopped, transition to Playing so audio starts.
                    if (_state.value == PlaybackState.Stopped) play()
                    else mediaSessionManager.updatePlaybackState(_state.value == PlaybackState.Playing)
                } else {
                    mediaSessionManager.deactivate()
                }
            }
        }
        // StateFlow already drops equal-by-equals re-emissions, and our
        // MetadataSnapshot data class compares ByteArray by reference (which
        // is what we want — see the class doc below). So no .distinctUntilChanged()
        // operator is needed here.
        scope.launch {
            metadataSnapshotFlow.collect { snapshot -> pushMetadata(snapshot) }
        }
        // All media actions (play/pause/stop/skip/playFromMediaId) route through
        // the MediaSessionActionHandler interface set above. No parallel lambda
        // paths remain.
    }
}

// Kotlin data class with a ByteArray property uses ARRAY REFERENCE equality
// (since arrays don't override equals). That's exactly what we want here:
// the producer caches and re-emits the same array instance per vibe, so
// reference equality cheaply captures "did the artwork actually change".
private data class MetadataSnapshot(
    val title: String,
    val subtitle: String,
    val isPlaying: Boolean,
    val artworkPng: ByteArray?,
)
