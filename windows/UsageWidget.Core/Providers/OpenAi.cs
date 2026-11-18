using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace UsageWidget;

public static class OpenAiEndpoints
{
    public const string Origin = "https://chatgpt.com";
    public const string UsagePage = "https://chatgpt.com/codex/settings/usage";
    public const string LoginPage = "https://chatgpt.com/auth/login?next=%2Fcodex%2Fsettings%2Fusage";
    public const string LoginFallback = "https://auth.openai.com/log-in";
    public const string Session = "https://chatgpt.com/api/auth/session";
    public const string WhamUsage = "https://chatgpt.com/backend-api/wham/usage";
    public const string WhamResetCredits = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits";
    public const string SessionCookiePrefix = "__Secure-next-auth.session-token";
    public static readonly string[] CookieHosts = { "chatgpt.com", "www.chatgpt.com", "chat.openai.com", "auth.openai.com" };
}

public enum WindowKind { FiveHour, Weekly, ThirtyDay, Unknown }

public sealed record OpenAiSession(string AccessToken, string? AccountId, string? PlanLabel);
public sealed record OpenAiWindow(string Title, WindowKind Kind, string? Slot, double? UsedPercent, long? ResetsAtEpochMs, long? LimitWindowSeconds = null);
public sealed record OpenAiUsage(
    string? PlanLabel,
    OpenAiWindow? FiveHour,
    OpenAiWindow? Weekly,
    IReadOnlyList<OpenAiWindow> Extras,
    ManualResets? ManualResets = null,
    string? ObservedJson = null,
    OpenAiWindow? ThirtyDay = null,
    OpenAiWindow? CodeReview = null,
    string? CreditsLabel = null);

public static class OpenAiUsageParser
{
    public static OpenAiSession ParseSession(string raw)
    {
        var root = JsonNav.ParseObject(raw);
        var access = root.Str("accessToken", "access_token")
            ?? root.Obj("user")?.Str("accessToken")
            ?? throw new ArgumentException("ChatGPT session JSON is missing accessToken");
        var accountId = root.Str("accountId", "account_id")
            ?? root.Obj("user")?.Str("accountId", "account_id")
            ?? AccountIdFromJwt(access);
        var plan = root.Str("planType", "plan_type") ?? root.Obj("user")?.Str("planType", "plan_type");
        return new OpenAiSession(access, accountId, plan);
    }

    public static OpenAiUsage ParseWhamUsage(string raw, long fetchedAtMs)
    {
        var root = JsonNav.ParseObject(raw);
        var rate = root.Obj("rate_limit", "rateLimit") ?? root;
        var windows = new List<OpenAiWindow>();
        var primary = WindowFrom(rate.Obj("primary_window", "primaryWindow"), fetchedAtMs, fallbackSlot: "primary");
        var secondary = WindowFrom(rate.Obj("secondary_window", "secondaryWindow"), fetchedAtMs, fallbackSlot: "secondary");
        if (primary is not null) windows.Add(primary);
        if (secondary is not null) windows.Add(secondary);
        if (windows.Count == 0) throw new ArgumentException("wham/usage is missing rate_limit windows");

        var fiveHour = windows.FirstOrDefault(w => w.Kind == WindowKind.FiveHour);
        var weekly = windows.FirstOrDefault(w => w.Kind == WindowKind.Weekly);
        var thirtyDay = windows.FirstOrDefault(w => w.Kind == WindowKind.ThirtyDay);
        var assigned = new HashSet<OpenAiWindow>(new[] { fiveHour, weekly, thirtyDay }.OfType<OpenAiWindow>());
        var extras = windows.Where(w => !assigned.Contains(w)).ToList();
        var extraArr = root["additional_rate_limits"] as JsonArray ?? root["additionalRateLimits"] as JsonArray;
        if (extraArr is not null)
        {
            foreach (var el in extraArr)
            {
                if (el is not JsonObject obj) continue;
                var name = obj.Str("title", "limit_name", "limitName", "metered_feature", "id") ?? "Extra";
                var nested = obj.Obj("rate_limit", "rateLimit") ?? obj;
                extras.AddRange(WindowsFromLimit(nested, fetchedAtMs, name));
            }
        }

        OpenAiWindow? codeReview = null;
        var review = root.Obj("code_review_rate_limit", "codeReviewRateLimit");
        if (review is not null)
        {
            var nested = review.Obj("rate_limit", "rateLimit") ?? review;
            codeReview = WindowFrom(nested.Obj("primary_window", "primaryWindow") ?? nested, fetchedAtMs, "Code review", "code_review");
        }

        var creditsLabel = CreditsLine(root.Obj("credits"));
        var manual = ManualResetParser.FromUsageRoot(root, fetchedAtMs)
            ?? new ManualResets(0, Array.Empty<ManualResetCredit>(), """{"rate_limit_reset_credits":null}""");
        return new OpenAiUsage(
            Titleize(root.Str("plan_type", "planType")),
            fiveHour, weekly, extras, manual, manual.ObservedJson, thirtyDay, codeReview, creditsLabel);
    }

    public static OpenAiUsage MergeResetDetails(OpenAiUsage usage, string? detailsRaw, long fetchedAtMs)
    {
        var merged = ManualResetParser.MergeDetails(usage.ManualResets, detailsRaw, fetchedAtMs);
        return usage with { ManualResets = merged, ObservedJson = merged?.ObservedJson ?? usage.ObservedJson };
    }

    public static ProviderSnapshot ToSnapshot(OpenAiUsage usage, string source = "json")
    {
        var meters = new List<UsageMeter>();
        if (usage.FiveHour is { } five) meters.Add(ToMeter(five, "openai.five_hour"));
        if (usage.Weekly is { } weekly) meters.Add(ToMeter(weekly, "openai.weekly"));
        if (usage.ThirtyDay is { } thirty) meters.Add(ToMeter(thirty, "openai.thirty_day"));
        if (usage.CodeReview is { } review) meters.Add(ToMeter(review, "openai.code_review"));
        for (var i = 0; i < usage.Extras.Count; i++)
            meters.Add(ToMeter(usage.Extras[i], ExtraMeterId(usage.Extras[i], i)));
        if (usage.CreditsLabel is { } credits)
            meters.Add(new UsageMeter("openai.credits", "Credits", ResetLabel: credits));
        var visibleResets = usage.ManualResets?.IsPresent == true ? usage.ManualResets : null;
        return new ProviderSnapshot(
            "openai", "Codex", "OK", null, source, usage.PlanLabel,
            meters, visibleResets, usage.ObservedJson ?? visibleResets?.ObservedJson);
    }

    public static string? AccountIdFromJwt(string token)
    {
        var parts = token.Split('.');
        if (parts.Length < 2) return null;
        var payload = DecodeBase64Url(parts[1]);
        if (payload is null) return null;
        try
        {
            var obj = JsonNav.ParseObject(payload);
            var id = obj.Str("account_id", "accountId");
            if (id is not null) return id;
            var auth = obj.Obj("https://api.openai.com/auth", "https://api.openai.com/profile");
            return auth?.Str("chatgpt_account_id", "account_id", "chatgptAccountId");
        }
        catch { return null; }
    }

    public static WindowKind ClassifyWindow(long? seconds, string? slot)
    {
        if (seconds is { } s)
        {
            if (s is >= 10_800 and <= 25_200) return WindowKind.FiveHour;
            if (s is >= 500_000 and <= 800_000) return WindowKind.Weekly;
            if (s is >= 2_000_000 and <= 4_000_000) return WindowKind.ThirtyDay;
            if (s >= 500_000) return WindowKind.Weekly;
            return WindowKind.Unknown;
        }
        return slot switch
        {
            "secondary" => WindowKind.Weekly,
            "primary" => WindowKind.FiveHour,
            _ => WindowKind.Unknown
        };
    }

    private static OpenAiWindow? WindowFrom(JsonObject? obj, long fetchedAtMs, string? titleHint = null, string? fallbackSlot = null)
    {
        if (obj is null) return null;
        var used = obj.Num("used_percent", "usedPercent");
        var seconds = obj.Long("limit_window_seconds", "limitWindowSeconds")
            ?? (obj.Num("limit_window_seconds", "limitWindowSeconds") is { } n ? (long)n : null);
        var resetMs = NyTime.ParseTimestamp(obj.Long("reset_at", "resetAt"), fetchedAtMs)
            ?? NyTime.ParseTimestamp(obj.Num("reset_at", "resetAt"), fetchedAtMs)
            ?? (obj.Num("reset_after_seconds", "resetAfterSeconds") is { } after ? fetchedAtMs + (long)(after * 1000) : null);
        if (used is null && resetMs is null) return null;
        var kind = ClassifyWindow(seconds, fallbackSlot);
        var title = TitleFor(kind, seconds, titleHint, fallbackSlot);
        return new OpenAiWindow(title, kind, fallbackSlot, used is { } u ? Percent.AsWirePercent(u) : null, resetMs, seconds);
    }

    internal static string TitleFor(WindowKind kind, long? seconds, string? titleHint, string? fallbackSlot)
    {
        if (!string.IsNullOrWhiteSpace(titleHint) && fallbackSlot is not ("primary" or "secondary"))
            return StripLimitWord(titleHint);
        return kind switch
        {
            WindowKind.FiveHour => "5-hour",
            WindowKind.Weekly => "Weekly",
            WindowKind.ThirtyDay => "30-day",
            _ => StripLimitWord(titleHint ?? DurationLabel(seconds) ?? (fallbackSlot == "secondary" ? "Weekly" : "5-hour"))
        };
    }

    private static UsageMeter ToMeter(OpenAiWindow w, string id)
    {
        var length = w.LimitWindowSeconds is { } s && s > 0
            ? s * 1000
            : w.Kind switch
            {
                WindowKind.FiveHour => PacingPolicy.WindowMs,
                WindowKind.Weekly => PacingPolicy.GrokPeriodMs,
                WindowKind.ThirtyDay => 30L * 24 * 60 * 60 * 1000,
                _ => PaceWindows.KnownLengthMs(id)
            };
        long? start = w.ResetsAtEpochMs is { } reset && length is { } len ? reset - len : null;
        return new UsageMeter(id, w.Title, "Shared across Codex, Work, and agents", w.UsedPercent, Percent.RemainingFromUsed(w.UsedPercent), w.ResetsAtEpochMs, WindowStartEpochMs: start, WindowLengthMs: length);
    }

    private static string StripLimitWord(string title)
    {
        var stripped = Regex.Replace(title, @"(?i)\s*limits?\b", "").Trim();
        return string.IsNullOrWhiteSpace(stripped) ? title : stripped;
    }

    private static List<OpenAiWindow> WindowsFromLimit(JsonObject nested, long fetchedAtMs, string name)
    {
        var found = new List<OpenAiWindow>();
        var p = WindowFrom(nested.Obj("primary_window", "primaryWindow"), fetchedAtMs, fallbackSlot: "primary");
        var s = WindowFrom(nested.Obj("secondary_window", "secondaryWindow"), fetchedAtMs, fallbackSlot: "secondary");
        if (p is not null) found.Add(p with { Title = $"{name} · {p.Title}" });
        if (s is not null) found.Add(s with { Title = $"{name} · {s.Title}" });
        if (found.Count == 0)
        {
            var lone = WindowFrom(nested, fetchedAtMs, name, name);
            if (lone is not null) found.Add(lone);
        }
        return found;
    }

    private static string ExtraMeterId(OpenAiWindow window, int index)
    {
        var slug = Regex.Replace(window.Title.ToLowerInvariant(), "[^a-z0-9]+", "_").Trim('_');
        if (string.IsNullOrEmpty(slug)) slug = $"extra_{index}";
        return index == 0 ? $"openai.{slug}" : $"openai.{slug}_{index}";
    }

    private static string? DurationLabel(long? seconds)
    {
        if (seconds is not { } s || s <= 0) return null;
        var hours = s / 3600.0;
        if (hours is >= 4.5 and <= 5.5) return "5-hour";
        if (hours is >= 23 and <= 25) return "Daily";
        if (hours is >= 167 and <= 169) return "Weekly";
        if (hours is >= 720 and <= 744) return "30-day";
        return hours >= 24 ? $"{(int)(hours / 24)}-day" : $"{(int)hours}-hour";
    }

    private static string? CreditsLine(JsonObject? credits)
    {
        if (credits is null) return null;
        var hasCredits = credits.Bool("has_credits", "hasCredits");
        var remaining = CreditNumber(credits, "remaining", "balance", "credits_remaining", "creditsRemaining");
        var total = CreditNumber(credits, "total", "limit", "credits_total", "creditsTotal");
        var used = CreditNumber(credits, "used", "credits_used", "creditsUsed");
        if (hasCredits != true && (remaining is null or <= 0) && used is null) return null;
        if (remaining is { } r && total is { } t) return $"{(int)r} of {(int)t} credits remaining";
        if (remaining is { } rem) return $"{(int)rem} credits remaining";
        if (used is { } u && total is { } tot) return $"{(int)u} of {(int)tot} credits used";
        return hasCredits == true ? "Credits available" : null;
    }

    private static double? CreditNumber(JsonObject obj, params string[] names) =>
        obj.Num(names) ?? (double.TryParse(obj.Str(names), NumberStyles.Float, CultureInfo.InvariantCulture, out var n) ? n : null);

    private static string? DecodeBase64Url(string value)
    {
        try
        {
            var padded = value + new string('=', (4 - value.Length % 4) % 4);
            return Encoding.UTF8.GetString(Convert.FromBase64String(padded.Replace('-', '+').Replace('_', '/')));
        }
        catch { return null; }
    }

    private static string? Titleize(string? s) => string.IsNullOrEmpty(s) ? s : char.ToUpperInvariant(s[0]) + s[1..];
}

public static class OpenAiHtmlExtractor
{
    public static OpenAiUsage Extract(string pageText, long fetchedAtMs)
    {
        var text = pageText.Replace('\u00a0', ' ');
        var five = Section(text, "5-hour limit", fetchedAtMs, WindowKind.FiveHour, "5-hour")
            ?? Section(text, "5-hour", fetchedAtMs, WindowKind.FiveHour, "5-hour");
        var weekly = Section(text, "Weekly limit", fetchedAtMs, WindowKind.Weekly, "Weekly")
            ?? Section(text, "Weekly", fetchedAtMs, WindowKind.Weekly, "Weekly");
        if (five is null && weekly is null)
            throw new ArgumentException("Could not find ChatGPT 5-hour/weekly limits in page text");
        var manual = ManualResetParser.FromHtml(text, fetchedAtMs);
        return new OpenAiUsage(null, five, weekly, Array.Empty<OpenAiWindow>(), manual, manual?.ObservedJson);
    }

    private static OpenAiWindow? Section(string text, string heading, long fetchedAtMs, WindowKind kind, string title)
    {
        var block = Regex.Match(text,
            Regex.Escape(heading) + @"[\s\S]{0,240}?(?=(?:5-hour limit|Weekly limit|5-hour|Weekly|Recent plan usage|Credits|Usage limit resets|$))",
            RegexOptions.IgnoreCase);
        if (!block.Success) return null;
        var remaining = Regex.Match(block.Value, @"(\d+(?:\.\d+)?)\s*%\s*left", RegexOptions.IgnoreCase);
        var usedExplicit = Regex.Match(block.Value, @"(\d+(?:\.\d+)?)\s*%\s*used", RegexOptions.IgnoreCase);
        double? used = usedExplicit.Success ? double.Parse(usedExplicit.Groups[1].Value, CultureInfo.InvariantCulture)
            : remaining.Success ? 100.0 - double.Parse(remaining.Groups[1].Value, CultureInfo.InvariantCulture)
            : null;
        if (used is null) return null;
        var relative = Regex.Match(block.Value, @"Resets in\s+([0-9dhms\s]+)", RegexOptions.IgnoreCase);
        var date = Regex.Match(block.Value, @"Resets\s+([A-Za-z]{3,9}\s+\d{1,2}.*)$", RegexOptions.IgnoreCase);
        var resetMs = relative.Success ? NyTime.ParseRelative("in " + relative.Groups[1].Value, fetchedAtMs)
            : date.Success ? NyTime.ParseTimestampString(date.Groups[1].Value, fetchedAtMs) : null;
        return new OpenAiWindow(title, kind, null, used, resetMs);
    }
}

public sealed class OpenAiUsageClient
{
    private readonly AuthenticatedHttp _http;
    private readonly ICookieStore _cookies;

    public OpenAiUsageClient(AuthenticatedHttp http, ICookieStore cookies)
    {
        _http = http;
        _cookies = cookies;
    }

    public ProviderResult Fetch(long fetchedAtMs)
    {
        if (!_cookies.HasSession(ProviderId.OpenAi))
            return new ProviderResult.NeedsSignIn("Sign in to ChatGPT to load Codex usage.");
        var endpoint = OpenAiEndpoints.Session;
        try
        {
            var sessionResponse = _http.GetJson(OpenAiEndpoints.Session, "chatgpt.com", new Dictionary<string, string> { ["Referer"] = OpenAiEndpoints.UsagePage });
            var session = OpenAiUsageParser.ParseSession(sessionResponse.Body);
            var headers = new Dictionary<string, string>
            {
                ["Authorization"] = $"Bearer {session.AccessToken}",
                ["Referer"] = OpenAiEndpoints.UsagePage,
                ["Origin"] = OpenAiEndpoints.Origin
            };
            var accountId = session.AccountId ?? AccountIdFromCookie();
            if (!string.IsNullOrWhiteSpace(accountId))
                headers["ChatGPT-Account-Id"] = accountId;
            endpoint = OpenAiEndpoints.WhamUsage;
            var usageResponse = _http.GetJson(OpenAiEndpoints.WhamUsage, "chatgpt.com", headers);
            var usage = OpenAiUsageParser.ParseWhamUsage(usageResponse.Body, fetchedAtMs);
            string? detailsBody = null;
            try { detailsBody = _http.GetJson(OpenAiEndpoints.WhamResetCredits, "chatgpt.com", headers).Body; }
            catch { /* optional */ }
            var merged = OpenAiUsageParser.MergeResetDetails(usage, detailsBody, fetchedAtMs);
            return new ProviderResult.Success(
                OpenAiUsageParser.ToSnapshot(merged) with { DiagnosticJson = UsageDump.OpenAiPayload(usageResponse.Body, detailsBody) },
                new[] { FetchAttempt.Of(fetchedAtMs, SoftwareSectionId.Codex, FetchOutcome.Success, endpoint: OpenAiEndpoints.WhamUsage, source: "json") });
        }
        catch (SessionExpiredException ex)
        {
            return new ProviderResult.NeedsSignIn("ChatGPT session expired. Sign in again.",
                new[] { FetchErrorClassifier.Attempt(ex, SoftwareSectionId.Codex, endpoint, fetchedAtMs) });
        }
        catch (Exception ex)
        {
            var attempt = FetchErrorClassifier.Attempt(ex, SoftwareSectionId.Codex, endpoint, fetchedAtMs);
            if (attempt.OutcomeEnum == FetchOutcome.AuthExpired)
                return new ProviderResult.NeedsSignIn("ChatGPT session expired. Sign in again.", new[] { attempt });
            return new ProviderResult.Failure(string.IsNullOrWhiteSpace(attempt.ShortReason) ? ex.Message : attempt.ShortReason, "json", new[] { attempt });
        }
    }

    public ProviderSnapshot SnapshotFromHtml(string pageText, long fetchedAtMs) =>
        OpenAiUsageParser.ToSnapshot(OpenAiHtmlExtractor.Extract(pageText, fetchedAtMs), "html");

    public FetchAttempt HtmlAttempt(long fetchedAtMs) =>
        FetchAttempt.Of(fetchedAtMs, SoftwareSectionId.Codex, FetchOutcome.Success, endpoint: OpenAiEndpoints.UsagePage, source: "html");

    private string? AccountIdFromCookie()
    {
        var header = _cookies.GetHeader("chatgpt.com");
        var account = CookieUtil.CookieMap(header).GetValueOrDefault("_account")?.Trim().Trim('"');
        return string.IsNullOrWhiteSpace(account) || account == "0" ? null : account;
    }
}
