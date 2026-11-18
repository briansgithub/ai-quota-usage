using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;
using UsageWidget;
using Forms = System.Windows.Forms;

namespace UsageWidget.App;

internal static class Ui
{
    public static TextBlock T(string text, Brush? brush = null, double size = 12, FontWeight? weight = null, TextWrapping wrap = TextWrapping.Wrap) =>
        new()
        {
            Text = text,
            Foreground = brush ?? Theme.TextBrush,
            FontSize = size,
            FontWeight = weight ?? FontWeights.Normal,
            TextWrapping = wrap,
            FontFamily = new FontFamily("Segoe UI")
        };

    public static Button Primary(string label, RoutedEventHandler click)
    {
        var b = new Button
        {
            Content = label,
            Background = Theme.BarBrush,
            Foreground = Brushes.White,
            BorderThickness = new Thickness(0),
            Padding = new Thickness(12, 6, 12, 6),
            Cursor = Cursors.Hand
        };
        b.Click += click;
        return b;
    }

    public static Button Ghost(string label, RoutedEventHandler click)
    {
        var b = new Button
        {
            Content = label,
            Background = Brushes.Transparent,
            Foreground = Theme.TextBrush,
            BorderBrush = Theme.OutlineBrush,
            Padding = new Thickness(10, 4, 10, 4),
            Cursor = Cursors.Hand
        };
        b.Click += click;
        return b;
    }

    public static Button GlyphButton(string glyph, string tip, Action click, double width = 32)
    {
        var icon = new TextBlock
        {
            Text = glyph,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 12,
            Foreground = Theme.MutedBrush,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            Tag = "glyph"
        };
        var button = new Button
        {
            Width = width,
            Height = MiniChrome.CaptionHeight,
            Padding = new Thickness(0),
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            BorderBrush = Brushes.Transparent,
            Cursor = Cursors.Hand,
            ToolTip = tip,
            Content = icon,
            Focusable = false
        };
        var border = new FrameworkElementFactory(typeof(Border));
        border.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(Button.BackgroundProperty));
        var presenter = new FrameworkElementFactory(typeof(ContentPresenter));
        presenter.SetValue(ContentPresenter.HorizontalAlignmentProperty, HorizontalAlignment.Center);
        presenter.SetValue(ContentPresenter.VerticalAlignmentProperty, VerticalAlignment.Center);
        border.AppendChild(presenter);
        button.Template = new ControlTemplate(typeof(Button)) { VisualTree = border };
        button.MouseEnter += (_, _) => button.Background = Theme.CaptionHoverBrush;
        button.MouseLeave += (_, _) => button.Background = Brushes.Transparent;
        button.Click += (_, e) =>
        {
            e.Handled = true;
            click();
        };
        return button;
    }

    public static CheckBox Check(string label, bool isChecked, Action<bool> onChange)
    {
        var box = new CheckBox { Content = label, IsChecked = isChecked, Foreground = Theme.TextBrush, Margin = new Thickness(0, 4, 0, 4) };
        box.Checked += (_, _) => onChange(true);
        box.Unchecked += (_, _) => onChange(false);
        return box;
    }

    public static FrameworkElement UsageBar(int usedPercent, double height = 8, double? expectedUsedPercent = null, bool capBar = false)
    {
        var clamped = Math.Clamp(usedPercent, 0, 100);
        var fill = new Rectangle
        {
            Fill = capBar ? Theme.CapFill(clamped) : Theme.PaceFill(clamped, expectedUsedPercent),
            Stroke = capBar ? Theme.CapStroke(clamped) : Theme.PaceStroke(clamped, expectedUsedPercent),
            StrokeThickness = height >= 6 ? 1 : 0.6,
            Height = height,
            RadiusX = Math.Max(1, height / 2),
            RadiusY = Math.Max(1, height / 2),
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center,
            Width = 1
        };
        var track = new Grid { Height = height };
        track.Children.Add(new Border { Background = Theme.TrackBrush, CornerRadius = new CornerRadius(Math.Max(1, height / 2)) });
        track.Children.Add(fill);
        Rectangle? tick = null;
        if (!capBar && expectedUsedPercent is { } exp)
        {
            tick = new Rectangle
            {
                Fill = Theme.BrightBrush,
                Stroke = Theme.ChromeBrush,
                StrokeThickness = 0.6,
                Width = 2,
                Height = height,
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Center,
                IsHitTestVisible = false
            };
            track.Children.Add(tick);
        }
        track.SizeChanged += (_, _) =>
        {
            var w = track.ActualWidth;
            fill.Width = Math.Max(0, w * clamped / 100.0);
            if (tick is not null && expectedUsedPercent is { } expected)
            {
                var x = w * Math.Clamp(expected, 0, 100) / 100.0;
                tick.Margin = new Thickness(Math.Clamp(x - 1, 0, Math.Max(0, w - 2)), 0, 0, 0);
            }
        };
        return track;
    }

    public static FrameworkElement MeterBlock(UsageMeter meter, long nowMs, bool compact, bool stale)
    {
        var col = new StackPanel { Margin = new Thickness(0, 0, 0, compact ? 4 : 8) };
        var row = new DockPanel { LastChildFill = true };
        var used = meter.UsedLabel();
        var reset = meter.ResetsAtEpochMs is { } r ? NyTime.FormatResetLine(r, nowMs) : meter.ResetLabel;
        row.Children.Add(T(meter.Title, stale ? Theme.MutedBrush : Theme.TextBrush, compact ? 12 : 14));
        if (used is not null)
        {
            var right = T(used, Theme.TextBrush, compact ? 11 : 13);
            DockPanel.SetDock(right, Dock.Right);
            row.Children.Insert(0, right);
        }
        col.Children.Add(row);
        if (meter.HasUsageBar)
            col.Children.Add(UsageBar(
                meter.DisplayUsedPercent ?? 0,
                expectedUsedPercent: meter.IsCapBar ? null : PaceWindows.ExpectedUsedPercent(meter, nowMs),
                capBar: meter.IsCapBar));
        if (!string.IsNullOrWhiteSpace(reset) && (used is not null || !meter.HasUsageBar))
        {
            var urgency = ResetEmphasis.Of(meter.ResetsAtEpochMs, nowMs);
            col.Children.Add(T(reset!, Theme.ResetBrush(urgency), Theme.ResetSize(urgency, compact), Theme.ResetWeight(urgency)));
        }
        return col;
    }

    public static MenuItem Menu(string header, Action click)
    {
        var item = new MenuItem { Header = header };
        item.Click += (_, _) => click();
        return item;
    }

    public static FrameworkElement SectionCard(
        SoftwareSection section,
        long nowMs,
        double percentPerWindow,
        bool compact,
        Action<SoftwareSection> onSignIn,
        Action<SoftwareSection>? onSignOut,
        Action? onRefresh = null)
    {
        var stale = section.StaleValue;
        var bg = !stale.IsStale ? Theme.SurfaceBrush : Theme.StaleBg(stale.Severity);
        var headerColor = stale.IsStale ? Theme.StaleBrush(stale.Severity) : Theme.TextBrush;
        var card = new Border
        {
            Background = bg,
            CornerRadius = new CornerRadius(compact ? 10 : 14),
            Padding = new Thickness(compact ? 8 : 14),
            Margin = new Thickness(0, 0, 0, compact ? 6 : 12),
            BorderBrush = Theme.OutlineBrush,
            BorderThickness = new Thickness(1)
        };
        var stack = new StackPanel();
        stack.Children.Add(T(section.Id.Title(), headerColor, compact ? 15 : 22, FontWeights.SemiBold));
        var authColor = section.Status is ProviderStatus.NeedsSignIn ? Theme.ImminentBrush
            : section.Status is ProviderStatus.Unsigned ? Theme.MutedBrush
            : Theme.MutedBrush;
        stack.Children.Add(T(AuthCopy.StatusLine(section), authColor, compact ? 10.5 : 12, FontWeights.Medium));
        if (!string.IsNullOrWhiteSpace(section.Subtitle) && !stale.IsStale && section.Status is not ProviderStatus.Unsigned and not ProviderStatus.NeedsSignIn)
            stack.Children.Add(T(section.Subtitle!, Theme.MutedBrush, compact ? 10 : 12));
        if (stale.IsStale)
        {
            var line = string.Join(" · ", new[] { stale.LastUpdatedLine, stale.ShortReason }.Where(s => !string.IsNullOrWhiteSpace(s)));
            stack.Children.Add(T(line, headerColor, compact ? 10 : 12));
        }
        var hasData = section.MeterList.Any(m => m.HasUsageBar || m.ResetsAtEpochMs != null);
        if (hasData)
        {
            foreach (var meter in section.MeterList)
                stack.Children.Add(MeterBlock(meter, nowMs, compact, stale.IsStale));
        }
        var manuals = section.ManualResets?.DisplayLine();
        if (!string.IsNullOrWhiteSpace(manuals))
            stack.Children.Add(T(manuals, Theme.TextBrush, compact ? 11 : 13));
        var pacing = PacingPlanner.ForSection(section, nowMs, percentPerWindow);
        if (pacing is not null)
        {
            stack.Children.Add(T(pacing.Line, Theme.PaceBrush(pacing.Urgency), compact ? 11 : 13, FontWeights.Medium));
            foreach (var warning in pacing.ManualList.Select(m => m.Warning).Where(w => w is not null))
                stack.Children.Add(T(warning!, Theme.ImminentBrush, compact ? 10 : 12));
        }

        if (section.Status is ProviderStatus.Unsigned or ProviderStatus.NeedsSignIn)
        {
            if (!hasData && !compact)
                stack.Children.Add(T(section.Error ?? "Sign in. The app never sees your password.", Theme.MutedBrush, 13));
        }
        else if (section.Status == ProviderStatus.Error && !hasData)
        {
            stack.Children.Add(T(section.Error ?? "Could not load usage.", Theme.BarWarnBrush, compact ? 11 : 13));
        }
        else if (section.Status == ProviderStatus.Ok && !string.IsNullOrWhiteSpace(section.Error) && !stale.IsStale && !compact)
        {
            stack.Children.Add(T(section.Error!, Theme.BarWarnBrush, 12));
        }

        var buttons = new WrapPanel { Margin = new Thickness(0, compact ? 6 : 8, 0, 0) };
        if (AuthCopy.ShowSignIn(section))
            buttons.Children.Add(Primary(AuthCopy.SignInButton(section.Status), (_, _) => onSignIn(section)));
        if (AuthCopy.ShowRetry(section) && onRefresh is not null)
            buttons.Children.Add(Ghost("Retry", (_, _) => onRefresh()));
        if (buttons.Children.Count > 0) stack.Children.Add(buttons);

        var menu = new ContextMenu();
        if (AuthCopy.ShowSignIn(section))
            menu.Items.Add(Menu(AuthCopy.SignInButton(section.Status), () => onSignIn(section)));
        if (onRefresh is not null)
            menu.Items.Add(Menu("Refresh", onRefresh));
        if (AuthCopy.ShowSignOut(section.Status) && onSignOut is not null)
            menu.Items.Add(Menu(AuthCopy.SignOutMenuItem(section.Id), () => onSignOut(section)));
        if (menu.Items.Count > 0) card.ContextMenu = menu;

        card.Child = stack;
        return card;
    }

    public static string LastUpdated(long fetchedAt) =>
        fetchedAt <= 0 ? "Last updated —" : $"Last updated {NyTimeAgo.Format(fetchedAt, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())}";
}

/// <summary>Windows 11-style caption buttons (min / max-restore / close) using Segoe MDL2 glyphs.</summary>
internal sealed class CaptionBar : StackPanel
{
    private const string GlyphMin = "\uE921";
    private const string GlyphMax = "\uE922";
    private const string GlyphRestore = "\uE923";
    private const string GlyphClose = "\uE8BB";
    private readonly Button _max;

    public event Action? Minimize;
    public event Action? Maximize;
    public event Action? CloseApp;

    public CaptionBar()
    {
        Orientation = Orientation.Horizontal;
        Height = 32;
        Background = Brushes.Transparent;
        IsHitTestVisible = true;
        Children.Add(CaptionButton(GlyphMin, "Minimize", Theme.CaptionHoverBrush, Theme.TextBrush, () => Minimize?.Invoke()));
        _max = CaptionButton(GlyphMax, "Maximize", Theme.CaptionHoverBrush, Theme.TextBrush, () => Maximize?.Invoke());
        Children.Add(_max);
        Children.Add(CaptionButton(GlyphClose, "Exit", Theme.CloseHoverBrush, Brushes.White, () => CloseApp?.Invoke()));
    }

    public void SetRestorable(bool restorable)
    {
        if (_max.Content is TextBlock glyph)
            glyph.Text = restorable ? GlyphRestore : GlyphMax;
        _max.ToolTip = restorable ? "Restore" : "Maximize";
    }

    private static Button CaptionButton(string glyph, string tip, Brush hoverBg, Brush hoverFg, Action click)
    {
        var icon = new TextBlock
        {
            Text = glyph,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 10,
            Foreground = Theme.TextBrush,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        var button = new Button
        {
            Width = 46,
            Height = 32,
            Padding = new Thickness(0),
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            BorderBrush = Brushes.Transparent,
            Cursor = Cursors.Arrow,
            ToolTip = tip,
            Content = icon,
            Focusable = false
        };
        var border = new FrameworkElementFactory(typeof(Border));
        border.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(BackgroundProperty));
        var presenter = new FrameworkElementFactory(typeof(ContentPresenter));
        presenter.SetValue(ContentPresenter.HorizontalAlignmentProperty, HorizontalAlignment.Center);
        presenter.SetValue(ContentPresenter.VerticalAlignmentProperty, VerticalAlignment.Center);
        border.AppendChild(presenter);
        button.Template = new ControlTemplate(typeof(Button)) { VisualTree = border };
        button.MouseEnter += (_, _) =>
        {
            button.Background = hoverBg;
            icon.Foreground = hoverFg;
        };
        button.MouseLeave += (_, _) =>
        {
            button.Background = Brushes.Transparent;
            icon.Foreground = Theme.TextBrush;
        };
        button.Click += (_, e) =>
        {
            e.Handled = true;
            click();
        };
        return button;
    }
}

internal sealed class MiniPanelWindow : Window
{
    private readonly AppHost _host;
    private readonly StackPanel _body;
    private readonly TextBlock _countdown;
    private readonly CaptionBar _caption = new();
    private readonly MenuItem _keepOnTop;
    private readonly Grid _titleStrip;
    private readonly Grid _inner;
    private readonly ScaleTransform _scaleTx = new(1, 1);
    private readonly CaptionGestureTracker _gestures = new();
    private readonly Button _pinButton;
    private readonly TextBlock _feedback;
    private readonly DispatcherTimer _feedbackHide = new() { Interval = TimeSpan.FromMilliseconds(1600) };
    private bool _applyingPlacement;
    private bool _captionLive;
    private double _currentScale = 1;

    public MiniPanelWindow(AppHost host)
    {
        _host = host;
        Title = "Usage";
        Icon = AppBranding.ImageSource;
        MinWidth = MiniChrome.MinWidth;
        MinHeight = MiniChrome.MinHeight;
        Width = host.Settings.MiniWidth > 0 ? host.Settings.MiniWidth : MiniChrome.DefaultWidth;
        Height = host.Settings.MiniHeight > 0 ? host.Settings.MiniHeight : MiniChrome.DefaultHeight;
        SizeToContent = SizeToContent.Manual;
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        ResizeMode = ResizeMode.CanResize;
        ShowInTaskbar = false;
        Topmost = host.Settings.AlwaysOnTop;
        Left = host.Settings.MiniLeft;
        Top = host.Settings.MiniTop;

        var chrome = new Border
        {
            Background = Theme.ChromeBrush,
            CornerRadius = new CornerRadius(12),
            BorderBrush = Theme.OutlineBrush,
            BorderThickness = new Thickness(1),
            ClipToBounds = true
        };
        _inner = new Grid
        {
            Background = Brushes.Transparent,
            LayoutTransform = _scaleTx,
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Top
        };
        _inner.RowDefinitions.Add(new RowDefinition { Height = new GridLength(MiniChrome.CaptionHeight) });
        _inner.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        _inner.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        _titleStrip = new Grid { Background = Theme.SurfaceBrush };
        _pinButton = Ui.GlyphButton("\uE840", "Always on top", () => ToggleAlwaysOnTop("pin-button"), MiniChrome.PinWidth);
        _pinButton.HorizontalAlignment = HorizontalAlignment.Left;
        _pinButton.VerticalAlignment = VerticalAlignment.Center;
        var gear = Ui.GlyphButton("\uE713", "Settings", () => OnDemandSettingsWindow.Show(host.Settings, this), MiniChrome.GearWidth);
        gear.HorizontalAlignment = HorizontalAlignment.Right;
        gear.VerticalAlignment = VerticalAlignment.Center;
        var titleLabel = Ui.T("Usage", Theme.TextBrush, 13, FontWeights.SemiBold);
        titleLabel.VerticalAlignment = VerticalAlignment.Center;
        titleLabel.Margin = new Thickness(MiniChrome.PinWidth, 0, MiniChrome.CaptionButtonsWidth + MiniChrome.GearWidth, 0);
        titleLabel.IsHitTestVisible = false;
        _feedback = Ui.T("", Theme.BrightBrush, 11, FontWeights.SemiBold);
        _feedback.HorizontalAlignment = HorizontalAlignment.Center;
        _feedback.VerticalAlignment = VerticalAlignment.Center;
        _feedback.IsHitTestVisible = false;
        _feedback.Opacity = 0;
        _caption.Opacity = 0;
        _caption.IsHitTestVisible = false;
        _caption.HorizontalAlignment = HorizontalAlignment.Right;
        _caption.VerticalAlignment = VerticalAlignment.Center;
        _caption.Margin = new Thickness(0, 0, MiniChrome.GearWidth, 0);
        _caption.Minimize += () => _host.HideMiniToTray();
        _caption.Maximize += () => _host.ToggleExpandedFromMini();
        _caption.CloseApp += () => _host.ExitApp();
        _titleStrip.Children.Add(titleLabel);
        _titleStrip.Children.Add(_pinButton);
        _titleStrip.Children.Add(_feedback);
        _titleStrip.Children.Add(_caption);
        _titleStrip.Children.Add(gear);
        _keepOnTop = new MenuItem { Header = "Keep on top", IsCheckable = true, IsChecked = host.Settings.AlwaysOnTop };
        _keepOnTop.Click += (_, _) => _host.SetAlwaysOnTop(_keepOnTop.IsChecked == true);
        _titleStrip.ContextMenu = new ContextMenu { Items = { _keepOnTop } };
        PreviewMouseLeftButtonDown += StripDown;
        PreviewMouseMove += StripMove;
        PreviewMouseLeftButtonUp += StripUp;

        _body = new StackPanel { Margin = new Thickness(10, 6, 10, 2) };
        _countdown = Ui.T("next 15:00", Theme.MutedBrush, 11);
        _countdown.Opacity = 0.7;
        _countdown.Margin = new Thickness(10, 0, 10, 6);

        Grid.SetRow(_titleStrip, 0);
        Grid.SetRow(_body, 1);
        Grid.SetRow(_countdown, 2);
        _inner.Children.Add(_titleStrip);
        _inner.Children.Add(_body);
        _inner.Children.Add(_countdown);
        chrome.Child = _inner;
        chrome.MouseEnter += (_, _) => FadeCaption(true);
        chrome.MouseLeave += (_, _) => FadeCaption(false);
        Content = chrome;
        _feedbackHide.Tick += (_, _) =>
        {
            _feedbackHide.Stop();
            _feedback.BeginAnimation(OpacityProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(250)));
            _titleStrip.Background = Theme.SurfaceBrush;
        };
        SyncKeepOnTop(host.Settings.AlwaysOnTop);

        SourceInitialized += (_, _) =>
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            NativeWindow.EnableThickFrame(hwnd);
            HwndSource.FromHwnd(hwnd)?.AddHook(WndProc);
            ApplyClampedPlacement();
            ApplyScale();
        };
        LocationChanged += (_, _) => PersistPlacement();
        SizeChanged += (_, _) =>
        {
            ApplyScale();
            PersistPlacement();
        };
        Closing += (_, e) =>
        {
            if (_host.IsExiting) return;
            e.Cancel = true;
            _host.ExitApp();
        };
    }

    public void SetExpanded(bool expanded) => _caption.SetRestorable(expanded);

    public void SyncKeepOnTop(bool value)
    {
        Topmost = value;
        if (_keepOnTop.IsChecked != value) _keepOnTop.IsChecked = value;
        if (_pinButton.Content is TextBlock glyph)
        {
            glyph.Foreground = value ? Theme.BrightBrush : Theme.MutedBrush;
            glyph.Opacity = value ? 1 : 0.55;
        }
        _pinButton.ToolTip = value ? "Always on top: On" : "Always on top: Off";
        WidgetLog.Write($"sync keep-on-top value={value} window.Topmost={Topmost}");
    }

    private void FadeCaption(bool show)
    {
        _captionLive = show;
        _caption.BeginAnimation(OpacityProperty, new DoubleAnimation(show ? 1 : 0, TimeSpan.FromMilliseconds(150)));
        _caption.IsHitTestVisible = show;
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == NativeWindow.WmNcLButtonDown)
            WidgetLog.Write($"WM_NCLBUTTONDOWN ht={wParam.ToInt32()} (click landed in non-client)");
        if (msg == NativeWindow.WmLButtonDown)
            WidgetLog.Write("WM_LBUTTONDOWN (click landed in client)");
        if (msg == NativeWindow.WmNcCalcSize && wParam != IntPtr.Zero)
        {
            handled = true;
            return IntPtr.Zero;
        }
        if (msg == NativeWindow.WmNcHitTest)
        {
            var local = PointFromScreen(NativeWindow.ScreenPoint(lParam));
            handled = true;
            return (IntPtr)NativeWindow.ToHt(MiniChrome.Hit(local.X, local.Y, ActualWidth, ActualHeight, _captionLive, _currentScale));
        }
        if (msg == NativeWindow.WmGetMinMaxInfo)
        {
            NativeWindow.ApplyMinMax(lParam, this, NativeWindow.WorkAreaDip(this));
            handled = true;
            return IntPtr.Zero;
        }
        if (msg == NativeWindow.WmExitSizeMove)
            ApplyClampedPlacement();
        return IntPtr.Zero;
    }

    private void ShowTitleMenu()
    {
        _keepOnTop.IsChecked = _host.Settings.AlwaysOnTop;
        var menu = _titleStrip.ContextMenu;
        if (menu is null) return;
        menu.PlacementTarget = _titleStrip;
        menu.Placement = PlacementMode.MousePoint;
        menu.IsOpen = true;
    }

    private void ApplyClampedPlacement()
    {
        if (_applyingPlacement) return;
        _applyingPlacement = true;
        try
        {
            var clamped = MiniChrome.Clamp(new MiniRect(Left, Top, Width, Height), NativeWindow.WorkAreaDip(this));
            Width = clamped.Width;
            Height = clamped.Height;
            Left = clamped.Left;
            Top = clamped.Top;
        }
        finally { _applyingPlacement = false; }
        PersistPlacement();
    }

    private void PersistPlacement()
    {
        if (_applyingPlacement) return;
        _host.Settings.SaveMiniPlacement(new MiniRect(Left, Top, Width, Height));
    }

    private void ApplyScale()
    {
        if (ActualWidth <= 0 || ActualHeight <= 0) return;
        var designWidth = MiniChrome.DefaultWidth;
        var measured = MiniChrome.ContentHeight(MeasureBodyAndFooter(designWidth));
        var designHeight = MiniChrome.DesignHeight(measured);
        _currentScale = MiniChrome.UniformScale(ActualWidth, ActualHeight, designWidth, designHeight);
        var layout = MiniChrome.LayoutSize(ActualWidth, ActualHeight, _currentScale);
        _scaleTx.ScaleX = _currentScale;
        _scaleTx.ScaleY = _currentScale;
        _inner.Width = layout.Width;
        _inner.Height = layout.Height;
    }

    /// <summary>
    /// Natural height of the card stack + countdown at <paramref name="innerWidth"/>,
    /// excluding the caption (added by <see cref="MiniChrome.ContentHeight"/>).
    /// Always measured at the design width so scale cannot oscillate.
    /// </summary>
    private double MeasureBodyAndFooter(double innerWidth)
    {
        var bodyAvail = Math.Max(1, innerWidth - _body.Margin.Left - _body.Margin.Right);
        _body.Measure(new Size(bodyAvail, double.PositiveInfinity));
        var countAvail = Math.Max(1, innerWidth - _countdown.Margin.Left - _countdown.Margin.Right);
        _countdown.Measure(new Size(countAvail, double.PositiveInfinity));
        return _body.DesiredSize.Height + _body.Margin.Top + _body.Margin.Bottom
             + _countdown.DesiredSize.Height + _countdown.Margin.Top + _countdown.Margin.Bottom;
    }

    private (double ThX, double ThY, int DoubleMs, double DoubleX, double DoubleY) GestureParams()
    {
        var thX = SystemParameters.MinimumHorizontalDragDistance;
        var thY = SystemParameters.MinimumVerticalDragDistance;
        var doubleMs = Forms.SystemInformation.DoubleClickTime;
        var slop = Forms.SystemInformation.DoubleClickSize;
        var src = PresentationSource.FromVisual(this);
        var toDip = src?.CompositionTarget?.TransformFromDevice ?? Matrix.Identity;
        var dip = toDip.Transform(new Point(slop.Width, slop.Height));
        return (thX, thY, doubleMs, Math.Max(1, dip.X), Math.Max(1, dip.Y));
    }

    private MiniHit HitAt(Point windowPoint) =>
        MiniChrome.Hit(windowPoint.X, windowPoint.Y, ActualWidth, ActualHeight, _captionLive, _currentScale);

    private bool OnCaption(Point windowPoint) => HitAt(windowPoint) == MiniHit.Caption;

    private static bool IsChromeButton(object? source)
    {
        for (var d = source as DependencyObject; d is not null; d = VisualTreeHelper.GetParent(d))
        {
            if (d is Button) return true;
        }
        return false;
    }

    private void StripDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton != MouseButton.Left) return;
        var p = e.GetPosition(this);
        var hit = HitAt(p);
        var onButton = IsChromeButton(e.OriginalSource);
        WidgetLog.Write($"strip down pos={p.X:0.0},{p.Y:0.0} hit={hit} scale={_currentScale:0.00} captionLive={_captionLive} button={onButton} clickCount={e.ClickCount} src={e.OriginalSource?.GetType().Name}");
        if (onButton)
        {
            _gestures.Cancel();
            return;
        }
        var gp = GestureParams();
        _gestures.Down(p.X, p.Y, Environment.TickCount64, hit == MiniHit.Caption, e.ClickCount, gp.DoubleMs, gp.DoubleX, gp.DoubleY);
        if (_gestures.IsArmed)
            CaptureMouse();
    }

    private void StripMove(object sender, MouseEventArgs e)
    {
        if (!_gestures.IsArmed || e.LeftButton != MouseButtonState.Pressed) return;
        var p = e.GetPosition(this);
        var gp = GestureParams();
        var gesture = _gestures.Move(p.X, p.Y, gp.ThX, gp.ThY);
        if (gesture != CaptionGesture.DragStart) return;
        WidgetLog.Write($"strip drag-start pos={p.X:0.0},{p.Y:0.0} threshold={gp.ThX:0.0},{gp.ThY:0.0}");
        if (IsMouseCaptured) ReleaseMouseCapture();
        try { DragMove(); }
        catch (InvalidOperationException) { /* mouse not down */ }
        _gestures.Up(p.X, p.Y, Environment.TickCount64, gp.ThX, gp.ThY);
    }

    private void StripUp(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton != MouseButton.Left) return;
        var p = e.GetPosition(this);
        var gp = GestureParams();
        var armed = _gestures.IsArmed;
        if (IsMouseCaptured) ReleaseMouseCapture();
        var gesture = armed ? _gestures.Up(p.X, p.Y, Environment.TickCount64, gp.ThX, gp.ThY) : CaptionGesture.None;
        WidgetLog.Write($"strip up pos={p.X:0.0},{p.Y:0.0} armed={armed} gesture={gesture} topmost={Topmost}");
        if (gesture == CaptionGesture.Click)
            ToggleAlwaysOnTop("title-click");
    }

    private void ToggleAlwaysOnTop(string reason)
    {
        var next = !_host.Settings.AlwaysOnTop;
        WidgetLog.Write($"toggle always-on-top reason={reason} {_host.Settings.AlwaysOnTop} -> {next}");
        _host.SetAlwaysOnTop(next);
        ShowOnTopFeedback();
    }

    private void ShowOnTopFeedback()
    {
        var on = _host.Settings.AlwaysOnTop;
        SyncKeepOnTop(on);
        _feedback.Text = on ? "Always on top: On" : "Always on top: Off";
        _feedback.BeginAnimation(OpacityProperty, null);
        _feedback.Opacity = 1;
        _titleStrip.Background = on ? Theme.CaptionHoverBrush : Theme.SurfaceBrush;
        _feedbackHide.Stop();
        _feedbackHide.Start();
        WidgetLog.Write($"feedback shown alwaysOnTop={on} window.Topmost={Topmost}");
    }

    public void Bind(UsageSnapshot snapshot, long nextRefreshAt, long nowMs)
    {
        if (Topmost != _host.Settings.AlwaysOnTop)
            Topmost = _host.Settings.AlwaysOnTop;
        _countdown.Text = RefreshSchedule.FormatCountdown(RefreshSchedule.RemainingMs(nextRefreshAt, nowMs));
        _body.Children.Clear();
        var stale = snapshot.HealthList.Select(h => h.StaleView(nowMs)).Where(v => v.IsStale).ToList();
        if (stale.Count > 0)
        {
            var worst = stale.Any(s => s.Severity == StaleSeverity.Red) ? StaleSeverity.Red : StaleSeverity.Amber;
            _body.Children.Add(new Border
            {
                Background = Theme.StaleBg(worst),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(8),
                Margin = new Thickness(0, 0, 0, 8),
                Child = Ui.T($"Stale · {stale[0].ShortReason}", Theme.StaleBrush(worst), 11)
            });
        }
        foreach (var section in OnDemandDisplay.Apply(UsageSections.From(snapshot, nowMs), _host.Settings.ShowCursorOnDemand, _host.Settings.ShowGrokOnDemand))
            _body.Children.Add(Ui.SectionCard(section, nowMs, _host.Settings.PercentPerFiveHourWindow, compact: true, _host.OpenSignIn, _host.SignOutSection, () => _ = _host.RefreshNow()));
        if (_body.Children.Count > 0 && _body.Children[^1] is FrameworkElement last)
            last.Margin = new Thickness(last.Margin.Left, last.Margin.Top, last.Margin.Right, 0);
        ApplyScale();
    }
}

internal sealed class DashboardWindow : Window
{
    private readonly AppHost _host;
    private readonly StackPanel _body;
    private readonly TextBlock _status;
    private CheckBox? _alwaysOnTopCheck;
    private CheckBox? _showCursorOnDemand;
    private CheckBox? _showGrokOnDemand;

    public DashboardWindow(AppHost host)
    {
        _host = host;
        Title = "Usage Widget";
        Icon = AppBranding.ImageSource;
        Width = 440;
        Height = 760;
        Background = Theme.ChromeBrush;
        MinWidth = 360;
        MinHeight = 480;
        ShowInTaskbar = true;
        Closing += (_, e) =>
        {
            if (_host.IsExiting) return;
            e.Cancel = true;
            _host.ExitApp();
        };
        StateChanged += (_, _) =>
        {
            if (_host.IsExiting) return;
            if (WindowState == WindowState.Minimized)
            {
                WindowState = WindowState.Normal;
                _host.HideDashboardToTray();
            }
        };

        var root = new DockPanel { Margin = new Thickness(16) };
        var header = new DockPanel { LastChildFill = true, Margin = new Thickness(0, 0, 0, 8) };
        var refresh = Ui.Primary("Refresh", (_, _) => _ = _host.RefreshNow());
        DockPanel.SetDock(refresh, Dock.Right);
        header.Children.Add(refresh);
        var gear = Ui.GlyphButton("\uE713", "Settings", () => OnDemandSettingsWindow.Show(_host.Settings, this), 36);
        DockPanel.SetDock(gear, Dock.Right);
        header.Children.Add(gear);
        header.Children.Add(Ui.T("Usage", Theme.TextBrush, 22, FontWeights.SemiBold));
        DockPanel.SetDock(header, Dock.Top);
        _status = Ui.T("", Theme.MutedBrush, 12);
        DockPanel.SetDock(_status, Dock.Top);

        var settings = BuildSettings();
        DockPanel.SetDock(settings, Dock.Bottom);

        _body = new StackPanel();
        var scroll = new ScrollViewer { Content = _body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        root.Children.Add(header);
        root.Children.Add(_status);
        root.Children.Add(settings);
        root.Children.Add(scroll);
        Content = root;
    }

    public void Bind(UsageSnapshot snapshot, long nextRefreshAt, long nowMs, bool refreshing)
    {
        SyncOnDemandChecks();
        _status.Text = refreshing
            ? "Refreshing…"
            : $"{Ui.LastUpdated(snapshot.FetchedAtEpochMs)}  ·  {RefreshSchedule.FormatCountdown(RefreshSchedule.RemainingMs(nextRefreshAt, nowMs))}";
        _body.Children.Clear();
        var sections = UsageSections.From(snapshot, nowMs);
        if (sections.Any(s => s.StaleValue.IsStale))
        {
            var worst = sections.Where(s => s.StaleValue.IsStale).MaxBy(s => s.StaleValue.Severity == StaleSeverity.Red ? 1 : 0)!;
            _body.Children.Add(new Border
            {
                Background = Theme.StaleBg(worst.StaleValue.Severity),
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(10),
                Margin = new Thickness(0, 0, 0, 10),
                Child = Ui.T($"Stale data on {worst.Id.Title()}" + (worst.StaleValue.ShortReason is { } r ? $" · {r}" : ""), Theme.StaleBrush(worst.StaleValue.Severity), 13)
            });
        }
        _body.Children.Add(Ui.T("Background refresh every 15 minutes. All times America/New_York.", Theme.MutedBrush, 11));
        foreach (var section in OnDemandDisplay.Apply(sections, _host.Settings.ShowCursorOnDemand, _host.Settings.ShowGrokOnDemand))
            _body.Children.Add(Ui.SectionCard(section, nowMs, _host.Settings.PercentPerFiveHourWindow, compact: false, _host.OpenSignIn, _host.SignOutSection, () => _ = _host.RefreshNow()));
        _body.Children.Add(Ui.T("Numbers come from each site’s own usage page using your sign-in cookie. Endpoints are undocumented and can break.", Theme.MutedBrush, 11));
    }

    private FrameworkElement BuildSettings()
    {
        var box = new Expander
        {
            Header = Ui.T("Settings & alerts", Theme.TextBrush, 14, FontWeights.SemiBold),
            Foreground = Theme.TextBrush,
            Margin = new Thickness(0, 8, 0, 0),
            IsExpanded = false
        };
        var s = _host.Settings;
        var stack = new StackPanel();
        var paceLabel = Ui.T($"Use this much of the weekly limit per 5-hour window: {s.PercentPerFiveHourWindow:0}%", Theme.MutedBrush, 12);
        stack.Children.Add(paceLabel);
        var slider = new Slider
        {
            Minimum = PacingPolicy.MinPercentPerWindow,
            Maximum = PacingPolicy.MaxPercentPerWindow,
            Value = s.PercentPerFiveHourWindow,
            TickFrequency = 1,
            IsSnapToTickEnabled = true,
            Margin = new Thickness(0, 4, 0, 8)
        };
        slider.ValueChanged += (_, e) =>
        {
            s.PercentPerFiveHourWindow = e.NewValue;
            paceLabel.Text = $"Use this much of the weekly limit per 5-hour window: {s.PercentPerFiveHourWindow:0}%";
        };
        stack.Children.Add(slider);
        stack.Children.Add(Ui.T("Show on demand usage", Theme.TextBrush, 13, FontWeights.SemiBold));
        stack.Children.Add(Ui.T("Hides that dollar-cap bar. Included quota bars stay.", Theme.MutedBrush, 11));
        _showCursorOnDemand = Ui.Check("Cursor", s.ShowCursorOnDemand, v => s.ShowCursorOnDemand = v);
        _showGrokOnDemand = Ui.Check("Grok Bot", s.ShowGrokOnDemand, v => s.ShowGrokOnDemand = v);
        stack.Children.Add(_showCursorOnDemand);
        stack.Children.Add(_showGrokOnDemand);
        _alwaysOnTopCheck = Ui.Check("Always on top (mini panel)", s.AlwaysOnTop, v => _host.SetAlwaysOnTop(v));
        stack.Children.Add(_alwaysOnTopCheck);
        stack.Children.Add(Ui.Check("Start with Windows", s.StartWithWindows || StartupManager.IsEnabled(), v => { s.StartWithWindows = v; StartupManager.SetEnabled(v); }));
        stack.Children.Add(Ui.Check("Notify when usage is back", s.NotifyUsageRestored, v => s.NotifyUsageRestored = v));
        stack.Children.Add(Ui.Check("Pacing reminders (start a session)", s.NotifyPacingReminders, v => s.NotifyPacingReminders = v));
        stack.Children.Add(Ui.Check("Missed-window / slack alerts", s.NotifyPacingMissed, v => s.NotifyPacingMissed = v));
        stack.Children.Add(Ui.Check("Stale-data notifications", s.FailureNotificationsEnabled, v => s.FailureNotificationsEnabled = v));
        stack.Children.Add(Ui.Check("Quiet hours (11 PM – 8 AM New York)", s.QuietHoursEnabled, v => s.QuietHoursEnabled = v));
        stack.Children.Add(Ui.T("Quota remaining notifications fire when remaining % crosses a step downward. One notice per step per billing window. Agents are never paused.", Theme.MutedBrush, 11));
        stack.Children.Add(Ui.Check("Quota remaining notifications", s.QuotaAlertsEnabled, v => s.QuotaAlertsEnabled = v));
        stack.Children.Add(Ui.Check("Grok Bot weekly quota", s.QuotaGrokWeeklyEnabled, v => s.QuotaGrokWeeklyEnabled = v));
        stack.Children.Add(Ui.Check("Cursor models quota", s.QuotaCursorModelsEnabled, v => s.QuotaCursorModelsEnabled = v));
        stack.Children.Add(Ui.Check("Codex 5-hour quota (off by default)", s.QuotaCodexFiveHourEnabled, v => s.QuotaCodexFiveHourEnabled = v));
        stack.Children.Add(Ui.Check("Codex weekly quota (off by default)", s.QuotaCodexWeeklyEnabled, v => s.QuotaCodexWeeklyEnabled = v));
        stack.Children.Add(Ui.T("Remaining % thresholds (comma-separated)", Theme.MutedBrush, 12));
        var thresholds = new TextBox
        {
            Text = s.QuotaThresholdsCsv,
            Margin = new Thickness(0, 4, 0, 8),
            Padding = new Thickness(6, 4, 6, 4),
            Background = Theme.SurfaceBrush,
            Foreground = Theme.TextBrush,
            BorderBrush = Theme.OutlineBrush,
            CaretBrush = Theme.TextBrush
        };
        thresholds.LostFocus += (_, _) => s.QuotaThresholdsCsv = thresholds.Text;
        stack.Children.Add(thresholds);
        stack.Children.Add(Ui.Ghost("Send test notification", (_, _) => _host.ShowTestNotification()));
        stack.Children.Add(Ui.Check("Write debug log (log.txt)", s.DebugLogging, v =>
        {
            s.DebugLogging = v;
            WidgetLog.Configure(v);
            WidgetLog.Write($"debug logging {(v ? "enabled" : "disabled")}");
        }));
        stack.Children.Add(Ui.T($"Sign-in cookies are stored encrypted for this Windows user in {Paths.DataDir}", Theme.MutedBrush, 11));
        box.Content = stack;
        return box;
    }

    public void SyncAlwaysOnTop(bool value)
    {
        if (_alwaysOnTopCheck is not null && _alwaysOnTopCheck.IsChecked != value)
            _alwaysOnTopCheck.IsChecked = value;
    }

    private void SyncOnDemandChecks()
    {
        if (_showCursorOnDemand is not null && _showCursorOnDemand.IsChecked != _host.Settings.ShowCursorOnDemand)
            _showCursorOnDemand.IsChecked = _host.Settings.ShowCursorOnDemand;
        if (_showGrokOnDemand is not null && _showGrokOnDemand.IsChecked != _host.Settings.ShowGrokOnDemand)
            _showGrokOnDemand.IsChecked = _host.Settings.ShowGrokOnDemand;
    }
}

internal sealed class OnDemandSettingsWindow : Window
{
    private static OnDemandSettingsWindow? _open;

    private OnDemandSettingsWindow(AppSettings settings)
    {
        Title = "Settings";
        Icon = AppBranding.ImageSource;
        WindowStyle = WindowStyle.ToolWindow;
        ResizeMode = ResizeMode.NoResize;
        SizeToContent = SizeToContent.WidthAndHeight;
        ShowInTaskbar = false;
        Background = Theme.ChromeBrush;
        var stack = new StackPanel { Margin = new Thickness(16), MinWidth = 240 };
        stack.Children.Add(Ui.T("Show on demand usage", Theme.TextBrush, 15, FontWeights.SemiBold));
        stack.Children.Add(Ui.T("Hides that dollar-cap bar. Included quota bars stay.", Theme.MutedBrush, 12));
        stack.Children.Add(Ui.Check("Cursor", settings.ShowCursorOnDemand, v => settings.ShowCursorOnDemand = v));
        stack.Children.Add(Ui.Check("Grok Bot", settings.ShowGrokOnDemand, v => settings.ShowGrokOnDemand = v));
        Content = stack;
    }

    public static void Show(AppSettings settings, Window? owner)
    {
        if (_open is { IsVisible: true })
        {
            _open.Activate();
            return;
        }
        var window = new OnDemandSettingsWindow(settings)
        {
            Owner = owner,
            WindowStartupLocation = owner is null ? WindowStartupLocation.CenterScreen : WindowStartupLocation.CenterOwner,
            Topmost = true
        };
        window.Closed += (_, _) => { if (_open == window) _open = null; };
        _open = window;
        window.Show();
    }
}
