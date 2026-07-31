@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.balch.djapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.diamondedge.logging.logging
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.models.Album
import java.util.Locale

class DjMediaLibraryService : MediaLibraryService() {

    private val log = logging("DjMediaLibrary")
    private var session: MediaLibrarySession? = null

    private val pulsarFeature: PulsarFeature? by lazy {
        try {
            val graph = DjAppApplication.getGraph(this)
            graph.featureGraphHolder.featureGraph.featureCollection
                .getFeature(PulsarFeature::class)
        } catch (e: Exception) {
            log.warn { "PulsarFeature not available: ${e.message}" }
            null
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        val graph = DjAppApplication.getGraph(this)
        session = graph.mediaSessionManager.buildLibrarySession(this)
        addSession(session!!)
        // Neither call below can throw: setAutoBrowserActive is a StateFlow
        // write, and pulsarFeature is a `by lazy` whose body catches internally
        // and returns null. (Previously this guarded synthOrchestrator.start(),
        // which moved to onStartCommand.)
        graph.mediaSessionStateManager.setAutoBrowserActive(true)
        pulsarFeature
        log.info { "DjMediaLibraryService created" }
    }

    // Engine start lives here, not in onCreate. The service can be bound for
    // pure introspection (system MediaBrowser, Android Auto discovery) without
    // becoming a foreground service — bind triggers onCreate but not
    // onStartCommand. Opening an Oboe stream in that bind-only window produces
    // Android 17 "AudioHardening level: partial" warnings (audio active, no
    // FGS). onStartCommand only fires on startForegroundService, so the engine
    // is guaranteed to start inside the FGS grace window.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            DjAppApplication.getGraph(this).synthOrchestrator.start()
        } catch (e: Exception) {
            log.error(e) { "Failed to start synth engine on service start" }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return session
    }

    override fun onDestroy() {
        log.info { "DjMediaLibraryService destroyed" }
        try {
            DjAppApplication.getGraph(this).mediaSessionStateManager.setAutoBrowserActive(false)
        } catch (_: Exception) { }
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
            "Orphic DJ",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}

class DjLibraryCallback(
    private val featureProvider: () -> PulsarFeature?,
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val log = logging("DjLibraryCallback")

    companion object {
        private const val ROOT_ID = "djapp_root"
        private const val ALBUM_PREFIX = "album_"
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val root = MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(root, params))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val feature = featureProvider() ?: return Futures.immediateFuture(
            LibraryResult.ofItemList(
                ImmutableList.of(),
                params
            )
        )

        val items: List<MediaItem> = when {
            parentId == ROOT_ID -> {
                Album.entries.mapNotNull { album ->
                    val vibesInAlbum = feature.vibeList.filter { it.album == album }
                    if (vibesInAlbum.isEmpty()) return@mapNotNull null
                    MediaItem.Builder()
                        .setMediaId(ALBUM_PREFIX + album.name)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(album.title)
                                .setSubtitle("${vibesInAlbum.size} vibes")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
                                .build()
                        )
                        .build()
                }
            }
            parentId.startsWith(ALBUM_PREFIX) -> {
                val albumName = parentId.removePrefix(ALBUM_PREFIX)
                val album = Album.entries.firstOrNull { it.name == albumName }
                if (album == null) return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                feature.vibeList
                    .filter { it.album == album }
                    .map { vibe ->
                        MediaItem.Builder()
                            .setMediaId(vibe.name)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(vibe.name)
                                    .setSubtitle("${vibe.bpm.toInt()} BPM")
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .build()
                            )
                            .build()
                    }
            }
            else -> emptyList()
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val feature = featureProvider() ?: return Futures.immediateFuture(
            LibraryResult.ofItemList(ImmutableList.of(), params)
        )
        val q = query.trim().lowercase(Locale.ROOT)
        val matches = feature.vibeList.filter {
            it.name.lowercase(Locale.ROOT).contains(q)
        }.map { vibe ->
            MediaItem.Builder()
                .setMediaId(vibe.name)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(vibe.name)
                        .setSubtitle("${vibe.bpm.toInt()} BPM")
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                )
                .build()
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(matches), params))
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> {
        val mediaId = mediaItems.firstOrNull()?.mediaId
        if (mediaId != null) {
            log.info { "onAddMediaItems: $mediaId" }
        }
        return Futures.immediateFuture(mediaItems)
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
            .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            .build()
}
