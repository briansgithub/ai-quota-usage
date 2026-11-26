package com.usagewidget.data

import android.content.Context
import android.util.Log
import com.usagewidget.auth.SecureCookieStore
import com.usagewidget.domain.FetchAttempt
import com.usagewidget.domain.FetchOutcome
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.ProviderResult
import com.usagewidget.domain.ProviderSnapshot
import com.usagewidget.domain.SnapshotMerger
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.domain.UsageSnapshot
import com.usagewidget.extract.HiddenWebViewExtractor
import com.usagewidget.net.AuthenticatedHttp
import com.usagewidget.net.FetchErrorClassifier
import com.usagewidget.providers.cursor.CursorEndpoints
import com.usagewidget.providers.cursor.CursorUsageClient
import com.usagewidget.providers.claude.ClaudeEndpoints
import com.usagewidget.providers.claude.ClaudeUsageClient
import com.usagewidget.providers.claude.ClaudeUsageParser
import com.usagewidget.providers.openai.OpenAiEndpoints
import com.usagewidget.providers.openai.OpenAiUsageClient
import com.usagewidget.refresh.StaleNotifier
import com.usagewidget.refresh.UsageAlertNotifier
import com.usagewidget.widget.WidgetPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class UsageRepository private constructor(private val context: Context) {
    private val cookies = SecureCookieStore.get(context)
    private val http = AuthenticatedHttp(cookies)
    private val cursor = CursorUsageClient(http, cookies)
    private val openai = OpenAiUsageClient(http, cookies)
    private val claude = ClaudeUsageClient(http, cookies)
    private val extractor = HiddenWebViewExtractor(context.applicationContext)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val settings = AppSettings.get(context)
    private val notifier = StaleNotifier(context.applicationContext, settings)
    private val alerts = UsageAlertNotifier(context.applicationContext, settings)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _snapshot = MutableStateFlow(loadCached() ?: UsageSnapshot.empty())
    val snapshot: StateFlow<UsageSnapshot> = _snapshot

    val refreshing = MutableStateFlow(false)

    fun hasSession(provider: ProviderId): Boolean = cookies.hasSession(provider)

    fun settings(): AppSettings = settings

    fun syncNotifications() {
        notifier.sync(_snapshot.value)
    }

    suspend fun refresh(allowHtmlFallback: Boolean = true): UsageSnapshot = mutex.withLock {
        refreshing.value = true
        var latest = _snapshot.value
        try {
            val now = System.currentTimeMillis()
            val previous = _snapshot.value
            val cursorResult = fetchProvider(ProviderId.CURSOR, allowHtmlFallback, now)
            val openaiResult = fetchProvider(ProviderId.OPENAI, allowHtmlFallback, now)
            val geminiResult = fetchProvider(ProviderId.GEMINI, false, now)
            val claudeResult = fetchProvider(ProviderId.CLAUDE, allowHtmlFallback, now)
            val combined = SnapshotMerger.merge(
                previous = previous,
                rounds = listOf(
                    SnapshotMerger.ProviderRound(ProviderId.CURSOR, cursorResult, cookies.hasSession(ProviderId.CURSOR)),
                    SnapshotMerger.ProviderRound(ProviderId.OPENAI, openaiResult, cookies.hasSession(ProviderId.OPENAI)),
                    SnapshotMerger.ProviderRound(ProviderId.CLAUDE, claudeResult, cookies.hasSession(ProviderId.CLAUDE)),
                    SnapshotMerger.ProviderRound(ProviderId.GEMINI, geminiResult, cookies.hasSession(ProviderId.GEMINI))
                ),
                nowMs = now
            )
            persist(combined)
            _snapshot.value = combined
            runCatching { settings.recordPaceSamples(previous, combined, now) }
            runCatching { alerts.evaluate(previous, combined, now) }
            notifier.sync(combined, now)
            latest = combined
            combined
        } finally {
            try {
                WidgetPublisher.push(context, latest)
            } catch (error: Throwable) {
                Log.e(WidgetPublisher.TAG, "widget push after refresh failed", error)
            }
            refreshing.value = false
        }
    }

    fun signOut(provider: ProviderId) {
        cookies.clear(provider)
        val updated = SnapshotMerger.clearForSignOut(_snapshot.value, provider)
        persist(updated)
        _snapshot.value = updated
        scope.launch {
            notifier.sync(updated)
            runCatching { WidgetPublisher.push(context, updated) }
        }
    }

    private suspend fun fetchProvider(
        provider: ProviderId,
        allowHtmlFallback: Boolean,
        now: Long
    ): ProviderResult {
        val result = withContext(Dispatchers.IO) {
            when (provider) {
                ProviderId.CURSOR -> cursor.fetch(now)
                ProviderId.OPENAI -> openai.fetch(now)
                ProviderId.CLAUDE -> claude.fetch(now)
                ProviderId.GEMINI -> com.usagewidget.providers.gemini.GeminiOAuth.fetch(cookies, now)
            }
        }
        if (provider == ProviderId.GEMINI) return result
        if (result is ProviderResult.Success || result is ProviderResult.NeedsSignIn) {
            return result
        }
        if (result is ProviderResult.Failure && allowHtmlFallback && cookies.hasSession(provider)) {
            return runCatching {
                if (provider == ProviderId.CLAUDE) {
                    val text = extractor.readClaudeUsageJson()
                    return@runCatching if (ClaudeUsageParser.isAuthErrorBody(text)) {
                        ProviderResult.NeedsSignIn(
                            message = "Claude session expired. Sign in again.",
                            attempts = result.attempts + FetchAttempt.of(
                                atEpochMs = now,
                                sectionId = SoftwareSectionId.CLAUDE,
                                outcome = FetchOutcome.AUTH_EXPIRED,
                                message = "claude.ai rejected the session",
                                endpoint = ClaudeEndpoints.USAGE_PAGE,
                                source = "webview"
                            )
                        )
                    } else {
                        ProviderResult.Success(
                            claude.snapshotFromWebJson(text, now),
                            attempts = result.attempts + claude.webAttempt(now)
                        )
                    }
                }
                val text = extractor.extractPageText(provider)
                when (provider) {
                    ProviderId.CURSOR -> {
                        val snapshot = cursor.snapshotFromHtml(text, now)
                        ProviderResult.Success(
                            snapshot,
                            attempts = result.attempts + cursor.htmlAttempts(now, snapshot)
                        )
                    }
                    ProviderId.OPENAI -> {
                        val snapshot = openai.snapshotFromHtml(text, now)
                        ProviderResult.Success(
                            snapshot,
                            attempts = result.attempts + openai.htmlAttempt(now)
                        )
                    }
                    ProviderId.CLAUDE, ProviderId.GEMINI -> error("unreachable")
                }
            }.getOrElse { htmlError ->
                val htmlAttempts = htmlFallbackAttempts(provider, htmlError, now)
                ProviderResult.Failure(
                    message = result.message,
                    cause = "html",
                    attempts = result.attempts + htmlAttempts,
                    snapshot = result.snapshot
                )
            }
        }
        return result
    }

    private fun htmlFallbackAttempts(
        provider: ProviderId,
        error: Throwable,
        now: Long
    ): List<FetchAttempt> {
        return when (provider) {
            ProviderId.GEMINI -> emptyList()
            ProviderId.CURSOR -> listOf(
                FetchErrorClassifier.attempt(
                    error, SoftwareSectionId.CURSOR, CursorEndpoints.SPENDING_PAGE, now, source = "html"
                ),
                FetchErrorClassifier.attempt(
                    error, SoftwareSectionId.GROK_BOT, CursorEndpoints.SPENDING_PAGE, now, source = "html"
                )
            )
            ProviderId.OPENAI -> listOf(
                FetchErrorClassifier.attempt(
                    error, SoftwareSectionId.CODEX, OpenAiEndpoints.USAGE_PAGE, now, source = "html"
                )
            )
            ProviderId.CLAUDE -> listOf(
                FetchErrorClassifier.attempt(
                    error, SoftwareSectionId.CLAUDE, ClaudeEndpoints.USAGE_PAGE, now, source = "webview"
                )
            )
        }
    }

    private fun persist(snapshot: UsageSnapshot) {
        cookies.saveSnapshotJson(json.encodeToString(snapshot))
    }

    private fun loadCached(): UsageSnapshot? {
        val raw = cookies.snapshotJson() ?: return null
        return runCatching { json.decodeFromString<UsageSnapshot>(raw) }.getOrNull()
    }

    companion object {
        @Volatile private var instance: UsageRepository? = null
        fun get(context: Context): UsageRepository {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: UsageRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
