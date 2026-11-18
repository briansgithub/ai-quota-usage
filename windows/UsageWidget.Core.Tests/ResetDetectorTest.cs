using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class ResetDetectorTest
{
    private const long Now = 2_000_000_000_000L;
    private const long Hour = 3_600_000L;

    [Fact]
    public void ResetTimePassingFiresOncePerCycle()
    {
        var prevReset = Now - 30_000L;
        var previous = Snapshot(Now - Hour, new[] { Meter("openai.five_hour", 2.0, prevReset) });
        var current = Snapshot(Now, new[] { Meter("openai.five_hour", 100.0, Now + 5 * Hour) });
        var events = ResetDetector.Detect(previous, current, Now);
        Assert.Single(events);
        Assert.Equal("codex.five_hour", events[0].Group);
        Assert.Equal(RestoredReason.ResetTimePassed, events[0].Reason);
        Assert.Equal($"restored:codex.five_hour:{prevReset}", events[0].DedupeKey);
        Assert.Equal(RestoredKind.FiveHour, events[0].Kind);
    }

    [Fact]
    public void RemainingJumpWithoutKnownResetStillFires()
    {
        var previous = Snapshot(Now - Hour, new[] { Meter("openai.weekly", 10.0, Now + 3 * Hour) });
        var current = Snapshot(Now, new[] { Meter("openai.weekly", 40.0, Now + 7 * 24 * Hour) });
        var events = ResetDetector.Detect(previous, current, Now);
        Assert.Single(events);
        Assert.Equal(RestoredReason.RemainingJump, events[0].Reason);
        Assert.Equal("codex.weekly", events[0].Group);
        Assert.StartsWith("restored:codex.weekly:", events[0].DedupeKey);
    }

    [Fact]
    public void SmallRemainingIncreaseIsIgnored()
    {
        var previous = Snapshot(Now - Hour, new[] { Meter("openai.weekly", 40.0, Now + 2 * Hour) });
        var current = Snapshot(Now, new[] { Meter("openai.weekly", 45.0, Now + 2 * Hour) });
        Assert.Empty(ResetDetector.Detect(previous, current, Now));
    }

    [Fact]
    public void ResetAlreadyPastAtPreviousFetchDoesNotRefire()
    {
        var previous = Snapshot(Now - Hour, new[] { Meter("openai.five_hour", 100.0, Now - 2 * Hour) });
        var current = Snapshot(Now, new[] { Meter("openai.five_hour", 100.0, Now + 3 * Hour) });
        Assert.Empty(ResetDetector.Detect(previous, current, Now));
    }

    [Fact]
    public void FirstAppearanceDoesNotNotify()
    {
        var previous = Snapshot(Now - Hour, Array.Empty<UsageMeter>());
        var current = Snapshot(Now, new[] { Meter("openai.five_hour", 100.0, Now + Hour) });
        Assert.Empty(ResetDetector.Detect(previous, current, Now));
    }

    [Fact]
    public void CursorModelsAndPlanResetCollapseToOneEvent()
    {
        var prevReset = Now - 10_000L;
        var previous = Snapshot(Now - Hour, new[]
        {
            Meter("cursor.models", 5.0, prevReset),
            Meter("cursor.other", 0.0, prevReset),
            Meter("cursor.plan_reset", null, prevReset)
        }, "cursor");
        var current = Snapshot(Now, new[]
        {
            Meter("cursor.models", 100.0, Now + 30 * 24 * Hour),
            Meter("cursor.other", 100.0, Now + 30 * 24 * Hour),
            Meter("cursor.plan_reset", null, Now + 30 * 24 * Hour)
        }, "cursor");
        var events = ResetDetector.Detect(previous, current, Now);
        Assert.Single(events);
        Assert.Equal("cursor.plan", events[0].Group);
    }

    [Fact]
    public void GrokWeeklyIsItsOwnGroup()
    {
        var prevReset = Now - 5_000L;
        var previous = Snapshot(Now - Hour, new[] { Meter("cursor.grok_bot", 1.0, prevReset) }, "cursor");
        var current = Snapshot(Now, new[] { Meter("cursor.grok_bot", 100.0, Now + 7 * 24 * Hour) }, "cursor");
        Assert.Equal(new[] { "grok.weekly" }, ResetDetector.Detect(previous, current, Now).Select(e => e.Group).ToArray());
    }

    [Fact]
    public void EmptyPreviousSnapshotYieldsNothing()
    {
        var current = Snapshot(Now, new[] { Meter("openai.five_hour", 100.0, Now + Hour) });
        Assert.Empty(ResetDetector.Detect(UsageSnapshot.Empty(Now - Hour), current, Now));
    }

    private static UsageMeter Meter(string id, double? remaining, long? resetAt) => new(
        id, id, RemainingPercent: remaining, UsedPercent: remaining is { } r ? 100.0 - r : null, ResetsAtEpochMs: resetAt);

    private static UsageSnapshot Snapshot(long fetchedAt, IReadOnlyList<UsageMeter> meters, string providerId = "openai") => new(
        fetchedAt,
        Providers: new[] { new ProviderSnapshot(providerId, providerId, "OK", Meters: meters) });
}
