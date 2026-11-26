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
import androidx.glance.layout.size
import androidx.glance.layout.padding
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.usagewidget.R
import com.usagewidget.MainActivity
import com.usagewidget.data.AppSettings
import com.usagewidget.data.UsageRepository
import com.usagewidget.domain.LimitGrouping
import com.usagewidget.domain.NyTimeAgo
import com.usagewidget.domain.OnDemandDisplay
import com.usagewidget.domain.UsageRemaining
import com.usagewidget.domain.WidgetBars
import com.usagewidget.domain.PaceWindows
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.SoftwareSection
import com.usagewidget.domain.UsageMeter
import com.usagewidget.domain.UsageSections
import com.usagewidget.domain.UsageSnapshot
import com.usagewidget.refresh.UsageRefreshWorker

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
            GlanceTheme {
                WidgetContent(snapshot)
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

@Composable
private fun WidgetContent(snapshot: UsageSnapshot) {
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
    val rows = widgetRows(sections, now, settings.groupByLimit)

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
            Text("Quota used", style = TextStyle(color = Muted, fontSize = 12.sp))
            Spacer(GlanceModifier.defaultWeight())
            Image(ImageProvider(R.drawable.ic_widget_refresh), "Refresh",
                GlanceModifier.size(32.dp).clickable(actionRunCallback<RefreshAction>()).padding(7.dp))
            Image(ImageProvider(R.drawable.ic_widget_settings), "Widget settings",
                GlanceModifier.size(32.dp).clickable(openSettings).padding(7.dp))
        }
        Spacer(GlanceModifier.height(if (compact) 4.dp else 6.dp))
        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            itemsIndexed(
                items = rows,
                itemId = { index, row -> row.stableId(index) }
            ) { _, row ->
                when (row) {
                    is WidgetRow.Heading -> GroupHeader(row.title)
                    is WidgetRow.Meter -> MeterGlanceRow(row, now, size.width)
                    is WidgetRow.SignIn -> SignInRow(row.title, row.providerId)
                    is WidgetRow.Message -> Text(row.text, style = TextStyle(color = Muted, fontSize = 12.sp))
                }
                Spacer(GlanceModifier.height(if (compact) 4.dp else 6.dp))
            }
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

internal sealed class WidgetRow {
    data class Heading(val title: String) : WidgetRow()
    data class Meter(val meter: UsageMeter, val title: String, val stale: Boolean) : WidgetRow()
    data class SignIn(val title: String, val providerId: String) : WidgetRow()
    data class Message(val text: String) : WidgetRow()

    fun stableId(index: Int): Long = when (this) {
        is Heading -> 100L + title.hashCode()
        is Meter -> 200L + meter.id.hashCode()
        is SignIn -> 300L + title.hashCode()
        is Message -> 400L + index
    }
}

internal fun widgetRows(
    sections: List<SoftwareSection>,
    nowMs: Long = System.currentTimeMillis(),
    groupByLimit: Boolean = false
): List<WidgetRow> = buildList {
    // Reset-only and placeholder rows belong to the detailed dashboard.
    val visible = sections.map { section -> section.copy(meters = section.meters.filter { it.hasUsageBar }) }
    if (groupByLimit) {
        for (group in LimitGrouping.group(visible, nowMs)) {
            val lengths = group.entries.flatMap { it.meters }.map(LimitGrouping::windowLengthMs).distinct()
            val window = when (lengths.singleOrNull()) {
                5L * 3_600_000 -> "5h"
                24L * 3_600_000 -> "Daily"
                7L * 24 * 3_600_000 -> "Weekly"
                else -> null
            }
            if (group.entries.any { it.meters.isNotEmpty() }) add(WidgetRow.Heading(window ?: group.kind.title))
            for (entry in group.entries) addMeters(entry.section, entry.meters, window)
        }
    } else {
        for (section in visible) addMeters(section, section.meters, null)
    }

    val signInProviders = mutableSetOf<ProviderId>()
    for (section in visible) {
        when {
            section.status == ProviderStatus.UNSIGNED || section.status == ProviderStatus.NEEDS_SIGN_IN -> {
                if (signInProviders.add(section.signInProvider)) {
                    val title = if (section.signInProvider == ProviderId.CURSOR) "Cursor" else section.id.title
                    add(WidgetRow.SignIn(title, section.signInProvider.wireName))
                }
            }
            section.meters.isEmpty() && section.status == ProviderStatus.ERROR ->
                add(WidgetRow.Message("${section.id.title} unavailable"))
            section.meters.isEmpty() && section.status == ProviderStatus.LOADING ->
                add(WidgetRow.Message("${section.id.title} loading"))
        }
    }
    if (isEmpty()) add(WidgetRow.Message("No visible limits"))
}

private fun MutableList<WidgetRow>.addMeters(section: SoftwareSection, meters: List<UsageMeter>, window: String?) {
    meters.forEachIndexed { index, meter ->
        val provider = section.id.title
        val shortName = WidgetBars.catalog.firstOrNull { it.meterId == meter.id }?.label ?: meter.title
        var name = shortName.replace("5-hour", "5h", ignoreCase = true)
            .removePrefix("$provider ").trim()
        if (window != null) {
            name = name.split(' ').filterNot { it.equals(window, ignoreCase = true) }.joinToString(" ")
            if (window == "5h" && name == "Session") name = ""
        }
        val label = when {
            name.isEmpty() -> provider
            index == 0 -> "$provider $name"
            else -> name
        }
        add(WidgetRow.Meter(meter, label, section.stale.isStale))
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        modifier = GlanceModifier.padding(top = 4.dp),
        style = TextStyle(color = Bright, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
            text = "$title � Sign in",
            style = TextStyle(color = Warn, fontSize = 12.sp),
            modifier = GlanceModifier.defaultWeight()
        )
    }
}

@Composable
private fun MeterGlanceRow(row: WidgetRow.Meter, nowMs: Long, tileWidth: Dp) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.title,
                style = TextStyle(color = TextOn, fontSize = 13.sp),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = (if (row.stale) "~" else "") + "${row.meter.displayUsedPercent}%",
                style = TextStyle(color = if (row.stale) Amber else TextOn, fontSize = 13.sp)
            )
        }
        Spacer(GlanceModifier.height(2.dp))
        GlancePaceBar(row.meter, nowMs, tileWidth)
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
    val bitmap = PaceBarBitmap.render(used, expected, widthPx, heightPx, meter)
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
