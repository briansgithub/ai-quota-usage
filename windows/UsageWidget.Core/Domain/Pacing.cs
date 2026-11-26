namespace UsageWidget;

public static class PacingPolicy
{
    public const long WindowMs = 5L * 60 * 60 * 1000;
    public const double DefaultPercentPerWindow = 12.0;
    public const double MinPercentPerWindow = 5.0;
    public const double MaxPercentPerWindow = 25.0;
    public const long ComfortableLeadMs = 80L * 60 * 1000;
    public const long FeasibleLeadMs = 15L * 60 * 1000;
    public const double ActiveMinRemaining = 1.0;
    public const double AvailableMinRemaining = 99.0;
    public const double WeeklyWasteBuffer = 10.0;
    public const long GrokPeriodMs = 7L * 24 * 60 * 60 * 1000;
    public const long CursorPeriodMs = 30L * 24 * 60 * 60 * 1000;
    public const double ManualFullResetPercent = 100.0;
    public const long TightSlackMs = 8L * 60 * 60 * 1000;
    public const long CriticalSlackMs = 2L * 60 * 60 * 1000;
    public const double RemainingEps = 0.5;

    public static double ClampPercentPerWindow(double value) =>
        Math.Clamp(value, MinPercentPerWindow, MaxPercentPerWindow);
}

public enum PaceKind { FiveAndWeekly, WeeklyOnly }
public enum PaceUrgency { Ok, Tight, Critical, Impossible }
public enum FiveHourWindowState { Available, Active, Exhausted, Unknown }

public sealed record ManualResetPace(
    long? ExpiresAtEpochMs,
    int WindowsNeeded,
    long TimeNeededMs,
    bool WillExpireUnused,
    double UnusedPercent,
    string? Warning);

public sealed record PacingResult(
    PaceKind Kind,
    string SectionId,
    double WeeklyRemaining,
    long WeeklyResetEpochMs,
    double PercentPerWindow,
    int WindowsNeeded,
    int ExtraWindowsNeeded,
    double CurrentWindowDeliverable,
    FiveHourWindowState FiveHourState,
    long SlackMs,
    IReadOnlyList<long> LatestStarts,
    long? NextLatestStartEpochMs,
    long? ComfortableStartEpochMs,
    long? FeasibleStartEpochMs,
    bool MissedWindow,
    double WeeklyLostPercent,
    string Line,
    PaceUrgency Urgency,
    IReadOnlyList<ManualResetPace>? Manuals = null,
    double WasteGap = 0,
    bool ShouldWarnUnused = false)
{
    public IReadOnlyList<ManualResetPace> ManualList => Manuals ?? Array.Empty<ManualResetPace>();
}

public static class PacingPlanner
{
    public static int WindowsNeeded(double remaining, double percentPerWindow)
    {
        var p = PacingPolicy.ClampPercentPerWindow(percentPerWindow);
        if (remaining <= PacingPolicy.RemainingEps || p <= 0) return 0;
        return (int)Math.Ceiling(remaining / p);
    }

    public static IReadOnlyList<long> LatestStarts(long weeklyResetEpochMs, int extraWindows)
    {
        if (extraWindows <= 0) return Array.Empty<long>();
        var list = new List<long>(extraWindows);
        for (var k = extraWindows; k >= 1; k--)
            list.Add(weeklyResetEpochMs - k * PacingPolicy.WindowMs);
        return list;
    }

    public static FiveHourWindowState FiveHourStateOf(double? remaining)
    {
        if (remaining is null) return FiveHourWindowState.Unknown;
        if (remaining >= PacingPolicy.AvailableMinRemaining) return FiveHourWindowState.Available;
        if (remaining >= PacingPolicy.ActiveMinRemaining) return FiveHourWindowState.Active;
        return FiveHourWindowState.Exhausted;
    }

    public static PacingResult PlanFiveAndWeekly(
        double weeklyRemaining,
        long weeklyResetEpochMs,
        double? fiveHourRemaining,
        long? fiveHourResetEpochMs,
        long nowMs,
        double percentPerWindow = PacingPolicy.DefaultPercentPerWindow,
        IReadOnlyList<ManualResetCredit>? manuals = null,
        string sectionId = "codex")
    {
        var p = PacingPolicy.ClampPercentPerWindow(percentPerWindow);
        var state = FiveHourStateOf(fiveHourRemaining);
        var currentDeliverable = state == FiveHourWindowState.Active
            ? Math.Min(weeklyRemaining, (fiveHourRemaining ?? 0) / 100.0 * p)
            : 0.0;
        var remainingAfterCurrent = Math.Max(0.0, weeklyRemaining - currentDeliverable);
        var extraWindows = WindowsNeeded(remainingAfterCurrent, p);
        var waitMs = state is FiveHourWindowState.Active or FiveHourWindowState.Exhausted
            ? Math.Max(0L, (fiveHourResetEpochMs ?? nowMs) - nowMs)
            : 0L;
        var timeUntilWeekly = weeklyResetEpochMs - nowMs;
        var slackMs = timeUntilWeekly - waitMs - extraWindows * PacingPolicy.WindowMs;
        var starts = LatestStarts(weeklyResetEpochMs, extraWindows);
        var nextLatest = starts.Count > 0 ? starts[0] : (long?)null;
        var missed = extraWindows > 0 && nextLatest is { } nl && nowMs > nl && state == FiveHourWindowState.Available;
        var earliestStart = nowMs + waitMs;
        var maxFit = (int)Math.Floor(Math.Max(0, weeklyResetEpochMs - earliestStart) / (double)PacingPolicy.WindowMs);
        var lost = Math.Max(0.0, remainingAfterCurrent - maxFit * p);
        var urgency = UrgencyOf(slackMs, lost);
        var manualsPace = PlanManuals(manuals ?? Array.Empty<ManualResetCredit>(), nowMs, p);
        var line = FormatFiveLine(state, extraWindows, slackMs, lost);
        return new PacingResult(
            Kind: PaceKind.FiveAndWeekly,
            SectionId: sectionId,
            WeeklyRemaining: weeklyRemaining,
            WeeklyResetEpochMs: weeklyResetEpochMs,
            PercentPerWindow: p,
            WindowsNeeded: extraWindows + (state == FiveHourWindowState.Active ? 1 : 0),
            ExtraWindowsNeeded: extraWindows,
            CurrentWindowDeliverable: currentDeliverable,
            FiveHourState: state,
            SlackMs: slackMs,
            LatestStarts: starts,
            NextLatestStartEpochMs: nextLatest,
            ComfortableStartEpochMs: nextLatest is { } n1 ? n1 - PacingPolicy.ComfortableLeadMs : null,
            FeasibleStartEpochMs: nextLatest is { } n2 ? n2 - PacingPolicy.FeasibleLeadMs : null,
            MissedWindow: missed,
            WeeklyLostPercent: lost,
            Line: line,
            Urgency: urgency,
            Manuals: manualsPace);
    }

    public static PacingResult PlanWeeklyOnly(double remaining, long resetAtEpochMs, long nowMs, long periodMs, string sectionId)
    {
        var timeLeft = resetAtEpochMs - nowMs;
        var period = Math.Max(Math.Max(periodMs, timeLeft), 1L);
        var even = 100.0 * Math.Clamp(timeLeft / (double)period, 0.0, 1.0);
        var wasteGap = remaining - even;
        var shouldWarn = wasteGap >= PacingPolicy.WeeklyWasteBuffer;
        var urgency = wasteGap >= 25.0 ? PaceUrgency.Critical : shouldWarn ? PaceUrgency.Tight : PaceUrgency.Ok;
        var used = RoundPct(Math.Max(0.0, 100.0 - remaining));
        var line = shouldWarn
            ? $"Pace: {used}% used · {RoundPct(wasteGap)}% above even pace"
            : $"Pace: {used}% used, {NyTime.FormatDuration(timeLeft)}";
        return new PacingResult(
            Kind: PaceKind.WeeklyOnly,
            SectionId: sectionId,
            WeeklyRemaining: remaining,
            WeeklyResetEpochMs: resetAtEpochMs,
            PercentPerWindow: PacingPolicy.DefaultPercentPerWindow,
            WindowsNeeded: 0,
            ExtraWindowsNeeded: 0,
            CurrentWindowDeliverable: 0,
            FiveHourState: FiveHourWindowState.Unknown,
            SlackMs: timeLeft,
            LatestStarts: Array.Empty<long>(),
            NextLatestStartEpochMs: null,
            ComfortableStartEpochMs: null,
            FeasibleStartEpochMs: null,
            MissedWindow: false,
            WeeklyLostPercent: shouldWarn ? wasteGap : 0,
            Line: line,
            Urgency: urgency,
            WasteGap: wasteGap,
            ShouldWarnUnused: shouldWarn);
    }

    public static IReadOnlyList<ManualResetPace> PlanManuals(
        IReadOnlyList<ManualResetCredit> credits,
        long nowMs,
        double percentPerWindow = PacingPolicy.DefaultPercentPerWindow)
    {
        var p = PacingPolicy.ClampPercentPerWindow(percentPerWindow);
        var windows = WindowsNeeded(PacingPolicy.ManualFullResetPercent, p);
        var timeNeeded = windows * PacingPolicy.WindowMs;
        return credits.Where(c => c.IsAvailable).Select(credit =>
        {
            var expires = credit.ExpiresAtEpochMs;
            if (expires is null)
            {
                return new ManualResetPace(null, windows, timeNeeded, false, 0, null);
            }
            var timeLeft = expires.Value - nowMs;
            var maxFit = (int)Math.Floor(Math.Max(0, timeLeft) / (double)PacingPolicy.WindowMs);
            var unused = Math.Max(0.0, 100.0 - maxFit * p);
            var willExpire = timeLeft < timeNeeded && unused > PacingPolicy.RemainingEps;
            return new ManualResetPace(
                expires,
                windows,
                timeNeeded,
                willExpire,
                unused,
                willExpire ? $"Manual reset expires before it can be used (~{RoundPct(unused)}% weekly unused)" : null);
        }).ToList();
    }

    public static PacingResult? ForSection(SoftwareSection section, long nowMs, double percentPerWindow = PacingPolicy.DefaultPercentPerWindow)
    {
        switch (section.Id)
        {
            case SoftwareSectionId.Codex:
            {
                var five = section.MeterList.FirstOrDefault(m => m.Id == "openai.five_hour");
                var weekly = section.MeterList.FirstOrDefault(m => m.Id == "openai.weekly");
                var remaining = weekly?.RemainingAmount();
                if (remaining is null || weekly?.ResetsAtEpochMs is null) return null;
                return PlanFiveAndWeekly(
                    remaining.Value,
                    weekly.ResetsAtEpochMs.Value,
                    five?.RemainingAmount(),
                    five?.ResetsAtEpochMs,
                    nowMs,
                    percentPerWindow,
                    section.ManualResets?.CreditList,
                    section.Id.Wire());
            }
            case SoftwareSectionId.Claude:
            {
                var five = section.MeterList.FirstOrDefault(m => m.Id == ClaudeMeterIds.FiveHour);
                var weekly = section.MeterList.FirstOrDefault(m => m.Id == ClaudeMeterIds.Weekly);
                var remaining = weekly?.RemainingAmount();
                if (remaining is null || weekly?.ResetsAtEpochMs is null) return null;
                return PlanFiveAndWeekly(
                    remaining.Value,
                    weekly.ResetsAtEpochMs.Value,
                    five?.RemainingAmount(),
                    five?.ResetsAtEpochMs,
                    nowMs,
                    percentPerWindow,
                    section.ManualResets?.CreditList,
                    section.Id.Wire());
            }
            case SoftwareSectionId.GrokBot:
            {
                var meter = section.MeterList.FirstOrDefault(m => m.Id == "cursor.grok_bot") ?? section.MeterList.FirstOrDefault();
                var remaining = meter?.RemainingAmount();
                if (remaining is null || meter?.ResetsAtEpochMs is null) return null;
                return PlanWeeklyOnly(remaining.Value, meter.ResetsAtEpochMs.Value, nowMs, PacingPolicy.GrokPeriodMs, section.Id.Wire());
            }
            case SoftwareSectionId.Cursor:
            {
                var models = section.MeterList.FirstOrDefault(m => m.Id == "cursor.models");
                var plan = section.MeterList.FirstOrDefault(m => m.Id == "cursor.plan_reset");
                var remaining = models?.RemainingAmount();
                var resetAt = plan?.ResetsAtEpochMs ?? models?.ResetsAtEpochMs;
                if (remaining is null || resetAt is null) return null;
                return PlanWeeklyOnly(remaining.Value, resetAt.Value, nowMs, PacingPolicy.CursorPeriodMs, section.Id.Wire());
            }
            default:
                return null;
        }
    }

    private static string FormatFiveLine(FiveHourWindowState state, int extraWindows, long slackMs, double lost)
    {
        var slackPart = slackMs >= 0 ? $"{NyTime.FormatDuration(slackMs)} slack" : $"{NyTime.FormatDuration(-slackMs)} short";
        if (lost > PacingPolicy.RemainingEps)
        {
            var n = extraWindows + (state == FiveHourWindowState.Active ? 1 : 0);
            var label = n == 1 ? "window" : "windows";
            return $"Pace: need {n} {label}, ~{RoundPct(lost)}% likely lost";
        }
        if (extraWindows == 0 && state != FiveHourWindowState.Active) return "Pace: on track";
        if (extraWindows == 0 && state == FiveHourWindowState.Active) return $"Pace: finish this window, {slackPart}";
        var winLabel = extraWindows == 1 ? "window" : "windows";
        return state == FiveHourWindowState.Active
            ? $"Pace: need {extraWindows} more, {slackPart}"
            : $"Pace: need {extraWindows} {winLabel}, {slackPart}";
    }

    private static PaceUrgency UrgencyOf(long slackMs, double lost) =>
        lost > PacingPolicy.RemainingEps || slackMs < 0 ? PaceUrgency.Impossible
        : slackMs <= PacingPolicy.CriticalSlackMs ? PaceUrgency.Critical
        : slackMs <= PacingPolicy.TightSlackMs ? PaceUrgency.Tight
        : PaceUrgency.Ok;

    private static int RoundPct(double value) => Math.Max(0, (int)Math.Round(value, MidpointRounding.AwayFromZero));
}
