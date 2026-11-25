namespace UsageWidget;

public sealed record WidgetBarOption(SoftwareSectionId Section, string MeterId, string Label);

/// <summary>
/// Per-bar visibility on the glance surfaces (mini panel, tray popup). The full window
/// always shows every bar. Meters not in <see cref="Catalog"/> (e.g. extra Codex
/// limits) are never hidden.
/// </summary>
public static class WidgetBars
{
    public static IReadOnlyList<SoftwareSectionId> DefaultSectionOrder { get; } = SoftwareSectionIds.All;

    public static IReadOnlyList<WidgetBarOption> Catalog { get; } = new WidgetBarOption[]
    {
        new(SoftwareSectionId.Cursor, "cursor.models", "Models"),
        new(SoftwareSectionId.Cursor, "cursor.other", "Other models"),
        new(SoftwareSectionId.Cursor, "cursor.on_demand", "On-demand"),
        new(SoftwareSectionId.GrokBot, MeterIds.Grok, "Weekly"),
        new(SoftwareSectionId.GrokBot, MeterIds.GrokOnDemand, "On-demand"),
        new(SoftwareSectionId.Codex, "openai.five_hour", "5-hour"),
        new(SoftwareSectionId.Codex, "openai.weekly", "Weekly"),
        new(SoftwareSectionId.Codex, "openai.thirty_day", "30-day"),
        new(SoftwareSectionId.Codex, "openai.code_review", "Review"),
        new(SoftwareSectionId.Claude, ClaudeMeterIds.FiveHour, "Session"),
        new(SoftwareSectionId.Claude, ClaudeMeterIds.Weekly, "Weekly"),
        new(SoftwareSectionId.Claude, ClaudeMeterIds.WeeklySonnet, "Sonnet"),
        new(SoftwareSectionId.Claude, ClaudeMeterIds.WeeklyOpus, "Opus"),
        new(SoftwareSectionId.Gemini, "gemini.gemini-5h", "Gemini 5-hour"),
        new(SoftwareSectionId.Gemini, "gemini.gemini-weekly", "Gemini weekly"),
        new(SoftwareSectionId.Gemini, "gemini.3p-5h", "Third-party 5-hour"),
        new(SoftwareSectionId.Gemini, "gemini.3p-weekly", "Third-party weekly"),
        new(SoftwareSectionId.Gemini, "gemini.argon-weekly", "Argon weekly"),
    };

    public static IReadOnlyList<SoftwareSectionId> NormalizeSectionOrder(IEnumerable<SoftwareSectionId>? order)
    {
        var result = new List<SoftwareSectionId>(DefaultSectionOrder.Count);
        var seen = new HashSet<SoftwareSectionId>();
        if (order is not null)
        {
            foreach (var id in order)
            {
                if (seen.Add(id)) result.Add(id);
            }
        }
        foreach (var id in DefaultSectionOrder)
        {
            if (seen.Add(id)) result.Add(id);
        }
        return result;
    }

    public static IReadOnlyList<SoftwareSectionId> ParseSectionOrder(IEnumerable<string>? wireNames)
    {
        if (wireNames is null) return NormalizeSectionOrder(null);
        var parsed = new List<SoftwareSectionId>();
        foreach (var raw in wireNames)
        {
            if (string.IsNullOrWhiteSpace(raw)) continue;
            var trimmed = raw.Trim();
            foreach (var candidate in SoftwareSectionIds.All)
            {
                if (string.Equals(candidate.Wire(), trimmed, StringComparison.OrdinalIgnoreCase))
                {
                    parsed.Add(candidate);
                    break;
                }
            }
        }
        return NormalizeSectionOrder(parsed);
    }

    public static IReadOnlyList<SoftwareSectionId> MoveSection(
        IEnumerable<SoftwareSectionId>? order,
        SoftwareSectionId section,
        int delta)
    {
        var normalized = NormalizeSectionOrder(order).ToList();
        var index = normalized.IndexOf(section);
        if (index < 0 || delta == 0) return normalized;
        var target = Math.Clamp(index + delta, 0, normalized.Count - 1);
        if (target == index) return normalized;
        normalized.RemoveAt(index);
        normalized.Insert(target, section);
        return normalized;
    }

    /// <summary>
    /// Drops hidden bars and applies custom software ordering. A card whose bars were all
    /// hidden is dropped too, so a service you switched off entirely leaves no empty card;
    /// cards that never had a bar (sign-in prompts, "Waiting for data") stay.
    /// </summary>
    public static IReadOnlyList<SoftwareSection> Apply(
        IReadOnlyList<SoftwareSection> sections,
        IReadOnlySet<string>? hidden,
        IReadOnlyList<SoftwareSectionId>? sectionOrder = null)
    {
        IReadOnlyList<SoftwareSection> filtered;
        if (hidden is null || hidden.Count == 0)
        {
            filtered = sections;
        }
        else
        {
            var result = new List<SoftwareSection>();
            foreach (var section in sections)
            {
                var kept = section.MeterList.Where(m => !hidden.Contains(m.Id)).ToList();
                if (kept.Count == section.MeterList.Count)
                {
                    result.Add(section);
                    continue;
                }
                if (!kept.Any(m => m.HasUsageBar) && Catalog.Where(o => o.Section == section.Id).All(o => hidden.Contains(o.MeterId)))
                    continue;
                result.Add(section with { Meters = kept });
            }
            filtered = result;
        }

        if (sectionOrder is null || sectionOrder.Count == 0) return filtered;
        var normalized = NormalizeSectionOrder(sectionOrder);
        var rank = new Dictionary<SoftwareSectionId, int>(normalized.Count);
        for (var i = 0; i < normalized.Count; i++) rank[normalized[i]] = i;
        return filtered.OrderBy(s => rank.TryGetValue(s.Id, out var r) ? r : int.MaxValue).ToList();
    }
}
