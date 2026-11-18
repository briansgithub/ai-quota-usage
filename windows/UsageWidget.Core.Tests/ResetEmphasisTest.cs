using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class ResetEmphasisTest
{
    private const long Now = 1_000_000_000_000L;

    [Fact]
    public void MoreThanTwoDaysIsRelaxed()
    {
        Assert.Equal(ResetUrgency.Relaxed, ResetEmphasis.Of(Now + 49L * 3_600_000, Now));
        Assert.Equal(ResetUrgency.Relaxed, ResetEmphasis.Of(Now + 3L * 86_400_000, Now));
    }

    [Fact]
    public void BetweenOneAndTwoDaysIsSoon()
    {
        Assert.Equal(ResetUrgency.Soon, ResetEmphasis.Of(Now + 36L * 3_600_000, Now));
        Assert.Equal(ResetUrgency.Soon, ResetEmphasis.Of(Now + ResetEmphasis.RelaxedAfterMs, Now));
    }

    [Fact]
    public void UnderTwentyFourHoursIsNear()
    {
        Assert.Equal(ResetUrgency.Near, ResetEmphasis.Of(Now + 23L * 3_600_000, Now));
        Assert.Equal(ResetUrgency.Near, ResetEmphasis.Of(Now + 6L * 3_600_000, Now));
    }

    [Fact]
    public void FiveHoursOrLessIsImminent()
    {
        Assert.Equal(ResetUrgency.Imminent, ResetEmphasis.Of(Now + ResetEmphasis.ImminentAfterMs, Now));
        Assert.Equal(ResetUrgency.Imminent, ResetEmphasis.Of(Now + 30L * 60_000, Now));
        Assert.Equal(ResetUrgency.Imminent, ResetEmphasis.Of(Now - 1_000L, Now));
        Assert.Equal(ResetUrgency.Relaxed, ResetEmphasis.Of(null, Now));
    }
}
