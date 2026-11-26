using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class WidgetBarsTest
{
    private static UsageSnapshot Snapshot() => new(1L, Providers: new[]
    {
        new ProviderSnapshot("openai", "Codex", "OK", Meters: new[]
        {
            new UsageMeter("openai.five_hour", "5-hour", UsedPercent: 10, RemainingPercent: 90),
            new UsageMeter("openai.weekly", "Weekly", UsedPercent: 20, RemainingPercent: 80)
        }),
        new ProviderSnapshot("claude", "Claude", "OK", Meters: new[]
        {
            new UsageMeter(ClaudeMeterIds.FiveHour, "Session", UsedPercent: 30, RemainingPercent: 70),
            new UsageMeter(ClaudeMeterIds.Weekly, "Weekly", UsedPercent: 40, RemainingPercent: 60)
        })
    });

    [Fact]
    public void NothingHiddenReturnsSameSections()
    {
        var sections = UsageSections.From(Snapshot(), 1L);
        Assert.Same(sections, WidgetBars.Apply(sections, new HashSet<string>()));
    }

    [Fact]
    public void HidesOnlyTheChosenBar()
    {
        var shown = WidgetBars.Apply(UsageSections.From(Snapshot(), 1L), new HashSet<string> { "openai.five_hour" });
        var codex = shown.Single(s => s.Id == SoftwareSectionId.Codex);
        Assert.Equal(new[] { "openai.weekly" }, codex.MeterList.Select(m => m.Id).ToArray());
        Assert.Equal(2, shown.Single(s => s.Id == SoftwareSectionId.Claude).MeterList.Count);
    }

    [Fact]
    public void HidingEveryBarOfAServiceDropsItsCard()
    {
        var hidden = WidgetBars.Catalog.Where(o => o.Section == SoftwareSectionId.Claude).Select(o => o.MeterId).ToHashSet();
        var shown = WidgetBars.Apply(UsageSections.From(Snapshot(), 1L), hidden);
        Assert.DoesNotContain(shown, s => s.Id == SoftwareSectionId.Claude);
        Assert.Contains(shown, s => s.Id == SoftwareSectionId.Codex);
    }

    [Fact]
    public void GlancePopupRespectsHiddenBars()
    {
        var model = GlanceUsage.From(Snapshot(), 1L, hiddenBars: new HashSet<string> { ClaudeMeterIds.Weekly });
        Assert.DoesNotContain(model.Lines, l => l.Label == "Claude Weekly");
        Assert.Contains(model.Lines, l => l.Label == "Claude Session");
    }

    [Fact]
    public void SettingsRoundTripVisibility()
    {
        var settings = new AppSettings(new MemorySettingsStore());
        Assert.True(settings.IsWidgetBarVisible("openai.weekly"));
        settings.SetWidgetBarVisible("openai.weekly", false);
        Assert.False(settings.IsWidgetBarVisible("openai.weekly"));
        settings.SetWidgetBarVisible("openai.weekly", true);
        Assert.Empty(settings.HiddenWidgetBars);
    }

    [Fact]
    public void ReordersSectionsByCustomOrder()
    {
        var order = new[] { SoftwareSectionId.Gemini, SoftwareSectionId.Claude, SoftwareSectionId.Codex };
        var shown = WidgetBars.Apply(UsageSections.From(Snapshot(), 1L), new HashSet<string>(), order);
        Assert.Equal(
            new[]
            {
                SoftwareSectionId.Gemini,
                SoftwareSectionId.Claude,
                SoftwareSectionId.Codex,
                SoftwareSectionId.Cursor,
                SoftwareSectionId.GrokBot
            },
            shown.Select(s => s.Id).ToArray());
    }

    [Fact]
    public void ParseSectionOrderDeduplicatesAndIgnoresUnknowns()
    {
        var parsed = WidgetBars.ParseSectionOrder(new[] { " claude ", "bogus", "gemini", "CLAUDE", "" });
        Assert.Equal(
            new[]
            {
                SoftwareSectionId.Claude,
                SoftwareSectionId.Gemini,
                SoftwareSectionId.Cursor,
                SoftwareSectionId.GrokBot,
                SoftwareSectionId.Codex
            },
            parsed);
    }

    [Fact]
    public void MoveSectionMovesAndClampsAtBounds()
    {
        var initial = WidgetBars.DefaultSectionOrder;
        Assert.Equal(initial, WidgetBars.MoveSection(initial, SoftwareSectionId.Cursor, -1));
        Assert.Equal(initial, WidgetBars.MoveSection(initial, SoftwareSectionId.Gemini, 1));

        var movedUp = WidgetBars.MoveSection(initial, SoftwareSectionId.Gemini, -1);
        Assert.Equal(
            new[]
            {
                SoftwareSectionId.Cursor,
                SoftwareSectionId.GrokBot,
                SoftwareSectionId.Codex,
                SoftwareSectionId.Gemini,
                SoftwareSectionId.Claude
            },
            movedUp);
    }

    [Fact]
    public void GlancePopupRespectsSectionOrder()
    {
        var order = new[] { SoftwareSectionId.Claude, SoftwareSectionId.Codex };
        var model = GlanceUsage.From(Snapshot(), 1L, sectionOrder: order);
        Assert.Equal(
            new[] { "Claude Session", "Claude Weekly", "Codex 5-hour", "Codex Weekly" },
            model.Lines.Select(l => l.Label).ToArray());
    }

    [Fact]
    public void SettingsRoundTripSectionOrder()
    {
        var store = new MemorySettingsStore();
        var settings = new AppSettings(store);
        Assert.Equal(WidgetBars.DefaultSectionOrder, settings.WidgetSectionOrder);

        settings.MoveWidgetSection(SoftwareSectionId.Gemini, -1);
        Assert.Equal(SoftwareSectionId.Gemini, settings.WidgetSectionOrder[3]);
        Assert.Equal(SoftwareSectionId.Claude, settings.WidgetSectionOrder[4]);

        var reloaded = new AppSettings(store);
        Assert.Equal(settings.WidgetSectionOrder, reloaded.WidgetSectionOrder);
    }
}
