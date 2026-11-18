package com.usagewidget.providers.openai

import com.usagewidget.domain.ManualResetParser
import com.usagewidget.util.NyTime

/**
 * Fallback parser for chatgpt.com/codex/settings/usage inner text (and the older
 * settings/usage overview copy of 5-hour / weekly limits).
 */
object OpenAiHtmlExtractor {
    fun extract(pageText: String, fetchedAtMs: Long = System.currentTimeMillis()): OpenAiUsage {
        val text = pageText.replace('\u00a0', ' ')
        val five = section(text, "5-hour limit", fetchedAtMs, WindowKind.FIVE_HOUR, "5-hour")
            ?: section(text, "5-hour", fetchedAtMs, WindowKind.FIVE_HOUR, "5-hour")
        val weekly = section(text, "Weekly limit", fetchedAtMs, WindowKind.WEEKLY, "Weekly")
            ?: section(text, "Weekly", fetchedAtMs, WindowKind.WEEKLY, "Weekly")
        if (five == null && weekly == null) {
            throw IllegalArgumentException("Could not find ChatGPT 5-hour/weekly limits in page text")
        }
        val manual = ManualResetParser.fromHtml(text, fetchedAtMs)
        return OpenAiUsage(
            planLabel = null,
            fiveHour = five,
            weekly = weekly,
            extras = emptyList(),
            manualResets = manual,
            observedJson = manual?.observedJson
        )
    }

    private fun section(
        text: String,
        heading: String,
        fetchedAtMs: Long,
        kind: WindowKind,
        title: String
    ): OpenAiWindow? {
        val block = Regex(
            Regex.escape(heading) + """[\s\S]{0,240}?(?=(?:5-hour limit|Weekly limit|5-hour|Weekly|Recent plan usage|Credits|Usage limit resets|$))""",
            RegexOption.IGNORE_CASE
        ).find(text)?.value ?: return null

        val remaining = Regex("""(\d+(?:\.\d+)?)\s*%\s*left""", RegexOption.IGNORE_CASE)
            .find(block)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
        val usedExplicit = Regex("""(\d+(?:\.\d+)?)\s*%\s*used""", RegexOption.IGNORE_CASE)
            .find(block)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
        val used = usedExplicit ?: remaining?.let { 100.0 - it } ?: return null

        val relative = Regex("""Resets in\s+([0-9dhms\s]+)""", RegexOption.IGNORE_CASE)
            .find(block)
            ?.groupValues
            ?.get(1)
        val date = Regex("""Resets\s+([A-Za-z]{3,9}\s+\d{1,2}.*)$""", RegexOption.IGNORE_CASE)
            .find(block)
            ?.groupValues
            ?.get(1)
        val resetMs = relative?.let { NyTime.parseRelative("in $it", fetchedAtMs) }
            ?: date?.let { NyTime.parseTimestampString(it, fetchedAtMs) }

        return OpenAiWindow(
            title = title,
            kind = kind,
            slot = null,
            usedPercent = used,
            resetsAtEpochMs = resetMs
        )
    }
}
