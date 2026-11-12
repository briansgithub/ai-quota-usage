package com.usagewidget.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.usagewidget.data.UsageRepository
import com.usagewidget.domain.AlertPreferences
import com.usagewidget.widget.WidgetPublisher
import com.usagewidget.domain.PacingPolicy
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.UsageSnapshot
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

    fun refresh() {
        viewModelScope.launch { repo.refresh(allowHtmlFallback = true) }
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
}
