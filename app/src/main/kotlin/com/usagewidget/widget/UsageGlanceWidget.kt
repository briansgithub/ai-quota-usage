package com.usagewidget.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.currentState
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.AndroidRemoteViews
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
import androidx.glance.layout.ContentScale
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
import com.usagewidget.domain.LimitGrouping
import com.usagewidget.domain.LimitGroupEntry
import com.usagewidget.domain.NyTimeAgo
import com.usagewidget.domain.OnDemandDisplay
import com.usagewidget.domain.UsageRemaining
import com.usagewidget.domain.WidgetBars
import com.usagewidget.domain.PaceUrgency
import com.usagewidget.domain.PaceWindows
import com.usagewidget.domain.PacingPlanner
import com.usagewidget.domain.PacingPolicy
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.RefreshSchedule
import com.usagewidget.domain.ResetEmphasis
import com.usagewidget.domain.ResetSegmentLook
import com.usagewidget.domain.ResetUrgency
import com.usagewidget.domain.SoftwareSection
import com.usagewidget.domain.StaleSeverity
import com.usagewidget.domain.StaleView
import com.usagewidget.domain.UsageMeter
import com.usagewidget.domain.UsageSections
import com.usagewidget.domain.UsageSnapshot
import com.usagewidget.refresh.UsageRefreshWorker
import com.usagewidget.util.NyTime
import com.usagewidget.util.ResetSegment

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
private val Warn = ColorProvider(Color(0xFFE08B6E), Color(0xFFE08B6E))
private val Amber = ColorProvider(Color(0xFFE6B84C), Color(0xFFE6B84C))
private val Bright = ColorProvider(Color(0xFFF5F5F5), Color(0xFFF5F5F5))
private val Imminent = ColorProvider(Color(0xFFFF7043), Color(0xFFFF7043))

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
    val openSettings = actionStartActivity(
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    )
    val settings = AppSettings.get(context)
    val sections = UsageRemaining.apply(
        WidgetBars.apply(
            OnDemandDisplay.apply(
                UsageSections.from(snapshot, now),
                showCursor = settings.showCursorOnDemand,
                showGrok = settings.showGrokOnDemand
            ),
            settings.hiddenWidgetBars,
            settings.widgetSectionOrder
        ),
        enabled = settings.onlyUsageRemaining,
        dropSpentCards = true
    )
    val anyStale = sections.any { it.stale.isStale }
    val rows = widgetRows(sections, includePace = !compact, percentPerWindow, now, settings.groupByLimit)

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
                modifier = GlanceModifier.wrapContentHeight()
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = "↻",
                style = TextStyle(color = TextOn, fontSize = 16.sp),
                modifier = GlanceModifier.wrapContentSize().clickable(actionRunCallback<RefreshAction>())
            )
            Text(
                text = "⚙",
                style = TextStyle(color = TextOn, fontSize = 16.sp),
                modifier = GlanceModifier.wrapContentSize().padding(start = 4.dp).clickable(openSettings)
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
                    is WidgetRow.Header -> SectionHeader(row.title, row.stale, row.nested)
                    is WidgetRow.GroupHeader -> GroupHeader(row.title)
                    is WidgetRow.Meter -> MeterGlanceRow(row.meter, now, compact, size.width)
                    is WidgetRow.SignIn -> SignInRow(row.title, row.providerId)
                    is WidgetRow.Message -> MessageRow(row.text, row.warn, row.emphasize, row.imminent)
                }
                Spacer(GlanceModifier.height(if (compact) 4.dp else 6.dp))
            }
        }
        val lastSuccess = RefreshSchedule.lastSuccessEpochMs(snapshot)
        if (nextRefreshAt > 0L || lastSuccess > 0L) {
            Spacer(GlanceModifier.height(4.dp))
            AndroidRemoteViews(
                remoteViews = NextRefreshCountdown.remoteViews(context, nextRefreshAt, lastSuccess, now),
                modifier = GlanceModifier
                    .wrapContentWidth()
                    .wrapContentHeight()
                    .height(16.dp)
            )
        }
    }
}

internal fun updatedAgoLabel(
    snapshot: UsageSnapshot,
    sections: List<SoftwareSection>,
    nowMs: Long,
    compact: Boolean
): String {
    val lastSuccess = sections.maxOf { it.health.lastSuccessEpochMs }
    val epoch = when {
        lastSuccess > 0L -> lastSuccess
        snapshot.fetchedAtEpochMs > 0L -> snapshot.fetchedAtEpochMs
        else -> 0L
    }
    if (epoch <= 0L) return if (compact) "—" else "updated —"
    val ago = NyTimeAgo.format(epoch, nowMs)
    return if (compact) ago else "updated $ago"
}

private sealed class WidgetRow {
    data object Gap : WidgetRow()
    data class Header(val title: String, val stale: StaleView, val nested: Boolean = false) : WidgetRow()
    /** Top-level group title in group-by-limit mode (e.g. "Short limits (5-hr)"). */
    data class GroupHeader(val title: String) : WidgetRow()
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
        is Header -> 100L + title.hashCode() + (if (nested) 7L + index else 0L)
        is GroupHeader -> 150L + title.hashCode()
        is Meter -> 200L + meter.id.hashCode()
        is SignIn -> 300L + providerId.hashCode()
        is Message -> 400L + index
    }
}

private fun widgetRows(
    sections: List<SoftwareSection>,
    includePace: Boolean = false,
    percentPerWindow: Double = PacingPolicy.DEFAULT_PERCENT_PER_WINDOW,
    nowMs: Long = System.currentTimeMillis(),
    groupByLimit: Boolean = false
): List<WidgetRow> {
    val rows = mutableListOf<WidgetRow>()
    if (!groupByLimit) {
        sections.forEachIndexed { index, section ->
            if (index > 0) rows += WidgetRow.Gap
            rows += WidgetRow.Header(section.id.title, section.stale)
            appendSectionBody(rows, section, section.meters, primary = true, includePace, percentPerWindow, nowMs)
        }
        return rows
    }
    LimitGrouping.group(sections, nowMs).forEachIndexed { groupIndex, group ->
        if (groupIndex > 0) rows += WidgetRow.Gap
        rows += WidgetRow.GroupHeader(group.kind.title)
        group.entries.forEach { entry: LimitGroupEntry ->
            rows += WidgetRow.Header(entry.section.id.title, entry.section.stale, nested = true)
            appendSectionBody(rows, entry.section, entry.meters, entry.primary, includePace, percentPerWindow, nowMs)
        }
    }
    return rows
}

/**
 * Rows under a software header. [meters] is the section's bars, or only the bars of one limit
 * group in group-by-limit mode; the card-level lines (stale notice, manual resets, pacing,
 * sign-in prompt) are added only for the [primary] entry so they appear once per software.
 */
private fun appendSectionBody(
    rows: MutableList<WidgetRow>,
    section: SoftwareSection,
    meters: List<UsageMeter>,
    primary: Boolean,
    includePace: Boolean,
    percentPerWindow: Double,
    nowMs: Long
) {
    val hasData = meters.any { it.hasUsageBar || it.resetsAtEpochMs != null }
    if (primary && section.stale.isStale) {
        val line = listOfNotNull(section.stale.lastUpdatedLine, section.stale.shortReason)
            .joinToString(" · ")
        if (line.isNotBlank()) {
            rows += WidgetRow.Message(line, warn = true)
        }
    }
    if (hasData) {
        meters.forEach { rows += WidgetRow.Meter(it) }
    }
    if (!primary) return
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
            if (!hasData && (section.id.title == "Codex" || section.id.title == "Claude")) {
                meters.forEach { rows += WidgetRow.Meter(it) }
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

@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = TextStyle(color = Bright, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    )
}

@Composable
private fun SectionHeader(title: String, stale: StaleView, nested: Boolean = false) {
    val color = when {
        !stale.isStale -> TextOn
        stale.severity == StaleSeverity.RED -> Warn
        else -> Amber
    }
    Text(
        text = if (stale.isStale) "⚠  $title" else title,
        style = TextStyle(color = color, fontSize = if (nested) 14.sp else 16.sp, fontWeight = FontWeight.Bold)
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
private fun MeterGlanceRow(
    meter: UsageMeter,
    nowMs: Long,
    compact: Boolean,
    tileWidth: Dp
) {
    val used = meter.usedLabel()
    val resetSegments = meter.resetsAtEpochMs?.let { NyTime.formatResetSegments(it, nowMs) }
        ?: meter.resetLabel?.takeIf { it.isNotBlank() }?.let { listOf(ResetSegment(it, emphasized = false)) }
        ?: emptyList()
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
            if (used != null) {
                Text(
                    text = used,
                    style = TextStyle(color = TextOn, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                )
            } else if (resetSegments.isNotEmpty() && !meter.hasUsageBar) {
                ResetLine(resetSegments, meter.resetsAtEpochMs, nowMs)
            }
        }
        if (meter.hasUsageBar && !compact) {
            Spacer(GlanceModifier.height(4.dp))
            GlancePaceBar(meter, nowMs, tileWidth)
        }
        if (used != null && resetSegments.isNotEmpty()) {
            Spacer(GlanceModifier.height(2.dp))
            ResetLine(resetSegments, meter.resetsAtEpochMs, nowMs)
        }
    }
}

/**
 * Reset line: only the emphasized runs (clock time, duration) get the urgency
 * colour, larger size and bold; "Reset:", the day and parentheses stay plain.
 */
@Composable
private fun ResetLine(segments: List<ResetSegment>, resetAtEpochMs: Long?, nowMs: Long) {
    val urgency = ResetEmphasis.of(resetAtEpochMs, nowMs)
    Row(verticalAlignment = Alignment.Bottom) {
        for (segment in segments) {
            val look = ResetSegmentLook.of(segment.emphasized, urgency)
            Text(
                text = segment.text,
                maxLines = 1,
                style = TextStyle(
                    color = when (look.urgency) {
                        ResetUrgency.RELAXED -> TextOn
                        ResetUrgency.SOON -> Bright
                        ResetUrgency.NEAR -> Amber
                        ResetUrgency.IMMINENT -> Imminent
                    },
                    fontSize = if (look.enlarged) 13.sp else 12.sp,
                    fontWeight = if (look.bold) FontWeight.Bold else FontWeight.Normal
                )
            )
        }
    }
}

@Composable
private fun GlancePaceBar(
    meter: UsageMeter,
    nowMs: Long,
    tileWidth: Dp
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val widthPx = (tileWidth.value * density).toInt().coerceAtLeast(8)
    val heightPx = (7f * density).toInt().coerceAtLeast(4)
    val used = meter.usedPercent ?: meter.displayUsedPercent?.toDouble() ?: 0.0
    val expected = if (meter.isCapBar) null else PaceWindows.expectedUsedPercent(meter, nowMs)
    val bitmap = PaceBarBitmap.render(used, expected, widthPx, heightPx, capBar = meter.isCapBar)
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = meter.usedLabel() ?: "usage",
        modifier = GlanceModifier.fillMaxWidth().height(7.dp),
        contentScale = ContentScale.FillBounds
    )
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters
    ) {
        // Not refresh() here: a slow fetch inside this broadcast got the app ANR-killed.
        UsageRefreshWorker.enqueueExpedited(context)
    }
}
