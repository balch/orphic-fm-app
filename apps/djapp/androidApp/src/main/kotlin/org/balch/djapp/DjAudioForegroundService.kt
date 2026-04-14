package org.balch.djapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.diamondedge.logging.logging
import org.balch.orpheus.core.media.ForegroundServiceController

/**
 * Foreground service that keeps DJ audio playing when the app is backgrounded.
 *
 * Provides a persistent notification with media controls (prev/play-pause/next)
 * and integrates with Android's MediaSession for lock screen, Bluetooth, and
 * Android Auto controls.
 */
class DjAudioForegroundService : Service() {

    private val log = logging("DjAudioForegroundService")
    private var mediaSession: MediaSessionCompat? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var albumArtBitmap: Bitmap? = null
    private var isPlaying = true
    private var currentTitle = "Orhpic-DJ"
    private var currentSubtitle = ""

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "djapp_audio_playback"

        private val ACTION_PLAY = ForegroundServiceController.ACTION_PLAY
        private val ACTION_PAUSE = ForegroundServiceController.ACTION_PAUSE
        private val ACTION_STOP = ForegroundServiceController.ACTION_STOP
        private val ACTION_SKIP_NEXT = ForegroundServiceController.ACTION_SKIP_NEXT
        private val ACTION_SKIP_PREVIOUS = ForegroundServiceController.ACTION_SKIP_PREVIOUS
        private val ACTION_UPDATE_STATE_PLAYING = ForegroundServiceController.ACTION_UPDATE_STATE_PLAYING
        private val ACTION_UPDATE_STATE_PAUSED = ForegroundServiceController.ACTION_UPDATE_STATE_PAUSED
        private val ACTION_UPDATE_METADATA = ForegroundServiceController.ACTION_UPDATE_METADATA
        private val EXTRA_TITLE = ForegroundServiceController.EXTRA_TITLE
        private val EXTRA_SUBTITLE = ForegroundServiceController.EXTRA_SUBTITLE
        private val EXTRA_IS_PLAYING = ForegroundServiceController.EXTRA_IS_PLAYING

        @Volatile var actionHandler: ((String) -> Unit)? = null
        @Volatile var sessionToken: MediaSessionCompat.Token? = null
            private set

        private val NOTIFICATION_COLOR = Color.parseColor("#7B68EE")
    }

    override fun onCreate() {
        super.onCreate()
        albumArtBitmap = BitmapFactory.decodeResource(resources, R.drawable.album_art)
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log.info { "DjAudioForegroundService onStartCommand: action=${intent?.action}" }

        when (intent?.action) {
            ACTION_PLAY -> {
                log.info { "Play action received" }
                isPlaying = true
                updatePlaybackState(true)
                actionHandler?.invoke("play")
            }
            ACTION_PAUSE -> {
                log.info { "Pause action received" }
                isPlaying = false
                updatePlaybackState(false)
                actionHandler?.invoke("pause")
            }
            ACTION_STOP -> {
                log.info { "Stop action received" }
                actionHandler?.invoke("stop")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SKIP_NEXT -> {
                log.info { "Skip next action received" }
                actionHandler?.invoke("skipNext")
            }
            ACTION_SKIP_PREVIOUS -> {
                log.info { "Skip previous action received" }
                actionHandler?.invoke("skipPrevious")
            }
            ACTION_UPDATE_STATE_PLAYING -> {
                log.debug { "State update: PLAYING" }
                isPlaying = true
                updatePlaybackState(true)
            }
            ACTION_UPDATE_STATE_PAUSED -> {
                log.debug { "State update: PAUSED" }
                isPlaying = false
                updatePlaybackState(false)
            }
            ACTION_UPDATE_METADATA -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "DJ App"
                val subtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: ""
                val intentIsPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)

                log.debug { "Metadata update: title=$title, subtitle=$subtitle, isPlaying=$intentIsPlaying" }

                currentTitle = title
                currentSubtitle = subtitle
                isPlaying = intentIsPlaying

                updateMediaSessionMetadata()
                updateNotification()
            }
            else -> {
                log.info { "Initial foreground service start" }
            }
        }

        val notification = createNotification(isPlaying)
        startForeground(NOTIFICATION_ID, notification)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        log.info { "DjAudioForegroundService destroyed" }
        audioFocusRequest?.let {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.abandonAudioFocusRequest(it)
        }
        sessionToken = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when DJ App is playing audio"
                setShowBadge(false)
                enableLights(true)
                lightColor = NOTIFICATION_COLOR
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "DjAppMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    actionHandler?.invoke("play")
                    updatePlaybackState(true)
                }

                override fun onPause() {
                    actionHandler?.invoke("pause")
                    updatePlaybackState(false)
                }

                override fun onStop() {
                    actionHandler?.invoke("stop")
                    stopSelf()
                }

                override fun onSkipToNext() {
                    actionHandler?.invoke("skipNext")
                }

                override fun onSkipToPrevious() {
                    actionHandler?.invoke("skipPrevious")
                }
            })

            isActive = true
        }

        sessionToken = mediaSession?.sessionToken

        // Request audio focus
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        actionHandler?.invoke("pause")
                        updatePlaybackState(false)
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        actionHandler?.invoke("pause")
                        updatePlaybackState(false)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        // Don't auto-resume — user can tap play
                    }
                }
            }
            .build()
        audioManager.requestAudioFocus(audioFocusRequest!!)

        updateMediaSessionMetadata()
        updatePlaybackState(true)
    }

    private fun updateMediaSessionMetadata() {
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSubtitle)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArtBitmap)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, albumArtBitmap)
                .build()
        )
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        this.isPlaying = isPlaying

        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .build()
        )

        updateMediaSessionMetadata()
        updateNotification()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(isPlaying))
    }

    private fun createNotification(isPlaying: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Skip previous action
        val skipPrevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "Previous",
            createActionIntent(ACTION_SKIP_PREVIOUS)
        )

        // Play/Pause action
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                createActionIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                createActionIntent(ACTION_PLAY)
            )
        }

        // Skip next action
        val skipNextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "Next",
            createActionIntent(ACTION_SKIP_NEXT)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentSubtitle)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setLargeIcon(albumArtBitmap)
            .setContentIntent(contentIntent)
            .addAction(skipPrevAction)
            .addAction(playPauseAction)
            .addAction(skipNextAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setColor(NOTIFICATION_COLOR)
            .setColorized(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createActionIntent(action: String): PendingIntent {
        val intent = Intent(this, DjAudioForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
