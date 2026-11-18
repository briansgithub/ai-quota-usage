namespace UsageWidget;

public sealed record WidgetBarOption(SoftwareSectionId Section, string MeterId, string Label);

/// <summary>
/// Per-bar visibility on the glance surfaces (mini panel, tray popup). The full window
/// always shows every bar. Meters not in <see cref="Catalog"/> (e.g. extra Codex
/// limits) are never hidden.
/// </summary>
public static class WidgetBars
{
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
    };

    /// <summary>
    /// Drops hidden bars. A card whose bars were all hidden is dropped too, so a service
    /// you switched off entirely leaves no empty card; cards that never had a bar
    /// (sign-in prompts, "Waiting for data") stay.
    /// </summary>
    public static IReadOnlyList<SoftwareSection> Apply(IReadOnlyList<SoftwareSection> sections, IReadOnlySet<string>? hidden)
    {
        if (hidden is null || hidden.Count == 0) return sections;
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
        return result;
    }
}
