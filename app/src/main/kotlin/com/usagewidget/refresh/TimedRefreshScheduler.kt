package com.usagewidget.refresh

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.usagewidget.domain.ScheduledWake
import java.util.concurrent.TimeUnit

/**
 * One-off WorkManager jobs at a known reset or pacing instant so alerts are not
 * stuck behind the 15-minute periodic refresh. WorkManager delays are inexact
 * (no SCHEDULE_EXACT_ALARM); they still fire much closer than the next period.
 */
object TimedRefreshScheduler {
    const val TAG = "timed-refresh"

    fun scheduleAll(context: Context, wakes: List<ScheduledWake>, nowMs: Long) {
        if (wakes.isEmpty()) return
        val wm = WorkManager.getInstance(context.applicationContext)
        for (wake in wakes) {
            val delay = wake.atEpochMs - nowMs
            if (delay <= 1_500L) {
                UsageRefreshWorker.enqueueOnce(context)
                continue
            }
            val request = OneTimeWorkRequestBuilder<UsageRefreshWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .build()
            wm.enqueueUniqueWork(wake.uniqueName, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
