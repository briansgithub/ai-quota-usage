using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class ManualResetParserTest
{
    private static readonly long Now = DateTimeOffset.Parse("2026-11-05T15:30:26Z").ToUnixTimeMilliseconds();

    [Fact]
    public void UsageSummaryCountWithoutCreditsArray()
    {
        var json = """
            {
              "plan_type": "plus",
              "rate_limit": {
                "primary_window": { "used_percent": 10, "limit_window_seconds": 18000, "reset_at": 1793910026 }
              },
              "rate_limit_reset_credits": { "available_count": 1 }
            }
            """;
        var usage = OpenAiUsageParser.ParseWhamUsage(json, Now);
        Assert.Equal(1, usage.ManualResets!.AvailableCount);
        Assert.Contains("available_count", usage.ManualResets.ObservedJson!);
        Assert.True(usage.ManualResets.IsPresent);
        Assert.Equal("Manual resets: 1", usage.ManualResets.DisplayLine());
        var snapshot = OpenAiUsageParser.ToSnapshot(usage);
        Assert.Equal("Manual resets: 1", snapshot.ManualResets!.DisplayLine());
        Assert.DoesNotContain("RateLimitResetCredit_", snapshot.DiagnosticJson!);
    }

    [Fact]
    public void DetailsEndpointAddsExpiryDdMm()
    {
        var usageJson = """
            {
              "rate_limit": {
                "primary_window": { "used_percent": 0, "limit_window_seconds": 18000, "reset_at": 1793910026 }
              },
              "rate_limit_reset_credits": { "available_count": 1 }
            }
            """;
        var details = """
            {
              "available_count": 1,
              "credits": [
                {
                  "id": "RateLimitResetCredit_SECRET",
                  "reset_type": "codex_rate_limits",
                  "status": "available",
                  "expires_at": "2026-11-12T17:00:00Z",
                  "title": "Full reset (Weekly + 5 hr)"
                }
              ]
            }
            """;
        var usage = OpenAiUsageParser.MergeResetDetails(OpenAiUsageParser.ParseWhamUsage(usageJson, Now), details, Now);
        Assert.Equal("Manual resets: 1 (exp 12/11)", usage.ManualResets!.DisplayLine());
        Assert.DoesNotContain("RateLimitResetCredit_SECRET", usage.ObservedJson!);
        Assert.Contains("expires_at", usage.ObservedJson!);
        Assert.Contains("codex_rate_limits", usage.ObservedJson!);
    }

    [Fact]
    public void MissingResetCreditsHidesLine()
    {
        var usage = OpenAiUsageParser.ParseWhamUsage(Fixture.Load("openai-wham-usage.json"), Now);
        Assert.False(usage.ManualResets?.IsPresent == true);
        var snapshot = OpenAiUsageParser.ToSnapshot(usage);
        Assert.Null(snapshot.ManualResets);
        Assert.Contains("rate_limit_reset_credits", snapshot.DiagnosticJson!);
    }

    [Fact]
    public void HtmlFallbackReadsUsageLimitResets()
    {
        var usage = OpenAiHtmlExtractor.Extract(Fixture.Load("openai-usage-page.txt"), Now);
        Assert.Equal(1, usage.ManualResets!.AvailableCount);
        Assert.Equal("Manual resets: 1 (exp 06/11)", usage.ManualResets.DisplayLine());
    }

    [Fact]
    public void TwoExpiriesAreCommaSeparated()
    {
        var resets = new ManualResets(2, new[]
        {
            new ManualResetCredit(DateTimeOffset.Parse("2026-11-12T17:00:00Z").ToUnixTimeMilliseconds(), "available"),
            new ManualResetCredit(DateTimeOffset.Parse("2026-12-18T17:00:00Z").ToUnixTimeMilliseconds(), "available")
        });
        Assert.Equal("Manual resets: 2 (exp 12/11, 18/12)", resets.DisplayLine());
    }
}
