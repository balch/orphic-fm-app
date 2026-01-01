package org.balch.orpheus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
    private var isPlaying = true  // Track current playback state
    private var currentModeName = "Manual Play"  // Current mode display name
    private var currentMode = "USER"  // Current mode enum name for color selection
    
    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "orpheus_audio_playback"
        const val ACTION_PLAY = "org.balch.orpheus.PLAY"
        const val ACTION_PAUSE = "org.balch.orpheus.PAUSE"
        const val ACTION_STOP = "org.balch.orpheus.STOP"
        const val ACTION_UPDATE_STATE_PLAYING = "org.balch.orpheus.UPDATE_STATE_PLAYING"
        const val ACTION_UPDATE_STATE_PAUSED = "org.balch.orpheus.UPDATE_STATE_PAUSED"
        const val ACTION_UPDATE_METADATA = "org.balch.orpheus.UPDATE_METADATA"
        
        // Intent extras for metadata
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_MODE_DISPLAY_NAME = "extra_mode_display_name"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        
        var actionHandler: ((String) -> Unit)? = null
        
        /**
         * Color scheme for different playback modes.
         * Each mode has a distinct color for visual identification.
         */
        private val MODE_COLORS = mapOf(
            "USER" to Color.parseColor("#6B7FD7"),    // Soft indigo - calm, manual control
            "DRONE" to Color.parseColor("#7B68EE"),   // Medium slate blue - ambient, expansive
            "SOLO" to Color.parseColor("#9370DB"),    // Medium purple - expressive, lead
            "REPL" to Color.parseColor("#00CED1")     // Dark turquoise - code, live coding vibe
        )
        
        // Default notification color (deep purple gradient base)
        private val DEFAULT_COLOR = Color.parseColor("#7B68EE")
    }
    
    override fun onCreate() {
        super.onCreate()
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
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "USER"
                val modeDisplayName = intent.getStringExtra(EXTRA_MODE_DISPLAY_NAME) ?: "Manual Play"
                val intentIsPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)
                
                log.debug { "Metadata update: mode=$mode, displayName=$modeDisplayName, isPlaying=$intentIsPlaying" }
                
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
        val albumArt = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Orpheus Synthesizer")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getSubtitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentModeName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, albumArt)
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
        
        // Get app icon for large icon
        val albumArt = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orpheus Synthesizer")
            .setContentText(getSubtitle())
            .setSubText(currentModeName)  // Shows mode in notification shade
            .setSmallIcon(R.mipmap.ic_launcher_foreground)  // Use app icon for status bar
            .setLargeIcon(albumArt)  // App icon in expanded notification
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
