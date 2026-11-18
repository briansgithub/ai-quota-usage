package com.usagewidget.domain

/**
 * Bar fill color vs even pace, not raw used-%.
 * Delta = actualUsed − expectedUsed (elapsed fraction of the window). Saturates
 * at ±[SATURATE_DELTA] percentage points: −25 (or more behind) → red,
 * 0 → pale (on pace), +25 (or more ahead) → green. 100% used is always green.
 * Unknown window → pale. Matches Windows [UsedGradient].
 */
data class Rgb8(val r: Int, val g: Int, val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255)
    }

    fun packed(): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

object UsedGradient {
    const val SATURATE_DELTA = 25.0

    val RED = Rgb8(224, 68, 58)
    val MID = Rgb8(236, 236, 240)
    val GREEN = Rgb8(46, 176, 92)
    val DARK_OUTLINE = Rgb8(16, 16, 18)

    fun fill(usedPercent: Double, expectedUsedPercent: Double?): Rgb8 {
        if (usedPercent >= 100) return GREEN
        if (expectedUsedPercent == null) return MID
        val delta = usedPercent - expectedUsedPercent
        val t = (delta / SATURATE_DELTA).coerceIn(-1.0, 1.0)
        return if (t < 0) lerp(MID, RED, -t) else lerp(MID, GREEN, t)
    }

    /**
     * On-demand dollar-cap bar: 0% green, 50% white, 100% red.
     * Fraction of the cap only — no pace.
     */
    fun capFill(usedPercent: Double): Rgb8 {
        val used = usedPercent.coerceIn(0.0, 100.0)
        return if (used <= 50.0) lerp(GREEN, MID, used / 50.0)
        else lerp(MID, RED, (used - 50.0) / 50.0)
    }

    fun outline(usedPercent: Double, expectedUsedPercent: Double?): Rgb8 =
        outlineOf(fill(usedPercent, expectedUsedPercent))

    fun capOutline(usedPercent: Double): Rgb8 = outlineOf(capFill(usedPercent))

    private fun outlineOf(fill: Rgb8): Rgb8 {
        val lum = 0.299 * fill.r + 0.587 * fill.g + 0.114 * fill.b
        if (lum >= 140) return DARK_OUTLINE
        return Rgb8(
            (fill.r * 0.45).toInt().coerceIn(0, 255),
            (fill.g * 0.45).toInt().coerceIn(0, 255),
            (fill.b * 0.45).toInt().coerceIn(0, 255)
        )
    }

    private fun lerp(a: Rgb8, b: Rgb8, tRaw: Double): Rgb8 {
        val t = tRaw.coerceIn(0.0, 1.0)
        return Rgb8(
            kotlin.math.round(a.r + (b.r - a.r) * t).toInt(),
            kotlin.math.round(a.g + (b.g - a.g) * t).toInt(),
            kotlin.math.round(a.b + (b.b - a.b) * t).toInt()
        )
    }
}
