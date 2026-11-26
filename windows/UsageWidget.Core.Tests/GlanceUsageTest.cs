using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class GlanceUsageTest
{
    [Fact]
    public void ListsUsedPercentsAndDropsFullQuotas()
    {
        var snap = Snapshot(
            cursor: new[]
            {
                new UsageMeter("cursor.models", "Cursor Models", UsedPercent: 17, RemainingPercent: 83),
                new UsageMeter("cursor.other", "Other Models", UsedPercent: 100, RemainingPercent: 0),
                new UsageMeter("cursor.plan_reset", "Plan reset", ResetsAtEpochMs: 1),
                new UsageMeter("cursor.grok_bot", "Grok Bot", UsedPercent: 65, RemainingPercent: 35)
            },
            openai: new[]
            {
                new UsageMeter("openai.five_hour", "5-hour", UsedPercent: 40, RemainingPercent: 60),
                new UsageMeter("openai.weekly", "Weekly", UsedPercent: 12, RemainingPercent: 88)
            });
        var glance = GlanceUsage.From(snap, 1);
        Assert.Equal(new[]
        {
            "Cursor Models", "Grok Bot", "Codex 5-hour", "Codex Weekly"
        }, glance.Lines.Select(l => l.Label).ToArray());
        Assert.Equal(new[] { 17, 65, 40, 12 }, glance.Lines.Select(l => l.UsedPercent).ToArray());
        Assert.True(glance.Lines.All(l => l.ExpectedUsedPercent is null));
        Assert.True(glance.Lines.All(l => l.GroupTitle is null));
        Assert.Null(glance.EmptyMessage);
        Assert.Contains("17%", GlanceUsage.Tooltip(glance));
        Assert.True(GlanceUsage.Tooltip(glance).Length <= GlanceUsage.TooltipMaxChars);
    }

    [Fact]
    public void GroupByLimitOrdersLongBeforeShortAndTagsGroupTitles()
    {
        var now = 10L * 24 * 60 * 60 * 1000;
        var halfWeekReset = now + (7L * 24 * 60 * 60 * 1000) / 2;
        var halfFiveHrReset = now + (5L * 60 * 60 * 1000) / 2;
        var snap = Snapshot(
            cursor: new[]
            {
                new UsageMeter("cursor.grok_bot", "Grok Bot", UsedPercent: 80, RemainingPercent: 20, ResetsAtEpochMs: halfWeekReset)
            },
            openai: new[]
            {
                new UsageMeter("openai.five_hour", "5-hour", UsedPercent: 40, RemainingPercent: 60, ResetsAtEpochMs: halfFiveHrReset),
                new UsageMeter("openai.weekly", "Weekly", UsedPercent: 20, RemainingPercent: 80, ResetsAtEpochMs: halfWeekReset)
            });
        var glance = GlanceUsage.From(snap, now, groupByLimit: true);
        Assert.Equal(
            new[] { "Codex Weekly", "Grok Bot", "Codex 5-hour" },
            glance.Lines.Select(l => l.Label).ToArray());
        Assert.Equal(
            new[]
            {
                LimitKind.Long.Title(),
                LimitKind.Long.Title(),
                LimitKind.Short.Title()
            },
            glance.Lines.Select(l => l.GroupTitle).ToArray());
    }

    [Fact]
    public void GlanceLinesCarryOnPaceExpected()
    {
        var reset = PacingPolicy.WindowMs;
        var snap = Snapshot(
            cursor: new[] { new UsageMeter("cursor.models", "Cursor Models", UsedPercent: 17, RemainingPercent: 83) },
            openai: new[]
            {
                new UsageMeter("openai.five_hour", "5-hour", UsedPercent: 40, RemainingPercent: 60, ResetsAtEpochMs: reset)
            });
        var glance = GlanceUsage.From(snap, reset / 2);
        var five = glance.Lines.Single(l => l.Label == "Codex 5-hour");
        Assert.Equal(50, five.ExpectedUsedPercent!.Value, 6);
        Assert.Null(glance.Lines.Single(l => l.Label == "Cursor Models").ExpectedUsedPercent);
    }

    [Fact]
    public void FullGrokBotStaysOnTheGlance()
    {
        var snap = Snapshot(
            cursor: new[]
            {
                new UsageMeter("cursor.models", "Cursor Models", UsedPercent: 81, RemainingPercent: 19),
                new UsageMeter("cursor.other", "Other Models", UsedPercent: 100, RemainingPercent: 0),
                new UsageMeter("cursor.grok_bot", "Grok Bot", UsedPercent: 100, RemainingPercent: 0),
                new UsageMeter("cursor.grok_on_demand", "On-demand  $7.14 / $7", UsedPercent: 102, RemainingPercent: 0, BarKind: MeterBarKind.Cap)
            },
            openai: new[]
            {
                new UsageMeter("openai.five_hour", "5-hour", UsedPercent: 100, RemainingPercent: 0),
                new UsageMeter("openai.weekly", "Weekly", UsedPercent: 85, RemainingPercent: 15)
            });
        var glance = GlanceUsage.From(snap, 1, showCursorOnDemand: false, showGrokOnDemand: false);
        Assert.Equal(new[] { "Cursor Models", "Grok Bot", "Codex Weekly" }, glance.Lines.Select(l => l.Label).ToArray());
        Assert.Equal(100, glance.Lines.Single(l => l.Label == "Grok Bot").UsedPercent);
    }

    [Fact]
    public void AllFullShowsSingleLine()
    {
        var snap = Snapshot(
            cursor: new[] { new UsageMeter("cursor.models", "Cursor Models", UsedPercent: 100, RemainingPercent: 0) },
            openai: new[] { new UsageMeter("openai.weekly", "Weekly", UsedPercent: 100, RemainingPercent: 0) });
        var glance = GlanceUsage.From(snap, 1);
        Assert.True(glance.IsEmpty);
        Assert.Equal("All quotas at 100% used", glance.EmptyMessage);
        Assert.Equal("All quotas at 100% used", GlanceUsage.Tooltip(glance));
    }

    [Fact]
    public void NoDataShowsSingleLine()
    {
        var glance = GlanceUsage.From(UsageSnapshot.Empty(1), 1);
        Assert.Equal("No usage data", glance.EmptyMessage);
    }

    [Fact]
    public void TooltipTruncatesToNotifyIconLimit()
    {
        var lines = Enumerable.Range(0, 20)
            .Select(i => new GlanceLine($"Cursor Models window {i}", 10 + i))
            .ToList();
        var tip = GlanceUsage.Tooltip(new GlanceModel(lines, null));
        Assert.Equal(GlanceUsage.TooltipMaxChars, tip.Length);
        Assert.EndsWith("…", tip);
    }

    [Fact]
    public void RepeatedBuildsCompareLinesStructurally()
    {
        var snap = Snapshot(
            cursor: new[] { new UsageMeter("cursor.models", "Cursor Models", UsedPercent: 17, RemainingPercent: 83) },
            openai: new[] { new UsageMeter("openai.weekly", "Weekly", UsedPercent: 12, RemainingPercent: 88) });
        var first = GlanceUsage.From(snap, 1);
        var second = GlanceUsage.From(snap, 1);
        Assert.NotSame(first.Lines, second.Lines);
        Assert.Equal(first, second);
    }

    [Fact]
    public void UsedLabelIsPercentUsed()
    {
        var meter = new UsageMeter("cursor.models", "Cursor Models", UsedPercent: 37.4, RemainingPercent: 62.6);
        Assert.Equal("37% used", meter.UsedLabel());
        Assert.Equal(37, meter.DisplayUsedPercent);
    }

    [Fact]
    public void OnDemandCapBarsKeepFullAndSkipPaceTick()
    {
        var snap = Snapshot(
            cursor: new[]
            {
                new UsageMeter("cursor.models", "Cursor Models", UsedPercent: 100, RemainingPercent: 0),
                new UsageMeter("cursor.on_demand", "On-demand  $50 / $50", UsedPercent: 100, RemainingPercent: 0, BarKind: MeterBarKind.Cap),
                new UsageMeter("cursor.grok_bot", "Grok Bot", UsedPercent: 10, RemainingPercent: 90),
                new UsageMeter("cursor.grok_on_demand", "On-demand  $12.34 / $50", UsedPercent: 24.68, RemainingPercent: 75.32, BarKind: MeterBarKind.Cap)
            },
            openai: Array.Empty<UsageMeter>());
        var glance = GlanceUsage.From(snap, 1);
        Assert.Equal(new[] { "Cursor On-demand", "Grok Bot", "Grok On-demand" }, glance.Lines.Select(l => l.Label).ToArray());
        Assert.Equal(new[] { 100, 10, 25 }, glance.Lines.Select(l => l.UsedPercent).ToArray());
        Assert.True(glance.Lines.Single(l => l.Label == "Cursor On-demand").IsCapBar);
        Assert.True(glance.Lines.Where(l => l.IsCapBar).All(l => l.ExpectedUsedPercent is null));
        Assert.Contains("25%", GlanceUsage.Tooltip(glance));
    }

    private static UsageSnapshot Snapshot(UsageMeter[] cursor, UsageMeter[] openai) => new(
        1,
        Providers: new[]
        {
            new ProviderSnapshot("cursor", "Cursor", "OK", Meters: cursor),
            new ProviderSnapshot("openai", "ChatGPT / Codex", "OK", Meters: openai)
        },
        SectionHealth: new[]
        {
            new SectionHealth("cursor", LastOutcome: "SUCCESS", SignedIn: true, LastGoodMeters: cursor, LastSuccessEpochMs: 1),
            new SectionHealth("grok_bot", LastOutcome: "SUCCESS", SignedIn: true, LastGoodMeters: cursor, LastSuccessEpochMs: 1),
            new SectionHealth("codex", LastOutcome: "SUCCESS", SignedIn: true, LastGoodMeters: openai, LastSuccessEpochMs: 1)
        });
}
