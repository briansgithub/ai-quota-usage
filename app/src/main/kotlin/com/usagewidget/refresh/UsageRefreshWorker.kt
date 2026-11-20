package com.usagewidget.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.workDataOf
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
            UsageRepository.get(applicationContext).refresh(
                allowHtmlFallback = inputData.getBoolean(KEY_HTML_FALLBACK, true)
            )
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_NAME = "usage-widget-periodic"
        const val PERIODIC_TAG = "usage-widget-periodic-tag"
        const val ONCE_NAME = "usage-widget-once"
        const val EXPEDITED_NAME = "usage-widget-expedited"
        private const val KEY_HTML_FALLBACK = "html_fallback"

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
                // Without a network the run only records a failure and waits another
                // period; with the constraint it fires as soon as the network is back.
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
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

        /**
         * Immediate refresh for the widget's refresh button and screen-on/unlock.
         * Runs as expedited work (no network in a broadcast receiver, so no ANR; not
         * held back by Doze/standby job deferral) and does not use the hidden WebView,
         * like the in-app refresh. KEEP: a tap while one is running does not restart it.
         */
        fun enqueueExpedited(context: Context) {
            val request = OneTimeWorkRequestBuilder<UsageRefreshWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_HTML_FALLBACK to false))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                EXPEDITED_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
