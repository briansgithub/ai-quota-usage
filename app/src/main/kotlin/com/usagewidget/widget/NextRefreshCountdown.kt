package com.usagewidget.widget

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.usagewidget.R
import com.usagewidget.domain.RefreshSchedule

object NextRefreshCountdown {
    fun remoteViews(context: Context, nextEpochMs: Long, nowMs: Long = System.currentTimeMillis()): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_next_refresh)
        val remaining = RefreshSchedule.remainingMs(nextEpochMs, nowMs)
        if (nextEpochMs <= 0L) {
            views.setViewVisibility(R.id.next_refresh_countdown, View.GONE)
            views.setViewVisibility(R.id.next_refresh_static, View.GONE)
            return views
        }
        if (remaining <= 0L) {
            views.setViewVisibility(R.id.next_refresh_countdown, View.GONE)
            views.setViewVisibility(R.id.next_refresh_static, View.VISIBLE)
            views.setTextViewText(R.id.next_refresh_static, RefreshSchedule.formatCountdown(0L))
            return views
        }
        val base = RefreshSchedule.chronometerBaseElapsedRealtime(
            nextEpochMs = nextEpochMs,
            nowEpochMs = nowMs,
            elapsedRealtime = SystemClock.elapsedRealtime()
        )
        views.setViewVisibility(R.id.next_refresh_countdown, View.VISIBLE)
        views.setViewVisibility(R.id.next_refresh_static, View.GONE)
        views.setChronometerCountDown(R.id.next_refresh_countdown, true)
        views.setChronometer(R.id.next_refresh_countdown, base, "next %s", true)
        return views
    }
}
