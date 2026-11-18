namespace UsageWidget;

public readonly record struct Rgb8(byte R, byte G, byte B);

/// <summary>
/// Bar fill color vs even pace, not raw used-%.
/// Delta = actualUsed − expectedUsed (elapsed fraction of the window). Saturates
/// at ±<see cref="SaturateDelta"/> percentage points: −25 (or more behind) → red,
/// 0 → pale (on pace), +25 (or more ahead) → green. 100% used is always green.
/// Unknown window → pale, not the old raw-used gradient. Delta is used instead of
/// a used/expected ratio so a 0% vs 1% expected at window start stays nearly white
/// instead of flashing full red.
/// Pale midtones get a dark outline so they read on the dark widget chrome.
/// </summary>
public static class UsedGradient
{
    public const double SaturateDelta = 25.0;

    public static readonly Rgb8 Red = new(224, 68, 58);
    public static readonly Rgb8 Mid = new(236, 236, 240);
    public static readonly Rgb8 Green = new(46, 176, 92);
    public static readonly Rgb8 DarkOutline = new(16, 16, 18);

    public static Rgb8 Fill(double usedPercent, double? expectedUsedPercent)
    {
        if (usedPercent >= 100) return Green;
        if (expectedUsedPercent is null) return Mid;
        var delta = usedPercent - expectedUsedPercent.Value;
        var t = Math.Clamp(delta / SaturateDelta, -1, 1);
        return t < 0 ? Lerp(Mid, Red, -t) : Lerp(Mid, Green, t);
    }

    /// <summary>
    /// On-demand dollar-cap bar: 0% green, 50% white, 100% red.
    /// Fraction of the cap only — no pace.
    /// </summary>
    public static Rgb8 CapFill(double usedPercent)
    {
        var used = Math.Clamp(usedPercent, 0, 100);
        return used <= 50 ? Lerp(Green, Mid, used / 50.0) : Lerp(Mid, Red, (used - 50) / 50.0);
    }

    public static Rgb8 Outline(double usedPercent, double? expectedUsedPercent) =>
        OutlineOf(Fill(usedPercent, expectedUsedPercent));

    public static Rgb8 CapOutline(double usedPercent) => OutlineOf(CapFill(usedPercent));

    private static Rgb8 OutlineOf(Rgb8 fill)
    {
        var lum = 0.299 * fill.R + 0.587 * fill.G + 0.114 * fill.B;
        if (lum >= 140) return DarkOutline;
        return new Rgb8((byte)(fill.R * 0.45), (byte)(fill.G * 0.45), (byte)(fill.B * 0.45));
    }

    private static Rgb8 Lerp(Rgb8 a, Rgb8 b, double t)
    {
        t = Math.Clamp(t, 0, 1);
        return new Rgb8(
            (byte)Math.Round(a.R + (b.R - a.R) * t),
            (byte)Math.Round(a.G + (b.G - a.G) * t),
            (byte)Math.Round(a.B + (b.B - a.B) * t));
    }
}
