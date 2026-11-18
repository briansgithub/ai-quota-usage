using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class CursorUsageParserTest
{
    private static readonly long SnapshotTime = DateTimeOffset.Parse("2026-11-05T15:30:13Z").ToUnixTimeMilliseconds();

    [Fact]
    public void UsageSummaryMatchesSpendingSnapshot()
    {
        var plan = CursorUsageParser.ParseUsageSummary(Fixture.Load("cursor-usage-summary.json"), SnapshotTime);
        Assert.Equal("Pro", plan.PlanLabel);
        Assert.Equal(17.0, plan.CursorModelsUsedPercent!.Value, 2);
        Assert.Equal(100.0, plan.OtherModelsUsedPercent!.Value, 2);
        var utc = DateTimeOffset.FromUnixTimeMilliseconds(plan.ResetsAtEpochMs!.Value).UtcDateTime;
        Assert.Equal(2026, utc.Year);
        Assert.Equal(11, utc.Month);
        Assert.Equal(11, utc.Day);
        var start = DateTimeOffset.FromUnixTimeMilliseconds(plan.WindowStartEpochMs!.Value).UtcDateTime;
        Assert.Equal(2026, start.Year);
        Assert.Equal(10, start.Month);
        Assert.Equal(11, start.Day);
        Assert.Null(plan.OnDemand);
    }

    [Fact]
    public void SandUsageCamelCaseOverlaysNyClockFromPeriodStart()
    {
        var grok = CursorUsageParser.ParseSandUsage(Fixture.Load("cursor-sand-usage.json"), SnapshotTime);
        Assert.Equal(65.0, grok.UsedPercent, 2);
        Assert.True(grok.HasIncludedAllowance);
        Assert.Equal(DateTimeOffset.Parse("2026-10-30T13:00:00.000Z").ToUnixTimeMilliseconds(), grok.WindowStartEpochMs);
        var reset = NyTime.AtZone(grok.ResetsAtEpochMs!.Value);
        Assert.Equal(6, reset.Day);
        Assert.Equal(9, reset.Hour);
        Assert.Equal(0, reset.Minute);
        Assert.Equal("Reset: Fri 9 AM (22h)", NyTime.FormatResetLine(grok.ResetsAtEpochMs!.Value, SnapshotTime));
    }

    [Fact]
    public void SandUsageAfterNov13_2026ResetRollsDateOnlyNextResetForward()
    {
        var local = DateTime.SpecifyKind(new DateTime(2026, 11, 13, 14, 18, 0), DateTimeKind.Unspecified);
        var resetInstant = NyTime.ToUnixMs(TimeZoneInfo.ConvertTimeToUtc(local, NyTime.Zone));
        var fetchedAt = resetInstant + 3 * 60_000L;
        var json = """
            {
              "currentPeriodStart": "2026-11-13T19:18:00.000Z",
              "nextResetTimestampUtc": "2026-11-13T00:00:00.000Z",
              "usagePercent": 0,
              "hasNonZeroIncludedLimit": true
            }
            """;
        var grok = CursorUsageParser.ParseSandUsage(json, fetchedAt);
        Assert.Equal(0.0, grok.UsedPercent, 2);
        Assert.Equal(resetInstant, grok.WindowStartEpochMs);
        var ny = NyTime.AtZone(grok.ResetsAtEpochMs!.Value);
        Assert.Equal(20, ny.Day);
        Assert.Equal(14, ny.Hour);
        Assert.Equal(18, ny.Minute);
        var meter = CursorUsageParser.ToSnapshot(null, grok).MeterList.Single();
        Assert.Equal("0% used", meter.UsedLabel());
        Assert.Equal(resetInstant, meter.WindowStartEpochMs);
    }

    [Fact]
    public void SandUsageSnakeCaseOverlaysNyClockFromPeriodStart()
    {
        var grok = CursorUsageParser.ParseSandUsage(Fixture.Load("cursor-sand-usage-snake.json"), SnapshotTime);
        Assert.Equal(65.0, grok.UsedPercent, 2);
        var reset = NyTime.AtZone(grok.ResetsAtEpochMs!.Value);
        Assert.Equal(6, reset.Day);
        Assert.Equal(9, reset.Hour);
        Assert.Equal("Reset: Fri 9 AM (22h)", NyTime.FormatResetLine(grok.ResetsAtEpochMs!.Value, SnapshotTime));
    }

    [Fact]
    public void SandUsageKeepsExactTimedReset()
    {
        var json = """
            {
              "currentPeriodStart": "2026-09-11T18:43:19.645Z",
              "nextResetTimestampUtc": "2026-11-06T18:43:19.645Z",
              "usagePercent": 19,
              "hasNonZeroIncludedLimit": true
            }
            """;
        var grok = CursorUsageParser.ParseSandUsage(json, SnapshotTime);
        Assert.Equal(DateTimeOffset.Parse("2026-11-06T18:43:19.645Z").ToUnixTimeMilliseconds(), grok.ResetsAtEpochMs);
        var ny = NyTime.AtZone(grok.ResetsAtEpochMs!.Value);
        Assert.Equal(13, ny.Hour);
        Assert.Equal(43, ny.Minute);
    }

    [Fact]
    public void SandUsageNumericEpochMs()
    {
        var epoch = DateTimeOffset.Parse("2026-11-06T14:00:00Z").ToUnixTimeMilliseconds();
        var json = $$"""
            {
              "nextResetTimestampUtc": {{epoch}},
              "usagePercent": 40,
              "hasNonZeroIncludedLimit": true
            }
            """;
        var grok = CursorUsageParser.ParseSandUsage(json, SnapshotTime);
        Assert.Equal(epoch, grok.ResetsAtEpochMs);
        Assert.Equal("Reset: Fri 9 AM (22h)", NyTime.FormatResetLine(grok.ResetsAtEpochMs!.Value, SnapshotTime));
    }

    [Fact]
    public void SandUsageMidnightUtcWithoutPeriodStartStaysDateOnly()
    {
        var json = """
            {
              "nextResetTimestampUtc": "2026-11-06T00:00:00.000Z",
              "usagePercent": 65,
              "hasNonZeroIncludedLimit": true
            }
            """;
        var grok = CursorUsageParser.ParseSandUsage(json, SnapshotTime);
        Assert.Equal(DateTimeOffset.Parse("2026-11-06T00:00:00.000Z").ToUnixTimeMilliseconds(), grok.ResetsAtEpochMs);
    }

    [Fact]
    public void CombinedSnapshotHasPlanResetAndGrok()
    {
        var plan = CursorUsageParser.ParseUsageSummary(Fixture.Load("cursor-usage-summary.json"), SnapshotTime);
        var grok = CursorUsageParser.ParseSandUsage(Fixture.Load("cursor-sand-usage.json"), SnapshotTime);
        var snapshot = CursorUsageParser.ToSnapshot(plan, grok);
        Assert.Equal(new[] { "cursor.models", "cursor.other", "cursor.plan_reset", "cursor.grok_bot" }, snapshot.MeterList.Select(m => m.Id).ToArray());
        Assert.Equal(83, snapshot.MeterList[0].DisplayRemainingPercent);
        Assert.Equal(0, snapshot.MeterList[1].DisplayRemainingPercent);
        Assert.Equal(plan.ResetsAtEpochMs, snapshot.MeterList[2].ResetsAtEpochMs);
        Assert.Equal(35, snapshot.MeterList[3].DisplayRemainingPercent);
    }

    [Fact]
    public void HtmlFallbackMatchesSpendingPageCopy()
    {
        var extracted = CursorHtmlExtractor.Extract(Fixture.Load("cursor-spending-page.txt"), SnapshotTime);
        Assert.Equal(17.0, extracted.Plan.CursorModelsUsedPercent!.Value, 2);
        Assert.Equal(100.0, extracted.Plan.OtherModelsUsedPercent!.Value, 2);
        Assert.Equal(65.0, extracted.Grok!.UsedPercent, 2);
        Assert.NotNull(extracted.Plan.ResetsAtEpochMs);
        Assert.NotNull(extracted.Grok.ResetsAtEpochMs);
        Assert.Equal(11, NyTime.AtZone(extracted.Plan.ResetsAtEpochMs!.Value).Day);
        Assert.Equal(6, NyTime.AtZone(extracted.Grok.ResetsAtEpochMs!.Value).Day);
        Assert.Null(extracted.Plan.OnDemand);
        Assert.Null(extracted.Grok.OnDemand);
    }

    [Fact]
    public void DisplayMessageFallbackWhenPercentsMissing()
    {
        var json = """
            {
              "billingCycleEnd": "2026-11-11T00:00:00.000Z",
              "membershipType": "pro",
              "autoModelSelectedDisplayMessage": "You've used 17% of your included total usage",
              "namedModelSelectedDisplayMessage": "You've used 100% of your included API usage"
            }
            """;
        var plan = CursorUsageParser.ParseUsageSummary(json, SnapshotTime);
        Assert.Equal(17.0, plan.CursorModelsUsedPercent!.Value, 2);
        Assert.Equal(100.0, plan.OtherModelsUsedPercent!.Value, 2);
    }

    [Fact]
    public void FractionalWirePercentIsAlreadyPercentUsedNotAFraction()
    {
        var json = """
            {
              "billingCycleEnd": "2026-11-11T00:00:00.000Z",
              "membershipType": "pro",
              "individualUsage": {
                "plan": {
                  "autoPercentUsed": 0.4,
                  "apiPercentUsed": 0.711
                }
              }
            }
            """;
        var plan = CursorUsageParser.ParseUsageSummary(json, SnapshotTime);
        Assert.Equal(0.4, plan.CursorModelsUsedPercent!.Value, 4);
        Assert.Equal(0.711, plan.OtherModelsUsedPercent!.Value, 4);
        var snapshot = CursorUsageParser.ToSnapshot(plan, null);
        Assert.Equal("0% used", snapshot.MeterList[0].UsedLabel());
        Assert.Equal("1% used", snapshot.MeterList[1].UsedLabel());
    }

    [Fact]
    public void GrokUsagePercentBelowOneStaysAPercent()
    {
        var json = """
            {
              "usagePercent": 0.4,
              "hasNonZeroIncludedLimit": true,
              "nextResetTimestampUtc": "2026-11-13T00:00:00.000Z"
            }
            """;
        var grok = CursorUsageParser.ParseSandUsage(json, SnapshotTime);
        Assert.Equal(0.4, grok.UsedPercent, 4);
        Assert.Equal("0% used", CursorUsageParser.ToSnapshot(null, grok).MeterList.Single().UsedLabel());
    }

    [Fact]
    public void OnDemandReadsCentsFromIndividualUsage()
    {
        var json = """
            {
              "billingCycleEnd": "2026-11-11T00:00:00.000Z",
              "membershipType": "pro",
              "individualUsage": {
                "plan": { "autoPercentUsed": 17, "apiPercentUsed": 40 },
                "onDemand": { "enabled": true, "used": 1234, "limit": 5000, "remaining": 3766 }
              }
            }
            """;
        var plan = CursorUsageParser.ParseUsageSummary(json, SnapshotTime);
        Assert.Equal(1234.0, plan.OnDemand!.UsedCents, 2);
        Assert.Equal(5000.0, plan.OnDemand.LimitCents, 2);
        Assert.Equal("On-demand  $12.34 / $50", plan.OnDemand.Title());
        var meter = plan.OnDemand.ToMeter("cursor.on_demand");
        Assert.Equal(MeterBarKind.Cap, meter.BarKind);
        Assert.Equal("25% used", meter.UsedLabel());
        var snapshot = CursorUsageParser.ToSnapshot(plan, null);
        Assert.Equal(new[] { "cursor.models", "cursor.other", "cursor.on_demand", "cursor.plan_reset", "cursor.grok_on_demand" }, snapshot.MeterList.Select(m => m.Id).ToArray());
        Assert.Equal("On-demand  $12.34 / $50", snapshot.MeterList[2].Title);
    }

    [Fact]
    public void OnDemandHidesWhenDisabledMissingOrZeroLimit()
    {
        Assert.Null(CursorUsageParser.ParseUsageSummary(
            """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":1},"onDemand":{"enabled":false,"used":1234,"limit":5000}}}""",
            SnapshotTime).OnDemand);
        Assert.Null(CursorUsageParser.ParseUsageSummary(
            """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":1},"onDemand":{"enabled":true,"used":1234,"limit":null}}}""",
            SnapshotTime).OnDemand);
        Assert.Null(CursorUsageParser.ParseUsageSummary(
            """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":1},"onDemand":{"enabled":true,"used":1234,"limit":0}}}""",
            SnapshotTime).OnDemand);
        Assert.Null(CursorUsageParser.ParseUsageSummary(
            """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":1},"onDemand":{"enabled":true,"limit":5000}}}""",
            SnapshotTime).OnDemand);
    }

    [Fact]
    public void OnDemandDerivesSpendFromRemainingAndFallsBackToTeamUsage()
    {
        var remainingOnly = CursorUsageParser.ParseUsageSummary(
            """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":1},"onDemand":{"enabled":true,"limit":5000,"remaining":3766}}}""",
            SnapshotTime);
        Assert.Equal(1234.0, remainingOnly.OnDemand!.UsedCents, 2);
        var team = CursorUsageParser.ParseUsageSummary(
            """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":1},"onDemand":{"enabled":true,"used":0,"limit":null}},"teamUsage":{"onDemand":{"enabled":true,"used":2309,"limit":10000}}}""",
            SnapshotTime);
        Assert.Equal(2309.0, team.OnDemand!.UsedCents, 2);
        Assert.Equal("On-demand  $23.09 / $100", team.OnDemand.Title());
    }

    [Fact]
    public void GrokSandWithoutDollarsFallsBackToCursorOnDemand()
    {
        var plan = CursorUsageParser.ParseUsageSummary(
            """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":17,"apiPercentUsed":40},"onDemand":{"enabled":true,"used":1234,"limit":5000}}}""",
            SnapshotTime);
        var grok = CursorUsageParser.ParseSandUsage(Fixture.Load("cursor-sand-usage.json"), SnapshotTime);
        Assert.Null(grok.OnDemand);
        var snapshot = CursorUsageParser.ToSnapshot(plan, grok);
        var grokCap = snapshot.MeterList.Single(m => m.Id == "cursor.grok_on_demand");
        Assert.Equal("On-demand  $12.34 / $50", grokCap.Title);
        Assert.Equal(MeterBarKind.Cap, grokCap.BarKind);
    }

    [Fact]
    public void GrokSandParsesOnDemandSettingsWhenPresent()
    {
        var grok = CursorUsageParser.ParseSandUsage(
            """
            {
              "usagePercent": 10,
              "hasNonZeroIncludedLimit": true,
              "nextResetTimestampUtc": "2026-11-13T00:00:00.000Z",
              "onDemandSettings": { "enabled": true, "used": 2500, "limit": 5000 }
            }
            """,
            SnapshotTime);
        Assert.Equal(2500.0, grok.OnDemand!.UsedCents, 2);
        var snapshot = CursorUsageParser.ToSnapshot(null, grok);
        Assert.Equal("On-demand  $25 / $50", snapshot.MeterList.Single(m => m.Id == "cursor.grok_on_demand").Title);
    }

    [Fact]
    public void HtmlOnDemandParsesDollarPairAndSkipsDisabledCopy()
    {
        var enabled = CursorHtmlExtractor.Extract(
            """
            Cursor Models 17% used
            Other Models 40% used
            On-demand $12.34 / $50
            """,
            SnapshotTime);
        Assert.Equal("On-demand  $12.34 / $50", enabled.Plan.OnDemand!.Title());
        var disabled = CursorHtmlExtractor.Extract(Fixture.Load("cursor-spending-page.txt"), SnapshotTime);
        Assert.Null(disabled.Plan.OnDemand);
    }
}
