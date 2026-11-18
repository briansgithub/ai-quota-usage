using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class SnapshotMergerTest
{
    private const long Now = 1_783_000_000_000L;
    private static readonly UsageMeter[] CursorMeters =
    {
        new("cursor.models", "Cursor Models", UsedPercent: 17.0, RemainingPercent: 83.0),
        new("cursor.other", "Other Models", UsedPercent: 100.0, RemainingPercent: 0.0),
        new("cursor.plan_reset", "Plan reset", ResetsAtEpochMs: Now + 86_400_000L)
    };
    private static readonly UsageMeter GrokMeter = new("cursor.grok_bot", "Grok Bot", UsedPercent: 65.0, RemainingPercent: 35.0);
    private static readonly UsageMeter[] CodexMeters =
    {
        new("openai.five_hour", "5-hour limit", UsedPercent: 0.0, RemainingPercent: 100.0),
        new("openai.weekly", "Weekly limit", UsedPercent: 12.0, RemainingPercent: 88.0)
    };

    [Fact]
    public void FailedRefreshKeepsLastGoodCursorMeters()
    {
        var previous = SnapshotMerger.Merge(
            UsageSnapshot.Empty(Now - 10_000L),
            new ProviderResult.Success(
                new ProviderSnapshot("cursor", "Cursor", "OK", Meters: CursorMeters.Concat(new[] { GrokMeter }).ToList(), PlanLabel: "Pro"),
                new[] { Success(SoftwareSectionId.Cursor), Success(SoftwareSectionId.GrokBot) }),
            new ProviderResult.Success(
                new ProviderSnapshot("openai", "ChatGPT / Codex", "OK", Meters: CodexMeters),
                new[] { Success(SoftwareSectionId.Codex) }),
            Now - 10_000L, true, true);
        var failed = SnapshotMerger.Merge(
            previous,
            new ProviderResult.Failure("connection refused", Attempts: new[]
            {
                FetchAttempt.Of(Now, SoftwareSectionId.Cursor, FetchOutcome.Network, endpoint: "https://cursor.com/api/usage-summary"),
                FetchAttempt.Of(Now, SoftwareSectionId.GrokBot, FetchOutcome.Network, endpoint: "https://cursor.com/api/dashboard/get-sand-usage-status")
            }),
            new ProviderResult.Success(
                new ProviderSnapshot("openai", "ChatGPT / Codex", "OK", Meters: CodexMeters),
                new[] { Success(SoftwareSectionId.Codex) }),
            Now, true, true);
        var cursor = failed.Health(SoftwareSectionId.Cursor);
        Assert.Equal(new[] { "cursor.models", "cursor.other", "cursor.plan_reset" }, cursor.LastGoodMeterList.Select(m => m.Id).ToArray());
        Assert.Equal(17.0, cursor.LastGoodMeterList[0].UsedPercent!.Value, 2);
        Assert.Equal(FetchOutcome.Network, cursor.OutcomeEnum);
        Assert.True(cursor.StaleView(Now).IsStale);
        Assert.Equal("Network error", cursor.StaleView(Now).ShortReason);
        var grok = failed.Health(SoftwareSectionId.GrokBot);
        Assert.Equal(65.0, grok.LastGoodMeterList.Single().UsedPercent!.Value, 2);
        Assert.False(failed.Health(SoftwareSectionId.Codex).StaleView(Now).IsStale);
    }

    [Fact]
    public void GrokCanFailIndependentlyOfCursor()
    {
        var mixed = SnapshotMerger.Merge(
            Seeded(),
            new ProviderResult.Success(
                new ProviderSnapshot("cursor", "Cursor", "OK", Error: "Grok Bot: HTTP 503", Meters: CursorMeters),
                new[]
                {
                    Success(SoftwareSectionId.Cursor),
                    FetchAttempt.Of(Now, SoftwareSectionId.GrokBot, FetchOutcome.Http, 503, endpoint: "https://cursor.com/api/dashboard/get-sand-usage-status")
                }),
            new ProviderResult.Success(
                new ProviderSnapshot("openai", "ChatGPT / Codex", "OK", Meters: CodexMeters),
                new[] { Success(SoftwareSectionId.Codex) }),
            Now, true, true);
        Assert.False(mixed.Health(SoftwareSectionId.Cursor).StaleView(Now).IsStale);
        var grok = mixed.Health(SoftwareSectionId.GrokBot);
        Assert.True(grok.StaleView(Now).IsStale);
        Assert.Equal("HTTP 503", grok.StaleView(Now).ShortReason);
        Assert.Equal(65.0, grok.LastGoodMeterList.Single().UsedPercent!.Value, 2);
    }

    [Fact]
    public void AuthExpiredKeepsLastGoodAndMarksSignInExpired()
    {
        var expired = SnapshotMerger.Merge(
            Seeded(),
            new ProviderResult.NeedsSignIn("Cursor session expired. Sign in again.", new[]
            {
                FetchAttempt.Of(Now, SoftwareSectionId.Cursor, FetchOutcome.AuthExpired),
                FetchAttempt.Of(Now, SoftwareSectionId.GrokBot, FetchOutcome.AuthExpired)
            }),
            new ProviderResult.NeedsSignIn("Sign in to ChatGPT to load Codex usage."),
            Now, false, false);
        var cursor = expired.Health(SoftwareSectionId.Cursor);
        Assert.True(cursor.HasLastGood);
        Assert.Equal("Sign-in expired", cursor.StaleView(Now).ShortReason);
        Assert.Equal(StaleSeverity.Red, cursor.StaleView(Now).Severity);
        var codex = expired.Health(SoftwareSectionId.Codex);
        Assert.True(codex.HasLastGood);
        Assert.Equal("Sign-in expired", codex.StaleView(Now).ShortReason);
    }

    [Fact]
    public void SuccessfulFetchReplacesLastGoodWhenUsedDropsAfterPeriodReset()
    {
        var periodStart = Now;
        var resetAt = Now + PacingPolicy.GrokPeriodMs;
        var grokAfter = new UsageMeter(
            "cursor.grok_bot",
            "Grok Bot",
            UsedPercent: 0.0,
            RemainingPercent: 100.0,
            ResetsAtEpochMs: resetAt,
            WindowStartEpochMs: periodStart,
            WindowLengthMs: PacingPolicy.GrokPeriodMs);
        var merged = SnapshotMerger.Merge(
            Seeded(),
            new ProviderResult.Success(
                new ProviderSnapshot("cursor", "Cursor", "OK", Meters: CursorMeters.Concat(new[] { grokAfter }).ToList(), PlanLabel: "Pro"),
                new[] { Success(SoftwareSectionId.Cursor), Success(SoftwareSectionId.GrokBot) }),
            new ProviderResult.Success(
                new ProviderSnapshot("openai", "ChatGPT / Codex", "OK", Meters: CodexMeters),
                new[] { Success(SoftwareSectionId.Codex) }),
            Now, true, true);
        var grok = merged.Health(SoftwareSectionId.GrokBot);
        Assert.Equal(0.0, grok.LastGoodMeterList.Single().UsedPercent!.Value, 2);
        Assert.Equal(periodStart, grok.LastGoodMeterList.Single().WindowStartEpochMs);
        Assert.False(grok.StaleView(Now).IsStale);
        var grokSection = UsageSections.From(merged, Now).First(s => s.Id == SoftwareSectionId.GrokBot);
        Assert.Equal("0% used", grokSection.MeterList.Single(m => m.Id == "cursor.grok_bot").UsedLabel());
        Assert.Equal(100.0, grokSection.MeterList.Single().RemainingPercent!.Value, 2);
    }

    [Fact]
    public void SuccessfulFetchWithEmptyGrokMetersClearsLastGood()
    {
        var merged = SnapshotMerger.Merge(
            Seeded(),
            new ProviderResult.Success(
                new ProviderSnapshot("cursor", "Cursor", "OK", Meters: CursorMeters, PlanLabel: "Pro"),
                new[] { Success(SoftwareSectionId.Cursor), Success(SoftwareSectionId.GrokBot) }),
            new ProviderResult.Success(
                new ProviderSnapshot("openai", "ChatGPT / Codex", "OK", Meters: CodexMeters),
                new[] { Success(SoftwareSectionId.Codex) }),
            Now, true, true);
        var grok = merged.Health(SoftwareSectionId.GrokBot);
        Assert.Empty(grok.LastGoodMeterList);
        Assert.False(grok.StaleView(Now).IsStale);
        var grokSection = UsageSections.From(merged, Now).First(s => s.Id == SoftwareSectionId.GrokBot);
        Assert.Null(grokSection.MeterList.Single().UsedPercent);
        Assert.Null(grokSection.MeterList.Single().UsedLabel());
    }

    [Fact]
    public void SignOutClearsLastGood()
    {
        var cleared = SnapshotMerger.ClearForSignOut(Seeded(), ProviderId.Cursor);
        Assert.False(cleared.Health(SoftwareSectionId.Cursor).HasLastGood);
        Assert.False(cleared.Health(SoftwareSectionId.GrokBot).SignedIn);
        Assert.True(cleared.Health(SoftwareSectionId.Codex).HasLastGood);
    }

    [Fact]
    public void AttemptLogRollsAndOmitsSecrets()
    {
        var snapshot = UsageSnapshot.Empty(Now);
        for (var index = 0; index < 30; index++)
        {
            snapshot = SnapshotMerger.Merge(
                snapshot,
                new ProviderResult.Failure("HTTP 503", Attempts: new[]
                {
                    FetchAttempt.Of(Now + index, SoftwareSectionId.Cursor, FetchOutcome.Http, 503,
                        "Bearer eyJhbGciOi.payload.sig failed",
                        "https://cursor.com/api/usage-summary?token=secret")
                }),
                new ProviderResult.NeedsSignIn("Sign in"),
                Now + index, true, false);
        }
        Assert.Equal(StalePolicy.AttemptLogLimit, snapshot.Attempts.Count);
        var last = snapshot.Attempts[^1];
        Assert.Equal("https://cursor.com/api/usage-summary", last.Endpoint);
        Assert.DoesNotContain("eyJ", last.Message ?? "");
        Assert.DoesNotContain("Bearer eyJ", last.Message ?? "");
    }

    private static UsageSnapshot Seeded() => SnapshotMerger.Merge(
        UsageSnapshot.Empty(Now - 60_000L),
        new ProviderResult.Success(
            new ProviderSnapshot("cursor", "Cursor", "OK", Meters: CursorMeters.Concat(new[] { GrokMeter }).ToList(), PlanLabel: "Pro"),
            new[] { Success(SoftwareSectionId.Cursor, Now - 60_000L), Success(SoftwareSectionId.GrokBot, Now - 60_000L) }),
        new ProviderResult.Success(
            new ProviderSnapshot("openai", "ChatGPT / Codex", "OK", Meters: CodexMeters, PlanLabel: "Plus"),
            new[] { Success(SoftwareSectionId.Codex, Now - 60_000L) }),
        Now - 60_000L, true, true);

    private static FetchAttempt Success(SoftwareSectionId section, long at = Now) =>
        FetchAttempt.Of(at, section, FetchOutcome.Success, source: "json");
}
