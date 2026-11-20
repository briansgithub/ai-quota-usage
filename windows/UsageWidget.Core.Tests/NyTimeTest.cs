using System.Linq;
using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class NyTimeTest
{
    private static readonly long Now = DateTimeOffset.Parse("2026-11-05T15:30:26Z").ToUnixTimeMilliseconds();

    [Fact]
    public void RelativeHoursAndMinutes()
    {
        var ms = NyTime.ParseRelative("Resets in 4h 50m", Now)!.Value;
        Assert.Equal(4 * 60 + 50, (ms - Now) / 60_000);
    }

    [Fact]
    public void RelativeDaysAndHours()
    {
        var ms = NyTime.ParseRelative("Resets in 6d 23h", Now)!.Value;
        Assert.Equal(6 * 24 + 23, (ms - Now) / 3_600_000);
    }

    [Fact]
    public void MonthDayUsesNewYork()
    {
        var ms = NyTime.ParseMonthDay("Nov 11", Now)!.Value;
        var zoned = NyTime.AtZone(ms);
        Assert.Equal(11, zoned.Day);
        Assert.Equal(2026, zoned.Year);
    }

    [Fact]
    public void UnixSecondsVsMillis()
    {
        Assert.Equal(1793910026_000L, NyTime.ParseTimestamp(1793910026.0, Now));
        Assert.Equal(1793910026000L, NyTime.ParseTimestamp(1793910026000.0, Now));
    }

    [Fact]
    public void DurationFormatsHoursMinutesAndDays()
    {
        Assert.Equal("1h 20m", NyTime.FormatDuration(80L * 60_000));
        Assert.Equal("15m", NyTime.FormatDuration(15L * 60_000));
        Assert.Equal("10h", NyTime.FormatDuration(10L * 3_600_000));
        Assert.Equal("1d 4h", NyTime.FormatDuration((24 + 4L) * 3_600_000));
        Assert.Equal("0m", NyTime.FormatDuration(0L));
    }

    [Fact]
    public void CountdownCopy()
    {
        var inFourHours = Now + 4 * 3_600_000 + 50 * 60_000;
        Assert.Contains("4h", NyTime.Countdown(inFourHours, Now));
    }

    [Fact]
    public void ResetSegmentsEmphasizeOnlyClockAndDuration()
    {
        var thuAfternoon = DateTimeOffset.Parse("2026-11-05T20:20:26Z").ToUnixTimeMilliseconds();
        var segments = NyTime.FormatResetSegments(thuAfternoon, Now);
        Assert.Equal(
            new[] { ("Reset: Thu ", false), ("3:20 PM", true), (" (", false), ("4h", true), (")", false) },
            segments.Select(s => (s.Text, s.Emphasized)).ToArray());
    }

    [Fact]
    public void ResetLineUsesNyWeekdayClockAndDaysHours()
    {
        var thuAfternoon = DateTimeOffset.Parse("2026-11-05T20:20:26Z").ToUnixTimeMilliseconds();
        Assert.Equal("Reset: Thu 3:20 PM (4h)", NyTime.FormatResetLine(thuAfternoon, Now));
        var nextThu = DateTimeOffset.Parse("2026-11-12T14:30:26Z").ToUnixTimeMilliseconds();
        Assert.Equal("Reset: Thu 9:30 AM (6d 23h)", NyTime.FormatResetLine(nextThu, Now));
        var wedThreePm = DateTimeOffset.Parse("2026-11-11T20:00:00Z").ToUnixTimeMilliseconds();
        Assert.Equal("Reset: Wed 3 PM (6d 4h)", NyTime.FormatResetLine(wedThreePm, Now));
        var thuTwoThirty = DateTimeOffset.Parse("2026-11-05T19:30:26Z").ToUnixTimeMilliseconds();
        Assert.Equal("Reset: Thu 2:30 PM (4h)", NyTime.FormatResetLine(thuTwoThirty, Now));
        Assert.Equal("06/11", NyTime.FormatDayMonth(DateTimeOffset.Parse("2026-11-06T21:36:00Z").ToUnixTimeMilliseconds()));
    }

    [Fact]
    public void OverlayUtcMidnightDateWithNyClockFromPeriodStart()
    {
        var next = DateTimeOffset.Parse("2026-11-06T00:00:00.000Z").ToUnixTimeMilliseconds();
        var start = DateTimeOffset.Parse("2026-10-30T13:00:00.000Z").ToUnixTimeMilliseconds();
        Assert.True(NyTime.IsUtcMidnight(next));
        var combined = NyTime.WithClockIfDateTruncated(next, start)!.Value;
        var ny = NyTime.AtZone(combined);
        Assert.Equal(6, ny.Day);
        Assert.Equal(9, ny.Hour);
        Assert.Equal(0, ny.Minute);
        Assert.Equal("Reset: Fri 9 AM (22h)", NyTime.FormatResetLine(combined, Now));
        Assert.Equal(next, NyTime.WithClockIfDateTruncated(next, next));
        var timed = DateTimeOffset.Parse("2026-11-06T18:43:19.645Z").ToUnixTimeMilliseconds();
        Assert.Equal(timed, NyTime.WithClockIfDateTruncated(timed, start));
    }

    [Fact]
    public void GrokResetOnNov13_2026RollsDateOnlyNextResetForwardAWeek()
    {
        var local = DateTime.SpecifyKind(new DateTime(2026, 11, 13, 14, 18, 0), DateTimeKind.Unspecified);
        var resetInstant = NyTime.ToUnixMs(TimeZoneInfo.ConvertTimeToUtc(local, NyTime.Zone));
        var nextDateOnly = DateTimeOffset.Parse("2026-11-13T00:00:00.000Z").ToUnixTimeMilliseconds();
        var after = resetInstant + 2 * 60_000L;
        var stamped = NyTime.WithClockIfDateTruncated(nextDateOnly, resetInstant, after)!.Value;
        var ny = NyTime.AtZone(stamped);
        Assert.Equal(20, ny.Day);
        Assert.Equal(11, ny.Month);
        Assert.Equal(14, ny.Hour);
        Assert.Equal(18, ny.Minute);
        Assert.True(stamped > after);
        var beforeReset = resetInstant - 20 * 60_000L;
        var stillThisWeek = NyTime.WithClockIfDateTruncated(
            nextDateOnly,
            DateTimeOffset.Parse("2026-11-06T19:18:00.000Z").ToUnixTimeMilliseconds(),
            beforeReset)!.Value;
        var stillNy = NyTime.AtZone(stillThisWeek);
        Assert.Equal(13, stillNy.Day);
        Assert.Equal(14, stillNy.Hour);
        Assert.Equal(18, stillNy.Minute);
    }
}
