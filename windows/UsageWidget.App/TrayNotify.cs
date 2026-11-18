using System.Reflection;
using System.Runtime.InteropServices;
using Forms = System.Windows.Forms;

namespace UsageWidget.App;

/// <summary>
/// Keeps NotifyIcon.Text as an accessible name only: NOTIFYICON_VERSION_4 without
/// NIF_SHOWTIP so Windows sends NIN_POPUPOPEN/CLOSE instead of the standard tooltip.
/// </summary>
internal static class TrayNotify
{
    public const string AccessibleName = "Usage Widget";
    private const int NimModify = 0x00000001;
    private const int NimSetVersion = 0x00000004;
    private const int NifTip = 0x00000004;
    private const int NotifyIconVersion4 = 4;
    private const int NinPopupOpen = 0x0406;
    private const int NinPopupClose = 0x0407;
    /// <summary>
    /// NotifyIcon's private callback. Confirmed in the installed
    /// System.Windows.Forms 8.0.21 NotifyIcon.WndProc, which compares the message to 0x800
    /// (WM_USER + 1024) before switching on lParam for mouse and balloon events.
    /// </summary>
    private const int WmTrayMouseMessage = 0x800;
    private static readonly IntPtr SubclassId = (IntPtr)0x55575347;

    private static SubclassProc? _proc;
    private static IntPtr _hwnd;
    private static IntPtr _iconHwnd;
    private static uint _iconId;
    private static Action? _popupOpen;
    private static Action? _popupClose;

    public static void Attach(Forms.NotifyIcon icon, Action popupOpen, Action popupClose)
    {
        _popupOpen = popupOpen;
        _popupClose = popupClose;
        icon.Text = string.Empty;
        SuppressStandardTip(icon);
        var hwnd = TryHandle(icon);
        if (hwnd == IntPtr.Zero) return;
        if (hwnd == _hwnd) return;
        // TryNative stored the icon hwnd/id, and Detach clears them. Put them back
        // so Shell_NotifyIconGetRect still works after the subclass is installed.
        var iconHwnd = _iconHwnd;
        var iconId = _iconId;
        Detach();
        _iconHwnd = iconHwnd;
        _iconId = iconId;
        _proc ??= WndProc;
        if (SetWindowSubclass(hwnd, _proc, SubclassId, IntPtr.Zero))
            _hwnd = hwnd;
    }

    public static void Detach()
    {
        if (_hwnd != IntPtr.Zero && _proc is not null)
            RemoveWindowSubclass(_hwnd, _proc, SubclassId);
        _hwnd = IntPtr.Zero;
        _iconHwnd = IntPtr.Zero;
        _iconId = 0;
    }

    public readonly record struct TrayIconRect(int Left, int Top, int Right, int Bottom);

    public static bool TryGetIconRect(out TrayIconRect rect)
    {
        rect = default;
        if (_iconHwnd == IntPtr.Zero) return false;
        var id = new NotifyIconIdentifier
        {
            cbSize = Marshal.SizeOf<NotifyIconIdentifier>(),
            hWnd = _iconHwnd,
            uID = _iconId
        };
        if (Shell_NotifyIconGetRect(ref id, out var raw) != 0) return false;
        var width = raw.Right - raw.Left;
        var height = raw.Bottom - raw.Top;
        // A tray icon is a few dozen pixels. A full-monitor rect would keep the popup up forever.
        if (width <= 0 || height <= 0 || width > 256 || height > 256) return false;
        rect = new TrayIconRect(raw.Left, raw.Top, raw.Right, raw.Bottom);
        return true;
    }

    public static void SuppressStandardTip(Forms.NotifyIcon icon)
    {
        if (!TryNative(icon, out var hwnd, out var id) || hwnd == IntPtr.Zero) return;
        var data = new NotifyIconData
        {
            cbSize = Marshal.SizeOf<NotifyIconData>(),
            hWnd = hwnd,
            uID = id,
            uFlags = NifTip,
            szTip = AccessibleName,
            uVersion = NotifyIconVersion4
        };
        Shell_NotifyIcon(NimSetVersion, ref data);
        data.uFlags = NifTip;
        data.szTip = AccessibleName;
        Shell_NotifyIcon(NimModify, ref data);
    }

    private static IntPtr WndProc(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam, IntPtr uIdSubclass, IntPtr dwRefData)
    {
        if (msg == WmTrayMouseMessage)
        {
            var evt = unchecked((int)(lParam.ToInt64() & 0xFFFF));
            if (evt == NinPopupOpen) _popupOpen?.Invoke();
            else if (evt == NinPopupClose) _popupClose?.Invoke();
        }
        return DefSubclassProc(hWnd, msg, wParam, lParam);
    }

    private static IntPtr TryHandle(Forms.NotifyIcon icon) =>
        TryNative(icon, out var hwnd, out _) ? hwnd : IntPtr.Zero;

    private static bool TryNative(Forms.NotifyIcon icon, out IntPtr hwnd, out int id)
    {
        hwnd = IntPtr.Zero;
        id = 0;
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;
        foreach (var name in new[] { "_window", "window" })
        {
            if (typeof(Forms.NotifyIcon).GetField(name, flags)?.GetValue(icon) is Forms.NativeWindow window && window.Handle != IntPtr.Zero)
            {
                hwnd = window.Handle;
                break;
            }
        }
        foreach (var name in new[] { "_id", "id" })
        {
            if (typeof(Forms.NotifyIcon).GetField(name, flags)?.GetValue(icon) is int value)
            {
                id = value;
                break;
            }
        }
        if (hwnd != IntPtr.Zero)
        {
            _iconHwnd = hwnd;
            _iconId = unchecked((uint)id);
        }
        return hwnd != IntPtr.Zero;
    }

    private delegate IntPtr SubclassProc(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam, IntPtr uIdSubclass, IntPtr dwRefData);

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern bool Shell_NotifyIcon(int dwMessage, ref NotifyIconData lpData);

    [DllImport("shell32.dll", ExactSpelling = true)]
    private static extern int Shell_NotifyIconGetRect(ref NotifyIconIdentifier identifier, out NativeRect iconLocation);

    [DllImport("comctl32.dll", ExactSpelling = true)]
    private static extern bool SetWindowSubclass(IntPtr hWnd, SubclassProc pfnSubclass, IntPtr uIdSubclass, IntPtr dwRefData);

    [DllImport("comctl32.dll", ExactSpelling = true)]
    private static extern bool RemoveWindowSubclass(IntPtr hWnd, SubclassProc pfnSubclass, IntPtr uIdSubclass);

    [DllImport("comctl32.dll", ExactSpelling = true)]
    private static extern IntPtr DefSubclassProc(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);

    /// <summary>
    /// x64 layout of NOTIFYICONIDENTIFIER: HWND is 8-aligned, so cbSize is followed by
    /// 4 bytes of padding. Size is 40, matching the Windows SDK struct.
    /// </summary>
    [StructLayout(LayoutKind.Explicit, Size = 40)]
    private struct NotifyIconIdentifier
    {
        [FieldOffset(0)] public int cbSize;
        [FieldOffset(8)] public IntPtr hWnd;
        [FieldOffset(16)] public uint uID;
        [FieldOffset(20)] public Guid guidItem;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NotifyIconData
    {
        public int cbSize;
        public IntPtr hWnd;
        public int uID;
        public int uFlags;
        public int uCallbackMessage;
        public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string szTip;
        public int dwState;
        public int dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string szInfo;
        public int uVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
        public string szInfoTitle;
        public int dwInfoFlags;
        public Guid guidItem;
        public IntPtr hBalloonIcon;
    }
}
