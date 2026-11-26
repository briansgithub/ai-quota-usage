using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace UsageWidget;

public static class CursorEndpoints
{
    public const string Origin = "https://cursor.com";
    public const string SpendingPage = "https://cursor.com/dashboard/spending";
    public const string LoginPage = "https://cursor.com/dashboard/spending";
    public const string UsageSummary = "https://cursor.com/api/usage-summary";
    public const string SandUsage = "https://cursor.com/api/dashboard/get-sand-usage-status";
    public const string SessionCookie = "WorkosCursorSessionToken";
    public static readonly string[] CookieHosts = { "cursor.com", "www.cursor.com" };
}

public sealed record CursorPlanUsage(string? PlanLabel, double? CursorModelsUsedPercent, double? OtherModelsUsedPercent, long? ResetsAtEpochMs, long? WindowStartEpochMs = null, OnDemandSpend? OnDemand = null);
public sealed record CursorGrokUsage(double UsedPercent, long? ResetsAtEpochMs, bool HasIncludedAllowance, string? PlanLabel, long? WindowStartEpochMs = null, OnDemandSpend? OnDemand = null, bool OnDemandDisabled = false);
public sealed record CursorHtmlUsage(CursorPlanUsage Plan, CursorGrokUsage? Grok);

/// <summary>
/// On-demand spend vs a dollar cap. Cursor usage-summary documents
/// <c>individualUsage.onDemand.{used,limit,remaining}</c> in cents
/// (live captures: used 2309 with limit null). Hide when spend or limit
/// is missing, or the limit is 0/unset.
/// </summary>
public sealed record OnDemandSpend(double UsedCents, double LimitCents)
{
    public double UsedUsd => UsedCents / 100.0;
    public double LimitUsd => LimitCents / 100.0;
    public double UsedPercent => Math.Max(0.0, UsedCents / LimitCents * 100.0);

    public string Title() => $"On-demand  {FormatUsd(UsedUsd)} / {FormatUsd(LimitUsd)}";

    public UsageMeter ToMeter(string id) => new(
        id, Title(), "Paid usage beyond included quota", UsedPercent, Percent.RemainingFromUsed(UsedPercent),
        BarKind: MeterBarKind.Cap);

    public static OnDemandSpend? FromUsageSummary(JsonObject root)
    {
        var individual = root.Obj("individualUsage", "individual_usage")?.Obj("onDemand", "on_demand");
        var team = root.Obj("teamUsage", "team_usage")?.Obj("onDemand", "on_demand");
        return FromBlock(individual) ?? FromBlock(team);
    }

    public static OnDemandSpend? FromSandUsage(JsonObject root)
    {
        var settings = root.Obj("onDemandSettings", "on_demand_settings");
        if (settings?.Bool("enabled") == false) return null;
        var nested = FromBlock(settings) ?? FromBlock(root.Obj("onDemand", "on_demand"));
        if (nested is not null) return nested;
        var used = Cents(root, "onDemandUsed", "on_demand_used", "onDemandSpend", "on_demand_spend");
        var limit = Cents(root, "onDemandLimit", "on_demand_limit", "onDemandCap", "on_demand_cap");
        return Of(used, limit, null);
    }

    public static OnDemandSpend? FromBlock(JsonObject? obj)
    {
        if (obj is null) return null;
        var enabled = obj.Bool("enabled");
        var used = Cents(obj, "used", "spend", "spent", "totalSpend", "total_spend");
        var limit = Cents(obj, "limit", "cap", "spendLimit", "spend_limit");
        var remaining = Cents(obj, "remaining");
        var spend = used ?? (limit is { } l && remaining is { } r ? Math.Max(0, l - r) : null);
        return Of(spend, limit, enabled);
    }

    public static OnDemandSpend? Of(double? usedCents, double? limitCents, bool? enabled)
    {
        if (enabled == false) return null;
        if (usedCents is null || limitCents is null) return null;
        if (!double.IsFinite(usedCents.Value) || !double.IsFinite(limitCents.Value)) return null;
        if (limitCents.Value <= 0) return null;
        return new OnDemandSpend(Math.Max(0, usedCents.Value), limitCents.Value);
    }

    public static string FormatUsd(double dollars)
    {
        var cents = (long)Math.Round(dollars * 100.0, MidpointRounding.AwayFromZero);
        return cents % 100 == 0
            ? $"${cents / 100}"
            : "$" + dollars.ToString("0.00", CultureInfo.InvariantCulture);
    }

    private static double? Cents(JsonObject obj, params string[] names)
    {
        var value = obj.Num(names);
        if (value is { } n && double.IsFinite(n)) return n;
        var text = obj.Str(names);
        return double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var parsed) ? parsed : null;
    }
}

public static class CursorUsageParser
{
    public static CursorPlanUsage ParseUsageSummary(string raw, long fetchedAtMs)
    {
        var root = JsonNav.ParseObject(raw);
        var plan = root.Obj("individualUsage", "individual_usage")?.Obj("plan")
            ?? root.Obj("planUsage", "plan_usage")
            ?? root.Obj("plan");
        var auto = ReadPercent(plan, "autoPercentUsed", "auto_percent_used", "cursorModelsPercentUsed")
            ?? PercentFromMessage(root.Str("autoModelSelectedDisplayMessage", "auto_model_selected_display_message"));
        var other = ReadPercent(plan, "apiPercentUsed", "api_percent_used", "namedPercentUsed", "named_percent_used")
            ?? PercentFromMessage(root.Str("namedModelSelectedDisplayMessage", "named_model_selected_display_message"));
        var resetMs = NyTime.ParseTimestamp(
            (object?)root.Str("billingCycleEnd", "billing_cycle_end", "includedUsageResetsAt") ?? root.Num("billingCycleEnd", "billing_cycle_end"),
            fetchedAtMs);
        var startMs = NyTime.ParseTimestamp(
            (object?)root.Str("billingCycleStart", "billing_cycle_start") ?? root.Num("billingCycleStart", "billing_cycle_start"),
            fetchedAtMs);
        var planLabel = Titleize(root.Str("membershipType", "membership_type", "planName", "plan_name"));
        if (auto is null && other is null && resetMs is null)
            throw new ArgumentException("Cursor usage-summary is missing plan percentages and reset date");
        return new CursorPlanUsage(planLabel, auto, other, resetMs, startMs, OnDemandSpend.FromUsageSummary(root));
    }

    public static CursorGrokUsage ParseSandUsage(string raw, long fetchedAtMs)
    {
        var root = JsonNav.ParseObject(raw);
        var used = ReadPercent(root, "usagePercent", "usage_percent")
            ?? throw new ArgumentException("Grok Bot payload is missing usagePercent");
        var nextRaw = TimestampField(root, "nextResetTimestampUtc", "next_reset_timestamp_utc", "nextResetMs", "next_reset_ms", "nextResetAt", "next_reset_at", "resetsAt", "resets_at");
        var startRaw = TimestampField(root, "currentPeriodStart", "current_period_start");
        var startMs = NyTime.ParseTimestamp(startRaw, fetchedAtMs);
        var resetMs = NyTime.WithClockIfDateTruncated(NyTime.ParseTimestamp(nextRaw, fetchedAtMs), startMs, fetchedAtMs);
        var included = root.Bool("hasNonZeroIncludedLimit", "has_non_zero_included_limit");
        var includedZero = root.Bool("includedLimitZero", "included_limit_zero");
        var hasAllowance = included == true || includedZero != true;
        var onDemandDisabled = root.Obj("onDemandSettings", "on_demand_settings")?.Bool("enabled") == false
            || root.Obj("onDemand", "on_demand")?.Bool("enabled") == false;
        return new CursorGrokUsage(used, resetMs, hasAllowance, root.Str("grokPlanLabel", "grok_plan_label", "cursorPlanName", "cursor_plan_name"), startMs, OnDemandSpend.FromSandUsage(root), onDemandDisabled);
    }

    public static ProviderSnapshot ToSnapshot(CursorPlanUsage? plan, CursorGrokUsage? grok, string? planError = null, string? grokError = null, string source = "json")
    {
        var meters = new List<UsageMeter>();
        if (plan is not null)
        {
            meters.Add(Meter("cursor.models", "Cursor Models", "Includes Cursor Grok and Composer", plan.CursorModelsUsedPercent, plan.ResetsAtEpochMs, plan.WindowStartEpochMs, PacingPolicy.CursorPeriodMs));
            meters.Add(Meter("cursor.other", "Other Models", "Named / API models", plan.OtherModelsUsedPercent, plan.ResetsAtEpochMs, plan.WindowStartEpochMs, PacingPolicy.CursorPeriodMs));
            if (plan.OnDemand is { } cursorDemand)
                meters.Add(cursorDemand.ToMeter("cursor.on_demand"));
            if (plan.ResetsAtEpochMs is not null)
                meters.Add(new UsageMeter("cursor.plan_reset", "Plan reset", ResetsAtEpochMs: plan.ResetsAtEpochMs, WindowStartEpochMs: plan.WindowStartEpochMs));
        }
        if (grok is { HasIncludedAllowance: true })
            meters.Add(Meter("cursor.grok_bot", "Grok Bot", "Weekly usage", grok.UsedPercent, grok.ResetsAtEpochMs, grok.WindowStartEpochMs, PacingPolicy.GrokPeriodMs));
        var grokDemand = grok?.OnDemandDisabled == true ? null : grok?.OnDemand ?? plan?.OnDemand;
        if (grokDemand is not null)
            meters.Add(grokDemand.ToMeter("cursor.grok_on_demand"));
        var errors = new[] { planError is null ? null : $"Plan: {planError}", grokError is null ? null : $"Grok Bot: {grokError}" }.Where(e => e is not null);
        var status = meters.Count > 0 ? "OK" : "ERROR";
        return new ProviderSnapshot("cursor", "Cursor", status, string.Join(" · ", errors!).NullIfEmpty(), source, plan?.PlanLabel ?? grok?.PlanLabel, meters);
    }

    private static UsageMeter Meter(string id, string title, string subtitle, double? used, long? reset, long? windowStart = null, long? windowLength = null) =>
        new(id, title, subtitle, used, Percent.RemainingFromUsed(used), reset, WindowStartEpochMs: windowStart, WindowLengthMs: windowLength);

    private static object? TimestampField(JsonObject obj, params string[] names)
    {
        var element = JsonNav.Field(obj, names);
        if (element is null) return null;
        if (JsonNav.Long(element) is { } l) return l;
        if (JsonNav.Num(element) is { } n) return n;
        return JsonNav.Str(element);
    }

    private static double? ReadPercent(JsonObject? obj, params string[] names)
    {
        var value = obj.Num(names);
        return value is { } v ? Percent.AsWirePercent(v) : null;
    }

    private static double? PercentFromMessage(string? message)
    {
        if (string.IsNullOrWhiteSpace(message)) return null;
        var match = Regex.Match(message, @"(\d+(?:\.\d+)?)\s*%");
        return match.Success ? double.Parse(match.Groups[1].Value, CultureInfo.InvariantCulture) : null;
    }

    private static string? Titleize(string? s) =>
        string.IsNullOrEmpty(s) ? s : char.ToUpperInvariant(s[0]) + s[1..];
}

public static class CursorHtmlExtractor
{
    public static CursorHtmlUsage Extract(string pageText, long fetchedAtMs)
    {
        var text = pageText.Replace('\u00a0', ' ');
        var cursorModels = PercentUsedNear(text, "Cursor Models");
        var otherModels = PercentUsedNear(text, "Other Models");
        var grok = PercentUsedNear(text, "Grok Bot") ?? PercentUsedNear(text, "Weekly usage");
        var planReset = MatchMonth(text, @"Usage limits reset on\s+([A-Za-z]{3,9}\s+\d{1,2})", fetchedAtMs)
            ?? MatchMonth(text, @"reset on\s+([A-Za-z]{3,9}\s+\d{1,2})", fetchedAtMs);
        var grokReset = MatchMonth(text, @"Grok Bot[\s\S]{0,400}?Resets\s+([A-Za-z]{3,9}\s+\d{1,2})", fetchedAtMs)
            ?? MatchMonth(text, @"Weekly usage[\s\S]{0,200}?Resets\s+([A-Za-z]{3,9}\s+\d{1,2})", fetchedAtMs);
        var planLabel = Regex.Match(text, @"\b(Pro\+|Ultra|Business|Enterprise|Pro|Free|Hobby)\b", RegexOptions.IgnoreCase).Value
            .NullIfEmpty();
        if (cursorModels is null && otherModels is null && grok is null)
            throw new ArgumentException("Could not find Cursor usage percentages in page text");
        var onDemand = OnDemandFromHtml(text);
        return new CursorHtmlUsage(
            new CursorPlanUsage(planLabel, cursorModels, otherModels, planReset, OnDemand: onDemand),
            grok is { } g ? new CursorGrokUsage(g, grokReset, true, planLabel, OnDemand: onDemand) : null);
    }

    private static OnDemandSpend? OnDemandFromHtml(string text)
    {
        if (Regex.IsMatch(text, @"on[- ]demand[\s\S]{0,220}disabled", RegexOptions.IgnoreCase))
            return null;
        var m = Regex.Match(text, @"on[- ]demand[\s\S]{0,500}?\$(\d+(?:\.\d{1,2})?)\s*(?:/|of)\s*\$(\d+(?:\.\d{1,2})?)(?!\s*/\s*mo)", RegexOptions.IgnoreCase);
        if (!m.Success) return null;
        if (!double.TryParse(m.Groups[1].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var used)) return null;
        if (!double.TryParse(m.Groups[2].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var limit)) return null;
        return OnDemandSpend.Of(used * 100.0, limit * 100.0, true);
    }

    private static long? MatchMonth(string text, string pattern, long nowMs)
    {
        var m = Regex.Match(text, pattern, RegexOptions.IgnoreCase);
        return m.Success ? NyTime.ParseMonthDay(m.Groups[1].Value, nowMs) : null;
    }

    private static double? PercentUsedNear(string text, string heading)
    {
        var m = Regex.Match(text, Regex.Escape(heading) + @"[\s\S]{0,280}?(\d+(?:\.\d+)?)\s*%\s*used", RegexOptions.IgnoreCase);
        return m.Success ? double.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture) : null;
    }
}

public sealed class CursorUsageClient
{
    private readonly AuthenticatedHttp _http;
    private readonly ICookieStore _cookies;

    public CursorUsageClient(AuthenticatedHttp http, ICookieStore cookies)
    {
        _http = http;
        _cookies = cookies;
    }

    public ProviderResult Fetch(long fetchedAtMs)
    {
        if (!_cookies.HasSession(ProviderId.Cursor))
            return new ProviderResult.NeedsSignIn("Sign in to Cursor to load plan usage.");
        CursorPlanUsage? plan = null;
        CursorGrokUsage? grok = null;
        var attempts = new List<FetchAttempt>();
        string? planError = null, grokError = null, summaryRaw = null, sandRaw = null;
        try
        {
            var summary = _http.GetJson(CursorEndpoints.UsageSummary, "cursor.com", new Dictionary<string, string> { ["Referer"] = CursorEndpoints.SpendingPage });
            summaryRaw = summary.Body;
            plan = CursorUsageParser.ParseUsageSummary(summary.Body, fetchedAtMs);
            attempts.Add(FetchAttempt.Of(fetchedAtMs, SoftwareSectionId.Cursor, FetchOutcome.Success, endpoint: CursorEndpoints.UsageSummary, source: "json"));
        }
        catch (SessionExpiredException ex)
        {
            var auth = FetchErrorClassifier.Attempt(ex, SoftwareSectionId.Cursor, CursorEndpoints.UsageSummary, fetchedAtMs);
            return new ProviderResult.NeedsSignIn("Cursor session expired. Sign in again.", new[] { auth, auth with { SectionId = SoftwareSectionId.GrokBot.Wire() } });
        }
        catch (Exception ex)
        {
            var attempt = FetchErrorClassifier.Attempt(ex, SoftwareSectionId.Cursor, CursorEndpoints.UsageSummary, fetchedAtMs);
            attempts.Add(attempt);
            planError = attempt.ShortReason;
        }

        try
        {
            var sand = _http.PostJson(CursorEndpoints.SandUsage, "cursor.com", "{}", new Dictionary<string, string>
            {
                ["Origin"] = CursorEndpoints.Origin,
                ["Referer"] = CursorEndpoints.SpendingPage
            });
            sandRaw = sand.Body;
            grok = CursorUsageParser.ParseSandUsage(sand.Body, fetchedAtMs);
            attempts.Add(FetchAttempt.Of(fetchedAtMs, SoftwareSectionId.GrokBot, FetchOutcome.Success, endpoint: CursorEndpoints.SandUsage, source: "json"));
        }
        catch (SessionExpiredException ex)
        {
            var attempt = FetchErrorClassifier.Attempt(ex, SoftwareSectionId.GrokBot, CursorEndpoints.SandUsage, fetchedAtMs);
            attempts.Add(attempt);
            grokError = attempt.ShortReason;
            if (plan is null)
                return new ProviderResult.NeedsSignIn("Cursor session expired. Sign in again.", attempts);
        }
        catch (Exception ex)
        {
            var attempt = FetchErrorClassifier.Attempt(ex, SoftwareSectionId.GrokBot, CursorEndpoints.SandUsage, fetchedAtMs);
            attempts.Add(attempt);
            grokError = attempt.ShortReason;
        }

        if (plan is null && grok is null)
            return new ProviderResult.Failure(planError ?? grokError ?? "Cursor endpoints returned no usage data", "json", attempts);
        var snapshot = CursorUsageParser.ToSnapshot(plan, grok, planError, grokError);
        ManualResets? manual = null;
        foreach (var raw in new[] { summaryRaw, sandRaw }.Where(s => s is not null))
        {
            try
            {
                var parsed = ManualResetParser.FromUsageRoot(JsonNav.ParseObject(raw!), fetchedAtMs);
                if (parsed?.IsPresent == true) { manual = parsed; break; }
            }
            catch { /* ignore */ }
        }
        return new ProviderResult.Success(snapshot with { ManualResets = manual, DiagnosticJson = UsageDump.CursorPayload(summaryRaw, sandRaw) }, attempts);
    }

    public ProviderSnapshot SnapshotFromHtml(string pageText, long fetchedAtMs)
    {
        var extracted = CursorHtmlExtractor.Extract(pageText, fetchedAtMs);
        return CursorUsageParser.ToSnapshot(extracted.Plan, extracted.Grok, source: "html");
    }

    public IReadOnlyList<FetchAttempt> HtmlAttempts(long fetchedAtMs, ProviderSnapshot snapshot)
    {
        var attempts = new List<FetchAttempt>();
        if (snapshot.MeterList.Any(m => MeterIds.Cursor.Contains(m.Id)))
            attempts.Add(FetchAttempt.Of(fetchedAtMs, SoftwareSectionId.Cursor, FetchOutcome.Success, endpoint: CursorEndpoints.SpendingPage, source: "html"));
        if (snapshot.MeterList.Any(m => m.Id == MeterIds.Grok))
            attempts.Add(FetchAttempt.Of(fetchedAtMs, SoftwareSectionId.GrokBot, FetchOutcome.Success, endpoint: CursorEndpoints.SpendingPage, source: "html"));
        return attempts;
    }
}

