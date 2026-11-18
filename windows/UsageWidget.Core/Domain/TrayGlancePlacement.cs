namespace UsageWidget;

public enum TrayTaskbarEdge
{
    Bottom,
    Top,
    Left,
    Right
}

/// <summary>
/// Pins the tray glance popup to the work-area side of the taskbar. The anchor is
/// the icon center along that edge (or the cursor's coordinate along the edge when
/// the icon rect is not ready). The cursor's other axis never floats or flips the window.
/// </summary>
public static class TrayGlancePlacement
{
    public const double EdgePaddingDip = 10;

    public static MiniRect Place(
        double width,
        double height,
        MiniRect work,
        TrayTaskbarEdge edge,
        double anchor,
        double padding = EdgePaddingDip)
    {
        width = Math.Max(1, width);
        height = Math.Max(1, height);
        padding = Math.Max(0, padding);
        double left;
        double top;
        switch (edge)
        {
            case TrayTaskbarEdge.Top:
                top = work.Top + padding;
                left = anchor - width / 2;
                break;
            case TrayTaskbarEdge.Left:
                left = work.Left + padding;
                top = anchor - height / 2;
                break;
            case TrayTaskbarEdge.Right:
                left = work.Right - padding - width;
                top = anchor - height / 2;
                break;
            default:
                top = work.Bottom - padding - height;
                left = anchor - width / 2;
                break;
        }
        var minLeft = work.Left + padding;
        var maxLeft = Math.Max(minLeft, work.Right - padding - width);
        var minTop = work.Top + padding;
        var maxTop = Math.Max(minTop, work.Bottom - padding - height);
        left = Math.Clamp(left, minLeft, maxLeft);
        top = Math.Clamp(top, minTop, maxTop);
        return new MiniRect(left, top, width, height);
    }

    public static TrayTaskbarEdge EdgeOfIcon(MiniRect work, MiniRect icon)
    {
        var cx = icon.Left + icon.Width / 2;
        var cy = icon.Top + icon.Height / 2;
        if (cy >= work.Bottom) return TrayTaskbarEdge.Bottom;
        if (cy <= work.Top) return TrayTaskbarEdge.Top;
        if (cx <= work.Left) return TrayTaskbarEdge.Left;
        if (cx >= work.Right) return TrayTaskbarEdge.Right;
        var bottom = work.Bottom - cy;
        var top = cy - work.Top;
        var left = cx - work.Left;
        var right = work.Right - cx;
        if (bottom <= top && bottom <= left && bottom <= right) return TrayTaskbarEdge.Bottom;
        if (top <= left && top <= right) return TrayTaskbarEdge.Top;
        if (left <= right) return TrayTaskbarEdge.Left;
        return TrayTaskbarEdge.Right;
    }

    public static TrayTaskbarEdge EdgeFromInset(MiniRect monitor, MiniRect work)
    {
        var top = Math.Max(0, work.Top - monitor.Top);
        var left = Math.Max(0, work.Left - monitor.Left);
        var bottom = Math.Max(0, monitor.Bottom - work.Bottom);
        var right = Math.Max(0, monitor.Right - work.Right);
        if (bottom >= top && bottom >= left && bottom >= right && bottom > 0) return TrayTaskbarEdge.Bottom;
        if (top >= left && top >= right && top > 0) return TrayTaskbarEdge.Top;
        if (left >= right && left > 0) return TrayTaskbarEdge.Left;
        if (right > 0) return TrayTaskbarEdge.Right;
        return TrayTaskbarEdge.Bottom;
    }

    /// <summary>
    /// Extra pixels around the tray icon. Small enough that the popup and the gap
    /// above the taskbar do not count as still hovering the icon.
    /// </summary>
    public const double IconSlopPx = 8;

    /// <summary>
    /// How far the pointer may move from the last point Windows reported as icon hover
    /// before it counts as having left the tray icon. Larger than the glyph, smaller
    /// than the gap up to the popup.
    /// </summary>
    public const double IconLeaveSlopPx = 24;

    /// <summary>
    /// True only while the pointer is over the tray icon (plus <see cref="IconSlopPx"/>).
    /// The popup and the gap between the popup and the icon do not keep it open.
    /// </summary>
    public static bool PointerOverIcon(MiniRect icon, double x, double y, double slop = IconSlopPx)
    {
        if (icon.Width <= 0 || icon.Height <= 0) return false;
        slop = Math.Max(0, slop);
        return x >= icon.Left - slop && x < icon.Right + slop
            && y >= icon.Top - slop && y < icon.Bottom + slop;
    }

    /// <summary>
    /// True while the pointer is still on the tray icon that produced <paramref name="anchorX"/> and <paramref name="anchorY"/>.
    /// Those coordinates come from a hover message, so they are on the icon. Leaving the icon moves farther than <paramref name="slop"/>.
    /// </summary>
    public static bool PointerStillOnIcon(double x, double y, double anchorX, double anchorY, double slop = IconLeaveSlopPx)
    {
        slop = Math.Max(0, slop);
        return Math.Abs(x - anchorX) <= slop && Math.Abs(y - anchorY) <= slop;
    }

}
