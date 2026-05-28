package org.balch.orpheus.core.media

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@UnstableApi
class SynthPlayer(
    application: Application,
    looper: Looper = Looper.getMainLooper(),
) : SimpleBasePlayer(looper) {

    private val handler = Handler(looper)
    private val applicationRef: Application = application
    private val audioManager: AudioManager =
        application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var playing = false
    private var currentMetadata = MediaMetadata.EMPTY
    private var currentMediaItem = MediaItem.EMPTY

    var onSkipNext: (() -> Unit)? = null
    var onSkipPrevious: (() -> Unit)? = null

    // STREAM_MUSIC max can change with output routing (BT, car HU, headset),
    // so rebuild DeviceInfo on every getState() rather than caching at
    // construction. DeviceInfo.equals compares the fields, so Media3 won't
    // see a no-op rebuild as a change.
    private fun currentDeviceInfo(): DeviceInfo =
        DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL)
            .setMaxVolume(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .build()

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VOLUME_CHANGED_ACTION) return
            val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (streamType != AudioManager.STREAM_MUSIC) return
            handler.post { invalidateState() }
        }
    }

    init {
        applicationRef.registerReceiver(
            volumeReceiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

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
                        COMMAND_GET_DEVICE_VOLUME,
                        COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                        COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                    )
                    .build()
            )
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(STATE_READY)
            .setDeviceInfo(currentDeviceInfo())
            .setDeviceVolume(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
            .setIsDeviceMuted(audioManager.isStreamMute(AudioManager.STREAM_MUSIC))
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

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, deviceVolume, flags)
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flags)
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flags)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            flags,
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        runCatching { applicationRef.unregisterReceiver(volumeReceiver) }
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
                // Some Cast / DLNA / AVRCP endpoints prefer displayTitle over
                // title (older Google Cast receivers, some smart-TV firmwares,
                // car-stereo head units). Setting both means the renderer
                // cannot fall back to its own default label (e.g. "AM Player"
                // on certain OEM media receivers) when displayTitle is null.
                .setDisplayTitle(title)
                .setArtist(subtitle)
                .setSubtitle(subtitle)
                .apply { if (artworkData != null) setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                .build()
            currentMediaItem = MediaItem.Builder()
                .setMediaId(title)
                .setMediaMetadata(currentMetadata)
                .build()
            invalidateState()
        }
    }

    companion object {
        // android.media.AudioManager constants that aren't in older SDKs as public symbols.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }
}
