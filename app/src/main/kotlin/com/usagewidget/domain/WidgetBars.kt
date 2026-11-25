package com.usagewidget.domain

import com.usagewidget.providers.claude.ClaudeMeterIds

data class WidgetBarOption(val section: SoftwareSectionId, val meterId: String, val label: String)

/**
 * Per-bar visibility on the home-screen widget. The in-app dashboard always shows every
 * bar. Meters not in [catalog] (e.g. extra Codex limits) are never hidden. Matches
 * Windows `WidgetBars`.
 */
object WidgetBars {
    val defaultSectionOrder: List<SoftwareSectionId> = SoftwareSectionId.entries.toList()

    val catalog: List<WidgetBarOption> = listOf(
        WidgetBarOption(SoftwareSectionId.CURSOR, "cursor.models", "Models"),
        WidgetBarOption(SoftwareSectionId.CURSOR, "cursor.other", "Other models"),
        WidgetBarOption(SoftwareSectionId.CURSOR, "cursor.on_demand", "On-demand"),
        WidgetBarOption(SoftwareSectionId.GROK_BOT, MeterIds.GROK, "Weekly"),
        WidgetBarOption(SoftwareSectionId.GROK_BOT, MeterIds.GROK_ON_DEMAND, "On-demand"),
        WidgetBarOption(SoftwareSectionId.CODEX, "openai.five_hour", "5-hour"),
        WidgetBarOption(SoftwareSectionId.CODEX, "openai.weekly", "Weekly"),
        WidgetBarOption(SoftwareSectionId.CODEX, "openai.thirty_day", "30-day"),
        WidgetBarOption(SoftwareSectionId.CODEX, "openai.code_review", "Review"),
        WidgetBarOption(SoftwareSectionId.CLAUDE, ClaudeMeterIds.FIVE_HOUR, "Session"),
        WidgetBarOption(SoftwareSectionId.CLAUDE, ClaudeMeterIds.WEEKLY, "Weekly"),
        WidgetBarOption(SoftwareSectionId.CLAUDE, ClaudeMeterIds.WEEKLY_SONNET, "Sonnet"),
        WidgetBarOption(SoftwareSectionId.CLAUDE, ClaudeMeterIds.WEEKLY_OPUS, "Opus"),
        WidgetBarOption(SoftwareSectionId.GEMINI, "gemini.gemini-5h", "Gemini 5-hour"),
        WidgetBarOption(SoftwareSectionId.GEMINI, "gemini.gemini-weekly", "Gemini weekly"),
        WidgetBarOption(SoftwareSectionId.GEMINI, "gemini.3p-5h", "Third-party 5-hour"),
        WidgetBarOption(SoftwareSectionId.GEMINI, "gemini.3p-weekly", "Third-party weekly"),
        WidgetBarOption(SoftwareSectionId.GEMINI, "gemini.argon-weekly", "Argon weekly")
    )

    fun normalizeSectionOrder(order: List<SoftwareSectionId>?): List<SoftwareSectionId> {
        val result = LinkedHashSet<SoftwareSectionId>()
        order?.forEach { result.add(it) }
        defaultSectionOrder.forEach { result.add(it) }
        return result.toList()
    }

    fun parseSectionOrder(wireNames: Iterable<String>?): List<SoftwareSectionId> {
        val parsed = wireNames?.mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) null
            else SoftwareSectionId.entries.firstOrNull { it.wireName.equals(trimmed, ignoreCase = true) }
        }
        return normalizeSectionOrder(parsed)
    }

    fun moveSection(
        order: List<SoftwareSectionId>?,
        section: SoftwareSectionId,
        delta: Int
    ): List<SoftwareSectionId> {
        val normalized = normalizeSectionOrder(order).toMutableList()
        val index = normalized.indexOf(section)
        if (index < 0 || delta == 0) return normalized
        val target = (index + delta).coerceIn(0, normalized.lastIndex)
        if (target == index) return normalized
        normalized.removeAt(index)
        normalized.add(target, section)
        return normalized
    }

    /**
     * Drops hidden bars and applies custom software ordering. A card whose bars were all
     * hidden is dropped too, so a service switched off entirely leaves no empty card; cards
     * that never had a bar (sign-in prompts, "Waiting for data") stay.
     */
    fun apply(
        sections: List<SoftwareSection>,
        hidden: Set<String>,
        sectionOrder: List<SoftwareSectionId>? = null
    ): List<SoftwareSection> {
        val filtered = if (hidden.isEmpty()) {
            sections
        } else {
            sections.mapNotNull { section ->
                val kept = section.meters.filter { it.id !in hidden }
                when {
                    kept.size == section.meters.size -> section
                    kept.none { it.hasUsageBar } &&
                        catalog.filter { it.section == section.id }.all { it.meterId in hidden } -> null
                    else -> section.copy(meters = kept)
                }
            }
        }
        if (sectionOrder.isNullOrEmpty()) return filtered
        val rank = normalizeSectionOrder(sectionOrder).withIndex().associate { (i, id) -> id to i }
        return filtered.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }
}
