package org.balch.orpheus.features.pulsar.playback

import androidx.compose.ui.graphics.decodeToImageBitmap
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.balch.orpheus.core.coroutines.AppCoroutineScope
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
 * Cached per vibe name so repeat metadata pushes reuse the same byte array
 * (PlaybackController's distinctUntilChanged uses reference equality).
 */
@SingleIn(AppScope::class)
@Inject
class PulsarMetadataProducer(
    pulsarFeature: PulsarFeature,
    scope: AppCoroutineScope,
) : MetadataProducer {

    override val titleFlow: StateFlow<String> =
        pulsarFeature.vibeFlow
            .map { it.name }
            .stateIn(scope, SharingStarted.Eagerly, pulsarFeature.vibeFlow.value.name)

    override val subtitleFlow: StateFlow<String> =
        pulsarFeature.vibeFlow
            .map { it.album.title }
            .stateIn(scope, SharingStarted.Eagerly, pulsarFeature.vibeFlow.value.album.title)

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

    // Initial value is null (not pre-rendered) so test environments without
    // Skia loaded — and platforms where toPngBytes() is a no-op — don't pay
    // for an upfront render. The first downstream emission populates it.
    override val artworkPngFlow: StateFlow<ByteArray?> =
        pulsarFeature.vibeFlow
            .map { renderArtworkBytes(it) }
            .stateIn(scope, SharingStarted.Eagerly, null)
}
