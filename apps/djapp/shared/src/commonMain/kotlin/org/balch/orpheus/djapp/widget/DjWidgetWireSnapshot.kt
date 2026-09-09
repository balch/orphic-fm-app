package org.balch.orpheus.djapp.widget

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The cross-process payload the iOS widget extension reads. Separate from
 * [DjWidgetSnapshot] so the render model stays free of serialization concerns
 * and the artwork ByteArray never enters JSON — the extension loads the image
 * from its own file instead.
 */
@Serializable
data class DjWidgetWireSnapshot(
    val currentVibe: String,
    val albumTitle: String,
    val isPlaying: Boolean,
    val timerRunning: Boolean,
    val timerRemainingSeconds: Long,
    val timerStatus: String,
    val artworkFile: String?,
    val writtenAtEpochMs: Long,
) {
    companion object {
        fun from(
            snapshot: DjWidgetSnapshot,
            artworkFile: String?,
            writtenAtEpochMs: Long,
        ): DjWidgetWireSnapshot = DjWidgetWireSnapshot(
            currentVibe = snapshot.currentVibe,
            albumTitle = snapshot.albumTitle,
            isPlaying = snapshot.isPlaying,
            timerRunning = snapshot.timerRunning,
            timerRemainingSeconds = snapshot.timerRemainingSeconds,
            timerStatus = snapshot.timerStatus,
            artworkFile = artworkFile,
            writtenAtEpochMs = writtenAtEpochMs,
        )

        /** Filename-safe key for a vibe; the snapshot carries no separate id. */
        fun artworkSlug(vibe: String): String {
            val slug = vibe.lowercase()
                .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
                .joinToString("")
                .trim('-')
                .replace(Regex("-+"), "-")
            return slug.ifEmpty { "unknown" }
        }
    }
}

object DjWidgetWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(wire: DjWidgetWireSnapshot): String = json.encodeToString(wire)

    /** Null rather than throwing: a malformed file must render the idle widget. */
    fun decode(text: String): DjWidgetWireSnapshot? =
        runCatching { json.decodeFromString<DjWidgetWireSnapshot>(text) }.getOrNull()
}
