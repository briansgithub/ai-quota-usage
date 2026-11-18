using System.Text.Json;
using System.Text.Json.Serialization;

namespace UsageWidget;

public sealed class UsageRepository
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    private readonly ICookieStore _cookies;
    private readonly AuthenticatedHttp _http;
    private readonly CursorUsageClient _cursor;
    private readonly OpenAiUsageClient _openai;
    private readonly IHtmlExtractor _html;
    private readonly object _gate = new();
    private UsageSnapshot _snapshot;
    private bool _refreshing;

    public UsageRepository(ICookieStore cookies, IHtmlExtractor? html = null, AuthenticatedHttp? http = null)
    {
        _cookies = cookies;
        _http = http ?? new AuthenticatedHttp(cookies);
        _cursor = new CursorUsageClient(_http, cookies);
        _openai = new OpenAiUsageClient(_http, cookies);
        _html = html ?? new NullHtmlExtractor();
        _snapshot = LoadCached() ?? UsageSnapshot.Empty(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    public event Action<UsageSnapshot>? SnapshotChanged;
    public event Action<bool>? RefreshingChanged;
    public event Action<UsageSnapshot, UsageSnapshot, long>? Refreshed;

    public UsageSnapshot Snapshot { get { lock (_gate) return _snapshot; } }
    public bool Refreshing { get { lock (_gate) return _refreshing; } }
    public bool HasSession(ProviderId provider) => _cookies.HasSession(provider);
    public ICookieStore Cookies => _cookies;

    public UsageSnapshot Refresh(bool allowHtmlFallback = true)
    {
        lock (_gate)
        {
            _refreshing = true;
        }
        RefreshingChanged?.Invoke(true);
        UsageSnapshot latest;
        UsageSnapshot previous;
        long now;
        try
        {
            previous = Snapshot;
            now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var cursorResult = FetchProvider(ProviderId.Cursor, allowHtmlFallback, now);
            var openaiResult = FetchProvider(ProviderId.OpenAi, allowHtmlFallback, now);
            var combined = SnapshotMerger.Merge(previous, cursorResult, openaiResult, now, _cookies.HasSession(ProviderId.Cursor), _cookies.HasSession(ProviderId.OpenAi));
            Persist(combined);
            lock (_gate) _snapshot = combined;
            SnapshotChanged?.Invoke(combined);
            Refreshed?.Invoke(previous, combined, now);
            latest = combined;
        }
        finally
        {
            lock (_gate) _refreshing = false;
            RefreshingChanged?.Invoke(false);
        }
        return latest;
    }

    public Task<UsageSnapshot> RefreshAsync(bool allowHtmlFallback = true) =>
        Task.Run(() => Refresh(allowHtmlFallback));

    public void SignOut(ProviderId provider)
    {
        _cookies.Clear(provider);
        var updated = SnapshotMerger.ClearForSignOut(Snapshot, provider);
        Persist(updated);
        lock (_gate) _snapshot = updated;
        SnapshotChanged?.Invoke(updated);
    }

    public bool ImportCookies(ProviderId provider, string raw)
    {
        var ok = _cookies.ImportRawCookies(provider, raw);
        return ok;
    }

    private ProviderResult FetchProvider(ProviderId provider, bool allowHtmlFallback, long now)
    {
        var result = provider == ProviderId.Cursor ? _cursor.Fetch(now) : _openai.Fetch(now);
        if (result is ProviderResult.Success or ProviderResult.NeedsSignIn) return result;
        if (result is ProviderResult.Failure failure && allowHtmlFallback && _cookies.HasSession(provider))
        {
            try
            {
                var text = _html.ExtractPageTextAsync(provider).GetAwaiter().GetResult();
                if (provider == ProviderId.Cursor)
                {
                    var snapshot = _cursor.SnapshotFromHtml(text, now);
                    return new ProviderResult.Success(snapshot, failure.AttemptList.Concat(_cursor.HtmlAttempts(now, snapshot)).ToList());
                }
                var openaiSnap = _openai.SnapshotFromHtml(text, now);
                return new ProviderResult.Success(openaiSnap, failure.AttemptList.Append(_openai.HtmlAttempt(now)).ToList());
            }
            catch (Exception htmlError)
            {
                var htmlAttempts = HtmlFallbackAttempts(provider, htmlError, now);
                return new ProviderResult.Failure(failure.Message, "html", failure.AttemptList.Concat(htmlAttempts).ToList(), failure.Snapshot);
            }
        }
        return result;
    }

    private static IReadOnlyList<FetchAttempt> HtmlFallbackAttempts(ProviderId provider, Exception error, long now) =>
        provider == ProviderId.Cursor
            ? new[]
            {
                FetchErrorClassifier.Attempt(error, SoftwareSectionId.Cursor, CursorEndpoints.SpendingPage, now, "html"),
                FetchErrorClassifier.Attempt(error, SoftwareSectionId.GrokBot, CursorEndpoints.SpendingPage, now, "html")
            }
            : new[]
            {
                FetchErrorClassifier.Attempt(error, SoftwareSectionId.Codex, OpenAiEndpoints.UsagePage, now, "html")
            };

    private void Persist(UsageSnapshot snapshot)
    {
        try { _cookies.SaveSnapshotJson(JsonSerializer.Serialize(snapshot, JsonOptions)); }
        catch { /* ignore */ }
    }

    private UsageSnapshot? LoadCached()
    {
        var raw = _cookies.SnapshotJson();
        if (string.IsNullOrWhiteSpace(raw)) return null;
        try { return JsonSerializer.Deserialize<UsageSnapshot>(raw, JsonOptions); }
        catch { return null; }
    }
}
