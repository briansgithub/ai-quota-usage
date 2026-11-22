using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class RefreshScheduleTest
{
    [Fact]
    public void NextIsLastPeriodicPlusFiveMinutes()
    {
        const long last = 1_000_000L;
        var next = RefreshSchedule.NextEpochMs(last, 0L, last + 1_000L);
        Assert.Equal(last + RefreshSchedule.IntervalMs, next);
        Assert.Equal(5L * 60_000, RefreshSchedule.IntervalMs);
    }

    [Fact]
    public void HealthyDelayIsIntervalPlusBoundedJitter()
    {
        var rng = new Random(7);
        for (var i = 0; i < 500; i++)
        {
            var delay = RefreshSchedule.DelayAfterRefreshMs(0, rng);
            Assert.InRange(delay, RefreshSchedule.IntervalMs, RefreshSchedule.IntervalMs + RefreshSchedule.JitterMaxMs);
        }
    }

    [Theory]
    [InlineData(1, 10L * 60_000)]
    [InlineData(2, 20L * 60_000)]
    [InlineData(3, 30L * 60_000)]
    [InlineData(9, 30L * 60_000)]
    public void BackoffDoublesThenCapsAtThirtyMinutes(int streak, long expectedBaseMs)
    {
        var delay = RefreshSchedule.DelayAfterRefreshMs(streak, new Random(1));
        Assert.InRange(delay, expectedBaseMs, expectedBaseMs + RefreshSchedule.JitterMaxMs);
    }

    [Fact]
    public void NetworkTimeoutAndHttpFailuresGrowStreakOthersDoNot()
    {
        var rateLimited = new[] { new SectionHealth("cursor", LastOutcome: "Http", HttpStatus: 429) };
        Assert.Equal(1, RefreshSchedule.NextFailureStreak(0, rateLimited));
        Assert.Equal(2, RefreshSchedule.NextFailureStreak(1, new[] { new SectionHealth("openai", LastOutcome: "Timeout") }));
        Assert.Equal(3, RefreshSchedule.NextFailureStreak(2, new[] { new SectionHealth("openai", LastOutcome: "Network") }));

        var parseBroken = new[] { new SectionHealth("openai", LastOutcome: "Parse") };
        Assert.Equal(0, RefreshSchedule.NextFailureStreak(4, parseBroken));

        var endpointGone = new[] { new SectionHealth("cursor", LastOutcome: "Http", HttpStatus: 404) };
        Assert.Equal(0, RefreshSchedule.NextFailureStreak(4, endpointGone));

        var authExpired = new[] { new SectionHealth("openai", LastOutcome: "Http", HttpStatus: 401) };
        Assert.Equal(0, RefreshSchedule.NextFailureStreak(4, authExpired));

        var nonJson200 = new[] { new SectionHealth("openai", LastOutcome: "Http", HttpStatus: 200) };
        Assert.Equal(0, RefreshSchedule.NextFailureStreak(4, nonJson200));

        var ok = new[] { new SectionHealth("cursor", LastOutcome: "Success") };
        Assert.Equal(0, RefreshSchedule.NextFailureStreak(4, ok));
    }

    [Fact]
    public void FailureStreakIsBoundedAndAnyPressuredSectionCounts()
    {
        var mixed = new[]
        {
            new SectionHealth("cursor", LastOutcome: "Success"),
            new SectionHealth("openai", LastOutcome: "Http", HttpStatus: 503)
        };
        Assert.Equal(RefreshSchedule.MaxFailureStreak, RefreshSchedule.NextFailureStreak(RefreshSchedule.MaxFailureStreak, mixed));
    }

    [Fact]
    public void RemainingIsClampedAtZero()
    {
        const long next = 5_000L;
        Assert.Equal(2_000L, RefreshSchedule.RemainingMs(next, 3_000L));
        Assert.Equal(0L, RefreshSchedule.RemainingMs(next, 5_000L));
        Assert.Equal(0L, RefreshSchedule.RemainingMs(next, 9_000L));
        Assert.Equal(0L, RefreshSchedule.RemainingMs(0L, 9_000L));
    }

    [Fact]
    public void CountdownFormatsMmSsAndDoesNotGoNegative()
    {
        Assert.Equal("next 07:42", RefreshSchedule.FormatCountdown((7 * 60 + 42) * 1_000L));
        Assert.Equal("next 05:00", RefreshSchedule.FormatCountdown(RefreshSchedule.IntervalMs));
        Assert.Equal("next 00:00", RefreshSchedule.FormatCountdown(0L));
        Assert.Equal("next 00:00", RefreshSchedule.FormatCountdown(-5_000L));
    }

    [Fact]
    public void ChronometerBaseIsElapsedRealtimePlusRemaining()
    {
        var @base = RefreshSchedule.ChronometerBaseElapsedRealtime(20_000L, 5_000L, 100_000L);
        Assert.Equal(115_000L, @base);
    }
}
