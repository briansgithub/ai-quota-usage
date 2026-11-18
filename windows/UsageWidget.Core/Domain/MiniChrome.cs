namespace UsageWidget;

public enum MiniHit
{
    Client,
    Caption,
    Left,
    Right,
    Top,
    Bottom,
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight
}

public readonly record struct MiniRect(double Left, double Top, double Width, double Height)
{
    public double Right => Left + Width;
    public double Bottom => Top + Height;
}

public static class MiniChrome
{
    public const double Grip = 6;
    public const double CaptionHeight = 32;
    public const double CaptionButtonWidth = 46;
    public const int CaptionButtonCount = 3;
    public const double CaptionButtonsWidth = CaptionButtonWidth * CaptionButtonCount;
    public const double PinWidth = 32;
    public const double GearWidth = 32;
    public const double LeftChromeWidth = PinWidth + GearWidth;
    /// <summary>Refresh / Settings buttons in the footer, next to the countdown.</summary>
    public const double FooterButtonWidth = 28;
    public const double FooterHeight = 24;
    public const double MinWidth = 240;
    public const double MinHeight = 200;
    public const double DefaultWidth = 280;
    /// <summary>
    /// Default mini-panel height. Must cover caption + four compact cards (Cursor, Grok Bot,
    /// Codex, Claude, including on-demand bars) + footer; 460 clipped the Codex card. Scale still uses
    /// <see cref="DesignHeight"/> from the measured content so a shorter saved window does not crop either.
    /// </summary>
    public const double DefaultHeight = 840;
    public const double ContentSlack = 4;

    public static double ContentHeight(double bodyAndFooterHeight) =>
        CaptionHeight + Math.Max(0, bodyAndFooterHeight) + ContentSlack;

    /// <summary>
    /// Design box for uniform scale: at least the default window, and never shorter than the
    /// measured caption + cards + footer (so Codex cannot be clipped).
    /// </summary>
    public static double DesignHeight(double measuredContentHeight) =>
        Math.Max(DefaultHeight, Math.Max(0, measuredContentHeight));

    public static double UniformScale(double windowWidth, double windowHeight, double designWidth = DefaultWidth, double designHeight = DefaultHeight)
    {
        if (windowWidth <= 0 || windowHeight <= 0 || designWidth <= 0 || designHeight <= 0) return 1;
        return Math.Min(windowWidth / designWidth, windowHeight / designHeight);
    }

    public static MiniRect LayoutSize(double windowWidth, double windowHeight, double scale)
    {
        scale = Math.Max(scale, 1e-6);
        return new MiniRect(0, 0, windowWidth / scale, windowHeight / scale);
    }

    public static bool LayoutContains(MiniRect layout, double contentWidth, double contentHeight) =>
        layout.Width + 1e-6 >= contentWidth && layout.Height + 1e-6 >= contentHeight;

    public static MiniHit Hit(double x, double y, double width, double height, bool captionButtonsLive, double scale = 1)
    {
        if (width <= 0 || height <= 0) return MiniHit.Client;
        scale = scale <= 0 ? 1 : scale;
        var grip = Math.Min(Grip, Math.Min(width, height) / 3);
        var left = x < grip;
        var right = x >= width - grip;
        var top = y < grip;
        var bottom = y >= height - grip;
        if (top && left) return MiniHit.TopLeft;
        if (top && right) return MiniHit.TopRight;
        if (bottom && left) return MiniHit.BottomLeft;
        if (bottom && right) return MiniHit.BottomRight;
        if (left) return MiniHit.Left;
        if (right) return MiniHit.Right;
        if (top) return MiniHit.Top;
        if (bottom) return MiniHit.Bottom;
        var capH = CaptionHeight * scale;
        if (y >= 0 && y < capH)
        {
            // The title strip holds window chrome only: pin on the left, and minimize /
            // maximize / exit at the far right while hovered. Refresh and Settings live in the
            // footer. The outer grip stays a resize edge (handled above).
            if (captionButtonsLive && x >= Math.Max(0, width - CaptionButtonsWidth * scale)) return MiniHit.Client;
            if (x >= grip && x < PinWidth * scale) return MiniHit.Client;
            return MiniHit.Caption;
        }
        return MiniHit.Client;
    }

    public static MiniRect Clamp(MiniRect placement, MiniRect workArea)
    {
        var maxW = Math.Max(1, workArea.Width);
        var maxH = Math.Max(1, workArea.Height);
        var width = placement.Width <= 0 ? DefaultWidth : placement.Width;
        var height = placement.Height <= 0 ? DefaultHeight : placement.Height;
        width = Math.Clamp(width, Math.Min(MinWidth, maxW), maxW);
        height = Math.Clamp(height, Math.Min(MinHeight, maxH), maxH);
        var left = placement.Left;
        var top = placement.Top;
        var maxLeft = workArea.Left + Math.Max(0, maxW - width);
        var maxTop = workArea.Top + Math.Max(0, maxH - height);
        left = Math.Clamp(left, workArea.Left, maxLeft);
        top = Math.Clamp(top, workArea.Top, maxTop);
        return new MiniRect(left, top, width, height);
    }
}
