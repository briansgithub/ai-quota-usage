package com.usagewidget.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.usagewidget.data.AppSettings
import com.usagewidget.data.UsageRepository
import com.usagewidget.domain.UsageSnapshot

/**
 * Writes the latest snapshot into Glance's per-instance DataStore, then
 * awaits [androidx.glance.appwidget.updateAll] so every home-screen tile
 * recomposes from that state instead of a one-shot provideGlance capture.
 */
object WidgetPublisher {
    const val TAG = "UsageWidget"

    suspend fun push(
        context: Context,
        snapshot: UsageSnapshot? = null,
        nextRefreshAt: Long? = null
    ) {
        val app = context.applicationContext
        val snap = snapshot ?: UsageRepository.get(app).snapshot.value
        val next = nextRefreshAt ?: AppSettings.get(app).nextRefreshAtEpochMs.value
        val manager = GlanceAppWidgetManager(app)
        val ids = runCatching { manager.getGlanceIds(UsageGlanceWidget::class.java) }
            .getOrElse { emptyList() }
        for (id in ids) {
            updateAppWidgetState(app, id) { prefs ->
                WidgetGlanceState.write(prefs, snap, next)
            }
        }
        UsageGlanceWidget().updateAll(app)
        Log.i(
            TAG,
            "updateAll widgets=${ids.size} fetchedAt=${snap.fetchedAtEpochMs} next=$next"
        )
    }
}
