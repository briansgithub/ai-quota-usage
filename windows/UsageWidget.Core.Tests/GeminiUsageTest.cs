using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class GeminiUsageTest
{
    private static string F(string name) => Fixture.Load("gemini-" + name + ".json");
    private static readonly long Now = DateTimeOffset.Parse("2026-11-25T03:00:00Z").ToUnixTimeMilliseconds();

    [Fact]
    public void DailyWinsDuplicatesAndProductionFillsMissingWeekly()
    {
        var meters = GeminiUsageParser.Merge(F("daily"), F("production"));
        Assert.Equal(5, meters.Count);
        Assert.Equal(37, meters[0].DisplayUsedPercent);
        Assert.Equal(20, meters[1].DisplayUsedPercent);
        Assert.Equal("Gemini 4 Argon weekly", meters[2].Title);
        Assert.Equal(25, meters[2].DisplayUsedPercent);
        Assert.Contains(meters, m => m.Id == "gemini.3p-5h" && m.DisplayUsedPercent == 60);
        Assert.Equal(GeminiUsageParser.FiveHours, meters[0].WindowLengthMs);
        Assert.Equal(60.0, PaceWindows.ExpectedUsedPercent(meters[0], Now));
        Assert.Equal(0, GeminiUsageParser.Merge(F("production"), F("daily"))[0].DisplayUsedPercent);
    }

    [Fact]
    public void DisabledMalformedArgonAndInvalidFractionsAreHidden() => Assert.Empty(GeminiUsageParser.Merge(F("disabled"), "not json", "[]"));

    [Fact]
    public void ModelsExcludeInternalImageOldAndArgonPools()
    {
        var meter = GeminiUsageParser.Models(F("models"));
        Assert.NotNull(meter);
        Assert.Equal("gemini.gemini-5h", meter.Id);
        Assert.Equal(30, meter.DisplayUsedPercent);
        Assert.Null(GeminiUsageParser.Models("{}"));
    }

    [Fact]
    public void PlanUsesPaidNameThenKnownIdThenCurrentTier()
    {
        Assert.Equal("Ultra", GeminiUsageParser.Plan(GeminiUsageParser.Object(F("plan"))));
        Assert.Equal("Pro", GeminiUsageParser.Plan(GeminiUsageParser.Object("""{"paidTier":{"id":"g1-pro-tier"},"currentTier":{"name":"Free"}}""")));
        Assert.Equal("Free", GeminiUsageParser.Plan(GeminiUsageParser.Object(F("missing-project"))));
    }

    [Fact]
    public void MissingProjectUsesEmptyBodyAndDailyFirst()
    {
        var hosts = new List<string>();
        var client = new GeminiQuotaClient((host, method, body, timeout) =>
        {
            if (method == "loadCodeAssist") { Assert.Equal(5, timeout); return new(500, "{}"); }
            Assert.Equal("{}", body);
            Assert.Equal(10, timeout);
            hosts.Add(host);
            return new(200, F(host == GeminiQuotaClient.Daily ? "daily" : "production"));
        });
        Assert.NotNull(client.Fetch());
        Assert.Equal(new[] { GeminiQuotaClient.Daily, GeminiQuotaClient.Production }, hosts);
    }

    [Theory]
    [InlineData(401)]
    [InlineData(403)]
    public void RefusingHostDoesNotStopOtherHost(int refusal)
    {
        var calls = 0;
        var snapshot = new GeminiQuotaClient((host, method, body, timeout) =>
        {
            if (method == "loadCodeAssist") return new(200, F("plan"));
            calls++;
            Assert.Contains("fixture-project", body);
            return host == GeminiQuotaClient.Daily ? new(refusal, F("failure")) : new(200, F("production"));
        }).Fetch();
        Assert.Equal(2, calls);
        Assert.Equal(2, snapshot!.MeterList.Count);
    }

    [Fact]
    public void AllProjectRefusalsRetryOnceWithoutProject()
    {
        var calls = 0;
        var client = new GeminiQuotaClient((host, method, body, timeout) =>
        {
            if (method == "loadCodeAssist") return new(200, F("plan"));
            calls++;
            return body == "{}" ? new(200, F("production")) : new(403, F("failure"));
        });
        Assert.NotNull(client.Fetch());
        Assert.Equal(4, calls);
        Assert.False(client.AuthRejected);
    }

    [Fact]
    public void OtherPoolsUseModelsOnlyForFiveHourGemini()
    {
        var snapshot = new GeminiQuotaClient((h, method, b, t) => new(200,
            method == "loadCodeAssist" ? F("missing-project") : method == "fetchAvailableModels" ? F("models") : F("other-pools"))).Fetch();
        Assert.Equal(2, snapshot!.MeterList.Count);
        Assert.Contains(snapshot.MeterList, m => m.Id == "gemini.gemini-5h");
        Assert.DoesNotContain(snapshot.MeterList, m => m.Id == "gemini.gemini-weekly");
    }

    [Fact]
    public void ExceptionsAndAuthFailuresDegradeWithoutThrowing()
    {
        Assert.Null(new GeminiQuotaClient((h, m, b, t) => throw new TimeoutException()).Fetch());
        var client = new GeminiQuotaClient((h, m, b, t) => new(401, F("failure")));
        Assert.Null(client.Fetch());
        Assert.True(client.AuthRejected);
        Assert.IsType<ProviderResult.NeedsSignIn>(new GeminiUsageClient(new MemoryCookieStore(), Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString())).Fetch(Now));
    }

    [Fact]
    public void TrayIncludesGeminiPaceButOmitsExhaustedMeters()
    {
        var meters = GeminiUsageParser.Merge(F("production")).Select(m => m.Id.EndsWith("weekly") ? m with { UsedPercent = 100, RemainingPercent = 0 } : m).ToList();
        var snapshot = new UsageSnapshot(Now, Providers: new[] { new ProviderSnapshot("gemini", "Gemini", "OK", Meters: meters) });
        var lines = GlanceUsage.From(snapshot, Now).Lines;
        Assert.Single(lines);
        Assert.Equal("Gemini 5-hour", lines[0].Label);
        Assert.NotNull(lines[0].ExpectedUsedPercent);
    }

    [Fact]
    public void ThresholdsDefaultOffHonorMasterAndReportUsed()
    {
        var settings = new AppSettings(new MemorySettingsStore());
        Assert.False(settings.QuotaGeminiEnabled);
        settings.QuotaGeminiEnabled = true;
        var prefs = settings.QuotaPreferences();
        UsageSnapshot Snapshot(double remaining) => new(Now, Providers: new[] { new ProviderSnapshot("gemini", "Gemini", "OK", Meters: new[] {
            new UsageMeter("gemini.gemini-5h", "Gemini 5-hour", UsedPercent: 100 - remaining, RemainingPercent: remaining, ResetsAtEpochMs: Now + 100_000) }) });
        var first = QuotaThresholdEngine.Evaluate(Snapshot(55), Now, prefs, new Dictionary<string, QuotaThresholdState>(), false);
        var next = QuotaThresholdEngine.Evaluate(Snapshot(18), Now, prefs, first.States, false);
        Assert.Single(next.Alerts);
        Assert.Contains("80%", next.Alerts[0].Body);
        Assert.Equal(6407, next.Alerts[0].NotificationId);
        Assert.Empty(QuotaThresholdEngine.Evaluate(Snapshot(18), Now, prefs with { MasterEnabled = false }, first.States, false).Alerts);
    }

    [Fact]
    public void NullResetTimeKeepsIdleBucketWhileMalformedResetIsDropped()
    {
        var json = """
            {
              "groups": [
                {
                  "displayName": "Gemini",
                  "buckets": [
                    { "bucketId": "gemini-5h", "remainingFraction": 1.0, "resetTime": null },
                    { "bucketId": "gemini-weekly", "remainingFraction": 0.5, "resetTime": "not-a-timestamp" }
                  ]
                }
              ]
            }
            """;
        var meters = GeminiUsageParser.Merge(json);
        Assert.Single(meters);
        Assert.Equal("gemini.gemini-5h", meters[0].Id);
        Assert.Equal(0, meters[0].DisplayUsedPercent);
        Assert.Null(meters[0].ResetsAtEpochMs);
    }

    [Fact]
    public void PaceWindowsAndStaleGeminiSectionKeepLastGood()
    {
        Assert.Equal(GeminiUsageParser.FiveHours, PaceWindows.KnownLengthMs("gemini.gemini-5h"));
        Assert.Equal(GeminiUsageParser.FiveHours, PaceWindows.KnownLengthMs("gemini.3p-5h"));
        Assert.Equal(GeminiUsageParser.Week, PaceWindows.KnownLengthMs("gemini.gemini-weekly"));
        Assert.Equal(GeminiUsageParser.Week, PaceWindows.KnownLengthMs("gemini.3p-weekly"));
        Assert.Equal(GeminiUsageParser.Week, PaceWindows.KnownLengthMs("gemini.argon-weekly"));

        var cached = new[] { new UsageMeter("gemini.gemini-5h", "Gemini 5-hour", UsedPercent: 30, RemainingPercent: 70) };
        var snapshot = new UsageSnapshot(
            Now,
            Providers: new[] { new ProviderSnapshot("gemini", "Gemini", "ERROR") },
            SectionHealth: new[]
            {
                new SectionHealth("gemini", LastAttemptEpochMs: Now, LastSuccessEpochMs: Now - 60_000,
                    LastOutcome: "NETWORK", ShortReason: "Network error", LastGoodMeters: cached, LastGoodPlanLabel: "Ultra", SignedIn: true)
            });
        var gemini = UsageSections.From(snapshot, Now).Single(s => s.Id == SoftwareSectionId.Gemini);
        Assert.Single(gemini.MeterList);
        Assert.Equal("Ultra", gemini.Subtitle);
        Assert.Equal("Network error", gemini.Error);
        Assert.True(gemini.StaleValue.IsStale);
    }

    [Fact]
    public void OrdersFiveHourBeforeWeeklyForThirdPartyPools()
    {
        var json = """
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
            """;
        var ids = GeminiUsageParser.Merge(json).Select(m => m.Id).ToArray();
        Assert.Equal(new[] { "gemini.gemini-5h", "gemini.gemini-weekly", "gemini.3p-5h", "gemini.3p-weekly" }, ids);
    }
}
