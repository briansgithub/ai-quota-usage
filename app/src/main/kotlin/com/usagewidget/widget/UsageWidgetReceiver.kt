package com.usagewidget.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.usagewidget.refresh.UsageRefreshWorker

class UsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        UsageRefreshWorker.enqueuePeriodic(context)
        UsageRefreshWorker.enqueueOnce(context)
    }
}
