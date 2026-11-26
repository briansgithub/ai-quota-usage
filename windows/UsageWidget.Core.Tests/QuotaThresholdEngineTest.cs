using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class QuotaThresholdEngineTest
{
    private const long Now = 2_000_000_000_000L;
    private static readonly long GrokReset = Now + 3 * 86_400_000L;
    private static readonly long ModelsReset = Now + 20 * 86_400_000L;
    private static readonly QuotaThresholdPreferences Prefs = new();

    [Fact]
    public void DefaultStepsAreTenThenFiveBelowThirty()
    {
        Assert.Equal(new[] { 90, 80, 70, 60, 50, 40, 30, 25, 20, 15, 10, 5, 0 }, QuotaThresholds.Default);
        Assert.Equal(QuotaThresholds.Default, QuotaThresholds.ParseCsv(""));
        Assert.Equal(new[] { 50, 20, 0 }, QuotaThresholds.ParseCsv("0, 20, 50, 50, 200, -1"));
    }

    [Fact]
    public void FirstObservationSeedsWithoutNotifying()
    {
        var current = LiveSnapshot(grokRemaining: 22.0, modelsRemaining: 45.0);
        var plan = QuotaThresholdEngine.Evaluate(current, Now, Prefs, new Dictionary<string, QuotaThresholdState>(), quiet: false);
        Assert.Empty(plan.Alerts);
        var grok = plan.States[QuotaRuleId.GrokWeekly.Wire()];
        Assert.Equal(22.0, grok.Remaining);
        Assert.Equal(new[] { 90, 80, 70, 60, 50, 40, 30, 25 }, grok.FiredList);
        var models = plan.States[QuotaRuleId.CursorModels.Wire()];
        Assert.Equal(new[] { 90, 80, 70, 60, 50 }, models.FiredList);
        Assert.False(plan.States.ContainsKey(QuotaRuleId.CodexWeekly.Wire()));
    }

    [Fact]
    public void SameThresholdIsDedupedUntilWindowResets()
    {
        var seeded = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 45.0), Now, Prefs, new Dictionary<string, QuotaThresholdState>(), false);
        var notified = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 38.0), Now, Prefs, planDict(seeded), false);
        Assert.Single(notified.Alerts);
        Assert.Contains("60% weekly quota used", notified.Alerts[0].Body);
        var again = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 35.0), Now, Prefs, planDict(notified), false);
        Assert.Empty(again.Alerts);
    }

    [Fact]
    public void MultiCrossingNotifiesOnlyTheLowest()
    {
        var seeded = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 55.0), Now, Prefs, new Dictionary<string, QuotaThresholdState>(), false);
        var plan = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 18.0), Now, Prefs, planDict(seeded), false);
        Assert.Single(plan.Alerts);
        Assert.Contains("80% weekly quota used", plan.Alerts[0].Body);
        Assert.Contains(plan.States[QuotaRuleId.GrokWeekly.Wire()].FiredList, t => t == 20);
        Assert.Contains(plan.States[QuotaRuleId.GrokWeekly.Wire()].FiredList, t => t == 50);
    }

    [Fact]
    public void ResetTimeChangeReseedsWithoutNotify()
    {
        var seeded = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 18.0, grokResetAt: GrokReset), Now, Prefs, new Dictionary<string, QuotaThresholdState>(), false);
        Assert.Contains(20, seeded.States[QuotaRuleId.GrokWeekly.Wire()].FiredList);
        var newReset = GrokReset + 7 * 86_400_000L;
        var nextWindow = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 100.0, grokResetAt: newReset), Now, Prefs, planDict(seeded), false);
        Assert.Empty(nextWindow.Alerts);
        Assert.Empty(nextWindow.States[QuotaRuleId.GrokWeekly.Wire()].FiredList);
        var drop = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 85.0, grokResetAt: newReset), Now, Prefs, planDict(nextWindow), false);
        Assert.Single(drop.Alerts);
        Assert.Contains("10% weekly quota used", drop.Alerts[0].Body);
    }

    [Fact]
    public void PeriodStartAdvanceReseedsEvenIfResetTimestampUnchanged()
    {
        var start1 = Now - 6 * 86_400_000L;
        var start2 = Now;
        var seeded = QuotaThresholdEngine.Evaluate(
            LiveSnapshot(grokRemaining: 18.0, grokResetAt: GrokReset, grokWindowStart: start1),
            Now, Prefs, new Dictionary<string, QuotaThresholdState>(), false);
        Assert.Contains(20, seeded.States[QuotaRuleId.GrokWeekly.Wire()].FiredList);
        var restored = QuotaThresholdEngine.Evaluate(
            LiveSnapshot(grokRemaining: 100.0, grokResetAt: GrokReset, grokWindowStart: start2),
            Now, Prefs, planDict(seeded), false);
        Assert.Empty(restored.Alerts);
        Assert.Empty(restored.States[QuotaRuleId.GrokWeekly.Wire()].FiredList);
        Assert.Equal($"{start2}|{GrokReset}", restored.States[QuotaRuleId.GrokWeekly.Wire()].WindowKey);
    }

    [Fact]
    public void RemainingJumpUpPastBoundaryResetsFired()
    {
        var seeded = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 18.0), Now, Prefs, new Dictionary<string, QuotaThresholdState>(), false);
        var restored = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 100.0), Now, Prefs, planDict(seeded), false);
        Assert.Empty(restored.Alerts);
        Assert.Empty(restored.States[QuotaRuleId.GrokWeekly.Wire()].FiredList);
    }

    [Fact]
    public void StaleOrCachedLastGoodDoesNotFireOrSeed()
    {
        var live = LiveSnapshot(grokRemaining: 45.0);
        var seeded = QuotaThresholdEngine.Evaluate(live, Now, Prefs, new Dictionary<string, QuotaThresholdState>(), false);
        var grokMeter = new UsageMeter("cursor.grok_bot", "Weekly", RemainingPercent: 12.0, UsedPercent: 88.0, ResetsAtEpochMs: GrokReset);
        var hydrated = HealthSynthesis.Hydrate(live);
        var staleHealth = hydrated.HealthList.Select(h =>
            h.SectionId != SoftwareSectionId.GrokBot.Wire()
                ? h
                : h with
                {
                    LastOutcome = "NETWORK",
                    LastSuccessEpochMs = Now - 60_000L,
                    LastGoodMeters = h.LastGoodMeterList.Count > 0 ? h.LastGoodMeterList : new[] { grokMeter }
                }).ToList();
        var stale = hydrated with { SectionHealth = staleHealth };
        var plan = QuotaThresholdEngine.Evaluate(stale, Now, Prefs, planDict(seeded), false);
        Assert.Empty(plan.Alerts);
        Assert.Equal(45.0, plan.States[QuotaRuleId.GrokWeekly.Wire()].Remaining);
    }

    [Fact]
    public void OnDemandCapDoesNotCreateThresholdState()
    {
        var live = LiveSnapshot(grokRemaining: 22.0, modelsRemaining: 45.0);
        var extra = new[]
        {
            new UsageMeter("cursor.on_demand", "On-demand  $45 / $50", UsedPercent: 90, RemainingPercent: 10, BarKind: MeterBarKind.Cap),
            new UsageMeter("cursor.grok_on_demand", "On-demand  $45 / $50", UsedPercent: 90, RemainingPercent: 10, BarKind: MeterBarKind.Cap)
        };
        var providers = live.ProviderList.Select(provider =>
        {
            if (provider.Id != "cursor") return provider;
            return provider with { Meters = provider.MeterList.Concat(extra).ToList() };
        }).ToArray();
        var withCap = live with { Providers = providers };
        var plan = QuotaThresholdEngine.Evaluate(withCap, Now, Prefs, new Dictionary<string, QuotaThresholdState>(), quiet: false);
        Assert.Empty(plan.Alerts);
        Assert.False(plan.States.ContainsKey("cursor.on_demand"));
        Assert.False(plan.States.ContainsKey("cursor.grok_on_demand"));
        Assert.Equal(new[] { QuotaRuleId.GrokWeekly.Wire(), QuotaRuleId.CursorModels.Wire() }.ToHashSet(), plan.States.Keys.ToHashSet());
    }

    [Fact]
    public void UnsignedDoesNotSeed()
    {
        var plan = QuotaThresholdEngine.Evaluate(UsageSnapshot.Empty(Now), Now, Prefs, new Dictionary<string, QuotaThresholdState>(), false);
        Assert.Empty(plan.Alerts);
        Assert.Empty(plan.States);
    }

    [Fact]
    public void CodexRulesExistButDefaultOff()
    {
        var snap = LiveSnapshot(grokRemaining: 40.0, fiveRemaining: 12.0, weeklyRemaining: 12.0);
        var plan = QuotaThresholdEngine.Evaluate(snap, Now, Prefs, new Dictionary<string, QuotaThresholdState>(), false);
        Assert.False(plan.States.ContainsKey(QuotaRuleId.CodexFiveHour.Wire()));
        var on = Prefs with
        {
            EnabledRules = Prefs.EnabledRuleSet.Concat(new[] { QuotaRuleId.CodexFiveHour.Wire() }).ToHashSet()
        };
        var enabled = QuotaThresholdEngine.Evaluate(snap, Now, on, new Dictionary<string, QuotaThresholdState>(), false);
        Assert.Empty(enabled.Alerts);
        Assert.True(enabled.States.ContainsKey(QuotaRuleId.CodexFiveHour.Wire()));
    }

    [Fact]
    public void MasterOffLeavesPriorStateAndPostsNothing()
    {
        var seeded = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 45.0), Now, Prefs, new Dictionary<string, QuotaThresholdState>(), false);
        var plan = QuotaThresholdEngine.Evaluate(
            LiveSnapshot(grokRemaining: 10.0),
            Now,
            Prefs with { MasterEnabled = false },
            planDict(seeded),
            false);
        Assert.Empty(plan.Alerts);
        Assert.Equal(45.0, plan.States[QuotaRuleId.GrokWeekly.Wire()].Remaining);
    }

    [Fact]
    public void QuietHoursSeedButHoldCrossingUntilLater()
    {
        var seeded = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 45.0), Now, Prefs, new Dictionary<string, QuotaThresholdState>(), true);
        Assert.Empty(seeded.Alerts);
        var quietDrop = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 18.0), Now, Prefs, planDict(seeded), true);
        Assert.Empty(quietDrop.Alerts);
        Assert.Equal(45.0, quietDrop.States[QuotaRuleId.GrokWeekly.Wire()].Remaining);
        var after = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 18.0), Now, Prefs, planDict(quietDrop), false);
        Assert.Single(after.Alerts);
        Assert.Contains("80% weekly quota used", after.Alerts[0].Body);
    }

    [Fact]
    public void NotificationCopyMatchesExampleShape()
    {
        var local = DateTime.SpecifyKind(new DateTime(2026, 11, 9, 15, 0, 0), DateTimeKind.Unspecified);
        var mondayThree = NyTime.ToUnixMs(TimeZoneInfo.ConvertTimeToUtc(local, NyTime.Zone));
        var body = QuotaThresholdEngine.BodyFor(QuotaRuleId.GrokWeekly, 20, mondayThree);
        Assert.Equal("Grok Bot: 80% weekly quota used (resets Mon 3:00 PM ET)", body);
        Assert.Equal(QuotaThresholdEngine.TestBody, body);
    }

    [Fact]
    public void CustomThresholdListIsHonored()
    {
        var custom = Prefs with { Thresholds = new[] { 50, 10 } };
        var seeded = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 80.0), Now, custom, new Dictionary<string, QuotaThresholdState>(), false);
        var plan = QuotaThresholdEngine.Evaluate(LiveSnapshot(grokRemaining: 9.0), Now, custom, planDict(seeded), false);
        Assert.Single(plan.Alerts);
        Assert.Contains("90% weekly quota used", plan.Alerts[0].Body);
    }

    private static Dictionary<string, QuotaThresholdState> planDict(QuotaThresholdPlan plan) =>
        plan.States.ToDictionary(kv => kv.Key, kv => kv.Value, StringComparer.OrdinalIgnoreCase);

    private static UsageSnapshot LiveSnapshot(
        double grokRemaining = 80.0,
        double modelsRemaining = 80.0,
        long? grokResetAt = null,
        long? grokWindowStart = null,
        long? modelsResetAt = null,
        double? fiveRemaining = null,
        double? weeklyRemaining = null)
    {
        var cursorMeters = new List<UsageMeter>
        {
            new("cursor.models", "Cursor Models", RemainingPercent: modelsRemaining, UsedPercent: 100.0 - modelsRemaining, ResetsAtEpochMs: modelsResetAt ?? ModelsReset),
            new("cursor.grok_bot", "Weekly", RemainingPercent: grokRemaining, UsedPercent: 100.0 - grokRemaining, ResetsAtEpochMs: grokResetAt ?? GrokReset, WindowStartEpochMs: grokWindowStart)
        };
        var openaiMeters = new List<UsageMeter>();
        if (fiveRemaining is { } five)
            openaiMeters.Add(new("openai.five_hour", "5-hour", RemainingPercent: five, UsedPercent: 100.0 - five, ResetsAtEpochMs: Now + 4 * 3_600_000L));
        if (weeklyRemaining is { } weekly)
            openaiMeters.Add(new("openai.weekly", "Weekly", RemainingPercent: weekly, UsedPercent: 100.0 - weekly, ResetsAtEpochMs: Now + 6 * 86_400_000L));
        return new UsageSnapshot(
            Now,
            Providers: new[]
            {
                new ProviderSnapshot("cursor", "Cursor", "OK", Meters: cursorMeters),
                new ProviderSnapshot("openai", "ChatGPT / Codex", openaiMeters.Count == 0 ? "UNSIGNED" : "OK", Meters: openaiMeters)
            });
    }
}
