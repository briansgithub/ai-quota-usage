package com.usagewidget.refresh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.usagewidget.MainActivity
import com.usagewidget.R
import com.usagewidget.data.AppSettings
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.domain.StaleView
import com.usagewidget.domain.UsageSnapshot

class StaleNotifier(
    private val context: Context,
    private val settings: AppSettings = AppSettings.get(context)
) {
    fun sync(snapshot: UsageSnapshot, nowMs: Long = System.currentTimeMillis()) {
        ensureChannel()
        if (!settings.failureNotificationsEnabled || !hasPermission()) {
            SoftwareSectionId.entries.forEach { cancel(it) }
            return
        }
        SoftwareSectionId.entries.forEach { section ->
            val stale: StaleView = snapshot.health(section).staleView(nowMs)
            if (stale.shouldNotify) {
                val already = settings.lastNotifiedAt(section.wireName)
                val lastSuccess = snapshot.health(section).lastSuccessEpochMs
                if (already > 0L && already >= lastSuccess) {
                    return@forEach
                }
                notify(section, stale)
                settings.markNotified(section.wireName, nowMs)
            } else {
                settings.clearNotified(section.wireName)
                cancel(section)
            }
        }
    }

    private fun notify(section: SoftwareSectionId, stale: StaleView) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_DIAGNOSTICS, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            section.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = listOfNotNull(stale.lastUpdatedLine, stale.shortReason).joinToString(" · ")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("${section.title} usage is stale")
            .setContentText(text.ifBlank { "Latest refresh failed." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.ifBlank { "Latest refresh failed." }))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(section), notification)
    }

    private fun cancel(section: SoftwareSectionId) {
        NotificationManagerCompat.from(context).cancel(notificationId(section))
    }

    private fun notificationId(section: SoftwareSectionId): Int = 4100 + section.ordinal

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "usage_retrieval"
    }
}
