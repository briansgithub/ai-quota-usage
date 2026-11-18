package com.usagewidget.refresh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.usagewidget.R
import com.usagewidget.domain.SoftwareSectionId

/**
 * Per-service notification branding. The small icon (status bar) is the monochrome
 * logo; the large icon (shown in the shade, where Android 16+ draws the app icon on the
 * left) is the logo in the service's colour on a white disc.
 */
object NotificationIcons {
    @DrawableRes
    fun forSection(section: SoftwareSectionId): Int = when (section) {
        SoftwareSectionId.CURSOR -> R.drawable.ic_notify_cursor
        SoftwareSectionId.GROK_BOT -> R.drawable.ic_notify_grok
        SoftwareSectionId.CODEX -> R.drawable.ic_notify_openai
        SoftwareSectionId.CLAUDE -> R.drawable.ic_notify_claude
    }

    private fun brandColor(section: SoftwareSectionId): Int = when (section) {
        SoftwareSectionId.CLAUDE -> Color.parseColor("#D97757")
        SoftwareSectionId.CODEX, SoftwareSectionId.CURSOR, SoftwareSectionId.GROK_BOT -> Color.BLACK
    }

    /** Never throws: a broken drawable means no large icon, not a lost notification. */
    fun largeIcon(context: Context, section: SoftwareSectionId, sizePx: Int = 192): Bitmap? =
        runCatching { drawLargeIcon(context, section, sizePx) }.getOrNull()

    private fun drawLargeIcon(context: Context, section: SoftwareSectionId, sizePx: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, forSection(section))?.mutate() ?: return null
        DrawableCompat.setTint(drawable, brandColor(section))
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, disc)
        val inset = (sizePx * 0.2f).toInt()
        drawable.setBounds(inset, inset, sizePx - inset, sizePx - inset)
        drawable.draw(canvas)
        return bitmap
    }

    fun groupKey(section: SoftwareSectionId): String = "usage:${section.wireName}"

    fun summaryId(section: SoftwareSectionId): Int = 7000 + section.ordinal
}
