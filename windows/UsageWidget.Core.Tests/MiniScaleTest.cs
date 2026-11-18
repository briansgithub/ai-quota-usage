using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class MiniScaleTest
{
    [Fact]
    public void WideWindowKeepsDesignHeightSoBarsCanStretch()
    {
        var scale = MiniChrome.UniformScale(MiniChrome.DefaultWidth * 2, MiniChrome.DefaultHeight);
        Assert.Equal(1, scale);
        var layout = MiniChrome.LayoutSize(MiniChrome.DefaultWidth * 2, MiniChrome.DefaultHeight, scale);
        Assert.Equal(MiniChrome.DefaultWidth * 2, layout.Width);
        Assert.Equal(MiniChrome.DefaultHeight, layout.Height);
    }

    [Fact]
    public void TallWindowKeepsDesignWidth()
    {
        var scale = MiniChrome.UniformScale(MiniChrome.DefaultWidth, MiniChrome.DefaultHeight * 2);
        Assert.Equal(1, scale);
        var layout = MiniChrome.LayoutSize(MiniChrome.DefaultWidth, MiniChrome.DefaultHeight * 2, scale);
        Assert.Equal(MiniChrome.DefaultWidth, layout.Width);
        Assert.Equal(MiniChrome.DefaultHeight * 2, layout.Height);
    }

    [Fact]
    public void SmallWindowScalesUniformlyToFitWithoutCropping()
    {
        var scale = MiniChrome.UniformScale(MiniChrome.DefaultWidth / 2, MiniChrome.DefaultHeight / 2);
        Assert.Equal(0.5, scale);
        var layout = MiniChrome.LayoutSize(MiniChrome.DefaultWidth / 2, MiniChrome.DefaultHeight / 2, scale);
        Assert.Equal(MiniChrome.DefaultWidth, layout.Width);
        Assert.Equal(MiniChrome.DefaultHeight, layout.Height);
    }

    [Fact]
    public void CaptionHitRegionScalesWithContent()
    {
        Assert.Equal(MiniHit.Client, MiniChrome.Hit(20, 20, 280, 400, false, scale: 0.5));
        Assert.Equal(MiniHit.Caption, MiniChrome.Hit(40, 10, 280, 400, false, scale: 0.5));
        Assert.Equal(MiniHit.Client, MiniChrome.Hit(250, 10, 280, 400, true, scale: 0.5));
        Assert.Equal(MiniHit.Client, MiniChrome.Hit(268, 10, 280, 400, false, scale: 0.5));
    }

    [Fact]
    public void ContentHeightAddsCaptionAndSlack()
    {
        Assert.Equal(MiniChrome.CaptionHeight + MiniChrome.ContentSlack, MiniChrome.ContentHeight(0));
        Assert.Equal(MiniChrome.CaptionHeight + 400 + MiniChrome.ContentSlack, MiniChrome.ContentHeight(400));
    }

    [Fact]
    public void DesignHeightNeverShorterThanMeasuredCardsAndFooter()
    {
        Assert.Equal(MiniChrome.DefaultHeight, MiniChrome.DesignHeight(0));
        Assert.Equal(MiniChrome.DefaultHeight, MiniChrome.DesignHeight(MiniChrome.DefaultHeight - 40));
        Assert.Equal(780, MiniChrome.DesignHeight(780));
    }

    [Fact]
    public void ShortWindowStillLayoutsTheFullCodexStack()
    {
        // Saved/default-old 460-tall window with three cards that measure ~640 including caption.
        const double contentW = MiniChrome.DefaultWidth;
        const double contentH = 640;
        const double windowW = MiniChrome.DefaultWidth;
        const double windowH = 460;
        var designH = MiniChrome.DesignHeight(contentH);
        var scale = MiniChrome.UniformScale(windowW, windowH, contentW, designH);
        var layout = MiniChrome.LayoutSize(windowW, windowH, scale);
        Assert.True(MiniChrome.LayoutContains(layout, contentW, contentH));
        Assert.Equal(windowH, layout.Height * scale, 6);
        Assert.True(layout.Height >= contentH);
        Assert.True(scale < 1);
    }

    [Fact]
    public void DefaultHeightCoversThreeCompactCards()
    {
        // 460 clipped Codex at 1:1. New default must be tall enough that a typical
        // signed-in stack (caption + Cursor + Grok + Codex + countdown) is not cropped.
        Assert.True(MiniChrome.DefaultHeight >= 660);
        var typical = MiniChrome.ContentHeight(500);
        Assert.True(MiniChrome.DesignHeight(typical) >= typical);
    }
}
