package com.mallowlink.app.calls.asr

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import com.mallowlink.app.calls.db.dao.RecordingSessionDao
import com.mallowlink.app.calls.db.entity.ProcessingState
import com.mallowlink.app.calls.linking.SmartLinker
import com.mallowlink.app.calls.summary.SummarisationPipeline
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

// ─────────────────────────────────────────────────────────────────────────────
// SummarisationWorker
//
// Runs after TranscriptionWorker.  Generates the call summary and then
// triggers SmartLinker to auto-suggest related documents / calendar events.
// ─────────────────────────────────────────────────────────────────────────────

@HiltWorker
class SummarisationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pipeline: SummarisationPipeline,
    private val sessionDao: RecordingSessionDao,
    private val smartLinker: SmartLinker,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        Timber.d("SummarisationWorker starting for $sessionId")

        return try {
            val summary = pipeline.summarise(sessionId)
            if (summary != null) {
                // Auto-suggest links to related content in the MallowLink index
                smartLinker.suggestLinks(summary)
                sessionDao.updateState(sessionId, ProcessingState.DONE)
                Timber.i("Summarisation + linking complete for $sessionId")
                Result.success()
            } else {
                sessionDao.updateState(sessionId, ProcessingState.ERROR, "Summarisation returned null")
                Result.failure()
            }
        } catch (e: Exception) {
            Timber.e(e, "SummarisationWorker failed for $sessionId")
            sessionDao.updateState(sessionId, ProcessingState.ERROR, e.message)
            Result.failure(Data.Builder().putString("error", e.message).build())
        }
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"

        fun buildRequest(sessionId: String) =
            OneTimeWorkRequestBuilder<SummarisationWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(Data.Builder().putString(KEY_SESSION_ID, sessionId).build())
                .addTag("summarisation_$sessionId")
                .build()
    }
}
