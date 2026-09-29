package com.custom.astrion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * An otherwise empty foreground service, run while MainActivity exists.
 *
 * The HA connection, and with it the work alarm and alerts, lives in
 * MainActivity. With the remote off the dock and the screen off, an app with
 * nothing in the foreground is first in line for the low-memory killer on a
 * 1 GB device — and a killed app can't ring. A foreground service keeps the
 * process at foreground priority. (Doze's network cut-off is handled
 * separately, by the battery-optimisation exemption MainActivity asks for.)
 *
 * Not sticky: the connection lives in the Activity, so a service restarted on
 * its own would only show a notification with nothing behind it.
 */
class KeepAliveService : Service() {
    companion object {
        private const val CHANNEL = "ha_connection"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        return START_NOT_STICKY
    }
}
