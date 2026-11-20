package com.usagewidget.widget

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.usagewidget.R
import com.usagewidget.domain.RefreshSchedule

/**
 * Widget footer: `updated 03:12 ago · next 11:48`. Both are live Chronometers the
 * launcher ticks itself, so they stay truthful between (possibly delayed) updates.
 * The age counts up from the last successful fetch; the countdown is hidden once the
 * scheduled refresh is overdue instead of sitting at "next 00:00".
 */
object NextRefreshCountdown {
    fun remoteViews(
        context: Context,
        nextEpochMs: Long,
        lastSuccessEpochMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_next_refresh)
        val elapsed = SystemClock.elapsedRealtime()
        val remaining = RefreshSchedule.remainingMs(nextEpochMs, nowMs)

        val showAge = lastSuccessEpochMs > 0L
        if (showAge) {
            val ageMs = (nowMs - lastSuccessEpochMs).coerceAtLeast(0L)
            views.setViewVisibility(R.id.updated_age, View.VISIBLE)
            views.setChronometerCountDown(R.id.updated_age, false)
            views.setChronometer(R.id.updated_age, elapsed - ageMs, "updated %s ago", true)
        }

        val showNext = nextEpochMs > 0L && remaining > 0L
        views.setViewVisibility(R.id.next_refresh_static, View.GONE)
        if (showNext) {
            views.setViewVisibility(R.id.next_refresh_countdown, View.VISIBLE)
            views.setChronometerCountDown(R.id.next_refresh_countdown, true)
            views.setChronometer(
                R.id.next_refresh_countdown,
                RefreshSchedule.chronometerBaseElapsedRealtime(nextEpochMs, nowMs, elapsed),
                "next %s",
                true
            )
        } else {
            views.setViewVisibility(R.id.next_refresh_countdown, View.GONE)
        }
        views.setViewVisibility(R.id.footer_separator, if (showAge && showNext) View.VISIBLE else View.GONE)
        return views
    }
}
