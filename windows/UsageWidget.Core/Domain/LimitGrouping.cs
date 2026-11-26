namespace UsageWidget;

/// <summary>Rate-limit window length class used by the widget's "group by limit" mode.</summary>
public enum LimitKind
{
    /// <summary>Windows of 24 hours or less (5-hour sessions, daily caps).</summary>
    Short,

    /// <summary>Everything longer: weekly, 30-day, monthly billing cycles, and spend caps.</summary>
    Long,

    /// <summary>Software with no bars at all (sign-in prompt, error, loading): kept visible at the end.</summary>
    NoData
}

public static class LimitKinds
{
    public static string Title(this LimitKind kind) => kind switch
    {
        LimitKind.Short => "Short limits (5-hr)",
        LimitKind.Long => "Long limits (weekly / monthly)",
        LimitKind.NoData => "Needs attention",
        _ => throw new ArgumentOutOfRangeException(nameof(kind))
    };
}

/// <summary>
/// One software's slice of a <see cref="LimitGroup"/>: the meters of that group's window class.
/// <see cref="Section"/> is the full original card; <see cref="Primary"/> marks the one entry
/// per software that also carries the card-level lines (stale notice, manual resets, pacing,
/// sign-in prompt) so they are shown once, not once per group.
/// </summary>
public sealed record LimitGroupEntry(
    SoftwareSection Section,
    IReadOnlyList<UsageMeter> Meters,
    bool Primary);

public sealed record LimitGroup(LimitKind Kind, IReadOnlyList<LimitGroupEntry> Entries);

/// <summary>
/// Regroups software cards by rate-limit window length: long windows first, then short, then a
/// trailing group for software with nothing to show. Order inside groups: see <see cref="Group(IReadOnlyList{SoftwareSection}, long)"/>.
/// </summary>
public static class LimitGrouping
{
    /// <summary>24-hour cutoff between short and long rate-limit windows.</summary>
    public const long ShortWindowMaxMs = 24L * 60 * 60 * 1000;

    /// <summary>
    /// Window length from the data model, best source first: the meter's own length, the span
    /// between its window start and reset, then the known length for its id. Null when the
    /// window can't be determined.
    /// </summary>
    public static long? WindowLengthMs(UsageMeter meter)
    {
        if (meter.WindowLengthMs is { } length && length > 0) return length;
        if (meter.WindowStartEpochMs is { } start && meter.ResetsAtEpochMs is { } end && end > start)
            return end - start;
        return PaceWindows.KnownLengthMs(meter.Id);
    }

    /// <summary>
    /// <see cref="LimitKind.Short"/> for windows up to 24 h. Everything else is <see cref="LimitKind.Long"/>,
    /// including odd cases: spend caps (monthly billing cycle) and meters whose window can't be determined,
    /// since an unknown window is far more likely a billing-cycle quota than a rolling short one.
    /// </summary>
    public static LimitKind Classify(UsageMeter meter)
    {
        if (meter.IsCapBar) return LimitKind.Long;
        var length = WindowLengthMs(meter);
        if (length is null) return LimitKind.Long;
        return length.Value <= ShortWindowMaxMs ? LimitKind.Short : LimitKind.Long;
    }

    /// <summary>
    /// Used-% minus even-pace expected-% (the widget's own bar-color delta, see <see cref="UsedGradient"/>);
    /// negative means behind even pace (less used than the clock allows, red bar). Null when
    /// there is no pace marker: spend caps, unknown windows, or no used figure.
    /// </summary>
    public static double? PaceDelta(UsageMeter meter, long nowMs)
    {
        if (meter.IsCapBar) return null;
        var used = meter.UsedPercent ?? (meter.DisplayUsedPercent is { } d ? (double)d : null);
        if (used is null) return null;
        var expected = PaceWindows.ExpectedUsedPercent(meter, nowMs);
        if (expected is null) return null;
        return used.Value - expected.Value;
    }

    /// <summary>0 = behind even pace, 1 = on pace or ahead, 2 = no pace data.</summary>
    private static int PaceRank(UsageMeter meter, long nowMs)
    {
        var delta = PaceDelta(meter, nowMs);
        if (delta is null) return 2;
        return delta.Value < 0.0 ? 0 : 1;
    }

    public static IReadOnlyList<LimitGroup> Group(IReadOnlyList<SoftwareSection> sections) =>
        Group(sections, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

    /// <summary>
    /// Groups are ordered Long, Short, then NoData. Within a group, software are ordered by
    /// their most-behind bar (lowest <see cref="PaceDelta"/>; software with no pace data last),
    /// ties keeping the incoming <paramref name="sections"/> order. Within a software, bars behind
    /// even pace come first, then bars on or ahead of pace, then bars without pace data, each
    /// keeping their original order.
    /// </summary>
    public static IReadOnlyList<LimitGroup> Group(IReadOnlyList<SoftwareSection> sections, long nowMs)
    {
        var shortEntries = new List<LimitGroupEntry>();
        var longEntries = new List<LimitGroupEntry>();
        var noData = new List<LimitGroupEntry>();

        foreach (var section in sections)
        {
            if (section.MeterList.Count == 0)
            {
                noData.Add(new LimitGroupEntry(section, Array.Empty<UsageMeter>(), Primary: true));
                continue;
            }
            var shortMeters = new List<UsageMeter>();
            var longMeters = new List<UsageMeter>();
            foreach (var meter in section.MeterList)
            {
                if (Classify(meter) == LimitKind.Short)
                    shortMeters.Add(meter);
                else
                    longMeters.Add(meter);
            }
            if (shortMeters.Count > 0)
                shortEntries.Add(new LimitGroupEntry(section, ByPace(shortMeters, nowMs), Primary: false));
            if (longMeters.Count > 0)
                longEntries.Add(new LimitGroupEntry(section, ByPace(longMeters, nowMs), Primary: false));
        }

        var seen = new HashSet<SoftwareSectionId>();
        List<LimitGroupEntry> Ordered(List<LimitGroupEntry> entries) =>
            entries
                .OrderBy(entry =>
                {
                    var min = double.PositiveInfinity;
                    foreach (var meter in entry.Meters)
                    {
                        if (PaceDelta(meter, nowMs) is { } delta && delta < min)
                            min = delta;
                    }
                    return min;
                })
                .Select(entry => entry with { Primary = seen.Add(entry.Section.Id) })
                .ToList();

        // Long first: card-level lines (primary) land under each software's first displayed entry.
        var orderedLong = Ordered(longEntries);
        var orderedShort = Ordered(shortEntries);

        var groups = new List<LimitGroup>(3);
        if (orderedLong.Count > 0) groups.Add(new LimitGroup(LimitKind.Long, orderedLong));
        if (orderedShort.Count > 0) groups.Add(new LimitGroup(LimitKind.Short, orderedShort));
        if (noData.Count > 0) groups.Add(new LimitGroup(LimitKind.NoData, noData));
        return groups;
    }

    private static IReadOnlyList<UsageMeter> ByPace(List<UsageMeter> meters, long nowMs) =>
        meters.OrderBy(m => PaceRank(m, nowMs)).ToList();
}
