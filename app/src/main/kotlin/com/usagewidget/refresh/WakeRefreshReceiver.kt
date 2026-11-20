package com.usagewidget.refresh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.usagewidget.data.UsageRepository
import com.usagewidget.domain.RefreshSchedule

/**
 * Refreshes stale data when the screen turns on or the phone is unlocked, which is when
 * the widget is actually looked at. The 15-minute periodic job can be deferred for
 * hours by Doze/standby, so without this the widget sat stale until the next window
 * while opening the app (which refreshes in onResume) showed fresh data.
 *
 * These broadcasts cannot be declared in the manifest, so the receiver is registered
 * from [com.usagewidget.UsageWidgetApp]; it works whenever the process is alive (the
 * periodic job and package updates restart it).
 */
class WakeRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val lastSuccess = RefreshSchedule.lastSuccessEpochMs(UsageRepository.get(app).snapshot.value)
        if (!RefreshSchedule.shouldRefreshOnWake(lastSuccess, lastRequestEpochMs, now)) return
        lastRequestEpochMs = now
        UsageRefreshWorker.enqueueExpedited(app)
    }

    companion object {
        @Volatile private var lastRequestEpochMs = 0L

        fun register(context: Context) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                context.applicationContext,
                WakeRefreshReceiver(),
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }
}
