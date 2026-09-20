package org.balch.orpheus.core.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.media.PlaybackProgress

/**
 * Primary metadata source for the now-playing card.
 *
 * Empty string means "I have nothing to contribute" — the controller falls
 * back to a default. Use null-safe StateFlow<String> rather than
 * Flow<String?> so consumers always have a current value at subscription
 * time (matches Compose state-holder idioms).
 */
interface MetadataProducer {
    val titleFlow: StateFlow<String>
    val subtitleFlow: StateFlow<String>

    /**
     * Name of the selected song, whatever the now-playing card is showing. Defaults to the
     * title; producers whose title can diverge from the selected song (e.g. an AI overlay) can
     * override this to report the underlying song name instead.
     */
    val songFlow: StateFlow<String> get() = titleFlow

    /**
     * PNG-encoded artwork for the now-playing surface (macOS Control Center,
     * Auto, lock screen, etc). Null = "no artwork to advertise" — the
     * controller will not push an artwork update. Producers that don't render
     * art can leave this as the default.
     */
    val artworkPngFlow: StateFlow<ByteArray?> get() = EMPTY_ARTWORK

    /** Progress through the current item for the system seek bar. Null means no seek bar. */
    val progressFlow: StateFlow<PlaybackProgress?> get() = NO_PROGRESS
}

private val EMPTY_ARTWORK: StateFlow<ByteArray?> = MutableStateFlow(null)
private val NO_PROGRESS: StateFlow<PlaybackProgress?> = MutableStateFlow(null)
