package org.balch.djapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.diamondedge.logging.logging
import org.balch.orpheus.core.media.ForegroundServiceController
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.Vibe
import java.util.Locale

/**
 * Single combined service for the DJ app that:
 *   - Serves the browsable media tree to Android Auto
 *   - Owns the MediaSession (used by notification, lock screen, and Auto)
 *   - Promotes itself to a foreground service with media-style notification
 *     once playback is active
 *
 * Merging browser + foreground responsibilities into one service avoids the
 * session-token-race that breaks Android Auto when the browser and the
 * playback service live in separate processes.
 */
class DjMediaBrowserService : MediaBrowserServiceCompat() {

    private val log = logging("DjMediaBrowser")

    private var mediaSession: MediaSessionCompat? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    // Set when a transient focus loss caused us to pause; cleared once we
    // auto-resume on GAIN. Permanent LOSS leaves this false so the user must
    // manually resume.
    private var wasPlayingBeforeFocusLoss = false
    private var zeroToOneArt: Bitmap? = null
    private var rifArt: Bitmap? = null
    private var stealthRenderer: ProceduralArtRenderer? = null
    private var isPlaying = true
    private var currentTitle = "Orphic DJ"
    private var primarySubtitle = ""
    private var isForegroundStarted = false
    // Set true once ACTION_STOP is processed. Defends against late intents
    // (queued startService calls from updateMetadata/updatePlaybackState
    // ricochets) re-promoting us back to foreground after teardown.
    private var isShuttingDown = false

    private var cachedFeature: PulsarFeature? = null

    private val pulsarFeature: PulsarFeature?
        get() {
            cachedFeature?.let { return it }
            return try {
                // Read the *shared* FeatureGraph from the AppScope holder.
                // Calling factory.create() here would build a second graph
                // with a second PulsarViewModel that silently overwrites the
                // Activity-side VM's MediaSessionManager handlers — leading
                // to the UI/notification de-sync this service used to cause.
                val graph = DjAppApplication.getGraph(this)
                val featureGraph = graph.featureGraphHolder.featureGraph
                val feature = featureGraph.featureCollection
                    .getFeature<PulsarFeature>(PulsarViewModel::class)
                cachedFeature = feature
                feature
            } catch (e: Exception) {
                log.warn { "PulsarFeature not available: ${e.message}" }
                null
            }
        }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "djapp_audio_playback"
        private const val ROOT_ID = "djapp_root"
        private const val ALBUM_PREFIX = "album_"
        // mediaIds: ROOT_ID -> "album_<Album.name>" (browsable) -> "<vibe.name>" (playable)
        // Stable schema lets PulsarVibePicker keep matching by vibe.name unchanged.

        private val ACTION_PLAY = ForegroundServiceController.ACTION_PLAY
        private val ACTION_PAUSE = ForegroundServiceController.ACTION_PAUSE
        private val ACTION_STOP = ForegroundServiceController.ACTION_STOP
        private val ACTION_SKIP_NEXT = ForegroundServiceController.ACTION_SKIP_NEXT
        private val ACTION_SKIP_PREVIOUS = ForegroundServiceController.ACTION_SKIP_PREVIOUS
        private val ACTION_UPDATE_STATE_PLAYING = ForegroundServiceController.ACTION_UPDATE_STATE_PLAYING
        private val ACTION_UPDATE_STATE_PAUSED = ForegroundServiceController.ACTION_UPDATE_STATE_PAUSED
        private val ACTION_UPDATE_METADATA = ForegroundServiceController.ACTION_UPDATE_METADATA
        private val EXTRA_TITLE = ForegroundServiceController.EXTRA_TITLE
        private val EXTRA_SUBTITLE = ForegroundServiceController.EXTRA_SUBTITLE
        private val EXTRA_IS_PLAYING = ForegroundServiceController.EXTRA_IS_PLAYING

        @Volatile var actionHandler: ((String) -> Unit)? = null
        @Volatile var playFromMediaIdHandler: ((String) -> Unit)? = null

        private val NOTIFICATION_COLOR = Color.parseColor("#7B68EE")
    }

    override fun onCreate() {
        super.onCreate()
        zeroToOneArt = BitmapFactory.decodeResource(resources, R.drawable.album_art_021)
        rifArt = BitmapFactory.decodeResource(resources, R.drawable.album_art_rif)
        stealthRenderer = ProceduralArtRenderer()
        createNotificationChannel()
        setupMediaSession()

        // Warm the audio engine + Pulsar stack so by the time Auto/Assistant
        // sends a playback command, the engine is running and action handlers
        // are wired through MediaSessionManager.
        //
        // Engine start happens HERE (not in the Compose UI) so Android Auto
        // works without launching MainActivity. SynthOrchestrator.start() is
        // idempotent — DjApp's LaunchedEffect also calls it on the launcher
        // path where no service is bound yet, and the second call is a no-op.
        //
        // AUTO_BROWSER keeps the session alive independent of mute/play state.
        // Logged at error level: if this fails, Auto play commands silently
        // do nothing, so the failure needs to be loud in logcat.
        try {
            val graph = DjAppApplication.getGraph(this)
            graph.synthOrchestrator.start()
            graph.mediaSessionStateManager.setAutoBrowserActive(true)
            pulsarFeature // trigger ViewModel init (wires MediaSessionActionHandler)
        } catch (e: Exception) {
            log.error(e) { "Failed to warm up audio engine — Auto play commands will be no-ops" }
        }

        log.info { "DjMediaBrowserService created" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log.info { "DjMediaBrowserService onStartCommand: action=${intent?.action} shuttingDown=$isShuttingDown" }

        if (isShuttingDown) {
            // We've already accepted ACTION_STOP. Drop any late-arriving
            // updates so we don't re-promote to foreground via ensureForeground().
            log.debug { "Ignoring ${intent?.action} while shutting down" }
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_PLAY -> {
                acquireAudioFocus()
                isPlaying = true
                updatePlaybackState(true)
                actionHandler?.invoke("play")
            }
            ACTION_PAUSE -> {
                isPlaying = false
                updatePlaybackState(false)
                actionHandler?.invoke("pause")
            }
            ACTION_STOP -> {
                isShuttingDown = true
                actionHandler?.invoke("stop")
                abandonAudioFocus()
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForegroundStarted = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SKIP_NEXT -> actionHandler?.invoke("skipNext")
            ACTION_SKIP_PREVIOUS -> actionHandler?.invoke("skipPrevious")
            ACTION_UPDATE_STATE_PLAYING -> {
                // The PlaybackController-driven path lands here whenever the
                // app transitions into Playing (auto-resume on Auto bind, in-app
                // tap, etc.). Without acquiring focus here, other media apps
                // (Spotify, Apple Music) never receive AUDIOFOCUS_LOSS and
                // continue playing on top of us.
                acquireAudioFocus()
                isPlaying = true
                updatePlaybackState(true)
            }
            ACTION_UPDATE_STATE_PAUSED -> {
                isPlaying = false
                updatePlaybackState(false)
            }
            ACTION_UPDATE_METADATA -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Orphic DJ"
                val subtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: ""
                val intentIsPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)

                currentTitle = title
                primarySubtitle = subtitle
                isPlaying = intentIsPlaying

                // Metadata can race ahead of the state intent (PlaybackController
                // emits both on play()) — cover that path so focus is acquired
                // even if metadata wins the scheduling.
                if (intentIsPlaying) acquireAudioFocus()

                updateMediaSessionMetadata()
                updateNotification()
            }
            else -> log.info { "Initial service start" }
        }

        ensureForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        log.info { "DjMediaBrowserService destroyed" }
        abandonAudioFocus()
        try {
            DjAppApplication.getGraph(this).mediaSessionStateManager.setAutoBrowserActive(false)
        } catch (_: Exception) { /* shutting down anyway */ }
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    // ─── MediaBrowserServiceCompat ─────────────────────────────────────

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val feature = pulsarFeature
        if (feature == null) {
            log.warn { "PulsarFeature not available for $parentId" }
            result.sendResult(mutableListOf())
            return
        }
        when {
            parentId == ROOT_ID -> {
                // Top level: one browsable folder per Album, ordered to match
                // the in-app vibe spinner grouping.
                val items = Album.values().mapNotNull { album ->
                    val vibesInAlbum = feature.vibeList.filter { it.album == album }
                    if (vibesInAlbum.isEmpty()) return@mapNotNull null
                    val description = MediaDescriptionCompat.Builder()
                        .setMediaId(ALBUM_PREFIX + album.name)
                        .setTitle(album.title)
                        .setSubtitle("${vibesInAlbum.size} vibes")
                        .setIconBitmap(albumArt(album))
                        .build()
                    MediaBrowserCompat.MediaItem(
                        description,
                        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                }
                result.sendResult(items.toMutableList())
            }
            parentId.startsWith(ALBUM_PREFIX) -> {
                val albumName = parentId.removePrefix(ALBUM_PREFIX)
                val album = Album.values().firstOrNull { it.name == albumName }
                if (album == null) {
                    log.warn { "Unknown album id: $parentId" }
                    result.sendResult(mutableListOf())
                    return
                }
                val items = feature.vibeList
                    .filter { it.album == album }
                    .map { vibe ->
                        val description = MediaDescriptionCompat.Builder()
                            .setMediaId(vibe.name)
                            .setTitle(vibe.name)
                            .setSubtitle("${vibe.bpm.toInt()} BPM")
                            .build()
                        MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                        )
                    }
                result.sendResult(items.toMutableList())
            }
            else -> {
                log.warn { "Unknown parent ID: $parentId" }
                result.sendResult(mutableListOf())
            }
        }
    }

    private fun albumArt(album: Album): Bitmap? = when (album) {
        Album.RIF -> rifArt
        Album.ZERO_TO_ONE -> zeroToOneArt
        Album.STEALTH -> zeroToOneArt // procedural per-title doesn't fit a folder thumbnail
    }

    // ─── MediaSession / notification / focus ───────────────────────────

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "DjAppMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    acquireAudioFocus()
                    actionHandler?.invoke("play")
                    updatePlaybackState(true)
                }

                override fun onPause() {
                    actionHandler?.invoke("pause")
                    updatePlaybackState(false)
                }

                override fun onStop() {
                    actionHandler?.invoke("stop")
                    abandonAudioFocus()
                    stopSelf()
                }

                override fun onSkipToNext() {
                    actionHandler?.invoke("skipNext")
                }

                override fun onSkipToPrevious() {
                    actionHandler?.invoke("skipPrevious")
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    mediaId ?: return
                    log.info { "onPlayFromMediaId: $mediaId" }
                    playMediaId(mediaId)
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    val vibe = resolveSearchQuery(query)
                    log.info { "onPlayFromSearch: query='$query' resolved=${vibe?.name}" }
                    if (vibe != null) playMediaId(vibe.name)
                }

                override fun onSkipToQueueItem(id: Long) {
                    val vibes = pulsarFeature?.vibeList.orEmpty()
                    val vibe = vibes.getOrNull(id.toInt())
                    log.info { "onSkipToQueueItem: id=$id resolved=${vibe?.name}" }
                    if (vibe != null) playMediaId(vibe.name)
                }
            })

            isActive = true
        }
        sessionToken = mediaSession?.sessionToken

        updateMediaSessionMetadata()
        publishQueue()
        updatePlaybackState(true)
    }

    /**
     * Common play path for any "start playing this vibe" trigger
     * (mediaId tap, voice search, queue item tap). Acquires focus, fans the
     * media id out to the existing PulsarVibePicker handler, and promotes us
     * to a foreground service so playback survives the initial Auto bind.
     */
    private fun playMediaId(mediaId: String) {
        acquireAudioFocus()
        playFromMediaIdHandler?.invoke(mediaId)
        ContextCompat.startForegroundService(
            this,
            Intent(this, DjMediaBrowserService::class.java)
        )
    }

    /**
     * Best-effort match of an Assistant query to a Vibe.
     *  1. Empty / null query -> current vibe (or first if none).
     *  2. Substring match against vibe name.
     *  3. Substring match against album title -> first vibe in that album.
     *  4. Nothing matched -> null (caller decides whether to fall back).
     *
     * Case- and locale-insensitive. Reviewers test queries like
     * "Hey Google, play <vibe> on Orphic DJ".
     */
    private fun resolveSearchQuery(query: String?): Vibe? {
        val vibes = pulsarFeature?.vibeList.orEmpty()
        if (vibes.isEmpty()) return null
        val q = query?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (q.isEmpty()) {
            return vibes.firstOrNull { it.name == currentTitle } ?: vibes.first()
        }
        vibes.firstOrNull { it.name.lowercase(Locale.ROOT).contains(q) }?.let { return it }
        Album.values().firstOrNull { it.title.lowercase(Locale.ROOT).contains(q) }?.let { album ->
            return vibes.firstOrNull { it.album == album }
        }
        return null
    }

    /**
     * Publish the full vibe list as the MediaSession queue. Auto's now-playing
     * card surfaces this as the "playing next" carousel; reviewers expect a
     * non-empty queue for any media-category app. Queue ids are stable indices
     * into vibeList so [onSkipToQueueItem] can resolve back to a Vibe.
     */
    private fun publishQueue() {
        val vibes = pulsarFeature?.vibeList ?: return
        val items = vibes.mapIndexed { index, vibe ->
            val description = MediaDescriptionCompat.Builder()
                .setMediaId(vibe.name)
                .setTitle(vibe.name)
                .setSubtitle("${vibe.bpm.toInt()} BPM • ${vibe.album.title}")
                .build()
            MediaSessionCompat.QueueItem(description, index.toLong())
        }
        mediaSession?.setQueue(items)
        mediaSession?.setQueueTitle("Vibes")
    }

    private fun currentQueueId(): Long {
        val vibes = pulsarFeature?.vibeList ?: return -1L
        val idx = vibes.indexOfFirst { it.name == currentTitle }
        return if (idx >= 0) idx.toLong() else -1L
    }

    /**
     * Acquire audio focus. Other audio apps (Apple Music, Spotify, etc.) pause
     * when this is granted. The focus-change listener handles loss/gain per
     * the table below — critical on Android Auto where ignoring nav prompts
     * is a real safety issue.
     *
     *   LOSS                       — another media app took focus; pause and stay paused.
     *   LOSS_TRANSIENT             — phone call/alarm; pause, auto-resume on GAIN.
     *   LOSS_TRANSIENT_CAN_DUCK    — nav prompt; system auto-ducks our output, no action.
     *   GAIN                       — resume only if a prior transient loss paused us.
     */
    private fun acquireAudioFocus() {
        if (hasAudioFocus) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { change -> onAudioFocusChanged(change) }
            .build()
            .also { audioFocusRequest = it }

        val result = audioManager.requestAudioFocus(request)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        log.info { "requestAudioFocus result=$result granted=$hasAudioFocus" }
    }

    private fun onAudioFocusChanged(change: Int) {
        log.info { "Audio focus change: $change (wasPlaying=$wasPlayingBeforeFocusLoss isPlaying=$isPlaying)" }
        val controller = try {
            DjAppApplication.getGraph(this).playbackController
        } catch (e: Exception) {
            log.warn(e) { "PlaybackController unavailable on focus change; ignoring" }
            return
        }
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent: another media app took over. Don't auto-resume.
                hasAudioFocus = false
                wasPlayingBeforeFocusLoss = false
                controller.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Phone call / alarm — remember we were playing so GAIN resumes.
                wasPlayingBeforeFocusLoss = isPlaying
                controller.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // System auto-ducks the mix; we keep playing at reduced volume.
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    controller.play()
                }
            }
        }
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasAudioFocus = false
    }

    private fun ensureForeground() {
        val notification = createNotification(isPlaying)
        if (!isForegroundStarted) {
            startForeground(NOTIFICATION_ID, notification)
            isForegroundStarted = true
        } else {
            updateNotification()
        }
    }

    /**
     * Album art is selected per [Album]:
     *  - ZERO_TO_ONE → fixed photographic poster.
     *  - RIF → fixed photographic poster.
     *  - STEALTH (default for un-tagged vibes) → procedural per-title art.
     * Album is resolved by looking up the current vibe in PulsarFeature; if
     * the vibe isn't found yet, fall back to the ZERO_TO_ONE art so we never
     * push a notification with no large icon. Title is shown by Android's
     * notification UI separately, so the artwork doesn't bake the song name in.
     */
    private fun currentArt(): Bitmap? {
        val album = pulsarFeature?.vibeList?.firstOrNull { it.name == currentTitle }?.album
        return when (album) {
            Album.RIF -> rifArt
            Album.STEALTH -> stealthRenderer?.render(currentTitle)
            Album.ZERO_TO_ONE, null -> zeroToOneArt
        }
    }

    private fun updateMediaSessionMetadata() {
        val art = currentArt()
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, primarySubtitle)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
                .build()
        )
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        this.isPlaying = isPlaying

        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActiveQueueItemId(currentQueueId())
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                    PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
                )
                .build()
        )

        updateMediaSessionMetadata()
        if (isForegroundStarted) updateNotification()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(isPlaying))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Orphic DJ is playing audio"
                setShowBadge(false)
                enableLights(true)
                lightColor = NOTIFICATION_COLOR
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(isPlaying: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val skipPrevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "Previous",
            createActionIntent(ACTION_SKIP_PREVIOUS)
        )
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                createActionIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                createActionIntent(ACTION_PLAY)
            )
        }
        val skipNextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "Next",
            createActionIntent(ACTION_SKIP_NEXT)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(primarySubtitle)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setLargeIcon(currentArt())
            .setContentIntent(contentIntent)
            .addAction(skipPrevAction)
            .addAction(playPauseAction)
            .addAction(skipNextAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setColor(NOTIFICATION_COLOR)
            .setColorized(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createActionIntent(action: String): PendingIntent {
        val intent = Intent(this, DjMediaBrowserService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
