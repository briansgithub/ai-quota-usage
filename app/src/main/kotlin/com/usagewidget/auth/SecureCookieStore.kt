package com.usagewidget.auth

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.usagewidget.domain.ProviderId
import com.usagewidget.providers.cursor.CursorEndpoints
import com.usagewidget.providers.openai.OpenAiEndpoints
import java.util.concurrent.ConcurrentHashMap

/**
 * Session cookies, encrypted with an Android Keystore-backed master key.
 * Stored in app-private prefs and excluded from backup/device transfer.
 */
class SecureCookieStore private constructor(private val prefs: SharedPreferences) {

    private val memory = ConcurrentHashMap<String, String>()

    fun cookieHeader(host: String): String? {
        memory[host]?.let { return it }
        val stored = prefs.getString(cookieKey(host), null)
        if (!stored.isNullOrBlank()) {
            memory[host] = stored
        }
        return stored
    }

    fun hasSession(provider: ProviderId): Boolean {
        return when (provider) {
            ProviderId.CURSOR ->
                hostsFor(provider).any { header ->
                    cookieMap(header).keys.any { it.equals(CursorEndpoints.SESSION_COOKIE, true) }
                }
            ProviderId.OPENAI ->
                hostsFor(provider).any { header ->
                    cookieMap(header).keys.any {
                        it.startsWith(OpenAiEndpoints.SESSION_COOKIE_PREFIX, ignoreCase = true)
                    }
                }
        }
    }

    fun importRawCookies(provider: ProviderId, raw: String): Boolean {
        val parsed = SessionPaste.parse(provider, raw)
        if (parsed.isEmpty()) return false
        val primaryHost = providerHosts(provider).first().removePrefix("www.")
        saveCookies(primaryHost, parsed.entries.joinToString("; ") { "${it.key}=${it.value}" })
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        for (host in providerHosts(provider)) {
            for ((name, value) in parsed) {
                manager.setCookie("https://$host", "$name=$value; Path=/; Secure")
            }
        }
        manager.flush()
        return hasSession(provider)
    }

    fun saveCookies(host: String, cookieHeader: String) {
        val cleaned = cookieHeader.trim()
        if (cleaned.isEmpty()) return
        val merged = mergeCookieHeaders(cookieHeader(host), cleaned)
        memory[host] = merged
        prefs.edit().putString(cookieKey(host), merged).apply()
    }

    fun captureFromCookieManager(provider: ProviderId) {
        val manager = CookieManager.getInstance()
        for (host in providerHosts(provider)) {
            val header = manager.getCookie("https://$host") ?: continue
            if (header.isNotBlank()) {
                saveCookies(host.removePrefix("www."), header)
            }
        }
    }

    fun restoreIntoCookieManager() {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        for (provider in ProviderId.entries) {
            for (host in providerHosts(provider)) {
                val header = cookieHeader(host.removePrefix("www.")) ?: continue
                for ((name, value) in cookieMap(header)) {
                    manager.setCookie("https://$host", "$name=$value; Path=/; Secure")
                }
            }
        }
        manager.flush()
    }

    fun applySetCookieHeaders(host: String, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        val current = cookieMap(cookieHeader(host))
        for (line in setCookieHeaders) {
            val pair = line.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isNotEmpty()) {
                current[name] = value
            }
        }
        saveCookies(host, current.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    fun clear(provider: ProviderId) {
        for (host in providerHosts(provider)) {
            val key = host.removePrefix("www.")
            memory.remove(key)
            prefs.edit().remove(cookieKey(key)).apply()
        }
        val manager = CookieManager.getInstance()
        for (host in providerHosts(provider)) {
            val existing = manager.getCookie("https://$host") ?: continue
            for (name in cookieMap(existing).keys) {
                manager.setCookie("https://$host", "$name=; Path=/; Max-Age=0")
            }
        }
        manager.flush()
    }

    fun snapshotJson(): String? = prefs.getString(KEY_SNAPSHOT, null)

    fun saveSnapshotJson(json: String) {
        prefs.edit().putString(KEY_SNAPSHOT, json).apply()
    }

    private fun hostsFor(provider: ProviderId): List<String> =
        providerHosts(provider).mapNotNull { cookieHeader(it.removePrefix("www.")) }

    private fun providerHosts(provider: ProviderId): List<String> = when (provider) {
        ProviderId.CURSOR -> CursorEndpoints.cookieHosts
        ProviderId.OPENAI -> OpenAiEndpoints.cookieHosts
    }

    private fun cookieKey(host: String) = "cookie.$host"

    companion object {
        private const val PREFS_FILE = "usage_widget_secure"
        private const val KEY_SNAPSHOT = "snapshot.json"
        @Volatile private var instance: SecureCookieStore? = null

        fun get(context: Context): SecureCookieStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: SecureCookieStore(createPrefs(context.applicationContext)).also { instance = it }
            }
        }

        private fun createPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        fun cookieMap(header: String?): MutableMap<String, String> {
            val out = linkedMapOf<String, String>()
            if (header.isNullOrBlank()) return out
            header.split(';').forEach { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty() || !trimmed.contains('=')) return@forEach
                val name = trimmed.substringBefore('=').trim()
                val value = trimmed.substringAfter('=').trim()
                if (name.isNotEmpty()) out[name] = value
            }
            return out
        }

        fun mergeCookieHeaders(existing: String?, incoming: String): String {
            val map = cookieMap(existing)
            map.putAll(cookieMap(incoming))
            return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }
}
