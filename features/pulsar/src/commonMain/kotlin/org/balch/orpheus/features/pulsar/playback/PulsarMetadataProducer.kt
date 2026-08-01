package org.balch.orpheus.features.pulsar.playback

import androidx.compose.ui.graphics.decodeToImageBitmap
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.features.pulsar.PulsarSession
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.Vibe
import org.jetbrains.compose.resources.ExperimentalResourceApi
import orpheus.features.pulsar.generated.resources.Res

/**
 * Pulsar-as-primary-metadata: title is the current vibe name, subtitle the album title. Used
 * directly by DJApp; wrapped by OrpheusMetadataProducer in Orpheus.
 *
 * [artworkPngFlow] emits PNG album art per vibe: STEALTH renders procedurally via
 * [AlbumArtRenderer], RIF/ZERO_TO_ONE decode a static WebP and re-encode as PNG (older macOS
 * NSImage won't decode WebP).
 *
 * Art is cached per vibe name and the *same* ByteArray instance must be returned on a hit.
 * This is load-bearing: PlaybackController de-dupes on StateFlow equal-emission dropping and
 * compares artwork by reference, so a fresh-but-identical array would re-emit on every tick.
 */
@SingleIn(AppScope::class)
@Inject
class PulsarMetadataProducer(
    // Was a () -> PulsarFeature lazy provider dodging the DI cycle (PulsarFeature →
    // PulsarViewModel → PulsarSongEnding → PlaybackController → MetadataProducer → here),
    // which also needed a main-dispatch deferral to avoid deadlocking on adjacent
    // BaseDoubleCheck locks while AWT built the graph. PulsarSession retires both.
    pulsarSession: PulsarSession,
    scope: AppCoroutineScope,
    dispatcherProvider: DispatcherProvider,
) : MetadataProducer {

    // Matches PlaybackController.snapshotOf's title.ifEmpty { "Orpheus" } fallback, so the
    // window before the first vibe looks like the empty-title case it already handles.
    private val _title = MutableStateFlow("Orpheus")
    private val _subtitle = MutableStateFlow("")
    private val _artwork = MutableStateFlow<ByteArray?>(null)

    override val titleFlow: StateFlow<String> = _title.asStateFlow()
    override val subtitleFlow: StateFlow<String> = _subtitle.asStateFlow()
    override val artworkPngFlow: StateFlow<ByteArray?> = _artwork.asStateFlow()

    private val log = com.diamondedge.logging.logging("PulsarMetadataProducer")
    // Contention is theoretical today (one collector), but the lock keeps the read-test/
    // write-update sequence safe if a second collector or a parallel merge ever appears.
    private val cacheLock = Mutex()
    private var cachedArtworkVibe: String? = null
    private var cachedArtworkBytes: ByteArray? = null

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun renderArtworkBytes(vibe: Vibe): ByteArray? = cacheLock.withLock {
        if (cachedArtworkVibe == vibe.name) return@withLock cachedArtworkBytes
        // A missing native lib (Skia off a test classpath) yields null artwork, not a crash.
        val bytes: ByteArray? = try {
            when (vibe.album) {
                Album.STEALTH ->
                    AlbumArtRenderer.render(seed = vibe.name.hashCode().toLong()).toPngBytes()
                Album.RIF -> loadStaticArt("drawable/album_art_rif.webp")
                Album.ZERO_TO_ONE -> loadStaticArt("drawable/album_art_021.webp")
            }
        } catch (t: Throwable) {
            log.warn(t) { "Album art render failed for ${vibe.name}; emitting null artwork" }
            null
        }
        // Only cache real bytes: a failure may be transient and worth retrying.
        if (bytes != null) {
            cachedArtworkVibe = vibe.name
            cachedArtworkBytes = bytes
        }
        bytes
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun loadStaticArt(path: String): ByteArray? {
        val webpBytes = Res.readBytes(path)
        // PNG is the lingua franca of MPMediaItemArtwork; older macOS NSImage won't take WebP.
        return webpBytes.decodeToImageBitmap().toPngBytes()
    }

    init {
        // No main-dispatched deferral needed: pulsarSession is a plain AppScope dependency,
        // not a lazily-resolved feature, so there's no graph-building race to queue behind.
        // filterNotNull holds the defaults above until a real vibe exists.
        scope.launch(dispatcherProvider.default) {
            pulsarSession.vibeFlow.filterNotNull().collect { vibe ->
                _title.value = vibe.name
                _subtitle.value = vibe.album.title
            }
        }
        // collectLatest so a rapid vibe change cancels an in-flight render.
        scope.launch(dispatcherProvider.default) {
            pulsarSession.vibeFlow.filterNotNull().collectLatest { vibe ->
                _artwork.value = renderArtworkBytes(vibe)
            }
        }
    }
}
