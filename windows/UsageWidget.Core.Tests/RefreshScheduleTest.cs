using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class RefreshScheduleTest
{
    [Fact]
    public void NextIsLastPeriodicPlusFifteenMinutes()
    {
        const long last = 1_000_000L;
        var next = RefreshSchedule.NextEpochMs(last, 0L, last + 1_000L);
        Assert.Equal(last + RefreshSchedule.IntervalMs, next);
        Assert.Equal(15L * 60_000, RefreshSchedule.IntervalMs);
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
        Assert.Equal("next 15:00", RefreshSchedule.FormatCountdown(RefreshSchedule.IntervalMs));
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
