package com.mallowlink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mallowlink.app.R
import com.mallowlink.app.ui.MainActivity

// ─────────────────────────────────────────────────────────────────────────────
// BubbleService
//
// Posts a persistent notification that appears as a conversation bubble
// (Android 11+ Bubbles API) giving quick access to MallowLink from any app.
// ─────────────────────────────────────────────────────────────────────────────

private const val BUBBLE_CHANNEL_ID = "mallow_bubble"
private const val BUBBLE_NOTIF_ID = 2001

class BubbleService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createBubbleChannel()
        postBubbleNotification()
        return START_STICKY
    }

    private fun createBubbleChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(BUBBLE_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                BUBBLE_CHANNEL_ID,
                "MallowLink Quick Query",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Persistent bubble for quick queries"
                setAllowBubbles(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun postBubbleNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Bubble metadata (Android 11+)
        val bubbleIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_MUTABLE,
        )

        val bubbleMetadata = Notification.BubbleMetadata.Builder(
            bubbleIntent,
            Icon.createWithResource(this, R.drawable.ic_mallow_tile)
        )
            .setDesiredHeight(600)
            .setSuppressNotification(true)
            .setAutoExpandBubble(false)
            .build()

        val person = Person.Builder()
            .setName("MallowLink")
            .setIcon(Icon.createWithResource(this, R.drawable.ic_mallow_tile))
            .setBot(true)
            .build()

        val notification = Notification.Builder(this, BUBBLE_CHANNEL_ID)
            .setContentTitle("MallowLink")
            .setContentText("Tap to ask a question")
            .setSmallIcon(R.drawable.ic_mallow_tile)
            .setContentIntent(openIntent)
            .setBubbleMetadata(bubbleMetadata)
            .addPerson(person)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(BUBBLE_NOTIF_ID, notification)
    }
}
