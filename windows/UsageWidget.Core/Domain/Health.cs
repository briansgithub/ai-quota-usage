using System.Text.RegularExpressions;

namespace UsageWidget;

public enum FetchOutcome { Never, Success, Network, Timeout, Http, AuthExpired, Parse, MissingFields, Unknown }

public static class FetchOutcomes
{
    public static bool IsFailure(this FetchOutcome o) => o is not FetchOutcome.Success and not FetchOutcome.Never;

    public static string ShortReason(this FetchOutcome o, int? httpStatus = null) => o switch
    {
        FetchOutcome.Never or FetchOutcome.Success => "",
        FetchOutcome.Network => "Network error",
        FetchOutcome.Timeout => "Timeout",
        FetchOutcome.AuthExpired => "Sign-in expired",
        FetchOutcome.Parse or FetchOutcome.MissingFields => "Data format changed",
        FetchOutcome.Http => httpStatus is { } s ? $"HTTP {s}" : "HTTP error",
        _ => "Refresh failed"
    };

    public static StaleSeverity Severity(this FetchOutcome o) => o switch
    {
        FetchOutcome.AuthExpired or FetchOutcome.Http or FetchOutcome.Parse
            or FetchOutcome.MissingFields or FetchOutcome.Unknown => StaleSeverity.Red,
        FetchOutcome.Network or FetchOutcome.Timeout => StaleSeverity.Amber,
        _ => StaleSeverity.None
    };

    public static FetchOutcome Parse(string name)
    {
        var n = name.Replace("_", "", StringComparison.Ordinal);
        return Enum.TryParse<FetchOutcome>(n, true, out var o) ? o : FetchOutcome.Unknown;
    }
}

public enum StaleSeverity { None, Amber, Red }

public sealed record FetchAttempt(
    long AtEpochMs,
    string SectionId,
    string Outcome,
    string ShortReason,
    string? Message = null,
    int? HttpStatus = null,
    string? Endpoint = null,
    string? Source = null)
{
    public FetchOutcome OutcomeEnum => FetchOutcomes.Parse(Outcome);

    public static FetchAttempt Of(
        long atEpochMs,
        SoftwareSectionId sectionId,
        FetchOutcome outcome,
        int? httpStatus = null,
        string? message = null,
        string? endpoint = null,
        string? source = null) => new(
        atEpochMs,
        sectionId.Wire(),
        HealthSynthesis.KotlinName(outcome),
        outcome.ShortReason(httpStatus),
        DiagnosticSanitizer.Message(message),
        httpStatus,
        DiagnosticSanitizer.Endpoint(endpoint),
        source);
}

public sealed record SectionHealth(
    string SectionId,
    long LastAttemptEpochMs = 0,
    long LastSuccessEpochMs = 0,
    string LastOutcome = "NEVER",
    string? ShortReason = null,
    string? Message = null,
    int? HttpStatus = null,
    string? Endpoint = null,
    string? Source = null,
    IReadOnlyList<UsageMeter>? LastGoodMeters = null,
    string? LastGoodPlanLabel = null,
    ManualResets? LastGoodManualResets = null,
    string? LastGoodDiagnosticJson = null,
    bool SignedIn = false)
{
    public IReadOnlyList<UsageMeter> LastGoodMeterList => LastGoodMeters ?? Array.Empty<UsageMeter>();
    public FetchOutcome OutcomeEnum => FetchOutcomes.Parse(LastOutcome);
    public bool HasLastGood => LastGoodMeterList.Any(m => m.UsedPercent != null || m.ResetsAtEpochMs != null);
    public StaleView StaleView(long nowMs) => StalePolicy.Evaluate(this, nowMs);
}

public sealed record StaleView(bool IsStale, StaleSeverity Severity, string? LastUpdatedLine, string? ShortReason, bool ShouldNotify)
{
    public static StaleView Fresh { get; } = new(false, StaleSeverity.None, null, null, false);
}

public static class StalePolicy
{
    public const long RefreshIntervalMs = RefreshSchedule.IntervalMs;
    public const long StaleAgeMs = 2 * RefreshIntervalMs;
    public const long NotifyAfterMs = 2L * 60 * 60 * 1000;
    public const int AttemptLogLimit = 24;

    public static StaleView Evaluate(SectionHealth health, long nowMs)
    {
        var hasLastGood = health.HasLastGood;
        var outcome = health.OutcomeEnum;
        var lastSuccess = health.LastSuccessEpochMs;
        var failStale = hasLastGood && outcome.IsFailure();
        var ageStale = hasLastGood && health.SignedIn && lastSuccess > 0 && nowMs - lastSuccess >= StaleAgeMs;
        var isStale = failStale || ageStale;
        if (!isStale)
        {
            return StaleView.Fresh with
            {
                LastUpdatedLine = lastSuccess > 0 ? LastUpdatedLine(lastSuccess, nowMs) : null
            };
        }
        var reason = outcome.IsFailure()
            ? (string.IsNullOrWhiteSpace(health.ShortReason) ? outcome.ShortReason(health.HttpStatus) : health.ShortReason)
            : "Refresh overdue";
        var severity = outcome.IsFailure()
            ? (outcome.Severity() == StaleSeverity.None ? StaleSeverity.Amber : outcome.Severity())
            : StaleSeverity.Amber;
        var failingSince = lastSuccess > 0 ? lastSuccess : health.LastAttemptEpochMs > 0 ? health.LastAttemptEpochMs : nowMs;
        return new StaleView(true, severity, LastUpdatedLine(lastSuccess, nowMs), reason,
            health.SignedIn && nowMs - failingSince >= NotifyAfterMs);
    }

    public static string LastUpdatedLine(long lastSuccessEpochMs, long nowMs) =>
        lastSuccessEpochMs <= 0 ? "Last updated —" : $"Last updated {NyTimeAgo.Format(lastSuccessEpochMs, nowMs)}";
}

public static class NyTimeAgo
{
    public static string Format(long epochMs, long nowMs)
    {
        var delta = Math.Max(0, nowMs - epochMs);
        if (delta < 45_000) return "just now";
        var minutes = delta / 60_000L;
        if (minutes < 60) return $"{minutes}m ago";
        var hours = minutes / 60L;
        if (hours < 48) return $"{hours}h ago";
        return $"{hours / 24}d ago";
    }
}

public static class MeterIds
{
    public static readonly HashSet<string> Cursor = new() { "cursor.models", "cursor.other", "cursor.on_demand", "cursor.plan_reset" };
    public const string Grok = "cursor.grok_bot";
    public const string GrokOnDemand = "cursor.grok_on_demand";
    public static readonly HashSet<string> Codex = new() { "openai.five_hour", "openai.weekly", "openai.thirty_day", "openai.code_review" };

    public static List<UsageMeter> CursorMeters(IEnumerable<UsageMeter> meters)
    {
        var list = meters.ToList();
        return new[] { "cursor.models", "cursor.other", "cursor.on_demand", "cursor.plan_reset" }
            .Select(id => list.FirstOrDefault(m => m.Id == id))
            .Where(m => m is not null)
            .Cast<UsageMeter>()
            .ToList();
    }

    public static List<UsageMeter> GrokMeters(IEnumerable<UsageMeter> meters)
    {
        var list = meters.ToList();
        return new[] { Grok, GrokOnDemand }
            .Select(id => list.FirstOrDefault(m => m.Id == id))
            .Where(m => m is not null)
            .Cast<UsageMeter>()
            .ToList();
    }

    public static List<UsageMeter> CodexMeters(IEnumerable<UsageMeter> meters) =>
        meters.Where(m => m.Id.StartsWith("openai.")).ToList();

    public static List<UsageMeter> ForSection(SoftwareSectionId sectionId, IEnumerable<UsageMeter> meters) =>
        sectionId switch
        {
            SoftwareSectionId.Cursor => CursorMeters(meters),
            SoftwareSectionId.GrokBot => GrokMeters(meters),
            SoftwareSectionId.Codex => CodexMeters(meters),
            _ => new List<UsageMeter>()
        };
}

public static class HealthSynthesis
{
    public static UsageSnapshot Hydrate(UsageSnapshot snapshot)
    {
        if (snapshot.HealthList.Count > 0) return snapshot;
        var cursor = snapshot.Provider(ProviderId.Cursor);
        var openai = snapshot.Provider(ProviderId.OpenAi);
        var at = snapshot.FetchedAtEpochMs;
        var health = new[]
        {
            Synthesized(SoftwareSectionId.Cursor, cursor, MeterIds.CursorMeters(cursor?.MeterList ?? Array.Empty<UsageMeter>()), at),
            Synthesized(SoftwareSectionId.GrokBot, cursor, MeterIds.GrokMeters(cursor?.MeterList ?? Array.Empty<UsageMeter>()), at),
            Synthesized(SoftwareSectionId.Codex, openai, MeterIds.CodexMeters(openai?.MeterList ?? Array.Empty<UsageMeter>()), at)
        };
        return snapshot with { SectionHealth = health };
    }

    private static SectionHealth Synthesized(SoftwareSectionId section, ProviderSnapshot? provider, List<UsageMeter> meters, long fetchedAt)
    {
        var status = provider?.ProviderStatus ?? ProviderStatus.Unsigned;
        var signedIn = status != ProviderStatus.Unsigned;
        var hasGood = meters.Any(m => m.UsedPercent != null || m.ResetsAtEpochMs != null);
        var outcome = status switch
        {
            ProviderStatus.Ok => FetchOutcome.Success,
            ProviderStatus.NeedsSignIn => FetchOutcome.AuthExpired,
            ProviderStatus.Error => FetchOutcome.Unknown,
            _ => FetchOutcome.Never
        };
        return new SectionHealth(
            SectionId: section.Wire(),
            LastAttemptEpochMs: outcome == FetchOutcome.Never ? 0 : fetchedAt,
            LastSuccessEpochMs: outcome == FetchOutcome.Success && hasGood ? fetchedAt : 0,
            LastOutcome: KotlinName(outcome),
            ShortReason: DiagnosticSanitizer.Message(provider?.Error) ?? (outcome.ShortReason() is { Length: > 0 } r ? r : null),
            LastGoodMeters: meters,
            LastGoodPlanLabel: provider?.PlanLabel,
            LastGoodManualResets: provider?.ManualResets,
            LastGoodDiagnosticJson: provider?.DiagnosticJson,
            SignedIn: signedIn);
    }

    public static string KotlinName(FetchOutcome o) => o switch
    {
        FetchOutcome.Never => "NEVER",
        FetchOutcome.Success => "SUCCESS",
        FetchOutcome.Network => "NETWORK",
        FetchOutcome.Timeout => "TIMEOUT",
        FetchOutcome.Http => "HTTP",
        FetchOutcome.AuthExpired => "AUTH_EXPIRED",
        FetchOutcome.Parse => "PARSE",
        FetchOutcome.MissingFields => "MISSING_FIELDS",
        _ => "UNKNOWN"
    };
}

public static class DiagnosticSanitizer
{
    private static readonly Regex Jwt = new(@"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9._-]+", RegexOptions.Compiled);
    private static readonly Regex Bearer = new(@"(?i)Bearer\s+[A-Za-z0-9._\-+=/]+", RegexOptions.Compiled);
    private static readonly Regex CookiePair = new(
        @"(?i)(WorkosCursorSessionToken|__Secure-next-auth\.session-token(?:\.\d+)?|Cookie|Authorization)\s*[:=]\s*\S+",
        RegexOptions.Compiled);
    private static readonly Regex Account = new(@"acct_[A-Za-z0-9]+", RegexOptions.Compiled);

    public static string? Endpoint(string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) return null;
        var cut = url.Split('?', '#')[0].Trim();
        return string.IsNullOrWhiteSpace(cut) ? null : cut;
    }

    public static string? Message(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        var text = Jwt.Replace(raw, "[redacted-jwt]");
        text = Bearer.Replace(text, "Bearer [redacted]");
        text = CookiePair.Replace(text, "$1=[redacted]");
        text = Account.Replace(text, "acct_[redacted]");
        text = text.Replace('\n', ' ').Trim();
        if (text.Length > 240) text = text[..240];
        return string.IsNullOrWhiteSpace(text) ? null : text;
    }
}

public static class RefreshSchedule
{
    public const long IntervalMs = 15L * 60_000;

    public static long NextEpochMs(long lastPeriodicRunEpochMs, long armedEpochMs, long nowMs)
    {
        if (lastPeriodicRunEpochMs > 0) return lastPeriodicRunEpochMs + IntervalMs;
        if (armedEpochMs > 0) return armedEpochMs;
        return nowMs + IntervalMs;
    }

    public static long RemainingMs(long nextEpochMs, long nowMs) =>
        nextEpochMs <= 0 ? 0 : Math.Max(0, nextEpochMs - nowMs);

    public static string FormatCountdown(long remainingMs)
    {
        var totalSec = Math.Max(0, remainingMs) / 1_000L;
        return $"next {totalSec / 60:D2}:{totalSec % 60:D2}";
    }

    public static long ChronometerBaseElapsedRealtime(long nextEpochMs, long nowEpochMs, long elapsedRealtime) =>
        elapsedRealtime + RemainingMs(nextEpochMs, nowEpochMs);
}
