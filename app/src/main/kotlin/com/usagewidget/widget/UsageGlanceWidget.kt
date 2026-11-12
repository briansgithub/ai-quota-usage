package com.usagewidget.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.currentState
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.wrapContentHeight
import androidx.glance.layout.wrapContentSize
import androidx.glance.layout.wrapContentWidth
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.usagewidget.MainActivity
import com.usagewidget.data.AppSettings
import com.usagewidget.data.UsageRepository
import com.usagewidget.domain.PaceUrgency
import com.usagewidget.domain.PacingPlanner
import com.usagewidget.domain.PacingPolicy
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.ResetEmphasis
import com.usagewidget.domain.ResetUrgency
import com.usagewidget.domain.SoftwareSection
import com.usagewidget.domain.StaleSeverity
import com.usagewidget.domain.StaleView
import com.usagewidget.domain.UsageMeter
import com.usagewidget.domain.UsageSections
import com.usagewidget.domain.UsageSnapshot
import com.usagewidget.util.NyTime

class UsageGlanceWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 110.dp),
            DpSize(250.dp, 180.dp),
            DpSize(300.dp, 260.dp),
            DpSize(300.dp, 360.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val snapshot = WidgetGlanceState.snapshotOf(prefs)
                ?: UsageRepository.get(LocalContext.current).snapshot.value
            val nextRefreshAt = WidgetGlanceState.nextRefreshOf(prefs)
                ?: AppSettings.get(LocalContext.current).nextRefreshAtEpochMs.value
            val percentPerWindow = AppSettings.get(LocalContext.current).percentPerFiveHourWindow
            GlanceTheme {
                WidgetContent(snapshot, nextRefreshAt, percentPerWindow)
            }
        }
    }
}

private val Chrome = ColorProvider(Color(0xFF141414), Color(0xFF141414))
private val Surface = ColorProvider(Color(0xFF1A1A1A), Color(0xFF1A1A1A))
private val TextOn = ColorProvider(Color(0xFFE8E8E8), Color(0xFFE8E8E8))
private val Muted = ColorProvider(Color(0xFF9A9A9A), Color(0xFF9A9A9A))
private val Bar = ColorProvider(Color(0xFF6EA8D8), Color(0xFF6EA8D8))
private val Warn = ColorProvider(Color(0xFFE08B6E), Color(0xFFE08B6E))
private val Amber = ColorProvider(Color(0xFFE6B84C), Color(0xFFE6B84C))
private val Bright = ColorProvider(Color(0xFFF5F5F5), Color(0xFFF5F5F5))
private val Imminent = ColorProvider(Color(0xFFFF7043), Color(0xFFFF7043))
private val Track = ColorProvider(Color(0xFF2A2A2A), Color(0xFF2A2A2A))

@Composable
private fun WidgetContent(
    snapshot: UsageSnapshot,
    nextRefreshAt: Long,
    percentPerWindow: Double = PacingPolicy.DEFAULT_PERCENT_PER_WINDOW
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val compact = size.height < 160.dp
    val now = System.currentTimeMillis()
    val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
    val sections = UsageSections.from(snapshot, now)
    val anyStale = sections.any { it.stale.isStale }
    val rows = widgetRows(sections, includePace = !compact, percentPerWindow, now)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Chrome)
            .cornerRadius(20.dp)
            .padding(if (compact) 8.dp else 12.dp)
            .clickable(openApp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().wrapContentHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (anyStale) "Usage  ⚠" else "Usage",
                style = TextStyle(
                    color = if (sections.any { it.stale.isStale && it.stale.severity == StaleSeverity.RED }) {
                        Warn
                    } else if (anyStale) {
                        Amber
                    } else {
                        TextOn
                    },
                    fontSize = if (compact) 14.sp else 16.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight().wrapContentHeight()
            )
            Text(
                text = "↻",
                style = TextStyle(color = TextOn, fontSize = 16.sp),
                modifier = GlanceModifier.wrapContentSize().clickable(actionRunCallback<RefreshAction>())
            )
        }
        Spacer(GlanceModifier.height(if (compact) 4.dp else 6.dp))
        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            itemsIndexed(
                items = rows,
                itemId = { index, row -> row.stableId(index) }
            ) { _, row ->
                when (row) {
                    is WidgetRow.Gap -> Spacer(GlanceModifier.height(if (compact) 8.dp else 12.dp))
                    is WidgetRow.Header -> SectionHeader(row.title, row.stale)
                    is WidgetRow.Meter -> MeterGlanceRow(row.meter, now, compact)
                    is WidgetRow.SignIn -> SignInRow(row.title, row.providerId)
                    is WidgetRow.Message -> MessageRow(row.text, row.warn, row.emphasize, row.imminent)
                }
                Spacer(GlanceModifier.height(if (compact) 4.dp else 6.dp))
            }
        }
        if (nextRefreshAt > 0L) {
            Spacer(GlanceModifier.height(4.dp))
            AndroidRemoteViews(
                remoteViews = NextRefreshCountdown.remoteViews(context, nextRefreshAt, now),
                modifier = GlanceModifier
                    .wrapContentWidth()
                    .wrapContentHeight()
                    .height(16.dp)
            )
        }
    }
}

private sealed class WidgetRow {
    data object Gap : WidgetRow()
    data class Header(val title: String, val stale: StaleView) : WidgetRow()
    data class Meter(val meter: UsageMeter) : WidgetRow()
    data class SignIn(val title: String, val providerId: String) : WidgetRow()
    data class Message(
        val text: String,
        val warn: Boolean = false,
        val emphasize: Boolean = false,
        val imminent: Boolean = false
    ) : WidgetRow()

    fun stableId(index: Int): Long = when (this) {
        is Gap -> 50L + index
        is Header -> 100L + title.hashCode()
        is Meter -> 200L + meter.id.hashCode()
        is SignIn -> 300L + providerId.hashCode()
        is Message -> 400L + index
    }
}

private fun widgetRows(
    sections: List<SoftwareSection>,
    includePace: Boolean = false,
    percentPerWindow: Double = PacingPolicy.DEFAULT_PERCENT_PER_WINDOW,
    nowMs: Long = System.currentTimeMillis()
): List<WidgetRow> {
    val rows = mutableListOf<WidgetRow>()
    sections.forEachIndexed { index, section ->
        if (index > 0) rows += WidgetRow.Gap
        rows += WidgetRow.Header(section.id.title, section.stale)
        val hasData = section.meters.any { it.hasUsageBar || it.resetsAtEpochMs != null }
        if (section.stale.isStale) {
            val line = listOfNotNull(section.stale.lastUpdatedLine, section.stale.shortReason)
                .joinToString(" · ")
            if (line.isNotBlank()) {
                rows += WidgetRow.Message(line, warn = true)
            }
        }
        if (hasData) {
            section.meters.forEach { rows += WidgetRow.Meter(it) }
        }
        section.manualResets?.displayLine()?.let { line ->
            rows += WidgetRow.Message(line, warn = false, emphasize = true)
        }
        if (includePace) {
            PacingPlanner.forSection(section, nowMs, percentPerWindow)?.let { pacing ->
                rows += WidgetRow.Message(
                    text = pacing.line,
                    warn = pacing.urgency == PaceUrgency.TIGHT,
                    emphasize = pacing.urgency != PaceUrgency.OK,
                    imminent = pacing.urgency == PaceUrgency.CRITICAL ||
                        pacing.urgency == PaceUrgency.IMPOSSIBLE
                )
            }
        }
        when (section.status) {
            ProviderStatus.NEEDS_SIGN_IN, ProviderStatus.UNSIGNED -> {
                if (!hasData && section.id.title == "Codex") {
                    section.meters.forEach { rows += WidgetRow.Meter(it) }
                }
                rows += WidgetRow.SignIn(
                    title = section.id.title,
                    providerId = section.signInProvider.wireName
                )
            }
            ProviderStatus.ERROR -> {
                if (!hasData) {
                    val message = section.error ?: "Could not load usage"
                    rows += WidgetRow.SignIn(
                        title = "${section.id.title} · $message",
                        providerId = section.signInProvider.wireName
                    )
                }
            }
            ProviderStatus.LOADING -> {
                rows += WidgetRow.Message("Loading…")
            }
            ProviderStatus.OK -> Unit
        }
    }
    return rows
}

@Composable
private fun SectionHeader(title: String, stale: StaleView) {
    val color = when {
        !stale.isStale -> TextOn
        stale.severity == StaleSeverity.RED -> Warn
        else -> Amber
    }
    Text(
        text = if (stale.isStale) "⚠  $title" else title,
        style = TextStyle(color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    )
}

@Composable
private fun SignInRow(title: String, providerId: String) {
    val context = LocalContext.current
    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_SIGN_IN_PROVIDER, providerId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(Surface)
            .cornerRadius(10.dp)
            .padding(8.dp)
            .clickable(actionStartActivity(intent)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (title.contains("Sign in", ignoreCase = true) || title.contains("·")) {
                title
            } else {
                "$title · Sign in"
            },
            style = TextStyle(color = Warn, fontSize = 12.sp),
            modifier = GlanceModifier.defaultWeight()
        )
    }
}

@Composable
private fun MessageRow(
    text: String,
    warn: Boolean,
    emphasize: Boolean = false,
    imminent: Boolean = false
) {
    val color = when {
        imminent -> Imminent
        warn -> Amber
        emphasize -> TextOn
        else -> Muted
    }
    Text(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = if (emphasize || imminent) 13.sp else 11.sp,
            fontWeight = if (imminent || warn) FontWeight.Bold else FontWeight.Normal
        )
    )
}

@Composable
private fun MeterGlanceRow(meter: UsageMeter, nowMs: Long, compact: Boolean) {
    val remaining = meter.remainingLabel()
    val reset = meter.resetsAtEpochMs?.let { NyTime.formatResetLine(it, nowMs) }
        ?: meter.resetLabel.orEmpty()
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = meter.title,
                style = TextStyle(color = TextOn, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.defaultWeight()
            )
            if (remaining != null) {
                Text(
                    text = remaining,
                    style = TextStyle(color = TextOn, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                )
            } else if (reset.isNotBlank() && !meter.hasUsageBar) {
                Text(text = reset, style = resetTextStyle(meter.resetsAtEpochMs, nowMs))
            }
        }
        if (meter.hasUsageBar && !compact) {
            Spacer(GlanceModifier.height(4.dp))
            GlanceBar(meter.displayUsedPercent?.toFloat() ?: 0f)
        }
        if (remaining != null && reset.isNotBlank()) {
            Spacer(GlanceModifier.height(2.dp))
            Text(text = reset, style = resetTextStyle(meter.resetsAtEpochMs, nowMs))
        }
    }
}

private fun resetTextStyle(resetAtEpochMs: Long?, nowMs: Long): TextStyle {
    val urgency = ResetEmphasis.of(resetAtEpochMs, nowMs)
    val color = when (urgency) {
        ResetUrgency.RELAXED -> TextOn
        ResetUrgency.SOON -> Bright
        ResetUrgency.NEAR -> Amber
        ResetUrgency.IMMINENT -> Imminent
    }
    val weight = when (urgency) {
        ResetUrgency.RELAXED -> FontWeight.Medium
        ResetUrgency.SOON -> FontWeight.Medium
        ResetUrgency.NEAR, ResetUrgency.IMMINENT -> FontWeight.Bold
    }
    val size = when (urgency) {
        ResetUrgency.RELAXED -> 12.sp
        else -> 13.sp
    }
    return TextStyle(color = color, fontSize = size, fontWeight = weight)
}

@Composable
private fun GlanceBar(percent: Float) {
    val used = percent.coerceIn(0f, 100f)
    val fill = if (used >= 90f) Warn else Bar
    LinearProgressIndicator(
        progress = used / 100f,
        modifier = GlanceModifier.fillMaxWidth().height(7.dp),
        color = fill,
        backgroundColor = Track
    )
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters
    ) {
        UsageRepository.get(context).refresh(allowHtmlFallback = true)
    }
}
