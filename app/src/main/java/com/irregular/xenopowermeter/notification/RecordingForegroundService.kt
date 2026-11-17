package com.irregular.xenopowermeter.notification

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Runs for the duration of a recording session only, and exists for exactly
 * one reason: holding the process at foreground priority so OEM battery
 * savers cannot tear down the USB sample stream while the activity is
 * backgrounded or the screen is off.
 *
 * It posts NO notification of its own. The live-measurement push the user
 * already opted into doubles as the foreground notification — same id and
 * channel ([IslandHelper.NOTIFICATION_ID]) — so the tray keeps showing one
 * entry, not two. The sample loop's periodic notify() with the same id keeps
 * refreshing that one entry's content while the service is foreground.
 *
 * START_NOT_STICKY: a dead recording session must not be silently restarted
 * by the system; the user starts the next one from the UI.
 */
class RecordingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        // Must be called promptly after startForegroundService; the build is
        // synchronous, and the next live-measurement update replaces the
        // placeholder content with real readings.
        ServiceCompat.startForeground(
            this,
            IslandHelper.NOTIFICATION_ID,
            IslandHelper.buildForegroundNotification(this),
            type
        )
        return START_NOT_STICKY
    }

    companion object {
        /** Recording started: raise the process to foreground priority. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingForegroundService::class.java)
            )
        }

        /** Recording ended: release the priority. No-op when not running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }
    }
}
