using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class UsageDumpTest
{
    [Fact]
    public void RedactsIdentityAndTokensButKeepsUsageFields()
    {
        var raw = """
            {
              "email": "brian@example.com",
              "user_id": "user_secret",
              "account_id": "acct_LIVEVALUE",
              "accessToken": "eyJhbGciOi.payload.sig",
              "rate_limit": {
                "primary_window": { "used_percent": 0.5, "limit_window_seconds": 18000, "reset_at": 1793910026 }
              }
            }
            """;
        var redacted = UsageDump.RedactJson(raw);
        Assert.DoesNotContain("brian@example.com", redacted);
        Assert.DoesNotContain("user_secret", redacted);
        Assert.DoesNotContain("acct_LIVEVALUE", redacted);
        Assert.DoesNotContain("eyJhbGciOi", redacted);
        Assert.Contains("[redacted]", redacted);
        Assert.Contains("0.5", redacted);
        Assert.Contains("18000", redacted);
        Assert.Contains("1793910026", redacted);
    }

    [Fact]
    public void FormatListsParsedMetersAndSanitizedJson()
    {
        var snapshot = new UsageSnapshot(
            1_783_000_000_000L,
            Providers: new[]
            {
                new ProviderSnapshot(
                    "openai",
                    "Codex",
                    "OK",
                    Meters: new[]
                    {
                        new UsageMeter("openai.weekly", "Weekly", UsedPercent: 8.0, RemainingPercent: 92.0)
                    },
                    DiagnosticJson: UsageDump.OpenAiPayload(
                        """{"email":"hide@me","rate_limit":{"primary_window":{"used_percent":8}}}"""))
            });
        var text = UsageDump.Format(snapshot, 1_783_000_000_000L + 60_000L);
        Assert.Contains("openai.weekly", text);
        Assert.Contains("used=8", text);
        Assert.Contains("8% used", text);
        Assert.Contains("whamUsage", text);
        Assert.DoesNotContain("hide@me", text);
        Assert.DoesNotContain("openai.five_hour", text);
    }
}
