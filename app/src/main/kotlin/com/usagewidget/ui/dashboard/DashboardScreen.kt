package com.usagewidget.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usagewidget.domain.NyTimeAgo
import com.usagewidget.domain.PaceWindows
import com.usagewidget.domain.PacingPolicy
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.RefreshSchedule
import com.usagewidget.domain.ResetEmphasis
import com.usagewidget.domain.ResetUrgency
import com.usagewidget.domain.SoftwareSection
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.domain.StaleSeverity
import com.usagewidget.domain.UsageSections
import com.usagewidget.domain.UsageSnapshot
import com.usagewidget.ui.settings.DisplaySettings
import com.usagewidget.ui.theme.CursorBar
import com.usagewidget.ui.theme.CursorChrome
import com.usagewidget.ui.theme.CursorMuted
import com.usagewidget.ui.theme.CursorSurface
import com.usagewidget.ui.theme.CursorText
import com.usagewidget.ui.theme.StaleAmber
import com.usagewidget.ui.theme.StaleAmberBg
import com.usagewidget.ui.theme.StaleRed
import com.usagewidget.ui.theme.StaleRedBg
import com.usagewidget.util.NyTime
import com.usagewidget.widget.PaceBarBitmap
import com.usagewidget.widget.WidgetRow
import com.usagewidget.widget.widgetRowsFor
import kotlinx.coroutines.delay

/**
 * One page: widget settings first, then a live preview of the home-screen widget built from
 * the same rows ([widgetRowsFor]) and bar renderer ([PaceBarBitmap]), so every visibility,
 * order, usage-left and group-by-limit setting shows here exactly as on the widget.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onSignIn: (ProviderId) -> Unit,
    onOpenDiagnostics: () -> Unit,
    settingsOpen: Boolean,
    onSettingsChange: (Boolean) -> Unit
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val nextRefreshAt by viewModel.nextRefreshAtEpochMs.collectAsStateWithLifecycle()
    val alerts by viewModel.alertPreferences.collectAsStateWithLifecycle()
    val display by viewModel.displayPreferences.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    // The widget's gear opens the app with settingsOpen: settings are at the top of the page.
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) {
            scroll.scrollTo(0)
            onSettingsChange(false)
        }
    }

    Scaffold(
        containerColor = CursorChrome,
        topBar = {
            TopAppBar(
                title = { Text("Usage") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Outlined.NotificationsNone, contentDescription = "Alerts and diagnostics")
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
            val now = System.currentTimeMillis()
            val sections = UsageSections.from(snapshot, now)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card {
                    Text("Widget settings", style = MaterialTheme.typography.titleLarge, color = CursorText)
                    DisplaySettings(
                        display = display,
                        onShowCursorOnDemand = viewModel::setShowCursorOnDemand,
                        onShowGrokOnDemand = viewModel::setShowGrokOnDemand,
                        onOnlyUsageRemaining = viewModel::setOnlyUsageRemaining,
                        onKeepResetTimes = viewModel::setKeepResetTimes,
                        onGroupByLimit = viewModel::setGroupByLimit,
                        onWidgetBar = viewModel::setWidgetBarVisible,
                        onMoveWidgetSection = viewModel::moveWidgetSection
                    )
                    Accounts(sections, onSignIn = onSignIn, onSignOut = { viewModel.signOut(it) })
                    TextButton(onClick = onOpenDiagnostics) { Text("Alerts and diagnostics") }
                }
                StatusLines(snapshot, sections, nextRefreshAt, now)
                Text(
                    "WIDGET PREVIEW",
                    color = CursorBar,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
                WidgetPreview(
                    rows = widgetRowsFor(snapshot, display, alerts.paceRates(), now),
                    nowMs = now,
                    onSignIn = { wire -> ProviderId.entries.firstOrNull { it.wireName == wire }?.let(onSignIn) }
                )
                Text(
                    text = "Numbers come from each site’s own usage page using your sign-in cookie. Endpoints are undocumented and can break.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CursorMuted
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CursorSurface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) { content() }
}

/** One row per sign-in (Grok Bot shares Cursor's), with the buttons the old cards had. */
@Composable
private fun Accounts(
    sections: List<SoftwareSection>,
    onSignIn: (ProviderId) -> Unit,
    onSignOut: (ProviderId) -> Unit
) {
    val byProvider = sections.filter { it.id != SoftwareSectionId.GROK_BOT }.associateBy { it.signInProvider }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "ACCOUNTS",
            color = CursorBar,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
        byProvider.forEach { (provider, section) ->
            val signedIn = section.status != ProviderStatus.UNSIGNED && section.status != ProviderStatus.NEEDS_SIGN_IN
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(accountName(provider), color = CursorText, style = MaterialTheme.typography.titleSmall)
                    Text(
                        accountStatus(section),
                        color = if (section.status == ProviderStatus.OK) CursorMuted else StaleAmber,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
                OutlinedButton(onClick = { onSignIn(provider) }, modifier = Modifier.heightIn(min = 36.dp)) {
                    Text(if (signedIn) "Re-auth" else "Sign in")
                }
                if (signedIn) {
                    OutlinedButton(onClick = { onSignOut(provider) }, modifier = Modifier.heightIn(min = 36.dp)) {
                        Text("Sign out")
                    }
                }
            }
        }
    }
}

private fun accountName(provider: ProviderId): String = when (provider) {
    ProviderId.CURSOR -> "Cursor + Grok Bot"
    ProviderId.OPENAI -> "Codex (ChatGPT)"
    ProviderId.CLAUDE -> "Claude"
    ProviderId.GEMINI -> "Gemini (Antigravity)"
}

private fun accountStatus(section: SoftwareSection): String = when (section.status) {
    ProviderStatus.OK -> listOfNotNull("Signed in", section.subtitle).joinToString(" · ")
    ProviderStatus.UNSIGNED -> "Not signed in"
    ProviderStatus.NEEDS_SIGN_IN -> "Sign-in expired"
    ProviderStatus.LOADING -> "Loading…"
    ProviderStatus.ERROR -> section.error ?: "Could not load usage"
}

@Composable
private fun StatusLines(snapshot: UsageSnapshot, sections: List<SoftwareSection>, nextRefreshAt: Long, now: Long) {
    val stale = sections.filter { it.stale.isStale }
    if (stale.isNotEmpty()) {
        val worst = stale.maxBy { if (it.stale.severity == StaleSeverity.RED) 1 else 0 }
        val red = worst.stale.severity == StaleSeverity.RED
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (red) StaleRedBg else StaleAmberBg).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val tint = if (red) StaleRed else StaleAmber
            Icon(Icons.Outlined.Warning, contentDescription = null, tint = tint)
            Text(
                "Stale data on ${worst.id.title}" + (worst.stale.shortReason?.let { " · $it" } ?: ""),
                color = tint,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            if (snapshot.fetchedAtEpochMs <= 0L) "Not updated yet"
            else "Updated ${NyTimeAgo.format(snapshot.fetchedAtEpochMs, now)} · ${NyTime.formatAbsolute(snapshot.fetchedAtEpochMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = CursorMuted
        )
        NextRefreshLabel(nextRefreshAt)
    }
}

private val WidgetChrome = Color(0xFF141414)
private val WidgetSurface = Color(0xFF1A1A1A)
private val WidgetText = Color(0xFFE8E8E8)
private val WidgetMuted = Color(0xFF9A9A9A)
private val WidgetWarn = Color(0xFFE08B6E)
private val WidgetAmber = Color(0xFFE6B84C)
private val WidgetOrange = Color(0xFFFF8A65)
private val WidgetBright = Color(0xFFF5F5F5)

/** Compose mirror of the Glance widget's row rendering in [com.usagewidget.widget.UsageGlanceWidget]. */
@Composable
private fun WidgetPreview(rows: List<WidgetRow>, nowMs: Long, onSignIn: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(WidgetChrome).padding(12.dp)
    ) {
        Text("Quota used", color = WidgetMuted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        rows.forEach { row ->
            when (row) {
                is WidgetRow.Heading -> Text(
                    row.title,
                    modifier = Modifier.padding(top = if (row.software) 1.dp else 4.dp),
                    color = WidgetBright,
                    fontSize = if (row.software) 12.sp else 15.sp,
                    fontWeight = FontWeight.Bold
                )
                is WidgetRow.Meter -> PreviewMeter(row, nowMs)
                is WidgetRow.Reset -> PreviewReset(row, nowMs)
                is WidgetRow.Manual -> Text(
                    row.text,
                    modifier = Modifier.padding(start = if (row.indented) 8.dp else 0.dp),
                    color = WidgetMuted,
                    fontSize = 11.sp
                )
                is WidgetRow.SignIn -> Text(
                    "${row.title} · Sign in",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WidgetSurface)
                        .clickable { onSignIn(row.providerId) }.padding(8.dp),
                    color = WidgetWarn,
                    fontSize = 12.sp
                )
                is WidgetRow.Message -> Text(row.text, color = WidgetMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(if (row is WidgetRow.Meter) 3.dp else 5.dp))
        }
    }
}

@Composable
private fun PreviewMeter(row: WidgetRow.Meter, nowMs: Long) {
    Column(Modifier.fillMaxWidth().padding(start = if (row.indented) 8.dp else 0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, color = WidgetText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            row.slackMs?.let { slack ->
                val label = if (slack < 0) "short" else "slack"
                val color = when {
                    slack <= PacingPolicy.CRITICAL_SLACK_MS -> WidgetOrange
                    slack <= PacingPolicy.TIGHT_SLACK_MS -> WidgetAmber
                    else -> WidgetMuted
                }
                Text("${NyTime.formatDuration(slack)} $label", color = color, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
            }
            Text(
                (if (row.stale) "~" else "") + "${row.meter.displayUsedPercent}%",
                color = if (row.stale) WidgetAmber else WidgetText,
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(2.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(7.dp)) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(8)
            val heightPx = with(density) { 7.dp.roundToPx() }.coerceAtLeast(4)
            val meter = row.meter
            val used = meter.usedPercent ?: meter.displayUsedPercent?.toDouble() ?: 0.0
            val expected = if (meter.isCapBar) null else PaceWindows.expectedUsedPercent(meter, nowMs)
            val bitmap = remember(meter, expected, widthPx, heightPx) {
                PaceBarBitmap.render(used, expected, widthPx, heightPx, meter).asImageBitmap()
            }
            Image(bitmap, contentDescription = meter.usedLabel(), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
}

@Composable
private fun PreviewReset(row: WidgetRow.Reset, now: Long) {
    val urgency = ResetEmphasis.of(row.at, now)
    val tint = when (urgency) {
        ResetUrgency.RELAXED -> WidgetText
        ResetUrgency.SOON -> WidgetBright
        ResetUrgency.NEAR -> WidgetAmber
        ResetUrgency.IMMINENT -> WidgetOrange
    }
    val time = NyTime.formatResetTime(row.at)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = WidgetMuted, fontSize = 11.sp)) { append(row.label?.let { "$it $time" } ?: time) }
            withStyle(
                SpanStyle(
                    color = tint,
                    fontSize = if (urgency == ResetUrgency.IMMINENT) 12.sp else 11.sp,
                    fontWeight = if (urgency == ResetUrgency.RELAXED) FontWeight.Normal else FontWeight.Bold
                )
            ) { append("  ${NyTime.formatResetRelative(row.at, now)}") }
        },
        modifier = Modifier.padding(start = if (row.indented) 8.dp else 0.dp)
    )
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
