namespace UsageWidget;

public static class QuotaThresholds
{
    public static readonly IReadOnlyList<int> Default = new[] { 90, 80, 70, 60, 50, 40, 30, 25, 20, 15, 10, 5, 0 };
    public const double JumpReset = 15.0;
    public const string DefaultCsv = "90,80,70,60,50,40,30,25,20,15,10,5,0";

    public static List<int> Normalize(IEnumerable<int>? raw)
    {
        var list = (raw ?? Array.Empty<int>())
            .Where(v => v is >= 0 and <= 100)
            .Distinct()
            .OrderByDescending(v => v)
            .ToList();
        return list.Count == 0 ? Default.ToList() : list;
    }

    public static List<int> ParseCsv(string? csv)
    {
        if (string.IsNullOrWhiteSpace(csv)) return Default.ToList();
        var parsed = csv.Split(',', ';', ' ', '\n', '\t')
            .Select(token => token.Trim().TrimEnd('%').Trim())
            .Where(token => token.Length > 0)
            .Select(token => int.TryParse(token, out var n) ? n : (int?)null)
            .Where(n => n.HasValue)
            .Select(n => n!.Value);
        return Normalize(parsed);
    }

    public static string ToCsv(IEnumerable<int>? list = null) => string.Join(",", Normalize(list ?? Default));
}

public enum QuotaRuleId
{
    GrokWeekly,
    CursorModels,
    CodexFiveHour,
    CodexWeekly,
    ClaudeFiveHour,
    ClaudeWeekly,
    GeminiFiveHour,
    GeminiWeekly,
    GeminiThirdPartyFiveHour,
    GeminiThirdPartyWeekly,
    GeminiArgonWeekly
}

public static class QuotaRuleIds
{
    public static string Wire(this QuotaRuleId id) => id switch
    {
        QuotaRuleId.GrokWeekly => "grok.weekly",
        QuotaRuleId.CursorModels => "cursor.models",
        QuotaRuleId.CodexFiveHour => "codex.five_hour",
        QuotaRuleId.CodexWeekly => "codex.weekly",
        QuotaRuleId.ClaudeFiveHour => "claude.five_hour",
        QuotaRuleId.ClaudeWeekly => "claude.weekly",
        QuotaRuleId.GeminiArgonWeekly => "gemini.argon-weekly",
        QuotaRuleId.GeminiThirdPartyWeekly => "gemini.3p-weekly",
        QuotaRuleId.GeminiThirdPartyFiveHour => "gemini.3p-5h",
        QuotaRuleId.GeminiWeekly => "gemini.gemini-weekly",
        QuotaRuleId.GeminiFiveHour => "gemini.gemini-5h",
        _ => throw new ArgumentOutOfRangeException(nameof(id))
    };

    public static string MeterId(this QuotaRuleId id) => id switch
    {
        QuotaRuleId.GrokWeekly => "cursor.grok_bot",
        QuotaRuleId.CursorModels => "cursor.models",
        QuotaRuleId.CodexFiveHour => "openai.five_hour",
        QuotaRuleId.CodexWeekly => "openai.weekly",
        QuotaRuleId.ClaudeFiveHour => ClaudeMeterIds.FiveHour,
        QuotaRuleId.ClaudeWeekly => ClaudeMeterIds.Weekly,
        QuotaRuleId.GeminiArgonWeekly => "gemini.argon-weekly",
        QuotaRuleId.GeminiThirdPartyWeekly => "gemini.3p-weekly",
        QuotaRuleId.GeminiThirdPartyFiveHour => "gemini.3p-5h",
        QuotaRuleId.GeminiWeekly => "gemini.gemini-weekly",
        QuotaRuleId.GeminiFiveHour => "gemini.gemini-5h",
        _ => throw new ArgumentOutOfRangeException(nameof(id))
    };

    public static SoftwareSectionId Section(this QuotaRuleId id) => id switch
    {
        QuotaRuleId.GrokWeekly => SoftwareSectionId.GrokBot,
        QuotaRuleId.CursorModels => SoftwareSectionId.Cursor,
        QuotaRuleId.CodexFiveHour => SoftwareSectionId.Codex,
        QuotaRuleId.CodexWeekly => SoftwareSectionId.Codex,
        QuotaRuleId.ClaudeFiveHour => SoftwareSectionId.Claude,
        QuotaRuleId.ClaudeWeekly => SoftwareSectionId.Claude,
        QuotaRuleId.GeminiArgonWeekly => SoftwareSectionId.Gemini,
        QuotaRuleId.GeminiThirdPartyWeekly => SoftwareSectionId.Gemini,
        QuotaRuleId.GeminiThirdPartyFiveHour => SoftwareSectionId.Gemini,
        QuotaRuleId.GeminiWeekly => SoftwareSectionId.Gemini,
        QuotaRuleId.GeminiFiveHour => SoftwareSectionId.Gemini,
        _ => throw new ArgumentOutOfRangeException(nameof(id))
    };

    public static string ServiceLabel(this QuotaRuleId id) => id switch
    {
        QuotaRuleId.GrokWeekly => "Grok Bot",
        QuotaRuleId.CursorModels => "Cursor",
        QuotaRuleId.CodexFiveHour => "Codex",
        QuotaRuleId.CodexWeekly => "Codex",
        QuotaRuleId.ClaudeFiveHour => "Claude",
        QuotaRuleId.ClaudeWeekly => "Claude",
        QuotaRuleId.GeminiArgonWeekly => "Gemini",
        QuotaRuleId.GeminiThirdPartyWeekly => "Gemini",
        QuotaRuleId.GeminiThirdPartyFiveHour => "Gemini",
        QuotaRuleId.GeminiWeekly => "Gemini",
        QuotaRuleId.GeminiFiveHour => "Gemini",
        _ => throw new ArgumentOutOfRangeException(nameof(id))
    };

    public static string WindowLabel(this QuotaRuleId id) => id switch
    {
        QuotaRuleId.GrokWeekly => "weekly",
        QuotaRuleId.CursorModels => "models",
        QuotaRuleId.CodexFiveHour => "5-hour",
        QuotaRuleId.CodexWeekly => "weekly",
        QuotaRuleId.ClaudeFiveHour => "session",
        QuotaRuleId.ClaudeWeekly => "weekly",
        QuotaRuleId.GeminiArgonWeekly => "Argon weekly",
        QuotaRuleId.GeminiThirdPartyWeekly => "third-party weekly",
        QuotaRuleId.GeminiThirdPartyFiveHour => "third-party 5-hour",
        QuotaRuleId.GeminiWeekly => "weekly",
        QuotaRuleId.GeminiFiveHour => "5-hour",
        _ => throw new ArgumentOutOfRangeException(nameof(id))
    };

    public static bool DefaultEnabled(this QuotaRuleId id) =>
        id is QuotaRuleId.GrokWeekly or QuotaRuleId.CursorModels or QuotaRuleId.ClaudeFiveHour or QuotaRuleId.ClaudeWeekly;

    public static int NotificationId(this QuotaRuleId id) => id switch
    {
        QuotaRuleId.GrokWeekly => 6401,
        QuotaRuleId.CursorModels => 6402,
        QuotaRuleId.CodexFiveHour => 6403,
        QuotaRuleId.CodexWeekly => 6404,
        QuotaRuleId.ClaudeFiveHour => 6405,
        QuotaRuleId.ClaudeWeekly => 6406,
        QuotaRuleId.GeminiArgonWeekly => 6411,
        QuotaRuleId.GeminiThirdPartyWeekly => 6410,
        QuotaRuleId.GeminiThirdPartyFiveHour => 6409,
        QuotaRuleId.GeminiWeekly => 6408,
        QuotaRuleId.GeminiFiveHour => 6407,
        _ => 6400
    };

    public static IReadOnlyList<QuotaRuleId> All { get; } = new[]
    {
        QuotaRuleId.GrokWeekly, QuotaRuleId.CursorModels, QuotaRuleId.CodexFiveHour, QuotaRuleId.CodexWeekly,
        QuotaRuleId.ClaudeFiveHour, QuotaRuleId.ClaudeWeekly,
        QuotaRuleId.GeminiFiveHour, QuotaRuleId.GeminiWeekly, QuotaRuleId.GeminiThirdPartyFiveHour, QuotaRuleId.GeminiThirdPartyWeekly, QuotaRuleId.GeminiArgonWeekly
    };

    public static IReadOnlySet<string> DefaultEnabledWires { get; } =
        All.Where(DefaultEnabled).Select(Wire).ToHashSet(StringComparer.OrdinalIgnoreCase);
}

public sealed record QuotaThresholdPreferences(
    bool MasterEnabled = true,
    IReadOnlyList<int>? Thresholds = null,
    IReadOnlySet<string>? EnabledRules = null)
{
    public IReadOnlyList<int> ThresholdList => QuotaThresholds.Normalize(Thresholds);
    public IReadOnlySet<string> EnabledRuleSet =>
        EnabledRules ?? QuotaRuleIds.DefaultEnabledWires;
    public string ThresholdsCsv => QuotaThresholds.ToCsv(ThresholdList);
    public bool IsRuleEnabled(QuotaRuleId id) => MasterEnabled && EnabledRuleSet.Contains(id.Wire());
}

public sealed record QuotaThresholdState(
    string WindowKey = "",
    double? Remaining = null,
    List<int>? Fired = null)
{
    public IReadOnlyList<int> FiredList => Fired ?? new List<int>();
}

public sealed record QuotaThresholdPlan(
    IReadOnlyList<AlertToPost> Alerts,
    IReadOnlyDictionary<string, QuotaThresholdState> States);

/// <summary>
/// Remaining-quota threshold crossings. Fires once per threshold per quota window,
/// seeds on first observation without notifying, and ignores stale / cached last-good.
/// Quiet hours still seed; unposted crossings stay unfired so they can notify later.
/// </summary>
public static class QuotaThresholdEngine
{
    public const string TestTitle = "Quota used";
    public const string TestBody = "Grok Bot: 80% weekly quota used (resets Mon 3:00 PM ET)";

    public static AlertToPost TestAlert() => new(
        AlertChannel.Quota,
        TestTitle,
        TestBody,
        "quota:test",
        6499);

    public static QuotaThresholdPlan Evaluate(
        UsageSnapshot snapshot,
        long nowMs,
        QuotaThresholdPreferences prefs,
        IReadOnlyDictionary<string, QuotaThresholdState>? prior,
        bool quiet)
    {
        var previous = prior ?? new Dictionary<string, QuotaThresholdState>();
        if (!prefs.MasterEnabled)
            return new QuotaThresholdPlan(Array.Empty<AlertToPost>(), previous);
        var thresholds = prefs.ThresholdList;
        var sections = UsageSections.From(snapshot, nowMs).ToDictionary(s => s.Id);
        var alerts = new List<AlertToPost>();
        var next = new Dictionary<string, QuotaThresholdState>(previous, StringComparer.OrdinalIgnoreCase);

        foreach (var rule in QuotaRuleIds.All)
        {
            if (!prefs.EnabledRuleSet.Contains(rule.Wire())) continue;
            if (!sections.TryGetValue(rule.Section(), out var section)) continue;
            if (!IsLive(section)) continue;
            var meter = section.MeterList.FirstOrDefault(m => m.Id == rule.MeterId());
            if (meter is null) continue;
            var remaining = meter.RemainingAmount();
            if (remaining is null) continue;
            var remainingValue = remaining.Value;
            var windowKey = meter.QuotaWindowKey();
            previous.TryGetValue(rule.Wire(), out var prev);
            var first = prev is null || prev.Remaining is null;
            var windowChanged = prev is { WindowKey.Length: > 0 } && prev.WindowKey != windowKey;
            var jumpedUp = prev?.Remaining is { } priorRemaining &&
                           remainingValue - priorRemaining >= QuotaThresholds.JumpReset;

            if (first || windowChanged || jumpedUp)
            {
                next[rule.Wire()] = Seed(windowKey, remainingValue, thresholds);
                continue;
            }

            var previousRemaining = prev!.Remaining!.Value;
            if (remainingValue > previousRemaining)
            {
                next[rule.Wire()] = prev with
                {
                    WindowKey = windowKey,
                    Remaining = remainingValue,
                    Fired = prev.FiredList.Where(t => remainingValue <= t).ToList()
                };
                continue;
            }

            var fired = prev.FiredList;
            var crossed = thresholds.Where(t => previousRemaining > t && remainingValue <= t && !fired.Contains(t)).ToList();
            if (crossed.Count == 0)
            {
                next[rule.Wire()] = prev with { WindowKey = windowKey, Remaining = remainingValue };
                continue;
            }
            if (quiet) continue;
            var lowest = crossed.Min();
            next[rule.Wire()] = new QuotaThresholdState(
                windowKey,
                remainingValue,
                fired.Concat(crossed).Distinct().OrderByDescending(t => t).ToList());
            alerts.Add(AlertFor(rule, lowest, meter.ResetsAtEpochMs, windowKey));
        }

        return new QuotaThresholdPlan(alerts, next);
    }

    public static string BodyFor(QuotaRuleId rule, int threshold, long? resetsAtEpochMs)
    {
        var core = $"{rule.ServiceLabel()}: {100 - threshold}% {rule.WindowLabel()} quota used";
        if (resetsAtEpochMs is null) return core;
        return $"{core} (resets {NyTime.FormatQuotaReset(resetsAtEpochMs.Value)})";
    }

    private static bool IsLive(SoftwareSection section) =>
        !section.StaleValue.IsStale &&
        section.Status == ProviderStatus.Ok &&
        section.HealthValue.OutcomeEnum == FetchOutcome.Success;

    private static QuotaThresholdState Seed(string windowKey, double remaining, IReadOnlyList<int> thresholds) =>
        new(windowKey, remaining, thresholds.Where(t => remaining <= t).ToList());

    private static AlertToPost AlertFor(QuotaRuleId rule, int threshold, long? resetsAtEpochMs, string windowKey) =>
        new(
            AlertChannel.Quota,
            TestTitle,
            BodyFor(rule, threshold, resetsAtEpochMs),
            $"quota:{rule.Wire()}:{windowKey}:{threshold}",
            rule.NotificationId());
}
