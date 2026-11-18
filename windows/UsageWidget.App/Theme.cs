using System.Windows;
using System.Windows.Media;
using UsageWidget;

namespace UsageWidget.App;

internal static class Theme
{
    public static readonly Color Chrome = Color.FromRgb(0x14, 0x14, 0x14);
    public static readonly Color Surface = Color.FromRgb(0x1A, 0x1A, 0x1A);
    public static readonly Color Outline = Color.FromRgb(0x2C, 0x2C, 0x2C);
    public static readonly Color Text = Color.FromRgb(0xE8, 0xE8, 0xE8);
    public static readonly Color Muted = Color.FromRgb(0x9A, 0x9A, 0x9A);
    public static readonly Color Bar = Color.FromRgb(0x6E, 0xA8, 0xD8);
    public static readonly Color BarWarn = Color.FromRgb(0xE0, 0x8B, 0x6E);
    public static readonly Color Track = Color.FromRgb(0x3A, 0x3A, 0x3E);
    public static readonly Color Amber = Color.FromRgb(0xE6, 0xB8, 0x4C);
    public static readonly Color Imminent = Color.FromRgb(0xFF, 0x70, 0x43);
    public static readonly Color Bright = Color.FromRgb(0xF5, 0xF5, 0xF5);
    public static readonly Color AmberBg = Color.FromRgb(0x33, 0x2A, 0x12);
    public static readonly Color RedBg = Color.FromRgb(0x3A, 0x22, 0x1C);
    public static readonly Color CaptionHover = Color.FromRgb(0x2D, 0x2D, 0x2D);
    public static readonly Color CloseHover = Color.FromRgb(0xE8, 0x11, 0x23);

    public static readonly Brush ChromeBrush = new SolidColorBrush(Chrome);
    public static readonly Brush SurfaceBrush = new SolidColorBrush(Surface);
    public static readonly Brush OutlineBrush = new SolidColorBrush(Outline);
    public static readonly Brush TextBrush = new SolidColorBrush(Text);
    public static readonly Brush MutedBrush = new SolidColorBrush(Muted);
    public static readonly Brush BarBrush = new SolidColorBrush(Bar);
    public static readonly Brush BarWarnBrush = new SolidColorBrush(BarWarn);
    public static readonly Brush TrackBrush = new SolidColorBrush(Track);
    public static readonly Brush AmberBrush = new SolidColorBrush(Amber);
    public static readonly Brush ImminentBrush = new SolidColorBrush(Imminent);
    public static readonly Brush BrightBrush = new SolidColorBrush(Bright);
    public static readonly Brush AmberBgBrush = new SolidColorBrush(AmberBg);
    public static readonly Brush RedBgBrush = new SolidColorBrush(RedBg);
    public static readonly Brush CaptionHoverBrush = new SolidColorBrush(CaptionHover);
    public static readonly Brush CloseHoverBrush = new SolidColorBrush(CloseHover);

    static Theme()
    {
        foreach (var brush in new Brush[]
                 {
                     ChromeBrush, SurfaceBrush, OutlineBrush, TextBrush, MutedBrush, BarBrush, BarWarnBrush,
                     TrackBrush, AmberBrush, ImminentBrush, BrightBrush, AmberBgBrush, RedBgBrush,
                     CaptionHoverBrush, CloseHoverBrush
                 })
            brush.Freeze();
    }

    public static Brush ResetBrush(ResetUrgency urgency) => urgency switch
    {
        ResetUrgency.Soon => BrightBrush,
        ResetUrgency.Near => AmberBrush,
        ResetUrgency.Imminent => ImminentBrush,
        _ => TextBrush
    };

    public static FontWeight ResetWeight(ResetUrgency urgency) => urgency switch
    {
        ResetUrgency.Relaxed => FontWeights.Medium,
        ResetUrgency.Soon => FontWeights.SemiBold,
        _ => FontWeights.Bold
    };

    public static double ResetSize(ResetUrgency urgency, bool compact) => urgency switch
    {
        ResetUrgency.Imminent => compact ? 12 : 15,
        ResetUrgency.Near => compact ? 11.5 : 14,
        ResetUrgency.Soon => compact ? 11 : 13.5,
        _ => compact ? 11 : 13
    };

    public static Brush PaceBrush(PaceUrgency urgency) => urgency switch
    {
        PaceUrgency.Impossible or PaceUrgency.Critical => ImminentBrush,
        PaceUrgency.Tight => AmberBrush,
        _ => MutedBrush
    };

    public static Brush StaleBrush(StaleSeverity severity) =>
        severity == StaleSeverity.Red ? BarWarnBrush : AmberBrush;

    public static Brush StaleBg(StaleSeverity severity) =>
        severity == StaleSeverity.Red ? RedBgBrush : AmberBgBrush;

    public static SolidColorBrush PaceFill(double usedPercent, double? expectedUsedPercent)
    {
        var rgb = UsedGradient.Fill(usedPercent, expectedUsedPercent);
        var brush = new SolidColorBrush(Color.FromRgb(rgb.R, rgb.G, rgb.B));
        brush.Freeze();
        return brush;
    }

    public static SolidColorBrush PaceStroke(double usedPercent, double? expectedUsedPercent)
    {
        var rgb = UsedGradient.Outline(usedPercent, expectedUsedPercent);
        var brush = new SolidColorBrush(Color.FromRgb(rgb.R, rgb.G, rgb.B));
        brush.Freeze();
        return brush;
    }

    public static SolidColorBrush CapFill(double usedPercent)
    {
        var rgb = UsedGradient.CapFill(usedPercent);
        var brush = new SolidColorBrush(Color.FromRgb(rgb.R, rgb.G, rgb.B));
        brush.Freeze();
        return brush;
    }

    public static SolidColorBrush CapStroke(double usedPercent)
    {
        var rgb = UsedGradient.CapOutline(usedPercent);
        var brush = new SolidColorBrush(Color.FromRgb(rgb.R, rgb.G, rgb.B));
        brush.Freeze();
        return brush;
    }
}
