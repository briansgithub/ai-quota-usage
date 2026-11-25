namespace UsageWidget;

public sealed record SoftwareSection(
    SoftwareSectionId Id,
    ProviderId SignInProvider,
    ProviderStatus Status,
    string? Subtitle = null,
    string? Error = null,
    IReadOnlyList<UsageMeter>? Meters = null,
    SectionHealth? Health = null,
    StaleView? Stale = null,
    ManualResets? ManualResets = null)
{
    public IReadOnlyList<UsageMeter> MeterList => Meters ?? Array.Empty<UsageMeter>();
    public SectionHealth HealthValue => Health ?? new SectionHealth(Id.Wire());
    public StaleView StaleValue => Stale ?? StaleView.Fresh;
}

public static class OnDemandDisplay
{
    public const string CursorMeter = "cursor.on_demand";
    public const string GrokMeter = "cursor.grok_on_demand";

    public static IReadOnlyList<SoftwareSection> Apply(
        IReadOnlyList<SoftwareSection> sections,
        bool showCursor,
        bool showGrok)
    {
        if (showCursor && showGrok) return sections;
        return sections.Select(section =>
        {
            var hidden = section.Id switch
            {
                SoftwareSectionId.Cursor when !showCursor => CursorMeter,
                SoftwareSectionId.GrokBot when !showGrok => GrokMeter,
                _ => null
            };
            if (hidden is null || section.MeterList.All(m => m.Id != hidden)) return section;
            return section with { Meters = section.MeterList.Where(m => m.Id != hidden).ToList() };
        }).ToList();
    }
}

public static class UsageSections
{
    public static IReadOnlyList<SoftwareSection> From(UsageSnapshot snapshot) =>
        From(snapshot, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

    public static IReadOnlyList<SoftwareSection> From(UsageSnapshot snapshot, long nowMs)
    {
        var hydrated = HealthSynthesis.Hydrate(snapshot);
        var cursor = hydrated.Provider(ProviderId.Cursor);
        var openai = hydrated.Provider(ProviderId.OpenAi);
        var claude = hydrated.Provider(ProviderId.Claude);
        return new[]
        {
            CursorSection(cursor, hydrated.Health(SoftwareSectionId.Cursor), nowMs),
            GrokSection(cursor, hydrated.Health(SoftwareSectionId.GrokBot), nowMs),
            CodexSection(openai, hydrated.Health(SoftwareSectionId.Codex), nowMs),
            ClaudeSection(claude, hydrated.Health(SoftwareSectionId.Claude), nowMs),
            new SoftwareSection(SoftwareSectionId.Gemini, ProviderId.Gemini,
                hydrated.Provider(ProviderId.Gemini)?.ProviderStatus ?? ProviderStatus.Unsigned,
                Subtitle: hydrated.Provider(ProviderId.Gemini)?.PlanLabel,
                Error: hydrated.Provider(ProviderId.Gemini)?.Error,
                Meters: hydrated.Provider(ProviderId.Gemini)?.MeterList,
                Health: hydrated.Health(SoftwareSectionId.Gemini),
                Stale: hydrated.Health(SoftwareSectionId.Gemini).StaleView(nowMs))
        };
    }

    private static SoftwareSection CursorSection(ProviderSnapshot? cursor, SectionHealth health, long nowMs)
    {
        var status = cursor?.ProviderStatus ?? ProviderStatus.Unsigned;
        var live = new List<UsageMeter>();
        var models = cursor?.MeterList.FirstOrDefault(m => m.Id == "cursor.models");
        var other = cursor?.MeterList.FirstOrDefault(m => m.Id == "cursor.other");
        var onDemand = cursor?.MeterList.FirstOrDefault(m => m.Id == "cursor.on_demand");
        var plan = cursor?.MeterList.FirstOrDefault(m => m.Id == "cursor.plan_reset");
        if (plan is null)
        {
            var found = cursor?.MeterList.FirstOrDefault(m =>
                m.ResetsAtEpochMs != null &&
                m.Id != "cursor.grok_bot" &&
                m.Id != "cursor.on_demand" &&
                m.Id != MeterIds.GrokOnDemand);
            if (found is not null)
                plan = new UsageMeter("cursor.plan_reset", "Plan reset", ResetsAtEpochMs: found.ResetsAtEpochMs, WindowStartEpochMs: found.WindowStartEpochMs);
        }
        if (models is not null) live.Add(models);
        if (other is not null) live.Add(other);
        if (onDemand is not null) live.Add(onDemand);
        if (plan is not null) live.Add(plan);
        var meters = health.OutcomeEnum == FetchOutcome.Success
            ? live
            : live.Count > 0 ? live : MeterIds.CursorMeters(health.LastGoodMeterList);
        var stale = health.StaleView(nowMs);
        return new SoftwareSection(
            SoftwareSectionId.Cursor,
            ProviderId.Cursor,
            status == ProviderStatus.Ok && meters.Count == 0 ? ProviderStatus.Error : status,
            cursor?.PlanLabel ?? health.LastGoodPlanLabel,
            stale.ShortReason ?? WithoutGrokError(cursor?.Error) ?? (status == ProviderStatus.Ok && meters.Count == 0
                ? "Cursor Models, Other Models, and plan reset were not in the response." : null),
            meters,
            health,
            stale,
            cursor?.ManualResets ?? health.LastGoodManualResets);
    }

    private static SoftwareSection GrokSection(ProviderSnapshot? cursor, SectionHealth health, long nowMs)
    {
        var grok = cursor?.MeterList.FirstOrDefault(m => m.Id == "cursor.grok_bot")
            ?? (health.OutcomeEnum == FetchOutcome.Success
                ? null
                : health.LastGoodMeterList.FirstOrDefault(m => m.Id == "cursor.grok_bot"));
        if (grok is not null) grok = grok with { Title = "Weekly usage", Subtitle = null };
        var onDemand = cursor?.MeterList.FirstOrDefault(m => m.Id == MeterIds.GrokOnDemand)
            ?? (health.OutcomeEnum == FetchOutcome.Success
                ? null
                : health.LastGoodMeterList.FirstOrDefault(m => m.Id == MeterIds.GrokOnDemand));
        var status = cursor is null ? ProviderStatus.Unsigned
            : cursor.ProviderStatus is ProviderStatus.NeedsSignIn or ProviderStatus.Unsigned ? cursor.ProviderStatus
            : grok is not null ? ProviderStatus.Ok
            : cursor.ProviderStatus == ProviderStatus.Loading ? ProviderStatus.Loading
            : ProviderStatus.Error;
        var stale = health.StaleView(nowMs);
        var error = stale.ShortReason ?? status switch
        {
            ProviderStatus.Ok => null,
            ProviderStatus.NeedsSignIn or ProviderStatus.Unsigned => "Sign in to Cursor to load Grok Bot weekly usage.",
            ProviderStatus.Loading => null,
            _ => GrokOnlyError(cursor?.Error) ?? "Grok Bot weekly usage was not in the Cursor response."
        };
        var meters = new List<UsageMeter>
        {
            grok ?? new UsageMeter("cursor.grok_bot", "Weekly usage",
                ResetLabel: status == ProviderStatus.Ok ? null : "Waiting for data")
        };
        if (onDemand is not null) meters.Add(onDemand);
        return new SoftwareSection(
            SoftwareSectionId.GrokBot,
            ProviderId.Cursor,
            status,
            status is ProviderStatus.Unsigned or ProviderStatus.NeedsSignIn ? "Uses your Cursor sign-in" : null,
            error,
            meters,
            health,
            stale);
    }

    private static SoftwareSection CodexSection(ProviderSnapshot? openai, SectionHealth health, long nowMs)
    {
        var status = openai?.ProviderStatus ?? ProviderStatus.Unsigned;
        var sourceMeters = (openai?.MeterList.Count > 0
            ? openai.MeterList
            : health.OutcomeEnum == FetchOutcome.Success
                ? Array.Empty<UsageMeter>()
                : health.LastGoodMeterList).ToList();
        var live = MeterIds.CodexMeters(sourceMeters);
        if (live.Count == 0) live = sourceMeters.ToList();
        var meters = live.Count > 0
            ? live
            : new List<UsageMeter>
            {
                new("openai.five_hour", "5-hour", ResetLabel: "Waiting for data"),
                new("openai.weekly", "Weekly", ResetLabel: "Waiting for data")
            };
        var stale = health.StaleView(nowMs);
        return new SoftwareSection(
            SoftwareSectionId.Codex,
            ProviderId.OpenAi,
            status,
            openai?.PlanLabel ?? health.LastGoodPlanLabel,
            stale.ShortReason ?? openai?.Error,
            meters,
            health,
            stale,
            openai?.ManualResets ?? health.LastGoodManualResets);
    }

    private static SoftwareSection ClaudeSection(ProviderSnapshot? claude, SectionHealth health, long nowMs)
    {
        var status = claude?.ProviderStatus ?? ProviderStatus.Unsigned;
        var sourceMeters = (claude?.MeterList.Count > 0
            ? claude.MeterList
            : health.OutcomeEnum == FetchOutcome.Success
                ? Array.Empty<UsageMeter>()
                : health.LastGoodMeterList).ToList();
        var live = MeterIds.ClaudeMeters(sourceMeters);
        var meters = live.Count > 0
            ? live
            : new List<UsageMeter>
            {
                new(ClaudeMeterIds.FiveHour, "Session", ResetLabel: "Waiting for data"),
                new(ClaudeMeterIds.Weekly, "Weekly", ResetLabel: "Waiting for data")
            };
        var stale = health.StaleView(nowMs);
        return new SoftwareSection(
            SoftwareSectionId.Claude,
            ProviderId.Claude,
            status,
            claude?.PlanLabel ?? health.LastGoodPlanLabel,
            stale.ShortReason ?? claude?.Error,
            meters,
            health,
            stale);
    }

    private static string? WithoutGrokError(string? error) =>
        error?.Split(" · ").Where(p => !p.Contains("Grok", StringComparison.OrdinalIgnoreCase)).DefaultIfEmpty()
            .Aggregate((a, b) => string.IsNullOrEmpty(a) ? b : a + " · " + b) is { Length: > 0 } s ? s : null;

    private static string? GrokOnlyError(string? error) =>
        error?.Split(" · ").FirstOrDefault(p => p.Contains("Grok", StringComparison.OrdinalIgnoreCase)) ?? error;
}
