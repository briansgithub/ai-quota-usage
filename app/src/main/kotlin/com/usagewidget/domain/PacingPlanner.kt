package com.usagewidget.domain

import com.usagewidget.util.NyTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Use-it-or-lose-it pacing.
 *
 * Each 5-hour window can burn about P% of weekly usage: learned per model from usage
 * ([RatioLearner]), else the manual setting (default [PacingPolicy.DEFAULT_PERCENT_PER_WINDOW]%). Windows needed `N = ceil(R / P)`. Working backwards from the
 * weekly reset, the latest start for the k-th-from-last needed window is
 * `weeklyReset - k * 5h`. Slack is time until weekly reset minus the time those
 * windows occupy, after waiting out an already-started 5h window.
 *
 * **Codex manual resets:** assumed to restore a full weekly bar plus a fresh 5-hour
 * window (`Full reset (Weekly + 5 hr)`). Burning that restored 100% still takes
 * `ceil(100 / P)` sequential 5h windows before `expires_at`.
 */
object PacingPolicy {
    const val WINDOW_MS = 5L * 60 * 60 * 1000
    const val DEFAULT_PERCENT_PER_WINDOW = 12.0
    const val MIN_PERCENT_PER_WINDOW = 5.0
    const val MAX_PERCENT_PER_WINDOW = 25.0
    const val COMFORTABLE_LEAD_MS = 80L * 60 * 1000
    const val FEASIBLE_LEAD_MS = 15L * 60 * 1000
    const val ACTIVE_MIN_REMAINING = 1.0
    const val AVAILABLE_MIN_REMAINING = 99.0
    const val WEEKLY_WASTE_BUFFER = 10.0
    const val GROK_PERIOD_MS = 7L * 24 * 60 * 60 * 1000
    const val CURSOR_PERIOD_MS = 30L * 24 * 60 * 60 * 1000
    const val MANUAL_FULL_RESET_PERCENT = 100.0
    const val TIGHT_SLACK_MS = 8L * 60 * 60 * 1000
    const val CRITICAL_SLACK_MS = 2L * 60 * 60 * 1000
    const val REMAINING_EPS = 0.5

    /** Manual setting (starting guess) range. */
    fun clampPercentPerWindow(value: Double): Double =
        value.coerceIn(MIN_PERCENT_PER_WINDOW, MAX_PERCENT_PER_WINDOW)

    /** Range for any rate the planner uses, manual or learned ([RatioLearner]). */
    fun clampRate(value: Double): Double =
        value.coerceIn(RatioLearner.LEARNED_MIN, RatioLearner.LEARNED_MAX)
}

enum class PaceKind { FIVE_AND_WEEKLY, WEEKLY_ONLY }

enum class PaceUrgency { OK, TIGHT, CRITICAL, IMPOSSIBLE }

enum class FiveHourWindowState { AVAILABLE, ACTIVE, EXHAUSTED, UNKNOWN }

data class ManualResetPace(
    val expiresAtEpochMs: Long?,
    val windowsNeeded: Int,
    val timeNeededMs: Long,
    val willExpireUnused: Boolean,
    val unusedPercent: Double,
    val warning: String?
)

data class PacingResult(
    val kind: PaceKind,
    val sectionId: String,
    val weeklyRemaining: Double,
    val weeklyResetEpochMs: Long,
    val percentPerWindow: Double,
    val windowsNeeded: Int,
    val extraWindowsNeeded: Int,
    val currentWindowDeliverable: Double,
    val fiveHourState: FiveHourWindowState,
    val slackMs: Long,
    val latestStarts: List<Long>,
    val nextLatestStartEpochMs: Long?,
    val comfortableStartEpochMs: Long?,
    val feasibleStartEpochMs: Long?,
    val missedWindow: Boolean,
    val weeklyLostPercent: Double,
    val line: String,
    val urgency: PaceUrgency,
    val manuals: List<ManualResetPace> = emptyList(),
    val wasteGap: Double = 0.0,
    val shouldWarnUnused: Boolean = false
)

object PacingPlanner {
    fun windowsNeeded(remaining: Double, percentPerWindow: Double): Int {
        val p = PacingPolicy.clampRate(percentPerWindow)
        if (remaining <= PacingPolicy.REMAINING_EPS || p <= 0.0) return 0
        return ceil(remaining / p).toInt()
    }

    fun latestStarts(weeklyResetEpochMs: Long, extraWindows: Int): List<Long> {
        if (extraWindows <= 0) return emptyList()
        return (extraWindows downTo 1).map { k ->
            weeklyResetEpochMs - k * PacingPolicy.WINDOW_MS
        }
    }

    fun fiveHourState(remaining: Double?): FiveHourWindowState {
        if (remaining == null) return FiveHourWindowState.UNKNOWN
        return when {
            remaining >= PacingPolicy.AVAILABLE_MIN_REMAINING -> FiveHourWindowState.AVAILABLE
            remaining >= PacingPolicy.ACTIVE_MIN_REMAINING -> FiveHourWindowState.ACTIVE
            else -> FiveHourWindowState.EXHAUSTED
        }
    }

    fun planFiveAndWeekly(
        weeklyRemaining: Double,
        weeklyResetEpochMs: Long,
        fiveHourRemaining: Double?,
        fiveHourResetEpochMs: Long?,
        nowMs: Long,
        percentPerWindow: Double = PacingPolicy.DEFAULT_PERCENT_PER_WINDOW,
        manuals: List<ManualResetCredit> = emptyList(),
        sectionId: String = SoftwareSectionId.CODEX.wireName
    ): PacingResult {
        val p = PacingPolicy.clampRate(percentPerWindow)
        val state = fiveHourState(fiveHourRemaining)
        val currentDeliverable = when (state) {
            FiveHourWindowState.ACTIVE -> {
                val five = fiveHourRemaining ?: 0.0
                minOf(weeklyRemaining, (five / 100.0) * p)
            }
            else -> 0.0
        }
        val remainingAfterCurrent = max(0.0, weeklyRemaining - currentDeliverable)
        val extraWindows = windowsNeeded(remainingAfterCurrent, p)
        val waitMs = when (state) {
            FiveHourWindowState.ACTIVE, FiveHourWindowState.EXHAUSTED ->
                max(0L, (fiveHourResetEpochMs ?: nowMs) - nowMs)
            else -> 0L
        }
        val timeUntilWeekly = weeklyResetEpochMs - nowMs
        val slackMs = timeUntilWeekly - waitMs - extraWindows * PacingPolicy.WINDOW_MS
        val starts = latestStarts(weeklyResetEpochMs, extraWindows)
        val nextLatest = starts.firstOrNull()
        val missed = extraWindows > 0 &&
            nextLatest != null &&
            nowMs > nextLatest &&
            state == FiveHourWindowState.AVAILABLE

        val earliestStart = nowMs + waitMs
        val maxFit = floor(
            ((weeklyResetEpochMs - earliestStart).coerceAtLeast(0).toDouble()) /
                PacingPolicy.WINDOW_MS.toDouble()
        ).toInt()
        val lost = max(0.0, remainingAfterCurrent - maxFit * p)
        val urgency = urgencyOf(slackMs, lost)
        val manualsPace = planManuals(manuals, nowMs, p)
        val line = formatFiveLine(state, extraWindows, slackMs, lost)

        return PacingResult(
            kind = PaceKind.FIVE_AND_WEEKLY,
            sectionId = sectionId,
            weeklyRemaining = weeklyRemaining,
            weeklyResetEpochMs = weeklyResetEpochMs,
            percentPerWindow = p,
            windowsNeeded = extraWindows + if (state == FiveHourWindowState.ACTIVE) 1 else 0,
            extraWindowsNeeded = extraWindows,
            currentWindowDeliverable = currentDeliverable,
            fiveHourState = state,
            slackMs = slackMs,
            latestStarts = starts,
            nextLatestStartEpochMs = nextLatest,
            comfortableStartEpochMs = nextLatest?.minus(PacingPolicy.COMFORTABLE_LEAD_MS),
            feasibleStartEpochMs = nextLatest?.minus(PacingPolicy.FEASIBLE_LEAD_MS),
            missedWindow = missed,
            weeklyLostPercent = lost,
            line = line,
            urgency = urgency,
            manuals = manualsPace
        )
    }

    fun planWeeklyOnly(
        remaining: Double,
        resetAtEpochMs: Long,
        nowMs: Long,
        periodMs: Long,
        sectionId: String
    ): PacingResult {
        val timeLeft = resetAtEpochMs - nowMs
        val period = max(periodMs, timeLeft).coerceAtLeast(1L)
        val even = 100.0 * (timeLeft.toDouble() / period.toDouble()).coerceIn(0.0, 1.0)
        val wasteGap = remaining - even
        val shouldWarn = wasteGap >= PacingPolicy.WEEKLY_WASTE_BUFFER
        val urgency = when {
            wasteGap >= 25.0 -> PaceUrgency.CRITICAL
            shouldWarn -> PaceUrgency.TIGHT
            else -> PaceUrgency.OK
        }
        val line = if (shouldWarn) {
            "Pace: ${roundPct(remaining)}% left · ${roundPct(wasteGap)}% above even pace"
        } else {
            "Pace: ${roundPct(remaining)}% left, ${formatDur(timeLeft)}"
        }
        return PacingResult(
            kind = PaceKind.WEEKLY_ONLY,
            sectionId = sectionId,
            weeklyRemaining = remaining,
            weeklyResetEpochMs = resetAtEpochMs,
            percentPerWindow = PacingPolicy.DEFAULT_PERCENT_PER_WINDOW,
            windowsNeeded = 0,
            extraWindowsNeeded = 0,
            currentWindowDeliverable = 0.0,
            fiveHourState = FiveHourWindowState.UNKNOWN,
            slackMs = timeLeft,
            latestStarts = emptyList(),
            nextLatestStartEpochMs = null,
            comfortableStartEpochMs = null,
            feasibleStartEpochMs = null,
            missedWindow = false,
            weeklyLostPercent = if (shouldWarn) wasteGap else 0.0,
            line = line,
            urgency = urgency,
            wasteGap = wasteGap,
            shouldWarnUnused = shouldWarn
        )
    }

    fun planManuals(
        credits: List<ManualResetCredit>,
        nowMs: Long,
        percentPerWindow: Double = PacingPolicy.DEFAULT_PERCENT_PER_WINDOW
    ): List<ManualResetPace> {
        val p = PacingPolicy.clampRate(percentPerWindow)
        val windows = windowsNeeded(PacingPolicy.MANUAL_FULL_RESET_PERCENT, p)
        val timeNeeded = windows * PacingPolicy.WINDOW_MS
        return credits.filter { it.isAvailable }.map { credit ->
            val expires = credit.expiresAtEpochMs
            if (expires == null) {
                ManualResetPace(
                    expiresAtEpochMs = null,
                    windowsNeeded = windows,
                    timeNeededMs = timeNeeded,
                    willExpireUnused = false,
                    unusedPercent = 0.0,
                    warning = null
                )
            } else {
                val timeLeft = expires - nowMs
                val maxFit = floor(
                    timeLeft.coerceAtLeast(0).toDouble() / PacingPolicy.WINDOW_MS.toDouble()
                ).toInt()
                val unused = max(0.0, 100.0 - maxFit * p)
                val willExpire = timeLeft < timeNeeded && unused > PacingPolicy.REMAINING_EPS
                ManualResetPace(
                    expiresAtEpochMs = expires,
                    windowsNeeded = windows,
                    timeNeededMs = timeNeeded,
                    willExpireUnused = willExpire,
                    unusedPercent = unused,
                    warning = if (willExpire) {
                        "Manual reset expires before it can be used (~${roundPct(unused)}% weekly unused)"
                    } else {
                        null
                    }
                )
            }
        }
    }

    fun forSection(
        section: SoftwareSection,
        nowMs: Long,
        percentPerWindow: Double = PacingPolicy.DEFAULT_PERCENT_PER_WINDOW
    ): PacingResult? = forSection(section, nowMs, PaceRates.fixed(percentPerWindow))

    /** [rates] gives each model's learned weekly % per 5-hour window ([RatioLearner]). */
    fun forSection(
        section: SoftwareSection,
        nowMs: Long,
        rates: PaceRates
    ): PacingResult? {
        return when (section.id) {
            SoftwareSectionId.GEMINI -> null // Quota bars have pace ticks; no generic reminder schedule.
            SoftwareSectionId.CODEX -> {
                val five = section.meters.find { it.id == "openai.five_hour" }
                val weekly = section.meters.find { it.id == "openai.weekly" }
                val remaining = weekly.remainingAmount() ?: return null
                val resetAt = weekly?.resetsAtEpochMs ?: return null
                planFiveAndWeekly(
                    weeklyRemaining = remaining,
                    weeklyResetEpochMs = resetAt,
                    fiveHourRemaining = five.remainingAmount(),
                    fiveHourResetEpochMs = five?.resetsAtEpochMs,
                    nowMs = nowMs,
                    percentPerWindow = rates.rateFor("codex"),
                    manuals = section.manualResets?.credits.orEmpty(),
                    sectionId = section.id.wireName
                )
            }
            SoftwareSectionId.CLAUDE -> {
                val five = section.meters.find { it.id == "claude.five_hour" }
                val weekly = section.meters.find { it.id == "claude.weekly" }
                val remaining = weekly.remainingAmount() ?: return null
                val resetAt = weekly?.resetsAtEpochMs ?: return null
                planFiveAndWeekly(
                    weeklyRemaining = remaining,
                    weeklyResetEpochMs = resetAt,
                    fiveHourRemaining = five.remainingAmount(),
                    fiveHourResetEpochMs = five?.resetsAtEpochMs,
                    nowMs = nowMs,
                    percentPerWindow = rates.rateFor("claude"),
                    manuals = section.manualResets?.credits.orEmpty(),
                    sectionId = section.id.wireName
                )
            }
            SoftwareSectionId.GROK_BOT -> {
                val meter = section.meters.find { it.id == "cursor.grok_bot" }
                    ?: section.meters.firstOrNull()
                val remaining = meter.remainingAmount() ?: return null
                val resetAt = meter?.resetsAtEpochMs ?: return null
                planWeeklyOnly(
                    remaining = remaining,
                    resetAtEpochMs = resetAt,
                    nowMs = nowMs,
                    periodMs = PacingPolicy.GROK_PERIOD_MS,
                    sectionId = section.id.wireName
                )
            }
            SoftwareSectionId.CURSOR -> {
                val models = section.meters.find { it.id == "cursor.models" }
                val plan = section.meters.find { it.id == "cursor.plan_reset" }
                val remaining = models.remainingAmount() ?: return null
                val resetAt = plan?.resetsAtEpochMs ?: models?.resetsAtEpochMs ?: return null
                planWeeklyOnly(
                    remaining = remaining,
                    resetAtEpochMs = resetAt,
                    nowMs = nowMs,
                    periodMs = PacingPolicy.CURSOR_PERIOD_MS,
                    sectionId = section.id.wireName
                )
            }
        }
    }

    private fun formatFiveLine(
        state: FiveHourWindowState,
        extraWindows: Int,
        slackMs: Long,
        lost: Double
    ): String {
        val slackPart = if (slackMs >= 0L) {
            "${formatDur(slackMs)} slack"
        } else {
            "${formatDur(-slackMs)} short"
        }
        if (lost > PacingPolicy.REMAINING_EPS) {
            val n = extraWindows + if (state == FiveHourWindowState.ACTIVE) 1 else 0
            val label = if (n == 1) "window" else "windows"
            return "Pace: need $n $label, ~${roundPct(lost)}% likely lost"
        }
        if (extraWindows == 0 && state != FiveHourWindowState.ACTIVE) {
            return "Pace: on track"
        }
        if (extraWindows == 0 && state == FiveHourWindowState.ACTIVE) {
            return "Pace: finish this window, $slackPart"
        }
        val label = if (extraWindows == 1) "window" else "windows"
        return if (state == FiveHourWindowState.ACTIVE) {
            "Pace: need $extraWindows more, $slackPart"
        } else {
            "Pace: need $extraWindows $label, $slackPart"
        }
    }

    private fun urgencyOf(slackMs: Long, lost: Double): PaceUrgency = when {
        lost > PacingPolicy.REMAINING_EPS || slackMs < 0L -> PaceUrgency.IMPOSSIBLE
        slackMs <= PacingPolicy.CRITICAL_SLACK_MS -> PaceUrgency.CRITICAL
        slackMs <= PacingPolicy.TIGHT_SLACK_MS -> PaceUrgency.TIGHT
        else -> PaceUrgency.OK
    }

    private fun roundPct(value: Double): Int = value.roundToInt().coerceAtLeast(0)

    private fun formatDur(ms: Long): String = NyTime.formatDuration(ms)

    private fun UsageMeter?.remainingAmount(): Double? {
        if (this == null) return null
        return remainingPercent ?: usedPercent?.let { (100.0 - it).coerceAtLeast(0.0) }
    }
}
