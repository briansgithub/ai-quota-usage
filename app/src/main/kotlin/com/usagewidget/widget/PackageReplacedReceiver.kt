package com.usagewidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import com.usagewidget.refresh.UsageRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * After an in-place APK update, existing home-screen Glance instances stay
 * registered (same [UsageWidgetReceiver] + provider XML) but can sit on a
 * stale RemoteViews until something calls [androidx.glance.appwidget.updateAll].
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        UsageRefreshWorker.enqueuePeriodic(app, ExistingPeriodicWorkPolicy.UPDATE)
        UsageRefreshWorker.enqueueOnce(app)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                WidgetPublisher.push(app)
            } finally {
                pending.finish()
            }
        }
    }
}
