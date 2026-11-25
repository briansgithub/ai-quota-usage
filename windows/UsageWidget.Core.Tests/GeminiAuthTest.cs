using System.Net;
using System.Text;
using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class GeminiAuthTest
{
    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void ExpiryOr401RefreshesIntoAppStoreWithoutChangingSource(bool expired)
    {
        var root = Path.Combine(Path.GetTempPath(), "gemini-unit-" + Guid.NewGuid());
        Directory.CreateDirectory(Path.Combine(root, "antigravity-test"));
        var path = Path.Combine(root, "antigravity-test", "oauth_creds.json");
        var source = "{\"access_token\":\"synthetic-access\",\"refresh_token\":\"synthetic-refresh\",\"expiry_date\":" + (expired ? 1 : 9_000_000_000_000L) + "}";
        File.WriteAllText(path, source);
        try
        {
            var store = new MemoryCookieStore();
            var refreshCalls = 0;
            var headers = new List<(string Agent, string? ContentType)>();
            using var http = new HttpClient(new Handler(request =>
            {
                if (request.RequestUri!.AbsoluteUri == GeminiOAuth.TokenEndpoint)
                {
                    refreshCalls++;
                    return new(HttpStatusCode.OK) { Content = new StringContent("{\"access_token\":\"synthetic-renewed\",\"expires_in\":3600}") };
                }
                headers.Add((request.Headers.UserAgent.ToString(), request.Content?.Headers.ContentType?.MediaType));
                var renewed = request.Headers.Authorization?.Parameter == "synthetic-renewed";
                return new(renewed ? HttpStatusCode.OK : HttpStatusCode.Unauthorized)
                { Content = new StringContent(renewed ? Fixture.Load("gemini-production.json") : Fixture.Load("gemini-failure.json")) };
            }));
            var client = new GeminiUsageClient(store, root, http, _ => "synthetic-public-client-credential");
            Assert.IsType<ProviderResult.Success>(client.Fetch(2_000_000_000_000L));
            Assert.Equal(1, refreshCalls);
            Assert.All(headers, header =>
            {
                Assert.Equal(GeminiOAuth.UserAgent, header.Agent);
                Assert.Equal("application/json", header.ContentType);
            });
            Assert.Equal(source, File.ReadAllText(path));
            Assert.NotNull(store.GetHeader(GeminiOAuth.StorageKey));
            Assert.IsType<ProviderResult.Success>(client.Fetch(2_000_000_001_000L));
            Assert.Equal(1, refreshCalls);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void CredentialManagerPreferredOverStaleOauthCredsAndHandlesRotatedRefreshToken()
    {
        var root = Path.Combine(Path.GetTempPath(), "gemini-credman-" + Guid.NewGuid());
        Directory.CreateDirectory(root);
        var staleFile = Path.Combine(root, "oauth_creds.json");
        var staleJson = """{"access_token":"synthetic-stale-access","refresh_token":"synthetic-stale-refresh","expiry_date":1}""";
        File.WriteAllText(staleFile, staleJson);
        try
        {
            var credManJson = """
                {
                  "token": {
                    "access_token": "synthetic-cm-old",
                    "token_type": "Bearer",
                    "refresh_token": "synthetic-cm-refresh",
                    "expiry": "2020-01-01T00:00:00Z",
                    "expires_in": 3599
                  },
                  "auth_method": "oauth",
                  "id_token_email": "user@example.test"
                }
                """;
            // Verify both UTF-8 and UTF-16LE credential blobs decode cleanly.
            Assert.NotNull(GeminiCredentialSources.DecodeCredentialBlob(Encoding.UTF8.GetBytes(credManJson)));
            Assert.NotNull(GeminiCredentialSources.DecodeCredentialBlob(Encoding.Unicode.GetBytes(credManJson)));

            var store = new MemoryCookieStore();
            var refreshCalls = 0;
            using var http = new HttpClient(new Handler(request =>
            {
                if (request.RequestUri!.AbsoluteUri == GeminiOAuth.TokenEndpoint)
                {
                    refreshCalls++;
                    var form = request.Content!.ReadAsStringAsync().GetAwaiter().GetResult();
                    if (!form.Contains("synthetic-cm-refresh"))
                        return new(HttpStatusCode.BadRequest) { Content = new StringContent("""{"error":"invalid_grant"}""") };
                    return new(HttpStatusCode.OK)
                    {
                        Content = new StringContent("""{"access_token":"synthetic-cm-fresh","refresh_token":"synthetic-cm-rotated","expires_in":3600}""")
                    };
                }
                var ok = request.Headers.Authorization?.Parameter == "synthetic-cm-fresh";
                return new(ok ? HttpStatusCode.OK : HttpStatusCode.Unauthorized)
                { Content = new StringContent(ok ? Fixture.Load("gemini-production.json") : Fixture.Load("gemini-failure.json")) };
            }));

            var client = new GeminiUsageClient(store, root, http, _ => "synthetic-public-client-credential",
                credentialManagerReader: () => credManJson);
            var first = client.Fetch(2_000_000_000_000L);
            Assert.IsType<ProviderResult.Success>(first);
            Assert.Equal(1, refreshCalls);
            Assert.Equal(staleJson, File.ReadAllText(staleFile));

            // Subsequent fetch must reuse DPAPI-cached token even though refresh_token rotated.
            var second = client.Fetch(2_000_000_005_000L);
            Assert.IsType<ProviderResult.Success>(second);
            Assert.Equal(1, refreshCalls);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void FallsBackFromStaleFileToVscdbProtobufAndSupportsWalOverlay()
    {
        var root = Path.Combine(Path.GetTempPath(), "gemini-vscdb-" + Guid.NewGuid());
        Directory.CreateDirectory(root);
        var staleFile = Path.Combine(root, "oauth_creds.json");
        File.WriteAllText(staleFile, """{"access_token":"synthetic-dead","refresh_token":"synthetic-dead-refresh","expiry_date":1}""");
        var dbPath = Path.Combine(root, "state.vscdb");
        try
        {
            var innerProto = BuildOAuthProtobuf("ya29.synthetic-vscdb-access", "1//synthetic-vscdb-refresh", 9_000_000_000L);
            var outerProto = WrapBase64InProtobuf(Convert.ToBase64String(innerProto));
            var vscdbValue = Convert.ToBase64String(outerProto);
            WriteMinimalSqliteItemTable(dbPath, GeminiOAuth.VscdbOAuthKey, "stale-uncommitted-value");
            WriteMinimalSqliteWal(dbPath + "-wal", BuildItemTableLeafPage(4096, GeminiOAuth.VscdbOAuthKey, vscdbValue));

            var decoded = GeminiCredentialSources.ReadVscdbOAuthToken(dbPath);
            Assert.NotNull(decoded);
            Assert.Equal("ya29.synthetic-vscdb-access", decoded!.Str("access_token"));
            Assert.Equal("1//synthetic-vscdb-refresh", decoded.Str("refresh_token"));

            var store = new MemoryCookieStore();
            using var http = new HttpClient(new Handler(request =>
            {
                if (request.RequestUri!.AbsoluteUri == GeminiOAuth.TokenEndpoint)
                    return new(HttpStatusCode.BadRequest) { Content = new StringContent("""{"error":"invalid_grant"}""") };
                var ok = request.Headers.Authorization?.Parameter == "ya29.synthetic-vscdb-access";
                return new(ok ? HttpStatusCode.OK : HttpStatusCode.Unauthorized)
                { Content = new StringContent(ok ? Fixture.Load("gemini-production.json") : Fixture.Load("gemini-failure.json")) };
            }));

            var client = new GeminiUsageClient(store, root, http, _ => "synthetic-public-client-credential",
                vscdbPaths: new[] { dbPath });
            Assert.IsType<ProviderResult.Success>(client.Fetch(2_000_000_000_000L));
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void TransientRefreshErrorReturnsFailureWhileRevokedTokenReturnsNeedsSignIn()
    {
        var root = Path.Combine(Path.GetTempPath(), "gemini-err-" + Guid.NewGuid());
        Directory.CreateDirectory(root);
        File.WriteAllText(Path.Combine(root, "oauth_creds.json"),
            """{"access_token":"synthetic-old","refresh_token":"synthetic-refresh","expiry_date":1}""");
        try
        {
            var store = new MemoryCookieStore();
            using var outageHttp = new HttpClient(new Handler(_ => new(HttpStatusCode.ServiceUnavailable) { Content = new StringContent("{}") }));
            var outageClient = new GeminiUsageClient(store, root, outageHttp, _ => "synthetic-public-client-credential");
            var outageResult = Assert.IsType<ProviderResult.Failure>(outageClient.Fetch(2_000_000_000_000L));
            Assert.Equal(FetchOutcome.Http, outageResult.AttemptList.Single().OutcomeEnum);

            var noCredClient = new GeminiUsageClient(store, root, outageHttp, _ => null);
            var noCredResult = Assert.IsType<ProviderResult.Failure>(noCredClient.Fetch(2_000_000_000_000L));
            Assert.Equal(FetchOutcome.Network, noCredResult.AttemptList.Single().OutcomeEnum);

            using var revokedHttp = new HttpClient(new Handler(_ => new(HttpStatusCode.BadRequest) { Content = new StringContent("""{"error":"invalid_grant"}""") }));
            var revokedClient = new GeminiUsageClient(store, root, revokedHttp, _ => "synthetic-public-client-credential");
            var expired = Assert.IsType<ProviderResult.NeedsSignIn>(revokedClient.Fetch(2_000_000_000_000L));
            Assert.Equal(FetchOutcome.AuthExpired, expired.AttemptList.Single().OutcomeEnum);

            // SnapshotMerger surfaces expired local credentials as NEEDS_SIGN_IN instead of UNSIGNED.
            var merged = SnapshotMerger.Merge(
                UsageSnapshot.Empty(2_000_000_000_000L),
                new[] { new SnapshotMerger.ProviderRound(ProviderId.Gemini, expired, false) },
                2_000_000_000_000L);
            Assert.Equal(ProviderStatus.NeedsSignIn, merged.Provider(ProviderId.Gemini)!.ProviderStatus);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void Api401WithFailedRefreshReturnsNeedsSignInNotGenericFailure()
    {
        var root = Path.Combine(Path.GetTempPath(), "gemini-401-" + Guid.NewGuid());
        Directory.CreateDirectory(root);
        File.WriteAllText(Path.Combine(root, "oauth_creds.json"),
            """{"access_token":"synthetic-rejected","refresh_token":"synthetic-revoked","expiry_date":9000000000000}""");
        try
        {
            using var http = new HttpClient(new Handler(req =>
                req.RequestUri!.AbsoluteUri == GeminiOAuth.TokenEndpoint
                    ? new(HttpStatusCode.BadRequest) { Content = new StringContent("""{"error":"invalid_grant"}""") }
                    : new(HttpStatusCode.Unauthorized) { Content = new StringContent(Fixture.Load("gemini-failure.json")) }));
            var client = new GeminiUsageClient(new MemoryCookieStore(), root, http, _ => "synthetic-public-client-credential");
            var result = Assert.IsType<ProviderResult.NeedsSignIn>(client.Fetch(2_000_000_000_000L));
            Assert.Equal(FetchOutcome.AuthExpired, result.AttemptList.Single().OutcomeEnum);
        }
        finally { Directory.Delete(root, true); }
    }

    private static byte[] BuildOAuthProtobuf(string access, string refresh, long expirySeconds)
    {
        using var ms = new MemoryStream();
        WriteProtoString(ms, 1, access);
        WriteProtoString(ms, 2, "Bearer");
        WriteProtoString(ms, 3, refresh);
        using var ts = new MemoryStream();
        WriteProtoVarint(ts, (1u << 3) | 0u);
        WriteProtoVarint(ts, (ulong)expirySeconds);
        WriteProtoBytes(ms, 4, ts.ToArray());
        return ms.ToArray();
    }

    private static byte[] WrapBase64InProtobuf(string innerBase64)
    {
        using var innerMsg = new MemoryStream();
        WriteProtoString(innerMsg, 1, "oauthTokenInfoSentinelKey");
        using var valWrap = new MemoryStream();
        WriteProtoString(valWrap, 1, innerBase64);
        WriteProtoBytes(innerMsg, 2, valWrap.ToArray());
        using var outer = new MemoryStream();
        WriteProtoBytes(outer, 1, innerMsg.ToArray());
        return outer.ToArray();
    }

    private static void WriteProtoString(Stream s, int field, string text) => WriteProtoBytes(s, field, Encoding.UTF8.GetBytes(text));

    private static void WriteProtoBytes(Stream s, int field, byte[] bytes)
    {
        WriteProtoVarint(s, ((ulong)field << 3) | 2u);
        WriteProtoVarint(s, (ulong)bytes.Length);
        s.Write(bytes, 0, bytes.Length);
    }

    private static void WriteProtoVarint(Stream s, ulong v)
    {
        while (v >= 0x80)
        {
            s.WriteByte((byte)((v & 0x7F) | 0x80));
            v >>= 7;
        }
        s.WriteByte((byte)v);
    }

    private static void WriteMinimalSqliteItemTable(string path, string key, string value)
    {
        const int pageSize = 4096;
        var page1 = new byte[pageSize];
        Encoding.ASCII.GetBytes("SQLite format 3\0").CopyTo(page1, 0);
        page1[16] = pageSize >> 8;
        page1[17] = pageSize & 0xFF;
        page1[18] = 2;
        page1[19] = 2;
        var schemaRecord = EncodeSqliteRecord("table", "ItemTable", "ItemTable", 2L, "CREATE TABLE ItemTable (key TEXT UNIQUE, value BLOB)");
        WriteLeafPage(page1, 100, schemaRecord);

        var page2 = BuildItemTableLeafPage(pageSize, key, value);
        using var fs = File.Create(path);
        fs.Write(page1);
        fs.Write(page2);
    }

    private static byte[] BuildItemTableLeafPage(int pageSize, string key, string value)
    {
        var page = new byte[pageSize];
        var record = EncodeSqliteRecord(key, value);
        WriteLeafPage(page, 0, record);
        return page;
    }

    private static void WriteLeafPage(byte[] page, int hdrOffset, byte[] record)
    {
        using var cellMs = new MemoryStream();
        WriteSqliteVarint(cellMs, record.Length);
        WriteSqliteVarint(cellMs, 1); // rowid
        cellMs.Write(record);
        var cell = cellMs.ToArray();
        var cellStart = page.Length - cell.Length;
        Buffer.BlockCopy(cell, 0, page, cellStart, cell.Length);
        page[hdrOffset] = 0x0D; // leaf table page
        page[hdrOffset + 3] = 0;
        page[hdrOffset + 4] = 1; // 1 cell
        page[hdrOffset + 5] = (byte)(cellStart >> 8);
        page[hdrOffset + 6] = (byte)(cellStart & 0xFF);
        page[hdrOffset + 8] = (byte)(cellStart >> 8);
        page[hdrOffset + 9] = (byte)(cellStart & 0xFF);
    }

    private static byte[] EncodeSqliteRecord(params object[] cols)
    {
        using var typesMs = new MemoryStream();
        using var bodyMs = new MemoryStream();
        foreach (var col in cols)
        {
            if (col is string s)
            {
                var bytes = Encoding.UTF8.GetBytes(s);
                WriteSqliteVarint(typesMs, bytes.Length * 2L + 13);
                bodyMs.Write(bytes);
            }
            else if (col is long l)
            {
                WriteSqliteVarint(typesMs, 1);
                bodyMs.WriteByte((byte)l);
            }
        }
        var typeBytes = typesMs.ToArray();
        var headerLen = typeBytes.Length + 1;
        if (headerLen >= 128) headerLen++;
        using var full = new MemoryStream();
        WriteSqliteVarint(full, headerLen);
        full.Write(typeBytes);
        full.Write(bodyMs.ToArray());
        return full.ToArray();
    }

    private static void WriteSqliteVarint(Stream s, long value)
    {
        var buf = new List<byte>();
        buf.Add((byte)(value & 0x7F));
        value >>= 7;
        while (value > 0)
        {
            buf.Add((byte)((value & 0x7F) | 0x80));
            value >>= 7;
        }
        buf.Reverse();
        foreach (var b in buf) s.WriteByte(b);
    }

    private static void WriteMinimalSqliteWal(string walPath, byte[] page2)
    {
        var wal = new byte[32 + 24 + page2.Length];
        WriteU32(wal, 0, 0x377F0682u);
        WriteU32(wal, 4, 3007000u);
        WriteU32(wal, 8, (uint)page2.Length);
        WriteU32(wal, 16, 0x11223344u);
        WriteU32(wal, 20, 0x55667788u);
        // Frame 1: page 2 committed (dbSizeAfterCommit = 2)
        WriteU32(wal, 32, 2u);
        WriteU32(wal, 36, 2u);
        WriteU32(wal, 40, 0x11223344u);
        WriteU32(wal, 44, 0x55667788u);
        Buffer.BlockCopy(page2, 0, wal, 56, page2.Length);
        File.WriteAllBytes(walPath, wal);
    }

    private static void WriteU32(byte[] buf, int offset, uint val)
    {
        buf[offset] = (byte)(val >> 24);
        buf[offset + 1] = (byte)(val >> 16);
        buf[offset + 2] = (byte)(val >> 8);
        buf[offset + 3] = (byte)val;
    }

    private sealed class Handler(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => Task.FromResult(respond(request));
    }
}
