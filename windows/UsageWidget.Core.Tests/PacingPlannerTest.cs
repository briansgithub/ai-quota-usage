using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class PacingPlannerTest
{
    private const long Now = 2_000_000_000_000L;
    private const long Hour = 3_600_000L;
    private const long Window = PacingPolicy.WindowMs;

    [Fact]
    public void WindowsNeededCeilsRemainingOverPercentPerWindow()
    {
        Assert.Equal(0, PacingPlanner.WindowsNeeded(0.0, 12.0));
        Assert.Equal(0, PacingPlanner.WindowsNeeded(0.4, 12.0));
        Assert.Equal(1, PacingPlanner.WindowsNeeded(12.0, 12.0));
        Assert.Equal(4, PacingPlanner.WindowsNeeded(48.0, 12.0));
        Assert.Equal(5, PacingPlanner.WindowsNeeded(48.1, 12.0));
        Assert.Equal(9, PacingPlanner.WindowsNeeded(100.0, 12.0));
    }

    [Fact]
    public void LatestStartsWorkBackwardsFromWeeklyReset()
    {
        var weekly = Now + 40 * Hour;
        Assert.Equal(
            new[] { weekly - 20 * Hour, weekly - 15 * Hour, weekly - 10 * Hour, weekly - 5 * Hour },
            PacingPlanner.LatestStarts(weekly, 4));
        Assert.Empty(PacingPlanner.LatestStarts(weekly, 0));
    }

    [Fact]
    public void AvailableWindowSlackIsTimeLeftMinusNeededWindows()
    {
        var weekly = Now + 30 * Hour;
        var result = PacingPlanner.PlanFiveAndWeekly(48.0, weekly, 100.0, Now + Window, Now, 12.0);
        Assert.Equal(FiveHourWindowState.Available, result.FiveHourState);
        Assert.Equal(4, result.ExtraWindowsNeeded);
        Assert.Equal(4, result.WindowsNeeded);
        Assert.Equal(10 * Hour, result.SlackMs);
        Assert.Equal(weekly - 20 * Hour, result.NextLatestStartEpochMs);
        Assert.Equal("Pace: need 4 windows, 10h slack", result.Line);
        Assert.Equal(PaceUrgency.Ok, result.Urgency);
        Assert.False(result.MissedWindow);
        Assert.Equal(0.0, result.WeeklyLostPercent, 2);
        Assert.Equal(weekly - 20 * Hour - PacingPolicy.ComfortableLeadMs, result.ComfortableStartEpochMs);
        Assert.Equal(weekly - 20 * Hour - PacingPolicy.FeasibleLeadMs, result.FeasibleStartEpochMs);
    }

    [Fact]
    public void ActiveWindowCountsUnusedCapacityAndWaitsForFiveHourReset()
    {
        var weekly = Now + 30 * Hour;
        var fiveReset = Now + Hour;
        var result = PacingPlanner.PlanFiveAndWeekly(48.0, weekly, 50.0, fiveReset, Now, 12.0);
        Assert.Equal(FiveHourWindowState.Active, result.FiveHourState);
        Assert.Equal(6.0, result.CurrentWindowDeliverable, 2);
        Assert.Equal(4, result.ExtraWindowsNeeded);
        Assert.Equal(5, result.WindowsNeeded);
        Assert.Equal(9 * Hour, result.SlackMs);
        Assert.Equal("Pace: need 4 more, 9h slack", result.Line);
        Assert.False(result.MissedWindow);
    }

    [Fact]
    public void ExhaustedWindowWaitsForFiveHourResetThenNeedsFullWindows()
    {
        var weekly = Now + 20 * Hour;
        var fiveReset = Now + 2 * Hour;
        var result = PacingPlanner.PlanFiveAndWeekly(24.0, weekly, 0.0, fiveReset, Now, 12.0);
        Assert.Equal(FiveHourWindowState.Exhausted, result.FiveHourState);
        Assert.Equal(2, result.ExtraWindowsNeeded);
        Assert.Equal(8 * Hour, result.SlackMs);
        Assert.Equal("Pace: need 2 windows, 8h slack", result.Line);
        Assert.Equal(PaceUrgency.Tight, result.Urgency);
        Assert.False(result.MissedWindow);
    }

    [Fact]
    public void MissedWindowWhenLatestStartPassesWithFiveHourStillAvailable()
    {
        var weekly = Now + 30 * Hour;
        var latestFirst = weekly - 20 * Hour;
        var afterMiss = latestFirst + 1_000L;
        var result = PacingPlanner.PlanFiveAndWeekly(48.0, weekly, 100.0, afterMiss + Window, afterMiss, 12.0);
        Assert.True(result.MissedWindow);
        Assert.Equal(latestFirst, result.NextLatestStartEpochMs);
    }

    [Fact]
    public void ActiveWindowIsNotAMissEvenIfLatestStartHasPassed()
    {
        var weekly = Now + 30 * Hour;
        var afterLatest = weekly - 20 * Hour + 1_000L;
        var result = PacingPlanner.PlanFiveAndWeekly(48.0, weekly, 40.0, afterLatest + 2 * Hour, afterLatest, 12.0);
        Assert.Equal(FiveHourWindowState.Active, result.FiveHourState);
        Assert.False(result.MissedWindow);
    }

    [Fact]
    public void NegativeSlackEstimatesWeeklyUsageThatWillBeLost()
    {
        var weekly = Now + 10 * Hour;
        var result = PacingPlanner.PlanFiveAndWeekly(48.0, weekly, 100.0, Now + Window, Now, 12.0);
        Assert.Equal(-10 * Hour, result.SlackMs);
        Assert.Equal(24.0, result.WeeklyLostPercent, 2);
        Assert.Equal(PaceUrgency.Impossible, result.Urgency);
        Assert.Contains("likely lost", result.Line);
        Assert.Contains("24%", result.Line);
    }

    [Fact]
    public void FinishThisWindowWhenActiveCapacityCoversRemainingWeekly()
    {
        var weekly = Now + 12 * Hour;
        var result = PacingPlanner.PlanFiveAndWeekly(5.0, weekly, 80.0, Now + 3 * Hour, Now, 12.0);
        Assert.Equal(0, result.ExtraWindowsNeeded);
        Assert.Equal(1, result.WindowsNeeded);
        Assert.Equal("Pace: finish this window, 9h slack", result.Line);
    }

    [Fact]
    public void OnTrackWhenNothingLeftToBurn()
    {
        var result = PacingPlanner.PlanFiveAndWeekly(0.0, Now + 40 * Hour, 100.0, Now + Window, Now);
        Assert.Equal(0, result.ExtraWindowsNeeded);
        Assert.Equal("Pace: on track", result.Line);
        Assert.Equal(PaceUrgency.Ok, result.Urgency);
    }

    [Fact]
    public void ManualResetExpiringBeforeNineWindowsWarnsUnused()
    {
        var credits = new[] { new ManualResetCredit(Now + 8 * Hour, "available", "Full reset") };
        var paces = PacingPlanner.PlanManuals(credits, Now, 12.0);
        Assert.Single(paces);
        Assert.Equal(9, paces[0].WindowsNeeded);
        Assert.Equal(9 * Window, paces[0].TimeNeededMs);
        Assert.True(paces[0].WillExpireUnused);
        Assert.Equal(88.0, paces[0].UnusedPercent, 2);
        Assert.Contains("88%", paces[0].Warning!);
    }

    [Fact]
    public void ManualResetWithEnoughTimeDoesNotWarn()
    {
        var credits = new[] { new ManualResetCredit(Now + 50 * Hour, "available") };
        var paces = PacingPlanner.PlanManuals(credits, Now, 12.0);
        Assert.False(paces[0].WillExpireUnused);
        Assert.Equal(0.0, paces[0].UnusedPercent, 2);
        Assert.Null(paces[0].Warning);
    }

    [Fact]
    public void UsedManualCreditIsIgnored()
    {
        var credits = new[] { new ManualResetCredit(Now + Hour, "used") };
        Assert.Empty(PacingPlanner.PlanManuals(credits, Now));
    }

    [Fact]
    public void WeeklyOnlyWarnsWhenRemainingIsAheadOfEvenPace()
    {
        var period = PacingPolicy.GrokPeriodMs;
        var resetAt = Now + period / 10;
        var result = PacingPlanner.PlanWeeklyOnly(50.0, resetAt, Now, period, "grok_bot");
        Assert.True(result.ShouldWarnUnused);
        Assert.Equal(PaceKind.WeeklyOnly, result.Kind);
        Assert.True(result.WasteGap >= 39.0);
        Assert.Contains("above even pace", result.Line);
    }

    [Fact]
    public void WeeklyOnlyOnTrackWhenRemainingMatchesTimeLeft()
    {
        var period = PacingPolicy.GrokPeriodMs;
        var resetAt = Now + period / 10;
        var result = PacingPlanner.PlanWeeklyOnly(10.0, resetAt, Now, period, "grok_bot");
        Assert.False(result.ShouldWarnUnused);
        Assert.Equal(PaceUrgency.Ok, result.Urgency);
        Assert.Contains("90% used", result.Line);
    }

    [Fact]
    public void ForSectionUsesCodexFiveHourAndWeekly()
    {
        var weekly = Now + 30 * Hour;
        var section = new SoftwareSection(
            SoftwareSectionId.Codex,
            ProviderId.OpenAi,
            ProviderStatus.Ok,
            Meters: new[]
            {
                new UsageMeter("openai.five_hour", "5-hour", RemainingPercent: 100.0, ResetsAtEpochMs: Now + Window),
                new UsageMeter("openai.weekly", "Weekly", RemainingPercent: 48.0, ResetsAtEpochMs: weekly)
            });
        var result = PacingPlanner.ForSection(section, Now, 12.0)!;
        Assert.Equal(PaceKind.FiveAndWeekly, result.Kind);
        Assert.Equal(4, result.ExtraWindowsNeeded);
        Assert.Equal("Pace: need 4 windows, 10h slack", result.Line);
    }

    [Fact]
    public void CriticalSlackUnderTwoHours()
    {
        var result = PacingPlanner.PlanFiveAndWeekly(12.0, Now + 6 * Hour, 100.0, Now + Window, Now, 12.0);
        Assert.Equal(Hour, result.SlackMs);
        Assert.Equal(PaceUrgency.Critical, result.Urgency);
    }
}
