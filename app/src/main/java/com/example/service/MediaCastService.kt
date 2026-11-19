package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class MediaCastService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.MEDIA_CAST_START"
        const val ACTION_STOP = "com.example.action.MEDIA_CAST_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_RECEIVER = "receiver"

        private const val CHANNEL_ID = "aircast_media_playback"
        private const val NOTIFICATION_ID = 4102

        fun createStartIntent(context: Context, title: String, receiverName: String): Intent {
            return Intent(context, MediaCastService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_RECEIVER, receiverName)
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, MediaCastService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CastEngineHolder.ensureInitialized(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CastEngineHolder.requestStopMedia()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Streaming Media"
                val receiver = intent.getStringExtra(EXTRA_RECEIVER) ?: "AirPlay Receiver"
                val notification = buildNotification(title, receiver)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_media),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, receiver: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            createStopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("Casting to $receiver")
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_stop_cast), stopIntent)
            .setOngoing(true)
            .build()
    }
}
