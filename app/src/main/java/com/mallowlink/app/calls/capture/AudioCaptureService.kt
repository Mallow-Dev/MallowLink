package com.mallowlink.app.calls.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mallowlink.app.R
import com.mallowlink.app.calls.consent.ConsentManager
import com.mallowlink.app.calls.db.dao.RecordingSessionDao
import com.mallowlink.app.calls.db.entity.ProcessingState
import com.mallowlink.app.calls.db.entity.RecordingSessionEntity
import com.mallowlink.app.util.crypto.AudioEncryption
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// AudioCaptureService
//
// Foreground service that:
//  1. Shows a persistent coloured recording indicator in the status bar
//  2. Captures microphone audio to an AES-256-GCM encrypted file
//  3. Stops immediately if the global consent flag is withdrawn
//  4. Posts a "recording active" bubble with a one-tap STOP action
//
// Lifecycle:
//   Start  → ACTION_START_CAPTURE (carries sessionId, sessionType)
//   Stop   → ACTION_STOP_CAPTURE  or user taps the notification action
// ─────────────────────────────────────────────────────────────────────────────

private const val CHANNEL_ID   = "recording_indicator"
private const val NOTIF_ID     = 3001
private const val SAMPLE_RATE  = 16_000          // 16 kHz – Whisper requirement
private const val CHANNEL_CFG  = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING     = AudioFormat.ENCODING_PCM_16BIT

@AndroidEntryPoint
class AudioCaptureService : Service() {

    @Inject lateinit var consentManager: ConsentManager
    @Inject lateinit var sessionDao: RecordingSessionDao
    @Inject lateinit var encryption: AudioEncryption

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioRecord: AudioRecord? = null
    private var isCapturing = false
    private var currentSessionId: String? = null
    private var currentKeyAlias: String? = null
    private var captureStartMs: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Intent action constants ───────────────────────────────────────────────

    companion object {
        const val ACTION_START_CAPTURE = "com.mallowlink.app.ACTION_START_CAPTURE"
        const val ACTION_STOP_CAPTURE  = "com.mallowlink.app.ACTION_STOP_CAPTURE"
        const val EXTRA_SESSION_ID     = "session_id"
        const val EXTRA_SESSION_TYPE   = "session_type"  // "CALL" or "MEETING"
        const val EXTRA_DISPLAY_TITLE  = "display_title"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val sessionId    = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
                val sessionType  = intent.getStringExtra(EXTRA_SESSION_TYPE) ?: "MEETING"
                val displayTitle = intent.getStringExtra(EXTRA_DISPLAY_TITLE) ?: "Recording"
                startCapture(sessionId, sessionType, displayTitle)
            }
            ACTION_STOP_CAPTURE -> stopCapture()
        }
        return START_NOT_STICKY
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    private fun startCapture(sessionId: String, sessionType: String, title: String) {
        if (isCapturing) return

        scope.launch {
            // Final guard: check consent hasn't been withdrawn
            if (!consentManager.isFeatureCurrentlyEnabled()) {
                Timber.w("Capture aborted – feature not enabled")
                stopSelf()
                return@launch
            }

            val keyAlias = "session_audio_$sessionId"
            encryption.createKey(keyAlias)

            val outFile = File(getDir("recordings", MODE_PRIVATE), "$sessionId.enc")
            val outStream = FileOutputStream(outFile)
            val encStream = encryption.encryptingOutputStream(keyAlias, outStream)

            // Persist initial session record
            sessionDao.upsert(
                RecordingSessionEntity(
                    id            = sessionId,
                    startedAt     = System.currentTimeMillis(),
                    sessionType   = sessionType,
                    keyAlias      = keyAlias,
                    displayTitle  = title,
                    state         = ProcessingState.PENDING,
                    consentGiven  = true,
                )
            )

            currentSessionId = sessionId
            currentKeyAlias  = keyAlias
            captureStartMs   = System.currentTimeMillis()

            acquireWakeLock()
            startForeground(NOTIF_ID, buildRecordingNotification(title))

            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, ENCODING)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CFG, ENCODING,
                bufferSize * 4,
            )

            isCapturing = true
            audioRecord?.startRecording()

            val buffer = ByteArray(bufferSize)
            try {
                while (isCapturing) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) encStream.write(buffer, 0, read)
                }
            } finally {
                encStream.close()
                outStream.close()
                val durationSecs = ((System.currentTimeMillis() - captureStartMs) / 1000).toInt()
                sessionDao.markEnded(
                    id          = sessionId,
                    endedAt     = System.currentTimeMillis(),
                    durationSecs = durationSecs,
                    audioPath   = outFile.absolutePath,
                )
                sessionDao.updateState(sessionId, ProcessingState.PENDING)
                Timber.i("Capture ended for $sessionId, ${durationSecs}s")
            }
        }
    }

    private fun stopCapture() {
        isCapturing = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        stopCapture()
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Recording in progress",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while MallowLink is recording audio"
            setShowBadge(true)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildRecordingNotification(title: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AudioCaptureService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 Recording: $title")
            .setContentText("Tap STOP to end the recording")
            .setSmallIcon(R.drawable.ic_mallow_tile)
            .setColor(0xFFE53935.toInt())   // red tint for unmistakeable indicator
            .setColorized(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_pause,
                "STOP",
                stopIntent,
            )
            .build()
    }

    // ── Wake lock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MallowLink:AudioCapture",
        ).also { it.acquire(4 * 60 * 60 * 1000L /* 4 h max */) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
