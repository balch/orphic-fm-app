package org.balch.orpheus.features.pulsar.playback

import androidx.compose.ui.graphics.decodeToImageBitmap
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.Vibe
import org.jetbrains.compose.resources.ExperimentalResourceApi
import orpheus.features.pulsar.generated.resources.Res

/**
 * Pulsar-as-primary-metadata: title is the current vibe name, subtitle is
 * the album title. Used directly by DJApp; wrapped by OrpheusMetadataProducer in
 * Orpheus (which can prefer AI-mode subtitle when active).
 *
 * For each vibe, [artworkPngFlow] emits PNG-encoded album art:
 *  - STEALTH: procedurally rendered via [AlbumArtRenderer] (seeded by vibe name).
 *  - RIF / ZERO_TO_ONE: static WebP poster decoded from compose resources and
 *    re-encoded as PNG for cross-platform consumption (macOS NSImage on older
 *    OSes won't decode WebP directly).
 *
 * Cached per vibe name so repeat metadata pushes reuse the *same* ByteArray
 * instance. This is load-bearing: PlaybackController de-dupes metadata via the
 * StateFlow's built-in equal-emission dropping (there is no distinctUntilChanged
 * operator anymore), and its snapshot compares artwork by reference, so a
 * fresh-but-identical array on every push would defeat the de-dupe and re-emit
 * artwork on each tick. Keep returning the cached instance per vibe — do not
 * copy/re-encode the bytes on the cache-hit path.
 */
@SingleIn(AppScope::class)
@Inject
class PulsarMetadataProducer(
    // Lazy provider breaks the static DI cycle (PulsarFeature → PulsarViewModel
    // → PulsarSongEnding → PlaybackController → MetadataProducer → here).
    // But that alone is not enough — invoking the provider on a Default-pool
    // worker while AWT is still building the Metro graph deadlocks two threads
    // on adjacent BaseDoubleCheck locks. Launching on `dispatcherProvider.main`
    // queues the resolution past AWT's current composition event, so by the
    // time the provider fires, the graph is fully built and no other thread
    // is contending for those locks.
    pulsarFeatureProvider: () -> PulsarFeature,
    scope: AppCoroutineScope,
    dispatcherProvider: DispatcherProvider,
) : MetadataProducer {

    // Defaults match PlaybackController.snapshotOf's title.ifEmpty { "Orpheus" }
    // fallback, so the brief window before the init coroutine fires looks the
    // same as the empty-title case the snapshot code already handles.
    private val _title = MutableStateFlow("Orpheus")
    private val _subtitle = MutableStateFlow("")
    private val _artwork = MutableStateFlow<ByteArray?>(null)

    override val titleFlow: StateFlow<String> = _title.asStateFlow()
    override val subtitleFlow: StateFlow<String> = _subtitle.asStateFlow()
    override val artworkPngFlow: StateFlow<ByteArray?> = _artwork.asStateFlow()

    private val log = com.diamondedge.logging.logging("PulsarMetadataProducer")
    // Guarded by [cacheLock]. Today the upstream Flow has a single collector
    // (SharingStarted.Eagerly + AppScope), so contention is theoretical — but
    // the lock makes the read-test/write-update sequence safe if a future
    // refactor adds a second collector or a parallel flatMapMerge.
    private val cacheLock = Mutex()
    private var cachedArtworkVibe: String? = null
    private var cachedArtworkBytes: ByteArray? = null

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun renderArtworkBytes(vibe: Vibe): ByteArray? = cacheLock.withLock {
        if (cachedArtworkVibe == vibe.name) return@withLock cachedArtworkBytes
        // Wrap in try/catch so a missing native lib (e.g. Skia not on a test
        // classpath) returns null artwork rather than crashing the producer.
        // Production rendering paths get Skia via Compose Multiplatform.
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
        // Don't cache transient failures — a future render attempt may
        // succeed (e.g. Skia loaded later, transient resource read error).
        // Only persist the cache when we have actual bytes.
        if (bytes != null) {
            cachedArtworkVibe = vibe.name
            cachedArtworkBytes = bytes
        }
        bytes
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun loadStaticArt(path: String): ByteArray? {
        val webpBytes = Res.readBytes(path)
        // Decode and re-encode as PNG: macOS NSImage doesn't decode WebP on
        // older OSes, and PNG is the lingua franca of MPMediaItemArtwork.
        return webpBytes.decodeToImageBitmap().toPngBytes()
    }

    init {
        // Outer launch on Main: deferred behind AWT's current composition pass,
        // so pulsarFeatureProvider() resolves only after the graph is built.
        scope.launch(dispatcherProvider.main) {
            val pulsarFeature = pulsarFeatureProvider()
            // Title/subtitle are pure mappings — single collect on default.
            launch(dispatcherProvider.default) {
                pulsarFeature.vibeFlow.collect { vibe ->
                    _title.value = vibe.name
                    _subtitle.value = vibe.album.title
                }
            }
            // Artwork uses collectLatest so a rapid vibe change cancels an
            // in-flight render — matches the prior `stateIn` + slow `map`
            // semantics, which would also discard intermediates.
            launch(dispatcherProvider.default) {
                pulsarFeature.vibeFlow.collectLatest { vibe ->
                    _artwork.value = renderArtworkBytes(vibe)
                }
            }
        }
    }
}
