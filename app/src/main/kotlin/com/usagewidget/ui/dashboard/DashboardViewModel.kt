package com.usagewidget.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.usagewidget.data.UsageRepository
import com.usagewidget.domain.AlertPreferences
import com.usagewidget.domain.DisplayPreferences
import com.usagewidget.widget.WidgetPublisher
import com.usagewidget.domain.PacingPolicy
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.QuotaThresholdPreferences
import com.usagewidget.domain.UsageSnapshot
import com.usagewidget.refresh.UsageAlertNotifier
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = UsageRepository.get(application)

    val snapshot: StateFlow<UsageSnapshot> = repo.snapshot.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        repo.snapshot.value
    )

    val refreshing: StateFlow<Boolean> = repo.refreshing

    val nextRefreshAtEpochMs: StateFlow<Long> = repo.settings().nextRefreshAtEpochMs

    val alertPreferences: StateFlow<AlertPreferences> = repo.settings().alertPreferences

    val quotaPreferences: StateFlow<QuotaThresholdPreferences> = repo.settings().quotaPreferences

    val displayPreferences: StateFlow<DisplayPreferences> = repo.settings().displayPreferences

    fun refresh() {
        viewModelScope.launch { repo.refresh(allowHtmlFallback = false) }
    }

    fun signOut(provider: ProviderId) {
        repo.signOut(provider)
    }

    fun hasSession(provider: ProviderId): Boolean = repo.hasSession(provider)

    fun failureNotificationsEnabled(): Boolean = repo.settings().failureNotificationsEnabled

    fun setFailureNotificationsEnabled(enabled: Boolean) {
        repo.settings().failureNotificationsEnabled = enabled
        repo.syncNotifications()
    }

    fun wantsNotificationPermission(): Boolean = repo.settings().wantsNotificationPermission()

    fun setNotifyUsageRestored(enabled: Boolean) {
        repo.settings().notifyUsageRestored = enabled
    }

    fun setNotifyPacingReminders(enabled: Boolean) {
        repo.settings().notifyPacingReminders = enabled
    }

    fun setNotifyPacingMissed(enabled: Boolean) {
        repo.settings().notifyPacingMissed = enabled
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        repo.settings().quietHoursEnabled = enabled
    }

    fun setPercentPerWindow(value: Double) {
        repo.settings().percentPerFiveHourWindow = PacingPolicy.clampPercentPerWindow(value)
        viewModelScope.launch {
            runCatching { WidgetPublisher.push(getApplication()) }
        }
    }

    fun setQuotaAlertsEnabled(enabled: Boolean) {
        repo.settings().quotaAlertsEnabled = enabled
    }

    fun setQuotaGrokWeeklyEnabled(enabled: Boolean) {
        repo.settings().quotaGrokWeeklyEnabled = enabled
    }

    fun setQuotaCursorModelsEnabled(enabled: Boolean) {
        repo.settings().quotaCursorModelsEnabled = enabled
    }

    fun setQuotaCodexFiveHourEnabled(enabled: Boolean) {
        repo.settings().quotaCodexFiveHourEnabled = enabled
    }

    fun setQuotaCodexWeeklyEnabled(enabled: Boolean) {
        repo.settings().quotaCodexWeeklyEnabled = enabled
    }

    fun setQuotaThresholdsCsv(csv: String) {
        repo.settings().quotaThresholdsCsv = csv
    }

    fun setShowCursorOnDemand(enabled: Boolean) {
        repo.settings().showCursorOnDemand = enabled
        pushWidget()
    }

    fun setShowGrokOnDemand(enabled: Boolean) {
        repo.settings().showGrokOnDemand = enabled
        pushWidget()
    }

    private fun pushWidget() {
        viewModelScope.launch {
            runCatching { WidgetPublisher.push(getApplication()) }
        }
    }

    fun sendTestNotification() {
        UsageAlertNotifier(getApplication()).postTest()
    }
}
