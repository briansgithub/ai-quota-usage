package com.usagewidget.providers.cursor

import com.usagewidget.util.NyTime
import java.util.Locale

/**
 * Fallback parser for the Cursor spending page inner text.
 * Used only when the JSON endpoints fail.
 */
object CursorHtmlExtractor {
    fun extract(pageText: String, fetchedAtMs: Long = System.currentTimeMillis()): CursorHtmlUsage {
        val text = pageText.replace('\u00a0', ' ')
        val cursorModels = percentUsedNear(text, "Cursor Models")
        val otherModels = percentUsedNear(text, "Other Models")
        val grok = percentUsedNear(text, "Grok Bot")
            ?: percentUsedNear(text, "Weekly usage")

        val planReset = Regex(
            """Usage limits reset on\s+([A-Za-z]{3,9}\s+\d{1,2})""",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.get(1)
            ?.let { NyTime.parseMonthDay(it, fetchedAtMs) }
            ?: Regex("""reset on\s+([A-Za-z]{3,9}\s+\d{1,2})""", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.let { NyTime.parseMonthDay(it, fetchedAtMs) }

        val grokReset = Regex(
            """Grok Bot[\s\S]{0,400}?Resets\s+([A-Za-z]{3,9}\s+\d{1,2})""",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.get(1)
            ?.let { NyTime.parseMonthDay(it, fetchedAtMs) }
            ?: Regex("""Weekly usage[\s\S]{0,200}?Resets\s+([A-Za-z]{3,9}\s+\d{1,2})""", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.let { NyTime.parseMonthDay(it, fetchedAtMs) }

        val planLabel = Regex("""\b(Pro\+|Ultra|Business|Enterprise|Pro|Free|Hobby)\b""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value

        if (cursorModels == null && otherModels == null && grok == null) {
            throw IllegalArgumentException("Could not find Cursor usage percentages in page text")
        }

        val onDemand = onDemandFromHtml(text)
        return CursorHtmlUsage(
            plan = CursorPlanUsage(
                planLabel = planLabel,
                cursorModelsUsedPercent = cursorModels,
                otherModelsUsedPercent = otherModels,
                resetsAtEpochMs = planReset,
                onDemand = onDemand
            ),
            grok = grok?.let {
                CursorGrokUsage(
                    usedPercent = it,
                    resetsAtEpochMs = grokReset,
                    hasIncludedAllowance = true,
                    planLabel = planLabel,
                    onDemand = onDemand
                )
            }
        )
    }

    private fun onDemandFromHtml(text: String): OnDemandSpend? {
        if (Regex("""on[- ]demand[\s\S]{0,220}disabled""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return null
        }
        val pair = Regex(
            """(?i)on[- ]demand[\s\S]{0,500}?\$(\d+(?:\.\d{1,2})?)\s*(?:/|of)\s*\$(\d+(?:\.\d{1,2})?)(?!\s*/\s*mo)"""
        ).find(text) ?: return null
        val used = pair.groupValues[1].toDoubleOrNull() ?: return null
        val limit = pair.groupValues[2].toDoubleOrNull() ?: return null
        return OnDemandSpend.of(used * 100.0, limit * 100.0, enabled = true)
    }

    private fun percentUsedNear(text: String, heading: String): Double? {
        val pattern = Regex(
            Regex.escape(heading) + """[\s\S]{0,280}?(\d+(?:\.\d+)?)\s*%\s*used""",
            RegexOption.IGNORE_CASE
        )
        return pattern.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}

data class CursorHtmlUsage(
    val plan: CursorPlanUsage,
    val grok: CursorGrokUsage?
)
