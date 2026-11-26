using System.Text.Json.Nodes;

namespace UsageWidget;

/// <summary>
/// claude.ai web usage (the numbers on <c>claude.ai/settings/usage</c>). Undocumented:
/// <c>GET /api/organizations</c> lists orgs (<c>uuid</c>), then
/// <c>GET /api/organizations/{uuid}/usage</c> returns
/// <c>five_hour</c> / <c>seven_day</c> / <c>seven_day_opus</c> / <c>seven_day_sonnet</c>, each
/// <c>{ "utilization": 0–100, "resets_at": ISO-8601 | null }</c> or null. Auth is the
/// <c>sessionKey</c> cookie on claude.ai.
/// </summary>
public static class ClaudeEndpoints
{
    public const string Origin = "https://claude.ai";
    public const string UsagePage = "https://claude.ai/settings/usage";
    public const string LoginPage = "https://claude.ai/login?returnTo=%2Fsettings%2Fusage";
    public const string Organizations = "https://claude.ai/api/organizations";
    public const string SessionCookie = "sessionKey";
    public const string LastActiveOrgCookie = "lastActiveOrg";
    public static readonly string[] CookieHosts = { "claude.ai" };

    /// <summary><c>cedar_ember=1</c> adds the full-limit-reset grants the usage page shows.</summary>
    public static string Usage(string orgId) => $"https://claude.ai/api/organizations/{Uri.EscapeDataString(orgId)}/usage?cedar_ember=1";
}

public static class ClaudeMeterIds
{
    public const string FiveHour = "claude.five_hour";
    public const string Weekly = "claude.weekly";
    public const string WeeklyOpus = "claude.weekly_opus";
    public const string WeeklySonnet = "claude.weekly_sonnet";
    public const long FiveHourMs = 5L * 60 * 60 * 1000;
    public const long WeekMs = 7L * 24 * 60 * 60 * 1000;
}

public static class ClaudeUsageParser
{
    /// <summary>
    /// Picks the org to read usage for: the <c>lastActiveOrg</c> cookie when it is in the
    /// list, else the first org with the <c>chat</c> capability, else the first org.
    /// </summary>
    public static string ParseOrgId(string organizationsRaw, string? preferredOrgId = null)
    {
        var node = JsonNode.Parse(organizationsRaw);
        var arr = node as JsonArray
            ?? (node as JsonObject)?["organizations"] as JsonArray
            ?? throw new ArgumentException("claude.ai organizations response is missing the org list");
        var orgs = arr.OfType<JsonObject>()
            .Select(o => (Obj: o, Id: o.Str("uuid", "id")))
            .Where(o => !string.IsNullOrWhiteSpace(o.Id))
            .ToList();
        if (orgs.Count == 0) throw new ArgumentException("claude.ai organizations response is missing an org uuid");
        if (!string.IsNullOrWhiteSpace(preferredOrgId))
        {
            var match = orgs.FirstOrDefault(o => string.Equals(o.Id, preferredOrgId, StringComparison.OrdinalIgnoreCase));
            if (match.Id is not null) return match.Id!;
        }
        var chat = orgs.FirstOrDefault(o =>
            (o.Obj["capabilities"] as JsonArray)?.Any(c => string.Equals(c?.ToString(), "chat", StringComparison.OrdinalIgnoreCase)) == true);
        return (chat.Id ?? orgs[0].Id)!;
    }

    public static ProviderSnapshot ParseUsage(string usageRaw, long fetchedAtMs, string source = "json")
    {
        var root = JsonNav.ParseObject(usageRaw);
        var meters = new List<UsageMeter>();
        AddWindow(meters, root.Obj("five_hour", "fiveHour"), ClaudeMeterIds.FiveHour, "Session", ClaudeMeterIds.FiveHourMs, fetchedAtMs);
        AddWindow(meters, root.Obj("seven_day", "sevenDay"), ClaudeMeterIds.Weekly, "Weekly", ClaudeMeterIds.WeekMs, fetchedAtMs);
        AddWindow(meters, root.Obj("seven_day_opus", "sevenDayOpus"), ClaudeMeterIds.WeeklyOpus, "Weekly · Opus", ClaudeMeterIds.WeekMs, fetchedAtMs, optional: true);
        AddWindow(meters, root.Obj("seven_day_sonnet", "sevenDaySonnet"), ClaudeMeterIds.WeeklySonnet, "Weekly · Sonnet", ClaudeMeterIds.WeekMs, fetchedAtMs, optional: true);
        if (meters.All(m => m.Id != ClaudeMeterIds.FiveHour && m.Id != ClaudeMeterIds.Weekly))
            throw new ArgumentException("claude.ai usage is missing five_hour and seven_day");
        return new ProviderSnapshot(
            ProviderId.Claude.Wire(), ProviderId.Claude.DisplayName(), "OK", null, source, null,
            meters, ManualResetParser.FromClaudeRoot(root, fetchedAtMs), UsageDump.ClaudePayload(usageRaw));
    }

    public static long RoundToMinute(long ms) => (ms + 30_000L) / 60_000L * 60_000L;

    /// <summary>
    /// Optional per-model weekly meters are shown only once they have usage, so an idle
    /// "Opus 0%" bar does not crowd the card.
    /// </summary>
    private static void AddWindow(List<UsageMeter> meters, JsonObject? obj, string id, string title, long lengthMs, long fetchedAtMs, bool optional = false)
    {
        if (obj is null) return;
        var used = obj.Num("utilization", "used_percent", "usedPercent");
        // resets_at's fraction changes on every request (18:00:00.951556, then .497762) while
        // the window does not; round to the minute so pacing / quota alerts do not repeat.
        var reset = (NyTime.ParseTimestamp(obj.Str("resets_at", "resetsAt"), fetchedAtMs)
            ?? NyTime.ParseTimestamp(obj.Num("resets_at", "resetsAt"), fetchedAtMs))?.Let(RoundToMinute);
        if (used is null && reset is null) return;
        if (optional && (used is null or <= 0)) return;
        var pct = used is { } u ? Math.Clamp(Percent.AsWirePercent(u), 0, 100) : (double?)null;
        // An idle window (0%, no reset yet) has not started; leave pace bounds unknown.
        long? start = reset is { } r ? r - lengthMs : null;
        meters.Add(new UsageMeter(id, title, null, pct ?? 0, Percent.RemainingFromUsed(pct ?? 0), reset,
            ResetLabel: reset is null ? "Starts with your next message" : null,
            WindowStartEpochMs: start, WindowLengthMs: lengthMs));
    }
}

public sealed class ClaudeUsageClient
{
    private readonly AuthenticatedHttp _http;
    private readonly ICookieStore _cookies;

    public ClaudeUsageClient(AuthenticatedHttp http, ICookieStore cookies)
    {
        _http = http;
        _cookies = cookies;
    }

    public ProviderResult Fetch(long fetchedAtMs)
    {
        if (!_cookies.HasSession(ProviderId.Claude))
            return new ProviderResult.NeedsSignIn("Sign in to claude.ai to load Claude usage.");
        var endpoint = ClaudeEndpoints.Organizations;
        var headers = new Dictionary<string, string>
        {
            ["Referer"] = ClaudeEndpoints.UsagePage,
            ["Origin"] = ClaudeEndpoints.Origin,
            ["anthropic-client-platform"] = "web_claude_ai"
        };
        try
        {
            var orgs = _http.GetJson(ClaudeEndpoints.Organizations, "claude.ai", headers);
            var orgId = ClaudeUsageParser.ParseOrgId(orgs.Body, LastActiveOrg());
            endpoint = ClaudeEndpoints.Usage(orgId);
            var usage = _http.GetJson(endpoint, "claude.ai", headers);
            return new ProviderResult.Success(
                ClaudeUsageParser.ParseUsage(usage.Body, fetchedAtMs),
                new[] { FetchAttempt.Of(fetchedAtMs, SoftwareSectionId.Claude, FetchOutcome.Success, endpoint: endpoint, source: "json") });
        }
        catch (SessionExpiredException ex) when (ex.Code == 401)
        {
            return new ProviderResult.NeedsSignIn("Claude session expired. Sign in again.",
                new[] { FetchErrorClassifier.Attempt(ex, SoftwareSectionId.Claude, endpoint, fetchedAtMs) });
        }
        catch (Exception ex)
        {
            var attempt = FetchErrorClassifier.Attempt(ex, SoftwareSectionId.Claude, endpoint, fetchedAtMs);
            // claude.ai answers 403 (often a Cloudflare HTML challenge) for plain HTTP clients
            // even with a live session. Report a failure so the WebView fallback (a real
            // browser with the same cookies) gets a turn; it decides whether sign-in is needed.
            return new ProviderResult.Failure(string.IsNullOrWhiteSpace(attempt.ShortReason) ? ex.Message : attempt.ShortReason, "json", new[] { attempt });
        }
    }

    /// <summary>Usage JSON read through the signed-in WebView (Cloudflare-safe fallback).</summary>
    public ProviderSnapshot SnapshotFromWebJson(string usageRaw, long fetchedAtMs) =>
        ClaudeUsageParser.ParseUsage(usageRaw, fetchedAtMs, "webview");

    /// <summary>
    /// True when the WebView body is claude.ai's own auth error rather than usage, e.g.
    /// <c>{"type":"error","error":{"type":"permission_error","details":{"error_code":"account_session_invalid"}}}</c>.
    /// </summary>
    public static bool IsAuthErrorBody(string body) =>
        body.Contains("account_session_invalid", StringComparison.OrdinalIgnoreCase)
        || body.Contains("authentication_error", StringComparison.OrdinalIgnoreCase)
        || body.Contains("\"permission_error\"", StringComparison.OrdinalIgnoreCase)
        || HttpBodies.IsUnauthorizedPayload(body, 200, "");

    public FetchAttempt WebAttempt(long fetchedAtMs) =>
        FetchAttempt.Of(fetchedAtMs, SoftwareSectionId.Claude, FetchOutcome.Success, endpoint: ClaudeEndpoints.UsagePage, source: "webview");

    private string? LastActiveOrg()
    {
        var value = CookieUtil.CookieMap(_cookies.GetHeader("claude.ai")).GetValueOrDefault(ClaudeEndpoints.LastActiveOrgCookie);
        return string.IsNullOrWhiteSpace(value) ? null : Uri.UnescapeDataString(value.Trim().Trim('"'));
    }
}
