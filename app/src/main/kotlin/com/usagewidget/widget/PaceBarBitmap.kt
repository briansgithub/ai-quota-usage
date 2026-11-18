package com.usagewidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.usagewidget.domain.UsedGradient
import kotlin.math.max
import kotlin.math.roundToInt

object PaceBarBitmap {
    fun render(
        usedPercent: Double,
        expectedUsedPercent: Double?,
        widthPx: Int,
        heightPx: Int,
        trackRgb: Int = 0xFF2A2A2A.toInt(),
        capBar: Boolean = false
    ): Bitmap {
        val w = max(widthPx, 8)
        val h = max(heightPx, 4)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = h / 2f
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackRgb }
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, track)

        val used = usedPercent.coerceIn(0.0, 100.0)
        val fillW = (w * used / 100.0).toFloat()
        if (fillW > 0f) {
            val fillRgb = if (capBar) UsedGradient.capFill(used) else UsedGradient.fill(used, expectedUsedPercent)
            val strokeRgb = if (capBar) UsedGradient.capOutline(used) else UsedGradient.outline(used, expectedUsedPercent)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillRgb.packed() }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = strokeRgb.packed()
                style = Paint.Style.STROKE
                strokeWidth = if (h >= 6) 1.5f else 1f
            }
            val rect = RectF(0.75f, 0.75f, fillW.coerceAtLeast(radius), h - 0.75f)
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }

        if (!capBar && expectedUsedPercent != null) {
            val x = (w * expectedUsedPercent.coerceIn(0.0, 100.0) / 100.0).roundToInt()
                .coerceIn(1, w - 2)
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F5.toInt() }
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF141414.toInt() }
            canvas.drawRect((x - 1).toFloat(), 0f, (x + 2).toFloat(), h.toFloat(), outline)
            canvas.drawRect(x.toFloat(), 0f, (x + 1).toFloat(), h.toFloat(), tick)
        }
        return bitmap
    }
}
