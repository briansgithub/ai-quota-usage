using System.Net;
using System.Text;
using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class GeminiAuthTest
{
    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void ExpiryOr401RefreshesIntoAppStoreWithoutChangingSource(bool expired)
    {
        var root = Path.Combine(Path.GetTempPath(), "gemini-unit-" + Guid.NewGuid());
        Directory.CreateDirectory(Path.Combine(root, "antigravity-test"));
        var path = Path.Combine(root, "antigravity-test", "oauth_creds.json");
        var source = "{\"access_token\":\"synthetic-access\",\"refresh_token\":\"synthetic-refresh\",\"expiry_date\":" + (expired ? 1 : 9_000_000_000_000L) + "}";
        File.WriteAllText(path, source);
        try
        {
            var store = new MemoryCookieStore();
            var refreshCalls = 0;
            var headers = new List<(string Agent, string? ContentType)>();
            using var http = new HttpClient(new Handler(request =>
            {
                if (request.RequestUri!.AbsoluteUri == GeminiOAuth.TokenEndpoint)
                {
                    refreshCalls++;
                    return new(HttpStatusCode.OK) { Content = new StringContent("{\"access_token\":\"synthetic-renewed\",\"expires_in\":3600}") };
                }
                headers.Add((request.Headers.UserAgent.ToString(), request.Content?.Headers.ContentType?.MediaType));
                var renewed = request.Headers.Authorization?.Parameter == "synthetic-renewed";
                return new(renewed ? HttpStatusCode.OK : HttpStatusCode.Unauthorized)
                { Content = new StringContent(renewed ? Fixture.Load("gemini-production.json") : Fixture.Load("gemini-failure.json")) };
            }));
            var client = new GeminiUsageClient(store, root, http, _ => "synthetic-public-client-credential");
            Assert.IsType<ProviderResult.Success>(client.Fetch(2_000_000_000_000L));
            Assert.Equal(1, refreshCalls);
            Assert.All(headers, header => {
                Assert.Equal(GeminiOAuth.UserAgent, header.Agent);
                Assert.Equal("application/json", header.ContentType);
            });
            Assert.Equal(source, File.ReadAllText(path));
            Assert.NotNull(store.GetHeader(GeminiOAuth.StorageKey));
            Assert.IsType<ProviderResult.Success>(client.Fetch(2_000_000_001_000L));
            Assert.Equal(1, refreshCalls);
        }
        finally { Directory.Delete(root, true); }
    }

    private sealed class Handler(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => Task.FromResult(respond(request));
    }
}
