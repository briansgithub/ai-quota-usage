package com.usagewidget.data

import android.content.Context
import com.usagewidget.domain.AlertPreferences
import com.usagewidget.domain.DisplayPreferences
import com.usagewidget.domain.PacingPolicy
import com.usagewidget.domain.QuietHours
import com.usagewidget.domain.QuotaRuleId
import com.usagewidget.domain.QuotaThresholdPreferences
import com.usagewidget.domain.QuotaThresholdState
import com.usagewidget.domain.QuotaThresholds
import com.usagewidget.domain.RefreshSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppSettings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _nextRefreshAtEpochMs = MutableStateFlow(prefs.getLong(KEY_NEXT_REFRESH, 0L))
    val nextRefreshAtEpochMs: StateFlow<Long> = _nextRefreshAtEpochMs
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _alertPreferences = MutableStateFlow(readAlertPreferences())
    val alertPreferences: StateFlow<AlertPreferences> = _alertPreferences.asStateFlow()
    private val _quotaPreferences = MutableStateFlow(readQuotaPreferences())
    val quotaPreferences: StateFlow<QuotaThresholdPreferences> = _quotaPreferences.asStateFlow()
    private val _displayPreferences = MutableStateFlow(readDisplayPreferences())
    val displayPreferences: StateFlow<DisplayPreferences> = _displayPreferences.asStateFlow()

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

    var quotaAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUOTA_MASTER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_QUOTA_MASTER, value).apply()
            publishQuota()
        }

    var quotaThresholdsCsv: String
        get() = prefs.getString(KEY_QUOTA_THRESHOLDS, null) ?: QuotaThresholds.DEFAULT_CSV
        set(value) {
            prefs.edit().putString(KEY_QUOTA_THRESHOLDS, QuotaThresholds.toCsv(QuotaThresholds.parseCsv(value))).apply()
            publishQuota()
        }

    var quotaGrokWeeklyEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUOTA_GROK, QuotaRuleId.GROK_WEEKLY.defaultEnabled)
        set(value) {
            prefs.edit().putBoolean(KEY_QUOTA_GROK, value).apply()
            publishQuota()
        }

    var quotaCursorModelsEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUOTA_MODELS, QuotaRuleId.CURSOR_MODELS.defaultEnabled)
        set(value) {
            prefs.edit().putBoolean(KEY_QUOTA_MODELS, value).apply()
            publishQuota()
        }

    var quotaCodexFiveHourEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUOTA_CODEX_FIVE, QuotaRuleId.CODEX_FIVE_HOUR.defaultEnabled)
        set(value) {
            prefs.edit().putBoolean(KEY_QUOTA_CODEX_FIVE, value).apply()
            publishQuota()
        }

    var quotaCodexWeeklyEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUOTA_CODEX_WEEKLY, QuotaRuleId.CODEX_WEEKLY.defaultEnabled)
        set(value) {
            prefs.edit().putBoolean(KEY_QUOTA_CODEX_WEEKLY, value).apply()
            publishQuota()
        }

    var quotaClaudeFiveHourEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUOTA_CLAUDE_FIVE, QuotaRuleId.CLAUDE_FIVE_HOUR.defaultEnabled)
        set(value) {
            prefs.edit().putBoolean(KEY_QUOTA_CLAUDE_FIVE, value).apply()
            publishQuota()
        }

    var quotaGeminiEnabled: Boolean
        get() = prefs.getBoolean("quota_gemini_enabled", false)
        set(value) { prefs.edit().putBoolean("quota_gemini_enabled", value).apply(); publishQuota() }

    var quotaClaudeWeeklyEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUOTA_CLAUDE_WEEKLY, QuotaRuleId.CLAUDE_WEEKLY.defaultEnabled)
        set(value) {
            prefs.edit().putBoolean(KEY_QUOTA_CLAUDE_WEEKLY, value).apply()
            publishQuota()
        }

    var showCursorOnDemand: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CURSOR_ON_DEMAND, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_CURSOR_ON_DEMAND, value).apply()
            publishDisplay()
        }

    var showGrokOnDemand: Boolean
        get() = prefs.getBoolean(KEY_SHOW_GROK_ON_DEMAND, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_GROK_ON_DEMAND, value).apply()
            publishDisplay()
        }

    /** Hide bars whose weekly/monthly quota is spent (see [com.usagewidget.domain.UsageRemaining]). */
    var onlyUsageRemaining: Boolean
        get() = prefs.getBoolean(KEY_ONLY_USAGE_REMAINING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONLY_USAGE_REMAINING, value).apply()
            publishDisplay()
        }

    val hiddenWidgetBars: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_WIDGET_BARS, emptySet())?.toSet().orEmpty()

    fun setWidgetBarVisible(meterId: String, visible: Boolean) {
        val current = hiddenWidgetBars
        val next = if (visible) current - meterId else current + meterId
        if (next == current) return
        prefs.edit().putStringSet(KEY_HIDDEN_WIDGET_BARS, next).apply()
        publishDisplay()
    }

    fun wantsNotificationPermission(): Boolean =
        failureNotificationsEnabled || notifyUsageRestored || notifyPacingReminders ||
            notifyPacingMissed || quotaAlertsEnabled

    fun seenAlertKeys(): LinkedHashSet<String> {
        val raw = prefs.getString(KEY_SEEN_ALERTS, "") ?: ""
        if (raw.isBlank()) return LinkedHashSet()
        return raw.split('\n').filter { it.isNotBlank() }.toCollection(LinkedHashSet())
    }

    fun replaceSeenAlertKeys(keys: Set<String>) {
        prefs.edit().putString(KEY_SEEN_ALERTS, keys.joinToString("\n")).apply()
    }

    fun quotaFiredStates(): Map<String, QuotaThresholdState> {
        val raw = prefs.getString(KEY_QUOTA_STATES, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, QuotaThresholdState>>(raw)
        }.getOrDefault(emptyMap())
    }

    fun replaceQuotaFiredStates(states: Map<String, QuotaThresholdState>) {
        prefs.edit().putString(KEY_QUOTA_STATES, json.encodeToString(states)).apply()
    }

    fun quotaPreferencesValue(): QuotaThresholdPreferences = _quotaPreferences.value

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

    private fun readQuotaPreferences(): QuotaThresholdPreferences {
        val enabled = buildSet {
            if (quotaGrokWeeklyEnabled) add(QuotaRuleId.GROK_WEEKLY.wire)
            if (quotaCursorModelsEnabled) add(QuotaRuleId.CURSOR_MODELS.wire)
            if (quotaCodexFiveHourEnabled) add(QuotaRuleId.CODEX_FIVE_HOUR.wire)
            if (quotaCodexWeeklyEnabled) add(QuotaRuleId.CODEX_WEEKLY.wire)
            if (quotaClaudeFiveHourEnabled) add(QuotaRuleId.CLAUDE_FIVE_HOUR.wire)
            if (quotaClaudeWeeklyEnabled) add(QuotaRuleId.CLAUDE_WEEKLY.wire)
            if (quotaGeminiEnabled) addAll(QuotaRuleId.entries.filter { it.section == com.usagewidget.domain.SoftwareSectionId.GEMINI }.map { it.wire })
        }
        return QuotaThresholdPreferences(
            masterEnabled = quotaAlertsEnabled,
            thresholds = QuotaThresholds.parseCsv(quotaThresholdsCsv),
            enabledRules = enabled
        )
    }

    private fun publishAlerts() {
        _alertPreferences.value = readAlertPreferences()
    }

    private fun publishQuota() {
        _quotaPreferences.value = readQuotaPreferences()
    }

    private fun readDisplayPreferences(): DisplayPreferences = DisplayPreferences(
        showCursorOnDemand = showCursorOnDemand,
        showGrokOnDemand = showGrokOnDemand,
        hiddenWidgetBars = hiddenWidgetBars,
        onlyUsageRemaining = onlyUsageRemaining
    )

    private fun publishDisplay() {
        _displayPreferences.value = readDisplayPreferences()
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
        private const val KEY_QUOTA_MASTER = "quota_alerts_enabled"
        private const val KEY_QUOTA_THRESHOLDS = "quota_thresholds_csv"
        private const val KEY_QUOTA_GROK = "quota_grok_weekly_enabled"
        private const val KEY_QUOTA_MODELS = "quota_cursor_models_enabled"
        private const val KEY_QUOTA_CODEX_FIVE = "quota_codex_five_hour_enabled"
        private const val KEY_QUOTA_CODEX_WEEKLY = "quota_codex_weekly_enabled"
        private const val KEY_HIDDEN_WIDGET_BARS = "hidden_widget_bars"
        private const val KEY_ONLY_USAGE_REMAINING = "only_usage_remaining"
        private const val KEY_QUOTA_CLAUDE_FIVE = "quota_claude_five_hour_enabled"
        private const val KEY_QUOTA_CLAUDE_WEEKLY = "quota_claude_weekly_enabled"
        private const val KEY_QUOTA_STATES = "quota_fired_states"
        private const val KEY_SHOW_CURSOR_ON_DEMAND = "show_cursor_on_demand"
        private const val KEY_SHOW_GROK_ON_DEMAND = "show_grok_on_demand"

        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }
    }
}
