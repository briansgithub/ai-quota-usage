using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace UsageWidget;

public static class ManualResetParser
{
    private static readonly HashSet<string> DropKeys = new(StringComparer.OrdinalIgnoreCase)
    {
        "id", "credit_id", "creditid", "user_id", "userid", "account_id", "accountid",
        "email", "access_token", "accesstoken", "description"
    };

    public static ManualResets? FromUsageRoot(JsonObject root, long fetchedAtMs)
    {
        var block = root.Obj("rate_limit_reset_credits", "rateLimitResetCredits");
        var nestedCredits = CreditsArray(block, fetchedAtMs) ?? CreditsArray(LooksLikeResetInventory(root) ? root : null, fetchedAtMs);
        var count = block?.Long("available_count", "availableCount", "applicable_available_count", "applicableAvailableCount")
            ?? nestedCredits?.Count(c => c.IsAvailable);
        if (block is null && nestedCredits is null) return null;
        var credits = nestedCredits ?? Array.Empty<ManualResetCredit>();
        var observed = ObservedSnippet(block, block is null ? root["credits"] as JsonArray ?? root["Credits"] as JsonArray : null, null);
        var available = Math.Max((int)(count ?? 0), credits.Count(c => c.IsAvailable));
        if (available <= 0 && credits.Count == 0 && block is not null)
            return new ManualResets(0, Array.Empty<ManualResetCredit>(), observed);
        var result = new ManualResets(available, credits, observed);
        return result.AvailableCount > 0 || result.CreditList.Count > 0 || observed is not null ? result : null;
    }

    /// <summary>
    /// claude.ai full limit resets: <c>cedar_ember.grants[]</c> on
    /// <c>/usage?cedar_ember=1</c>, each <c>{ type: "full", resets_left, ends_at, paused }</c>.
    /// One credit per remaining reset, expiring at the grant's <c>ends_at</c>.
    /// </summary>
    public static ManualResets? FromClaudeRoot(JsonObject root, long fetchedAtMs)
    {
        var block = root.Obj("cedar_ember", "cedarEmber");
        if (block is null) return null;
        var credits = new List<ManualResetCredit>();
        var observedGrants = new JsonArray();
        foreach (var grant in (block["grants"] as JsonArray ?? new JsonArray()).OfType<JsonObject>())
        {
            var type = grant.Str("type");
            if (type is not null && !type.Equals("full", StringComparison.OrdinalIgnoreCase)) continue;
            var left = (int)Math.Max(0, grant.Long("resets_left", "resetsLeft") ?? 0);
            var ends = NyTime.ParseTimestamp(grant.Str("ends_at", "endsAt") ?? (object?)grant.Num("ends_at", "endsAt"), fetchedAtMs);
            var paused = grant.Bool("paused") == true;
            var expired = ends is { } e && e <= fetchedAtMs;
            var status = paused ? "paused" : expired ? "expired" : "available";
            for (var i = 0; i < left; i++)
                credits.Add(new ManualResetCredit(ends, status, grant.Str("label") ?? "Full reset", "full"));
            observedGrants.Add(new JsonObject
            {
                ["type"] = type,
                ["resets_left"] = left,
                ["ends_at"] = grant.Str("ends_at", "endsAt"),
                ["paused"] = paused
            });
        }
        var observed = new JsonObject { ["cedar_ember"] = new JsonObject { ["grants"] = observedGrants } }.ToJsonString();
        return new ManualResets(credits.Count(c => c.IsAvailable), credits, observed);
    }

    public static ManualResets? MergeDetails(ManualResets? existing, string? detailsRaw, long fetchedAtMs)
    {
        if (string.IsNullOrWhiteSpace(detailsRaw)) return existing;
        JsonObject root;
        try { root = JsonNav.ParseObject(detailsRaw); }
        catch { return existing; }
        var count = root.Long("available_count", "availableCount") is { } c ? (int)c : (int?)null;
        var credits = CreditsArray(root, fetchedAtMs) ?? Array.Empty<ManualResetCredit>();
        var observed = ObservedSnippet(null, root["credits"] as JsonArray ?? root["Credits"] as JsonArray, count);
        var available = count ?? existing?.AvailableCount ?? credits.Count(x => x.IsAvailable);
        var mergedCredits = credits.Count > 0 ? credits : existing?.CreditList ?? Array.Empty<ManualResetCredit>();
        if (available <= 0 && mergedCredits.Count == 0)
            return existing is null ? existing : existing with { ObservedJson = JoinObserved(existing.ObservedJson, observed) };
        return new ManualResets(available, mergedCredits, JoinObserved(existing?.ObservedJson, observed));
    }

    public static ManualResets? FromHtml(string pageText, long fetchedAtMs)
    {
        var text = pageText.Replace('\u00a0', ' ');
        var section = Regex.Match(text, @"Usage limit resets[\s\S]{0,800}", RegexOptions.IgnoreCase);
        if (!section.Success) return null;
        var expiries = Regex.Matches(section.Value,
                @"Expires\s+([A-Za-z]{3,9}\s+\d{1,2}(?:,\s*\d{4})?(?:[,\s]+\d{1,2}:\d{2}\s*(?:AM|PM)?(?:\s+[A-Z]{2,4})?)?)",
                RegexOptions.IgnoreCase)
            .Select(m => NyTime.ParseTimestampString(m.Groups[1].Value, fetchedAtMs))
            .Where(ms => ms.HasValue)
            .Select(ms => ms!.Value)
            .ToList();
        if (expiries.Count == 0 && !section.Value.Contains("Full reset", StringComparison.OrdinalIgnoreCase)) return null;
        var count = Math.Max(expiries.Count, Math.Max(Regex.Matches(section.Value, "Full reset", RegexOptions.IgnoreCase).Count, 1));
        return new ManualResets(
            count,
            expiries.Select(e => new ManualResetCredit(e, "available", "Full reset")).ToList(),
            """{"source":"html","usage_limit_resets":true,"full_reset_count":""" + count + "}");
    }

    private static IReadOnlyList<ManualResetCredit>? CreditsArray(JsonObject? obj, long fetchedAtMs)
    {
        if (obj is null) return null;
        var arr = obj["credits"] as JsonArray ?? obj["Credits"] as JsonArray;
        if (arr is null) return null;
        var parsed = arr.Select(el => el as JsonObject)
            .Where(item => item is not null && LooksLikeResetCredit(item!))
            .Select(item => new ManualResetCredit(
                NyTime.ParseTimestamp(item!.Str("expires_at", "expiresAt") ?? (object?)item.Num("expires_at", "expiresAt"), fetchedAtMs),
                item.Str("status"),
                item.Str("title"),
                item.Str("reset_type", "resetType")))
            .ToList();
        return parsed.Count > 0 ? parsed : null;
    }

    private static bool LooksLikeResetInventory(JsonObject obj)
    {
        var arr = obj["credits"] as JsonArray ?? obj["Credits"] as JsonArray;
        return arr is not null && arr.Any(el => el is JsonObject o && LooksLikeResetCredit(o));
    }

    private static bool LooksLikeResetCredit(JsonObject obj)
    {
        if (obj.Str("reset_type", "resetType") is not null) return true;
        if (obj.Str("expires_at", "expiresAt") is not null && obj.Str("status") is not null) return true;
        return obj.Str("title")?.Contains("reset", StringComparison.OrdinalIgnoreCase) == true;
    }

    private static string? ObservedSnippet(JsonObject? usageBlock, JsonArray? detailsCredits, int? detailsCount)
    {
        var obj = new JsonObject();
        if (usageBlock is not null) obj["rate_limit_reset_credits"] = Redact(usageBlock);
        if (detailsCount is { } c) obj["details_available_count"] = c;
        if (detailsCredits is not null) obj["rate_limit_reset_credits_details"] = Redact(detailsCredits);
        return obj.Count == 0 ? null : obj.ToJsonString();
    }

    private static string? JoinObserved(string? first, string? second)
    {
        var parts = new[] { first, second }.Where(s => !string.IsNullOrWhiteSpace(s)).ToList();
        if (parts.Count == 0) return null;
        if (parts.Count == 1) return parts[0];
        return $$"""{"usage":{{parts[0]}},"details":{{parts[1]}}}""";
    }

    private static JsonNode Redact(JsonNode element) => element switch
    {
        JsonObject o => new JsonObject(o.Where(kv => !DropKeys.Contains(kv.Key))
            .Select(kv => new KeyValuePair<string, JsonNode?>(kv.Key, kv.Value is null ? null : Redact(kv.Value)))),
        JsonArray a => new JsonArray(a.Select(n => n is null ? null : Redact(n)).ToArray()),
        _ => element.DeepClone()
    };
}
