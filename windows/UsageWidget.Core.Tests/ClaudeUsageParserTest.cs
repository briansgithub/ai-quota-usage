using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class ClaudeUsageParserTest
{
    private static readonly long Now = DateTimeOffset.Parse("2026-11-17T21:00:00Z").ToUnixTimeMilliseconds();

    [Fact]
    public void PicksChatOrgUnlessLastActiveOrgMatches()
    {
        var raw = Fixture.Load("claude-organizations.json");
        Assert.Equal("22222222-2222-2222-2222-222222222222", ClaudeUsageParser.ParseOrgId(raw));
        Assert.Equal("11111111-1111-1111-1111-111111111111", ClaudeUsageParser.ParseOrgId(raw, "11111111-1111-1111-1111-111111111111"));
        Assert.Equal("22222222-2222-2222-2222-222222222222", ClaudeUsageParser.ParseOrgId(raw, "not-an-org"));
    }

    [Fact]
    public void UsageBecomesSessionWeeklyAndActiveModelMeters()
    {
        var snapshot = ClaudeUsageParser.ParseUsage(Fixture.Load("claude-usage.json"), Now);
        Assert.Equal("claude", snapshot.Id);
        // Opus is 0% with no reset, so it is not shown; Sonnet has usage.
        Assert.Equal(new[] { ClaudeMeterIds.FiveHour, ClaudeMeterIds.Weekly, ClaudeMeterIds.WeeklySonnet },
            snapshot.MeterList.Select(m => m.Id).ToArray());
        var session = snapshot.MeterList[0];
        Assert.Equal("Session", session.Title);
        Assert.Equal("37% used", session.UsedLabel());
        Assert.Equal(DateTimeOffset.Parse("2026-11-18T00:00:00Z").ToUnixTimeMilliseconds(), session.ResetsAtEpochMs);
        Assert.Equal(ClaudeMeterIds.FiveHourMs, session.WindowLengthMs);
        Assert.Equal(session.ResetsAtEpochMs - ClaudeMeterIds.FiveHourMs, session.WindowStartEpochMs);
        var weekly = snapshot.MeterList[1];
        Assert.Equal("Weekly", weekly.Title);
        Assert.Equal(38, weekly.DisplayRemainingPercent);
        Assert.NotNull(PaceWindows.ExpectedUsedPercent(weekly, Now));
    }

    [Fact]
    public void CedarEmberGrantsBecomeManualFullResets()
    {
        const string raw = """
            {"five_hour":{"utilization":3.0,"resets_at":"2026-11-25T12:30:00+00:00"},
             "seven_day":{"utilization":90.0,"resets_at":"2026-11-25T18:00:00+00:00"},
             "cedar_ember":{"eligible":true,"grants":[
               {"id":"full-reset-2611","label":"One full limit reset (November 2026)","resets_total":1,"resets_left":1,
                "starts_at":"2026-11-10T21:30:00+00:00","ends_at":"2026-12-10T22:00:00+00:00","paused":false,"type":"full"},
               {"id":"used","type":"full","resets_left":0,"ends_at":"2026-12-01T00:00:00+00:00"}],
              "event_props":{"tier":"claude_pro"}}}
            """;
        var snapshot = ClaudeUsageParser.ParseUsage(raw, Now);
        Assert.Equal(1, snapshot.ManualResets!.AvailableCount);
        Assert.Equal("Manual resets: 1 (exp 12/10)", snapshot.ManualResets.DisplayLine());
        Assert.DoesNotContain("full-reset-2611", snapshot.ManualResets.ObservedJson!);
        Assert.DoesNotContain("claude_pro", snapshot.ManualResets.ObservedJson!);

        var section = UsageSections.From(new UsageSnapshot(Now, Providers: new[] { snapshot }), Now)
            .Single(s => s.Id == SoftwareSectionId.Claude);
        Assert.Equal("Manual resets: 1 (exp 12/10)", section.ManualResets?.DisplayLine());
    }

    [Fact]
    public void UsageWithoutCedarEmberHasNoManualResets()
    {
        var snapshot = ClaudeUsageParser.ParseUsage(Fixture.Load("claude-usage.json"), Now);
        Assert.Null(snapshot.ManualResets);
    }

    [Fact]
    public void IdleSessionWithoutResetStillShowsZeroBar()
    {
        var snapshot = ClaudeUsageParser.ParseUsage(
            """{"five_hour":{"utilization":0,"resets_at":null},"seven_day":{"utilization":5,"resets_at":"2026-11-20T18:00:00Z"}}""", Now);
        var session = snapshot.MeterList.First(m => m.Id == ClaudeMeterIds.FiveHour);
        Assert.Equal("0% used", session.UsedLabel());
        Assert.Null(session.ResetsAtEpochMs);
        Assert.Null(PaceWindows.ExpectedUsedPercent(session, Now));
    }

    [Fact]
    public void ResetJitterBetweenRequestsKeepsTheSameWindow()
    {
        long? Reset(string raw) => ClaudeUsageParser.ParseUsage(
            "{\"five_hour\":{\"utilization\":5,\"resets_at\":\"" + raw + "\"},\"seven_day\":{\"utilization\":5,\"resets_at\":\"" + raw + "\"}}", Now).MeterList[0].ResetsAtEpochMs;
        Assert.Equal(Reset("2026-11-18T18:00:00.951556+00:00"), Reset("2026-11-18T18:00:00.497762+00:00"));
    }

    [Fact]
    public void MissingWindowsIsAParseFailure()
    {
        Assert.Throws<ArgumentException>(() => ClaudeUsageParser.ParseUsage("""{"extra_usage":null}""", Now));
    }

    [Fact]
    public void AuthErrorBodiesAreRecognised()
    {
        Assert.True(ClaudeUsageClient.IsAuthErrorBody(
            """{"type":"error","error":{"type":"permission_error","message":"Invalid authorization","details":{"error_code":"account_session_invalid"}}}"""));
        Assert.False(ClaudeUsageClient.IsAuthErrorBody(Fixture.Load("claude-usage.json")));
    }

    [Fact]
    public void ClaudeIsItsOwnSectionAndMergesWithoutTouchingOthers()
    {
        var usage = ClaudeUsageParser.ParseUsage(Fixture.Load("claude-usage.json"), Now);
        var merged = SnapshotMerger.Merge(UsageSnapshot.Empty(Now - 1000), new[]
        {
            new SnapshotMerger.ProviderRound(ProviderId.Cursor, new ProviderResult.NeedsSignIn(), false),
            new SnapshotMerger.ProviderRound(ProviderId.OpenAi, new ProviderResult.NeedsSignIn(), false),
            new SnapshotMerger.ProviderRound(ProviderId.Claude, new ProviderResult.Success(usage), true)
        }, Now);
        var sections = UsageSections.From(merged, Now);
        var claude = sections.Single(s => s.Id == SoftwareSectionId.Claude);
        Assert.Equal(ProviderStatus.Ok, claude.Status);
        Assert.Equal(ProviderId.Claude, claude.SignInProvider);
        Assert.Equal(3, claude.MeterList.Count);
        Assert.Equal(ProviderStatus.Unsigned, sections.Single(s => s.Id == SoftwareSectionId.Codex).Status);

        var signedOut = SnapshotMerger.ClearForSignOut(merged, ProviderId.Claude);
        Assert.Equal(ProviderStatus.Unsigned, UsageSections.From(signedOut, Now).Single(s => s.Id == SoftwareSectionId.Claude).Status);
    }

    [Fact]
    public void ClaudeSessionResetPostsUsageIsBack()
    {
        var resetAt = Now + 60_000;
        UsageSnapshot Snap(long at, double used, long reset) => new(at, Providers: new[]
        {
            new ProviderSnapshot("claude", "Claude", "OK", Meters: new[]
            {
                new UsageMeter(ClaudeMeterIds.FiveHour, "Session", UsedPercent: used, RemainingPercent: 100 - used, ResetsAtEpochMs: reset)
            })
        });
        var events = ResetDetector.Detect(Snap(Now, 90, resetAt), Snap(resetAt + 5_000, 0, resetAt + ClaudeMeterIds.FiveHourMs), resetAt + 5_000);
        var e = Assert.Single(events);
        Assert.Equal("claude.five_hour", e.Group);
        Assert.Equal("Claude session usage is back", e.Title);
    }

    [Fact]
    public void ClaudeQuotaRulesAreOnByDefault()
    {
        Assert.Contains(QuotaRuleId.ClaudeFiveHour.Wire(), QuotaRuleIds.DefaultEnabledWires);
        Assert.Contains(QuotaRuleId.ClaudeWeekly.Wire(), QuotaRuleIds.DefaultEnabledWires);
        Assert.Equal(ClaudeMeterIds.Weekly, QuotaRuleId.ClaudeWeekly.MeterId());
    }
}
