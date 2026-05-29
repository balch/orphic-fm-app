package org.balch.orpheus.djapp.widget

/**
 * Immutable render model for the DjApp home-screen widget. Built inside the
 * Glance composition from the live graph flows exposed by `DjWidgetData.sources()`
 * (see `DjWidget.rememberWidgetSnapshot`) and consumed by `DjWidget`.
 *
 * [artworkPng] uses array reference identity for equality (the data class
 * default) — matching `PlaybackController`'s artwork dedupe, where the metadata
 * producer re-emits the same instance per vibe. Tests assert fields, not whole
 * objects, so the array-in-data-class semantics never bite.
 */
data class DjWidgetSnapshot(
    val currentVibe: String,
    val albumTitle: String,
    val nextVibe: String,
    val isPlaying: Boolean,
    val timerRunning: Boolean,
    val timerRemainingSeconds: Long,
    val timerStatus: String,
    val artworkPng: ByteArray?,
)

/** Pure mapper — no Android types, fully unit-testable. */
object DjWidgetSnapshotBuilder {

    private const val NONE = "—"

    fun build(
        currentVibe: String,
        albumTitle: String,
        vibeNames: List<String>,
        isPlaying: Boolean,
        timerRunning: Boolean,
        timerRemainingSeconds: Long,
        timerStatus: String,
        artworkPng: ByteArray?,
    ): DjWidgetSnapshot = DjWidgetSnapshot(
        currentVibe = currentVibe.ifEmpty { NONE },
        albumTitle = albumTitle,
        nextVibe = nextVibe(currentVibe, vibeNames),
        isPlaying = isPlaying,
        timerRunning = timerRunning,
        timerRemainingSeconds = timerRemainingSeconds.coerceAtLeast(0L),
        timerStatus = timerStatus,
        artworkPng = artworkPng,
    )

    /** Next vibe in cycle (same rule as PulsarSkipHandler). */
    fun nextVibe(currentVibe: String, vibeNames: List<String>): String {
        if (vibeNames.isEmpty()) return NONE
        val idx = vibeNames.indexOf(currentVibe)
        val nextIdx = if (idx < 0) 0 else (idx + 1) % vibeNames.size
        return vibeNames[nextIdx]
    }
}
