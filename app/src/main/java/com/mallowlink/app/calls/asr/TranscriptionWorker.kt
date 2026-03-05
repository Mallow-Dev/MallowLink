package com.mallowlink.app.calls.asr

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import com.mallowlink.app.calls.db.dao.RecordingSessionDao
import com.mallowlink.app.calls.db.dao.TranscriptSegmentDao
import com.mallowlink.app.calls.db.entity.ProcessingState
import com.mallowlink.app.util.crypto.AudioEncryption
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ─────────────────────────────────────────────────────────────────────────────
// TranscriptionWorker
//
// Runs post-call (or live if the device is capable) to:
//   1. Decrypt the encrypted audio file
//   2. Decode PCM samples from raw bytes
//   3. Feed to TranscriptionEngine in 30-second windows
//   4. Persist resulting TranscriptSegmentEntities to Room
//   5. Enqueue SummarisationWorker on completion
//
// Runs as an expedited worker so it completes within Android's allowed
// background execution window (~10 min for expedited jobs).
// ─────────────────────────────────────────────────────────────────────────────

@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val sessionDao: RecordingSessionDao,
    private val transcriptDao: TranscriptSegmentDao,
    private val engine: TranscriptionEngine,
    private val encryption: AudioEncryption,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        Timber.d("TranscriptionWorker starting for session $sessionId")

        val session = sessionDao.getById(sessionId) ?: run {
            Timber.w("Session $sessionId not found"); return Result.failure()
        }
        val audioPath = session.audioPath ?: run {
            Timber.w("No audio path for $sessionId"); return Result.failure()
        }

        sessionDao.updateState(sessionId, ProcessingState.TRANSCRIBING)

        // Init ASR engine (loads ONNX model)
        engine.init()
        if (!engine.isReady()) {
            Timber.e("TranscriptionEngine not ready – skipping transcription")
            sessionDao.updateState(sessionId, ProcessingState.ERROR, "ASR model unavailable")
            return Result.failure()
        }

        return try {
            // Decrypt audio
            val audioFile = File(audioPath)
            val pcmSamples = decryptAudioToPcm(audioFile, session.keyAlias)

            // Transcribe in streaming windows
            val segments = mutableListOf<com.mallowlink.app.calls.db.entity.TranscriptSegmentEntity>()
            engine.transcribe(sessionId, pcmSamples).collect { segment ->
                segments.add(segment)
                // Insert as we go for live-transcript UX
                transcriptDao.insertAll(listOf(segment))
                Timber.v("Segment [${segment.startSec}s]: ${segment.text.take(60)}")
            }

            Timber.i("Transcription done: ${segments.size} segments for $sessionId")

            // Trigger summarisation
            val summaryWorkRequest = SummarisationWorker.buildRequest(sessionId)
            androidx.work.WorkManager.getInstance(context).enqueue(summaryWorkRequest)

            sessionDao.updateState(sessionId, ProcessingState.SUMMARISING)
            Result.success()

        } catch (e: Exception) {
            Timber.e(e, "Transcription failed for $sessionId")
            sessionDao.updateState(sessionId, ProcessingState.ERROR, e.message)
            Result.failure(Data.Builder().putString("error", e.message).build())
        }
    }

    /** Decrypt the encrypted audio file and return PCM-16 samples. */
    private fun decryptAudioToPcm(file: File, keyAlias: String): ShortArray {
        val decrypted = FileInputStream(file).use { fis ->
            encryption.decryptingInputStream(keyAlias, fis).readBytes()
        }
        // Convert raw bytes (little-endian PCM-16) to ShortArray
        val shorts = ShortArray(decrypted.size / 2)
        ByteBuffer.wrap(decrypted).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"

        fun buildRequest(sessionId: String) =
            OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(Data.Builder().putString(KEY_SESSION_ID, sessionId).build())
                .addTag("transcription_$sessionId")
                .build()
    }
}
