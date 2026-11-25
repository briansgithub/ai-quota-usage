using System.Drawing;
using System.IO;
using System.Windows;
using System.Windows.Threading;
using UsageWidget;
using Forms = System.Windows.Forms;

namespace UsageWidget.App;

internal sealed class AppHost : IDisposable
{
    public AppSettings Settings { get; }
    public ICookieStore Cookies => _cookies;
    private readonly DpapiCookieStore _cookies;
    private readonly UsageRepository _repo;
    private readonly DispatcherTimer _refresh = new() { Interval = TimeSpan.FromMinutes(15) };
    private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly List<ScheduledWake> _wakes = new();
    private readonly HashSet<string> _firedWakes = new();
    private MiniPanelWindow? _mini;
    private DashboardWindow? _dashboard;
    private Forms.NotifyIcon? _tray;
    private Forms.ToolStripMenuItem? _alwaysOnTopItem;
    private Icon? _icon;
    private Icon? _trayIcon;
    private TrayCue? _trayCue;
    private TrayGlanceWindow? _glance;
    private readonly DispatcherTimer _glancePoll = new() { Interval = TimeSpan.FromMilliseconds(32) };
    private GlanceModel? _shownGlance;
    private bool _haveHoverPoint;
    private int _hoverX;
    private int _hoverY;
    private long _nextRefreshAt;
    private bool _busy;
    private bool _miniInTray;
    private bool _dashboardInTray;
    public bool IsExiting { get; private set; }

    public AppHost()
    {
        Settings = new AppSettings(new JsonSettingsStore(Paths.SettingsPath));
        _cookies = new DpapiCookieStore();
        var html = new WebViewHtmlExtractor(Dispatcher.CurrentDispatcher);
        _repo = new UsageRepository(_cookies, html);
        Settings.Changed += () => Dispatcher.CurrentDispatcher.BeginInvoke(Render);
        _repo.SnapshotChanged += _ => Dispatcher.CurrentDispatcher.BeginInvoke(Render);
        _repo.RefreshingChanged += _ => Dispatcher.CurrentDispatcher.BeginInvoke(Render);
        _repo.Refreshed += OnRefreshed;
        _refresh.Tick += async (_, _) => await PeriodicRefresh();
        _tick.Tick += (_, _) => OnTick();
    }

    public void Start()
    {
        WidgetLog.Configure(Settings.DebugLogging);
        WidgetLog.Write($"start alwaysOnTop={Settings.AlwaysOnTop} debug={Settings.DebugLogging}");
        StartupManager.SetEnabled(Settings.StartWithWindows);
        Settings.ArmNextRefreshIfUnarmed(Now());
        _nextRefreshAt = Settings.NextRefreshAtEpochMs;
        CreateTray();
        _mini = new MiniPanelWindow(this);
        if (Settings.MiniPanelVisible) _mini.Show();
        _refresh.Start();
        _tick.Start();
        ConsumeTestNotificationFlag();
        Render();
        _ = RefreshNow();
    }

    public async Task RefreshNow()
    {
        if (_busy || IsExiting) return;
        _busy = true;
        try
        {
            await _repo.RefreshAsync(allowHtmlFallback: true);
            Settings.ArmNextRefreshFromNow(Now());
            _nextRefreshAt = Settings.NextRefreshAtEpochMs;
        }
        catch (Exception ex)
        {
            ToastService.Show("Usage Widget", DiagnosticSanitizer.Message(ex.Message) ?? "Refresh failed");
        }
        finally
        {
            _busy = false;
            Render();
        }
    }

    public void OpenDashboard()
    {
        if (_dashboard is null)
        {
            _dashboard = new DashboardWindow(this);
            _dashboard.Closed += (_, _) => { if (!IsExiting) _dashboard = null; };
        }
        _dashboardInTray = false;
        _dashboard.ShowInTaskbar = true;
        _dashboard.Show();
        _dashboard.WindowState = WindowState.Normal;
        _dashboard.Activate();
        _mini?.SetExpanded(true);
        Render();
    }

    public void ToggleMini()
    {
        if (_mini is null) _mini = new MiniPanelWindow(this);
        if (_mini.IsVisible)
            HideMiniToTray();
        else
            ShowMini();
        Render();
    }

    public void ShowMini()
    {
        if (_mini is null) _mini = new MiniPanelWindow(this);
        _miniInTray = false;
        Settings.MiniPanelVisible = true;
        _mini.Show();
        _mini.Activate();
        _mini.SetExpanded(_dashboard is { IsVisible: true });
    }

    public void HideMiniToTray()
    {
        if (_mini is null) return;
        _miniInTray = true;
        Settings.MiniPanelVisible = false;
        _mini.Hide();
    }

    public void HideDashboardToTray()
    {
        if (_dashboard is null) return;
        _dashboardInTray = true;
        _dashboard.ShowInTaskbar = false;
        _dashboard.Hide();
        _mini?.SetExpanded(false);
    }

    public void RestoreFromTray()
    {
        if (_dashboardInTray) OpenDashboard();
        if (_miniInTray || _mini is not { IsVisible: true }) ShowMini();
        else _mini.Activate();
        Render();
    }

    public void ToggleExpandedFromMini()
    {
        if (_dashboard is { IsVisible: true })
            CollapseDashboard();
        else
            OpenDashboard();
        ShowMini();
        Render();
    }

    public void CollapseDashboard()
    {
        if (_dashboard is null) return;
        _dashboardInTray = false;
        _dashboard.ShowInTaskbar = false;
        _dashboard.Hide();
        _mini?.SetExpanded(false);
    }

    public void ExitApp()
    {
        if (IsExiting) return;
        IsExiting = true;
        _refresh.Stop();
        _tick.Stop();
        _glancePoll.Stop();
        HideGlance();
        TrayNotify.Detach();
        if (_glance is not null)
        {
            _glance.Close();
            _glance = null;
        }
        ToastService.Stop();
        if (_tray is not null)
        {
            _tray.Visible = false;
            _tray.Dispose();
            _tray = null;
        }
        _trayIcon?.Dispose();
        _trayIcon = null;
        _icon?.Dispose();
        _icon = null;
        Application.Current.Shutdown();
    }

    public void OpenSignIn(SoftwareSection section) =>
        OpenSignIn(section.SignInProvider, section.Id, AuthCopy.IsReauth(section));

    public void OpenSignIn(ProviderId provider, SoftwareSectionId sectionId, bool reauth)
    {
        if (provider == ProviderId.Gemini)
        {
            _repo.ConnectGemini();
            System.Windows.MessageBox.Show("Sign in using Antigravity on this PC, then refresh Usage Widget. Credentials are read from your .gemini folder; the source files are never changed.", "Gemini (Antigravity)");
            _ = RefreshNow();
            return;
        }
        var win = new SignInWindow(this, provider, sectionId, reauth);
        win.Show();
    }

    public void OnSignedIn(ProviderId provider)
    {
        _ = RefreshNow();
    }

    public void SignOut(ProviderId provider)
    {
        _repo.SignOut(provider);
        Render();
    }

    public void SignOutSection(SoftwareSection section) => SignOut(section.SignInProvider);

    public void SetAlwaysOnTop(bool value)
    {
        var before = Settings.AlwaysOnTop;
        if (before != value)
            Settings.AlwaysOnTop = value;
        ApplyAlwaysOnTop();
        if (_alwaysOnTopItem is not null && _alwaysOnTopItem.Checked != value)
            _alwaysOnTopItem.Checked = value;
        _mini?.SyncKeepOnTop(value);
        _dashboard?.SyncAlwaysOnTop(value);
        WidgetLog.Write($"SetAlwaysOnTop {before} -> {value} mini.Topmost={_mini?.Topmost}");
    }

    public void ApplyAlwaysOnTop()
    {
        if (_mini is not null) _mini.Topmost = Settings.AlwaysOnTop;
    }

    private async Task PeriodicRefresh()
    {
        if (IsExiting) return;
        Settings.OnPeriodicRun(Now());
        _nextRefreshAt = Settings.NextRefreshAtEpochMs;
        await RefreshNow();
    }

    private void OnRefreshed(UsageSnapshot previous, UsageSnapshot current, long nowMs)
    {
        var plan = UsageAlertEngine.Evaluate(previous, current, nowMs, Settings.AlertPreferencesValue(), Settings.SeenAlertKeys());
        Settings.ReplaceSeenAlertKeys(plan.NewSeenKeys);
        foreach (var alert in plan.Alerts)
            ToastService.Show(alert.Title, alert.Body);
        lock (_wakes)
        {
            _wakes.Clear();
            _wakes.AddRange(plan.Wakes);
            _firedWakes.Clear();
        }
        var quiet = QuietHours.IsQuiet(
            nowMs,
            Settings.QuietHoursEnabled,
            Settings.QuietHoursStartHour,
            Settings.QuietHoursEndHour);
        var quota = QuotaThresholdEngine.Evaluate(
            current,
            nowMs,
            Settings.QuotaPreferences(),
            Settings.QuotaFiredStates(),
            quiet);
        Settings.ReplaceQuotaFiredStates(quota.States);
        foreach (var alert in quota.Alerts)
            ToastService.Show(alert.Title, alert.Body);
        MaybeNotifyStale(current, nowMs);
        Dispatcher.CurrentDispatcher.BeginInvoke(Render);
    }

    public void ShowTestNotification() =>
        ToastService.Show(QuotaThresholdEngine.TestTitle, QuotaThresholdEngine.TestBody);

    private void MaybeNotifyStale(UsageSnapshot snapshot, long nowMs)
    {
        if (!Settings.FailureNotificationsEnabled) return;
        foreach (var health in snapshot.HealthList)
        {
            var view = health.StaleView(nowMs);
            if (view.ShouldNotify)
                ToastService.Show($"{SoftwareSectionIds.FromWire(health.SectionId).Title()} is stale", view.ShortReason ?? "Refresh overdue");
        }
    }

    private void OnTick()
    {
        if (IsExiting) return;
        var now = Now();
        if (now >= _nextRefreshAt && !_busy)
            _ = PeriodicRefresh();
        List<ScheduledWake> due;
        lock (_wakes)
        {
            due = _wakes.Where(w => w.AtEpochMs <= now && _firedWakes.Add(w.UniqueName)).ToList();
        }
        if (due.Count > 0) _ = RefreshNow();
        ConsumeTestNotificationFlag();
        Render();
    }

    private void ConsumeTestNotificationFlag()
    {
        try
        {
            if (!File.Exists(Paths.TestNotificationFlag)) return;
            File.Delete(Paths.TestNotificationFlag);
            ShowTestNotification();
        }
        catch { /* flag is best-effort */ }
    }

    private void Render()
    {
        if (IsExiting) return;
        var now = Now();
        var snap = _repo.Snapshot;
        _mini?.Bind(snap, _nextRefreshAt, now);
        _mini?.SetExpanded(_dashboard is { IsVisible: true });
        _dashboard?.Bind(snap, _nextRefreshAt, now, _repo.Refreshing || _busy);
        if (_tray is not null)
            ApplyTrayCue(TrayCues.Of(snap, now));
    }

    private void CreateTray()
    {
        _icon = LoadIcon();
        _tray = new Forms.NotifyIcon
        {
            Icon = _icon,
            Visible = true,
            Text = string.Empty
        };
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => OpenDashboard());
        menu.Items.Add("Show / hide mini panel", null, (_, _) => ToggleMini());
        menu.Items.Add("Refresh now", null, (_, _) => _ = RefreshNow());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Sign in to Cursor", null, (_, _) => OpenSignIn(ProviderId.Cursor, SoftwareSectionId.Cursor, false));
        menu.Items.Add("Sign in to Cursor for Grok Bot", null, (_, _) => OpenSignIn(ProviderId.Cursor, SoftwareSectionId.GrokBot, false));
        menu.Items.Add("Sign in to ChatGPT for Codex", null, (_, _) => OpenSignIn(ProviderId.OpenAi, SoftwareSectionId.Codex, false));
        menu.Items.Add("Connect Gemini (Antigravity)", null, (_, _) => OpenSignIn(ProviderId.Gemini, SoftwareSectionId.Gemini, false));
        menu.Items.Add("Sign in to Claude", null, (_, _) => OpenSignIn(ProviderId.Claude, SoftwareSectionId.Claude, false));
        menu.Items.Add(new Forms.ToolStripSeparator());
        _alwaysOnTopItem = new Forms.ToolStripMenuItem("Always on top") { Checked = Settings.AlwaysOnTop, CheckOnClick = true };
        _alwaysOnTopItem.CheckedChanged += (_, _) => SetAlwaysOnTop(_alwaysOnTopItem.Checked);
        menu.Items.Add(_alwaysOnTopItem);
        var start = new Forms.ToolStripMenuItem("Start with Windows") { Checked = Settings.StartWithWindows || StartupManager.IsEnabled(), CheckOnClick = true };
        start.CheckedChanged += (_, _) => { Settings.StartWithWindows = start.Checked; StartupManager.SetEnabled(start.Checked); };
        menu.Items.Add(start);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => ExitApp());
        _tray.ContextMenuStrip = menu;
        _glancePoll.Tick += (_, _) => PollGlanceHover();
        TrayNotify.Attach(_tray, OnTrayPopupOpen, OnTrayPopupClose);
        _tray.MouseMove += (_, _) => ShowGlance();
        _tray.MouseClick += (_, e) =>
        {
            HideGlance();
            if (e.Button == Forms.MouseButtons.Left) RestoreFromTray();
        };
        _tray.DoubleClick += (_, _) => OpenDashboard();
    }

    private void OnTrayPopupOpen() => Dispatcher.CurrentDispatcher.BeginInvoke(ShowGlance);

    private void OnTrayPopupClose() => Dispatcher.CurrentDispatcher.BeginInvoke(HideGlanceIfPointerLeftIcon);

    private void ShowGlance()
    {
        if (IsExiting || _tray is null) return;
        try
        {
            EnsureGlance();
            if (NativeWindow.TryCursorScreenPx(out var x, out var y))
            {
                _hoverX = x;
                _hoverY = y;
                _haveHoverPoint = true;
            }
            var model = GlanceUsage.From(_repo.Snapshot, Now(), Settings.ShowCursorOnDemand, Settings.ShowGrokOnDemand, Settings.HiddenWidgetBars);
            var wasHidden = !_glance!.IsVisible;
            if (!SameGlance(_shownGlance, model))
            {
                _glance.Bind(model);
                _shownGlance = model;
            }
            if (wasHidden)
            {
                _glance.Show();
                _glance.PlaceIfNeeded(justShown: true);
            }
            _glancePoll.Start();
        }
        catch
        {
            /* custom popup is best-effort; never fall back to a native tooltip */
        }
    }

    private void EnsureGlance() => _glance ??= new TrayGlanceWindow();

    private void PollGlanceHover()
    {
        if (_glance is not { IsVisible: true })
        {
            _glancePoll.Stop();
            return;
        }
        HideGlanceIfPointerLeftIcon();
    }

    private void HideGlanceIfPointerLeftIcon()
    {
        if (_glance is not { IsVisible: true }) return;
        if (PointerStillOnTrayIcon()) return;
        HideGlance();
    }

    /// <summary>
    /// MouseMove and NIN_POPUPOPEN only arrive while Windows says the pointer is on the tray icon.
    /// The latest of those points is the icon. A failed icon-rectangle query must not count as leaving,
    /// or Windows opens the popup again and it flickers.
    /// </summary>
    private bool PointerStillOnTrayIcon()
    {
        if (!_haveHoverPoint) return true;
        if (!NativeWindow.TryCursorScreenPx(out var x, out var y)) return true;
        if (TrayGlancePlacement.PointerStillOnIcon(x, y, _hoverX, _hoverY)) return true;
        if (!TrayNotify.TryGetIconRect(out var px)) return false;
        var icon = new MiniRect(px.Left, px.Top, px.Right - px.Left, px.Bottom - px.Top);
        return TrayGlancePlacement.PointerOverIcon(icon, x, y);
    }

    private static bool SameGlance(GlanceModel? shown, GlanceModel next)
    {
        if (shown is null || shown.EmptyMessage != next.EmptyMessage || shown.Lines.Count != next.Lines.Count)
            return false;
        for (var i = 0; i < shown.Lines.Count; i++)
        {
            var a = shown.Lines[i];
            var b = next.Lines[i];
            if (a.Label != b.Label || a.UsedPercent != b.UsedPercent || a.IsCapBar != b.IsCapBar)
                return false;
            if (a.ExpectedUsedPercent is null != b.ExpectedUsedPercent is null)
                return false;
            if (a.ExpectedUsedPercent is { } left && b.ExpectedUsedPercent is { } right && Math.Abs(left - right) >= 1)
                return false;
        }
        return true;
    }

    private void HideGlance()
    {
        _glancePoll.Stop();
        _haveHoverPoint = false;
        if (_glance is { IsVisible: true }) _glance.Hide();
    }

    private void ApplyTrayCue(TrayCue cue)
    {
        if (_tray is null || _icon is null) return;
        if (cue == _trayCue && _trayIcon is not null) return;
        _trayCue = cue;
        Icon next;
        try { next = TrayIcons.WithCue(_icon, cue); }
        catch { next = (Icon)_icon.Clone(); }
        _tray.Icon = next;
        _trayIcon?.Dispose();
        _trayIcon = next;
        _tray.Text = string.Empty;
        TrayNotify.SuppressStandardTip(_tray);
    }

    private static Icon LoadIcon()
    {
        try { return AppBranding.LoadTrayIcon(16); }
        catch { /* fall through */ }
        var exe = Environment.ProcessPath;
        if (!string.IsNullOrWhiteSpace(exe))
        {
            try { return Icon.ExtractAssociatedIcon(exe) ?? SystemIcons.Application; }
            catch { /* ignore */ }
        }
        return SystemIcons.Application;
    }

    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public void Dispose()
    {
        if (!IsExiting) ExitApp();
    }
}
