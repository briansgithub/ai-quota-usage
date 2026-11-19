namespace UsageWidget;

public enum ResetUrgency { Relaxed, Soon, Near, Imminent }

public static class ResetEmphasis
{
    public const long RelaxedAfterMs = 48L * 60 * 60 * 1000;
    public const long SoonAfterMs = 24L * 60 * 60 * 1000;
    public const long ImminentAfterMs = 5L * 60 * 60 * 1000;

    public static ResetUrgency Of(long? resetAtEpochMs, long nowMs)
    {
        if (resetAtEpochMs is null) return ResetUrgency.Relaxed;
        var left = resetAtEpochMs.Value - nowMs;
        if (left <= ImminentAfterMs) return ResetUrgency.Imminent;
        if (left <= SoonAfterMs) return ResetUrgency.Near;
        if (left <= RelaxedAfterMs) return ResetUrgency.Soon;
        return ResetUrgency.Relaxed;
    }
}

public static class QuietHours
{
    public const int DefaultStartHour = 23;
    public const int DefaultEndHour = 8;

    public static bool IsQuiet(long nowMs, bool enabled, int startHour = DefaultStartHour, int endHour = DefaultEndHour)
    {
        if (!enabled) return false;
        var hour = NyTime.AtZone(nowMs).Hour;
        if (startHour == endHour) return true;
        if (startHour < endHour) return hour >= startHour && hour < endHour;
        return hour >= startHour || hour < endHour;
    }
}

public static class NotificationDedupe
{
    public const int MaxKeys = 80;

    public static bool ShouldNotify(IReadOnlyCollection<string> seen, string key) => !seen.Contains(key);

    public static List<string> Remember(IEnumerable<string> seen, string key)
    {
        var next = seen.Where(s => !string.IsNullOrWhiteSpace(s) && s != key).ToList();
        next.Add(key);
        while (next.Count > MaxKeys) next.RemoveAt(0);
        return next;
    }

    public static List<string> RememberAll(IEnumerable<string> seen, IEnumerable<string> keys)
    {
        var acc = seen.Where(s => !string.IsNullOrWhiteSpace(s)).ToList();
        foreach (var key in keys) acc = Remember(acc, key);
        return acc;
    }
}

public enum RestoredKind { FiveHour, Weekly }
public enum RestoredReason { ResetTimePassed, RemainingJump }

public sealed record RestoredUsageEvent(
    string MeterId,
    string Group,
    string Title,
    RestoredKind Kind,
    string ServiceLabel,
    long? PreviousResetAt,
    double? NewRemaining,
    double? PreviousRemaining,
    RestoredReason Reason,
    string DedupeKey);

public static class ResetDetector
{
    public const double JumpThreshold = 15.0;
    private static readonly string[] Groups = { "codex.five_hour", "codex.weekly", "claude.five_hour", "claude.weekly", "grok.weekly", "cursor.plan" };

    public static IReadOnlyList<RestoredUsageEvent> Detect(UsageSnapshot previous, UsageSnapshot current, long nowMs)
    {
        var prevMeters = Flatten(previous);
        var currMeters = Flatten(current);
        if (prevMeters.Count == 0 || currMeters.Count == 0) return Array.Empty<RestoredUsageEvent>();

        var events = new List<RestoredUsageEvent>();
        foreach (var group in Groups)
        {
            var ids = MeterIdsFor(group);
            var prev = ids.Select(id => prevMeters.GetValueOrDefault(id)).Where(m => m is not null).Cast<UsageMeter>().ToList();
            var curr = ids.Select(id => currMeters.GetValueOrDefault(id)).Where(m => m is not null).Cast<UsageMeter>().ToList();
            if (prev.Count == 0 || curr.Count == 0) continue;

            var resetPassed = prev.Any(meter =>
                meter.ResetsAtEpochMs is { } resetAt && resetAt > previous.FetchedAtEpochMs && resetAt <= nowMs);

            (string Id, double Before, double After)? jumpedPair = null;
            foreach (var id in ids)
            {
                var before = RemainingOf(prevMeters.GetValueOrDefault(id));
                var after = RemainingOf(currMeters.GetValueOrDefault(id));
                if (before is { } b && after is { } a && a - b >= JumpThreshold)
                {
                    jumpedPair = (id, b, a);
                    break;
                }
            }
            if (!resetPassed && jumpedPair is null) continue;

            var representative = curr[0];
            var prevReset = prev.Select(m => m.ResetsAtEpochMs).Where(ms => ms.HasValue).Select(ms => ms!.Value).DefaultIfEmpty().Min();
            long? prevResetN = prev.Any(m => m.ResetsAtEpochMs.HasValue) ? prevReset : null;
            var currReset = curr.Where(m => m.ResetsAtEpochMs.HasValue).Select(m => m.ResetsAtEpochMs!.Value).DefaultIfEmpty().Min();
            long? currResetN = curr.Any(m => m.ResetsAtEpochMs.HasValue) ? currReset : null;
            var cycleId = prevResetN ?? currResetN ?? HourBucket(nowMs);
            var remaining = jumpedPair?.After ?? RemainingOf(representative);
            var previousRemaining = jumpedPair?.Before ?? RemainingOf(prev[0]);
            events.Add(new RestoredUsageEvent(
                representative.Id, group, TitleFor(group), KindFor(group), ServiceFor(group),
                prevResetN, remaining, previousRemaining,
                resetPassed ? RestoredReason.ResetTimePassed : RestoredReason.RemainingJump,
                $"restored:{group}:{cycleId}"));
        }
        return events;
    }

    public static string? NotifyGroup(string meterId) => meterId switch
    {
        "openai.five_hour" => "codex.five_hour",
        "openai.weekly" => "codex.weekly",
        ClaudeMeterIds.FiveHour => "claude.five_hour",
        ClaudeMeterIds.Weekly => "claude.weekly",
        "cursor.grok_bot" => "grok.weekly",
        "cursor.models" or "cursor.plan_reset" => "cursor.plan",
        _ => null
    };

    private static IReadOnlyList<string> MeterIdsFor(string group) => group switch
    {
        "codex.five_hour" => new[] { "openai.five_hour" },
        "codex.weekly" => new[] { "openai.weekly" },
        "claude.five_hour" => new[] { ClaudeMeterIds.FiveHour },
        "claude.weekly" => new[] { ClaudeMeterIds.Weekly },
        "grok.weekly" => new[] { "cursor.grok_bot" },
        "cursor.plan" => new[] { "cursor.models", "cursor.plan_reset" },
        _ => Array.Empty<string>()
    };

    private static string TitleFor(string group) => group switch
    {
        "codex.five_hour" => "Codex 5-hour usage is back",
        "codex.weekly" => "Codex weekly usage is back",
        "claude.five_hour" => "Claude session usage is back",
        "claude.weekly" => "Claude weekly usage is back",
        "grok.weekly" => "Grok Bot weekly usage is back",
        "cursor.plan" => "Cursor usage is back",
        _ => "Usage is back"
    };

    private static string ServiceFor(string group) => group switch
    {
        "codex.five_hour" or "codex.weekly" => "Codex",
        "claude.five_hour" or "claude.weekly" => "Claude",
        "grok.weekly" => "Grok Bot",
        "cursor.plan" => "Cursor",
        _ => "Usage"
    };

    private static RestoredKind KindFor(string group) =>
        group is "codex.five_hour" or "claude.five_hour" ? RestoredKind.FiveHour : RestoredKind.Weekly;

    private static Dictionary<string, UsageMeter> Flatten(UsageSnapshot snapshot)
    {
        var outMap = new Dictionary<string, UsageMeter>();
        foreach (var provider in snapshot.ProviderList)
        foreach (var meter in provider.MeterList)
            outMap[meter.Id] = meter;
        return outMap;
    }

    private static double? RemainingOf(UsageMeter? meter) => meter?.RemainingAmount();
    private static long HourBucket(long nowMs) => nowMs / 3_600_000L * 3_600_000L;
}

public sealed record AlertPreferences(
    double PercentPerWindow = PacingPolicy.DefaultPercentPerWindow,
    bool NotifyStaleFailures = true,
    bool NotifyUsageRestored = true,
    bool NotifyPacingReminders = true,
    bool NotifyPacingMissed = true,
    bool QuietHoursEnabled = false,
    int QuietHoursStartHour = QuietHours.DefaultStartHour,
    int QuietHoursEndHour = QuietHours.DefaultEndHour);

public enum AlertChannel { Restored, Pacing, Quota }

public sealed record AlertToPost(AlertChannel Channel, string Title, string Body, string DedupeKey, int NotificationId);
public sealed record ScheduledWake(string UniqueName, long AtEpochMs);
public sealed record UsageAlertPlan(IReadOnlyList<AlertToPost> Alerts, IReadOnlyList<ScheduledWake> Wakes, IReadOnlyCollection<string> NewSeenKeys);

public static class UsageAlertEngine
{
    public const long ResetWakePaddingMs = 5_000L;
    public const long MaxWakeAheadMs = 7L * 24 * 60 * 60 * 1000;

    public static UsageAlertPlan Evaluate(
        UsageSnapshot previous,
        UsageSnapshot current,
        long nowMs,
        AlertPreferences prefs,
        IReadOnlyCollection<string> seenKeys)
    {
        var restored = prefs.NotifyUsageRestored ? ResetDetector.Detect(previous, current, nowMs) : Array.Empty<RestoredUsageEvent>();
        var sections = UsageSections.From(current, nowMs);
        var pacing = sections.Select(s => PacingPlanner.ForSection(s, nowMs, prefs.PercentPerWindow)).Where(p => p is not null).Cast<PacingResult>().ToList();

        var candidates = new List<AlertToPost>();
        if (prefs.NotifyUsageRestored)
            candidates.AddRange(restored.Select(RestoredAlert));
        foreach (var result in pacing)
            candidates.AddRange(PacingAlerts(result, nowMs, prefs));

        var quiet = QuietHours.IsQuiet(nowMs, prefs.QuietHoursEnabled, prefs.QuietHoursStartHour, prefs.QuietHoursEndHour);
        var toPost = quiet ? new List<AlertToPost>() : candidates.Where(a => NotificationDedupe.ShouldNotify(seenKeys, a.DedupeKey)).ToList();
        IReadOnlyCollection<string> newSeen = quiet ? seenKeys.ToList() : NotificationDedupe.RememberAll(seenKeys, toPost.Select(a => a.DedupeKey));

        var wakes = new List<ScheduledWake>();
        if (prefs.NotifyUsageRestored) wakes.AddRange(ResetWakes(current, nowMs));
        if (prefs.NotifyPacingReminders || prefs.NotifyPacingMissed) wakes.AddRange(PacingWakes(pacing, nowMs));

        return new UsageAlertPlan(toPost, wakes.DistinctBy(w => w.UniqueName).ToList(), newSeen);
    }

    private static AlertToPost RestoredAlert(RestoredUsageEvent e)
    {
        var remaining = e.NewRemaining is { } r ? $"{100 - (int)Math.Round(r, MidpointRounding.AwayFromZero)}% used" : "ready to use";
        var body = e.Group switch
        {
            "codex.five_hour" => $"Codex 5-hour limit has reset ({remaining}).",
            "codex.weekly" => $"Codex weekly limit has reset ({remaining}).",
            "claude.five_hour" => $"Claude session limit has reset ({remaining}).",
            "claude.weekly" => $"Claude weekly limit has reset ({remaining}).",
            "grok.weekly" => $"Grok Bot weekly limit has reset ({remaining}).",
            "cursor.plan" => $"Cursor plan usage has reset ({remaining}).",
            _ => $"{e.ServiceLabel} usage has reset ({remaining})."
        };
        return new AlertToPost(AlertChannel.Restored, e.Title, body, e.DedupeKey, NotificationId(AlertChannel.Restored, e.DedupeKey));
    }

    private static IEnumerable<AlertToPost> PacingAlerts(PacingResult result, long nowMs, AlertPreferences prefs)
    {
        var name = DisplayName(result.SectionId);
        if (prefs.NotifyPacingReminders &&
            result.Kind == PaceKind.FiveAndWeekly &&
            result.FiveHourState == FiveHourWindowState.Available &&
            result.ExtraWindowsNeeded > 0 &&
            result.NextLatestStartEpochMs is { } latest && latest > nowMs)
        {
            var until = latest - nowMs;
            var comfortable = result.ComfortableStartEpochMs;
            var feasible = result.FeasibleStartEpochMs;
            if (comfortable is { } c && nowMs >= c && nowMs < (feasible ?? latest))
            {
                yield return Alert(AlertChannel.Pacing, $"Start a {name} session",
                    $"Start a {name} session within {NyTime.FormatDuration(until)} to stay on pace.",
                    $"pace-start:{result.SectionId}:{latest}:comfortable");
            }
            if (feasible is { } f && nowMs >= f && nowMs < latest)
            {
                yield return Alert(AlertChannel.Pacing, $"Last chance for this {name} window",
                    $"Start a {name} session within {NyTime.FormatDuration(until)} — latest start is now.",
                    $"pace-start:{result.SectionId}:{latest}:feasible");
            }
        }

        if (prefs.NotifyPacingMissed && result.MissedWindow)
        {
            var slack = result.SlackMs;
            var body = result.Urgency switch
            {
                PaceUrgency.Impossible =>
                    $"Missed a {name} window. You can no longer use everything (~{(int)Math.Round(result.WeeklyLostPercent, MidpointRounding.AwayFromZero)}% weekly will likely be lost).",
                PaceUrgency.Critical or PaceUrgency.Tight =>
                    $"Missed a {name} window. Cutting it close — {NyTime.FormatDuration(Math.Max(0, slack))} float left.",
                _ =>
                    $"Missed a {name} window. {NyTime.FormatDuration(Math.Max(0, slack))} float left before it becomes critical."
            };
            yield return Alert(AlertChannel.Pacing, $"Missed a {name} window", body,
                $"pace-missed:{result.SectionId}:{result.NextLatestStartEpochMs}");
        }

        if (prefs.NotifyPacingMissed &&
            result.Kind == PaceKind.FiveAndWeekly &&
            (result.Urgency is PaceUrgency.Critical or PaceUrgency.Impossible) &&
            !result.MissedWindow)
        {
            var body = result.Urgency == PaceUrgency.Impossible
                ? $"{name} slack is gone. About {(int)Math.Round(result.WeeklyLostPercent, MidpointRounding.AwayFromZero)}% weekly will likely be left unused."
                : $"{name} slack is down to {NyTime.FormatDuration(Math.Max(0, result.SlackMs))}.";
            yield return Alert(AlertChannel.Pacing, $"{name} pacing is {result.Urgency.ToString().ToLowerInvariant()}", body,
                $"pace-critical:{result.SectionId}:{result.WeeklyResetEpochMs}");
        }

        if (prefs.NotifyPacingReminders && result.Kind == PaceKind.WeeklyOnly && result.ShouldWarnUnused)
        {
            yield return Alert(AlertChannel.Pacing, $"{name} usage is ahead of the clock",
                $"{(int)Math.Round(result.WasteGap, MidpointRounding.AwayFromZero)}% less used than even pace — on track to leave usage unused.",
                $"pace-weekly:{result.SectionId}:{result.WeeklyResetEpochMs}");
        }

        if (prefs.NotifyPacingMissed)
        {
            foreach (var manual in result.ManualList.Where(m => m.WillExpireUnused && m.Warning is not null))
            {
                yield return Alert(AlertChannel.Pacing, "Codex manual reset expiring unused", manual.Warning!,
                    $"pace-expire:{manual.ExpiresAtEpochMs}");
            }
        }
    }

    private static IEnumerable<ScheduledWake> ResetWakes(UsageSnapshot current, long nowMs)
    {
        foreach (var meter in current.ProviderList.SelectMany(p => p.MeterList))
        {
            var group = ResetDetector.NotifyGroup(meter.Id);
            if (group is null || meter.ResetsAtEpochMs is null) continue;
            var at = meter.ResetsAtEpochMs.Value + ResetWakePaddingMs;
            if (at <= nowMs || at - nowMs > MaxWakeAheadMs) continue;
            yield return new ScheduledWake($"timed-reset-{group}", at);
        }
    }

    private static IEnumerable<ScheduledWake> PacingWakes(IEnumerable<PacingResult> results, long nowMs)
    {
        foreach (var result in results)
        {
            if (result.Kind != PaceKind.FiveAndWeekly || result.NextLatestStartEpochMs is not { } latest) continue;
            if (result.ComfortableStartEpochMs is { } c && c > nowMs && c - nowMs <= MaxWakeAheadMs)
                yield return new ScheduledWake($"timed-pace-{result.SectionId}-comfortable", c);
            if (result.FeasibleStartEpochMs is { } f && f > nowMs && f - nowMs <= MaxWakeAheadMs)
                yield return new ScheduledWake($"timed-pace-{result.SectionId}-feasible", f);
            var missedAt = latest + ResetWakePaddingMs;
            if (missedAt > nowMs && missedAt - nowMs <= MaxWakeAheadMs)
                yield return new ScheduledWake($"timed-pace-{result.SectionId}-missed", missedAt);
        }
    }

    private static AlertToPost Alert(AlertChannel channel, string title, string body, string key) =>
        new(channel, title, body, key, NotificationId(channel, key));

    public static int NotificationId(AlertChannel channel, string key)
    {
        var baseId = channel == AlertChannel.Restored ? 4200 : 5300;
        return baseId + (key.GetHashCode() & 0x3FF);
    }

    private static string DisplayName(string sectionId)
    {
        try { return SoftwareSectionIds.FromWire(sectionId).Title(); }
        catch { return sectionId; }
    }
}
