using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace UsageWidget;

public static class GeminiOAuth
{
    public const string ClientId = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
    public const string TokenEndpoint = "https://oauth2.googleapis.com/token";
    public const string CredentialReference = "https://raw.githubusercontent.com/PowerUserZ/OpenTokenUsage/main/docs/providers/antigravity.md";
    public const string StorageKey = "gemini-oauth.invalid";
    public const string UserAgent = "antigravity/2.34.0 (aidev_client; os_type=windows; arch=amd64)";

    // The installed-app credential is published upstream. Resolve only in memory; never log or persist it.
    public static string? PublicClientCredential(HttpClient http)
    {
        try
        {
            var reference = http.GetStringAsync(CredentialReference).GetAwaiter().GetResult();
            if (reference.Length > 100_000 || !reference.Contains("client_id=" + ClientId)) return null;
            var match = Regex.Match(reference, @"client_secret=(GOCSPX-[A-Za-z0-9_-]+)");
            return match.Success ? match.Groups[1].Value : null;
        }
        catch { return null; }
    }
}

public sealed class GeminiUsageClient(ICookieStore store, string? credentialRoot = null, HttpClient? http = null,
    Func<HttpClient, string?>? publicCredential = null)
{
    private static readonly HttpClient DefaultHttp = new(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(10) };
    private readonly HttpClient _http = http ?? DefaultHttp;

    private JsonObject? ReadCredentials()
    {
        if (CookieUtil.CookieMap(store.GetHeader(GeminiOAuth.StorageKey)).GetValueOrDefault("disabled") == "1") return null;
        var cachedText = CookieUtil.CookieMap(store.GetHeader(GeminiOAuth.StorageKey)).GetValueOrDefault("oauth");
        JsonObject? cached = null;
        if (cachedText is not null)
        {
            try { cached = GeminiUsageParser.Object(Encoding.UTF8.GetString(Convert.FromBase64String(cachedText))); }
            catch { /* Try the original read-only source. */ }
        }
        var root = credentialRoot ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".gemini");
        try
        {
            var files = new List<string> { Path.Combine(root, "oauth_creds.json") };
            if (Directory.Exists(root))
                foreach (var directory in Directory.EnumerateDirectories(root, "antigravity*"))
                    files.AddRange(Directory.EnumerateFiles(directory, "*.json", new EnumerationOptions { RecurseSubdirectories = true, MaxRecursionDepth = 2, IgnoreInaccessible = true }).Take(100));
            foreach (var file in files)
            {
                try
                {
                    if (!File.Exists(file) || new FileInfo(file).Length > 1_000_000) continue;
                    var token = FindToken(GeminiUsageParser.Object(File.ReadAllText(file)));
                    if (token is not null)
                    {
                        // A cached token must follow account changes in the read-only source.
                        var sourceRefresh = token.Str("refresh_token");
                        return sourceRefresh is not null && sourceRefresh == cached?.Str("refresh_token") ? cached : token;
                    }
                }
                catch { /* One malformed file must not hide other candidates. */ }
            }
        }
        catch { /* Missing or inaccessible credentials means signed out. */ }
        return null;
    }

    private static JsonObject? FindToken(JsonNode? node)
    {
        if (node is JsonObject obj)
        {
            var access = obj.Str("access_token", "accessToken");
            var refresh = obj.Str("refresh_token", "refreshToken");
            if (access is not null || refresh is not null)
                return new JsonObject { ["access_token"] = access, ["refresh_token"] = refresh,
                    ["expiry_date"] = obj.Num("expiry_date", "expires_at", "expiry_timestamp"), ["expiry"] = obj.Str("expiry", "expiresAt") };
            foreach (var value in obj.Select(kv => kv.Value)) if (FindToken(value) is { } found) return found;
        }
        if (node is JsonArray arr) foreach (var value in arr) if (FindToken(value) is { } found) return found;
        return null;
    }

    public ProviderResult Fetch(long now)
    {
        try
        {
            var credentials = ReadCredentials();
            if (credentials is null) return new ProviderResult.NeedsSignIn("Sign in to Antigravity on this PC, then refresh.");
            var access = credentials.Str("access_token");
            var expiry = credentials.Num("expiry_date");
            if (expiry is > 0 and < 100_000_000_000) expiry *= 1000;
            if (expiry is null && DateTimeOffset.TryParse(credentials.Str("expiry"), out var date)) expiry = date.ToUnixTimeMilliseconds();
            var refreshed = false;
            if (access is null || expiry <= now + 60_000)
            {
                access = Refresh(credentials, now);
                refreshed = true;
            }
            if (access is null) return new ProviderResult.NeedsSignIn("Antigravity session expired. Sign in again on this PC.");
            var client = Client(access);
            var snapshot = client.Fetch();
            if (snapshot is null && client.AuthRejected && !refreshed && Refresh(credentials, now) is { } renewed)
                snapshot = Client(renewed).Fetch();
            return snapshot is null ? new ProviderResult.Failure("Gemini quota unavailable. Try again later.", "cloud-code") : new ProviderResult.Success(snapshot);
        }
        catch { return new ProviderResult.Failure("Gemini quota unavailable. Try again later.", "cloud-code"); }
    }

    private GeminiQuotaClient Client(string access) => new((host, method, body, seconds) =>
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, host + "/v1internal:" + method);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", access);
        request.Headers.TryAddWithoutValidation("User-Agent", GeminiOAuth.UserAgent);
        request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(seconds));
        using var response = _http.SendAsync(request, timeout.Token).GetAwaiter().GetResult();
        return new((int)response.StatusCode, response.Content.ReadAsStringAsync(timeout.Token).GetAwaiter().GetResult());
    });

    private string? Refresh(JsonObject credentials, long now)
    {
        var refresh = credentials.Str("refresh_token");
        if (refresh is null) return null;
        var secret = (publicCredential ?? GeminiOAuth.PublicClientCredential)(_http);
        if (secret is null) return null;
        using var body = new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["client_id"] = GeminiOAuth.ClientId, ["client_secret"] = secret,
            ["grant_type"] = "refresh_token", ["refresh_token"] = refresh
        });
        using var response = _http.PostAsync(GeminiOAuth.TokenEndpoint, body).GetAwaiter().GetResult();
        if (!response.IsSuccessStatusCode) return null;
        var token = GeminiUsageParser.Object(response.Content.ReadAsStringAsync().GetAwaiter().GetResult());
        var access = token.Str("access_token");
        if (access is null) return null;
        var saved = new JsonObject { ["access_token"] = access, ["refresh_token"] = token.Str("refresh_token") ?? refresh,
            ["expiry_date"] = now + (token.Num("expires_in") ?? 3600) * 1000 };
        store.SaveCookies(GeminiOAuth.StorageKey, "oauth=" + Convert.ToBase64String(Encoding.UTF8.GetBytes(saved.ToJsonString())));
        return access;
    }
}
