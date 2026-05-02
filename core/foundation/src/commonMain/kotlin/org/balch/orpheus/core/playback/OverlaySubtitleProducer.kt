package org.balch.orpheus.core.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * Optional secondary subtitle producer that *overrides* the primary
 * subtitle when active (e.g. sleep timer countdown).
 *
 * null = no overlay; controller falls back to MetadataProducer.subtitleFlow.
 */
interface OverlaySubtitleProducer {
    val overlayFlow: StateFlow<String?>
}
