using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using UsageWidget;
using DrawColor = System.Drawing.Color;

namespace UsageWidget.App;

internal static class AppBranding
{
    public static readonly Uri IconUri = new("pack://application:,,,/Resources/app.ico", UriKind.Absolute);

    private static ImageSource? _image;

    public static ImageSource ImageSource =>
        _image ??= BitmapFrame.Create(IconUri);

    public static Icon LoadTrayIcon(int size = 16)
    {
        var stream = System.Windows.Application.GetResourceStream(IconUri)?.Stream
            ?? throw new InvalidOperationException("app.ico resource missing");
        using (stream)
            return new Icon(stream, size, size);
    }
}

internal static class TrayIcons
{
    public static Icon WithCue(Icon baseIcon, TrayCue cue)
    {
        if (cue == TrayCue.Ok) return (Icon)baseIcon.Clone();
        var size = Math.Max(16, baseIcon.Width);
        using var bmp = new Bitmap(size, size);
        using (var g = Graphics.FromImage(bmp))
        {
            g.Clear(DrawColor.Transparent);
            g.InterpolationMode = InterpolationMode.NearestNeighbor;
            g.PixelOffsetMode = PixelOffsetMode.Half;
            g.SmoothingMode = SmoothingMode.None;
            using (var src = baseIcon.ToBitmap())
                g.DrawImage(src, 0, 0, size, size);
            var fill = cue switch
            {
                TrayCue.Error => DrawColor.FromArgb(255, 232, 17, 35),
                TrayCue.Stale => DrawColor.FromArgb(255, 230, 184, 76),
                _ => DrawColor.FromArgb(255, 46, 204, 113)
            };
            var d = Math.Max(5, size / 4);
            var x = size - d - 1;
            var y = size - d - 1;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var rim = new SolidBrush(DrawColor.FromArgb(255, 10, 14, 20)))
                g.FillEllipse(rim, x - 1, y - 1, d + 2, d + 2);
            using (var brush = new SolidBrush(fill))
                g.FillEllipse(brush, x, y, d, d);
        }
        return IconFromBitmap(bmp);
    }

    private static Icon IconFromBitmap(Bitmap bmp)
    {
        var handle = bmp.GetHicon();
        try
        {
            using var tmp = Icon.FromHandle(handle);
            return (Icon)tmp.Clone();
        }
        finally
        {
            NativeWindow.DestroyIcon(handle);
        }
    }
}
