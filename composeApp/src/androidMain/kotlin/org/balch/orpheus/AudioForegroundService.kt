package org.balch.orpheus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
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
 */
class AudioForegroundService : Service() {
    
    private val log = logging("AudioForegroundService")
    private var mediaSession: MediaSessionCompat? = null
    
    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "orpheus_audio_playback"
        const val ACTION_PLAY = "org.balch.orpheus.PLAY"
        const val ACTION_PAUSE = "org.balch.orpheus.PAUSE"
        const val ACTION_STOP = "org.balch.orpheus.STOP"
        
        var actionHandler: ((String) -> Unit)? = null
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log.info { "AudioForegroundService started" }
        
        // Handle media button actions
        when (intent?.action) {
            ACTION_PLAY -> actionHandler?.invoke("play")
            ACTION_PAUSE -> actionHandler?.invoke("pause")
            ACTION_STOP -> {
                actionHandler?.invoke("stop")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        
        val notification = createNotification(isPlaying = true)
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
            
            // Set metadata
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Orpheus Synthesizer")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Playing")
                    .build()
            )
            
            isActive = true
        }
        
        updatePlaybackState(true)
    }
    
    fun updatePlaybackState(isPlaying: Boolean) {
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
        
        // Update notification
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
            .setContentTitle("Orpheus Synthesizer")
            .setContentText(if (isPlaying) "Playing" else "Paused")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(contentIntent)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
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
