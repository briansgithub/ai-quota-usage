using System.Net.Sockets;
using System.Text.Json;
using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class FetchErrorClassifierTest
{
    private const long Now = 1_783_000_000_000L;

    [Fact]
    public void ClassifiesNetworkTimeoutHttpAuthAndParse()
    {
        Assert.Equal(FetchOutcome.Network, FetchErrorClassifier.Classify(new SocketException((int)SocketError.HostNotFound)).Outcome);
        Assert.Equal(FetchOutcome.Network, FetchErrorClassifier.Classify(new SocketException((int)SocketError.ConnectionRefused)).Outcome);
        Assert.Equal(FetchOutcome.Timeout, FetchErrorClassifier.Classify(new TimeoutException("timeout")).Outcome);
        Assert.Equal(FetchOutcome.AuthExpired, FetchErrorClassifier.Classify(new SessionExpiredException(401, "https://cursor.com/login")).Outcome);
        var http = FetchErrorClassifier.Classify(new ProviderHttpException(503, "unavailable", "https://chatgpt.com/backend-api/wham/usage"));
        Assert.Equal(FetchOutcome.Http, http.Outcome);
        Assert.Equal(503, http.HttpStatus);
        Assert.Equal(FetchOutcome.Parse, FetchErrorClassifier.Classify(new JsonException("unexpected")).Outcome);
        Assert.Equal(FetchOutcome.MissingFields, FetchErrorClassifier.Classify(new ArgumentException("Cursor usage-summary is missing plan percentages")).Outcome);
        Assert.Equal(FetchOutcome.Parse, FetchErrorClassifier.Classify(new ArgumentException("Could not decode usage windows")).Outcome);
        Assert.Equal(FetchOutcome.MissingFields, FetchErrorClassifier.Classify(new ArgumentException("Could not find ChatGPT 5-hour/weekly limits in page text")).Outcome);
    }

    [Fact]
    public void AttemptShortReasonsMatchProductCopy()
    {
        var http = FetchErrorClassifier.Attempt(
            new ProviderHttpException(503, "nope", "https://chatgpt.com/backend-api/wham/usage"),
            SoftwareSectionId.Codex,
            "https://chatgpt.com/backend-api/wham/usage",
            Now);
        Assert.Equal("HTTP 503", http.ShortReason);
        Assert.Equal("https://chatgpt.com/backend-api/wham/usage", http.Endpoint);

        var auth = FetchErrorClassifier.Attempt(
            new SessionExpiredException(401, "https://cursor.com/api/usage-summary"),
            SoftwareSectionId.Cursor,
            "https://cursor.com/api/usage-summary",
            Now);
        Assert.Equal("Sign-in expired", auth.ShortReason);

        var net = FetchErrorClassifier.Attempt(
            new SocketException((int)SocketError.HostNotFound),
            SoftwareSectionId.GrokBot,
            "https://cursor.com/api/dashboard/get-sand-usage-status",
            Now);
        Assert.Equal("Network error", net.ShortReason);
        Assert.DoesNotContain("WorkosCursorSessionToken", net.Message ?? "");
    }
}
