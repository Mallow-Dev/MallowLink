package com.mallowlink.app.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import com.mallowlink.app.R
import com.mallowlink.app.data.indexing.FileIndexer
import com.mallowlink.app.data.model.EmbeddingManager
import com.mallowlink.app.ui.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

private const val CHANNEL_ID = "indexing_channel"
private const val NOTIF_ID = 1001

// ─────────────────────────────────────────────────────────────────────────────
// IndexingWorker
//
// Runs as an expedited foreground worker so the OS doesn't kill it mid-index.
// Battery-aware: skips re-indexing when battery < 15% unless plugged in.
// ─────────────────────────────────────────────────────────────────────────────

@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val fileIndexer: FileIndexer,
    private val embeddingManager: EmbeddingManager,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE_ID) ?: return Result.failure()
        val uriString = inputData.getString(KEY_TREE_URI) ?: return Result.failure()
        val forceReindex = inputData.getBoolean(KEY_FORCE_REINDEX, false)

        // Battery guard
        if (!forceReindex && isBatteryLow()) {
            Timber.w("Skipping indexing – battery low and not charging")
            return Result.retry()
        }

        createNotificationChannel()
        setForeground(buildForegroundInfo("Initialising…"))

        // Ensure embedding model is ready
        embeddingManager.init()
        if (!embeddingManager.isReady()) {
            Timber.e("Embedding model unavailable – aborting indexing")
            return Result.failure(
                Data.Builder().putString("error", "Embedding model not ready").build()
            )
        }

        // Observe progress and push notifications
        val progressJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            fileIndexer.indexingState.collect { state ->
                when (state) {
                    is com.mallowlink.app.domain.model.IndexingState.Running -> {
                        setForeground(buildForegroundInfo(
                            "Indexing ${state.currentFile}",
                            state.progress,
                            state.total,
                        ))
                    }
                    else -> { /* handled below */ }
                }
            }
        }

        return try {
            fileIndexer.indexSource(sourceId, Uri.parse(uriString))
            progressJob.cancel()
            Result.success()
        } catch (e: Exception) {
            progressJob.cancel()
            Timber.e(e, "Indexing failed for source $sourceId")
            Result.failure(Data.Builder().putString("error", e.message).build())
        }
    }

    private fun isBatteryLow(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging
        return level < 15 && !isCharging
    }

    private fun createNotificationChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Indexing", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Background document indexing" }
            )
        }
    }

    private fun buildForegroundInfo(
        message: String,
        progress: Int = 0,
        total: Int = 0,
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(context)
            .createCancelPendingIntent(id)

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("MallowLink – Indexing")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_mallow_tile)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelIntent)
            .apply {
                if (total > 0) {
                    setProgress(total, progress, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .setOngoing(true)
            .build()

        return ForegroundInfo(NOTIF_ID, notification)
    }

    companion object {
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_FORCE_REINDEX = "force_reindex"

        fun buildRequest(sourceId: String, treeUri: String, force: Boolean = false) =
            OneTimeWorkRequestBuilder<IndexingWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    Data.Builder()
                        .putString(KEY_SOURCE_ID, sourceId)
                        .putString(KEY_TREE_URI, treeUri)
                        .putBoolean(KEY_FORCE_REINDEX, force)
                        .build()
                )
                .addTag("indexing_$sourceId")
                .build()
    }
}

// Extension alias for readability
private val WorkManager get() = androidx.work.WorkManager
private fun kotlinx.coroutines.CoroutineScope.launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
    kotlinx.coroutines.launch(block = block)
