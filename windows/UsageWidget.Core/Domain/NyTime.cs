using System.Collections.Generic;
using System.Linq;
using System.Globalization;
using System.Text.RegularExpressions;

namespace UsageWidget;

public readonly record struct ResetSegment(string Text, bool Emphasized);

public static class NyTime
{
    public static readonly TimeZoneInfo Zone = ResolveZone();
    private const long GrokWeekMs = 7L * 24 * 60 * 60 * 1000;

    private static TimeZoneInfo ResolveZone()
    {
        try { return TimeZoneInfo.FindSystemTimeZoneById(NyZone.Id); }
        catch (TimeZoneNotFoundException)
        {
            return TimeZoneInfo.FindSystemTimeZoneById("Eastern Standard Time");
        }
    }

    public static DateTimeOffset AtZone(long epochMs) =>
        TimeZoneInfo.ConvertTime(DateTimeOffset.FromUnixTimeMilliseconds(epochMs), Zone);

    public static string FormatResetLine(long epochMs, long nowMs) =>
        string.Concat(FormatResetSegments(epochMs, nowMs).Select(s => s.Text));

    /// <summary>
    /// The reset line split so the UI can emphasize only the clock time and the
    /// duration: <c>Reset: Fri </c> + <b>9 AM</b> + <c> (</c> + <b>1d 4h</b> + <c>)</c>.
    /// </summary>
    public static IReadOnlyList<ResetSegment> FormatResetSegments(long epochMs, long nowMs)
    {
        var zoned = AtZone(epochMs);
        var day = zoned.ToString("ddd", CultureInfo.InvariantCulture);
        return new[]
        {
            new ResetSegment($"Reset: {day} ", false),
            new ResetSegment(FormatResetClock(zoned), true),
            new ResetSegment(" (", false),
            new ResetSegment(FormatResetRelative(epochMs, nowMs), true),
            new ResetSegment(")", false)
        };
    }

    public static string FormatResetClock(DateTimeOffset zoned)
    {
        var fmt = zoned.Minute == 0 ? "h tt" : "h:mm tt";
        return zoned.ToString(fmt, CultureInfo.InvariantCulture);
    }

    /// <summary>Weekday plus always-minutes clock, e.g. <c>Mon 3:00 PM ET</c>.</summary>
    public static string FormatQuotaReset(long epochMs)
    {
        var zoned = AtZone(epochMs);
        var day = zoned.ToString("ddd", CultureInfo.InvariantCulture);
        var clock = zoned.ToString("h:mm tt", CultureInfo.InvariantCulture);
        return $"{day} {clock} ET";
    }

    public static bool IsUtcMidnight(long epochMs)
    {
        var utc = DateTimeOffset.FromUnixTimeMilliseconds(epochMs).UtcDateTime;
        return utc.TimeOfDay == TimeSpan.Zero;
    }

    public static long OverlayUtcDateWithNyClock(long dateEpochMs, long clockEpochMs)
    {
        var date = DateTimeOffset.FromUnixTimeMilliseconds(dateEpochMs).UtcDateTime.Date;
        var nyTime = AtZone(clockEpochMs).TimeOfDay;
        var local = DateTime.SpecifyKind(date.Add(nyTime), DateTimeKind.Unspecified);
        return ToUnixMs(TimeZoneInfo.ConvertTimeToUtc(local, Zone));
    }

    public static long? WithClockIfDateTruncated(long? nextEpochMs, long? clockEpochMs, long? nowMs = null)
    {
        if (nextEpochMs is null) return null;
        if (clockEpochMs is null) return nextEpochMs;
        if (!IsUtcMidnight(nextEpochMs.Value)) return nextEpochMs;
        if (IsUtcMidnight(clockEpochMs.Value)) return nextEpochMs;
        var stamped = OverlayUtcDateWithNyClock(nextEpochMs.Value, clockEpochMs.Value);
        var week = GrokWeekMs;
        var guard = 0;
        // Date-only nextReset stamped with the new period-start clock equals that start
        // (the reset that just fired) instead of +7 days. Roll until it is after both
        // the period start and "now" so the widget does not show "due" / old pacing.
        while (stamped <= clockEpochMs.Value && guard++ < 4)
            stamped += week;
        if (nowMs is { } now)
        {
            while (stamped <= now && guard++ < 8)
                stamped += week;
        }
        return stamped;
    }

    public static string FormatResetRelative(long epochMs, long nowMs)
    {
        var deltaSec = (epochMs - nowMs) / 1000L;
        if (deltaSec <= 0) return "due";
        return FormatDuration(deltaSec * 1000L);
    }

    public static string FormatDuration(long ms)
    {
        var deltaSec = Math.Abs(ms) / 1000L;
        if (deltaSec < 60) return "0m";
        var days = deltaSec / 86_400;
        var hours = (deltaSec % 86_400) / 3_600;
        var minutes = (deltaSec % 3_600) / 60;
        if (days >= 1 && hours > 0) return $"{days}d {hours}h";
        if (days >= 1) return $"{days}d";
        if (hours >= 1 && minutes > 0 && hours < 3) return $"{hours}h {minutes}m";
        if (hours >= 1) return $"{hours}h";
        return $"{minutes}m";
    }

    public static string FormatDayMonth(long epochMs) =>
        AtZone(epochMs).ToString("dd/MM", CultureInfo.InvariantCulture);

    public static string Countdown(long epochMs, long nowMs)
    {
        var deltaSec = (epochMs - nowMs) / 1000L;
        if (deltaSec <= 0) return "reset due";
        var days = deltaSec / 86_400;
        var hours = (deltaSec % 86_400) / 3_600;
        var minutes = (deltaSec % 3_600) / 60;
        if (days >= 1) return $"{days}d {hours}h left";
        if (hours >= 1) return $"{hours}h {minutes}m left";
        return $"{minutes}m left";
    }

    public static string FormatAbsolute(long epochMs) =>
        AtZone(epochMs).ToString("MMM d, yyyy h:mm tt zzz", CultureInfo.InvariantCulture);

    public static long? ParseTimestamp(object? value, long nowMs)
    {
        switch (value)
        {
            case null: return null;
            case JsonNumber n: return EpochFromNumber(n.Value);
            case double d: return EpochFromNumber(d);
            case long l: return EpochFromNumber(l);
            case int i: return EpochFromNumber(i);
            case string s: return ParseTimestampString(s, nowMs);
            default: return null;
        }
    }

    public static long? ParseTimestampString(string raw, long nowMs)
    {
        var text = raw.Trim();
        if (text.Length == 0 || text == "null") return null;
        if (double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var num))
            return EpochFromNumber(num);

        var isoCandidates = new[]
        {
            text,
            text.Replace(' ', 'T'),
            text.EndsWith('Z') || text.Contains('+') || Regex.IsMatch(text, @"T.*-") ? text : text + "Z"
        };
        foreach (var candidate in isoCandidates.Distinct())
        {
            if (DateTimeOffset.TryParse(candidate, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var dto))
                return dto.ToUnixTimeMilliseconds();
            if (DateTime.TryParse(candidate.TrimEnd('Z'), CultureInfo.InvariantCulture, DateTimeStyles.None, out var local))
            {
                var unspecified = DateTime.SpecifyKind(local, DateTimeKind.Unspecified);
                try
                {
                    return ToUnixMs(TimeZoneInfo.ConvertTimeToUtc(unspecified, Zone));
                }
                catch { /* next */ }
            }
            if (candidate.Length >= 10 && DateOnly.TryParse(candidate[..10], CultureInfo.InvariantCulture, DateTimeStyles.None, out var date))
            {
                var start = DateTime.SpecifyKind(date.ToDateTime(TimeOnly.MinValue), DateTimeKind.Unspecified);
                try
                {
                    return ToUnixMs(TimeZoneInfo.ConvertTimeToUtc(start, Zone));
                }
                catch { /* next */ }
            }
        }
        return ParseMonthDay(text, nowMs) ?? ParseRelative(text, nowMs);
    }

    public static long? ParseRelative(string text, long nowMs)
    {
        var lower = text.ToLowerInvariant();
        var dayHour = Regex.Match(lower, @"(\d+)\s*d(?:ays?)?[^\d]*?(\d+)\s*h(?:ours?)?");
        if (dayHour.Success)
            return nowMs + (long.Parse(dayHour.Groups[1].Value) * 86_400 + long.Parse(dayHour.Groups[2].Value) * 3_600) * 1000;
        var hourMin = Regex.Match(lower, @"(\d+)\s*h(?:ours?)?[^\d]*?(\d+)\s*m(?:in(?:utes?)?)?");
        if (hourMin.Success)
            return nowMs + (long.Parse(hourMin.Groups[1].Value) * 3_600 + long.Parse(hourMin.Groups[2].Value) * 60) * 1000;
        var daysOnly = Regex.Match(lower, @"(\d+)\s*d(?:ays?)?");
        var hoursOnly = Regex.Match(lower, @"(\d+)\s*h(?:ours?)?");
        var minutesOnly = Regex.Match(lower, @"(\d+)\s*m(?:in(?:utes?)?)?");
        if (daysOnly.Success && !hoursOnly.Success)
            return nowMs + long.Parse(daysOnly.Groups[1].Value) * 86_400_000;
        if (hoursOnly.Success && !daysOnly.Success && !minutesOnly.Success)
            return nowMs + long.Parse(hoursOnly.Groups[1].Value) * 3_600_000;
        if (minutesOnly.Success && !daysOnly.Success && !hoursOnly.Success)
            return nowMs + long.Parse(minutesOnly.Groups[1].Value) * 60_000;
        var daysLeft = Regex.Match(lower, @"(\d+)\s+days?\s+left");
        if (daysLeft.Success)
            return nowMs + long.Parse(daysLeft.Groups[1].Value) * 86_400_000;
        return null;
    }

    public static long? ParseMonthDay(string text, long nowMs)
    {
        var match = Regex.Match(
            text,
            @"\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:,\s*(\d{4}))?(?:[,\s]+(\d{1,2}):(\d{2})\s*(am|pm)?)?",
            RegexOptions.IgnoreCase);
        if (!match.Success) return null;
        var month = MonthFromName(match.Groups[1].Value);
        if (month is null) return null;
        var day = int.Parse(match.Groups[2].Value);
        var now = AtZone(nowMs);
        var year = match.Groups[3].Success && match.Groups[3].Length > 0 ? int.Parse(match.Groups[3].Value) : now.Year;
        var hourRaw = match.Groups[4].Success && match.Groups[4].Length > 0 ? int.Parse(match.Groups[4].Value) : (int?)null;
        var minute = match.Groups[5].Success && match.Groups[5].Length > 0 ? int.Parse(match.Groups[5].Value) : 0;
        var ampm = match.Groups[6].Value.ToLowerInvariant();
        int hour;
        if (hourRaw is null) hour = 0;
        else if (ampm == "pm" && hourRaw.Value < 12) hour = hourRaw.Value + 12;
        else if (ampm == "am" && hourRaw.Value == 12) hour = 0;
        else hour = hourRaw.Value;
        try
        {
            var date = new DateTime(year, month.Value, day);
            if (match.Groups[3].Length == 0)
            {
                var startMs = ToUnixMs(TimeZoneInfo.ConvertTimeToUtc(DateTime.SpecifyKind(date, DateTimeKind.Unspecified), Zone));
                if (startMs < nowMs - 2 * 86_400_000L)
                    date = date.AddYears(1);
            }
            var local = DateTime.SpecifyKind(new DateTime(date.Year, date.Month, date.Day, hour, minute, 0), DateTimeKind.Unspecified);
            return ToUnixMs(TimeZoneInfo.ConvertTimeToUtc(local, Zone));
        }
        catch
        {
            return null;
        }
    }

    private static int? MonthFromName(string name) => name[..3].ToLowerInvariant() switch
    {
        "jan" => 1, "feb" => 2, "mar" => 3, "apr" => 4, "may" => 5, "jun" => 6,
        "jul" => 7, "aug" => 8, "sep" => 9, "oct" => 10, "nov" => 11, "dec" => 12,
        _ => null
    };

    private static long? EpochFromNumber(double value)
    {
        if (!double.IsFinite(value) || value <= 0) return null;
        return value < 1e12 ? (long)(value * 1000.0) : (long)value;
    }

    public static long ToUnixMs(DateTime utc) =>
        new DateTimeOffset(DateTime.SpecifyKind(utc, DateTimeKind.Utc)).ToUnixTimeMilliseconds();
}

public readonly record struct JsonNumber(double Value);
