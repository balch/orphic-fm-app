package org.balch.djapp

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.MediaBrowserServiceCompat
import com.diamondedge.logging.logging
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel

class DjMediaBrowserService : MediaBrowserServiceCompat() {

    private val log = logging("DjMediaBrowser")

    private var cachedFeature: PulsarFeature? = null

    private val pulsarFeature: PulsarFeature?
        get() {
            cachedFeature?.let { return it }
            return try {
                val graph = DjAppApplication.getGraph(this)
                val featureGraph = graph.featureGraphFactory.create()
                val feature = featureGraph.featureCollection
                    .getFeature<PulsarFeature>(PulsarViewModel::class)
                cachedFeature = feature
                feature
            } catch (e: Exception) {
                log.warn { "PulsarFeature not available: ${e.message}" }
                null
            }
        }

    override fun onCreate() {
        super.onCreate()
        log.info { "DjMediaBrowserService created" }
        DjAudioForegroundService.sessionToken?.let { token ->
            setSessionToken(token)
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        // Lazily set session token if foreground service started after us
        if (sessionToken == null) {
            DjAudioForegroundService.sessionToken?.let { token ->
                setSessionToken(token)
            }
        }
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        when (parentId) {
            ROOT_ID -> {
                val feature = pulsarFeature
                if (feature == null) {
                    log.warn { "PulsarFeature not available" }
                    result.sendResult(mutableListOf())
                    return
                }
                val items = feature.vibeList.map { vibe ->
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

    companion object {
        private const val ROOT_ID = "djapp_root"
    }
}
