package com.mallowlink.app.workers

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.mallowlink.app.data.db.dao.SourceDao
import com.mallowlink.app.data.indexing.FileIndexer
import com.mallowlink.app.data.model.EmbeddingManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// SyncWorker
//
// Periodically re-scans all enabled sources for changed/new files.
// Runs at most once every 6 hours; deferred when on battery saver or low battery.
// ─────────────────────────────────────────────────────────────────────────────

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val sourceDao: SourceDao,
    private val fileIndexer: FileIndexer,
    private val embeddingManager: EmbeddingManager,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("SyncWorker starting")
        embeddingManager.init()
        if (!embeddingManager.isReady()) return Result.retry()

        val sources = sourceDao.getEnabled()
        Timber.d("SyncWorker: ${sources.size} sources to sync")

        for (source in sources) {
            try {
                fileIndexer.indexSource(source.id, Uri.parse(source.uriString))
            } catch (e: Exception) {
                Timber.e(e, "SyncWorker: failed for source ${source.id}")
            }
        }
        return Result.success()
    }

    companion object {
        fun buildPeriodicRequest() =
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(false)
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .addTag("periodic_sync")
                .build()
    }
}
