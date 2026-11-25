using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace UsageWidget;

public static class GeminiUsageParser
{
    public const long FiveHours = 18_000_000;
    public const long Week = 604_800_000;
    public static JsonObject Object(string? raw)
    {
        try { return JsonNode.Parse(raw ?? "{}") as JsonObject ?? new(); }
        catch { return new(); }
    }

    public static string? Plan(JsonObject root) => root.Obj("paidTier")?.Str("name")
        ?? (root.Obj("paidTier")?.Str("id") switch
        {
            "g1-pro-tier" => "Pro", "g1-ultra-tier" => "Ultra", "g1-ultra-lite-tier" => "Ultra Lite", _ => null
        }) ?? root.Obj("currentTier")?.Str("name");

    // Inputs are ordered daily, then production. Keep the first usable exact bucket ID.
    public static List<UsageMeter> Merge(params string?[] summaries)
    {
        var meters = new Dictionary<string, UsageMeter>(StringComparer.Ordinal);
        foreach (var raw in summaries)
        {
            var root = Object(raw);
            var groups = root["groups"] as JsonArray ?? root.Obj("response")?["groups"] as JsonArray ?? root["quotaGroups"] as JsonArray ?? new();
            var argonBucketCount = groups.OfType<JsonObject>().Where(g =>
                (g.Str("displayName", "name") ?? "").Contains("Gemini 4", StringComparison.OrdinalIgnoreCase)
                && (g.Str("displayName", "name") ?? "").Contains("Argon", StringComparison.OrdinalIgnoreCase))
                .Sum(g => (g["buckets"] as JsonArray)?.Count ?? 0);
            foreach (var group in groups.OfType<JsonObject>())
            {
                var buckets = group["buckets"] as JsonArray ?? new();
                var name = group.Str("displayName", "name") ?? "";
                var argon = name.Contains("Gemini 4", StringComparison.OrdinalIgnoreCase) && name.Contains("Argon", StringComparison.OrdinalIgnoreCase);
                if (argon && (argonBucketCount != 1 || meters.Values.Any(m => m.Id == "gemini.argon-weekly"))) continue;
                foreach (var bucket in buckets.OfType<JsonObject>())
                {
                    var id = bucket.Str("bucketId");
                    if (id is null || meters.ContainsKey(id)) continue;
                    if (argon && (id is "gemini-5h" or "gemini-weekly" or "3p-5h" or "3p-weekly")) continue;
                    var label = id switch
                    {
                        "gemini-5h" => "Gemini 5-hour", "gemini-weekly" => "Gemini weekly",
                        "3p-5h" => "Third-party (Claude/GPT) 5-hour", "3p-weekly" => "Third-party (Claude/GPT) weekly",
                        _ => argon && buckets.Count == 1 && bucket.Str("window") == "weekly" ? "Gemini 4 Argon weekly" : null
                    };
                    if (label is null) continue;
                    var duration = label == "Gemini 4 Argon weekly" ? Week : id.EndsWith("5h", StringComparison.Ordinal) ? FiveHours : Week;
                    var meter = Meter(bucket, label == "Gemini 4 Argon weekly" ? "gemini.argon-weekly" : "gemini." + id, label, duration);
                    if (meter is not null) meters.Add(id, meter);
                }
            }
        }
        return meters.Values.OrderBy(m => m.Id switch
        {
            "gemini.gemini-5h" => 0,
            "gemini.gemini-weekly" => 1,
            "gemini.argon-weekly" => 2,
            "gemini.3p-5h" => 3,
            "gemini.3p-weekly" => 4,
            _ => m.Title.Contains("Argon") ? 2 : 5
        }).ToList();
    }

    private static UsageMeter? Meter(JsonObject obj, string id, string title, long duration)
    {
        if (obj.ContainsKey("disabled") && (obj["disabled"] is not JsonValue d || !d.TryGetValue<bool>(out var disabled) || disabled)) return null;
        if (obj["remainingFraction"] is not JsonValue v || !v.TryGetValue<double>(out var fraction) || !double.IsFinite(fraction) || fraction < 0 || fraction > 1) return null;
        var hasNonNullReset = obj.TryGetPropertyValue("resetTime", out var resetNode) && resetNode is not null && resetNode.GetValueKind() != JsonValueKind.Null;
        long? reset = DateTimeOffset.TryParse(obj.Str("resetTime"), CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var at) ? at.ToUnixTimeMilliseconds() : null;
        if (hasNonNullReset && reset is null) return null;
        return new UsageMeter(id, title, UsedPercent: (1 - fraction) * 100, RemainingPercent: fraction * 100,
            ResetsAtEpochMs: reset, WindowStartEpochMs: reset - duration, WindowLengthMs: duration);
    }

    public static UsageMeter? Models(string? raw)
    {
        var models = Object(raw)["models"] as JsonObject ?? new();
        return models.Where(kv =>
        {
            var id = kv.Key.ToLowerInvariant();
            var obj = kv.Value as JsonObject;
            var name = obj?.Str("displayName");
            var internalId = obj?.Str("model") ?? kv.Key;
            if (internalId is "MODEL_CHAT_20706" or "MODEL_CHAT_23310" or "MODEL_GOOGLE_GEMINI_2_5_FLASH" or "MODEL_GOOGLE_GEMINI_2_5_FLASH_THINKING" or "MODEL_GOOGLE_GEMINI_2_5_FLASH_LITE" or "MODEL_GOOGLE_GEMINI_2_5_PRO" or "MODEL_PLACEHOLDER_M19" or "MODEL_PLACEHOLDER_M9" or "MODEL_PLACEHOLDER_M12" or "MODEL_PLACEHOLDER_M421") return false;
            return (id.StartsWith("gemini-") || name?.StartsWith("Gemini", StringComparison.OrdinalIgnoreCase) == true) && !new[] { "image", "argon", "2.5", "placeholder", "internal" }.Any(id.Contains)
                && !string.IsNullOrWhiteSpace(name) && !name.Contains("image", StringComparison.OrdinalIgnoreCase) && !name.Contains("Argon", StringComparison.OrdinalIgnoreCase)
                && obj?.Bool("isInternal") != true;
        }).Select(kv => kv.Value is JsonObject obj && obj["quotaInfo"] is JsonObject quota
            ? Meter(quota, "gemini.gemini-5h", "Gemini 5-hour", FiveHours) : null)
            .Where(m => m is not null).OrderByDescending(m => m!.UsedPercent).FirstOrDefault();
    }
}

public sealed record GeminiResponse(int Status, string Body);

// Injectable transport keeps host retries and degraded responses covered without network tests.
public sealed class GeminiQuotaClient(Func<string, string, string, int, GeminiResponse> post)
{
    public const string Daily = "https://daily-cloudcode-pa.googleapis.com";
    public const string Production = "https://cloudcode-pa.googleapis.com";
    public bool AuthRejected { get; private set; }
    public ProviderSnapshot? Fetch()
    {
        GeminiResponse Request(string host, string method, string body, int timeout = 10)
        {
            try { return post(host, method, body, timeout); }
            catch { return new(0, "{}"); }
        }
        var load = Request(Production, "loadCodeAssist", "{}", 5);
        var metadata = GeminiUsageParser.Object(load.Status is >= 200 and < 300 ? load.Body : null);
        var project = metadata.Str("cloudaicompanionProject");
        var body = project is null ? "{}" : new JsonObject { ["project"] = project }.ToJsonString();
        var hosts = new[] { Daily, Production };
        var responses = hosts.Select(h => Request(h, "retrieveUserQuotaSummary", body)).ToArray();
        var answered = responses.Where(r => r.Status != 0).ToArray();
        if (project is not null && answered.Length > 0 && answered.All(r => r.Status is 401 or 403))
            for (var i = 0; i < responses.Length; i++)
                if (responses[i].Status is 401 or 403) responses[i] = Request(hosts[i], "retrieveUserQuotaSummary", "{}");
        AuthRejected = responses.Any(r => r.Status is 401 or 403);
        var meters = GeminiUsageParser.Merge(responses.Select(r => r.Status is >= 200 and < 300 ? r.Body : null).ToArray());
        if (!meters.Any(m => m.Id is "gemini.gemini-5h" or "gemini.gemini-weekly"))
        {
            foreach (var host in new[] { Production, Daily })
            {
                var fallback = Request(host, "fetchAvailableModels", "{}");
                AuthRejected |= fallback.Status is 401 or 403;
                if (fallback.Status is >= 200 and < 300 && GeminiUsageParser.Models(fallback.Body) is { } meter)
                { meters.Insert(0, meter); break; }
            }
        }
        return meters.Count == 0 ? null : new ProviderSnapshot("gemini", "Gemini (Antigravity)", "OK", Source: "cloud-code", PlanLabel: GeminiUsageParser.Plan(metadata), Meters: meters);
    }
}
