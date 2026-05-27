package org.balch.orpheus.core.media

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@UnstableApi
class SynthPlayer(
    looper: Looper = Looper.getMainLooper()
) : SimpleBasePlayer(looper) {

    private val handler = Handler(looper)
    private var playing = false
    private var currentMetadata = MediaMetadata.EMPTY
    private var currentMediaItem = MediaItem.EMPTY

    var onSkipNext: (() -> Unit)? = null
    var onSkipPrevious: (() -> Unit)? = null

    override fun getState(): State {
        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        COMMAND_PLAY_PAUSE,
                        COMMAND_STOP,
                        COMMAND_SEEK_TO_NEXT,
                        COMMAND_SEEK_TO_PREVIOUS,
                        COMMAND_GET_METADATA,
                        COMMAND_GET_CURRENT_MEDIA_ITEM,
                    )
                    .build()
            )
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(STATE_READY)
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(currentMediaItem.mediaId.ifEmpty { "synth" })
                        .setMediaItem(currentMediaItem)
                        .setMediaMetadata(currentMetadata)
                        .build()
                )
            )
            .setCurrentMediaItemIndex(0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playing = false
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onSkipNext?.invoke()
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onSkipPrevious?.invoke()
        }
        return Futures.immediateVoidFuture()
    }

    fun updatePlayState(isPlaying: Boolean) {
        handler.post {
            playing = isPlaying
            invalidateState()
        }
    }

    fun updateMetadata(title: String, subtitle: String, artworkData: ByteArray? = null) {
        handler.post {
            currentMetadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .apply { if (artworkData != null) setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                .build()
            currentMediaItem = MediaItem.Builder()
                .setMediaId(title)
                .setMediaMetadata(currentMetadata)
                .build()
            invalidateState()
        }
    }
}
