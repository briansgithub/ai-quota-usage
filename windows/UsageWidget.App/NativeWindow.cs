using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using Forms = System.Windows.Forms;

namespace UsageWidget.App;

internal static class NativeWindow
{
    public const int WmNcHitTest = 0x0084;
    public const int WmNcCalcSize = 0x0083;
    public const int WmNcRButtonUp = 0x00A5;
    public const int WmNcLButtonDown = 0x00A1;
    public const int WmLButtonDown = 0x0201;
    public const int WmGetMinMaxInfo = 0x0024;
    public const int WmExitSizeMove = 0x0232;

    public const int HtClient = 1;
    public const int HtCaption = 2;
    public const int HtLeft = 10;
    public const int HtRight = 11;
    public const int HtTop = 12;
    public const int HtTopLeft = 13;
    public const int HtTopRight = 14;
    public const int HtBottom = 15;
    public const int HtBottomLeft = 16;
    public const int HtBottomRight = 17;

    private const int GwlStyle = -16;
    private const int GwlExStyle = -20;
    private const int WsThickFrame = 0x00040000;
    private const int WsExNoActivate = 0x08000000;
    private const int WsExToolWindow = 0x00000080;
    private const uint MonitorDefaultToNearest = 2;
    private const int MdEffectiveDpi = 0;

    public static int ToHt(MiniHit hit) => hit switch
    {
        MiniHit.Caption => HtClient,
        MiniHit.Left => HtLeft,
        MiniHit.Right => HtRight,
        MiniHit.Top => HtTop,
        MiniHit.Bottom => HtBottom,
        MiniHit.TopLeft => HtTopLeft,
        MiniHit.TopRight => HtTopRight,
        MiniHit.BottomLeft => HtBottomLeft,
        MiniHit.BottomRight => HtBottomRight,
        _ => HtClient
    };

    public static Point ScreenPoint(IntPtr lParam)
    {
        var xy = unchecked((int)lParam.ToInt64());
        return new Point(unchecked((short)xy), unchecked((short)(xy >> 16)));
    }

    public static void EnableThickFrame(IntPtr hwnd)
    {
        var style = GetWindowLongPtr(hwnd, GwlStyle);
        SetWindowLongPtr(hwnd, GwlStyle, style | (IntPtr)WsThickFrame);
    }

    public static void EnableToolPopup(IntPtr hwnd)
    {
        var ex = GetWindowLongPtr(hwnd, GwlExStyle);
        SetWindowLongPtr(hwnd, GwlExStyle, ex | (IntPtr)(WsExNoActivate | WsExToolWindow));
    }

    public static MiniRect WorkAreaDip(Window window)
    {
        var hwnd = new WindowInteropHelper(window).EnsureHandle();
        return WorkAreaDip(Forms.Screen.FromHandle(hwnd), window);
    }

    public static MiniRect WorkAreaDipFromScreenPoint(System.Drawing.Point devicePx, Window dpiRef) =>
        WorkAreaDip(Forms.Screen.FromPoint(devicePx), dpiRef);

    /// <summary>Physical screen pixels from GetCursorPos, same space as Shell_NotifyIconGetRect.</summary>
    public static bool TryCursorScreenPx(out int x, out int y)
    {
        if (GetCursorPos(out var point))
        {
            x = point.X;
            y = point.Y;
            return true;
        }
        x = 0;
        y = 0;
        return false;
    }

    public readonly record struct MonitorDip(MiniRect Bounds, MiniRect Work, uint DpiX, uint DpiY);

    public static bool TryMonitorFromDeviceRect(int left, int top, int right, int bottom, out MonitorDip monitor)
    {
        var raw = new NativeRect { Left = left, Top = top, Right = right, Bottom = bottom };
        return TryMonitor(MonitorFromRect(ref raw, MonitorDefaultToNearest), out monitor);
    }

    public static bool TryMonitorFromDevicePoint(int x, int y, out MonitorDip monitor) =>
        TryMonitor(MonitorFromPoint(new NativePoint(x, y), MonitorDefaultToNearest), out monitor);

    public static MiniRect DeviceRectToDip(int left, int top, int right, int bottom, uint dpiX, uint dpiY)
    {
        var sx = 96.0 / Math.Max(1, dpiX);
        var sy = 96.0 / Math.Max(1, dpiY);
        var l = left * sx;
        var t = top * sy;
        var r = right * sx;
        var b = bottom * sy;
        return new MiniRect(l, t, Math.Max(1, r - l), Math.Max(1, b - t));
    }

    public static bool TryWindowRectPx(IntPtr hwnd, out MiniRect rect)
    {
        rect = default;
        if (hwnd == IntPtr.Zero || !GetWindowRect(hwnd, out var raw)) return false;
        var width = raw.Right - raw.Left;
        var height = raw.Bottom - raw.Top;
        if (width <= 0 || height <= 0) return false;
        rect = new MiniRect(raw.Left, raw.Top, width, height);
        return true;
    }

    private static bool TryMonitor(IntPtr hmon, out MonitorDip monitor)
    {
        monitor = default;
        if (hmon == IntPtr.Zero) return false;
        var info = new MonitorInfo { cbSize = Marshal.SizeOf<MonitorInfo>() };
        if (!GetMonitorInfo(hmon, ref info)) return false;
        uint dpiX = 96, dpiY = 96;
        if (GetDpiForMonitor(hmon, MdEffectiveDpi, out var x, out var y) == 0 && x > 0 && y > 0)
        {
            dpiX = x;
            dpiY = y;
        }
        monitor = new MonitorDip(
            DeviceRectToDip(info.rcMonitor.Left, info.rcMonitor.Top, info.rcMonitor.Right, info.rcMonitor.Bottom, dpiX, dpiY),
            DeviceRectToDip(info.rcWork.Left, info.rcWork.Top, info.rcWork.Right, info.rcWork.Bottom, dpiX, dpiY),
            dpiX,
            dpiY);
        return true;
    }

    private static MiniRect WorkAreaDip(Forms.Screen screen, Window dpiRef)
    {
        var hwnd = new WindowInteropHelper(dpiRef).EnsureHandle();
        var src = (HwndSource?)PresentationSource.FromVisual(dpiRef) ?? HwndSource.FromHwnd(hwnd);
        var transform = src?.CompositionTarget?.TransformFromDevice ?? Matrix.Identity;
        var wa = screen.WorkingArea;
        var tl = transform.Transform(new Point(wa.Left, wa.Top));
        var br = transform.Transform(new Point(wa.Right, wa.Bottom));
        return new MiniRect(tl.X, tl.Y, Math.Max(1, br.X - tl.X), Math.Max(1, br.Y - tl.Y));
    }

    public static void ApplyMinMax(IntPtr lParam, Window window, MiniRect workAreaDip)
    {
        var info = Marshal.PtrToStructure<MinMaxInfo>(lParam);
        var src = (HwndSource?)PresentationSource.FromVisual(window) ?? HwndSource.FromHwnd(new WindowInteropHelper(window).EnsureHandle());
        var toDevice = src?.CompositionTarget?.TransformToDevice ?? Matrix.Identity;
        var min = toDevice.Transform(new Point(MiniChrome.MinWidth, MiniChrome.MinHeight));
        var max = toDevice.Transform(new Point(workAreaDip.Width, workAreaDip.Height));
        info.ptMinTrackSize = new NativePoint((int)Math.Ceiling(min.X), (int)Math.Ceiling(min.Y));
        info.ptMaxTrackSize = new NativePoint(Math.Max(1, (int)Math.Floor(max.X)), Math.Max(1, (int)Math.Floor(max.Y)));
        Marshal.StructureToPtr(info, lParam, true);
    }

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern IntPtr GetWindowLongPtr(IntPtr hWnd, int nIndex);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW")]
    private static extern IntPtr SetWindowLongPtr(IntPtr hWnd, int nIndex, IntPtr dwNewLong);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool DestroyIcon(IntPtr hIcon);

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out NativePoint lpPoint);

    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hWnd, out NativeRect lpRect);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromRect(ref NativeRect lprc, uint dwFlags);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromPoint(NativePoint pt, uint dwFlags);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MonitorInfo lpmi);

    [DllImport("shcore.dll")]
    private static extern int GetDpiForMonitor(IntPtr hmonitor, int dpiType, out uint dpiX, out uint dpiY);

    [StructLayout(LayoutKind.Sequential)]
    private struct NativePoint
    {
        public int X;
        public int Y;
        public NativePoint(int x, int y) { X = x; Y = y; }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct MonitorInfo
    {
        public int cbSize;
        public NativeRect rcMonitor;
        public NativeRect rcWork;
        public int dwFlags;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MinMaxInfo
    {
        public NativePoint ptReserved;
        public NativePoint ptMaxSize;
        public NativePoint ptMaxPosition;
        public NativePoint ptMinTrackSize;
        public NativePoint ptMaxTrackSize;
    }
}
