package org.balch.orpheus

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
 * Foreground service that keeps audio playing when the app is backgrounded.
 * 
 * Provides a persistent notification with media controls and integrates
 * with Android's MediaSession for lock screen and Bluetooth controls.
 * 
 * Features:
 * - App icon displayed in notifications and lock screen
 * - Mode-aware display (REPL/Drone/Solo/User)
 * - Cool color scheme with gradient-inspired theming
 */
class AudioForegroundService : Service() {
    
    private val log = logging("AudioForegroundService")
    private var mediaSession: MediaSessionCompat? = null
    private var albumArtBitmap: Bitmap? = null
    private var isPlaying = true
    private var currentTitle = "Orpheus Synthesizer"
    private var currentModeName = "Manual Play"
    private var currentMode = "USER"

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "orpheus_audio_playback"

        // Action and extra constants sourced from ForegroundServiceController
        // to keep a single source of truth.
        private val ACTION_PLAY = ForegroundServiceController.ACTION_PLAY
        private val ACTION_PAUSE = ForegroundServiceController.ACTION_PAUSE
        private val ACTION_STOP = ForegroundServiceController.ACTION_STOP
        private val ACTION_UPDATE_STATE_PLAYING = ForegroundServiceController.ACTION_UPDATE_STATE_PLAYING
        private val ACTION_UPDATE_STATE_PAUSED = ForegroundServiceController.ACTION_UPDATE_STATE_PAUSED
        private val ACTION_UPDATE_METADATA = ForegroundServiceController.ACTION_UPDATE_METADATA
        private val EXTRA_TITLE = ForegroundServiceController.EXTRA_TITLE
        private val EXTRA_MODE = ForegroundServiceController.EXTRA_MODE
        private val EXTRA_MODE_DISPLAY_NAME = ForegroundServiceController.EXTRA_MODE_DISPLAY_NAME
        private val EXTRA_IS_PLAYING = ForegroundServiceController.EXTRA_IS_PLAYING

        var actionHandler: ((String) -> Unit)? = null

        private val MODE_COLORS = mapOf(
            "USER" to Color.parseColor("#6B7FD7"),
            "DRONE" to Color.parseColor("#7B68EE"),
            "SOLO" to Color.parseColor("#9370DB"),
            "REPL" to Color.parseColor("#00CED1")
        )

        private val DEFAULT_COLOR = Color.parseColor("#7B68EE")
    }
    
    override fun onCreate() {
        super.onCreate()
        albumArtBitmap = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        createNotificationChannel()
        setupMediaSession()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log.info { "AudioForegroundService onStartCommand: action=${intent?.action}" }
        
        // Handle media button actions
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
                // Extract metadata from intent
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Orpheus Synthesizer"
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "USER"
                val modeDisplayName = intent.getStringExtra(EXTRA_MODE_DISPLAY_NAME) ?: "Manual Play"
                val intentIsPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)

                log.debug { "Metadata update: title=$title, mode=$mode, displayName=$modeDisplayName, isPlaying=$intentIsPlaying" }

                currentTitle = title
                currentMode = mode
                currentModeName = modeDisplayName
                isPlaying = intentIsPlaying
                
                updateMediaSessionMetadata()
                updateNotification()
            }
            else -> {
                // Initial start - no action, just start foreground
                log.info { "Initial foreground service start" }
            }
        }
        
        val notification = createNotification(isPlaying)
        startForeground(NOTIFICATION_ID, notification)
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        log.info { "AudioForegroundService destroyed" }
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
                description = "Shows when Orpheus is playing audio"
                setShowBadge(false)
                // Enable lights with our theme color
                enableLights(true)
                lightColor = DEFAULT_COLOR
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "OrpheusMediaSession").apply {
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
            })
            
            isActive = true
        }
        
        updateMediaSessionMetadata()
        updatePlaybackState(true)
    }
    
    private fun updateMediaSessionMetadata() {
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getSubtitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentModeName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArtBitmap)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, albumArtBitmap)
                .build()
        )
    }
    
    private fun getSubtitle(): String {
        return if (isPlaying) "Playing: $currentModeName" else "Paused: $currentModeName"
    }
    
    private fun getModeColor(): Int {
        return MODE_COLORS[currentMode] ?: DEFAULT_COLOR
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
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
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
        // Intent to open the app when notification is tapped
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
        
        // Stop action
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            createActionIntent(ACTION_STOP)
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(getSubtitle())
            .setSubText(currentModeName)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setLargeIcon(albumArtBitmap)
            .setContentIntent(contentIntent)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setColor(getModeColor())  // Cool color scheme per mode
            .setColorized(true)  // Enable colorized notification for media style
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    
    private fun createActionIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioForegroundService::class.java).apply {
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
