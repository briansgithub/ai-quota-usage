package com.usagewidget.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usagewidget.domain.DisplayPreferences
import com.usagewidget.domain.MeterIds
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.domain.WidgetBars
import com.usagewidget.refresh.NotificationIcons
import com.usagewidget.ui.theme.CursorBar
import com.usagewidget.ui.theme.CursorMuted
import com.usagewidget.ui.theme.CursorOutline
import com.usagewidget.ui.theme.CursorText
import com.usagewidget.ui.theme.primaryContainerDark

/**
 * Display settings shared by the Settings dialog and the Diagnostics screen:
 * on-demand spend bars (app and widget), the usage-left filter (app and widget), then
 * per-bar visibility on the widget.
 */
@Composable
fun DisplaySettings(
    display: DisplayPreferences,
    onShowCursorOnDemand: (Boolean) -> Unit,
    onShowGrokOnDemand: (Boolean) -> Unit,
    onOnlyUsageRemaining: (Boolean) -> Unit,
    onWidgetBar: (meterId: String, visible: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup(
            title = "On-demand spend",
            hint = "Dollar-cap bars, in the app and on the widget."
        ) {
            ChipRow {
                ToggleChip("Cursor", display.showCursorOnDemand, onShowCursorOnDemand)
                ToggleChip("Grok Bot", display.showGrokOnDemand, onShowGrokOnDemand)
            }
        }
        SettingsGroup(
            title = "Usage left",
            hint = "Hide bars with weekly or monthly quota used up, and that model's 5-hour bar. In the app and on the widget."
        ) {
            ChipRow {
                ToggleChip(
                    label = "Only models with usage remaining",
                    selected = display.onlyUsageRemaining,
                    onChange = onOnlyUsageRemaining,
                    onIcon = Icons.Outlined.FilterAlt,
                    offIcon = Icons.Outlined.FilterAltOff
                )
            }
        }
        SettingsGroup(
            title = "Widget bars",
            hint = "Tap a bar to show or hide it on the home-screen widget."
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                WidgetBars.catalog.groupBy { it.section }.forEach { (section, options) ->
                    // On-demand hidden everywhere wins; its widget chip is shown off and disabled.
                    val forcedOff = buildSet {
                        if (!display.showCursorOnDemand) add("cursor.on_demand")
                        if (!display.showGrokOnDemand) add(MeterIds.GROK_ON_DEMAND)
                    }
                    val allHidden = options.all { it.meterId in display.hiddenWidgetBars || it.meterId in forcedOff }
                    Column {
                        ServiceHeader(section, allHidden)
                        ChipRow {
                            options.forEach { option ->
                                val forced = option.meterId in forcedOff
                                ToggleChip(
                                    label = option.label,
                                    selected = !forced && option.meterId !in display.hiddenWidgetBars,
                                    enabled = !forced,
                                    onChange = { onWidgetBar(option.meterId, it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, hint: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title.uppercase(),
                color = CursorBar,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            )
            Text(hint, color = CursorMuted, style = MaterialTheme.typography.bodySmall)
        }
        content()
    }
}

@Composable
private fun ServiceHeader(section: SoftwareSectionId, allHidden: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(NotificationIcons.forSection(section)),
            contentDescription = null,
            tint = if (allHidden) CursorMuted else CursorText,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            section.title,
            color = if (allHidden) CursorMuted else CursorText,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        if (allHidden) {
            Text("Hidden", color = CursorMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/**
 * Show/hide pill: eye = shown, slashed eye = hidden (or [onIcon]/[offIcon]). Same width in both states so the
 * row never reflows when tapped; 48dp touch target via minimumInteractiveComponentSize.
 */
@Composable
private fun ToggleChip(
    label: String,
    selected: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    onIcon: ImageVector = Icons.Outlined.Visibility,
    offIcon: ImageVector = Icons.Outlined.VisibilityOff
) {
    val shape = RoundedCornerShape(8.dp)
    val content = when {
        !enabled -> CursorMuted.copy(alpha = 0.4f)
        selected -> CursorText
        else -> CursorMuted
    }
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .height(34.dp)
            .clip(shape)
            .background(if (selected) primaryContainerDark else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else CursorOutline.copy(alpha = if (enabled) 1f else 0.5f), shape)
            .toggleable(value = selected, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = if (selected) onIcon else offIcon,
            contentDescription = null,
            tint = if (selected) CursorBar else content,
            modifier = Modifier.size(16.dp)
        )
        Text(label, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
