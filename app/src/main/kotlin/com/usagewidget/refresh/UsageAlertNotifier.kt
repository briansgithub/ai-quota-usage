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
import com.usagewidget.domain.AlertChannel
import com.usagewidget.domain.AlertToPost
import com.usagewidget.domain.QuietHours
import com.usagewidget.domain.QuotaThresholdEngine
import com.usagewidget.domain.UsageAlertEngine
import com.usagewidget.domain.UsageSnapshot

class UsageAlertNotifier(
    private val context: Context,
    private val settings: AppSettings = AppSettings.get(context)
) {
    fun evaluate(
        previous: UsageSnapshot,
        current: UsageSnapshot,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ensureChannels()
        val plan = UsageAlertEngine.evaluate(
            previous = previous,
            current = current,
            nowMs = nowMs,
            prefs = settings.alertPreferencesValue(),
            seenKeys = settings.seenAlertKeys()
        )
        val quiet = QuietHours.isQuiet(
            nowMs = nowMs,
            enabled = settings.quietHoursEnabled,
            startHour = settings.quietHoursStartHour,
            endHour = settings.quietHoursEndHour
        )
        val quota = QuotaThresholdEngine.evaluate(
            snapshot = current,
            nowMs = nowMs,
            prefs = settings.quotaPreferencesValue(),
            prior = settings.quotaFiredStates(),
            quiet = quiet || !hasPermission()
        )
        settings.replaceQuotaFiredStates(quota.states)
        if (hasPermission()) {
            plan.alerts.forEach { post(it) }
            quota.alerts.forEach { post(it) }
            settings.replaceSeenAlertKeys(plan.newSeenKeys)
        }
        TimedRefreshScheduler.scheduleAll(context, plan.wakes, nowMs)
    }

    fun postTest() {
        ensureChannels()
        if (!hasPermission()) return
        post(QuotaThresholdEngine.testAlert())
    }

    private fun post(alert: AlertToPost) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            alert.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val channelId = when (alert.channel) {
            AlertChannel.RESTORED -> CHANNEL_RESTORED
            AlertChannel.PACING -> CHANNEL_PACING
            AlertChannel.QUOTA -> CHANNEL_QUOTA
        }
        val icon = when (alert.channel) {
            AlertChannel.RESTORED -> android.R.drawable.stat_notify_sync
            AlertChannel.PACING -> android.R.drawable.ic_dialog_info
            AlertChannel.QUOTA -> android.R.drawable.ic_dialog_info
        }
        val priority = when (alert.channel) {
            AlertChannel.RESTORED -> NotificationCompat.PRIORITY_DEFAULT
            AlertChannel.PACING -> NotificationCompat.PRIORITY_HIGH
            AlertChannel.QUOTA -> NotificationCompat.PRIORITY_HIGH
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(icon)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(priority)
            .build()
        NotificationManagerCompat.from(context).notify(alert.notificationId, notification)
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_RESTORED) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RESTORED,
                    context.getString(R.string.notification_channel_restored_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notification_channel_restored_description)
                }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_PACING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PACING,
                    context.getString(R.string.notification_channel_pacing_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_pacing_description)
                }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_QUOTA) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_QUOTA,
                    context.getString(R.string.notification_channel_quota_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_quota_description)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_RESTORED = "usage_restored"
        const val CHANNEL_PACING = "pacing_alerts"
        const val CHANNEL_QUOTA = QuotaThresholdEngine.CHANNEL_ID
    }
}
