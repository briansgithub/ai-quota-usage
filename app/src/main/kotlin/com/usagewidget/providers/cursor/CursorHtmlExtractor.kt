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

        return CursorHtmlUsage(
            plan = CursorPlanUsage(
                planLabel = planLabel,
                cursorModelsUsedPercent = cursorModels,
                otherModelsUsedPercent = otherModels,
                resetsAtEpochMs = planReset
            ),
            grok = grok?.let {
                CursorGrokUsage(
                    usedPercent = it,
                    resetsAtEpochMs = grokReset,
                    hasIncludedAllowance = true,
                    planLabel = planLabel
                )
            }
        )
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
