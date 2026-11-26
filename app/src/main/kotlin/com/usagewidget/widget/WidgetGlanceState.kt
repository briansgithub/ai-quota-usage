package com.usagewidget.widget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.usagewidget.domain.UsageSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object WidgetGlanceState {
    val SNAPSHOT_JSON = stringPreferencesKey("usage_snapshot_json")
    val NEXT_REFRESH_AT = longPreferencesKey("next_refresh_epoch_ms")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun forWidget(snapshot: UsageSnapshot): UsageSnapshot = snapshot.copy(
        attemptLog = emptyList(),
        providers = snapshot.providers.map { it.copy(diagnosticJson = null) },
        sectionHealth = snapshot.sectionHealth.map { it.copy(lastGoodDiagnosticJson = null) }
    )

    fun encodeSnapshot(snapshot: UsageSnapshot): String =
        json.encodeToString(UsageSnapshot.serializer(), forWidget(snapshot))

    fun decodeSnapshot(raw: String?): UsageSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(UsageSnapshot.serializer(), raw) }.getOrNull()
    }

    fun write(prefs: MutablePreferences, snapshot: UsageSnapshot, nextRefreshAt: Long) {
        prefs[SNAPSHOT_JSON] = encodeSnapshot(snapshot)
        prefs[NEXT_REFRESH_AT] = nextRefreshAt
        // Display settings live in AppSettings, not here; a fresh stamp on every push makes
        // the Glance session see a state change and recompose, so settings apply at once.
        prefs[PUSHED_AT] = System.nanoTime()
    }

    val PUSHED_AT = longPreferencesKey("pushed_at_nanos")

    fun snapshotOf(prefs: Preferences): UsageSnapshot? = decodeSnapshot(prefs[SNAPSHOT_JSON])

    fun nextRefreshOf(prefs: Preferences): Long? =
        if (NEXT_REFRESH_AT in prefs) prefs[NEXT_REFRESH_AT] else null
}
