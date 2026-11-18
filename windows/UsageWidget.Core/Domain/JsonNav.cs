using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace UsageWidget;

public static class JsonNav
{
    public static JsonObject ParseObject(string raw)
    {
        var trimmed = raw.Trim();
        if (trimmed.Length == 0) throw new ArgumentException("Empty JSON body");
        var node = JsonNode.Parse(trimmed);
        return node as JsonObject
            ?? throw new ArgumentException($"JSON root is {node?.GetType().Name}, expected object");
    }

    public static JsonNode? Field(JsonObject? obj, params string[] names)
    {
        if (obj is null) return null;
        foreach (var name in names)
        {
            if (obj.TryGetPropertyValue(name, out var n) && n is not null && n.GetValueKind() != JsonValueKind.Null)
                return n;
        }
        var lowered = new Dictionary<string, JsonNode?>(StringComparer.OrdinalIgnoreCase);
        foreach (var kv in obj)
            lowered.TryAdd(kv.Key, kv.Value);
        foreach (var name in names)
        {
            if (lowered.TryGetValue(name, out var match) && match is not null && match.GetValueKind() != JsonValueKind.Null)
                return match;
        }
        return null;
    }

    public static JsonObject? Obj(this JsonObject? obj, params string[] names) => Field(obj, names) as JsonObject;
    public static JsonObject? AsObj(this JsonNode? node) => node as JsonObject;
    public static JsonArray? AsArr(this JsonNode? node) => node as JsonArray;

    public static string? Str(this JsonObject? obj, params string[] names) => Str(Field(obj, names));
    public static string? Str(JsonNode? node)
    {
        if (node is not JsonValue v) return null;
        var s = v.ToString()?.Trim();
        return string.IsNullOrEmpty(s) || s == "null" ? null : s;
    }

    public static bool? Bool(this JsonObject? obj, params string[] names)
    {
        var node = Field(obj, names);
        if (node is not JsonValue v) return null;
        if (v.TryGetValue<bool>(out var b)) return b;
        return v.ToString()?.ToLowerInvariant() switch
        {
            "true" or "1" or "yes" => true,
            "false" or "0" or "no" => false,
            _ => null
        };
    }

    public static double? Num(this JsonObject? obj, params string[] names) => Num(Field(obj, names));
    public static double? Num(JsonNode? node)
    {
        if (node is not JsonValue v) return null;
        if (v.TryGetValue<double>(out var d)) return d;
        var text = v.ToString()?.Trim().TrimEnd('%');
        return double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var parsed) ? parsed : null;
    }

    public static long? Long(this JsonObject? obj, params string[] names) => Long(Field(obj, names));
    public static long? Long(JsonNode? node)
    {
        if (node is not JsonValue v) return null;
        if (v.TryGetValue<long>(out var l)) return l;
        if (v.TryGetValue<double>(out var d) && double.IsFinite(d)) return (long)d;
        var text = v.ToString()?.Trim();
        return long.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed) ? parsed : null;
    }

}

public static class HttpBodies
{
    public static bool LooksLikeJson(string body)
    {
        var trimmed = body.TrimStart();
        return trimmed.StartsWith('{') || trimmed.StartsWith('[');
    }

    public static bool LooksLikeHtml(string body)
    {
        var trimmed = body.TrimStart().ToLowerInvariant();
        return trimmed.StartsWith("<!doctype") || trimmed.StartsWith("<html") || trimmed.Contains("<body");
    }

    public static bool LooksLikeLoginUrl(string finalUrl)
    {
        var url = finalUrl.ToLowerInvariant();
        return url.Contains("login") || url.Contains("authorize") || url.Contains("signin")
            || url.Contains("sign-in") || url.Contains("auth.openai") || url.Contains("accounts.google")
            || url.Contains("workos.com");
    }

    public static bool IsUnauthorizedPayload(string body, int code, string finalUrl)
    {
        if (code == 401) return true;
        if (code == 403 && LooksLikeHtml(body)) return true;
        if (LooksLikeLoginUrl(finalUrl) && ((code is >= 300 and <= 399) || LooksLikeHtml(body) || string.IsNullOrWhiteSpace(body)))
            return true;
        if (body.Contains("not_authenticated", StringComparison.OrdinalIgnoreCase)) return true;
        if (LooksLikeJson(body))
        {
            try
            {
                var obj = JsonNav.ParseObject(body);
                var error = obj.Str("error", "detail", "message");
                if (error != null && (
                    error.Equals("not_authenticated", StringComparison.OrdinalIgnoreCase) ||
                    error.Equals("unauthorized", StringComparison.OrdinalIgnoreCase) ||
                    error.Contains("sign in", StringComparison.OrdinalIgnoreCase)))
                    return true;
            }
            catch { /* ignore */ }
        }
        return false;
    }
}
