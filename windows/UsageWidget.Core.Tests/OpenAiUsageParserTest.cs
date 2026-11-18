using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class OpenAiUsageParserTest
{
    private static readonly long SnapshotTime = DateTimeOffset.Parse("2026-11-05T15:30:26Z").ToUnixTimeMilliseconds();

    [Fact]
    public void SessionReadsAccessTokenWithoutStoringIdentity()
    {
        var session = OpenAiUsageParser.ParseSession(Fixture.Load("openai-session.json"));
        Assert.StartsWith("eyJ", session.AccessToken);
        Assert.Equal("acct_REDACTED", session.AccountId);
    }

    [Fact]
    public void WhamUsageMatchesOverviewSnapshot()
    {
        var usage = OpenAiUsageParser.ParseWhamUsage(Fixture.Load("openai-wham-usage.json"), SnapshotTime);
        Assert.Equal("Plus", usage.PlanLabel);
        Assert.Equal(WindowKind.FiveHour, usage.FiveHour!.Kind);
        Assert.Equal(WindowKind.Weekly, usage.Weekly!.Kind);
        Assert.Equal(0.0, usage.FiveHour.UsedPercent!.Value, 2);
        Assert.Equal(0.0, usage.Weekly.UsedPercent!.Value, 2);
        Assert.Equal(DateTimeOffset.Parse("2026-11-05T20:20:26Z").ToUnixTimeMilliseconds(), usage.FiveHour.ResetsAtEpochMs);
        Assert.Equal(DateTimeOffset.Parse("2026-11-12T14:30:26Z").ToUnixTimeMilliseconds(), usage.Weekly.ResetsAtEpochMs);
        var snapshot = OpenAiUsageParser.ToSnapshot(usage);
        Assert.Equal(new[] { "openai.five_hour", "openai.weekly" }, snapshot.MeterList.Select(m => m.Id).ToArray());
        Assert.Equal(100, snapshot.MeterList[0].DisplayRemainingPercent);
        Assert.Equal(100, snapshot.MeterList[1].DisplayRemainingPercent);
        Assert.Equal("100% left", snapshot.MeterList[1].RemainingLabel());
        Assert.Equal("5-hour", snapshot.MeterList[0].Title);
        Assert.Equal("Weekly", snapshot.MeterList[1].Title);
    }

    [Fact]
    public void HtmlFallbackReadsPercentLeftAndRelativeResets()
    {
        var usage = OpenAiHtmlExtractor.Extract(Fixture.Load("openai-usage-page.txt"), SnapshotTime);
        Assert.Equal(0.0, usage.FiveHour!.UsedPercent!.Value, 2);
        Assert.Equal(0.0, usage.Weekly!.UsedPercent!.Value, 2);
        Assert.NotNull(usage.FiveHour.ResetsAtEpochMs);
        Assert.NotNull(usage.Weekly.ResetsAtEpochMs);
        var fiveDeltaMin = (usage.FiveHour.ResetsAtEpochMs!.Value - SnapshotTime) / 60_000;
        var weekDeltaHour = (usage.Weekly.ResetsAtEpochMs!.Value - SnapshotTime) / 3_600_000;
        Assert.Equal(4 * 60 + 50, fiveDeltaMin);
        Assert.Equal(6 * 24 + 23, weekDeltaHour);
    }

    [Fact]
    public void ClassifiesWindowsByDurationNotSlotName()
    {
        var json = """
            {
              "rate_limit": {
                "primary_window": {
                  "used_percent": 12,
                  "limit_window_seconds": 604800,
                  "reset_at": 1794493826
                },
                "secondary_window": {
                  "used_percent": 40,
                  "limit_window_seconds": 18000,
                  "reset_at": 1793910026
                }
              }
            }
            """;
        var usage = OpenAiUsageParser.ParseWhamUsage(json, SnapshotTime);
        Assert.Equal(WindowKind.Weekly, usage.Weekly!.Kind);
        Assert.Equal(12.0, usage.Weekly.UsedPercent!.Value, 2);
        Assert.Equal(WindowKind.FiveHour, usage.FiveHour!.Kind);
        Assert.Equal(40.0, usage.FiveHour.UsedPercent!.Value, 2);
    }

    [Fact]
    public void SnapshotOmitsMissingWindowsInsteadOfInventingThem()
    {
        var json = """
            {
              "plan_type": "plus",
              "rate_limit": {
                "primary_window": {
                  "used_percent": 22,
                  "limit_window_seconds": 18000,
                  "reset_at": 1793910026
                }
              }
            }
            """;
        var usage = OpenAiUsageParser.ParseWhamUsage(json, SnapshotTime);
        var snapshot = OpenAiUsageParser.ToSnapshot(usage);
        Assert.Equal(new[] { "openai.five_hour" }, snapshot.MeterList.Select(m => m.Id).ToArray());
        Assert.Equal(22, snapshot.MeterList[0].DisplayUsedPercent);
        Assert.Equal(78, snapshot.MeterList[0].DisplayRemainingPercent);
        Assert.Equal("5-hour", snapshot.MeterList[0].Title);
    }

    [Fact]
    public void UsedPercentBelowOneIsAlreadyAPercentNotAFraction()
    {
        var json = """
            {
              "rate_limit": {
                "primary_window": {
                  "used_percent": 0.5,
                  "limit_window_seconds": 18000,
                  "reset_at": 1793910026
                },
                "secondary_window": {
                  "used_percent": 12.5,
                  "limit_window_seconds": 604800,
                  "reset_at": 1794493826
                }
              }
            }
            """;
        var usage = OpenAiUsageParser.ParseWhamUsage(json, SnapshotTime);
        Assert.Equal(0.5, usage.FiveHour!.UsedPercent!.Value, 4);
        Assert.Equal(12.5, usage.Weekly!.UsedPercent!.Value, 4);
        var snapshot = OpenAiUsageParser.ToSnapshot(usage);
        Assert.Equal("1% used", snapshot.MeterList[0].UsedLabel());
        Assert.Equal("13% used", snapshot.MeterList[1].UsedLabel());
    }

    [Fact]
    public void TeamWeeklyOnlyDoesNotInventAFiveHourRow()
    {
        var json = """
            {
              "plan_type": "team",
              "rate_limit": {
                "primary_window": {
                  "used_percent": 8,
                  "limit_window_seconds": 604800,
                  "reset_at": 1794493826
                }
              }
            }
            """;
        var usage = OpenAiUsageParser.ParseWhamUsage(json, SnapshotTime);
        Assert.Equal(WindowKind.Weekly, usage.Weekly!.Kind);
        Assert.Null(usage.FiveHour);
        Assert.Equal(new[] { "openai.weekly" }, OpenAiUsageParser.ToSnapshot(usage).MeterList.Select(m => m.Id).ToArray());
    }

    [Fact]
    public void ThirtyDayWindowIsNotLabeledWeekly()
    {
        var json = """
            {
              "plan_type": "free",
              "rate_limit": {
                "primary_window": {
                  "used_percent": 3,
                  "limit_window_seconds": 2592000,
                  "reset_after_seconds": 2000000
                }
              }
            }
            """;
        var usage = OpenAiUsageParser.ParseWhamUsage(json, SnapshotTime);
        Assert.Equal(WindowKind.ThirtyDay, usage.ThirtyDay!.Kind);
        Assert.Null(usage.Weekly);
        Assert.Equal("30-day", usage.ThirtyDay.Title);
        Assert.Equal(SnapshotTime + 2_000_000_000L, usage.ThirtyDay.ResetsAtEpochMs);
        var snapshot = OpenAiUsageParser.ToSnapshot(usage);
        Assert.Equal(new[] { "openai.thirty_day" }, snapshot.MeterList.Select(m => m.Id).ToArray());
        Assert.Equal("30-day", snapshot.MeterList.Single().Title);
    }

    [Fact]
    public void PrefersResetAtEpochOverResetAfterSeconds()
    {
        var json = """
            {
              "rate_limit": {
                "primary_window": {
                  "used_percent": 1,
                  "limit_window_seconds": 18000,
                  "reset_after_seconds": 10,
                  "reset_at": 1793910026
                }
              }
            }
            """;
        var usage = OpenAiUsageParser.ParseWhamUsage(json, SnapshotTime);
        Assert.Equal(DateTimeOffset.Parse("2026-11-05T20:20:26Z").ToUnixTimeMilliseconds(), usage.FiveHour!.ResetsAtEpochMs);
    }

    [Fact]
    public void IncludesCodeReviewAdditionalLimitsAndCredits()
    {
        var json = """
            {
              "rate_limit": {
                "primary_window": {
                  "used_percent": 4,
                  "limit_window_seconds": 18000,
                  "reset_at": 1793910026
                },
                "secondary_window": {
                  "used_percent": 9,
                  "limit_window_seconds": 604800,
                  "reset_at": 1794493826
                }
              },
              "code_review_rate_limit": {
                "used_percent": 20,
                "limit_window_seconds": 604800,
                "reset_at": 1794493826
              },
              "additional_rate_limits": [
                {
                  "title": "Deep research",
                  "rate_limit": {
                    "primary_window": {
                      "used_percent": 50,
                      "limit_window_seconds": 86400,
                      "reset_at": 1793996426
                    }
                  }
                }
              ],
              "credits": {
                "has_credits": true,
                "remaining": 12,
                "total": 20
              }
            }
            """;
        var usage = OpenAiUsageParser.ParseWhamUsage(json, SnapshotTime);
        Assert.Equal("Code review", usage.CodeReview!.Title);
        Assert.Equal(20.0, usage.CodeReview.UsedPercent!.Value, 2);
        Assert.Equal("12 of 20 credits remaining", usage.CreditsLabel);
        var snapshot = OpenAiUsageParser.ToSnapshot(usage);
        Assert.Equal(
            new[] { "openai.five_hour", "openai.weekly", "openai.code_review", "openai.deep_research_daily", "openai.credits" },
            snapshot.MeterList.Select(m => m.Id).ToArray());
        Assert.Equal("Credits", snapshot.MeterList.Last().Title);
        Assert.Equal("12 of 20 credits remaining", snapshot.MeterList.Last().ResetLabel);
    }

    [Fact]
    public void ClassifiesNearWeeklyDurationsAsWeekly()
    {
        Assert.Equal(WindowKind.Weekly, OpenAiUsageParser.ClassifyWindow(604_800L, "primary"));
        Assert.Equal(WindowKind.Weekly, OpenAiUsageParser.ClassifyWindow(691_200L, null));
        Assert.Equal(WindowKind.Weekly, OpenAiUsageParser.ClassifyWindow(500_000L, "primary"));
        Assert.Equal(WindowKind.FiveHour, OpenAiUsageParser.ClassifyWindow(18_000L, "secondary"));
        Assert.Equal(WindowKind.Weekly, OpenAiUsageParser.ClassifyWindow(null, "secondary"));
        Assert.Equal(WindowKind.ThirtyDay, OpenAiUsageParser.ClassifyWindow(2_592_000L, "primary"));
    }
}
