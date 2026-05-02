package org.balch.djapp

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import org.balch.orpheus.features.pulsar.playback.AlbumArtRenderer

/**
 * Android-side bridge to the multiplatform [AlbumArtRenderer]. Renders
 * the procedural art into an Android [Bitmap] for `setLargeIcon` and
 * METADATA_KEY_ALBUM_ART, cached by title so repeat metadata pings
 * reuse the same bitmap.
 */
class ProceduralArtRenderer {
    private var lastTitle: String? = null
    private var cached: Bitmap? = null

    fun render(title: String): Bitmap {
        if (title == lastTitle) cached?.let { return it }
        val out = AlbumArtRenderer.render(seed = title.hashCode().toLong())
            .asAndroidBitmap()
        lastTitle = title
        cached = out
        return out
    }
}
