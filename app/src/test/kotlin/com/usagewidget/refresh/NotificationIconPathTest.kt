package com.usagewidget.refresh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's vector PathParser rejects SVG's compact arc flags ("0 00-1.8-1"), which
 * crashed the app when it drew the Grok logo. Keep every arc in the notification logos
 * written as 7 separated values.
 */
class NotificationIconPathTest {
    private val arc = Regex("""[aA]([^a-zA-Z]*)""")
    private val number = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)""")

    @Test
    fun logoArcsHaveSevenSeparatedValuesEach() {
        val dir = listOf("src/main/res/drawable", "app/src/main/res/drawable").map(::File).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.startsWith("ic_notify_") }.orEmpty()
        assertTrue("logo drawables present", files.size >= 4)
        files.forEach { file ->
            Regex("""android:pathData="([^"]+)"""").findAll(file.readText()).forEach { path ->
                arc.findAll(path.groupValues[1]).forEach { m ->
                    val count = number.findAll(m.groupValues[1]).count()
                    assertTrue("${file.name}: arc args $count", count > 0 && count % 7 == 0)
                    assertFalse("${file.name}: compact flags", Regex("""\s[01][01][-.\d]""").containsMatchIn(m.groupValues[1]))
                }
            }
        }
    }
}
