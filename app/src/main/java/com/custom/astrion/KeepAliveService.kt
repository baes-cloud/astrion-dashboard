package com.custom.astrion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * An otherwise empty foreground service, run while MainActivity exists.
 *
 * The HA connection belongs to the process (AstrionApp), the alarm and alert
 * watching to MainActivity's ViewModels. With the remote off the dock and the
 * screen off, an app with nothing in the foreground is first in line for the
 * low-memory killer on a 1 GB device — and a killed app can't ring. A
 * foreground service keeps the process at foreground priority. (Doze's
 * network cut-off is handled separately, by the battery-optimisation
 * exemption MainActivity asks for.)
 *
 * Sticky: if the process is killed anyway, Android restarts the service (with
 * a null intent), which starts the process and with it the HA connection. The
 * alarm watching needs MainActivity, so that restart brings it back too, but
 * only with the screen off: nobody is looking at another app then, and that
 * is when an alarm would otherwise be missed.
 */
class KeepAliveService : Service() {
    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL = "ha_connection"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (intent == null) relaunchIfScreenOff()
        return START_STICKY
    }

    private fun relaunchIfScreenOff() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isInteractive) return
        Log.w(TAG, "Restarted after a kill with the screen off: bringing MainActivity back")
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }.onFailure { Log.w(TAG, "Couldn't relaunch MainActivity", it) }
    }

    private fun promoteToForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Home Assistant connection", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Astrion")
            .setContentText("Keeping the Home Assistant connection open for alarms")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }
}
