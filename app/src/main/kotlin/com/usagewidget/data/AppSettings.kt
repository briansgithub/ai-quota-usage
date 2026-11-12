package com.usagewidget.data

import android.content.Context
import com.usagewidget.domain.AlertPreferences
import com.usagewidget.domain.PacingPolicy
import com.usagewidget.domain.QuietHours
import com.usagewidget.domain.RefreshSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _nextRefreshAtEpochMs = MutableStateFlow(prefs.getLong(KEY_NEXT_REFRESH, 0L))
    val nextRefreshAtEpochMs: StateFlow<Long> = _nextRefreshAtEpochMs
    private val _alertPreferences = MutableStateFlow(readAlertPreferences())
    val alertPreferences: StateFlow<AlertPreferences> = _alertPreferences.asStateFlow()

    var failureNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFY, value).apply()
            publishAlerts()
        }

    var percentPerFiveHourWindow: Double
        get() = PacingPolicy.clampPercentPerWindow(
            prefs.getFloat(KEY_PACE_PCT, PacingPolicy.DEFAULT_PERCENT_PER_WINDOW.toFloat()).toDouble()
        )
        set(value) {
            prefs.edit().putFloat(KEY_PACE_PCT, PacingPolicy.clampPercentPerWindow(value).toFloat()).apply()
            publishAlerts()
        }

    var notifyUsageRestored: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_RESTORED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFY_RESTORED, value).apply()
            publishAlerts()
        }

    var notifyPacingReminders: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_PACE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFY_PACE, value).apply()
            publishAlerts()
        }

    var notifyPacingMissed: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_MISSED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFY_MISSED, value).apply()
            publishAlerts()
        }

    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUIET, false)
        set(value) {
            prefs.edit().putBoolean(KEY_QUIET, value).apply()
            publishAlerts()
        }

    var quietHoursStartHour: Int
        get() = prefs.getInt(KEY_QUIET_START, QuietHours.DEFAULT_START_HOUR)
        set(value) {
            prefs.edit().putInt(KEY_QUIET_START, value).apply()
            publishAlerts()
        }

    var quietHoursEndHour: Int
        get() = prefs.getInt(KEY_QUIET_END, QuietHours.DEFAULT_END_HOUR)
        set(value) {
            prefs.edit().putInt(KEY_QUIET_END, value).apply()
            publishAlerts()
        }

    var lastPeriodicRunEpochMs: Long
        get() = prefs.getLong(KEY_LAST_PERIODIC, 0L)
        private set(value) {
            prefs.edit().putLong(KEY_LAST_PERIODIC, value).apply()
        }

    var enqueuedPeriodicIntervalMs: Long
        get() = prefs.getLong(KEY_PERIODIC_INTERVAL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_PERIODIC_INTERVAL, value).apply()
        }

    fun lastNotifiedAt(sectionId: String): Long = prefs.getLong(notifyKey(sectionId), 0L)

    fun markNotified(sectionId: String, atEpochMs: Long) {
        prefs.edit().putLong(notifyKey(sectionId), atEpochMs).apply()
    }

    fun clearNotified(sectionId: String) {
        prefs.edit().remove(notifyKey(sectionId)).apply()
    }

    fun onPeriodicRun(nowMs: Long) {
        lastPeriodicRunEpochMs = nowMs
        setNextRefreshAt(nowMs + RefreshSchedule.INTERVAL_MS)
    }

    fun armNextRefreshFromNow(nowMs: Long) {
        setNextRefreshAt(nowMs + RefreshSchedule.INTERVAL_MS)
    }

    fun armNextRefreshIfUnarmed(nowMs: Long) {
        if (_nextRefreshAtEpochMs.value <= 0L) {
            armNextRefreshFromNow(nowMs)
        }
    }

    fun wantsNotificationPermission(): Boolean =
        failureNotificationsEnabled || notifyUsageRestored || notifyPacingReminders || notifyPacingMissed

    fun seenAlertKeys(): LinkedHashSet<String> {
        val raw = prefs.getString(KEY_SEEN_ALERTS, "") ?: ""
        if (raw.isBlank()) return LinkedHashSet()
        return raw.split('\n').filter { it.isNotBlank() }.toCollection(LinkedHashSet())
    }

    fun replaceSeenAlertKeys(keys: Set<String>) {
        prefs.edit().putString(KEY_SEEN_ALERTS, keys.joinToString("\n")).apply()
    }

    fun alertPreferencesValue(): AlertPreferences = _alertPreferences.value

    private fun readAlertPreferences(): AlertPreferences = AlertPreferences(
        percentPerWindow = percentPerFiveHourWindow,
        notifyStaleFailures = failureNotificationsEnabled,
        notifyUsageRestored = notifyUsageRestored,
        notifyPacingReminders = notifyPacingReminders,
        notifyPacingMissed = notifyPacingMissed,
        quietHoursEnabled = quietHoursEnabled,
        quietHoursStartHour = quietHoursStartHour,
        quietHoursEndHour = quietHoursEndHour
    )

    private fun publishAlerts() {
        _alertPreferences.value = readAlertPreferences()
    }

    private fun setNextRefreshAt(epochMs: Long) {
        prefs.edit().putLong(KEY_NEXT_REFRESH, epochMs).apply()
        _nextRefreshAtEpochMs.value = epochMs
    }

    private fun notifyKey(sectionId: String) = "notified.$sectionId"

    companion object {
        private const val PREFS = "usage_widget_settings"
        private const val KEY_NOTIFY = "failure_notifications"
        private const val KEY_LAST_PERIODIC = "last_periodic_run_epoch_ms"
        private const val KEY_NEXT_REFRESH = "next_refresh_epoch_ms"
        private const val KEY_PERIODIC_INTERVAL = "enqueued_periodic_interval_ms"
        private const val KEY_PACE_PCT = "percent_per_five_hour_window"
        private const val KEY_NOTIFY_RESTORED = "notify_usage_restored"
        private const val KEY_NOTIFY_PACE = "notify_pacing_reminders"
        private const val KEY_NOTIFY_MISSED = "notify_pacing_missed"
        private const val KEY_QUIET = "quiet_hours_enabled"
        private const val KEY_QUIET_START = "quiet_hours_start_hour"
        private const val KEY_QUIET_END = "quiet_hours_end_hour"
        private const val KEY_SEEN_ALERTS = "seen_alert_keys"

        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }
    }
}
