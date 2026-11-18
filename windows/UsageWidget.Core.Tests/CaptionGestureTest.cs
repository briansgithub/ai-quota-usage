using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class CaptionGestureTest
{
    private const double Tx = 4;
    private const double Ty = 4;
    private const int DoubleMs = 500;

    [Fact]
    public void StationaryDownUpIsClick()
    {
        var t = new CaptionGestureTracker();
        Assert.Equal(CaptionGesture.None, t.Down(10, 10, 0, onCaption: true, clickCount: 1, DoubleMs, Tx, Ty));
        Assert.Equal(CaptionGesture.None, t.Move(11, 10, Tx, Ty));
        Assert.Equal(CaptionGesture.Click, t.Up(11, 10, 20, Tx, Ty));
    }

    [Fact]
    public void MovementPastThresholdIsDragAndNeverClick()
    {
        var t = new CaptionGestureTracker();
        t.Down(10, 10, 0, true, 1, DoubleMs, Tx, Ty);
        Assert.Equal(CaptionGesture.DragStart, t.Move(10 + Tx, 10, Tx, Ty));
        Assert.True(t.IsDragging);
        Assert.Equal(CaptionGesture.None, t.Up(40, 12, 80, Tx, Ty));
    }

    [Fact]
    public void ReleasePastThresholdWithoutMoveEventsIsNotClick()
    {
        var t = new CaptionGestureTracker();
        t.Down(10, 10, 0, true, 1, DoubleMs, Tx, Ty);
        Assert.Equal(CaptionGesture.None, t.Up(10 + Tx, 10, 30, Tx, Ty));
    }

    [Fact]
    public void SecondClickOfDoubleClickDoesNotToggleAgain()
    {
        var t = new CaptionGestureTracker();
        t.Down(10, 10, 0, true, 1, DoubleMs, Tx, Ty);
        Assert.Equal(CaptionGesture.Click, t.Up(10, 10, 10, Tx, Ty));
        t.Down(11, 10, 80, true, 2, DoubleMs, Tx, Ty);
        Assert.Equal(CaptionGesture.Ignore, t.Up(11, 10, 90, Tx, Ty));
    }

    [Fact]
    public void DoubleClickDetectedByTimeAndDistanceEvenWithoutClickCount()
    {
        var t = new CaptionGestureTracker();
        t.Down(10, 10, 0, true, 1, DoubleMs, Tx, Ty);
        Assert.Equal(CaptionGesture.Click, t.Up(10, 10, 10, Tx, Ty));
        t.Down(12, 11, 100, true, 1, DoubleMs, Tx, Ty);
        Assert.Equal(CaptionGesture.Ignore, t.Up(12, 11, 110, Tx, Ty));
    }

    [Fact]
    public void SlowSecondClickIsANewToggle()
    {
        var t = new CaptionGestureTracker();
        t.Down(10, 10, 0, true, 1, DoubleMs, Tx, Ty);
        Assert.Equal(CaptionGesture.Click, t.Up(10, 10, 10, Tx, Ty));
        t.Down(10, 10, 10 + DoubleMs + 1, true, 1, DoubleMs, Tx, Ty);
        Assert.Equal(CaptionGesture.Click, t.Up(10, 10, 10 + DoubleMs + 20, Tx, Ty));
    }

    [Fact]
    public void CancelBetweenDownAndUpDropsTheClick()
    {
        var t = new CaptionGestureTracker();
        t.Down(10, 10, 0, true, 1, DoubleMs, Tx, Ty);
        Assert.True(t.IsArmed);
        t.Cancel();
        Assert.False(t.IsArmed);
        Assert.Equal(CaptionGesture.None, t.Up(10, 10, 20, Tx, Ty));
    }

    [Fact]
    public void ClicksOffCaptionNeverToggle()
    {
        var t = new CaptionGestureTracker();
        Assert.Equal(CaptionGesture.None, t.Down(10, 10, 0, onCaption: false, 1, DoubleMs, Tx, Ty));
        Assert.Equal(CaptionGesture.None, t.Up(10, 10, 20, Tx, Ty));
    }

    [Fact]
    public void DragThresholdHelperMatchesSystemStyleAxes()
    {
        Assert.False(CaptionGestureTracker.ExceedsDragThreshold(3, 3, 4, 4));
        Assert.True(CaptionGestureTracker.ExceedsDragThreshold(4, 0, 4, 4));
        Assert.True(CaptionGestureTracker.ExceedsDragThreshold(0, -4, 4, 4));
    }
}
