package com.mallowlink.app.calls.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mallowlink.app.R
import com.mallowlink.app.calls.consent.ConsentManager
import com.mallowlink.app.calls.ui.screens.ConsentSessionType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// CallStateMonitor
//
// BroadcastReceiver that listens for phone call state changes.
// On OFFHOOK (call answered): posts a consent notification with Accept/Decline
// actions so the user can opt in to record the current call.
//
// Design:
//  • Does NOT auto-start recording; the user must explicitly tap "Record"
//  • When the call ends without consent, no audio is ever captured
//  • Consent notification is dismissed automatically when the call ends
// ─────────────────────────────────────────────────────────────────────────────

private const val CHANNEL_CONSENT = "call_consent"
private const val NOTIF_CONSENT   = 3002

private const val ACTION_RECORD   = "com.mallowlink.app.CONSENT_RECORD"
private const val ACTION_SKIP     = "com.mallowlink.app.CONSENT_SKIP"
private const val EXTRA_SESSION   = "session_id"
private const val EXTRA_CALLER    = "caller"

@AndroidEntryPoint
class CallStateReceiver : BroadcastReceiver() {

    @Inject lateinit var consentManager: ConsentManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state  = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val caller = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown"

        scope.launch {
            when (state) {
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // Call is connected — offer consent
                    if (consentManager.isCallRecordingEnabled.first()) {
                        val sessionId = UUID.randomUUID().toString()
                        postConsentNotification(context, sessionId, caller)
                        Timber.i("Call started — consent notification posted for session $sessionId")
                    }
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // Call ended — dismiss consent notification
                    NotificationManagerCompat.from(context).cancel(NOTIF_CONSENT)
                }
            }
        }
    }

    private fun postConsentNotification(context: Context, sessionId: String, caller: String) {
        createConsentChannel(context)

        val recordIntent = PendingIntentHelper.buildConsentPendingIntent(
            context, ACTION_RECORD, sessionId, caller
        )
        val skipIntent = PendingIntentHelper.buildConsentPendingIntent(
            context, ACTION_SKIP, sessionId, caller
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CONSENT)
            .setSmallIcon(R.drawable.ic_mallow_tile)
            .setContentTitle("Record this call?")
            .setContentText("Tap to summarise with MallowLink (on-device only)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(R.drawable.ic_mallow_tile, "🔴 Record", recordIntent)
            .addAction(android.R.drawable.ic_delete, "Skip", skipIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_CONSENT, notification)
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS permission not granted")
        }
    }

    private fun createConsentChannel(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_CONSENT,
            NotificationManagerCompat.IMPORTANCE_HIGH,
        )
            .setName("Call recording consent")
            .setDescription("Prompts to record a phone call")
            .build()
        nm.createNotificationChannel(channel)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ConsentActionReceiver
//
// Handles the "Record" / "Skip" actions from the consent notification.
// ─────────────────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class ConsentActionReceiver : BroadcastReceiver() {

    @Inject lateinit var consentManager: ConsentManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val caller    = intent.getStringExtra(EXTRA_CALLER) ?: "Unknown"

        NotificationManagerCompat.from(context).cancel(NOTIF_CONSENT)

        when (intent.action) {
            ACTION_RECORD -> {
                scope.launch {
                    if (consentManager.isFeatureCurrentlyEnabled()) {
                        context.startForegroundService(
                            Intent(context, AudioCaptureService::class.java).apply {
                                action = AudioCaptureService.ACTION_START_CAPTURE
                                putExtra(AudioCaptureService.EXTRA_SESSION_ID, sessionId)
                                putExtra(AudioCaptureService.EXTRA_SESSION_TYPE, "CALL")
                                putExtra(AudioCaptureService.EXTRA_DISPLAY_TITLE, caller)
                            }
                        )
                        Timber.i("User consented to record call session $sessionId")
                    }
                }
            }
            ACTION_SKIP -> {
                Timber.i("User declined to record call session $sessionId")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PendingIntent helper (avoids duplication)
// ─────────────────────────────────────────────────────────────────────────────

object PendingIntentHelper {
    fun buildConsentPendingIntent(
        context: Context,
        action: String,
        sessionId: String,
        caller: String,
    ): android.app.PendingIntent {
        val intent = Intent(context, ConsentActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_SESSION, sessionId)
            putExtra(EXTRA_CALLER, caller)
        }
        return android.app.PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
