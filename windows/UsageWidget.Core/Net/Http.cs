using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace UsageWidget;

public static class AppHeaders
{
    public const string UserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
}

public sealed record HttpJsonResult(int Code, string Body, string FinalUrl, IReadOnlyList<string> SetCookies)
{
    public bool IsSuccessful => Code is >= 200 and <= 299;
}

public sealed class SessionExpiredException : Exception
{
    public int Code { get; }
    public string Url { get; }
    public SessionExpiredException(int code, string url) : base($"Session expired (HTTP {code})")
    {
        Code = code;
        Url = url;
    }
}

public sealed class ProviderHttpException : Exception
{
    public int Code { get; }
    public string Snippet { get; }
    public string Url { get; }
    public ProviderHttpException(int code, string snippet, string url) : base($"HTTP {code} from {url}")
    {
        Code = code;
        Snippet = snippet;
        Url = url;
    }
}

public interface ICookieStore
{
    string? GetHeader(string host);
    bool HasSession(ProviderId provider);
    void SaveCookies(string host, string cookieHeader);
    void ApplySetCookieHeaders(string host, IEnumerable<string> setCookieHeaders);
    void Clear(ProviderId provider);
    bool ImportRawCookies(ProviderId provider, string raw);
    string? SnapshotJson();
    void SaveSnapshotJson(string json);
}

public static class CookieUtil
{
    public static Dictionary<string, string> CookieMap(string? header)
    {
        var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (string.IsNullOrWhiteSpace(header)) return map;
        foreach (var part in header.Split(';'))
        {
            var trimmed = part.Trim();
            if (trimmed.Length == 0 || !trimmed.Contains('=')) continue;
            var name = trimmed.Split('=')[0].Trim();
            var value = trimmed[(trimmed.IndexOf('=') + 1)..].Trim();
            if (name.Length > 0) map[name] = value;
        }
        return map;
    }

    public static string MergeCookieHeaders(string? existing, string incoming)
    {
        var map = CookieMap(existing);
        foreach (var kv in CookieMap(incoming)) map[kv.Key] = kv.Value;
        return string.Join("; ", map.Select(kv => $"{kv.Key}={kv.Value}"));
    }

    public static string[] HostsFor(ProviderId provider) => provider switch
    {
        ProviderId.Cursor => CursorEndpoints.CookieHosts,
        ProviderId.OpenAi => OpenAiEndpoints.CookieHosts,
        ProviderId.Claude => ClaudeEndpoints.CookieHosts,
        _ => Array.Empty<string>()
    };
}

public sealed class MemoryCookieStore : ICookieStore
{
    private readonly Dictionary<string, string> _cookies = new(StringComparer.OrdinalIgnoreCase);
    private string? _snapshot;

    public string? GetHeader(string host)
    {
        var key = host.StartsWith("www.", StringComparison.OrdinalIgnoreCase) ? host[4..] : host;
        return _cookies.GetValueOrDefault(key);
    }

    public bool HasSession(ProviderId provider)
    {
        foreach (var host in CookieUtil.HostsFor(provider))
        {
            var header = GetHeader(host);
            var map = CookieUtil.CookieMap(header);
            if (provider == ProviderId.Cursor &&
                map.Keys.Any(k => k.Equals(CursorEndpoints.SessionCookie, StringComparison.OrdinalIgnoreCase)))
                return true;
            if (provider == ProviderId.OpenAi &&
                map.Keys.Any(k => k.StartsWith(OpenAiEndpoints.SessionCookiePrefix, StringComparison.OrdinalIgnoreCase)))
                return true;
            if (provider == ProviderId.Claude &&
                map.Any(kv => kv.Key.Equals(ClaudeEndpoints.SessionCookie, StringComparison.OrdinalIgnoreCase) && kv.Value.Length > 0))
                return true;
        }
        return false;
    }

    public void SaveCookies(string host, string cookieHeader)
    {
        var cleaned = cookieHeader.Trim();
        if (cleaned.Length == 0) return;
        var key = host.StartsWith("www.", StringComparison.OrdinalIgnoreCase) ? host[4..] : host;
        _cookies[key] = CookieUtil.MergeCookieHeaders(GetHeader(key), cleaned);
    }

    public void ApplySetCookieHeaders(string host, IEnumerable<string> setCookieHeaders)
    {
        var current = CookieUtil.CookieMap(GetHeader(host));
        foreach (var line in setCookieHeaders)
        {
            var pair = line.Split(';')[0];
            var eq = pair.IndexOf('=');
            if (eq <= 0) continue;
            current[pair[..eq].Trim()] = pair[(eq + 1)..].Trim();
        }
        if (current.Count > 0)
            SaveCookies(host, string.Join("; ", current.Select(kv => $"{kv.Key}={kv.Value}")));
    }

    public void Clear(ProviderId provider)
    {
        foreach (var host in CookieUtil.HostsFor(provider))
        {
            var key = host.StartsWith("www.", StringComparison.OrdinalIgnoreCase) ? host[4..] : host;
            _cookies.Remove(key);
        }
    }

    public bool ImportRawCookies(ProviderId provider, string raw)
    {
        var parsed = SessionPaste.Parse(provider, raw);
        if (parsed.Count == 0) return false;
        var primary = CookieUtil.HostsFor(provider)[0];
        SaveCookies(primary, string.Join("; ", parsed.Select(kv => $"{kv.Key}={kv.Value}")));
        return HasSession(provider);
    }

    public string? SnapshotJson() => _snapshot;
    public void SaveSnapshotJson(string json) => _snapshot = json;
}

public static class SessionPaste
{
    public static IReadOnlyDictionary<string, string> Parse(ProviderId provider, string raw)
    {
        var text = raw.Trim().Trim('"');
        if (text.StartsWith("Cookie:", StringComparison.OrdinalIgnoreCase))
            text = text[(text.IndexOf(':') + 1)..].Trim();
        if (text.Length == 0) return new Dictionary<string, string>();
        if (!text.Contains('='))
            return new Dictionary<string, string> { [DefaultCookieName(provider)] = text };
        return CookieUtil.CookieMap(text)
            .Where(kv => kv.Key.Length > 0 && kv.Value.Length > 0)
            .ToDictionary(kv => kv.Key, kv => kv.Value, StringComparer.OrdinalIgnoreCase);
    }

    public static string DefaultCookieName(ProviderId provider) => provider switch
    {
        ProviderId.Cursor => CursorEndpoints.SessionCookie,
        ProviderId.OpenAi => OpenAiEndpoints.SessionCookiePrefix,
        ProviderId.Claude => ClaudeEndpoints.SessionCookie,
        _ => "session"
    };
}

public sealed class AuthenticatedHttp : IDisposable
{
    private readonly ICookieStore _cookies;
    private readonly HttpClient _client;

    public AuthenticatedHttp(ICookieStore cookies, HttpMessageHandler? handler = null)
    {
        _cookies = cookies;
        var inner = handler ?? new HttpClientHandler
        {
            AllowAutoRedirect = true,
            AutomaticDecompression = DecompressionMethods.All,
            UseCookies = false
        };
        _client = new HttpClient(inner, disposeHandler: handler is null)
        {
            Timeout = TimeSpan.FromSeconds(25)
        };
    }

    public HttpJsonResult GetJson(string url, string cookieHost, IDictionary<string, string>? extraHeaders = null) =>
        Execute(HttpMethod.Get, url, cookieHost, extraHeaders, null);

    public HttpJsonResult PostJson(string url, string cookieHost, string jsonBody = "{}", IDictionary<string, string>? extraHeaders = null) =>
        Execute(HttpMethod.Post, url, cookieHost, extraHeaders, jsonBody);

    private HttpJsonResult Execute(HttpMethod method, string url, string cookieHost, IDictionary<string, string>? extraHeaders, string? jsonBody)
    {
        using var request = new HttpRequestMessage(method, url);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        request.Headers.TryAddWithoutValidation("User-Agent", AppHeaders.UserAgent);
        request.Headers.TryAddWithoutValidation("Accept-Language", "en-US,en;q=0.9");
        request.Headers.TryAddWithoutValidation("Cache-Control", "no-cache, no-store");
        request.Headers.TryAddWithoutValidation("Pragma", "no-cache");
        var cookie = _cookies.GetHeader(cookieHost);
        if (!string.IsNullOrWhiteSpace(cookie))
            request.Headers.TryAddWithoutValidation("Cookie", cookie);
        if (extraHeaders is not null)
        {
            foreach (var kv in extraHeaders)
                request.Headers.TryAddWithoutValidation(kv.Key, kv.Value);
        }
        if (jsonBody is not null)
            request.Content = new StringContent(jsonBody, Encoding.UTF8, "application/json");

        HttpResponseMessage response;
        try
        {
            response = _client.Send(request);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or TimeoutException)
        {
            throw;
        }

        using (response)
        {
            var body = response.Content.ReadAsStringAsync().GetAwaiter().GetResult();
            var setCookies = response.Headers.TryGetValues("Set-Cookie", out var values)
                ? values.ToList()
                : new List<string>();
            if (setCookies.Count > 0)
                _cookies.ApplySetCookieHeaders(cookieHost, setCookies);
            var finalUrl = response.RequestMessage?.RequestUri?.ToString() ?? url;
            var result = new HttpJsonResult((int)response.StatusCode, body, finalUrl, setCookies);
            if (HttpBodies.IsUnauthorizedPayload(result.Body, result.Code, result.FinalUrl))
                throw new SessionExpiredException(result.Code, result.FinalUrl);
            if (!result.IsSuccessful)
                throw new ProviderHttpException(result.Code, Summarize(result.Body), result.FinalUrl);
            if (!HttpBodies.LooksLikeJson(result.Body))
            {
                if (HttpBodies.LooksLikeHtml(result.Body))
                    throw new SessionExpiredException(result.Code, result.FinalUrl);
                throw new ProviderHttpException(result.Code, "Non-JSON response", result.FinalUrl);
            }
            return result;
        }
    }

    private static string Summarize(string body) => body.Replace('\n', ' ').Trim()[..Math.Min(160, body.Replace('\n', ' ').Trim().Length)];

    public void Dispose() => _client.Dispose();
}

public sealed record ClassifiedError(FetchOutcome Outcome, int? HttpStatus = null, string? Message = null);

public static class FetchErrorClassifier
{
    public static FetchAttempt Attempt(
        Exception error,
        SoftwareSectionId sectionId,
        string? endpoint,
        long atEpochMs,
        string? source = "json")
    {
        var classified = Classify(error);
        return FetchAttempt.Of(atEpochMs, sectionId, classified.Outcome, classified.HttpStatus, classified.Message, endpoint, source);
    }

    public static ClassifiedError Classify(Exception error)
    {
        for (Exception? current = error; current is not null; current = current.InnerException)
        {
            switch (current)
            {
                case SessionExpiredException expired:
                    return new ClassifiedError(FetchOutcome.AuthExpired, expired.Code > 0 ? expired.Code : null, DiagnosticSanitizer.Message(expired.Message));
                case ProviderHttpException http:
                    return new ClassifiedError(FetchOutcome.Http, http.Code, DiagnosticSanitizer.Message(http.Message));
                case TaskCanceledException or TimeoutException or SocketException { SocketErrorCode: SocketError.TimedOut }:
                    return new ClassifiedError(FetchOutcome.Timeout, Message: DiagnosticSanitizer.Message(current.Message));
                case HttpRequestException httpEx:
                    if (httpEx.InnerException is SocketException or TimeoutException)
                        return new ClassifiedError(FetchOutcome.Network, Message: DiagnosticSanitizer.Message(current.Message));
                    if (IsTimeoutMessage(httpEx.Message))
                        return new ClassifiedError(FetchOutcome.Timeout, Message: DiagnosticSanitizer.Message(current.Message));
                    return new ClassifiedError(FetchOutcome.Network, Message: DiagnosticSanitizer.Message(current.Message));
                case SocketException:
                    return new ClassifiedError(FetchOutcome.Network, Message: DiagnosticSanitizer.Message(current.Message));
                case JsonException:
                    return new ClassifiedError(FetchOutcome.Parse, Message: DiagnosticSanitizer.Message(current.Message));
                case ArgumentException arg:
                    var message = arg.Message ?? "";
                    var outcome = message.Contains("missing", StringComparison.OrdinalIgnoreCase)
                        || message.Contains("could not find", StringComparison.OrdinalIgnoreCase)
                        ? FetchOutcome.MissingFields
                        : FetchOutcome.Parse;
                    return new ClassifiedError(outcome, Message: DiagnosticSanitizer.Message(message));
            }
        }
        var text = error.Message ?? "";
        if (text.Contains("accessToken", StringComparison.OrdinalIgnoreCase) ||
            text.Contains("not_authenticated", StringComparison.OrdinalIgnoreCase))
            return new ClassifiedError(FetchOutcome.AuthExpired, Message: DiagnosticSanitizer.Message(text));
        return new ClassifiedError(FetchOutcome.Unknown, Message: DiagnosticSanitizer.Message(text));
    }

    private static bool IsTimeoutMessage(string message) =>
        message.Contains("timeout", StringComparison.OrdinalIgnoreCase) ||
        message.Contains("timed out", StringComparison.OrdinalIgnoreCase);
}

public interface IHtmlExtractor
{
    Task<string> ExtractPageTextAsync(ProviderId provider, CancellationToken cancellationToken = default);
}

public sealed class NullHtmlExtractor : IHtmlExtractor
{
    public Task<string> ExtractPageTextAsync(ProviderId provider, CancellationToken cancellationToken = default) =>
        Task.FromException<string>(new InvalidOperationException("HTML fallback is not available"));
}
