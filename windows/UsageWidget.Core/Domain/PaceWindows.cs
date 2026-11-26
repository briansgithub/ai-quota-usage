namespace UsageWidget;

/// <summary>
/// Even-pace expected used-% for a quota window: elapsedFraction × 100.
/// 60% of the week gone → on-pace used is 60%. Window bounds come from the
/// meter’s start+reset when known, otherwise reset minus a known length
/// (5-hour, 7-day weekly, ~30-day Cursor billing cycle).
/// </summary>
public static class PaceWindows
{
    public static long? KnownLengthMs(string meterId) => meterId switch
    {
        "openai.five_hour" => PacingPolicy.WindowMs,
        "openai.weekly" => PacingPolicy.GrokPeriodMs,
        "openai.thirty_day" => 30L * 24 * 60 * 60 * 1000,
        ClaudeMeterIds.FiveHour => ClaudeMeterIds.FiveHourMs,
        ClaudeMeterIds.Weekly or ClaudeMeterIds.WeeklyOpus or ClaudeMeterIds.WeeklySonnet => ClaudeMeterIds.WeekMs,
        "gemini.gemini-5h" or "gemini.3p-5h" => GeminiUsageParser.FiveHours,
        "gemini.gemini-weekly" or "gemini.3p-weekly" or "gemini.argon-weekly" => GeminiUsageParser.Week,
        "cursor.grok_bot" => PacingPolicy.GrokPeriodMs,
        "cursor.models" or "cursor.other" => PacingPolicy.CursorPeriodMs,
        _ => null
    };

    public static (long Start, long End)? Bounds(UsageMeter meter)
    {
        var start = meter.WindowStartEpochMs;
        var end = meter.ResetsAtEpochMs;
        var length = meter.WindowLengthMs ?? KnownLengthMs(meter.Id);
        if (start is { } s && end is { } e && e > s) return (s, e);
        if (end is { } e2 && length is { } len && len > 0) return (e2 - len, e2);
        if (start is { } s2 && length is { } len2 && len2 > 0) return (s2, s2 + len2);
        return null;
    }

    /// <summary>
    /// On-pace used percent at <paramref name="nowMs"/>, or null when the window
    /// cannot be determined (bar color then stays neutral white).
    /// </summary>
    public static double? ExpectedUsedPercent(UsageMeter meter, long nowMs)
    {
        var bounds = Bounds(meter);
        if (bounds is null) return null;
        var (start, end) = bounds.Value;
        var length = end - start;
        if (length <= 0) return null;
        var elapsed = Math.Clamp((nowMs - start) / (double)length, 0, 1);
        return elapsed * 100.0;
    }
}
