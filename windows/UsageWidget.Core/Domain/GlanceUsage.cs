namespace UsageWidget;

public sealed record GlanceLine(string Label, int UsedPercent, double? ExpectedUsedPercent = null, bool IsCapBar = false)
{
    public string UsedLabel => $"{UsedPercent}% used";
}

public sealed record GlanceModel(IReadOnlyList<GlanceLine> Lines, string? EmptyMessage)
{
    public bool IsEmpty => Lines.Count == 0;

    public bool Equals(GlanceModel? other) =>
        other is not null &&
        EmptyMessage == other.EmptyMessage &&
        Lines.SequenceEqual(other.Lines);

    public override int GetHashCode()
    {
        var hash = new HashCode();
        hash.Add(EmptyMessage);
        foreach (var line in Lines) hash.Add(line);
        return hash.ToHashCode();
    }
}

public static class GlanceUsage
{
    public const int TooltipMaxChars = 127;

    public static GlanceModel From(UsageSnapshot snapshot, long nowMs, bool showCursorOnDemand = true, bool showGrokOnDemand = true, IReadOnlySet<string>? hiddenBars = null)
    {
        var lines = new List<GlanceLine>();
        var hadBar = false;
        foreach (var section in WidgetBars.Apply(OnDemandDisplay.Apply(UsageSections.From(snapshot, nowMs), showCursorOnDemand, showGrokOnDemand), hiddenBars))
        {
            foreach (var meter in section.MeterList)
            {
                if (!meter.HasUsageBar) continue;
                var used = meter.DisplayUsedPercent;
                if (used is null) continue;
                hadBar = true;
                // Full included quotas are omitted so the popup stays short, except Grok Bot:
                // a weekly bar at 100% is still the usage line, and dropping it looks like the meter is missing.
                if (used.Value >= 100 && !meter.IsCapBar && meter.Id != MeterIds.Grok) continue;
                var expected = meter.IsCapBar ? null : PaceWindows.ExpectedUsedPercent(meter, nowMs);
                lines.Add(new GlanceLine(Label(section.Id, meter), used.Value, expected, meter.IsCapBar));
            }
        }
        var empty = lines.Count > 0 ? null
            : hadBar ? "All quotas at 100% used"
            : "No usage data";
        return new GlanceModel(lines, empty);
    }

    public static string Tooltip(GlanceModel model)
    {
        if (model.EmptyMessage is { } empty) return Truncate(empty, TooltipMaxChars);
        var text = string.Join(" · ", model.Lines.Select(l => $"{l.Label} {l.UsedPercent}%"));
        return Truncate(text, TooltipMaxChars);
    }

    public static string Label(SoftwareSectionId section, UsageMeter meter) => meter.Id switch
    {
        "cursor.models" => "Cursor Models",
        "cursor.other" => "Cursor Other",
        "cursor.on_demand" => "Cursor On-demand",
        "cursor.grok_bot" => "Grok Bot",
        "cursor.grok_on_demand" => "Grok On-demand",
        "openai.five_hour" => "Codex 5-hour",
        "openai.weekly" => "Codex Weekly",
        "openai.thirty_day" => "Codex 30-day",
        "openai.code_review" => "Codex code review",
        ClaudeMeterIds.FiveHour => "Claude Session",
        ClaudeMeterIds.Weekly => "Claude Weekly",
        ClaudeMeterIds.WeeklyOpus => "Claude Opus",
        ClaudeMeterIds.WeeklySonnet => "Claude Sonnet",
        _ => string.IsNullOrWhiteSpace(meter.Title) ? section.Title() : $"{section.Title()} {meter.Title}"
    };

    private static string Truncate(string text, int max)
    {
        if (text.Length <= max) return text;
        if (max <= 1) return text[..Math.Max(0, max)];
        return text[..(max - 1)] + "…";
    }
}
