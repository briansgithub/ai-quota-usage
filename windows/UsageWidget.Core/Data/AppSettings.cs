using System.Text.Json;
using System.Text.Json.Serialization;

namespace UsageWidget;

public sealed class AppSettings
{
    private readonly ISettingsStore _store;
    private SettingsState _state;

    public AppSettings(ISettingsStore store)
    {
        _store = store;
        _state = store.Load() ?? new SettingsState();
    }

    public event Action? Changed;

    public bool FailureNotificationsEnabled { get => _state.FailureNotifications; set => Set(s => s with { FailureNotifications = value }); }
    public double PercentPerFiveHourWindow
    {
        get => PacingPolicy.ClampPercentPerWindow(_state.PercentPerWindow);
        set => Set(s => s with { PercentPerWindow = PacingPolicy.ClampPercentPerWindow(value) });
    }
    public bool NotifyUsageRestored { get => _state.NotifyUsageRestored; set => Set(s => s with { NotifyUsageRestored = value }); }
    public bool NotifyPacingReminders { get => _state.NotifyPacingReminders; set => Set(s => s with { NotifyPacingReminders = value }); }
    public bool NotifyPacingMissed { get => _state.NotifyPacingMissed; set => Set(s => s with { NotifyPacingMissed = value }); }
    public bool QuietHoursEnabled { get => _state.QuietHoursEnabled; set => Set(s => s with { QuietHoursEnabled = value }); }
    public int QuietHoursStartHour { get => _state.QuietHoursStartHour; set => Set(s => s with { QuietHoursStartHour = value }); }
    public int QuietHoursEndHour { get => _state.QuietHoursEndHour; set => Set(s => s with { QuietHoursEndHour = value }); }
    public long NextRefreshAtEpochMs { get => _state.NextRefreshAtEpochMs; private set => Set(s => s with { NextRefreshAtEpochMs = value }); }
    public long LastPeriodicRunEpochMs { get => _state.LastPeriodicRunEpochMs; private set => Set(s => s with { LastPeriodicRunEpochMs = value }); }
    public bool AlwaysOnTop { get => _state.AlwaysOnTop; set => Set(s => s with { AlwaysOnTop = value }); }
    public bool DebugLogging { get => _state.DebugLogging; set => Set(s => s with { DebugLogging = value }); }
    public bool StartWithWindows { get => _state.StartWithWindows; set => Set(s => s with { StartWithWindows = value }); }
    public bool MiniPanelVisible { get => _state.MiniPanelVisible; set => Set(s => s with { MiniPanelVisible = value }); }
    public double MiniLeft { get => _state.MiniLeft; set => Set(s => s with { MiniLeft = value }); }
    public double MiniTop { get => _state.MiniTop; set => Set(s => s with { MiniTop = value }); }
    public double MiniWidth { get => _state.MiniWidth; set => Set(s => s with { MiniWidth = value }); }
    public double MiniHeight { get => _state.MiniHeight; set => Set(s => s with { MiniHeight = value }); }

    public MiniRect MiniPlacement => new(MiniLeft, MiniTop, MiniWidth, MiniHeight);

    public void SaveMiniPlacement(MiniRect placement) =>
        Set(s => s with
        {
            MiniLeft = placement.Left,
            MiniTop = placement.Top,
            MiniWidth = placement.Width,
            MiniHeight = placement.Height
        });

    public bool QuotaAlertsEnabled { get => _state.QuotaAlertsEnabled; set => Set(s => s with { QuotaAlertsEnabled = value }); }
    public string QuotaThresholdsCsv
    {
        get => string.IsNullOrWhiteSpace(_state.QuotaThresholdsCsv) ? QuotaThresholds.DefaultCsv : _state.QuotaThresholdsCsv!;
        set => Set(s => s with { QuotaThresholdsCsv = QuotaThresholds.ToCsv(QuotaThresholds.ParseCsv(value)) });
    }
    public bool QuotaGrokWeeklyEnabled { get => _state.QuotaGrokWeeklyEnabled; set => Set(s => s with { QuotaGrokWeeklyEnabled = value }); }
    public bool QuotaCursorModelsEnabled { get => _state.QuotaCursorModelsEnabled; set => Set(s => s with { QuotaCursorModelsEnabled = value }); }
    public bool QuotaCodexFiveHourEnabled { get => _state.QuotaCodexFiveHourEnabled; set => Set(s => s with { QuotaCodexFiveHourEnabled = value }); }
    public bool QuotaCodexWeeklyEnabled { get => _state.QuotaCodexWeeklyEnabled; set => Set(s => s with { QuotaCodexWeeklyEnabled = value }); }
    public bool QuotaClaudeFiveHourEnabled { get => _state.QuotaClaudeFiveHourEnabled; set => Set(s => s with { QuotaClaudeFiveHourEnabled = value }); }
    public bool QuotaClaudeWeeklyEnabled { get => _state.QuotaClaudeWeeklyEnabled; set => Set(s => s with { QuotaClaudeWeeklyEnabled = value }); }
    public bool ShowCursorOnDemand { get => _state.ShowCursorOnDemand; set => Set(s => s with { ShowCursorOnDemand = value }); }
    public bool ShowGrokOnDemand { get => _state.ShowGrokOnDemand; set => Set(s => s with { ShowGrokOnDemand = value }); }

    /// <summary>Meter ids hidden on the mini panel and tray popup (see <see cref="WidgetBars"/>).</summary>
    public IReadOnlySet<string> HiddenWidgetBars =>
        (_state.HiddenWidgetBars ?? new List<string>()).ToHashSet(StringComparer.OrdinalIgnoreCase);

    public bool IsWidgetBarVisible(string meterId) => !HiddenWidgetBars.Contains(meterId);

    public void SetWidgetBarVisible(string meterId, bool visible)
    {
        var set = HiddenWidgetBars.ToHashSet(StringComparer.OrdinalIgnoreCase);
        if (visible ? !set.Remove(meterId) : !set.Add(meterId)) return;
        Set(s => s with { HiddenWidgetBars = set.OrderBy(x => x, StringComparer.Ordinal).ToList() });
    }

    public AlertPreferences AlertPreferencesValue() => new(
        PercentPerFiveHourWindow,
        FailureNotificationsEnabled,
        NotifyUsageRestored,
        NotifyPacingReminders,
        NotifyPacingMissed,
        QuietHoursEnabled,
        QuietHoursStartHour,
        QuietHoursEndHour);

    public QuotaThresholdPreferences QuotaPreferences()
    {
        var enabled = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (QuotaGrokWeeklyEnabled) enabled.Add(QuotaRuleId.GrokWeekly.Wire());
        if (QuotaCursorModelsEnabled) enabled.Add(QuotaRuleId.CursorModels.Wire());
        if (QuotaCodexFiveHourEnabled) enabled.Add(QuotaRuleId.CodexFiveHour.Wire());
        if (QuotaCodexWeeklyEnabled) enabled.Add(QuotaRuleId.CodexWeekly.Wire());
        if (QuotaClaudeFiveHourEnabled) enabled.Add(QuotaRuleId.ClaudeFiveHour.Wire());
        if (QuotaClaudeWeeklyEnabled) enabled.Add(QuotaRuleId.ClaudeWeekly.Wire());
        return new QuotaThresholdPreferences(
            QuotaAlertsEnabled,
            QuotaThresholds.ParseCsv(QuotaThresholdsCsv),
            enabled);
    }

    public IReadOnlyDictionary<string, QuotaThresholdState> QuotaFiredStates() =>
        _state.QuotaFiredStates ?? new Dictionary<string, QuotaThresholdState>();

    public void ReplaceQuotaFiredStates(IReadOnlyDictionary<string, QuotaThresholdState> states) =>
        Set(s => s with
        {
            QuotaFiredStates = states.ToDictionary(kv => kv.Key, kv => kv.Value, StringComparer.OrdinalIgnoreCase)
        });

    public IReadOnlyCollection<string> SeenAlertKeys() =>
        (_state.SeenAlertKeys ?? new List<string>()).Where(s => !string.IsNullOrWhiteSpace(s)).ToList();

    public void ReplaceSeenAlertKeys(IEnumerable<string> keys) =>
        Set(s => s with { SeenAlertKeys = keys.Where(k => !string.IsNullOrWhiteSpace(k)).ToList() });

    public void OnPeriodicRun(long nowMs)
    {
        Set(s => s with
        {
            LastPeriodicRunEpochMs = nowMs,
            NextRefreshAtEpochMs = nowMs + RefreshSchedule.IntervalMs
        });
    }

    public void ArmNextRefreshFromNow(long nowMs, long delayMs = RefreshSchedule.IntervalMs) =>
        Set(s => s with { NextRefreshAtEpochMs = nowMs + delayMs });

    public void ArmNextRefreshIfUnarmed(long nowMs)
    {
        if (_state.NextRefreshAtEpochMs <= 0) ArmNextRefreshFromNow(nowMs);
    }

    private void Set(Func<SettingsState, SettingsState> mutate)
    {
        _state = mutate(_state);
        _store.Save(_state);
        Changed?.Invoke();
    }
}

public sealed record SettingsState(
    bool FailureNotifications = true,
    double PercentPerWindow = PacingPolicy.DefaultPercentPerWindow,
    bool NotifyUsageRestored = true,
    bool NotifyPacingReminders = true,
    bool NotifyPacingMissed = true,
    bool QuietHoursEnabled = false,
    int QuietHoursStartHour = QuietHours.DefaultStartHour,
    int QuietHoursEndHour = QuietHours.DefaultEndHour,
    long NextRefreshAtEpochMs = 0,
    long LastPeriodicRunEpochMs = 0,
    bool AlwaysOnTop = false,
    bool StartWithWindows = false,
    bool MiniPanelVisible = true,
    double MiniLeft = 64,
    double MiniTop = 64,
    double MiniWidth = MiniChrome.DefaultWidth,
    double MiniHeight = MiniChrome.DefaultHeight,
    bool DebugLogging = false,
    List<string>? SeenAlertKeys = null,
    bool QuotaAlertsEnabled = true,
    string? QuotaThresholdsCsv = null,
    bool QuotaGrokWeeklyEnabled = true,
    bool QuotaCursorModelsEnabled = true,
    bool QuotaCodexFiveHourEnabled = false,
    bool QuotaCodexWeeklyEnabled = false,
    bool QuotaClaudeFiveHourEnabled = true,
    bool QuotaClaudeWeeklyEnabled = true,
    Dictionary<string, QuotaThresholdState>? QuotaFiredStates = null,
    bool ShowCursorOnDemand = true,
    bool ShowGrokOnDemand = true,
    List<string>? HiddenWidgetBars = null);

public interface ISettingsStore
{
    SettingsState? Load();
    void Save(SettingsState state);
}

public sealed class MemorySettingsStore : ISettingsStore
{
    private SettingsState? _state;
    public SettingsState? Load() => _state;
    public void Save(SettingsState state) => _state = state;
}

public sealed class JsonSettingsStore : ISettingsStore
{
    private readonly string _path;
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    public JsonSettingsStore(string path) => _path = path;

    public SettingsState? Load()
    {
        try
        {
            if (!File.Exists(_path)) return null;
            return JsonSerializer.Deserialize<SettingsState>(File.ReadAllText(_path), JsonOptions);
        }
        catch { return null; }
    }

    public void Save(SettingsState state)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        File.WriteAllText(_path, JsonSerializer.Serialize(state, JsonOptions));
    }
}
