using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Runtime.InteropServices;
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
    public const string CredentialTarget = "gemini:antigravity";
    public const string VscdbOAuthKey = "antigravityUnifiedStateSync.oauthToken";
    private static volatile string? _cachedPublicCredential;

    // The installed-app credential is published upstream. Resolve only in memory; never log or persist it.
    public static string? PublicClientCredential(HttpClient http)
    {
        if (_cachedPublicCredential is { } cached) return cached;
        try
        {
            var reference = http.GetStringAsync(CredentialReference).GetAwaiter().GetResult();
            if (reference.Length > 100_000 || !reference.Contains("client_id=" + ClientId)) return null;
            var match = Regex.Match(reference, @"client_secret=(GOCSPX-[A-Za-z0-9_-]+)");
            if (!match.Success) return null;
            _cachedPublicCredential = match.Groups[1].Value;
            return _cachedPublicCredential;
        }
        catch { return null; }
    }
}

public static class GeminiCredentialSources
{
    private const uint CredTypeGeneric = 1;
    private const int MaxBlobBytes = 1_000_000;
    private const long MaxDbBytes = 64L * 1024 * 1024;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NativeCredential
    {
        public uint Flags;
        public uint Type;
        public string? TargetName;
        public string? Comment;
        public long LastWritten;
        public uint CredentialBlobSize;
        public IntPtr CredentialBlob;
        public uint Persist;
        public uint AttributeCount;
        public IntPtr Attributes;
        public string? TargetAlias;
        public string? UserName;
    }

    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredRead(string target, uint type, uint flags, out IntPtr credentialPtr);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern void CredFree(IntPtr buffer);

    public static string? ReadWindowsCredentialManager(string target = GeminiOAuth.CredentialTarget)
    {
        if (!OperatingSystem.IsWindows()) return null;
        try
        {
            if (!CredRead(target, CredTypeGeneric, 0, out var ptr) || ptr == IntPtr.Zero) return null;
            try
            {
                var cred = Marshal.PtrToStructure<NativeCredential>(ptr);
                if (cred.CredentialBlob == IntPtr.Zero || cred.CredentialBlobSize == 0 || cred.CredentialBlobSize > MaxBlobBytes)
                    return null;
                var bytes = new byte[cred.CredentialBlobSize];
                Marshal.Copy(cred.CredentialBlob, bytes, 0, bytes.Length);
                return DecodeCredentialBlob(bytes);
            }
            finally { CredFree(ptr); }
        }
        catch { return null; }
    }

    public static string? DecodeCredentialBlob(byte[] bytes)
    {
        if (bytes.Length == 0 || bytes.Length > MaxBlobBytes) return null;
        var utf8 = Encoding.UTF8.GetString(bytes).Trim('\uFEFF', '\0', ' ', '\t', '\r', '\n');
        if (utf8.StartsWith('{') && FindToken(GeminiUsageParser.Object(utf8)) is not null) return utf8;
        if (bytes.Length >= 2 && bytes.Length % 2 == 0)
        {
            var utf16 = Encoding.Unicode.GetString(bytes).Trim('\uFEFF', '\0', ' ', '\t', '\r', '\n');
            if (utf16.StartsWith('{') && FindToken(GeminiUsageParser.Object(utf16)) is not null) return utf16;
        }
        return null;
    }

    public static IReadOnlyList<string> DefaultVscdbPaths()
    {
        var appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        if (string.IsNullOrWhiteSpace(appData)) return Array.Empty<string>();
        return new[]
        {
            Path.Combine(appData, "Antigravity", "User", "globalStorage", "state.vscdb"),
            Path.Combine(appData, "Antigravity IDE", "User", "globalStorage", "state.vscdb")
        };
    }

    public static JsonObject? FindToken(JsonNode? node)
    {
        if (node is JsonObject obj)
        {
            var access = obj.Str("access_token", "accessToken");
            var refresh = obj.Str("refresh_token", "refreshToken");
            if (access is not null || refresh is not null)
                return new JsonObject
                {
                    ["access_token"] = access,
                    ["refresh_token"] = refresh,
                    ["expiry_date"] = obj.Num("expiry_date", "expires_at", "expiry_timestamp"),
                    ["expiry"] = obj.Str("expiry", "expiresAt")
                };
            foreach (var value in obj.Select(kv => kv.Value))
                if (FindToken(value) is { } found) return found;
        }
        if (node is JsonArray arr)
            foreach (var value in arr)
                if (FindToken(value) is { } found) return found;
        return null;
    }

    public static JsonObject? ReadVscdbOAuthToken(string dbPath, string key = GeminiOAuth.VscdbOAuthKey)
    {
        var raw = ReadSqliteItemTableValue(dbPath, key);
        if (raw is null) return null;
        return DecodeOAuthEnvelope(raw);
    }

    /// <summary>
    /// Decodes Antigravity's state.vscdb OAuth token value (JSON or base64-encoded protobuf
    /// envelope wrapping OAuthTokenInfo: field 1 access_token, field 2 token_type, field 3
    /// refresh_token, field 4 expiry Timestamp).
    /// </summary>
    public static JsonObject? DecodeOAuthEnvelope(string raw)
    {
        var trimmed = raw.Trim();
        if (trimmed.Length == 0) return null;
        if (trimmed.StartsWith('{'))
            return FindToken(GeminiUsageParser.Object(trimmed));
        if (TryDecodeBase64(trimmed, out var bytes))
            return DecodeProtobufToken(bytes, depth: 0);
        return null;
    }

    public static JsonObject? DecodeProtobufToken(ReadOnlySpan<byte> data, int depth = 0)
    {
        if (depth > 6 || data.Length == 0) return null;
        string? field1 = null;
        string? field2 = null;
        string? field3 = null;
        long? expiryMs = null;
        var nested = new List<byte[]>();

        var pos = 0;
        var fieldCount = 0;
        while (pos < data.Length)
        {
            if (!TryReadProtoVarint(data, ref pos, out var tag) || tag == 0) return null;
            var fieldNum = (int)(tag >> 3);
            var wireType = (int)(tag & 7);
            if (fieldNum <= 0) return null;
            fieldCount++;
            switch (wireType)
            {
                case 0:
                    if (!TryReadProtoVarint(data, ref pos, out var v)) return null;
                    if (fieldNum == 4 && v > 0) expiryMs = NormalizeEpochMs((long)v);
                    break;
                case 1:
                    if (pos + 8 > data.Length) return null;
                    pos += 8;
                    break;
                case 2:
                    if (!TryReadProtoVarint(data, ref pos, out var len) || len > (ulong)(data.Length - pos)) return null;
                    var slice = data.Slice(pos, (int)len);
                    pos += (int)len;
                    if (fieldNum == 1 && TryAsciiToken(slice, out var s1)) field1 = s1;
                    else if (fieldNum == 2 && TryAsciiToken(slice, out var s2)) field2 = s2;
                    else if (fieldNum == 3 && TryAsciiToken(slice, out var s3)) field3 = s3;
                    else if (fieldNum == 4 && TryReadTimestampSeconds(slice, out var tsMs)) expiryMs = tsMs;
                    if (slice.Length >= 8) nested.Add(slice.ToArray());
                    break;
                case 5:
                    if (pos + 4 > data.Length) return null;
                    pos += 4;
                    break;
                default:
                    return null;
            }
        }
        if (fieldCount == 0) return null;

        var looksLikeOAuth =
            (field3 is not null && field3.StartsWith("1//", StringComparison.Ordinal))
            || (field1 is not null && field1.StartsWith("ya29.", StringComparison.Ordinal))
            || (string.Equals(field2, "Bearer", StringComparison.OrdinalIgnoreCase) && (field1 is not null || field3 is not null));
        if (looksLikeOAuth)
        {
            return new JsonObject
            {
                ["access_token"] = field1,
                ["refresh_token"] = field3,
                ["expiry_date"] = expiryMs
            };
        }

        foreach (var child in nested)
        {
            if (DecodeProtobufToken(child, depth + 1) is { } found) return found;
            if (TryAsciiString(child, out var text))
            {
                var t = text.Trim();
                if (t.StartsWith('{') && FindToken(GeminiUsageParser.Object(t)) is { } jsonFound) return jsonFound;
                if (t.Length >= 16 && TryDecodeBase64(t, out var decoded) && DecodeProtobufToken(decoded, depth + 1) is { } b64Found)
                    return b64Found;
            }
        }
        return null;
    }

    private static bool TryReadTimestampSeconds(ReadOnlySpan<byte> data, out long epochMs)
    {
        epochMs = 0;
        var pos = 0;
        long? seconds = null;
        while (pos < data.Length)
        {
            if (!TryReadProtoVarint(data, ref pos, out var tag) || tag == 0) return false;
            var fieldNum = (int)(tag >> 3);
            var wireType = (int)(tag & 7);
            if (wireType == 0)
            {
                if (!TryReadProtoVarint(data, ref pos, out var v)) return false;
                if (fieldNum == 1) seconds = (long)v;
            }
            else if (wireType == 2)
            {
                if (!TryReadProtoVarint(data, ref pos, out var len) || len > (ulong)(data.Length - pos)) return false;
                pos += (int)len;
            }
            else if (wireType == 1) { if (pos + 8 > data.Length) return false; pos += 8; }
            else if (wireType == 5) { if (pos + 4 > data.Length) return false; pos += 4; }
            else return false;
        }
        if (seconds is null or <= 0) return false;
        epochMs = NormalizeEpochMs(seconds.Value);
        return true;
    }

    private static long NormalizeEpochMs(long value) => value is > 0 and < 100_000_000_000 ? value * 1000 : value;

    private static bool TryReadProtoVarint(ReadOnlySpan<byte> data, ref int pos, out ulong value)
    {
        value = 0;
        var shift = 0;
        while (pos < data.Length && shift < 64)
        {
            var b = data[pos++];
            value |= (ulong)(b & 0x7F) << shift;
            if ((b & 0x80) == 0) return true;
            shift += 7;
        }
        return false;
    }

    private static bool TryAsciiToken(ReadOnlySpan<byte> data, out string text)
    {
        text = "";
        if (data.Length == 0 || data.Length > 8192) return false;
        foreach (var b in data)
            if (b < 0x21 || b > 0x7E) return false;
        text = Encoding.ASCII.GetString(data);
        return true;
    }

    private static bool TryAsciiString(ReadOnlySpan<byte> data, out string text)
    {
        text = "";
        if (data.Length == 0 || data.Length > 65536) return false;
        foreach (var b in data)
            if (b != '\r' && b != '\n' && b != '\t' && (b < 0x20 || b > 0x7E)) return false;
        text = Encoding.ASCII.GetString(data);
        return true;
    }

    private static bool TryDecodeBase64(string text, out byte[] bytes)
    {
        bytes = Array.Empty<byte>();
        var cleaned = text.Replace('-', '+').Replace('_', '/');
        var mod = cleaned.Length % 4;
        if (mod == 1) return false;
        if (mod > 0) cleaned += new string('=', 4 - mod);
        try { bytes = Convert.FromBase64String(cleaned); return bytes.Length > 0; }
        catch { return false; }
    }

    /// <summary>
    /// Minimal, strictly read-only SQLite 3 reader for VS Code / Antigravity's ItemTable(key, value).
    /// Opens with FileShare.ReadWrite | FileShare.Delete and applies committed -wal frames in memory
    /// so the IDE's live database is never locked or modified.
    /// </summary>
    public static string? ReadSqliteItemTableValue(string dbPath, string targetKey)
    {
        try
        {
            if (!File.Exists(dbPath)) return null;
            var info = new FileInfo(dbPath);
            if (info.Length < 100 || info.Length > MaxDbBytes) return null;
            byte[] dbBytes;
            using (var fs = new FileStream(dbPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            {
                dbBytes = new byte[fs.Length];
                fs.ReadExactly(dbBytes);
            }
            if (dbBytes.Length < 100 || !dbBytes.AsSpan(0, 16).SequenceEqual("SQLite format 3\0"u8)) return null;
            var pageSize = (dbBytes[16] << 8) | dbBytes[17];
            if (pageSize == 1) pageSize = 65536;
            if (pageSize < 512 || pageSize > 65536 || (pageSize & (pageSize - 1)) != 0) return null;
            var usableSize = pageSize - dbBytes[20];
            if (usableSize < 480) return null;

            var walPages = ReadWalOverlay(dbPath + "-wal", pageSize);
            byte[]? GetPage(int pageNo)
            {
                if (pageNo <= 0) return null;
                if (walPages.TryGetValue(pageNo, out var walPage)) return walPage;
                var offset = (long)(pageNo - 1) * pageSize;
                if (offset + pageSize > dbBytes.Length) return null;
                var copy = new byte[pageSize];
                Buffer.BlockCopy(dbBytes, (int)offset, copy, 0, pageSize);
                return copy;
            }

            int? itemTableRoot = null;
            TraverseTable(1, GetPage, usableSize, row =>
            {
                if (row.Count >= 4 && row[0] is string type && type == "table"
                    && row[1] is string name && name.Equals("ItemTable", StringComparison.OrdinalIgnoreCase)
                    && row[3] is long root && root > 0 && root <= int.MaxValue)
                {
                    itemTableRoot = (int)root;
                    return false;
                }
                return true;
            });
            if (itemTableRoot is null) return null;

            string? found = null;
            TraverseTable(itemTableRoot.Value, GetPage, usableSize, row =>
            {
                if (row.Count >= 2 && row[0] is string key && key == targetKey)
                {
                    found = row[1] switch
                    {
                        string s => s,
                        byte[] b => Encoding.UTF8.GetString(b),
                        _ => null
                    };
                    return false;
                }
                return true;
            });
            return found;
        }
        catch { return null; }
    }

    private static Dictionary<int, byte[]> ReadWalOverlay(string walPath, int pageSize)
    {
        var committed = new Dictionary<int, byte[]>();
        try
        {
            if (!File.Exists(walPath)) return committed;
            var info = new FileInfo(walPath);
            if (info.Length < 32 || info.Length > MaxDbBytes) return committed;
            byte[] wal;
            using (var fs = new FileStream(walPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            {
                wal = new byte[fs.Length];
                fs.ReadExactly(wal);
            }
            var magic = ReadU32(wal, 0);
            if (magic != 0x377F0682u && magic != 0x377F0683u) return committed;
            if ((int)ReadU32(wal, 8) != pageSize) return committed;
            var salt1 = ReadU32(wal, 16);
            var salt2 = ReadU32(wal, 20);
            var frameSize = 24 + pageSize;
            var pending = new List<(int PageNo, byte[] Data)>();
            for (var offset = 32; offset + frameSize <= wal.Length; offset += frameSize)
            {
                var pageNo = (int)ReadU32(wal, offset);
                var commitPages = ReadU32(wal, offset + 4);
                var fSalt1 = ReadU32(wal, offset + 8);
                var fSalt2 = ReadU32(wal, offset + 12);
                if (pageNo <= 0 || fSalt1 != salt1 || fSalt2 != salt2) break;
                var page = new byte[pageSize];
                Buffer.BlockCopy(wal, offset + 24, page, 0, pageSize);
                pending.Add((pageNo, page));
                if (commitPages > 0)
                {
                    foreach (var entry in pending) committed[entry.PageNo] = entry.Data;
                    pending.Clear();
                }
            }
        }
        catch { /* ignore malformed/in-use WAL */ }
        return committed;
    }

    private static void TraverseTable(int rootPage, Func<int, byte[]?> getPage, int usableSize, Func<List<object?>, bool> onRow)
    {
        var visited = new HashSet<int>();
        var stack = new Stack<int>();
        stack.Push(rootPage);
        while (stack.Count > 0)
        {
            var pageNo = stack.Pop();
            if (!visited.Add(pageNo)) continue;
            var page = getPage(pageNo);
            if (page is null) continue;
            var hdr = pageNo == 1 ? 100 : 0;
            if (hdr + 8 > page.Length) continue;
            var type = page[hdr];
            var cellCount = (page[hdr + 3] << 8) | page[hdr + 4];
            if (type == 0x05)
            {
                if (hdr + 12 + cellCount * 2 > page.Length) continue;
                var rightChild = (int)ReadU32(page, hdr + 8);
                if (rightChild > 0) stack.Push(rightChild);
                for (var i = cellCount - 1; i >= 0; i--)
                {
                    var cellOffset = (page[hdr + 12 + i * 2] << 8) | page[hdr + 13 + i * 2];
                    if (cellOffset + 4 <= page.Length)
                    {
                        var leftChild = (int)ReadU32(page, cellOffset);
                        if (leftChild > 0) stack.Push(leftChild);
                    }
                }
            }
            else if (type == 0x0D)
            {
                if (hdr + 8 + cellCount * 2 > page.Length) continue;
                for (var i = 0; i < cellCount; i++)
                {
                    var cellOffset = (page[hdr + 8 + i * 2] << 8) | page[hdr + 9 + i * 2];
                    var row = ReadLeafRow(page, cellOffset, usableSize, getPage);
                    if (row is not null && !onRow(row)) return;
                }
            }
        }
    }

    private static List<object?>? ReadLeafRow(byte[] page, int offset, int usableSize, Func<int, byte[]?> getPage)
    {
        var pos = offset;
        if (!TryReadSqliteVarint(page, ref pos, out var payloadSize) || payloadSize <= 0 || payloadSize > MaxBlobBytes) return null;
        if (!TryReadSqliteVarint(page, ref pos, out _)) return null; // rowid
        var x = usableSize - 35;
        var m = ((usableSize - 12) * 32 / 255) - 23;
        var k = m + (int)((payloadSize - m) % (usableSize - 4));
        var localLen = payloadSize <= x ? (int)payloadSize : (k <= x ? k : m);
        if (localLen < 0 || pos + localLen > page.Length) return null;

        byte[] payload;
        if (localLen == payloadSize)
        {
            payload = new byte[localLen];
            Buffer.BlockCopy(page, pos, payload, 0, localLen);
        }
        else
        {
            if (pos + localLen + 4 > page.Length) return null;
            payload = new byte[payloadSize];
            Buffer.BlockCopy(page, pos, payload, 0, localLen);
            var written = localLen;
            var nextPage = (int)ReadU32(page, pos + localLen);
            var seenOverflow = new HashSet<int>();
            while (written < payloadSize && nextPage > 0 && seenOverflow.Add(nextPage))
            {
                var ov = getPage(nextPage);
                if (ov is null || ov.Length < usableSize) return null;
                nextPage = (int)ReadU32(ov, 0);
                var take = (int)Math.Min(payloadSize - written, usableSize - 4);
                Buffer.BlockCopy(ov, 4, payload, written, take);
                written += take;
            }
            if (written < payloadSize) return null;
        }
        return DecodeSqliteRecord(payload);
    }

    private static List<object?>? DecodeSqliteRecord(byte[] payload)
    {
        var pos = 0;
        if (!TryReadSqliteVarint(payload, ref pos, out var headerEnd) || headerEnd < pos || headerEnd > payload.Length) return null;
        var types = new List<long>();
        while (pos < headerEnd)
        {
            if (!TryReadSqliteVarint(payload, ref pos, out var st)) return null;
            types.Add(st);
        }
        var values = new List<object?>(types.Count);
        var valPos = (int)headerEnd;
        foreach (var st in types)
        {
            switch (st)
            {
                case 0: values.Add(null); break;
                case 1: if (!TryReadSignedInt(payload, ref valPos, 1, out var v1)) return null; values.Add(v1); break;
                case 2: if (!TryReadSignedInt(payload, ref valPos, 2, out var v2)) return null; values.Add(v2); break;
                case 3: if (!TryReadSignedInt(payload, ref valPos, 3, out var v3)) return null; values.Add(v3); break;
                case 4: if (!TryReadSignedInt(payload, ref valPos, 4, out var v4)) return null; values.Add(v4); break;
                case 5: if (!TryReadSignedInt(payload, ref valPos, 6, out var v5)) return null; values.Add(v5); break;
                case 6: if (!TryReadSignedInt(payload, ref valPos, 8, out var v6)) return null; values.Add(v6); break;
                case 7: if (valPos + 8 > payload.Length) return null; valPos += 8; values.Add(null); break;
                case 8: values.Add(0L); break;
                case 9: values.Add(1L); break;
                default:
                    if (st >= 12)
                    {
                        var len = (int)((st - (st % 2 == 0 ? 12 : 13)) / 2);
                        if (len < 0 || valPos + len > payload.Length) return null;
                        if (st % 2 == 0)
                        {
                            var blob = new byte[len];
                            Buffer.BlockCopy(payload, valPos, blob, 0, len);
                            values.Add(blob);
                        }
                        else
                        {
                            values.Add(Encoding.UTF8.GetString(payload, valPos, len));
                        }
                        valPos += len;
                    }
                    else values.Add(null);
                    break;
            }
        }
        return values;
    }

    private static bool TryReadSignedInt(byte[] data, ref int pos, int bytes, out long value)
    {
        value = 0;
        if (pos + bytes > data.Length) return false;
        long acc = (sbyte)data[pos++];
        for (var i = 1; i < bytes; i++) acc = (acc << 8) | data[pos++];
        value = acc;
        return true;
    }

    private static bool TryReadSqliteVarint(ReadOnlySpan<byte> data, ref int pos, out long value)
    {
        value = 0;
        for (var i = 0; i < 8; i++)
        {
            if (pos >= data.Length) return false;
            var b = data[pos++];
            value = (value << 7) | (long)(b & 0x7F);
            if ((b & 0x80) == 0) return true;
        }
        if (pos >= data.Length) return false;
        value = (value << 8) | data[pos++];
        return true;
    }

    private static uint ReadU32(byte[] data, int offset) =>
        ((uint)data[offset] << 24) | ((uint)data[offset + 1] << 16) | ((uint)data[offset + 2] << 8) | data[offset + 3];
}

public sealed class GeminiUsageClient(ICookieStore store, string? credentialRoot = null, HttpClient? http = null,
    Func<HttpClient, string?>? publicCredential = null, Func<string?>? credentialManagerReader = null,
    IReadOnlyList<string>? vscdbPaths = null)
{
    private static readonly HttpClient DefaultHttp = new(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(10) };
    private readonly HttpClient _http = http ?? DefaultHttp;

    private enum RefreshStatus { Ok, AuthRejected, TransientFailure }
    private readonly record struct RefreshResult(string? AccessToken, RefreshStatus Status, FetchOutcome Outcome = FetchOutcome.Unknown);

    private List<(JsonObject Effective, string? SourceRefresh)> ReadCredentialCandidates()
    {
        var results = new List<(JsonObject, string?)>();
        if (CookieUtil.CookieMap(store.GetHeader(GeminiOAuth.StorageKey)).GetValueOrDefault("disabled") == "1") return results;
        var cachedText = CookieUtil.CookieMap(store.GetHeader(GeminiOAuth.StorageKey)).GetValueOrDefault("oauth");
        JsonObject? cached = null;
        if (cachedText is not null)
        {
            try { cached = GeminiUsageParser.Object(Encoding.UTF8.GetString(Convert.FromBase64String(cachedText))); }
            catch { /* Try the original read-only sources. */ }
        }

        var seen = new HashSet<string>(StringComparer.Ordinal);
        void AddCandidate(JsonObject? token)
        {
            if (token is null) return;
            var sourceRefresh = token.Str("refresh_token");
            var sourceAccess = token.Str("access_token");
            var dedupeKey = (sourceRefresh ?? "") + "|" + (sourceAccess ?? "");
            if (dedupeKey == "|" || !seen.Add(dedupeKey)) return;
            var cachedMatches = sourceRefresh is not null && cached is not null
                && (sourceRefresh == cached.Str("source_refresh_token") || sourceRefresh == cached.Str("refresh_token"));
            results.Add((cachedMatches ? cached! : token, sourceRefresh));
        }

        // 1. Windows Credential Manager ("gemini:antigravity") — fresh Antigravity/agy sign-ins live here.
        // Skip host Credential Manager when a test explicitly overrides credentialRoot without supplying a reader.
        var readCredMan = credentialManagerReader ?? (credentialRoot is null ? () => GeminiCredentialSources.ReadWindowsCredentialManager() : null);
        if (readCredMan is not null)
        {
            try
            {
                var raw = readCredMan();
                if (raw is not null) AddCandidate(GeminiCredentialSources.FindToken(GeminiUsageParser.Object(raw)));
            }
            catch { /* Continue to other read-only sources. */ }
        }

        // 2. .gemini/oauth_creds.json and .gemini/antigravity*/**/*.json (read-only).
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
                    AddCandidate(GeminiCredentialSources.FindToken(GeminiUsageParser.Object(File.ReadAllText(file))));
                }
                catch { /* One malformed file must not hide other candidates. */ }
            }
        }
        catch { /* Missing or inaccessible .gemini directory. */ }

        // 3. Antigravity IDE state.vscdb (read-only; skip host %APPDATA% when credentialRoot is overridden without vscdbPaths).
        var dbPaths = vscdbPaths ?? (credentialRoot is null ? GeminiCredentialSources.DefaultVscdbPaths() : Array.Empty<string>());
        foreach (var dbPath in dbPaths)
        {
            try
            {
                AddCandidate(GeminiCredentialSources.ReadVscdbOAuthToken(dbPath));
            }
            catch { /* Malformed or locked state.vscdb must not hide other candidates. */ }
        }

        return results;
    }

    public ProviderResult Fetch(long now)
    {
        try
        {
            var candidates = ReadCredentialCandidates();
            if (candidates.Count == 0)
                return new ProviderResult.NeedsSignIn("Sign in to Antigravity on this PC, then refresh.");

            var sawAuthRejected = false;
            var sawTransientFailure = false;
            var transientOutcome = FetchOutcome.Unknown;

            foreach (var (credentials, sourceRefresh) in candidates)
            {
                var access = credentials.Str("access_token");
                var expiry = credentials.Num("expiry_date");
                if (expiry is > 0 and < 100_000_000_000) expiry *= 1000;
                if (expiry is null && DateTimeOffset.TryParse(credentials.Str("expiry"), CultureInfo.InvariantCulture,
                        DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var date))
                    expiry = date.ToUnixTimeMilliseconds();

                var refreshed = false;
                if (access is null || expiry <= now + 60_000)
                {
                    var refreshResult = Refresh(credentials, sourceRefresh, now);
                    if (refreshResult.Status == RefreshStatus.Ok && refreshResult.AccessToken is not null)
                    {
                        access = refreshResult.AccessToken;
                        refreshed = true;
                    }
                    else if (refreshResult.Status == RefreshStatus.AuthRejected)
                    {
                        sawAuthRejected = true;
                        continue;
                    }
                    else
                    {
                        sawTransientFailure = true;
                        transientOutcome = refreshResult.Outcome;
                        continue;
                    }
                }

                var client = Client(access);
                var snapshot = client.Fetch();
                if (snapshot is null && client.AuthRejected)
                {
                    if (!refreshed)
                    {
                        var retry = Refresh(credentials, sourceRefresh, now);
                        if (retry.Status == RefreshStatus.Ok && retry.AccessToken is not null)
                        {
                            var retryClient = Client(retry.AccessToken);
                            snapshot = retryClient.Fetch();
                            if (snapshot is not null)
                                return Success(snapshot, now);
                            if (retryClient.AuthRejected) { sawAuthRejected = true; continue; }
                            sawTransientFailure = true;
                            transientOutcome = FetchOutcome.Network;
                            continue;
                        }
                        if (retry.Status == RefreshStatus.AuthRejected) { sawAuthRejected = true; continue; }
                        sawTransientFailure = true;
                        transientOutcome = retry.Outcome;
                        continue;
                    }
                    sawAuthRejected = true;
                    continue;
                }

                if (snapshot is not null)
                    return Success(snapshot, now);

                sawTransientFailure = true;
                transientOutcome = FetchOutcome.Network;
            }

            if (sawTransientFailure)
                return new ProviderResult.Failure("Gemini quota unavailable. Try again later.", "cloud-code",
                    new[] { FetchAttempt.Of(now, SoftwareSectionId.Gemini, transientOutcome, message: "Gemini quota unavailable. Try again later.", source: "cloud-code") });
            if (sawAuthRejected)
                return new ProviderResult.NeedsSignIn("Antigravity session expired. Sign in again on this PC.",
                    new[] { FetchAttempt.Of(now, SoftwareSectionId.Gemini, FetchOutcome.AuthExpired, message: "Antigravity session expired. Sign in again on this PC.", source: "cloud-code") });
            return new ProviderResult.Failure("Gemini quota unavailable. Try again later.", "cloud-code",
                new[] { FetchAttempt.Of(now, SoftwareSectionId.Gemini, FetchOutcome.Unknown, message: "Gemini quota unavailable. Try again later.", source: "cloud-code") });
        }
        catch (Exception ex)
        {
            var attempt = FetchErrorClassifier.Attempt(ex, SoftwareSectionId.Gemini, null, now, "cloud-code");
            return new ProviderResult.Failure("Gemini quota unavailable. Try again later.", "cloud-code", new[] { attempt });
        }
    }

    private static ProviderResult.Success Success(ProviderSnapshot snapshot, long now) =>
        new(snapshot, new[] { FetchAttempt.Of(now, SoftwareSectionId.Gemini, FetchOutcome.Success, source: "cloud-code") });

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

    private RefreshResult Refresh(JsonObject credentials, string? sourceRefresh, long now)
    {
        var refresh = credentials.Str("refresh_token");
        if (refresh is null) return new(null, RefreshStatus.AuthRejected, FetchOutcome.AuthExpired);
        var secret = (publicCredential ?? GeminiOAuth.PublicClientCredential)(_http);
        if (secret is null) return new(null, RefreshStatus.TransientFailure, FetchOutcome.Network);
        try
        {
            using var body = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["client_id"] = GeminiOAuth.ClientId,
                ["client_secret"] = secret,
                ["grant_type"] = "refresh_token",
                ["refresh_token"] = refresh
            });
            using var response = _http.PostAsync(GeminiOAuth.TokenEndpoint, body).GetAwaiter().GetResult();
            if (!response.IsSuccessStatusCode)
            {
                if (response.StatusCode is HttpStatusCode.BadRequest or HttpStatusCode.Unauthorized)
                    return new(null, RefreshStatus.AuthRejected, FetchOutcome.AuthExpired);
                return new(null, RefreshStatus.TransientFailure, FetchOutcome.Http);
            }
            var token = GeminiUsageParser.Object(response.Content.ReadAsStringAsync().GetAwaiter().GetResult());
            var access = token.Str("access_token");
            if (access is null) return new(null, RefreshStatus.TransientFailure, FetchOutcome.Parse);
            var saved = new JsonObject
            {
                ["access_token"] = access,
                ["refresh_token"] = token.Str("refresh_token") ?? refresh,
                ["source_refresh_token"] = sourceRefresh ?? refresh,
                ["expiry_date"] = now + (token.Num("expires_in") ?? 3600) * 1000
            };
            store.SaveCookies(GeminiOAuth.StorageKey, "oauth=" + Convert.ToBase64String(Encoding.UTF8.GetBytes(saved.ToJsonString())));
            return new(access, RefreshStatus.Ok, FetchOutcome.Success);
        }
        catch (Exception ex) when (ex is TaskCanceledException or TimeoutException)
        {
            return new(null, RefreshStatus.TransientFailure, FetchOutcome.Timeout);
        }
        catch
        {
            return new(null, RefreshStatus.TransientFailure, FetchOutcome.Network);
        }
    }
}
