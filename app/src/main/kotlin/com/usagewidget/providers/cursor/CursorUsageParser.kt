package com.usagewidget.providers.cursor

import com.usagewidget.domain.MeterBarKind
import com.usagewidget.domain.PacingPolicy
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
        val startMs = NyTime.parseTimestamp(
            root.str("billingCycleStart", "billing_cycle_start")
                ?: root.num("billingCycleStart", "billing_cycle_start"),
            fetchedAtMs
        )

        val planLabel = root.str("membershipType", "membership_type", "planName", "plan_name")
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        if (auto == null && other == null && resetMs == null) {
            throw IllegalArgumentException("Cursor usage-summary is missing plan percentages and reset date")
        }

        val onDemand = OnDemandSpend.fromUsageSummary(root)
        return CursorPlanUsage(
            planLabel = planLabel,
            cursorModelsUsedPercent = auto,
            otherModelsUsedPercent = other,
            resetsAtEpochMs = resetMs,
            windowStartEpochMs = startMs,
            onDemand = onDemand
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
        val startMs = NyTime.parseTimestamp(startRaw, fetchedAtMs)
        val resetMs = NyTime.withClockIfDateTruncated(
            nextEpochMs = NyTime.parseTimestamp(nextRaw, fetchedAtMs),
            clockEpochMs = startMs,
            nowMs = fetchedAtMs
        )

        val included = root.bool("hasNonZeroIncludedLimit", "has_non_zero_included_limit")
        val includedZero = root.bool("includedLimitZero", "included_limit_zero")
        val hasAllowance = when {
            included == true -> true
            includedZero == true -> false
            else -> true
        }

        val onDemandDisabled = root.obj("onDemandSettings", "on_demand_settings")?.bool("enabled") == false ||
            root.obj("onDemand", "on_demand")?.bool("enabled") == false
        return CursorGrokUsage(
            usedPercent = used,
            resetsAtEpochMs = resetMs,
            hasIncludedAllowance = hasAllowance,
            planLabel = root.str("grokPlanLabel", "grok_plan_label", "cursorPlanName", "cursor_plan_name"),
            windowStartEpochMs = startMs,
            onDemand = OnDemandSpend.fromSandUsage(root),
            onDemandDisabled = onDemandDisabled
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
                reset = plan.resetsAtEpochMs,
                windowStart = plan.windowStartEpochMs,
                windowLength = PacingPolicy.CURSOR_PERIOD_MS
            )
            meters += meter(
                id = "cursor.other",
                title = "Other Models",
                subtitle = "Named / API models",
                used = plan.otherModelsUsedPercent,
                reset = plan.resetsAtEpochMs,
                windowStart = plan.windowStartEpochMs,
                windowLength = PacingPolicy.CURSOR_PERIOD_MS
            )
            plan.onDemand?.toMeter("cursor.on_demand")?.let { meters += it }
            if (plan.resetsAtEpochMs != null) {
                meters += UsageMeter(
                    id = "cursor.plan_reset",
                    title = "Plan reset",
                    resetsAtEpochMs = plan.resetsAtEpochMs,
                    windowStartEpochMs = plan.windowStartEpochMs
                )
            }
        }
        if (grok != null && grok.hasIncludedAllowance) {
            meters += meter(
                id = "cursor.grok_bot",
                title = "Grok Bot",
                subtitle = "Weekly usage",
                used = grok.usedPercent,
                reset = grok.resetsAtEpochMs,
                windowStart = grok.windowStartEpochMs,
                windowLength = PacingPolicy.GROK_PERIOD_MS
            )
        }
        val grokOnDemand = if (grok?.onDemandDisabled == true) null else grok?.onDemand ?: plan?.onDemand
        grokOnDemand?.toMeter("cursor.grok_on_demand")?.let { meters += it }
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
        reset: Long?,
        windowStart: Long? = null,
        windowLength: Long? = null
    ): UsageMeter = UsageMeter(
        id = id,
        title = title,
        subtitle = subtitle,
        usedPercent = used,
        remainingPercent = Percent.remainingFromUsed(used),
        resetsAtEpochMs = reset,
        windowStartEpochMs = windowStart,
        windowLengthMs = windowLength
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
        return Percent.asWirePercent(value)
    }

    private fun percentFromMessage(message: String?): Double? {
        if (message.isNullOrBlank()) return null
        val match = Regex("""(\d+(?:\.\d+)?)\s*%""").find(message) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

}

data class CursorPlanUsage(
    val planLabel: String?,
    val cursorModelsUsedPercent: Double?,
    val otherModelsUsedPercent: Double?,
    val resetsAtEpochMs: Long?,
    val windowStartEpochMs: Long? = null,
    val onDemand: OnDemandSpend? = null
)

data class CursorGrokUsage(
    val usedPercent: Double,
    val resetsAtEpochMs: Long?,
    val hasIncludedAllowance: Boolean,
    val planLabel: String?,
    val windowStartEpochMs: Long? = null,
    val onDemand: OnDemandSpend? = null,
    val onDemandDisabled: Boolean = false
)

/**
 * On-demand spend vs a dollar cap. Cursor `usage-summary` documents
 * `individualUsage.onDemand.{used,limit,remaining}` in **cents** (live
 * captures: used 2309 with limit null; our fixture used 0 / limit null).
 * Hide when spend or limit is missing, or the limit is 0/unset.
 */
data class OnDemandSpend(
    val usedCents: Double,
    val limitCents: Double
) {
    val usedUsd: Double get() = usedCents / 100.0
    val limitUsd: Double get() = limitCents / 100.0
    val usedPercent: Double get() = (usedCents / limitCents * 100.0).coerceAtLeast(0.0)

    fun title(): String = "On-demand  ${formatUsd(usedUsd)} / ${formatUsd(limitUsd)}"

    fun toMeter(id: String): UsageMeter = UsageMeter(
        id = id,
        title = title(),
        subtitle = "Paid usage beyond included quota",
        usedPercent = usedPercent,
        remainingPercent = Percent.remainingFromUsed(usedPercent),
        barKind = MeterBarKind.CAP
    )

    companion object {
        fun fromUsageSummary(root: JsonObject): OnDemandSpend? {
            val individual = root.obj("individualUsage", "individual_usage")
                ?.obj("onDemand", "on_demand")
            val team = root.obj("teamUsage", "team_usage")
                ?.obj("onDemand", "on_demand")
            return fromBlock(individual) ?: fromBlock(team)
        }

        fun fromSandUsage(root: JsonObject): OnDemandSpend? {
            val settings = root.obj("onDemandSettings", "on_demand_settings")
            if (settings?.bool("enabled") == false) return null
            fromBlock(settings)?.let { return it }
            fromBlock(root.obj("onDemand", "on_demand"))?.let { return it }
            val used = cents(root, "onDemandUsed", "on_demand_used", "onDemandSpend", "on_demand_spend")
            val limit = cents(root, "onDemandLimit", "on_demand_limit", "onDemandCap", "on_demand_cap")
            return of(used, limit, enabled = null)
        }

        fun fromBlock(obj: JsonObject?): OnDemandSpend? {
            if (obj == null) return null
            val enabled = obj.bool("enabled")
            val used = cents(obj, "used", "spend", "spent", "totalSpend", "total_spend")
            val limit = cents(obj, "limit", "cap", "spendLimit", "spend_limit")
            val remaining = cents(obj, "remaining")
            val spend = used ?: if (limit != null && remaining != null) (limit - remaining).coerceAtLeast(0.0) else null
            return of(spend, limit, enabled)
        }

        fun of(usedCents: Double?, limitCents: Double?, enabled: Boolean?): OnDemandSpend? {
            if (enabled == false) return null
            if (usedCents == null || limitCents == null) return null
            if (!usedCents.isFinite() || !limitCents.isFinite()) return null
            if (limitCents <= 0.0) return null
            return OnDemandSpend(usedCents = usedCents.coerceAtLeast(0.0), limitCents = limitCents)
        }

        fun formatUsd(dollars: Double): String {
            val cents = kotlin.math.round(dollars * 100.0).toLong()
            return if (cents % 100L == 0L) "$${cents / 100L}"
            else "$" + "%.2f".format(java.util.Locale.US, dollars)
        }

        private fun cents(obj: JsonObject, vararg names: String): Double? {
            val value = obj.num(*names) ?: obj.str(*names)?.toDoubleOrNull() ?: return null
            if (!value.isFinite()) return null
            return value
        }
    }
}
