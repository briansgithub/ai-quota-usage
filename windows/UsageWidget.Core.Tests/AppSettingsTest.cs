using System.Text.Json;
using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class AppSettingsTest
{
    [Fact]
    public void AlwaysOnTopDefaultsOffAndMiniSizeHasDefaults()
    {
        var settings = new AppSettings(new MemorySettingsStore());
        Assert.False(settings.AlwaysOnTop);
        Assert.Equal(MiniChrome.DefaultWidth, settings.MiniWidth);
        Assert.Equal(MiniChrome.DefaultHeight, settings.MiniHeight);
    }

    [Fact]
    public void MissingJsonAlwaysOnTopIsOff()
    {
        var state = JsonSerializer.Deserialize<SettingsState>("{}");
        Assert.NotNull(state);
        Assert.False(state!.AlwaysOnTop);
        Assert.False(state.DebugLogging);
        Assert.Equal(MiniChrome.DefaultWidth, state.MiniWidth);
        Assert.Equal(MiniChrome.DefaultHeight, state.MiniHeight);
        Assert.True(state.QuotaAlertsEnabled);
        Assert.True(state.QuotaGrokWeeklyEnabled);
        Assert.True(state.QuotaCursorModelsEnabled);
        Assert.False(state.QuotaCodexFiveHourEnabled);
        Assert.False(state.QuotaCodexWeeklyEnabled);
        Assert.True(state.ShowCursorOnDemand);
        Assert.True(state.ShowGrokOnDemand);
    }

    [Fact]
    public void OnDemandVisibilityDefaultsOnAndRoundTrips()
    {
        var settings = new AppSettings(new MemorySettingsStore());
        Assert.True(settings.ShowCursorOnDemand);
        Assert.True(settings.ShowGrokOnDemand);
        settings.ShowCursorOnDemand = false;
        settings.ShowGrokOnDemand = false;
        Assert.False(settings.ShowCursorOnDemand);
        Assert.False(settings.ShowGrokOnDemand);
    }

    [Fact]
    public void QuotaFiredStatesRoundTripInMemory()
    {
        var settings = new AppSettings(new MemorySettingsStore());
        settings.ReplaceQuotaFiredStates(new Dictionary<string, QuotaThresholdState>
        {
            ["grok.weekly"] = new("123", 22.0, new List<int> { 90, 80, 30 })
        });
        var loaded = settings.QuotaFiredStates();
        Assert.Equal(22.0, loaded["grok.weekly"].Remaining);
        Assert.Contains(30, loaded["grok.weekly"].FiredList);
        var prefs = settings.QuotaPreferences();
        Assert.True(prefs.MasterEnabled);
        Assert.Equal(QuotaThresholds.Default, prefs.ThresholdList);
        Assert.DoesNotContain(QuotaRuleId.CodexWeekly.Wire(), prefs.EnabledRuleSet);
    }
}
