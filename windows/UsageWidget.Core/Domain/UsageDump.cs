using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace UsageWidget;

/// <summary>
/// Sanitized usage dump for diagnostics. No cookies or tokens.
/// Compare parsed fields with chatgpt.com/codex/settings/usage and
/// cursor.com/dashboard/spending the next time a live session is available.
/// </summary>
public static class UsageDump
{
    public const string LogTag = "UsageWidget";

    private static readonly HashSet<string> RedactKeys = new(StringComparer.OrdinalIgnoreCase)
    {
        "email", "user_id", "userId", "account_id", "accountId", "accessToken",
        "access_token", "id_token", "idToken", "refresh_token", "refreshToken",
        "name", "picture", "chatgpt_account_id"
    };

    private static readonly Regex Jwt = new(@"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9._-]+", RegexOptions.Compiled);
    private static readonly Regex Bearer = new(@"(?i)Bearer\s+[A-Za-z0-9._\-+=/]+", RegexOptions.Compiled);
    private static readonly Regex CookiePair = new(
        @"(?i)(WorkosCursorSessionToken|__Secure-next-auth\.session-token(?:\.\d+)?|Cookie|Authorization)\s*[:=]\s*\S+",
        RegexOptions.Compiled);
    private static readonly Regex Account = new(@"acct_[A-Za-z0-9]+", RegexOptions.Compiled);

    public static string Format(UsageSnapshot snapshot, long? nowMs = null)
    {
        var now = nowMs ?? snapshot.FetchedAtEpochMs;
        var hydrated = HealthSynthesis.Hydrate(snapshot);
        var builder = new System.Text.StringBuilder();
        builder.AppendLine($"=== {LogTag} dump_usage ===");
        builder.AppendLine($"fetchedAtEpochMs={snapshot.FetchedAtEpochMs}");
        builder.AppendLine($"nowMs={now}");
        foreach (var id in SoftwareSectionIds.All)
        {
            var health = hydrated.Health(id);
            var section = UsageSections.From(hydrated, now).First(s => s.Id == id);
            builder.AppendLine();
            builder.AppendLine(id.Title());
            builder.AppendLine($"  outcome={health.LastOutcome} source={health.Source ?? "—"} endpoint={DiagnosticSanitizer.Endpoint(health.Endpoint) ?? "—"}");
            foreach (var meter in section.MeterList)
            {
                builder.AppendLine(
                    $"  {meter.Id} title={meter.Title} used={meter.UsedPercent} displayUsed={meter.UsedLabel() ?? "—"} remaining={meter.RemainingPercent} resetMs={meter.ResetsAtEpochMs} windowStart={meter.WindowStartEpochMs} lengthMs={meter.WindowLengthMs} resetLabel={meter.ResetLabel ?? "—"}");
            }
            if (health.LastGoodDiagnosticJson is { } json)
                builder.AppendLine($"  diagnosticJson={json}");
        }
        return builder.ToString().TrimEnd() + "\n";
    }

    public static string CursorPayload(string? summaryRaw, string? sandRaw)
    {
        var obj = new JsonObject();
        if (summaryRaw is not null) obj["usageSummary"] = RedactElement(summaryRaw);
        if (sandRaw is not null) obj["sandUsage"] = RedactElement(sandRaw);
        return obj.ToJsonString();
    }

    public static string OpenAiPayload(string? whamRaw, string? resetCreditsRaw = null)
    {
        var obj = new JsonObject();
        if (whamRaw is not null) obj["whamUsage"] = RedactElement(whamRaw);
        if (resetCreditsRaw is not null) obj["rateLimitResetCredits"] = RedactElement(resetCreditsRaw);
        return obj.ToJsonString();
    }

    public static string RedactJson(string raw) => RedactElement(raw).ToJsonString();

    private static JsonNode RedactElement(string raw)
    {
        try
        {
            var parsed = JsonNode.Parse(raw);
            return parsed is null ? JsonValue.Create(SanitizeText(raw) ?? "[unparsed]")! : Redact(parsed);
        }
        catch
        {
            return JsonValue.Create(SanitizeText(raw) ?? "[unparsed]")!;
        }
    }

    private static JsonNode Redact(JsonNode node)
    {
        if (node is JsonObject obj)
        {
            var copy = new JsonObject();
            foreach (var (key, value) in obj)
            {
                if (RedactKeys.Contains(key)) copy[key] = "[redacted]";
                else copy[key] = value is null ? null : Redact(value);
            }
            return copy;
        }
        if (node is JsonArray arr)
        {
            var copy = new JsonArray();
            foreach (var item in arr)
                copy.Add(item is null ? null : Redact(item));
            return copy;
        }
        if (node is JsonValue jsonValue && jsonValue.TryGetValue<string>(out var text))
            return JsonValue.Create(SanitizeText(text) ?? text)!;
        return node.DeepClone();
    }

    private static string? SanitizeText(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        var text = Jwt.Replace(raw, "[redacted-jwt]");
        text = Bearer.Replace(text, "Bearer [redacted]");
        text = CookiePair.Replace(text, "$1=[redacted]");
        text = Account.Replace(text, "acct_[redacted]");
        return string.IsNullOrWhiteSpace(text) ? null : text;
    }
}
