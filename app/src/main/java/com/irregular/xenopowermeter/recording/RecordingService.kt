package com.irregular.xenopowermeter.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.irregular.xenopowermeter.MainActivity
import com.irregular.xenopowermeter.R

class RecordingService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notification = buildNotification()
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val localized = com.irregular.xenopowermeter.AppSettings.localizedContext(this)
        // IMPORTANCE_MIN keeps the mandatory foreground-service notification
        // out of the shade/lock screen — it's a keep-alive marker, not content;
        // the island notification is the user-facing one.
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = localized.getString(R.string.recording_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("XenoPowerMeter")
            .setContentText(
                com.irregular.xenopowermeter.AppSettings.localizedContext(this)
                    .getString(R.string.recording_notification_text)
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 2
        const val ACTION_START = "com.irregular.xenopowermeter.START_RECORDING"
        const val ACTION_STOP = "com.irregular.xenopowermeter.STOP_RECORDING"
    }
}
