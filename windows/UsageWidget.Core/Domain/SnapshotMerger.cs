namespace UsageWidget;

public static class SnapshotMerger
{
    public static UsageSnapshot Merge(
        UsageSnapshot previous,
        ProviderResult cursorResult,
        ProviderResult openaiResult,
        long nowMs,
        bool cursorSignedIn,
        bool openaiSignedIn)
    {
        var prior = HealthSynthesis.Hydrate(previous);
        var health = prior.HealthList.ToDictionary(h => h.SectionId, h => h);
        foreach (var id in SoftwareSectionIds.All)
            health.TryAdd(id.Wire(), new SectionHealth(id.Wire()));

        var cursorMetersThisRound = MetersFrom(cursorResult);
        var openaiMetersThisRound = MetersFrom(openaiResult);
        var attempts = new List<FetchAttempt>();
        attempts.AddRange(AttemptsOrSynthetic(cursorResult, new[] { SoftwareSectionId.Cursor, SoftwareSectionId.GrokBot }, cursorSignedIn, health, nowMs));
        attempts.AddRange(AttemptsOrSynthetic(openaiResult, new[] { SoftwareSectionId.Codex }, openaiSignedIn, health, nowMs));

        foreach (var attempt in attempts)
        {
            SoftwareSectionId section;
            try { section = SoftwareSectionIds.FromWire(attempt.SectionId); }
            catch { continue; }
            var incoming = MeterIds.ForSection(section, section == SoftwareSectionId.Codex ? openaiMetersThisRound : cursorMetersThisRound);
            var planLabel = section == SoftwareSectionId.Codex ? SnapshotOf(openaiResult)?.PlanLabel : SnapshotOf(cursorResult)?.PlanLabel;
            var signedIn = section == SoftwareSectionId.Codex
                ? openaiSignedIn || attempt.OutcomeEnum == FetchOutcome.AuthExpired
                : cursorSignedIn || attempt.OutcomeEnum == FetchOutcome.AuthExpired;
            var incomingManual = section switch
            {
                SoftwareSectionId.Codex => SnapshotOf(openaiResult)?.ManualResets,
                SoftwareSectionId.Cursor => SnapshotOf(cursorResult)?.ManualResets,
                _ => null
            };
            var incomingDiag = section switch
            {
                SoftwareSectionId.Codex => SnapshotOf(openaiResult)?.DiagnosticJson,
                SoftwareSectionId.Cursor => SnapshotOf(cursorResult)?.DiagnosticJson,
                _ => null
            };
            health[section.Wire()] = ApplyAttempt(health[section.Wire()], attempt, incoming, planLabel, signedIn, incomingManual, incomingDiag);
        }

        MarkUnsignedOrExpired(cursorSignedIn, new[] { SoftwareSectionId.Cursor, SoftwareSectionId.GrokBot }, attempts, health, nowMs);
        MarkUnsignedOrExpired(openaiSignedIn, new[] { SoftwareSectionId.Codex }, attempts, health, nowMs);

        var cursorHealth = health[SoftwareSectionId.Cursor.Wire()];
        var grokHealth = health[SoftwareSectionId.GrokBot.Wire()];
        var codexHealth = health[SoftwareSectionId.Codex.Wire()];

        var cursorProvider = RebuildProvider(
            ProviderId.Cursor, prior.Provider(ProviderId.Cursor), SnapshotOf(cursorResult), cursorResult,
            cursorSignedIn || cursorHealth.SignedIn,
            cursorHealth.LastGoodMeterList.Concat(grokHealth.LastGoodMeterList).ToList(),
            cursorHealth.LastGoodPlanLabel, cursorHealth.LastGoodManualResets, cursorHealth.LastGoodDiagnosticJson);
        var openaiProvider = RebuildProvider(
            ProviderId.OpenAi, prior.Provider(ProviderId.OpenAi), SnapshotOf(openaiResult), openaiResult,
            openaiSignedIn || codexHealth.SignedIn,
            codexHealth.LastGoodMeterList.ToList(),
            codexHealth.LastGoodPlanLabel, codexHealth.LastGoodManualResets, codexHealth.LastGoodDiagnosticJson);

        var log = prior.Attempts.Concat(attempts).TakeLast(StalePolicy.AttemptLogLimit).ToList();
        return new UsageSnapshot(
            FetchedAtEpochMs: nowMs,
            Providers: new[] { cursorProvider, openaiProvider },
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
        var sections = provider == ProviderId.Cursor
            ? new[] { SoftwareSectionId.Cursor, SoftwareSectionId.GrokBot }
            : new[] { SoftwareSectionId.Codex };
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
