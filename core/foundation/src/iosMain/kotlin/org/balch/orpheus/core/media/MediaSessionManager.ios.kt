@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package org.balch.orpheus.core.media

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGSize
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPNowPlayingPlaybackStatePaused
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage

/**
 * iOS implementation of MediaSessionManager.
 *
 * Uses MPNowPlayingInfoCenter for lock screen / Control Center metadata
 * and MPRemoteCommandCenter for transport controls (play, pause, skip).
 */
@SingleIn(AppScope::class)
@Inject
actual class MediaSessionManager {
    private val log = logging("MediaSessionManager")
    private var handler: MediaSessionActionHandler? = null
    private var isActive = false
    private var isPlaying = false

    // Cache the latest metadata so `activate()` can replay it. Without this,
    // metadata that arrives before activation gets silently dropped and the
    // Now Playing widget stays blank until the next user-driven update.
    private var latestMetadata: PlaybackMetadata? = null

    actual fun activate() {
        if (isActive) return
        log.info { "Activating media session" }

        val cc = MPRemoteCommandCenter.sharedCommandCenter()

        cc.playCommand.addTargetWithHandler { _ ->
            handler?.onPlay()
            MPRemoteCommandHandlerStatusSuccess
        }

        cc.pauseCommand.addTargetWithHandler { _ ->
            handler?.onPause()
            MPRemoteCommandHandlerStatusSuccess
        }

        cc.togglePlayPauseCommand.addTargetWithHandler { _ ->
            if (isPlaying) handler?.onPause() else handler?.onPlay()
            MPRemoteCommandHandlerStatusSuccess
        }

        cc.stopCommand.addTargetWithHandler { _ ->
            handler?.onStop()
            MPRemoteCommandHandlerStatusSuccess
        }

        // Next/prev track cycle vibes and render as proper ⏮ / ⏭ glyphs.
        //
        // History: an earlier build ALSO enabled skipForward/skipBackward
        // (15s) because the Now Playing widget appeared to need them for the
        // artwork-rich layout. That observation was made while a resource
        // packaging bug kept the static posters' bytes null on iOS (see the
        // "Copy Compose Resources" phase in apps/djapp/iosApp/project.yml),
        // so the "compact layout" was very likely just the no-artwork
        // rendering. If artwork ever fails to render rich again, re-test
        // against a build where the artwork bytes are confirmed present
        // before reaching for the skip-command workaround — it costs the
        // ⏩15/⏪15 icon style.
        cc.nextTrackCommand.addTargetWithHandler { _ ->
            handler?.onSkipNext()
            MPRemoteCommandHandlerStatusSuccess
        }
        cc.previousTrackCommand.addTargetWithHandler { _ ->
            handler?.onSkipPrevious()
            MPRemoteCommandHandlerStatusSuccess
        }

        cc.playCommand.setEnabled(true)
        cc.pauseCommand.setEnabled(true)
        cc.togglePlayPauseCommand.setEnabled(true)
        cc.stopCommand.setEnabled(true)
        cc.nextTrackCommand.setEnabled(true)
        cc.previousTrackCommand.setEnabled(true)
        // Explicitly off: enabled-but-targetless skip commands still reserve
        // the seek-icon layout slots on some iOS versions.
        cc.skipForwardCommand.setEnabled(false)
        cc.skipBackwardCommand.setEnabled(false)

        isActive = true
        // Replay any metadata that arrived before activation so the Now
        // Playing widget shows the current title/art immediately.
        latestMetadata?.let { pushToNative(it) }
    }

    actual fun deactivate() {
        if (!isActive) return
        log.info { "Deactivating media session" }

        val cc = MPRemoteCommandCenter.sharedCommandCenter()
        cc.playCommand.removeTarget(null)
        cc.pauseCommand.removeTarget(null)
        cc.togglePlayPauseCommand.removeTarget(null)
        cc.stopCommand.removeTarget(null)
        cc.nextTrackCommand.removeTarget(null)
        cc.previousTrackCommand.removeTarget(null)

        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null

        isActive = false
    }

    actual fun updatePlaybackState(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        if (!isActive) return
        // Re-push the full metadata so artwork + title + rate stay in sync.
        // Partial updates to nowPlayingInfo can cause iOS to drop fields we
        // didn't explicitly include — the play/pause icon stops refreshing
        // and (sometimes) artwork disappears. Always send the whole thing.
        val current = latestMetadata
        if (current != null) {
            val updated = current.copy(isPlaying = isPlaying)
            latestMetadata = updated
            pushToNative(updated)
        } else {
            // No metadata yet — fall back to setting just the playback state.
            MPNowPlayingInfoCenter.defaultCenter().playbackState =
                if (isPlaying) MPNowPlayingPlaybackStatePlaying
                else MPNowPlayingPlaybackStatePaused
        }
    }

    actual fun updateMetadata(metadata: PlaybackMetadata) {
        latestMetadata = metadata
        if (!isActive) return
        pushToNative(metadata)
    }

    actual fun setActionHandler(handler: MediaSessionActionHandler) {
        this.handler = handler
    }

    // iOS handles audio focus / interruption through AVAudioSession at the
    // app-bridge layer — these hooks are no-ops here.
    actual fun requestPlaybackFocus(): Boolean = true

    actual fun notifyUserPaused() {}

    @OptIn(BetaInteropApi::class)
    private fun pushToNative(metadata: PlaybackMetadata) {
        // Merge into the existing dict so updates to title/subtitle don't
        // wipe out artwork (and vice versa).
        val info = MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo?.toMutableMap()
            ?: mutableMapOf<Any?, Any?>()
        info[MPMediaItemPropertyTitle] = metadata.title
        info[MPMediaItemPropertyArtist] = metadata.subtitle
        // Wrap rate as NSNumber explicitly. Some Kotlin/Native bridge paths
        // can leave a raw Kotlin Double in the dict; iOS's MPNowPlayingInfo
        // expects an NSNumber here and can silently ignore the entry otherwise.
        info[MPNowPlayingInfoPropertyPlaybackRate] =
            NSNumber(double = if (metadata.isPlaying) 1.0 else 0.0)

        val artworkBytes = metadata.artworkPng
        if (artworkBytes != null) {
            val image = decodeImage(artworkBytes)
            if (image != null) {
                val size = image.size.useContents { CGSizeValue(width, height) }
                val artwork = MPMediaItemArtwork(boundsSize = size) { _ -> image }
                info[MPMediaItemPropertyArtwork] = artwork
            } else {
                // Bytes arrived but UIImage refused them — surface it, this
                // is the difference between "producer sent nothing" and
                // "decode failed" when debugging blank artwork on device.
                log.warn { "Artwork decode failed (${artworkBytes.size} bytes) — dropping artwork" }
                info.remove(MPMediaItemPropertyArtwork)
            }
        } else {
            info.remove(MPMediaItemPropertyArtwork)
        }

        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info

        isPlaying = metadata.isPlaying
        MPNowPlayingInfoCenter.defaultCenter().playbackState =
            if (metadata.isPlaying) MPNowPlayingPlaybackStatePlaying
            else MPNowPlayingPlaybackStatePaused
    }

    /** Decode raw PNG/JPEG/WebP bytes into a UIImage via NSData. */
    @OptIn(BetaInteropApi::class)
    private fun decodeImage(bytes: ByteArray): UIImage? {
        val nsData: NSData = bytes.toNSData() ?: return null
        return UIImage.imageWithData(nsData)
    }
}

/**
 * Holder for a CGSize value to keep the cinterop boundary tight — the raw
 * `image.size.useContents { ... }` block can't return a CValue<CGSize>
 * directly without a wrapper construction.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CGSizeValue(width: Double, height: Double): kotlinx.cinterop.CValue<CGSize> =
    cValue<CGSize> {
        this.width = width
        this.height = height
    }

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData? {
    if (isEmpty()) return null
    return memScoped {
        NSData.create(
            bytes = allocArrayOf(this@toNSData),
            length = this@toNSData.size.toULong(),
        )
    }
}
