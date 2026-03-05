package com.mallowlink.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.mallowlink.app.workers.SyncWorker
import timber.log.Timber

// ─────────────────────────────────────────────────────────────────────────────
// BootReceiver – reschedule periodic sync after device reboot
// ─────────────────────────────────────────────────────────────────────────────

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
            )
        ) return

        Timber.i("Device rebooted – rescheduling periodic sync")
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "periodic_sync",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            SyncWorker.buildPeriodicRequest(),
        )
    }
}
