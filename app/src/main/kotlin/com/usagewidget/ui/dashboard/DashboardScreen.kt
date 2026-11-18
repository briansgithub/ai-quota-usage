package com.usagewidget.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usagewidget.R
import com.usagewidget.domain.DisplayPreferences
import com.usagewidget.domain.NyTimeAgo
import com.usagewidget.domain.OnDemandDisplay
import com.usagewidget.domain.PaceWindows
import com.usagewidget.domain.PacingPlanner
import com.usagewidget.domain.PaceUrgency
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.UsedGradient
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.RefreshSchedule
import com.usagewidget.domain.ResetEmphasis
import com.usagewidget.domain.ResetUrgency
import com.usagewidget.domain.SoftwareSection
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.domain.StaleSeverity
import com.usagewidget.domain.UsageMeter
import com.usagewidget.domain.UsageSections
import com.usagewidget.domain.UsageSnapshot
import com.usagewidget.ui.theme.CursorBar
import com.usagewidget.ui.theme.CursorBarWarn
import com.usagewidget.ui.theme.CursorChrome
import com.usagewidget.ui.theme.CursorMuted
import com.usagewidget.ui.theme.CursorSurface
import com.usagewidget.ui.theme.CursorText
import com.usagewidget.ui.theme.CursorTrack
import com.usagewidget.ui.theme.ResetAmber
import com.usagewidget.ui.theme.ResetBright
import com.usagewidget.ui.theme.ResetImminent
import com.usagewidget.ui.theme.StaleAmber
import com.usagewidget.ui.theme.StaleAmberBg
import com.usagewidget.ui.theme.StaleRed
import com.usagewidget.ui.theme.StaleRedBg
import com.usagewidget.util.NyTime
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onSignIn: (ProviderId) -> Unit,
    onOpenDiagnostics: () -> Unit,
    openSettings: Boolean = false
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val nextRefreshAt by viewModel.nextRefreshAtEpochMs.collectAsStateWithLifecycle()
    val alerts by viewModel.alertPreferences.collectAsStateWithLifecycle()
    val display by viewModel.displayPreferences.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(openSettings) }

    if (settingsOpen) {
        OnDemandSettingsDialog(
            display = display,
            onCursor = viewModel::setShowCursorOnDemand,
            onGrok = viewModel::setShowGrokOnDemand,
            onDismiss = { settingsOpen = false }
        )
    }

    Scaffold(
        containerColor = CursorChrome,
        topBar = {
            TopAppBar(
                title = { Text("Usage") },
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Outlined.BugReport, contentDescription = "Diagnostics")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CursorChrome,
                    titleContentColor = CursorText,
                    actionIconContentColor = CursorText
                )
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            DashboardBody(
                snapshot = snapshot,
                nextRefreshAtEpochMs = nextRefreshAt,
                percentPerWindow = alerts.percentPerWindow,
                display = display,
                onSignIn = onSignIn,
                onSignOut = { viewModel.signOut(it) }
            )
        }
    }
}

@Composable
private fun OnDemandSettingsDialog(
    display: DisplayPreferences,
    onCursor: (Boolean) -> Unit,
    onGrok: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CursorSurface
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, color = CursorText)
                Text(stringResource(R.string.show_on_demand), color = CursorText, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Hides that dollar-cap bar. Included quota bars stay.",
                    color = CursorMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                OnDemandSwitch(stringResource(R.string.show_on_demand_cursor), display.showCursorOnDemand, onCursor)
                OnDemandSwitch(stringResource(R.string.show_on_demand_grok), display.showGrokOnDemand, onGrok)
                HorizontalDivider(color = CursorMuted.copy(alpha = 0.2f))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun OnDemandSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CursorText, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DashboardBody(
    snapshot: UsageSnapshot,
    nextRefreshAtEpochMs: Long,
    percentPerWindow: Double,
    display: DisplayPreferences,
    onSignIn: (ProviderId) -> Unit,
    onSignOut: (ProviderId) -> Unit
) {
    val now = System.currentTimeMillis()
    val sections = OnDemandDisplay.apply(
        UsageSections.from(snapshot, now),
        showCursor = display.showCursorOnDemand,
        showGrok = display.showGrokOnDemand
    )
    val anyStale = sections.any { it.stale.isStale }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (anyStale) {
            val worst = sections.filter { it.stale.isStale }
                .maxBy { if (it.stale.severity == StaleSeverity.RED) 1 else 0 }
            StaleBanner(
                text = "Stale data on ${worst.id.title}" +
                    (worst.stale.shortReason?.let { " · $it" } ?: ""),
                severity = worst.stale.severity
            )
        }
        Text(
            text = lastUpdatedLine(snapshot.fetchedAtEpochMs, now),
            style = MaterialTheme.typography.bodySmall,
            color = CursorMuted
        )
        NextRefreshLabel(nextRefreshAtEpochMs)
        Text(
            text = "Background refresh every 15 minutes.",
            style = MaterialTheme.typography.bodySmall,
            color = CursorMuted.copy(alpha = 0.55f)
        )
        sections.forEach { section ->
            Spacer(Modifier.height(16.dp))
            SectionCard(
                section = section,
                nowMs = now,
                percentPerWindow = percentPerWindow,
                onSignIn = { onSignIn(section.signInProvider) },
                onSignOut = { onSignOut(section.signInProvider) }
            )
        }
        Text(
            text = "Numbers come from each site’s own usage page using your sign-in cookie. Endpoints are undocumented and can break.",
            style = MaterialTheme.typography.bodySmall,
            color = CursorMuted
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StaleBanner(text: String, severity: StaleSeverity) {
    val tint = if (severity == StaleSeverity.RED) StaleRed else StaleAmber
    val bg = if (severity == StaleSeverity.RED) StaleRedBg else StaleAmberBg
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.Warning, contentDescription = null, tint = tint)
        Text(text, color = tint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionCard(
    section: SoftwareSection,
    nowMs: Long,
    percentPerWindow: Double,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    val stale = section.stale
    val headerColor = staleHeaderColor(stale.severity, stale.isStale)
    val cardBg = when {
        !stale.isStale -> CursorSurface
        stale.severity == StaleSeverity.RED -> StaleRedBg
        else -> StaleAmberBg
    }
    val hasData = section.meters.any { it.hasUsageBar || it.resetsAtEpochMs != null }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stale.isStale) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = "Stale",
                    tint = headerColor,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    section.id.title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp
                    ),
                    color = headerColor
                )
                if (!section.subtitle.isNullOrBlank() && !stale.isStale) {
                    Text(section.subtitle, style = MaterialTheme.typography.bodySmall, color = CursorMuted)
                }
                if (stale.isStale) {
                    val line = listOfNotNull(stale.lastUpdatedLine, stale.shortReason).joinToString(" · ")
                    Text(line, style = MaterialTheme.typography.bodySmall, color = headerColor)
                }
            }
        }

        if (hasData) {
            section.meters.forEach { MeterRow(it, nowMs = nowMs, stale = stale.isStale) }
        }
        section.manualResets?.displayLine()?.let { line ->
            Text(line, color = CursorText, style = MaterialTheme.typography.bodyMedium)
        }
        val pacing = PacingPlanner.forSection(section, nowMs, percentPerWindow)
        if (pacing != null) {
            Text(
                pacing.line,
                color = paceColor(pacing.urgency),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            )
            pacing.manuals.mapNotNull { it.warning }.forEach { warning ->
                Text(
                    warning,
                    color = ResetImminent,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        when (section.status) {
            ProviderStatus.UNSIGNED, ProviderStatus.NEEDS_SIGN_IN -> {
                if (!hasData) {
                    Text(
                        section.error ?: "Sign in on this phone. The app never sees your password.",
                        color = CursorMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Button(onClick = onSignIn, colors = ButtonDefaults.buttonColors(containerColor = CursorBar)) {
                    Text(
                        if (section.status == ProviderStatus.NEEDS_SIGN_IN) {
                            "Sign in again"
                        } else {
                            signInLabel(section)
                        }
                    )
                }
            }
            ProviderStatus.LOADING -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            ProviderStatus.ERROR -> {
                if (!hasData) {
                    Text(section.error ?: "Could not load usage.", color = CursorBarWarn)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSignIn) { Text("Sign in again") }
                    if (section.id != SoftwareSectionId.GROK_BOT) {
                        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
                    }
                }
            }
            ProviderStatus.OK -> {
                if (!section.error.isNullOrBlank() && !stale.isStale) {
                    Text(section.error, color = CursorBarWarn, style = MaterialTheme.typography.bodySmall)
                }
                if (section.id != SoftwareSectionId.GROK_BOT) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSignIn) { Text("Re-auth") }
                        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
                    }
                }
            }
        }
    }
}

@Composable
fun MeterRow(meter: UsageMeter, nowMs: Long = System.currentTimeMillis(), stale: Boolean = false) {
    val used = meter.usedLabel()
    val reset = meter.resetsAtEpochMs?.let { NyTime.formatResetLine(it, nowMs) }
        ?: meter.resetLabel
    val titleColor = if (stale) CursorMuted else CursorText
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                meter.title,
                color = titleColor,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            if (used != null) {
                Text(used, color = CursorText, style = MaterialTheme.typography.bodyMedium)
            } else if (!reset.isNullOrBlank() && !meter.hasUsageBar) {
                ResetText(reset, meter.resetsAtEpochMs, nowMs)
            }
        }
        if (meter.hasUsageBar) {
            UsageBar(meter = meter, nowMs = nowMs)
        }
        if (used != null && !reset.isNullOrBlank()) {
            ResetText(reset, meter.resetsAtEpochMs, nowMs)
        }
    }
}

@Composable
fun UsageBar(meter: UsageMeter, nowMs: Long) {
    val used = (meter.usedPercent ?: meter.displayUsedPercent?.toDouble() ?: 0.0).coerceIn(0.0, 100.0)
    val expected = if (meter.isCapBar) null else PaceWindows.expectedUsedPercent(meter, nowMs)
    val fill = if (meter.isCapBar) UsedGradient.capFill(used) else UsedGradient.fill(used, expected)
    val fillColor = Color(red = fill.r / 255f, green = fill.g / 255f, blue = fill.b / 255f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(CursorTrack)
    ) {
        if (used > 0.0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((used / 100.0).toFloat())
                    .fillMaxHeight()
                    .background(fillColor)
            )
        }
        if (!meter.isCapBar && expected != null) {
            val tickX = maxWidth * (expected.coerceIn(0.0, 100.0) / 100.0).toFloat()
            Box(
                modifier = Modifier
                    .offset(x = tickX - 1.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color(0xFFF5F5F5))
            )
        }
    }
}

@Composable
private fun ResetText(reset: String, resetAtEpochMs: Long?, nowMs: Long) {
    val urgency = ResetEmphasis.of(resetAtEpochMs, nowMs)
    Text(
        reset,
        color = resetColor(urgency),
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = resetWeight(urgency),
            fontSize = resetSize(urgency)
        )
    )
}

private fun resetColor(urgency: ResetUrgency): Color = when (urgency) {
    ResetUrgency.RELAXED -> CursorText
    ResetUrgency.SOON -> ResetBright
    ResetUrgency.NEAR -> ResetAmber
    ResetUrgency.IMMINENT -> ResetImminent
}

private fun resetWeight(urgency: ResetUrgency): FontWeight = when (urgency) {
    ResetUrgency.RELAXED -> FontWeight.Medium
    ResetUrgency.SOON -> FontWeight.SemiBold
    ResetUrgency.NEAR, ResetUrgency.IMMINENT -> FontWeight.Bold
}

private fun resetSize(urgency: ResetUrgency): TextUnit = when (urgency) {
    ResetUrgency.RELAXED -> 13.sp
    ResetUrgency.SOON, ResetUrgency.NEAR, ResetUrgency.IMMINENT -> 14.sp
}

private fun paceColor(urgency: PaceUrgency): Color = when (urgency) {
    PaceUrgency.OK -> CursorText
    PaceUrgency.TIGHT -> ResetAmber
    PaceUrgency.CRITICAL, PaceUrgency.IMPOSSIBLE -> ResetImminent
}

private fun staleHeaderColor(severity: StaleSeverity, isStale: Boolean): Color {
    if (!isStale) return CursorText
    return if (severity == StaleSeverity.RED) StaleRed else StaleAmber
}

private fun signInLabel(section: SoftwareSection): String = when (section.id) {
    SoftwareSectionId.GROK_BOT -> "Sign in to Cursor"
    SoftwareSectionId.CODEX -> "Sign in to ChatGPT"
    SoftwareSectionId.CURSOR -> "Sign in"
}

private fun lastUpdatedLine(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0L) return "Not updated yet"
    return "Updated ${NyTimeAgo.format(epochMs, nowMs)} · ${NyTime.formatAbsolute(epochMs)}"
}

@Composable
private fun NextRefreshLabel(nextEpochMs: Long) {
    if (nextEpochMs <= 0L) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nextEpochMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Text(
        text = RefreshSchedule.formatCountdown(RefreshSchedule.remainingMs(nextEpochMs, now)),
        style = MaterialTheme.typography.bodySmall,
        color = CursorMuted.copy(alpha = 0.45f)
    )
}
