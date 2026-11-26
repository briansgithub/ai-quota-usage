package com.usagewidget.providers.gemini

import com.usagewidget.domain.*
import com.usagewidget.loadFixture
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GeminiUsageTest {
    private fun f(name: String) = loadFixture("gemini-$name.json")
    private val now = Instant.parse("2026-11-25T03:00:00Z").toEpochMilli()

    @Test fun dailyWinsDuplicatesAndProductionFillsMissingWeekly() {
        val meters = GeminiUsageParser.merge(f("daily"), f("production"))
        assertEquals(5, meters.size)
        assertEquals(37, meters[0].displayUsedPercent)
        assertEquals(20, meters[1].displayUsedPercent)
        assertEquals("Gemini 4 Argon weekly", meters[2].title)
        assertEquals(25, meters[2].displayUsedPercent)
        assertTrue(meters.any { it.id == "gemini.3p-5h" && it.displayUsedPercent == 60 })
        assertEquals(GeminiUsageParser.FIVE_HOURS, meters[0].windowLengthMs)
        assertEquals(60.0, PaceWindows.expectedUsedPercent(meters[0], now)!!, 0.001)
        assertEquals(0, GeminiUsageParser.merge(f("production"), f("daily"))[0].displayUsedPercent)
    }

    @Test fun disabledMalformedArgonAndInvalidFractionsAreHidden() {
        assertTrue(GeminiUsageParser.merge(f("disabled"), "not json", "[]").isEmpty())
    }

    @Test fun modelsExcludeInternalImageOldAndArgonPools() {
        val meter = GeminiUsageParser.models(f("models"))!!
        assertEquals("gemini.gemini-5h", meter.id)
        assertEquals(30, meter.displayUsedPercent)
        assertNull(GeminiUsageParser.models("{}"))
    }

    @Test fun planUsesPaidNameThenKnownIdThenCurrentTier() {
        assertEquals("Ultra", GeminiUsageParser.plan(GeminiUsageParser.obj(f("plan"))))
        assertEquals("Pro", GeminiUsageParser.plan(GeminiUsageParser.obj("""{"paidTier":{"id":"g1-pro-tier"},"currentTier":{"name":"Free"}}""")))
        assertEquals("Free", GeminiUsageParser.plan(GeminiUsageParser.obj(f("missing-project"))))
    }

    @Test fun missingProjectUsesEmptyBodyAndDailyFirst() {
        val hosts = mutableListOf<String>()
        val client = GeminiQuotaClient { host, method, body, timeout ->
            if (method == "loadCodeAssist") { assertEquals(5, timeout); GeminiResponse(500, "{}") }
            else {
                assertEquals("{}", body)
                assertEquals(10, timeout)
                hosts.add(host)
                GeminiResponse(200, f(if (host == GeminiQuotaClient.DAILY) "daily" else "production"))
            }
        }
        assertNotNull(client.fetch())
        assertEquals(listOf(GeminiQuotaClient.DAILY, GeminiQuotaClient.PRODUCTION), hosts)
    }

    @Test fun refusingHostDoesNotStopOtherHost() {
        for (refusal in listOf(401, 403)) {
            var calls = 0
            val snapshot = GeminiQuotaClient { host, method, body, _ ->
                if (method == "loadCodeAssist") GeminiResponse(200, f("plan"))
                else {
                    calls++
                    assertTrue(body.contains("fixture-project"))
                    if (host == GeminiQuotaClient.DAILY) GeminiResponse(refusal, f("failure")) else GeminiResponse(200, f("production"))
                }
            }.fetch()
            assertEquals(2, calls)
            assertEquals(2, snapshot!!.meters.size)
        }
    }

    @Test fun allProjectRefusalsRetryOnceWithoutProject() {
        var calls = 0
        val client = GeminiQuotaClient { _, method, body, _ ->
            if (method == "loadCodeAssist") GeminiResponse(200, f("plan"))
            else {
                calls++
                if (body == "{}") GeminiResponse(200, f("production")) else GeminiResponse(403, f("failure"))
            }
        }
        assertNotNull(client.fetch())
        assertEquals(4, calls)
        assertFalse(client.authRejected)
    }

    @Test fun otherPoolsUseModelsOnlyForFiveHourGemini() {
        val snapshot = GeminiQuotaClient { _, method, _, _ -> GeminiResponse(200,
            f(when (method) { "loadCodeAssist" -> "missing-project"; "fetchAvailableModels" -> "models"; else -> "other-pools" })) }.fetch()!!
        assertEquals(2, snapshot.meters.size)
        assertTrue(snapshot.meters.any { it.id == "gemini.gemini-5h" })
        assertFalse(snapshot.meters.any { it.id == "gemini.gemini-weekly" })
    }

    @Test fun exceptionsAndAuthFailuresDegradeWithoutThrowing() {
        assertNull(GeminiQuotaClient { _, _, _, _ -> throw java.net.SocketTimeoutException() }.fetch())
        val client = GeminiQuotaClient { _, _, _, _ -> GeminiResponse(401, f("failure")) }
        assertNull(client.fetch())
        assertTrue(client.authRejected)
    }

    @Test fun domainSectionsAndWidgetFilteringIncludeGemini() {
        val meters = GeminiUsageParser.merge(f("daily"), f("production"))
        val snapshot = UsageSnapshot(now, providers = listOf(ProviderSnapshot("gemini", "Gemini", "OK", meters = meters)))
        val section = UsageSections.from(snapshot, now).single { it.id == SoftwareSectionId.GEMINI }
        assertEquals(meters, section.meters)
        assertEquals(ProviderId.GEMINI, section.signInProvider)
        assertTrue(WidgetBars.catalog.any { it.meterId == "gemini.gemini-5h" })
    }

    @Test fun thresholdsDefaultOffHonorMasterAndReportUsed() {
        val rules = QuotaRuleId.entries.filter { it.section == SoftwareSectionId.GEMINI }
        assertEquals(5, rules.size)
        assertTrue(rules.none { it.defaultEnabled })
        val prefs = QuotaThresholdPreferences(enabledRules = rules.map { it.wire }.toSet())
        fun snapshot(remaining: Double) = UsageSnapshot(now, providers = listOf(ProviderSnapshot("gemini", "Gemini", "OK", meters = listOf(
            UsageMeter("gemini.gemini-5h", "Gemini 5-hour", usedPercent = 100 - remaining, remainingPercent = remaining, resetsAtEpochMs = now + 100_000)))))
        val first = QuotaThresholdEngine.evaluate(snapshot(55.0), now, prefs, emptyMap(), false)
        val next = QuotaThresholdEngine.evaluate(snapshot(18.0), now, prefs, first.states, false)
        assertEquals(1, next.alerts.size)
        assertTrue(next.alerts.single().body.contains("80%"))
        assertEquals(SoftwareSectionId.GEMINI, next.alerts.single().section)
        assertTrue(QuotaThresholdEngine.evaluate(snapshot(18.0), now, prefs.copy(masterEnabled = false), first.states, false).alerts.isEmpty())
    }

    @Test fun nullResetTimeAndOffsetTimestampParseWhileMalformedIsRejected() {
        val json = """
            {
              "groups": [
                {
                  "displayName": "Gemini",
                  "buckets": [
                    { "bucketId": "gemini-5h", "remainingFraction": 1.0, "resetTime": null },
                    { "bucketId": "gemini-weekly", "remainingFraction": 0.5, "resetTime": "2026-11-25T05:00:00+00:00" },
                    { "bucketId": "3p-5h", "remainingFraction": 0.5, "resetTime": "not-a-timestamp" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val meters = GeminiUsageParser.merge(json)
        assertEquals(listOf("gemini.gemini-5h", "gemini.gemini-weekly"), meters.map { it.id })
        assertNull(meters[0].resetsAtEpochMs)
        assertNotNull(meters[1].resetsAtEpochMs)
    }

    @Test fun paceWindowsAndStaleGeminiSectionKeepLastGood() {
        assertEquals(GeminiUsageParser.FIVE_HOURS, PaceWindows.knownLengthMs("gemini.gemini-5h"))
        assertEquals(GeminiUsageParser.FIVE_HOURS, PaceWindows.knownLengthMs("gemini.3p-5h"))
        assertEquals(GeminiUsageParser.WEEK, PaceWindows.knownLengthMs("gemini.gemini-weekly"))
        assertEquals(GeminiUsageParser.WEEK, PaceWindows.knownLengthMs("gemini.3p-weekly"))
        assertEquals(GeminiUsageParser.WEEK, PaceWindows.knownLengthMs("gemini.argon-weekly"))

        val cached = listOf(UsageMeter("gemini.gemini-5h", "Gemini 5-hour", usedPercent = 30.0, remainingPercent = 70.0))
        val snapshot = UsageSnapshot(
            fetchedAtEpochMs = now,
            providers = listOf(ProviderSnapshot("gemini", "Gemini", "ERROR")),
            sectionHealth = listOf(
                SectionHealth(
                    sectionId = "gemini",
                    lastAttemptEpochMs = now,
                    lastSuccessEpochMs = now - 60_000,
                    lastOutcome = FetchOutcome.NETWORK.name,
                    shortReason = "Network error",
                    lastGoodMeters = cached,
                    lastGoodPlanLabel = "Ultra",
                    signedIn = true
                )
            )
        )
        val gemini = UsageSections.from(snapshot, now).single { it.id == SoftwareSectionId.GEMINI }
        assertEquals(1, gemini.meters.size)
        assertEquals("Ultra", gemini.subtitle)
        assertEquals("Network error", gemini.error)
        assertTrue(gemini.stale.isStale)
    }

    @Test fun geminiAuthDistinguishesTransientFailureFromAuthExpired() {
        var stored: String? = """{"access_token":"synthetic-old","refresh_token":"synthetic-refresh","expiry_date":1}"""
        val transient = GeminiOAuth.fetchWith(
            readTokens = { stored },
            saveTokens = { stored = it },
            now = now,
            refreshToken = { GeminiOAuth.RefreshResult(GeminiOAuth.RefreshStatus.TRANSIENT_FAILURE, FetchOutcome.TIMEOUT) },
            quotaPost = { _, _, _, _, _ -> error("unreachable") }
        )
        assertTrue(transient is ProviderResult.Failure)
        assertEquals(FetchOutcome.TIMEOUT, (transient as ProviderResult.Failure).attempts.single().outcomeEnum)

        val revoked = GeminiOAuth.fetchWith(
            readTokens = { stored },
            saveTokens = { stored = it },
            now = now,
            refreshToken = { GeminiOAuth.RefreshResult(GeminiOAuth.RefreshStatus.AUTH_REJECTED, FetchOutcome.AUTH_EXPIRED) },
            quotaPost = { _, _, _, _, _ -> error("unreachable") }
        )
        assertTrue(revoked is ProviderResult.NeedsSignIn)
        assertEquals(FetchOutcome.AUTH_EXPIRED, (revoked as ProviderResult.NeedsSignIn).attempts.single().outcomeEnum)

        stored = """{"access_token":"synthetic-rejected","refresh_token":"synthetic-revoked","expiry_date":${now + 3600_000}}"""
        val api401 = GeminiOAuth.fetchWith(
            readTokens = { stored },
            saveTokens = { stored = it },
            now = now,
            refreshToken = { GeminiOAuth.RefreshResult(GeminiOAuth.RefreshStatus.AUTH_REJECTED, FetchOutcome.AUTH_EXPIRED) },
            quotaPost = { _, _, _, _, _ -> GeminiResponse(401, f("failure")) }
        )
        assertTrue(api401 is ProviderResult.NeedsSignIn)
        assertEquals(FetchOutcome.AUTH_EXPIRED, (api401 as ProviderResult.NeedsSignIn).attempts.single().outcomeEnum)
    }

    @Test
    fun ordersFiveHourBeforeWeeklyForThirdPartyPools() {
        val json = """
            {
              "groups": [
                {
                  "displayName": "Third-party",
                  "buckets": [
                    { "bucketId": "3p-weekly", "remainingFraction": 1.0 },
                    { "bucketId": "3p-5h", "remainingFraction": 1.0 }
                  ]
                },
                {
                  "displayName": "Gemini",
                  "buckets": [
                    { "bucketId": "gemini-weekly", "remainingFraction": 0.9 },
                    { "bucketId": "gemini-5h", "remainingFraction": 0.6 }
                  ]
                }
              ]
            }
        """.trimIndent()
        val ids = GeminiUsageParser.merge(json).map { it.id }
        assertEquals(listOf("gemini.gemini-5h", "gemini.gemini-weekly", "gemini.3p-5h", "gemini.3p-weekly"), ids)
    }
}
