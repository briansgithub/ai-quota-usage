using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class MiniChromeTest
{
    [Fact]
    public void TopStripIsCaptionExceptLiveCaptionButtons()
    {
        Assert.Equal(MiniHit.Client, MiniChrome.Hit(20, 16, 280, 400, captionButtonsLive: false));
        Assert.Equal(MiniHit.Caption, MiniChrome.Hit(80, 16, 280, 400, captionButtonsLive: false));
        // Window buttons sit at the far right only while hovered; otherwise that is drag area.
        Assert.Equal(MiniHit.Caption, MiniChrome.Hit(260, 16, 280, 400, captionButtonsLive: false));
        Assert.Equal(MiniHit.Client, MiniChrome.Hit(260, 16, 280, 400, captionButtonsLive: true));
        Assert.Equal(MiniHit.Caption, MiniChrome.Hit(200, 16, 280, 400, captionButtonsLive: false));
        Assert.Equal(MiniHit.Client, MiniChrome.Hit(200, 16, 280, 400, captionButtonsLive: true));
        Assert.Equal(MiniHit.Caption, MiniChrome.Hit(80, 16, 280, 400, captionButtonsLive: true));
        Assert.Equal(MiniHit.Client, MiniChrome.Hit(40, 80, 280, 400, captionButtonsLive: false));
    }

    [Fact]
    public void EdgesAndCornersAreResizeHitsAndBeatCaption()
    {
        const double w = 280, h = 400;
        Assert.Equal(MiniHit.Left, MiniChrome.Hit(2, 80, w, h, false));
        Assert.Equal(MiniHit.Right, MiniChrome.Hit(278, 80, w, h, false));
        Assert.Equal(MiniHit.Top, MiniChrome.Hit(80, 2, w, h, false));
        Assert.Equal(MiniHit.Bottom, MiniChrome.Hit(80, 398, w, h, false));
        Assert.Equal(MiniHit.TopLeft, MiniChrome.Hit(2, 2, w, h, false));
        Assert.Equal(MiniHit.TopRight, MiniChrome.Hit(278, 2, w, h, false));
        Assert.Equal(MiniHit.BottomLeft, MiniChrome.Hit(2, 398, w, h, false));
        Assert.Equal(MiniHit.BottomRight, MiniChrome.Hit(278, 398, w, h, false));
    }

    [Fact]
    public void ClampKeepsPanelOnWorkAreaAndHonorsMinimum()
    {
        var work = new MiniRect(0, 0, 1920, 1040);
        var off = MiniChrome.Clamp(new MiniRect(-400, 5000, 100, 50), work);
        Assert.Equal(0, off.Left);
        Assert.InRange(off.Top, 0, 1040 - MiniChrome.MinHeight);
        Assert.Equal(MiniChrome.MinWidth, off.Width);
        Assert.Equal(MiniChrome.MinHeight, off.Height);

        var tooBig = MiniChrome.Clamp(new MiniRect(0, 0, 5000, 5000), work);
        Assert.Equal(1920, tooBig.Width);
        Assert.Equal(1040, tooBig.Height);
        Assert.Equal(0, tooBig.Left);
        Assert.Equal(0, tooBig.Top);
    }

    [Fact]
    public void ClampUsesDefaultsWhenSizeMissing()
    {
        var work = new MiniRect(100, 200, 800, 1000);
        var placed = MiniChrome.Clamp(new MiniRect(120, 220, 0, 0), work);
        Assert.Equal(MiniChrome.DefaultWidth, placed.Width);
        Assert.Equal(MiniChrome.DefaultHeight, placed.Height);
        Assert.Equal(120, placed.Left);
        Assert.Equal(220, placed.Top);
    }
}
