using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class UsageAlertEngineTest
{
    private const long Now = 2_000_000_000_000L;
    private const long Hour = 3_600_000L;
    private static readonly AlertPreferences Prefs = new();

    [Fact]
    public void RestoredEventIsDedupedOnSecondEvaluate()
    {
        var prevReset = Now - 20_000L;
        var previous = OpenaiSnapshot(Now - Hour, 3.0, prevReset, 80.0, Now + 40 * Hour);
        var current = OpenaiSnapshot(Now, 100.0, Now + 5 * Hour, 80.0, Now + 40 * Hour);
        var first = UsageAlertEngine.Evaluate(previous, current, Now, Prefs, Array.Empty<string>());
        Assert.Equal(1, first.Alerts.Count(a => a.Channel == AlertChannel.Restored));
        Assert.Contains("5-hour", first.Alerts.First(a => a.Channel == AlertChannel.Restored).Title);
        Assert.Contains("(0% used)", first.Alerts.First(a => a.Channel == AlertChannel.Restored).Body);
        var second = UsageAlertEngine.Evaluate(previous, current, Now, Prefs, first.NewSeenKeys);
        Assert.DoesNotContain(second.Alerts, a => a.Channel == AlertChannel.Restored);
    }

    [Fact]
    public void QuietHoursSuppressAlertsButKeepWakesAndDoNotDedupe()
    {
        var local = DateTime.SpecifyKind(new DateTime(2026, 11, 6, 1, 0, 0), DateTimeKind.Unspecified);
        var epoch = NyTime.ToUnixMs(TimeZoneInfo.ConvertTimeToUtc(local, NyTime.Zone));
        var prevReset = epoch - 10_000L;
        var previous = OpenaiSnapshot(epoch - Hour, 1.0, prevReset, 48.0, epoch + 30 * Hour);
        var current = OpenaiSnapshot(epoch, 100.0, epoch + 5 * Hour, 48.0, epoch + 30 * Hour);
        var plan = UsageAlertEngine.Evaluate(previous, current, epoch, Prefs with { QuietHoursEnabled = true }, Array.Empty<string>());
        Assert.Empty(plan.Alerts);
        Assert.Empty(plan.NewSeenKeys);
        Assert.Contains(plan.Wakes, w => w.UniqueName.StartsWith("timed-reset-"));
    }

    [Fact]
    public void RemainingJumpOverThresholdNotifiesWeekly()
    {
        var previous = OpenaiSnapshot(Now - Hour, 50.0, Now + 2 * Hour, 10.0, Now + 4 * Hour);
        var current = OpenaiSnapshot(Now, 50.0, Now + 2 * Hour, 100.0, Now + 7 * 24 * Hour);
        var plan = UsageAlertEngine.Evaluate(previous, current, Now, Prefs, Array.Empty<string>());
        Assert.Contains(plan.Alerts, a => a.Title.Contains("weekly", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void ComfortableStartReminderUsesOneHourTwentyCopy()
    {
        var weekly = Now + 30 * Hour;
        var latestFirst = weekly - 20 * Hour;
        var comfortable = latestFirst - PacingPolicy.ComfortableLeadMs;
        var previous = OpenaiSnapshot(comfortable - Hour, 100.0, comfortable + 5 * Hour, 48.0, weekly);
        var current = OpenaiSnapshot(comfortable, 100.0, comfortable + 5 * Hour, 48.0, weekly);
        var plan = UsageAlertEngine.Evaluate(previous, current, comfortable + 1_000L, Prefs, Array.Empty<string>());
        var reminder = plan.Alerts.First(a => a.DedupeKey.Contains("comfortable"));
        Assert.Contains("to stay on pace", reminder.Body);
        Assert.True(reminder.Body.Contains("1h 20m") || reminder.Body.Contains("1h"));
        Assert.Equal(AlertChannel.Pacing, reminder.Channel);
    }

    [Fact]
    public void MissedWindowAlertAndDisabledTypeIsSkipped()
    {
        var weekly = Now + 30 * Hour;
        var afterMiss = weekly - 20 * Hour + 2_000L;
        var current = OpenaiSnapshot(afterMiss, 100.0, afterMiss + 5 * Hour, 48.0, weekly);
        var previous = current with { FetchedAtEpochMs = afterMiss - Hour };
        var on = UsageAlertEngine.Evaluate(previous, current, afterMiss, Prefs, Array.Empty<string>());
        Assert.Contains(on.Alerts, a => a.DedupeKey.StartsWith("pace-missed:"));
        var off = UsageAlertEngine.Evaluate(previous, current, afterMiss, Prefs with { NotifyPacingMissed = false }, Array.Empty<string>());
        Assert.DoesNotContain(off.Alerts, a => a.DedupeKey.StartsWith("pace-missed:"));
    }

    [Fact]
    public void SchedulesResetWakeShortlyAfterKnownResetTime()
    {
        var resetAt = Now + 90 * 60_000L;
        var current = OpenaiSnapshot(Now, 10.0, resetAt, 80.0, Now + 40 * Hour);
        var plan = UsageAlertEngine.Evaluate(UsageSnapshot.Empty(Now - Hour), current, Now, Prefs, Array.Empty<string>());
        var wake = plan.Wakes.First(w => w.UniqueName == "timed-reset-codex.five_hour");
        Assert.Equal(resetAt + UsageAlertEngine.ResetWakePaddingMs, wake.AtEpochMs);
    }

    [Fact]
    public void NotificationDedupeDropsOldestAfterCap()
    {
        var seen = Enumerable.Range(1, NotificationDedupe.MaxKeys).Select(i => $"k{i}").ToList();
        var next = NotificationDedupe.Remember(seen, "new");
        Assert.Equal(NotificationDedupe.MaxKeys, next.Count);
        Assert.Contains("new", next);
        Assert.DoesNotContain("k1", next);
        Assert.False(NotificationDedupe.ShouldNotify(next, "new"));
        Assert.True(NotificationDedupe.ShouldNotify(next, "k1"));
    }

    [Fact]
    public void ExpiringManualResetPostsPacingAlert()
    {
        var current = OpenaiSnapshot(Now, 100.0, Now + 5 * Hour, 10.0, Now + 40 * Hour);
        var openai = current.ProviderList[0] with
        {
            ManualResets = new ManualResets(1, new[] { new ManualResetCredit(Now + 8 * Hour, "available") })
        };
        current = current with { Providers = new[] { openai } };
        var plan = UsageAlertEngine.Evaluate(current with { FetchedAtEpochMs = Now - Hour }, current, Now, Prefs, Array.Empty<string>());
        Assert.Contains(plan.Alerts, a => a.DedupeKey.StartsWith("pace-expire:"));
    }

    private static UsageSnapshot OpenaiSnapshot(long fetchedAt, double fiveRemaining, long fiveReset, double weeklyRemaining, long weeklyReset) => new(
        fetchedAt,
        Providers: new[]
        {
            new ProviderSnapshot(
                "openai",
                "ChatGPT / Codex",
                "OK",
                Meters: new[]
                {
                    new UsageMeter("openai.five_hour", "5-hour", RemainingPercent: fiveRemaining, UsedPercent: 100.0 - fiveRemaining, ResetsAtEpochMs: fiveReset),
                    new UsageMeter("openai.weekly", "Weekly", RemainingPercent: weeklyRemaining, UsedPercent: 100.0 - weeklyRemaining, ResetsAtEpochMs: weeklyReset)
                })
        });
}
