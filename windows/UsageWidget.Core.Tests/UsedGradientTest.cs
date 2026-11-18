using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class UsedGradientTest
{
    [Fact]
    public void OnPaceIsPaleBehindIsRedAheadIsGreen()
    {
        var on = UsedGradient.Fill(50, 50);
        var behind = UsedGradient.Fill(25, 50);
        var ahead = UsedGradient.Fill(75, 50);
        Assert.True(on.R > 200 && on.G > 200 && on.B > 200);
        Assert.True(behind.R > behind.G && behind.R > behind.B);
        Assert.True(ahead.G > ahead.R && ahead.G > ahead.B);
        Assert.True(UsedGradient.Outline(50, 50).R < 40);
    }

    [Fact]
    public void FullUsedIsAlwaysGreen()
    {
        var a = UsedGradient.Fill(100, 10);
        var b = UsedGradient.Fill(100, 100);
        var c = UsedGradient.Fill(100, null);
        Assert.Equal(UsedGradient.Green, a);
        Assert.Equal(UsedGradient.Green, b);
        Assert.Equal(UsedGradient.Green, c);
    }

    [Fact]
    public void UnknownWindowIsNeutralNotRawUsedGradient()
    {
        var unknownEmpty = UsedGradient.Fill(0, null);
        var unknownMid = UsedGradient.Fill(50, null);
        var unknownHigh = UsedGradient.Fill(90, null);
        Assert.Equal(UsedGradient.Mid, unknownEmpty);
        Assert.Equal(UsedGradient.Mid, unknownMid);
        Assert.Equal(UsedGradient.Mid, unknownHigh);
        Assert.NotEqual(UsedGradient.Red, unknownEmpty);
    }

    [Fact]
    public void DeltaSaturatesAtTwentyFivePoints()
    {
        var satBehind = UsedGradient.Fill(0, 25);
        var moreBehind = UsedGradient.Fill(0, 80);
        Assert.Equal(UsedGradient.Red, satBehind);
        Assert.Equal(UsedGradient.Red, moreBehind);
        var satAhead = UsedGradient.Fill(75, 50);
        var moreAhead = UsedGradient.Fill(90, 50);
        Assert.Equal(UsedGradient.Green, satAhead);
        Assert.Equal(UsedGradient.Green, moreAhead);
    }

    [Fact]
    public void InterpolatesTowardTheStops()
    {
        var mildBehind = UsedGradient.Fill(40, 50);
        var mildAhead = UsedGradient.Fill(60, 50);
        Assert.True(mildBehind.R > mildBehind.G);
        Assert.True(mildAhead.G > mildAhead.R);
        Assert.NotEqual(UsedGradient.Red, mildBehind);
        Assert.NotEqual(UsedGradient.Green, mildAhead);
    }

    [Fact]
    public void CapFillIsGreenAtZeroWhiteAtHalfRedAtFull()
    {
        Assert.Equal(UsedGradient.Green, UsedGradient.CapFill(0));
        Assert.Equal(UsedGradient.Mid, UsedGradient.CapFill(50));
        Assert.Equal(UsedGradient.Red, UsedGradient.CapFill(100));
        Assert.Equal(UsedGradient.Green, UsedGradient.CapFill(-10));
        Assert.Equal(UsedGradient.Red, UsedGradient.CapFill(140));
    }

    [Fact]
    public void CapFillInterpolatesTowardTheStops()
    {
        var low = UsedGradient.CapFill(25);
        Assert.Equal(141, low.R);
        Assert.Equal(206, low.G);
        Assert.Equal(166, low.B);
        var high = UsedGradient.CapFill(75);
        Assert.Equal(230, high.R);
        Assert.Equal(152, high.G);
        Assert.Equal(149, high.B);
        Assert.True(UsedGradient.CapOutline(50).R < 40);
    }
}
