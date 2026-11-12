package com.usagewidget.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.usagewidget.data.AppSettings
import com.usagewidget.data.UsageRepository
import com.usagewidget.domain.RefreshSchedule
import com.usagewidget.widget.WidgetPublisher
import java.util.concurrent.TimeUnit

class UsageRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (PERIODIC_TAG in tags) {
            val settings = AppSettings.get(applicationContext)
            settings.onPeriodicRun(System.currentTimeMillis())
            runCatching { WidgetPublisher.push(applicationContext) }
        }
        return try {
            UsageRepository.get(applicationContext).refresh(allowHtmlFallback = true)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_NAME = "usage-widget-periodic"
        const val PERIODIC_TAG = "usage-widget-periodic-tag"
        const val ONCE_NAME = "usage-widget-once"

        fun enqueuePeriodic(
            context: Context,
            policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
        ) {
            val settings = AppSettings.get(context)
            val resolved = if (settings.enqueuedPeriodicIntervalMs != RefreshSchedule.INTERVAL_MS) {
                ExistingPeriodicWorkPolicy.UPDATE
            } else {
                policy
            }
            val request = PeriodicWorkRequestBuilder<UsageRefreshWorker>(
                RefreshSchedule.INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
                .addTag(PERIODIC_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                resolved,
                request
            )
            settings.enqueuedPeriodicIntervalMs = RefreshSchedule.INTERVAL_MS
            val now = System.currentTimeMillis()
            if (resolved == ExistingPeriodicWorkPolicy.UPDATE) {
                settings.armNextRefreshFromNow(now)
            } else {
                settings.armNextRefreshIfUnarmed(now)
            }
        }

        fun enqueueOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<UsageRefreshWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
