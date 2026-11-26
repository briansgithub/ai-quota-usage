using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class LimitGroupingTest
{
    private const long HourMs = 60L * 60 * 1000;
    private const long DayMs = 24L * HourMs;
    private const long WeekMs = 7L * DayMs;

    private static UsageMeter Meter(
        string id,
        string title = "M",
        long? windowLengthMs = null,
        long? windowStartMs = null,
        long? resetsAtMs = null,
        double? usedPercent = 40.0,
        MeterBarKind barKind = MeterBarKind.Pace) => new(
            Id: id,
            Title: title,
            UsedPercent: usedPercent,
            RemainingPercent: usedPercent is { } u ? Math.Max(0.0, 100.0 - u) : null,
            ResetsAtEpochMs: resetsAtMs,
            WindowStartEpochMs: windowStartMs,
            WindowLengthMs: windowLengthMs,
            BarKind: barKind);

    private static SoftwareSection Section(SoftwareSectionId id, params UsageMeter[] meters) => new(
        Id: id,
        SignInProvider: id switch
        {
            SoftwareSectionId.Cursor or SoftwareSectionId.GrokBot => ProviderId.Cursor,
            SoftwareSectionId.Codex => ProviderId.OpenAi,
            SoftwareSectionId.Claude => ProviderId.Claude,
            SoftwareSectionId.Gemini => ProviderId.Gemini,
            _ => throw new ArgumentOutOfRangeException(nameof(id))
        },
        Status: meters.Length == 0 ? ProviderStatus.Unsigned : ProviderStatus.Ok,
        Meters: meters);

    [Fact]
    public void ClassifiesByWindowLengthWith24HourCutoff()
    {
        Assert.Equal(LimitKind.Short, LimitGrouping.Classify(Meter("openai.five_hour", windowLengthMs: 5 * HourMs)));
        Assert.Equal(LimitKind.Short, LimitGrouping.Classify(Meter("gemini.daily", windowLengthMs: DayMs)));
        Assert.Equal(LimitKind.Long, LimitGrouping.Classify(Meter("custom.two_day", windowLengthMs: DayMs + 1)));
        Assert.Equal(LimitKind.Long, LimitGrouping.Classify(Meter("openai.weekly", windowLengthMs: WeekMs)));
        Assert.Equal(LimitKind.Long, LimitGrouping.Classify(Meter("openai.thirty_day", windowLengthMs: 30 * DayMs)));
    }

    [Fact]
    public void FallsBackToWindowStartAndResetSpanThenKnownId()
    {
        Assert.Equal(LimitKind.Short, LimitGrouping.Classify(Meter("custom.daily", windowStartMs: 1_000L, resetsAtMs: 1_000L + 5 * HourMs)));
        Assert.Equal(LimitKind.Long, LimitGrouping.Classify(Meter("cursor.models", windowStartMs: 1_000L, resetsAtMs: 1_000L + 30 * DayMs)));
        Assert.Equal(LimitKind.Short, LimitGrouping.Classify(Meter("openai.five_hour")));
        Assert.Equal(LimitKind.Short, LimitGrouping.Classify(Meter(ClaudeMeterIds.FiveHour)));
        Assert.Equal(LimitKind.Long, LimitGrouping.Classify(Meter("openai.weekly")));
        Assert.Equal(LimitKind.Long, LimitGrouping.Classify(Meter(ClaudeMeterIds.Weekly)));
        Assert.Equal(LimitKind.Long, LimitGrouping.Classify(Meter("cursor.grok_bot")));
        Assert.Equal(LimitKind.Long, LimitGrouping.Classify(Meter("unknown.meter")));
    }

    [Fact]
    public void ExplicitWindowLengthBeatsKnownId()
    {
        // A Gemini model reset in 5h (<=24h) is Short even if its id has no PaceWindows entry.
        var fiveHourGemini = Meter("gemini.pro", windowLengthMs: 5 * HourMs);
        Assert.Equal(LimitKind.Short, LimitGrouping.Classify(fiveHourGemini));
        // A Weekly id overridden to a 2h test window respects the meter's own length.
        var shortened = Meter("openai.weekly", windowLengthMs: 2 * HourMs);
        Assert.Equal(LimitKind.Short, LimitGrouping.Classify(shortened));
    }

    [Fact]
    public void SpendCapBarsAlwaysClassifyAsLong()
    {
        var cap = Meter("cursor.on_demand", windowLengthMs: HourMs, barKind: MeterBarKind.Cap);
        Assert.Equal(LimitKind.Long, LimitGrouping.Classify(cap));
        var grokCap = Meter("cursor.grok_on_demand", barKind: MeterBarKind.Cap);
        Assert.Equal(LimitKind.Long, LimitGrouping.Classify(grokCap));
    }

    [Fact]
    public void GroupsPlaceLongLimitsBeforeShortAndPreserveSoftwareOrderOnTies()
    {
        // No reset timestamps -> all bars have null paceDelta, so custom order is preserved.
        var sections = new[]
        {
            Section(SoftwareSectionId.Claude, Meter(ClaudeMeterIds.FiveHour), Meter(ClaudeMeterIds.Weekly)),
            Section(SoftwareSectionId.Cursor, Meter("cursor.models"), Meter("cursor.on_demand", barKind: MeterBarKind.Cap)),
            Section(SoftwareSectionId.Codex, Meter("openai.five_hour"), Meter("openai.weekly")),
            Section(SoftwareSectionId.GrokBot, Meter("cursor.grok_bot"))
        };

        var groups = LimitGrouping.Group(sections, nowMs: 1_000_000L);
        Assert.Equal(new[] { LimitKind.Long, LimitKind.Short }, groups.Select(g => g.Kind).ToArray());

        var longGroup = groups[0];
        Assert.Equal(
            new[] { SoftwareSectionId.Claude, SoftwareSectionId.Cursor, SoftwareSectionId.Codex, SoftwareSectionId.GrokBot },
            longGroup.Entries.Select(e => e.Section.Id).ToArray());
        Assert.Equal(new[] { ClaudeMeterIds.Weekly }, longGroup.Entries[0].Meters.Select(m => m.Id).ToArray());
        Assert.Equal(new[] { "cursor.models", "cursor.on_demand" }, longGroup.Entries[1].Meters.Select(m => m.Id).ToArray());

        var shortGroup = groups[1];
        Assert.Equal(
            new[] { SoftwareSectionId.Claude, SoftwareSectionId.Codex },
            shortGroup.Entries.Select(e => e.Section.Id).ToArray());
        Assert.Equal(new[] { ClaudeMeterIds.FiveHour }, shortGroup.Entries[0].Meters.Select(m => m.Id).ToArray());
        Assert.Equal(new[] { "openai.five_hour" }, shortGroup.Entries[1].Meters.Select(m => m.Id).ToArray());
    }

    [Fact]
    public void EveryMeterAppearsOnceAndPrimaryFlagsFirstGroupPerSoftware()
    {
        var sections = new[]
        {
            Section(SoftwareSectionId.Cursor, Meter("cursor.models")),
            Section(SoftwareSectionId.Codex, Meter("openai.five_hour"), Meter("openai.weekly")),
            Section(SoftwareSectionId.Gemini, Meter("gemini.flash", windowLengthMs: 5 * HourMs))
        };

        var groups = LimitGrouping.Group(sections, nowMs: 1_000_000L);
        var allMeterIds = groups.SelectMany(g => g.Entries).SelectMany(e => e.Meters).Select(m => m.Id).ToList();
        Assert.Equal(
            new HashSet<string> { "cursor.models", "openai.five_hour", "openai.weekly", "gemini.flash" },
            allMeterIds.ToHashSet());
        Assert.Equal(4, allMeterIds.Count);

        var primaryBySoftware = groups
            .SelectMany(g => g.Entries.Select(e => (g.Kind, e)))
            .Where(x => x.e.Primary)
            .ToDictionary(x => x.e.Section.Id, x => x.Kind);
        // Long is displayed first: Codex appears in Long first, so Long is primary and Short is secondary.
        Assert.Equal(LimitKind.Long, primaryBySoftware[SoftwareSectionId.Codex]);
        Assert.Equal(LimitKind.Long, primaryBySoftware[SoftwareSectionId.Cursor]);
        Assert.Equal(LimitKind.Short, primaryBySoftware[SoftwareSectionId.Gemini]);

        var codexShort = groups.Single(g => g.Kind == LimitKind.Short).Entries.Single(e => e.Section.Id == SoftwareSectionId.Codex);
        Assert.False(codexShort.Primary);
    }

    [Fact]
    public void EmptyGroupsAreOmitted()
    {
        var onlyLong = new[] { Section(SoftwareSectionId.GrokBot, Meter("cursor.grok_bot")) };
        var groups = LimitGrouping.Group(onlyLong, nowMs: 1_000_000L);
        Assert.Equal(new[] { LimitKind.Long }, groups.Select(g => g.Kind).ToArray());

        Assert.Empty(LimitGrouping.Group(Array.Empty<SoftwareSection>(), nowMs: 1_000_000L));
    }

    [Fact]
    public void SectionsWithNoMetersLandInNeedsAttentionAtTheEnd()
    {
        var sections = new[]
        {
            Section(SoftwareSectionId.Cursor), // signed out, no meters
            Section(SoftwareSectionId.Codex, Meter("openai.five_hour")),
            Section(SoftwareSectionId.GrokBot, Meter("cursor.grok_bot"))
        };

        var groups = LimitGrouping.Group(sections, nowMs: 1_000_000L);
        Assert.Equal(
            new[] { LimitKind.Long, LimitKind.Short, LimitKind.NoData },
            groups.Select(g => g.Kind).ToArray());
        var needs = groups[2];
        Assert.Equal("Needs attention", needs.Kind.Title());
        Assert.Single(needs.Entries);
        Assert.Equal(SoftwareSectionId.Cursor, needs.Entries[0].Section.Id);
        Assert.True(needs.Entries[0].Primary);
        Assert.Empty(needs.Entries[0].Meters);
    }

    [Fact]
    public void PaceDeltaMatchesBarColorSemantics()
    {
        var now = 10 * DayMs;
        // Halfway through a 7-day window -> expected = 50%.
        var resets = now + WeekMs / 2;
        Assert.Equal(-30.0, LimitGrouping.PaceDelta(Meter("openai.weekly", resetsAtMs: resets, usedPercent: 20.0), now)!.Value, 6);
        Assert.Equal(0.0, LimitGrouping.PaceDelta(Meter("openai.weekly", resetsAtMs: resets, usedPercent: 50.0), now)!.Value, 6);
        Assert.Equal(25.0, LimitGrouping.PaceDelta(Meter("openai.weekly", resetsAtMs: resets, usedPercent: 75.0), now)!.Value, 6);
        // Cap bars and unknown windows have no pace delta.
        Assert.Null(LimitGrouping.PaceDelta(Meter("cursor.on_demand", resetsAtMs: resets, usedPercent: 10.0, barKind: MeterBarKind.Cap), now));
        Assert.Null(LimitGrouping.PaceDelta(Meter("unknown.bar", resetsAtMs: resets, usedPercent: 10.0), now));
    }

    [Fact]
    public void WithinSoftwareBarsBehindEvenPaceComeFirstPreservingOrderWithinBucket()
    {
        var now = 10 * DayMs;
        var halfWeekReset = now + WeekMs / 2; // expected = 50%
        var ahead = Meter(ClaudeMeterIds.Weekly, resetsAtMs: halfWeekReset, usedPercent: 80.0);
        var behindFirst = Meter(ClaudeMeterIds.WeeklyOpus, resetsAtMs: halfWeekReset, usedPercent: 30.0);
        var onPace = Meter("claude.extra", windowLengthMs: WeekMs, resetsAtMs: halfWeekReset, usedPercent: 50.0);
        var behindSecond = Meter(ClaudeMeterIds.WeeklySonnet, resetsAtMs: halfWeekReset, usedPercent: 10.0);
        var noPace = Meter("claude.unknown", usedPercent: 5.0);

        var sections = new[]
        {
            Section(SoftwareSectionId.Claude, ahead, noPace, behindFirst, onPace, behindSecond)
        };
        var meters = LimitGrouping.Group(sections, now).Single().Entries.Single().Meters;
        // Behind-pace meters first in original order, then on/ahead in original order, then no-pace.
        Assert.Equal(
            new[] { ClaudeMeterIds.WeeklyOpus, ClaudeMeterIds.WeeklySonnet, ClaudeMeterIds.Weekly, "claude.extra", "claude.unknown" },
            meters.Select(m => m.Id).ToArray());
    }

    [Fact]
    public void SpendCapsSortAfterPacedBarsInsideSameSoftware()
    {
        var now = 10 * DayMs;
        var cycleStart = now - 15 * DayMs;
        var cycleEnd = now + 15 * DayMs; // expected = 50%
        var cap = Meter("cursor.on_demand", windowStartMs: cycleStart, resetsAtMs: cycleEnd, usedPercent: 5.0, barKind: MeterBarKind.Cap);
        var ahead = Meter("cursor.models", windowStartMs: cycleStart, resetsAtMs: cycleEnd, usedPercent: 90.0);
        var behind = Meter("cursor.other", windowStartMs: cycleStart, resetsAtMs: cycleEnd, usedPercent: 20.0);

        var sections = new[] { Section(SoftwareSectionId.Cursor, cap, ahead, behind) };
        var meters = LimitGrouping.Group(sections, now).Single().Entries.Single().Meters;
        Assert.Equal(new[] { "cursor.other", "cursor.models", "cursor.on_demand" }, meters.Select(m => m.Id).ToArray());
    }

    [Fact]
    public void WithinGroupSoftwareSortedByMostBehindBarWithTiesKeepingCustomOrder()
    {
        var now = 10 * DayMs;
        var halfWeekReset = now + WeekMs / 2; // expected = 50%
        var sections = new[]
        {
            Section(SoftwareSectionId.Cursor, Meter("cursor.models", usedPercent: 10.0)), // no pace -> last
            Section(SoftwareSectionId.GrokBot, Meter("cursor.grok_bot", resetsAtMs: halfWeekReset, usedPercent: 80.0)), // +30
            Section(SoftwareSectionId.Codex, Meter("openai.weekly", resetsAtMs: halfWeekReset, usedPercent: 30.0)), // -20
            Section(SoftwareSectionId.Claude,
                Meter(ClaudeMeterIds.Weekly, resetsAtMs: halfWeekReset, usedPercent: 70.0), // +20
                Meter(ClaudeMeterIds.WeeklySonnet, resetsAtMs: halfWeekReset, usedPercent: 10.0)), // -40 -> drives Claude
            Section(SoftwareSectionId.Gemini, Meter("gemini.weekly", windowLengthMs: WeekMs, resetsAtMs: halfWeekReset, usedPercent: 30.0)) // -20, ties Codex -> stays after Codex
        };

        var order = LimitGrouping.Group(sections, now).Single().Entries.Select(e => e.Section.Id).ToArray();
        Assert.Equal(
            new[]
            {
                SoftwareSectionId.Claude,  // -40
                SoftwareSectionId.Codex,   // -20 (before Gemini in input)
                SoftwareSectionId.Gemini,  // -20 (after Codex in input)
                SoftwareSectionId.GrokBot, // +30
                SoftwareSectionId.Cursor   // no pace -> last
            },
            order);
    }

    [Fact]
    public void ShortAndLongGroupsOrderSoftwareIndependently()
    {
        var now = 10 * DayMs;
        var halfWeekReset = now + WeekMs / 2; // expected = 50%
        var halfFiveHrReset = now + (5 * HourMs) / 2; // expected = 50%
        var sections = new[]
        {
            Section(SoftwareSectionId.Codex,
                Meter("openai.weekly", resetsAtMs: halfWeekReset, usedPercent: 20.0),     // -30 in Long
                Meter("openai.five_hour", resetsAtMs: halfFiveHrReset, usedPercent: 80.0)), // +30 in Short
            Section(SoftwareSectionId.Claude,
                Meter(ClaudeMeterIds.Weekly, resetsAtMs: halfWeekReset, usedPercent: 80.0), // +30 in Long
                Meter(ClaudeMeterIds.FiveHour, resetsAtMs: halfFiveHrReset, usedPercent: 15.0)) // -35 in Short
        };

        var groups = LimitGrouping.Group(sections, now);
        Assert.Equal(
            new[] { SoftwareSectionId.Codex, SoftwareSectionId.Claude },
            groups[0].Entries.Select(e => e.Section.Id).ToArray());
        Assert.Equal(
            new[] { SoftwareSectionId.Claude, SoftwareSectionId.Codex },
            groups[1].Entries.Select(e => e.Section.Id).ToArray());
    }

    [Fact]
    public void PrimaryFollowsDisplayOrderNotSoftwareSortOrder()
    {
        var now = 10 * DayMs;
        var halfWeekReset = now + WeekMs / 2;
        var halfFiveHrReset = now + (5 * HourMs) / 2;
        var sections = new[]
        {
            Section(SoftwareSectionId.Claude,
                Meter(ClaudeMeterIds.Weekly, resetsAtMs: halfWeekReset, usedPercent: 90.0), // ahead in Long
                Meter(ClaudeMeterIds.FiveHour, resetsAtMs: halfFiveHrReset, usedPercent: 5.0)) // way behind in Short
        };
        var groups = LimitGrouping.Group(sections, now);
        Assert.Equal(LimitKind.Long, groups[0].Kind);
        Assert.True(groups[0].Entries.Single().Primary);
        Assert.Equal(LimitKind.Short, groups[1].Kind);
        Assert.False(groups[1].Entries.Single().Primary);
    }

    [Fact]
    public void GroupTitlesMatchAndroidCopy()
    {
        Assert.Equal("Long limits (weekly / monthly)", LimitKind.Long.Title());
        Assert.Equal("Short limits (5-hr)", LimitKind.Short.Title());
        Assert.Equal("Needs attention", LimitKind.NoData.Title());
    }
}
