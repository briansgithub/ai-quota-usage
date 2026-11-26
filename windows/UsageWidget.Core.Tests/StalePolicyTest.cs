using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class StalePolicyTest
{
    private const long Now = 1_783_000_000_000L;
    private static readonly UsageMeter[] Meters =
    {
        new("cursor.models", "Cursor Models", UsedPercent: 17.0, RemainingPercent: 83.0)
    };

    [Fact]
    public void RecentSuccessIsNotStale()
    {
        var view = StalePolicy.Evaluate(Healthy(Now - 5 * 60_000L, FetchOutcome.Success), Now);
        Assert.False(view.IsStale);
        Assert.Equal(StaleSeverity.None, view.Severity);
        Assert.False(view.ShouldNotify);
    }

    [Fact]
    public void AgePastTwoRefreshIntervalsIsAmberEvenWithoutError()
    {
        var lastSuccess = Now - StalePolicy.StaleAgeMs - 1_000L;
        var view = StalePolicy.Evaluate(Healthy(lastSuccess, FetchOutcome.Success), Now);
        Assert.True(view.IsStale);
        Assert.Equal(StaleSeverity.Amber, view.Severity);
        Assert.Equal("Refresh overdue", view.ShortReason);
        Assert.Equal("Last updated 30m ago", view.LastUpdatedLine);
        Assert.False(view.ShouldNotify);
    }

    [Fact]
    public void NetworkErrorKeepsLastGoodAsAmberStale()
    {
        var view = StalePolicy.Evaluate(Healthy(Now - 10 * 60_000L, FetchOutcome.Network, "Network error"), Now);
        Assert.True(view.IsStale);
        Assert.Equal(StaleSeverity.Amber, view.Severity);
        Assert.Equal("Network error", view.ShortReason);
        Assert.Equal("Last updated 10m ago", view.LastUpdatedLine);
    }

    [Fact]
    public void Http503IsRedWithHttpReason()
    {
        var view = StalePolicy.Evaluate(Healthy(Now - 15 * 60_000L, FetchOutcome.Http, "HTTP 503", 503), Now);
        Assert.True(view.IsStale);
        Assert.Equal(StaleSeverity.Red, view.Severity);
        Assert.Equal("HTTP 503", view.ShortReason);
    }

    [Fact]
    public void AuthExpiredIsRed()
    {
        var view = StalePolicy.Evaluate(Healthy(Now - 20 * 60_000L, FetchOutcome.AuthExpired, "Sign-in expired"), Now);
        Assert.True(view.IsStale);
        Assert.Equal(StaleSeverity.Red, view.Severity);
        Assert.Equal("Sign-in expired", view.ShortReason);
    }

    [Fact]
    public void ParseFailureIsDataFormatChanged()
    {
        var view = StalePolicy.Evaluate(Healthy(Now - 8 * 60_000L, FetchOutcome.Parse, "Data format changed"), Now);
        Assert.True(view.IsStale);
        Assert.Equal(StaleSeverity.Red, view.Severity);
        Assert.Equal("Data format changed", view.ShortReason);
    }

    [Fact]
    public void FailureOlderThanTwoHoursNotifiesWhenSignedIn()
    {
        var lastSuccess = Now - StalePolicy.NotifyAfterMs - 1_000L;
        var view = StalePolicy.Evaluate(Healthy(lastSuccess, FetchOutcome.Timeout, "Timeout"), Now);
        Assert.True(view.ShouldNotify);
        Assert.Equal("Last updated 2h ago", view.LastUpdatedLine);
    }

    [Fact]
    public void UnsignedWithoutDataIsNotStale()
    {
        var view = StalePolicy.Evaluate(new SectionHealth("cursor", SignedIn: false), Now);
        Assert.False(view.IsStale);
        Assert.False(view.ShouldNotify);
    }

    [Fact]
    public void SignedOutDoesNotAgeStale()
    {
        var view = StalePolicy.Evaluate(new SectionHealth(
            "cursor",
            LastSuccessEpochMs: Now - 3 * 60 * 60_000L,
            LastOutcome: "SUCCESS",
            LastGoodMeters: Meters,
            SignedIn: false), Now);
        Assert.False(view.IsStale);
    }

    [Fact]
    public void ResetPassedSinceLastSuccessMarksAmberStaleUntilRefreshed()
    {
        var lastSuccess = Now - 10 * 60_000L;
        var resetAt = Now - 60_000L;
        var meters = new[] { new UsageMeter("openai.five_hour", "5-hour", UsedPercent: 100.0, RemainingPercent: 0.0, ResetsAtEpochMs: resetAt) };
        var preRefresh = new SectionHealth("codex", LastAttemptEpochMs: lastSuccess, LastSuccessEpochMs: lastSuccess,
            LastOutcome: "SUCCESS", LastGoodMeters: meters, SignedIn: true);
        var stale = StalePolicy.Evaluate(preRefresh, Now);
        Assert.True(stale.IsStale);
        Assert.Equal(StaleSeverity.Amber, stale.Severity);
        Assert.Equal("Reset passed", stale.ShortReason);
        Assert.False(stale.ShouldNotify);

        var postRefresh = preRefresh with
        {
            LastAttemptEpochMs = Now,
            LastSuccessEpochMs = Now,
            LastGoodMeters = new[] { new UsageMeter("openai.five_hour", "5-hour", UsedPercent: 0.0, RemainingPercent: 100.0, ResetsAtEpochMs: Now + PacingPolicy.WindowMs) }
        };
        Assert.False(StalePolicy.Evaluate(postRefresh, Now).IsStale);
    }

    private static SectionHealth Healthy(long lastSuccess, FetchOutcome outcome, string? shortReason = null, int? httpStatus = null) => new(
        "cursor",
        LastAttemptEpochMs: Now - 30_000L,
        LastSuccessEpochMs: lastSuccess,
        LastOutcome: HealthSynthesis.KotlinName(outcome),
        ShortReason: shortReason ?? outcome.ShortReason(httpStatus),
        HttpStatus: httpStatus,
        LastGoodMeters: Meters,
        SignedIn: true);
}
