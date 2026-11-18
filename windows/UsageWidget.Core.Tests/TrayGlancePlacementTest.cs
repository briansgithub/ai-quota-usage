using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class TrayGlancePlacementTest
{
    [Fact]
    public void PaddingIsInTheRequestedBand()
    {
        Assert.InRange(TrayGlancePlacement.EdgePaddingDip, 8, 12);
    }

    [Fact]
    public void BottomTaskbarSitsAboveTheIconAndRepeats()
    {
        var work = new MiniRect(0, 0, 1920, 1040);
        var placed = TrayGlancePlacement.Place(180, 80, work, TrayTaskbarEdge.Bottom, 960);
        Assert.Equal(work.Bottom - TrayGlancePlacement.EdgePaddingDip, placed.Bottom, 6);
        Assert.Equal(960, placed.Left + placed.Width / 2, 6);
        Assert.Equal(placed, TrayGlancePlacement.Place(180, 80, work, TrayTaskbarEdge.Bottom, 960));
    }

    [Fact]
    public void BottomTaskbarClampsTheHorizontalCenterIntoTheWorkArea()
    {
        var work = new MiniRect(0, 0, 1920, 1040);
        var placed = TrayGlancePlacement.Place(180, 80, work, TrayTaskbarEdge.Bottom, 1910);
        Assert.Equal(work.Bottom - TrayGlancePlacement.EdgePaddingDip, placed.Bottom, 6);
        Assert.Equal(work.Right - TrayGlancePlacement.EdgePaddingDip, placed.Right, 6);
        Assert.True(placed.Left >= work.Left + TrayGlancePlacement.EdgePaddingDip - 1e-6);
    }

    [Fact]
    public void TopTaskbarSitsOnTheTopEdge()
    {
        var work = new MiniRect(0, 40, 1920, 1000);
        var placed = TrayGlancePlacement.Place(180, 80, work, TrayTaskbarEdge.Top, 200);
        Assert.Equal(work.Top + TrayGlancePlacement.EdgePaddingDip, placed.Top, 6);
        Assert.Equal(200, placed.Left + placed.Width / 2, 6);
        Assert.True(placed.Bottom <= work.Bottom - TrayGlancePlacement.EdgePaddingDip + 1e-6);
    }

    [Fact]
    public void LeftAndRightTaskbarsSitOnThatEdge()
    {
        var leftWork = new MiniRect(48, 0, 1800, 1080);
        var left = TrayGlancePlacement.Place(180, 80, leftWork, TrayTaskbarEdge.Left, 400);
        Assert.Equal(leftWork.Left + TrayGlancePlacement.EdgePaddingDip, left.Left, 6);
        Assert.Equal(400, left.Top + left.Height / 2, 6);

        var rightWork = new MiniRect(0, 0, 1872, 1080);
        var right = TrayGlancePlacement.Place(180, 80, rightWork, TrayTaskbarEdge.Right, 500);
        Assert.Equal(rightWork.Right - TrayGlancePlacement.EdgePaddingDip, right.Right, 6);
        Assert.Equal(500, right.Top + right.Height / 2, 6);
    }

    [Fact]
    public void PointerOverTheIconStaysOwnedAndLeavesWithTheIcon()
    {
        var icon = new MiniRect(170, 900, 40, 40);
        Assert.True(TrayGlancePlacement.PointerOverIcon(icon, 190, 910));
        Assert.True(TrayGlancePlacement.PointerOverIcon(icon, 170 - TrayGlancePlacement.IconSlopPx, 900));
        Assert.False(TrayGlancePlacement.PointerOverIcon(icon, 180, 820));
        Assert.False(TrayGlancePlacement.PointerOverIcon(icon, 180, 890));
        Assert.False(TrayGlancePlacement.PointerOverIcon(icon, 10, 10));
        Assert.False(TrayGlancePlacement.PointerOverIcon(new MiniRect(0, 0, 0, 0), 190, 910));
        Assert.InRange(TrayGlancePlacement.IconSlopPx, 1, 12);
        Assert.InRange(TrayGlancePlacement.IconLeaveSlopPx, 16, 32);
        Assert.True(TrayGlancePlacement.PointerStillOnIcon(200, 910, 190, 910));
        Assert.False(TrayGlancePlacement.PointerStillOnIcon(190, 910 - TrayGlancePlacement.IconLeaveSlopPx - 1, 190, 910));
    }
}
