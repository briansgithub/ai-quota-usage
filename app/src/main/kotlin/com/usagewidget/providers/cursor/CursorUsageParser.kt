package com.usagewidget.providers.cursor

import com.usagewidget.domain.Percent
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.ProviderSnapshot
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.UsageMeter
import com.usagewidget.util.JsonNav
import com.usagewidget.util.NyTime
import com.usagewidget.util.bool
import com.usagewidget.util.num
import com.usagewidget.util.obj
import com.usagewidget.util.str
import kotlinx.serialization.json.JsonObject

object CursorUsageParser {
    fun parseUsageSummary(
        raw: String,
        fetchedAtMs: Long = System.currentTimeMillis()
    ): CursorPlanUsage {
        val root = JsonNav.parseObject(raw)
        val plan = root.obj("individualUsage", "individual_usage")
            ?.obj("plan")
            ?: root.obj("planUsage", "plan_usage")
            ?: root.obj("plan")

        val auto = readPercent(
            plan,
            "autoPercentUsed",
            "auto_percent_used",
            "cursorModelsPercentUsed"
        ) ?: percentFromMessage(
            root.str(
                "autoModelSelectedDisplayMessage",
                "auto_model_selected_display_message"
            )
        )

        val other = readPercent(
            plan,
            "apiPercentUsed",
            "api_percent_used",
            "namedPercentUsed",
            "named_percent_used"
        ) ?: percentFromMessage(
            root.str(
                "namedModelSelectedDisplayMessage",
                "named_model_selected_display_message"
            )
        )

        val resetMs = NyTime.parseTimestamp(
            root.str("billingCycleEnd", "billing_cycle_end", "includedUsageResetsAt")
                ?: root.num("billingCycleEnd", "billing_cycle_end"),
            fetchedAtMs
        )

        val planLabel = root.str("membershipType", "membership_type", "planName", "plan_name")
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        if (auto == null && other == null && resetMs == null) {
            throw IllegalArgumentException("Cursor usage-summary is missing plan percentages and reset date")
        }

        return CursorPlanUsage(
            planLabel = planLabel,
            cursorModelsUsedPercent = auto,
            otherModelsUsedPercent = other,
            resetsAtEpochMs = resetMs
        )
    }

    fun parseSandUsage(
        raw: String,
        fetchedAtMs: Long = System.currentTimeMillis()
    ): CursorGrokUsage {
        val root = JsonNav.parseObject(raw)
        val used = readPercent(
            root,
            "usagePercent",
            "usage_percent"
        ) ?: throw IllegalArgumentException("Grok Bot payload is missing usagePercent")

        val nextRaw = timestampField(
            root,
            "nextResetTimestampUtc",
            "next_reset_timestamp_utc",
            "nextResetMs",
            "next_reset_ms",
            "nextResetAt",
            "next_reset_at",
            "resetsAt",
            "resets_at"
        )
        val startRaw = timestampField(
            root,
            "currentPeriodStart",
            "current_period_start"
        )
        val resetMs = NyTime.withClockIfDateTruncated(
            nextEpochMs = NyTime.parseTimestamp(nextRaw, fetchedAtMs),
            clockEpochMs = NyTime.parseTimestamp(startRaw, fetchedAtMs)
        )

        val included = root.bool("hasNonZeroIncludedLimit", "has_non_zero_included_limit")
        val includedZero = root.bool("includedLimitZero", "included_limit_zero")
        val hasAllowance = when {
            included == true -> true
            includedZero == true -> false
            else -> true
        }

        return CursorGrokUsage(
            usedPercent = used,
            resetsAtEpochMs = resetMs,
            hasIncludedAllowance = hasAllowance,
            planLabel = root.str("grokPlanLabel", "grok_plan_label", "cursorPlanName", "cursor_plan_name")
        )
    }

    fun toSnapshot(
        plan: CursorPlanUsage?,
        grok: CursorGrokUsage?,
        planError: String? = null,
        grokError: String? = null,
        source: String = "json"
    ): ProviderSnapshot {
        val meters = mutableListOf<UsageMeter>()
        if (plan != null) {
            meters += meter(
                id = "cursor.models",
                title = "Cursor Models",
                subtitle = "Includes Cursor Grok and Composer",
                used = plan.cursorModelsUsedPercent,
                reset = plan.resetsAtEpochMs
            )
            meters += meter(
                id = "cursor.other",
                title = "Other Models",
                subtitle = "Named / API models",
                used = plan.otherModelsUsedPercent,
                reset = plan.resetsAtEpochMs
            )
            if (plan.resetsAtEpochMs != null) {
                meters += UsageMeter(
                    id = "cursor.plan_reset",
                    title = "Plan reset",
                    resetsAtEpochMs = plan.resetsAtEpochMs
                )
            }
        }
        if (grok != null && grok.hasIncludedAllowance) {
            meters += meter(
                id = "cursor.grok_bot",
                title = "Grok Bot",
                subtitle = "Weekly usage",
                used = grok.usedPercent,
                reset = grok.resetsAtEpochMs
            )
        }
        val errors = listOfNotNull(
            planError?.let { "Plan: $it" },
            grokError?.let { "Grok Bot: $it" }
        )
        val status = when {
            meters.isNotEmpty() -> ProviderStatus.OK
            planError != null || grokError != null -> ProviderStatus.ERROR
            else -> ProviderStatus.ERROR
        }
        return ProviderSnapshot(
            id = ProviderId.CURSOR.wireName,
            displayName = ProviderId.CURSOR.displayName,
            status = status.name,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            source = source,
            planLabel = plan?.planLabel ?: grok?.planLabel,
            meters = meters
        )
    }

    private fun meter(
        id: String,
        title: String,
        subtitle: String,
        used: Double?,
        reset: Long?
    ): UsageMeter = UsageMeter(
        id = id,
        title = title,
        subtitle = subtitle,
        usedPercent = used,
        remainingPercent = Percent.remainingFromUsed(used),
        resetsAtEpochMs = reset
    )

    private fun timestampField(obj: JsonObject, vararg names: String): Any? {
        val element = JsonNav.field(obj, *names) ?: return null
        JsonNav.long(element)?.let { return it }
        JsonNav.num(element)?.let { return it }
        return JsonNav.str(element)
    }

    private fun readPercent(obj: JsonObject?, vararg names: String): Double? {
        if (obj == null) return null
        val value = obj.num(*names) ?: return null
        return normalizePercent(value)
    }

    private fun percentFromMessage(message: String?): Double? {
        if (message.isNullOrBlank()) return null
        val match = Regex("""(\d+(?:\.\d+)?)\s*%""").find(message) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    private fun normalizePercent(value: Double): Double {
        if (!value.isFinite()) return 0.0
        // Some payloads may emit 0.17 instead of 17. Treat (0,1) as a fraction,
        // but keep 0 and 1 as 0% / 1% because dashboards use whole percents.
        return if (value > 0.0 && value < 1.0) value * 100.0 else value
    }
}

data class CursorPlanUsage(
    val planLabel: String?,
    val cursorModelsUsedPercent: Double?,
    val otherModelsUsedPercent: Double?,
    val resetsAtEpochMs: Long?
)

data class CursorGrokUsage(
    val usedPercent: Double,
    val resetsAtEpochMs: Long?,
    val hasIncludedAllowance: Boolean,
    val planLabel: String?
)
