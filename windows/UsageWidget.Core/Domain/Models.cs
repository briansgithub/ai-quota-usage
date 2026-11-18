namespace UsageWidget;

public enum ProviderId
{
    Cursor,
    OpenAi,
    Claude
}

public static class ProviderIds
{
    public static string Wire(this ProviderId id) => id switch
    {
        ProviderId.Cursor => "cursor",
        ProviderId.OpenAi => "openai",
        ProviderId.Claude => "claude",
        _ => throw new ArgumentOutOfRangeException(nameof(id))
    };

    public static string DisplayName(this ProviderId id) => id switch
    {
        ProviderId.Cursor => "Cursor",
        ProviderId.OpenAi => "ChatGPT / Codex",
        ProviderId.Claude => "Claude",
        _ => id.ToString()
    };

    public static ProviderId FromWire(string value) =>
        value.Equals("cursor", StringComparison.OrdinalIgnoreCase) ? ProviderId.Cursor
        : value.Equals("openai", StringComparison.OrdinalIgnoreCase) ? ProviderId.OpenAi
        : value.Equals("claude", StringComparison.OrdinalIgnoreCase) ? ProviderId.Claude
        : throw new ArgumentException($"Unknown provider: {value}");
}

public enum ProviderStatus { Ok, NeedsSignIn, Error, Unsigned, Loading }

public enum MeterBarKind { Pace, Cap }

public enum SoftwareSectionId { Cursor, GrokBot, Codex, Claude }

public static class SoftwareSectionIds
{
    public static string Title(this SoftwareSectionId id) => id switch
    {
        SoftwareSectionId.Cursor => "Cursor",
        SoftwareSectionId.GrokBot => "Grok Bot",
        SoftwareSectionId.Codex => "Codex",
        SoftwareSectionId.Claude => "Claude",
        _ => id.ToString()
    };

    public static string Wire(this SoftwareSectionId id) => id switch
    {
        SoftwareSectionId.Cursor => "cursor",
        SoftwareSectionId.GrokBot => "grok_bot",
        SoftwareSectionId.Codex => "codex",
        SoftwareSectionId.Claude => "claude",
        _ => throw new ArgumentOutOfRangeException(nameof(id))
    };

    public static SoftwareSectionId FromWire(string value) =>
        value.Equals("cursor", StringComparison.OrdinalIgnoreCase) ? SoftwareSectionId.Cursor
        : value.Equals("grok_bot", StringComparison.OrdinalIgnoreCase) ? SoftwareSectionId.GrokBot
        : value.Equals("codex", StringComparison.OrdinalIgnoreCase) ? SoftwareSectionId.Codex
        : value.Equals("claude", StringComparison.OrdinalIgnoreCase) ? SoftwareSectionId.Claude
        : throw new ArgumentException($"Unknown section: {value}");

    public static IReadOnlyList<SoftwareSectionId> All { get; } =
        new[] { SoftwareSectionId.Cursor, SoftwareSectionId.GrokBot, SoftwareSectionId.Codex, SoftwareSectionId.Claude };
}

public sealed record UsageMeter(
    string Id,
    string Title,
    string? Subtitle = null,
    double? UsedPercent = null,
    double? RemainingPercent = null,
    long? ResetsAtEpochMs = null,
    string? ResetLabel = null,
    long? WindowStartEpochMs = null,
    long? WindowLengthMs = null,
    MeterBarKind BarKind = MeterBarKind.Pace)
{
    public bool IsCapBar => BarKind == MeterBarKind.Cap;

    public int? DisplayUsedPercent =>
        UsedPercent is { } u ? Percent.Round(u)
        : RemainingPercent is { } r ? Percent.Round(Math.Max(0.0, 100.0 - r))
        : null;

    public int? DisplayRemainingPercent =>
        RemainingPercent is { } r ? Percent.Round(r)
        : UsedPercent is { } u ? Percent.Round(100.0 - u)
        : null;

    public bool HasUsageBar => RemainingPercent != null || UsedPercent != null;

    public string? UsedLabel() => DisplayUsedPercent is { } n ? $"{n}% used" : null;

    public string? RemainingLabel() => DisplayRemainingPercent is { } n ? $"{n}% left" : null;

    public double? RemainingAmount() =>
        RemainingPercent ?? UsedPercent?.Let(u => Math.Max(0.0, 100.0 - u));

    /// <summary>
    /// Quota-window identity: period start plus reset, so a reset reseeds even if the
    /// overlaid reset timestamp is unchanged.
    /// </summary>
    public string QuotaWindowKey()
    {
        var start = WindowStartEpochMs?.ToString() ?? "";
        var reset = ResetsAtEpochMs?.ToString() ?? "open";
        return $"{start}|{reset}";
    }
}

public sealed record ManualResetCredit(
    long? ExpiresAtEpochMs = null,
    string? Status = null,
    string? Title = null,
    string? ResetType = null)
{
    public bool IsAvailable =>
        string.IsNullOrWhiteSpace(Status) || Status.Equals("available", StringComparison.OrdinalIgnoreCase);
}

public sealed record ManualResets(
    int AvailableCount,
    IReadOnlyList<ManualResetCredit>? Credits = null,
    string? ObservedJson = null)
{
    public IReadOnlyList<ManualResetCredit> CreditList => Credits ?? Array.Empty<ManualResetCredit>();

    public bool IsPresent =>
        AvailableCount > 0 || CreditList.Any(c => c.IsAvailable);

    public string? DisplayLine()
    {
        if (!IsPresent) return null;
        var availableCredits = CreditList.Where(c => c.IsAvailable).ToList();
        var count = AvailableCount > 0 ? AvailableCount : availableCredits.Count;
        if (count <= 0) return null;
        var expiries = availableCredits
            .Select(c => c.ExpiresAtEpochMs)
            .Where(ms => ms.HasValue)
            .Select(ms => ms!.Value)
            .OrderBy(ms => ms)
            .Select(NyTime.FormatDayMonth)
            .ToList();
        return expiries.Count == 0
            ? $"Manual resets: {count}"
            : $"Manual resets: {count} (exp {string.Join(", ", expiries)})";
    }
}

public sealed record ProviderSnapshot(
    string Id,
    string DisplayName,
    string Status,
    string? Error = null,
    string? Source = null,
    string? PlanLabel = null,
    IReadOnlyList<UsageMeter>? Meters = null,
    ManualResets? ManualResets = null,
    string? DiagnosticJson = null)
{
    public IReadOnlyList<UsageMeter> MeterList => Meters ?? Array.Empty<UsageMeter>();
    public ProviderId ProviderId => ProviderIds.FromWire(Id);
    public ProviderStatus ProviderStatus
    {
        get
        {
            var n = Status.Replace("_", "", StringComparison.Ordinal);
            return Enum.TryParse<ProviderStatus>(n, true, out var s) ? s : ProviderStatus.Error;
        }
    }
}

public sealed record UsageSnapshot(
    long FetchedAtEpochMs,
    string Timezone = NyZone.Id,
    IReadOnlyList<ProviderSnapshot>? Providers = null,
    IReadOnlyList<SectionHealth>? SectionHealth = null,
    IReadOnlyList<FetchAttempt>? AttemptLog = null)
{
    public IReadOnlyList<ProviderSnapshot> ProviderList => Providers ?? Array.Empty<ProviderSnapshot>();
    public IReadOnlyList<SectionHealth> HealthList => SectionHealth ?? Array.Empty<SectionHealth>();
    public IReadOnlyList<FetchAttempt> Attempts => AttemptLog ?? Array.Empty<FetchAttempt>();

    public ProviderSnapshot? Provider(ProviderId id) =>
        ProviderList.FirstOrDefault(p => p.Id == id.Wire());

    public SectionHealth Health(SoftwareSectionId sectionId) =>
        HealthList.FirstOrDefault(h => h.SectionId == sectionId.Wire())
        ?? new SectionHealth(sectionId.Wire());

    public static UsageSnapshot Empty(long nowMs) => new(
        FetchedAtEpochMs: nowMs,
        Providers: new[]
        {
            new ProviderSnapshot(ProviderId.Cursor.Wire(), ProviderId.Cursor.DisplayName(), "UNSIGNED"),
            new ProviderSnapshot(ProviderId.OpenAi.Wire(), ProviderId.OpenAi.DisplayName(), "UNSIGNED"),
            new ProviderSnapshot(ProviderId.Claude.Wire(), ProviderId.Claude.DisplayName(), "UNSIGNED")
        });
}

public static class Percent
{
    public static double? RemainingFromUsed(double? used) =>
        used is { } u ? Math.Max(0.0, 100.0 - u) : null;

    /// <summary>
    /// Wire percents from Cursor, Grok, and Codex WHAM are already 0–100
    /// (Cursor <c>apiPercentUsed: 0.71</c> is 0.71% used, not 71%). Do not
    /// treat (0, 1) as a fraction.
    /// </summary>
    public static double AsWirePercent(double value) =>
        double.IsFinite(value) ? value : 0;

    public static int Round(double value) =>
        double.IsFinite(value) ? (int)Math.Round(value, MidpointRounding.AwayFromZero) : 0;
}

public static class NyZone
{
    public const string Id = "America/New_York";
}

public abstract record ProviderResult
{
    public sealed record Success(ProviderSnapshot Snapshot, IReadOnlyList<FetchAttempt>? Attempts = null) : ProviderResult
    {
        public IReadOnlyList<FetchAttempt> AttemptList => Attempts ?? Array.Empty<FetchAttempt>();
    }

    public sealed record NeedsSignIn(string Message = "Session expired. Sign in again.", IReadOnlyList<FetchAttempt>? Attempts = null) : ProviderResult
    {
        public IReadOnlyList<FetchAttempt> AttemptList => Attempts ?? Array.Empty<FetchAttempt>();
    }

    public sealed record Failure(string Message, string? Cause = null, IReadOnlyList<FetchAttempt>? Attempts = null, ProviderSnapshot? Snapshot = null) : ProviderResult
    {
        public IReadOnlyList<FetchAttempt> AttemptList => Attempts ?? Array.Empty<FetchAttempt>();
    }
}

internal static class OptionExt
{
    public static TOut Let<TIn, TOut>(this TIn value, Func<TIn, TOut> map) => map(value);
}

public static class StringNull
{
    public static string? NullIfEmpty(this string? s) => string.IsNullOrWhiteSpace(s) ? null : s;
}
