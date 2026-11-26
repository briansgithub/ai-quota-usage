namespace UsageWidget;

public static class SnapshotMerger
{
    /// <summary>One provider's fetch this round.</summary>
    public sealed record ProviderRound(ProviderId Provider, ProviderResult Result, bool SignedIn);

    public static IReadOnlyList<SoftwareSectionId> SectionsOf(ProviderId provider) => provider switch
    {
        ProviderId.Cursor => new[] { SoftwareSectionId.Cursor, SoftwareSectionId.GrokBot },
        ProviderId.OpenAi => new[] { SoftwareSectionId.Codex },
        ProviderId.Claude => new[] { SoftwareSectionId.Claude },
        ProviderId.Gemini => new[] { SoftwareSectionId.Gemini },
        _ => Array.Empty<SoftwareSectionId>()
    };

    private static ProviderId ProviderOf(SoftwareSectionId section) => section switch
    {
        SoftwareSectionId.Codex => ProviderId.OpenAi,
        SoftwareSectionId.Claude => ProviderId.Claude,
        SoftwareSectionId.Gemini => ProviderId.Gemini,
        _ => ProviderId.Cursor
    };

    /// <summary>Cursor + OpenAI only; any other provider keeps its previous state.</summary>
    public static UsageSnapshot Merge(
        UsageSnapshot previous,
        ProviderResult cursorResult,
        ProviderResult openaiResult,
        long nowMs,
        bool cursorSignedIn,
        bool openaiSignedIn) =>
        Merge(previous, new[]
        {
            new ProviderRound(ProviderId.Cursor, cursorResult, cursorSignedIn),
            new ProviderRound(ProviderId.OpenAi, openaiResult, openaiSignedIn)
        }, nowMs);

    public static UsageSnapshot Merge(UsageSnapshot previous, IReadOnlyList<ProviderRound> rounds, long nowMs)
    {
        var prior = HealthSynthesis.Hydrate(previous);
        var health = prior.HealthList.ToDictionary(h => h.SectionId, h => h);
        foreach (var id in SoftwareSectionIds.All)
            health.TryAdd(id.Wire(), new SectionHealth(id.Wire()));

        var byProvider = rounds.ToDictionary(r => r.Provider);
        var metersThisRound = rounds.ToDictionary(r => r.Provider, r => MetersFrom(r.Result));
        var attempts = new List<FetchAttempt>();
        foreach (var round in rounds)
            attempts.AddRange(AttemptsOrSynthetic(round.Result, SectionsOf(round.Provider).ToArray(), round.SignedIn, health, nowMs));

        foreach (var attempt in attempts)
        {
            SoftwareSectionId section;
            try { section = SoftwareSectionIds.FromWire(attempt.SectionId); }
            catch { continue; }
            var round = byProvider[ProviderOf(section)];
            var snap = SnapshotOf(round.Result);
            var incoming = MeterIds.ForSection(section, metersThisRound[round.Provider]);
            var signedIn = round.SignedIn || attempt.OutcomeEnum == FetchOutcome.AuthExpired;
            var incomingManual = section is SoftwareSectionId.Codex or SoftwareSectionId.Cursor or SoftwareSectionId.Claude ? snap?.ManualResets : null;
            var incomingDiag = section is SoftwareSectionId.GrokBot ? null : snap?.DiagnosticJson;
            health[section.Wire()] = ApplyAttempt(health[section.Wire()], attempt, incoming, snap?.PlanLabel, signedIn, incomingManual, incomingDiag);
        }

        foreach (var round in rounds)
            MarkUnsignedOrExpired(round.SignedIn, SectionsOf(round.Provider).ToArray(), attempts, health, nowMs);

        var providers = new List<ProviderSnapshot>();
        foreach (var id in new[] { ProviderId.Cursor, ProviderId.OpenAi, ProviderId.Claude, ProviderId.Gemini })
        {
            if (!byProvider.TryGetValue(id, out var round))
            {
                if (prior.Provider(id) is { } kept) providers.Add(kept);
                continue;
            }
            var sections = SectionsOf(id);
            var primary = health[sections[0].Wire()];
            var meters = sections.SelectMany(s => health[s.Wire()].LastGoodMeterList).ToList();
            providers.Add(RebuildProvider(
                id, prior.Provider(id), SnapshotOf(round.Result), round.Result,
                round.SignedIn || primary.SignedIn,
                meters,
                primary.LastGoodPlanLabel, primary.LastGoodManualResets, primary.LastGoodDiagnosticJson));
        }

        var log = prior.Attempts.Concat(attempts).TakeLast(StalePolicy.AttemptLogLimit).ToList();
        return new UsageSnapshot(
            FetchedAtEpochMs: nowMs,
            Providers: providers,
            SectionHealth: SoftwareSectionIds.All.Select(id => health[id.Wire()]).ToList(),
            AttemptLog: log);
    }

    public static SectionHealth ApplyAttempt(
        SectionHealth previous,
        FetchAttempt attempt,
        IReadOnlyList<UsageMeter> newMeters,
        string? planLabel,
        bool signedIn,
        ManualResets? manualResets = null,
        string? diagnosticJson = null)
    {
        var success = attempt.OutcomeEnum == FetchOutcome.Success;
        // A successful fetch is the live truth, including an empty Grok list after a
        // quota reset (0% used / omitted allowance). Keeping last-good here froze the
        // pre-reset percent on screen even after a manual refresh.
        var goodMeters = success ? newMeters : previous.LastGoodMeterList;
        return previous with
        {
            LastAttemptEpochMs = attempt.AtEpochMs,
            LastSuccessEpochMs = success ? attempt.AtEpochMs : previous.LastSuccessEpochMs,
            LastOutcome = attempt.Outcome,
            ShortReason = string.IsNullOrWhiteSpace(attempt.ShortReason) ? null : attempt.ShortReason,
            Message = attempt.Message,
            HttpStatus = attempt.HttpStatus,
            Endpoint = attempt.Endpoint,
            Source = attempt.Source,
            LastGoodMeters = goodMeters,
            LastGoodPlanLabel = success ? planLabel ?? previous.LastGoodPlanLabel : previous.LastGoodPlanLabel,
            LastGoodManualResets = success ? manualResets : previous.LastGoodManualResets,
            LastGoodDiagnosticJson = success ? diagnosticJson ?? previous.LastGoodDiagnosticJson : previous.LastGoodDiagnosticJson,
            SignedIn = signedIn
        };
    }

    public static UsageSnapshot ClearForSignOut(UsageSnapshot previous, ProviderId provider)
    {
        var sections = SectionsOf(provider);
        var health = previous.HealthList.ToList();
        foreach (var id in SoftwareSectionIds.All)
        {
            if (health.All(h => h.SectionId != id.Wire()))
                health.Add(new SectionHealth(id.Wire()));
        }
        var updatedHealth = health.Select(item =>
        {
            try
            {
                var id = SoftwareSectionIds.FromWire(item.SectionId);
                return sections.Contains(id) ? new SectionHealth(item.SectionId, SignedIn: false) : item;
            }
            catch { return item; }
        }).ToList();
        var providers = previous.ProviderList.Select(it =>
            it.Id == provider.Wire()
                ? it with { Status = nameof(ProviderStatus.Unsigned), Error = null, Meters = Array.Empty<UsageMeter>(), Source = null, PlanLabel = null, ManualResets = null, DiagnosticJson = null }
                : it).ToList();
        return previous with { Providers = providers, SectionHealth = updatedHealth };
    }

    private static IReadOnlyList<FetchAttempt> AttemptsOrSynthetic(
        ProviderResult result,
        SoftwareSectionId[] sections,
        bool signedIn,
        Dictionary<string, SectionHealth> health,
        long nowMs)
    {
        var explicitAttempts = (result switch
        {
            ProviderResult.Success s => s.AttemptList,
            ProviderResult.NeedsSignIn n => n.AttemptList,
            ProviderResult.Failure f => f.AttemptList,
            _ => Array.Empty<FetchAttempt>()
        }).Where(a => sections.Any(s => s.Wire() == a.SectionId)).ToList();
        if (explicitAttempts.Count > 0) return explicitAttempts;
        if (!signedIn) return Array.Empty<FetchAttempt>();
        var outcome = result switch
        {
            ProviderResult.Success => FetchOutcome.Success,
            ProviderResult.NeedsSignIn => FetchOutcome.AuthExpired,
            _ => FetchOutcome.Unknown
        };
        var message = result switch
        {
            ProviderResult.NeedsSignIn n => n.Message,
            ProviderResult.Failure f => f.Message,
            _ => null
        };
        return sections.Select(section => FetchAttempt.Of(nowMs, section, outcome, message: message, source: SnapshotOf(result)?.Source))
            .Where(a => a.OutcomeEnum != FetchOutcome.Never)
            .ToList();
    }

    private static void MarkUnsignedOrExpired(
        bool signedIn,
        SoftwareSectionId[] sections,
        List<FetchAttempt> attempts,
        Dictionary<string, SectionHealth> health,
        long nowMs)
    {
        if (signedIn) return;
        if (attempts.Any(a => sections.Any(s => s.Wire() == a.SectionId))) return;
        foreach (var section in sections)
        {
            var previous = health[section.Wire()];
            if (previous.HasLastGood)
            {
                var attempt = FetchAttempt.Of(nowMs, section, FetchOutcome.AuthExpired, message: "Sign-in expired");
                attempts.Add(attempt);
                health[section.Wire()] = ApplyAttempt(previous, attempt, Array.Empty<UsageMeter>(), null, true);
            }
            else
            {
                health[section.Wire()] = new SectionHealth(previous.SectionId, SignedIn: false);
            }
        }
    }

    private static ProviderSnapshot? SnapshotOf(ProviderResult result) => result switch
    {
        ProviderResult.Success s => s.Snapshot,
        ProviderResult.Failure f => f.Snapshot,
        _ => null
    };

    private static List<UsageMeter> MetersFrom(ProviderResult result) => SnapshotOf(result)?.MeterList.ToList() ?? new();

    private static ProviderSnapshot RebuildProvider(
        ProviderId id,
        ProviderSnapshot? previous,
        ProviderSnapshot? thisRound,
        ProviderResult result,
        bool signedIn,
        List<UsageMeter> meters,
        string? planLabel,
        ManualResets? manualResets,
        string? diagnosticJson)
    {
        var computed = result switch
        {
            ProviderResult.Success => ProviderStatus.Ok,
            ProviderResult.NeedsSignIn => ProviderStatus.NeedsSignIn,
            ProviderResult.Failure => meters.Count > 0 ? ProviderStatus.Ok : ProviderStatus.Error,
            _ => ProviderStatus.Error
        };
        var status = !signedIn && meters.Count == 0 ? ProviderStatus.Unsigned : computed;
        var error = result switch
        {
            ProviderResult.Success => thisRound?.Error,
            ProviderResult.NeedsSignIn n => n.Message,
            ProviderResult.Failure f => f.Message,
            _ => null
        };
        return new ProviderSnapshot(
            id.Wire(),
            id.DisplayName(),
            KotlinStatus(status),
            error,
            thisRound?.Source ?? previous?.Source,
            planLabel ?? thisRound?.PlanLabel ?? previous?.PlanLabel,
            meters,
            manualResets,
            diagnosticJson);
    }

    private static string KotlinStatus(ProviderStatus s) => s switch
    {
        ProviderStatus.Ok => "OK",
        ProviderStatus.NeedsSignIn => "NEEDS_SIGN_IN",
        ProviderStatus.Error => "ERROR",
        ProviderStatus.Unsigned => "UNSIGNED",
        ProviderStatus.Loading => "LOADING",
        _ => s.ToString().ToUpperInvariant()
    };
}
