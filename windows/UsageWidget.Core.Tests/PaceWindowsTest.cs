using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class PaceWindowsTest
{
    private const long Hour = 3_600_000L;

    [Fact]
    public void FiveHourMidpointIsFiftyPercentExpected()
    {
        var reset = 10_000_000L;
        var meter = new UsageMeter("openai.five_hour", "5-hour", UsedPercent: 40, RemainingPercent: 60, ResetsAtEpochMs: reset);
        var expected = PaceWindows.ExpectedUsedPercent(meter, reset - PacingPolicy.WindowMs / 2);
        Assert.NotNull(expected);
        Assert.Equal(50, expected!.Value, 6);
        Assert.Equal(PacingPolicy.WindowMs, PaceWindows.KnownLengthMs(meter.Id));
    }

    [Fact]
    public void WeeklyElapsedFractionUsesSevenDays()
    {
        var reset = 20_000_000L;
        var meter = new UsageMeter("openai.weekly", "Weekly", UsedPercent: 12, RemainingPercent: 88, ResetsAtEpochMs: reset);
        var now = reset - PacingPolicy.GrokPeriodMs / 10;
        var expected = PaceWindows.ExpectedUsedPercent(meter, now);
        Assert.Equal(90, expected!.Value, 6);
    }

    [Fact]
    public void CursorBillingCycleUsesExplicitStartAndEnd()
    {
        var start = DateTimeOffset.Parse("2026-10-11T00:00:00Z").ToUnixTimeMilliseconds();
        var end = DateTimeOffset.Parse("2026-11-11T00:00:00Z").ToUnixTimeMilliseconds();
        var now = DateTimeOffset.Parse("2026-11-05T15:30:13Z").ToUnixTimeMilliseconds();
        var meter = new UsageMeter("cursor.models", "Cursor Models", UsedPercent: 17, RemainingPercent: 83,
            ResetsAtEpochMs: end, WindowStartEpochMs: start);
        var expected = PaceWindows.ExpectedUsedPercent(meter, now);
        Assert.NotNull(expected);
        var frac = (now - start) / (double)(end - start) * 100;
        Assert.Equal(frac, expected!.Value, 6);
        Assert.InRange(expected.Value, 80, 86);
    }

    [Fact]
    public void UnknownMeterWithoutWindowIsNeutral()
    {
        var meter = new UsageMeter("mystery", "Mystery", UsedPercent: 40);
        Assert.Null(PaceWindows.Bounds(meter));
        Assert.Null(PaceWindows.ExpectedUsedPercent(meter, 1));
    }

    [Fact]
    public void AfterResetExpectedIsOneHundred()
    {
        var reset = 5_000_000L;
        var meter = new UsageMeter("cursor.grok_bot", "Grok Bot", UsedPercent: 80, ResetsAtEpochMs: reset);
        Assert.Equal(100, PaceWindows.ExpectedUsedPercent(meter, reset + Hour)!.Value, 6);
    }

    [Fact]
    public void BeforeWindowStartExpectedIsZero()
    {
        var reset = 5_000_000L;
        var meter = new UsageMeter("openai.five_hour", "5-hour", UsedPercent: 0, ResetsAtEpochMs: reset);
        var start = reset - PacingPolicy.WindowMs;
        Assert.Equal(0, PaceWindows.ExpectedUsedPercent(meter, start - Hour)!.Value, 6);
    }
}
