using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class UsageSectionsTest
{
    private const long ResetMs = 1_793_836_800_000L;
    private const long WeekResetMs = 1_793_404_800_000L;

    [Fact]
    public void EmptySnapshotAlwaysHasFourSoftwareSections()
    {
        var sections = UsageSections.From(UsageSnapshot.Empty(0L));
        Assert.Equal(new[] { "Cursor", "Grok Bot", "Codex", "Claude" }, sections.Select(s => s.Id.Title()).ToArray());
        Assert.Equal(ProviderId.Cursor, sections[0].SignInProvider);
        Assert.Equal(ProviderId.Cursor, sections[1].SignInProvider);
        Assert.Equal(ProviderId.OpenAi, sections[2].SignInProvider);
        Assert.Equal(ProviderStatus.Unsigned, sections[0].Status);
        Assert.Equal(ProviderStatus.Unsigned, sections[1].Status);
        Assert.Equal(ProviderStatus.Unsigned, sections[2].Status);
        Assert.Equal(new[] { "openai.five_hour", "openai.weekly" }, sections[2].MeterList.Select(m => m.Id).ToArray());
        Assert.Equal(ProviderId.Claude, sections[3].SignInProvider);
        Assert.Equal(ProviderStatus.Unsigned, sections[3].Status);
        Assert.Equal(new[] { "claude.five_hour", "claude.weekly" }, sections[3].MeterList.Select(m => m.Id).ToArray());
    }

    [Fact]
    public void SplitsCursorGrokAndCodexAndKeepsWeekly()
    {
        var snapshot = new UsageSnapshot(1L, Providers: new[]
        {
            new ProviderSnapshot("cursor", "Cursor", "OK", PlanLabel: "Pro", Meters: new[]
            {
                new UsageMeter("cursor.models", "Cursor Models", UsedPercent: 17.0, RemainingPercent: 83.0, ResetsAtEpochMs: ResetMs),
                new UsageMeter("cursor.other", "Other Models", UsedPercent: 100.0, RemainingPercent: 0.0, ResetsAtEpochMs: ResetMs),
                new UsageMeter("cursor.plan_reset", "Plan reset", ResetsAtEpochMs: ResetMs),
                new UsageMeter("cursor.grok_bot", "Grok Bot", UsedPercent: 65.0, RemainingPercent: 35.0, ResetsAtEpochMs: WeekResetMs)
            }),
            new ProviderSnapshot("openai", "ChatGPT / Codex", "OK", PlanLabel: "Plus", Meters: new[]
            {
                new UsageMeter("openai.five_hour", "5-hour", UsedPercent: 0.0, RemainingPercent: 100.0, ResetsAtEpochMs: ResetMs),
                new UsageMeter("openai.weekly", "Weekly", UsedPercent: 12.0, RemainingPercent: 88.0, ResetsAtEpochMs: WeekResetMs)
            })
        });
        var sections = UsageSections.From(snapshot);
        Assert.Equal(new[] { "cursor.models", "cursor.other", "cursor.plan_reset" }, sections[0].MeterList.Select(m => m.Id).ToArray());
        Assert.Equal(new[] { "cursor.grok_bot" }, sections[1].MeterList.Select(m => m.Id).ToArray());
        Assert.Equal("Weekly usage", sections[1].MeterList.Single().Title);
        Assert.Equal(new[] { "openai.five_hour", "openai.weekly" }, sections[2].MeterList.Select(m => m.Id).ToArray());
        Assert.Equal(12, sections[2].MeterList[1].DisplayUsedPercent);
        Assert.Equal(88, sections[2].MeterList[1].DisplayRemainingPercent);
        Assert.Equal("83% left", sections[0].MeterList[0].RemainingLabel());
        Assert.Equal("17% used", sections[0].MeterList[0].UsedLabel());
        Assert.Null(sections[0].MeterList[2].RemainingLabel());
        Assert.NotNull(sections[0].MeterList[2].ResetsAtEpochMs);
    }

    [Fact]
    public void CodexShowsOnlyWindowsTheProviderReturned()
    {
        var snapshot = new UsageSnapshot(1L, Providers: new[]
        {
            new ProviderSnapshot("openai", "ChatGPT / Codex", "OK", Meters: new[]
            {
                new UsageMeter("openai.weekly", "Weekly", UsedPercent: 8.0, RemainingPercent: 92.0)
            })
        });
        var meters = UsageSections.From(snapshot)[2].MeterList;
        Assert.Equal(new[] { "openai.weekly" }, meters.Select(m => m.Id).ToArray());
        Assert.DoesNotContain(meters, m => m.Id == "openai.five_hour");
    }

    [Fact]
    public void GrokNeedsCursorSignInWhenCursorIsUnsigned()
    {
        var grok = UsageSections.From(UsageSnapshot.Empty(0L))[1];
        Assert.Equal(ProviderStatus.Unsigned, grok.Status);
        Assert.Equal(ProviderId.Cursor, grok.SignInProvider);
        Assert.Equal("Weekly usage", grok.MeterList.Single().Title);
        Assert.False(grok.StaleValue.IsStale);
    }

    [Fact]
    public void LastGoodMetersStayVisibleWhenSectionIsStale()
    {
        const long now = 1_783_000_000_000L;
        var snapshot = new UsageSnapshot(
            now,
            Providers: new[] { new ProviderSnapshot("openai", "ChatGPT / Codex", "ERROR") },
            SectionHealth: new[]
            {
                new SectionHealth(
                    "codex",
                    LastAttemptEpochMs: now,
                    LastSuccessEpochMs: now - 20 * 60_000L,
                    LastOutcome: "HTTP",
                    ShortReason: "HTTP 503",
                    HttpStatus: 503,
                    LastGoodMeters: new[]
                    {
                        new UsageMeter("openai.five_hour", "5-hour", UsedPercent: 40.0, RemainingPercent: 60.0),
                        new UsageMeter("openai.weekly", "Weekly", UsedPercent: 12.0, RemainingPercent: 88.0)
                    },
                    SignedIn: true)
            });
        var codex = UsageSections.From(snapshot, now)[2];
        Assert.True(codex.StaleValue.IsStale);
        Assert.Equal(StaleSeverity.Red, codex.StaleValue.Severity);
        Assert.Equal("HTTP 503", codex.StaleValue.ShortReason);
        Assert.Equal(40, codex.MeterList[0].DisplayUsedPercent);
        Assert.Equal(12, codex.MeterList[1].DisplayUsedPercent);
        Assert.StartsWith("Last updated", codex.StaleValue.LastUpdatedLine!);
    }

    [Fact]
    public void OnDemandBarsSitOnCursorAndGrokCards()
    {
        var snapshot = new UsageSnapshot(1L, Providers: new[]
        {
            new ProviderSnapshot("cursor", "Cursor", "OK", Meters: new[]
            {
                new UsageMeter("cursor.models", "Cursor Models", UsedPercent: 17.0, RemainingPercent: 83.0),
                new UsageMeter("cursor.other", "Other Models", UsedPercent: 40.0, RemainingPercent: 60.0),
                new UsageMeter("cursor.on_demand", "On-demand  $12.34 / $50", UsedPercent: 24.68, RemainingPercent: 75.32, BarKind: MeterBarKind.Cap),
                new UsageMeter("cursor.plan_reset", "Plan reset", ResetsAtEpochMs: ResetMs),
                new UsageMeter("cursor.grok_bot", "Grok Bot", UsedPercent: 65.0, RemainingPercent: 35.0),
                new UsageMeter("cursor.grok_on_demand", "On-demand  $12.34 / $50", UsedPercent: 24.68, RemainingPercent: 75.32, BarKind: MeterBarKind.Cap)
            })
        });
        var sections = UsageSections.From(snapshot);
        Assert.Equal(new[] { "cursor.models", "cursor.other", "cursor.on_demand", "cursor.plan_reset" }, sections[0].MeterList.Select(m => m.Id).ToArray());
        Assert.Equal(new[] { "cursor.grok_bot", "cursor.grok_on_demand" }, sections[1].MeterList.Select(m => m.Id).ToArray());
        Assert.Equal("Weekly usage", sections[1].MeterList[0].Title);
        Assert.True(sections[0].MeterList[2].IsCapBar);
        Assert.True(sections[1].MeterList[1].IsCapBar);
    }

    [Fact]
    public void OnDemandDisplayHidesEachBarIndependently()
    {
        var snapshot = new UsageSnapshot(1L, Providers: new[]
        {
            new ProviderSnapshot("cursor", "Cursor", "OK", Meters: new[]
            {
                new UsageMeter("cursor.models", "Cursor Models", UsedPercent: 17.0, RemainingPercent: 83.0),
                new UsageMeter("cursor.on_demand", "On-demand  $12.34 / $50", UsedPercent: 24.68, RemainingPercent: 75.32, BarKind: MeterBarKind.Cap),
                new UsageMeter("cursor.grok_bot", "Grok Bot", UsedPercent: 65.0, RemainingPercent: 35.0),
                new UsageMeter("cursor.grok_on_demand", "On-demand  $12.34 / $50", UsedPercent: 24.68, RemainingPercent: 75.32, BarKind: MeterBarKind.Cap)
            })
        });
        var shown = UsageSections.From(snapshot);
        var cursorOff = OnDemandDisplay.Apply(shown, false, true);
        Assert.Equal(new[] { "cursor.models" }, cursorOff[0].MeterList.Select(m => m.Id).ToArray());
        Assert.Equal(new[] { "cursor.grok_bot", "cursor.grok_on_demand" }, cursorOff[1].MeterList.Select(m => m.Id).ToArray());
        var grokOff = OnDemandDisplay.Apply(shown, true, false);
        Assert.Equal(new[] { "cursor.models", "cursor.on_demand" }, grokOff[0].MeterList.Select(m => m.Id).ToArray());
        Assert.Equal(new[] { "cursor.grok_bot" }, grokOff[1].MeterList.Select(m => m.Id).ToArray());
        Assert.Same(shown, OnDemandDisplay.Apply(shown, true, true));
    }
}
