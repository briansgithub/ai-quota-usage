namespace UsageWidget;

/// <summary>
/// Corner-dot on the tray icon. High used-% is shown on the bars (green), not as a tray cue —
/// the old orange “≤10% remaining” dot is gone. Error covers auth/expired and red-stale;
/// Stale covers fetch errors and milder stale; Ok is a clean icon.
/// </summary>
public enum TrayCue
{
    Ok,
    Stale,
    Error
}

public static class TrayCues
{
    public static TrayCue Of(UsageSnapshot snapshot, long nowMs)
    {
        var sections = UsageSections.From(snapshot, nowMs);
        if (sections.Any(s => s.Status == ProviderStatus.NeedsSignIn || s.StaleValue.Severity == StaleSeverity.Red))
            return TrayCue.Error;
        if (sections.Any(s => s.Status == ProviderStatus.Error || s.StaleValue.IsStale))
            return TrayCue.Stale;
        return TrayCue.Ok;
    }
}
