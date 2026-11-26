package com.usagewidget.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.pow

/**
 * One 5-hour window's worth of observed usage for one model: how much 5-hour quota (points
 * of %) and how much of the same model's longer quota (weekly) went up while both windows
 * stayed the same. Matches Windows `PaceSample`.
 */
@Serializable
data class PaceSample(
    val pair: String,
    val fiveResetMs: Long,
    val longResetMs: Long,
    val fiveDelta: Double,
    val longDelta: Double,
    val updatedAtMs: Long
)

/** A model with a 5-hour limit and a longer limit whose ratio is learned. */
data class PacePair(
    val key: String,
    val label: String,
    val provider: ProviderId,
    val section: SoftwareSectionId,
    val fiveId: String,
    val longId: String
)

data class PaceEstimate(
    val pair: String,
    val label: String,
    val rate: Double,
    val observedFivePoints: Double,
    val samples: Int
)

/** Per-model "weekly % per full 5-hour window", falling back to the manual value. */
data class PaceRates(
    val manual: Double = PacingPolicy.DEFAULT_PERCENT_PER_WINDOW,
    val learned: Map<String, Double> = emptyMap()
) {
    fun rateFor(pairKey: String): Double = learned[pairKey] ?: manual

    companion object {
        fun fixed(percent: Double) = PaceRates(percent)
    }
}

/**
 * Learns, per model, how much of the longer limit one full 5-hour window uses.
 *
 * Each refresh compares the previous and current snapshot. While neither the 5-hour nor the
 * longer window rolled over (no reset, no manual reset), the increases of both bars are added
 * to one sample per 5-hour window. The estimate is a ratio of sums, `100 · Σw·Δlong / Σw·Δ5h`,
 * so windows with more 5-hour use dominate (rounding noise of whole-% bars averages out), with
 * `w` halving every 7 days and doubled for the current long window. A prior of
 * [PRIOR_FIVE_POINTS] pseudo-points at the manual rate keeps early estimates near it.
 */
object RatioLearner {
    const val PRIOR_FIVE_POINTS = 50.0
    const val HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_AGE_MS = 28L * 24 * 60 * 60 * 1000
    const val MIN_FIVE_DELTA = 3.0
    const val MAX_SAMPLES_PER_PAIR = 60
    const val CURRENT_LONG_BOOST = 2.0
    const val LEARNED_MIN = 2.0
    const val LEARNED_MAX = 60.0
    const val SAME_WINDOW_TOLERANCE_MS = 10L * 60 * 1000

    private const val CODEX_EXTRA_FIVE = " · 5-hour"
    private const val CODEX_EXTRA_LONG = " · Weekly"

    private val fixedPairs = listOf(
        PacePair("codex", "Codex", ProviderId.OPENAI, SoftwareSectionId.CODEX, "openai.five_hour", "openai.weekly"),
        PacePair("claude", "Claude", ProviderId.CLAUDE, SoftwareSectionId.CLAUDE, "claude.five_hour", "claude.weekly"),
        PacePair("gemini:main", "Gemini", ProviderId.GEMINI, SoftwareSectionId.GEMINI, "gemini.gemini-5h", "gemini.gemini-weekly"),
        PacePair("gemini:3p", "Gemini 3rd-party", ProviderId.GEMINI, SoftwareSectionId.GEMINI, "gemini.3p-5h", "gemini.3p-weekly")
    )

    /** Fixed pairs plus Codex extra models ("Name · 5-hour" with "Name · Weekly"). */
    fun pairs(snapshot: UsageSnapshot): List<PacePair> {
        val codex = snapshot.provider(ProviderId.OPENAI)?.meters.orEmpty()
        val extras = codex.filter { it.title.endsWith(CODEX_EXTRA_FIVE) }.mapNotNull { five ->
            val name = five.title.removeSuffix(CODEX_EXTRA_FIVE)
            val long = codex.firstOrNull { it.title == name + CODEX_EXTRA_LONG } ?: return@mapNotNull null
            PacePair("codex:$name", "Codex $name", ProviderId.OPENAI, SoftwareSectionId.CODEX, five.id, long.id)
        }
        return fixedPairs + extras
    }

    /** Adds this refresh's usage to [samples], then prunes. Returns the new list. */
    fun observe(samples: List<PaceSample>, previous: UsageSnapshot, current: UsageSnapshot, nowMs: Long): List<PaceSample> {
        val result = samples.toMutableList()
        for (pair in pairs(current)) {
            if (current.health(pair.section).lastSuccessEpochMs <= previous.health(pair.section).lastSuccessEpochMs) continue
            val prevFive = previous.meter(pair.provider, pair.fiveId) ?: continue
            val prevLong = previous.meter(pair.provider, pair.longId) ?: continue
            val curFive = current.meter(pair.provider, pair.fiveId) ?: continue
            val curLong = current.meter(pair.provider, pair.longId) ?: continue
            val fiveReset = curFive.resetsAtEpochMs ?: continue
            val longReset = curLong.resetsAtEpochMs ?: continue
            if (!sameWindow(prevFive, curFive) || !sameWindow(prevLong, curLong)) continue
            val dFive = (used(curFive) ?: continue) - (used(prevFive) ?: continue)
            val dLong = (used(curLong) ?: continue) - (used(prevLong) ?: continue)
            // A drop means a reset the window identity didn't show (e.g. a manual reset): skip.
            if (dFive < -1.0 || dLong < -1.0) continue
            if (dFive <= 0.0 && dLong <= 0.0) continue
            val index = result.indexOfFirst {
                it.pair == pair.key && abs(it.fiveResetMs - fiveReset) <= SAME_WINDOW_TOLERANCE_MS
            }
            val add = dFive.coerceAtLeast(0.0) to dLong.coerceAtLeast(0.0)
            if (index >= 0) {
                val s = result[index]
                result[index] = s.copy(
                    fiveResetMs = fiveReset,
                    longResetMs = longReset,
                    fiveDelta = s.fiveDelta + add.first,
                    longDelta = s.longDelta + add.second,
                    updatedAtMs = nowMs
                )
            } else {
                result += PaceSample(pair.key, fiveReset, longReset, add.first, add.second, nowMs)
            }
        }
        return prune(result, nowMs)
    }

    fun prune(samples: List<PaceSample>, nowMs: Long): List<PaceSample> =
        samples.filter { nowMs - it.updatedAtMs <= MAX_AGE_MS }
            .groupBy { it.pair }
            .values
            .flatMap { it.sortedByDescending { s -> s.updatedAtMs }.take(MAX_SAMPLES_PER_PAIR) }

    fun estimate(
        samples: List<PaceSample>,
        pair: PacePair,
        manual: Double,
        currentLongResetMs: Long?,
        nowMs: Long
    ): PaceEstimate {
        var weightedFive = 0.0
        var weightedLong = 0.0
        var observed = 0.0
        var count = 0
        for (s in samples) {
            if (s.pair != pair.key || s.fiveDelta < MIN_FIVE_DELTA) continue
            val age = (nowMs - s.updatedAtMs).coerceAtLeast(0L)
            var w = 0.5.pow(age.toDouble() / HALF_LIFE_MS)
            if (currentLongResetMs != null && abs(s.longResetMs - currentLongResetMs) <= SAME_WINDOW_TOLERANCE_MS) {
                w *= CURRENT_LONG_BOOST
            }
            weightedFive += w * s.fiveDelta
            weightedLong += w * s.longDelta
            observed += s.fiveDelta
            count++
        }
        val prior = manual / 100.0 * PRIOR_FIVE_POINTS
        val rate = 100.0 * (weightedLong + prior) / (weightedFive + PRIOR_FIVE_POINTS)
        return PaceEstimate(pair.key, pair.label, rate.coerceIn(LEARNED_MIN, LEARNED_MAX), observed, count)
    }

    /** Estimates for every known pair (also ones with no samples yet). */
    fun estimates(samples: List<PaceSample>, snapshot: UsageSnapshot, manual: Double, nowMs: Long): List<PaceEstimate> =
        pairs(snapshot).map { pair ->
            estimate(samples, pair, manual, snapshot.meter(pair.provider, pair.longId)?.resetsAtEpochMs, nowMs)
        }

    /** Learned rates for pairs that have at least one usable sample. */
    fun learnedRates(samples: List<PaceSample>, snapshot: UsageSnapshot, manual: Double, nowMs: Long): Map<String, Double> =
        estimates(samples, snapshot, manual, nowMs).filter { it.samples > 0 }.associate { it.pair to it.rate }

    private fun UsageSnapshot.meter(provider: ProviderId, id: String): UsageMeter? =
        provider(provider)?.meters?.firstOrNull { it.id == id }

    private fun used(meter: UsageMeter): Double? =
        meter.usedPercent ?: meter.remainingPercent?.let { 100.0 - it }

    /**
     * Same window when both resets agree (within [SAME_WINDOW_TOLERANCE_MS]), or the earlier
     * reading was an idle window (no reset yet, nothing used) that this reading started.
     */
    private fun sameWindow(previous: UsageMeter, current: UsageMeter): Boolean {
        val prevReset = previous.resetsAtEpochMs
        val curReset = current.resetsAtEpochMs ?: return false
        if (prevReset == null) return (used(previous) ?: 0.0) <= 0.0
        return abs(prevReset - curReset) <= SAME_WINDOW_TOLERANCE_MS
    }
}
