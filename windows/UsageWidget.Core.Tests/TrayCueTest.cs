using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class TrayCueTest
{
    [Fact]
    public void AuthExpiredIsErrorCue()
    {
        var snap = new UsageSnapshot(1_000, Providers: new[]
        {
            new ProviderSnapshot("cursor", "Cursor", "NEEDS_SIGN_IN"),
            new ProviderSnapshot("openai", "ChatGPT / Codex", "UNSIGNED")
        }, SectionHealth: new[]
        {
            new SectionHealth("cursor", LastOutcome: "AUTH_EXPIRED", ShortReason: "Sign-in expired"),
            new SectionHealth("grok_bot", LastOutcome: "AUTH_EXPIRED"),
            new SectionHealth("codex", LastOutcome: "NEVER")
        });
        Assert.Equal(TrayCue.Error, TrayCues.Of(snap, 1_000));
    }

    [Fact]
    public void NetworkFailureWithLastGoodIsStaleCue()
    {
        var meters = new[] { new UsageMeter("openai.five_hour", "5-hour", RemainingPercent: 40, UsedPercent: 60) };
        var snap = new UsageSnapshot(1_000, Providers: new[]
        {
            new ProviderSnapshot("cursor", "Cursor", "UNSIGNED"),
            new ProviderSnapshot("openai", "ChatGPT / Codex", "ERROR", Meters: meters)
        }, SectionHealth: new[]
        {
            new SectionHealth("cursor", LastOutcome: "NEVER"),
            new SectionHealth("grok_bot", LastOutcome: "NEVER"),
            new SectionHealth("codex", LastOutcome: "NETWORK", ShortReason: "Network error", SignedIn: true,
                LastGoodMeters: meters, LastSuccessEpochMs: 1)
        });
        Assert.Equal(TrayCue.Stale, TrayCues.Of(snap, 1_000));
    }

    [Fact]
    public void HighUsedDoesNotGetAUsageDot()
    {
        var meters = new[]
        {
            new UsageMeter("openai.five_hour", "5-hour", RemainingPercent: 8, UsedPercent: 92),
            new UsageMeter("openai.weekly", "Weekly", RemainingPercent: 50, UsedPercent: 50)
        };
        var snap = new UsageSnapshot(1_000, Providers: new[]
        {
            new ProviderSnapshot("cursor", "Cursor", "UNSIGNED"),
            new ProviderSnapshot("openai", "ChatGPT / Codex", "OK", Meters: meters)
        }, SectionHealth: new[]
        {
            new SectionHealth("cursor", LastOutcome: "NEVER"),
            new SectionHealth("grok_bot", LastOutcome: "NEVER"),
            new SectionHealth("codex", LastOutcome: "SUCCESS", SignedIn: true, LastGoodMeters: meters, LastSuccessEpochMs: 1_000)
        });
        Assert.Equal(TrayCue.Ok, TrayCues.Of(snap, 1_000));
    }
}
