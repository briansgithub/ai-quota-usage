using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using UsageWidget;
using Forms = System.Windows.Forms;

namespace UsageWidget.App;

internal sealed class TrayGlanceWindow : Window
{
    private readonly StackPanel _body = new();

    public TrayGlanceWindow()
    {
        Title = "Usage";
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = System.Windows.Media.Brushes.Transparent;
        ShowInTaskbar = false;
        ShowActivated = false;
        Topmost = true;
        ResizeMode = ResizeMode.NoResize;
        SizeToContent = SizeToContent.WidthAndHeight;
        Focusable = false;
        var chrome = new Border
        {
            Background = Theme.ChromeBrush,
            BorderBrush = Theme.OutlineBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(8, 6, 8, 6)
        };
        chrome.Child = _body;
        Content = chrome;
        SizeChanged += (_, _) =>
        {
            if (IsVisible) PlaceIfNeeded(justShown: false);
        };
        SourceInitialized += (_, _) =>
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            NativeWindow.EnableToolPopup(hwnd);
        };
    }

    public void Bind(GlanceModel model)
    {
        _body.Children.Clear();
        if (model.IsEmpty)
        {
            _body.Children.Add(Ui.T(model.EmptyMessage ?? "No usage data", Theme.MutedBrush, 11));
            return;
        }
        string? currentGroup = null;
        foreach (var line in model.Lines)
        {
            if (line.GroupTitle is not null && line.GroupTitle != currentGroup)
            {
                var heading = Ui.T(line.GroupTitle, Theme.BrightBrush, 11, FontWeights.SemiBold);
                heading.Margin = new Thickness(0, currentGroup is null ? 0 : 5, 0, 3);
                _body.Children.Add(heading);
                currentGroup = line.GroupTitle;
            }
            var row = new DockPanel { LastChildFill = true, Margin = new Thickness(0, 0, 0, 3), MinWidth = 168 };
            var pct = Ui.T(line.UsedLabel, Theme.TextBrush, 11, FontWeights.SemiBold);
            DockPanel.SetDock(pct, Dock.Right);
            pct.Margin = new Thickness(8, 0, 0, 0);
            row.Children.Add(pct);
            var label = Ui.T(line.Label, Theme.MutedBrush, 11);
            label.Margin = new Thickness(0, 0, 0, 1);
            var stack = new StackPanel();
            stack.Children.Add(label);
            stack.Children.Add(Ui.UsageBar(
                line.UsedPercent,
                height: 4,
                expectedUsedPercent: line.IsCapBar ? null : line.ExpectedUsedPercent,
                capBar: line.IsCapBar));
            row.Children.Add(stack);
            _body.Children.Add(row);
        }
        if (_body.Children.Count > 0 && _body.Children[^1] is FrameworkElement last)
            last.Margin = new Thickness(0);
    }

    private double _placedWidth;
    private double _placedHeight;
    private bool _placed;
    private bool _anchoredToIcon;
    private bool _placing;

    public MiniRect BoundsPx()
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        if (NativeWindow.TryWindowRectPx(hwnd, out var rect)) return rect;
        return new MiniRect(Left, Top, Math.Max(1, ActualWidth), Math.Max(1, ActualHeight));
    }

    /// <summary>
    /// Place on show, and again only when the measured size changes or the last
    /// placement had to fall back to the cursor because the icon rect was missing.
    /// </summary>
    public void PlaceIfNeeded(bool justShown)
    {
        if (_placing) return;
        _placing = true;
        try
        {
            UpdateLayout();
            var width = ActualWidth > 1 ? ActualWidth : 180;
            var height = ActualHeight > 1 ? ActualHeight : 48;
            var sizeChanged = !_placed
                || Math.Abs(width - _placedWidth) > 0.5
                || Math.Abs(height - _placedHeight) > 0.5;
            if (!justShown && _anchoredToIcon && !sizeChanged) return;
            if (!TryAnchor(width, height, out var placed, out var anchored)) return;
            Left = placed.Left;
            Top = placed.Top;
            _placedWidth = width;
            _placedHeight = height;
            _placed = true;
            _anchoredToIcon = anchored;
        }
        finally
        {
            _placing = false;
        }
    }

    private bool TryAnchor(double width, double height, out MiniRect placed, out bool anchored)
    {
        placed = default;
        anchored = false;
        if (TrayNotify.TryGetIconRect(out var iconPx) &&
            NativeWindow.TryMonitorFromDeviceRect(iconPx.Left, iconPx.Top, iconPx.Right, iconPx.Bottom, out var iconMon))
        {
            var icon = NativeWindow.DeviceRectToDip(iconPx.Left, iconPx.Top, iconPx.Right, iconPx.Bottom, iconMon.DpiX, iconMon.DpiY);
            var edge = TrayGlancePlacement.EdgeOfIcon(iconMon.Work, icon);
            var along = edge is TrayTaskbarEdge.Left or TrayTaskbarEdge.Right
                ? icon.Top + icon.Height / 2
                : icon.Left + icon.Width / 2;
            placed = TrayGlancePlacement.Place(width, height, iconMon.Work, edge, along);
            anchored = true;
            return true;
        }

        var mouse = Forms.Control.MousePosition;
        if (!NativeWindow.TryMonitorFromDevicePoint(mouse.X, mouse.Y, out var cursorMon)) return false;
        var edgeFromWork = TrayGlancePlacement.EdgeFromInset(cursorMon.Bounds, cursorMon.Work);
        var scaleX = 96.0 / Math.Max(1, cursorMon.DpiX);
        var scaleY = 96.0 / Math.Max(1, cursorMon.DpiY);
        var alongCursor = edgeFromWork is TrayTaskbarEdge.Left or TrayTaskbarEdge.Right
            ? mouse.Y * scaleY
            : mouse.X * scaleX;
        placed = TrayGlancePlacement.Place(width, height, cursorMon.Work, edgeFromWork, alongCursor);
        return true;
    }
}
