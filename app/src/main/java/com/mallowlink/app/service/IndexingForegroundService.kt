package com.mallowlink.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mallowlink.app.R

// ─────────────────────────────────────────────────────────────────────────────
// IndexingForegroundService
//
// Thin wrapper that starts a foreground notification so the OS allows
// the WorkManager's expedited work to run long-lived indexing tasks.
// The actual work is done by IndexingWorker.
// ─────────────────────────────────────────────────────────────────────────────

private const val CHANNEL_ID = "indexing_service"
private const val NOTIF_ID = 1002

class IndexingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Document Indexing",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("MallowLink")
        .setContentText("Indexing your documents…")
        .setSmallIcon(R.drawable.ic_mallow_tile)
        .setOngoing(true)
        .build()
}
