package com.usagewidget.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.usagewidget.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usagewidget.BuildConfig
import com.usagewidget.domain.DiagnosticsReport
import com.usagewidget.domain.FetchAttempt
import com.usagewidget.domain.HealthSynthesis
import com.usagewidget.ui.settings.DisplaySettings
import com.usagewidget.domain.QuotaRuleId
import com.usagewidget.domain.SectionHealth
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.ui.dashboard.DashboardViewModel
import com.usagewidget.ui.theme.CursorBar
import com.usagewidget.ui.theme.CursorChrome
import com.usagewidget.ui.theme.CursorMuted
import com.usagewidget.ui.theme.CursorSurface
import com.usagewidget.ui.theme.CursorText
import com.usagewidget.ui.theme.StaleAmber
import com.usagewidget.ui.theme.StaleRed
import com.usagewidget.util.NyTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DashboardViewModel,
    onBack: () -> Unit,
    onNotificationsToggled: (Boolean) -> Unit
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val alerts by viewModel.alertPreferences.collectAsStateWithLifecycle()
    val quota by viewModel.quotaPreferences.collectAsStateWithLifecycle()
    val display by viewModel.displayPreferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hydrated = HealthSynthesis.hydrate(snapshot)

    Scaffold(
        containerColor = CursorChrome,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CursorChrome,
                    titleContentColor = CursorText,
                    navigationIconContentColor = CursorText
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CursorSurface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Settings", style = MaterialTheme.typography.titleMedium, color = CursorText)
                DisplaySettings(
                    display = display,
                    onShowCursorOnDemand = viewModel::setShowCursorOnDemand,
                    onShowGrokOnDemand = viewModel::setShowGrokOnDemand,
                    onOnlyUsageRemaining = viewModel::setOnlyUsageRemaining,
                    onWidgetBar = viewModel::setWidgetBarVisible
                )
                HorizontalDivider(color = CursorMuted.copy(alpha = 0.2f))
                Text(
                    "Background refresh every 15 minutes (WorkManager periodic minimum).",
                    color = CursorMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                SettingSwitch(
                    label = stringResource(R.string.failure_notifications),
                    checked = alerts.notifyStaleFailures,
                    onCheckedChange = { enabled ->
                        viewModel.setFailureNotificationsEnabled(enabled)
                        onNotificationsToggled(viewModel.wantsNotificationPermission())
                    }
                )
                SettingSwitch(
                    label = stringResource(R.string.notify_usage_restored),
                    checked = alerts.notifyUsageRestored,
                    onCheckedChange = { enabled ->
                        viewModel.setNotifyUsageRestored(enabled)
                        onNotificationsToggled(viewModel.wantsNotificationPermission())
                    }
                )
                SettingSwitch(
                    label = stringResource(R.string.notify_pacing_reminders),
                    checked = alerts.notifyPacingReminders,
                    onCheckedChange = { enabled ->
                        viewModel.setNotifyPacingReminders(enabled)
                        onNotificationsToggled(viewModel.wantsNotificationPermission())
                    }
                )
                SettingSwitch(
                    label = stringResource(R.string.notify_pacing_missed),
                    checked = alerts.notifyPacingMissed,
                    onCheckedChange = { enabled ->
                        viewModel.setNotifyPacingMissed(enabled)
                        onNotificationsToggled(viewModel.wantsNotificationPermission())
                    }
                )
                SettingSwitch(
                    label = stringResource(R.string.quiet_hours),
                    checked = alerts.quietHoursEnabled,
                    onCheckedChange = { viewModel.setQuietHoursEnabled(it) }
                )
                HorizontalDivider(color = CursorMuted.copy(alpha = 0.2f))
                Text("Quota remaining", style = MaterialTheme.typography.titleSmall, color = CursorText)
                Text(
                    "Notify when remaining quota crosses a step downward. One notice per step per billing window; several steps between refreshes send the lowest. Agents are never paused.",
                    color = CursorMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                SettingSwitch(
                    label = stringResource(R.string.quota_alerts),
                    checked = quota.masterEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setQuotaAlertsEnabled(enabled)
                        onNotificationsToggled(viewModel.wantsNotificationPermission())
                    }
                )
                SettingSwitch(
                    label = stringResource(R.string.quota_grok_weekly),
                    checked = QuotaRuleId.GROK_WEEKLY.wire in quota.enabledRules,
                    onCheckedChange = { viewModel.setQuotaGrokWeeklyEnabled(it) }
                )
                SettingSwitch(
                    label = stringResource(R.string.quota_cursor_models),
                    checked = QuotaRuleId.CURSOR_MODELS.wire in quota.enabledRules,
                    onCheckedChange = { viewModel.setQuotaCursorModelsEnabled(it) }
                )
                SettingSwitch(
                    label = stringResource(R.string.quota_codex_five),
                    checked = QuotaRuleId.CODEX_FIVE_HOUR.wire in quota.enabledRules,
                    onCheckedChange = { viewModel.setQuotaCodexFiveHourEnabled(it) }
                )
                SettingSwitch(
                    label = stringResource(R.string.quota_codex_weekly),
                    checked = QuotaRuleId.CODEX_WEEKLY.wire in quota.enabledRules,
                    onCheckedChange = { viewModel.setQuotaCodexWeeklyEnabled(it) }
                )
                SettingSwitch(
                    label = stringResource(R.string.quota_claude_five),
                    checked = QuotaRuleId.CLAUDE_FIVE_HOUR.wire in quota.enabledRules,
                    onCheckedChange = { viewModel.setQuotaClaudeFiveHourEnabled(it) }
                )
                SettingSwitch(
                    label = stringResource(R.string.quota_claude_weekly),
                    checked = QuotaRuleId.CLAUDE_WEEKLY.wire in quota.enabledRules,
                    onCheckedChange = { viewModel.setQuotaClaudeWeeklyEnabled(it) }
                )
                var thresholdsCsv by rememberSaveable(quota.thresholdsCsv) { mutableStateOf(quota.thresholdsCsv) }
                OutlinedTextField(
                    value = thresholdsCsv,
                    onValueChange = { thresholdsCsv = it },
                    label = { Text(stringResource(R.string.quota_thresholds)) },
                    supportingText = {
                        Text("Comma-separated remaining percents. Default every 10% from 90 to 30, then every 5% to 0.")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            if (!focus.isFocused) viewModel.setQuotaThresholdsCsv(thresholdsCsv)
                        },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CursorText,
                        unfocusedTextColor = CursorText,
                        focusedBorderColor = CursorBar,
                        unfocusedBorderColor = CursorMuted,
                        focusedLabelColor = CursorText,
                        unfocusedLabelColor = CursorMuted,
                        focusedSupportingTextColor = CursorMuted,
                        unfocusedSupportingTextColor = CursorMuted,
                        cursorColor = CursorBar
                    )
                )
                Button(
                    onClick = {
                        onNotificationsToggled(true)
                        viewModel.sendTestNotification()
                        Toast.makeText(context, "Test notification sent", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CursorBar),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.send_test_notification))
                }
                Text(
                    stringResource(R.string.percent_per_window),
                    color = CursorText,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.setPercentPerWindow(alerts.percentPerWindow - 1.0) }) {
                        Text("−", color = CursorText)
                    }
                    Text(
                        "${alerts.percentPerWindow.toInt()}%",
                        color = CursorText,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    TextButton(onClick = { viewModel.setPercentPerWindow(alerts.percentPerWindow + 1.0) }) {
                        Text("+", color = CursorText)
                    }
                }
                Text(
                    "Default 12%. Codex pacing counts how many 5-hour windows you still need from remaining weekly %. Quiet hours skip usage-restored and pacing alerts only.",
                    color = CursorMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = {
                    val text = DiagnosticsReport.format(hydrated, BuildConfig.VERSION_NAME)
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Usage Widget diagnostics", text))
                    Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CursorBar),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Copy diagnostics")
            }
            Text(
                "The copy does not include cookies or tokens. Send it when a parser or endpoint breaks.",
                color = CursorMuted,
                style = MaterialTheme.typography.bodySmall
            )

            SoftwareSectionId.entries.forEach { section ->
                HealthCard(section.title, hydrated.health(section))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CursorSurface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Recent attempts", style = MaterialTheme.typography.titleMedium, color = CursorText)
                if (hydrated.attemptLog.isEmpty()) {
                    Text("No attempts yet.", color = CursorMuted)
                } else {
                    hydrated.attemptLog.asReversed().forEach { attempt ->
                        AttemptRow(attempt)
                        HorizontalDivider(color = CursorMuted.copy(alpha = 0.2f))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = CursorText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HealthCard(title: String, health: SectionHealth) {
    val stale = health.staleView(System.currentTimeMillis())
    val tint = when {
        !stale.isStale -> CursorText
        stale.severity.name == "RED" -> StaleRed
        else -> StaleAmber
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CursorSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
        Line("Last attempt", formatTs(health.lastAttemptEpochMs))
        Line("Last success", formatTs(health.lastSuccessEpochMs))
        Line("Outcome", health.lastOutcome)
        Line("Reason", health.shortReason ?: "—")
        Line("HTTP status", health.httpStatus?.toString() ?: "—")
        Line("Endpoint", health.endpoint ?: "—")
        Line("Source", health.source ?: "—")
        Line("Message", health.message ?: "—")
        Line("Manual resets", health.lastGoodManualResets?.displayLine() ?: "hidden (none present)")
        health.lastGoodDiagnosticJson?.let { json ->
            Text("Observed JSON: $json", color = CursorMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AttemptRow(attempt: FetchAttempt) {
    val title = runCatching { SoftwareSectionId.fromWire(attempt.sectionId).title }
        .getOrDefault(attempt.sectionId)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            "$title · ${attempt.outcome}${attempt.httpStatus?.let { " · HTTP $it" } ?: ""}",
            color = CursorText,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(formatTs(attempt.atEpochMs), color = CursorMuted, style = MaterialTheme.typography.bodySmall)
        attempt.endpoint?.let {
            Text(it, color = CursorMuted, style = MaterialTheme.typography.bodySmall)
        }
        attempt.message?.let {
            Text(it, color = CursorMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Text("$label: $value", color = CursorMuted, style = MaterialTheme.typography.bodySmall)
}

private fun formatTs(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    return NyTime.formatAbsolute(epochMs)
}
