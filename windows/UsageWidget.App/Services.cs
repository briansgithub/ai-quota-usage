using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Toolkit.Uwp.Notifications;
using Microsoft.Win32;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using UsageWidget;

namespace UsageWidget.App;

internal static class Paths
{
    public static string DataDir
    {
        get
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "UsageWidget");
            Directory.CreateDirectory(dir);
            return dir;
        }
    }

    public static string CookiesPath => Path.Combine(DataDir, "cookies.bin");
    public static string SnapshotPath => Path.Combine(DataDir, "snapshot.json");
    public static string SettingsPath => Path.Combine(DataDir, "settings.json");
    public static string LogPath => Path.Combine(DataDir, "log.txt");
    public static string WebViewUserData => Path.Combine(DataDir, "webview");
    public static string TestNotificationFlag => Path.Combine(DataDir, "test-notification.flag");
}

internal static class WidgetLog
{
    private static readonly object Gate = new();
    public static bool Enabled { get; private set; }

    public static void Configure(bool settingsFlag)
    {
        var env = Environment.GetEnvironmentVariable("USAGEWIDGET_DEBUG");
        Enabled = settingsFlag || string.Equals(env, "1", StringComparison.OrdinalIgnoreCase)
                  || string.Equals(env, "true", StringComparison.OrdinalIgnoreCase);
    }

    public static void Write(string message)
    {
        if (!Enabled) return;
        try
        {
            var line = $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff} {message}{Environment.NewLine}";
            lock (Gate) File.AppendAllText(Paths.LogPath, line);
        }
        catch { /* logging must never break the UI */ }
    }
}

internal sealed class DpapiCookieStore : ICookieStore
{
    private readonly MemoryCookieStore _memory = new();
    private readonly object _gate = new();

    public DpapiCookieStore()
    {
        Load();
    }

    public string? GetHeader(string host) { lock (_gate) return _memory.GetHeader(host); }
    public bool HasSession(ProviderId provider) { lock (_gate) return _memory.HasSession(provider); }

    public void SaveCookies(string host, string cookieHeader)
    {
        lock (_gate)
        {
            _memory.SaveCookies(host, cookieHeader);
            Persist();
        }
    }

    public void ApplySetCookieHeaders(string host, IEnumerable<string> setCookieHeaders)
    {
        lock (_gate)
        {
            _memory.ApplySetCookieHeaders(host, setCookieHeaders);
            Persist();
        }
    }

    public void Clear(ProviderId provider)
    {
        lock (_gate)
        {
            _memory.Clear(provider);
            Persist();
        }
    }

    public bool ImportRawCookies(ProviderId provider, string raw)
    {
        lock (_gate)
        {
            var ok = _memory.ImportRawCookies(provider, raw);
            Persist();
            return ok;
        }
    }

    public string? SnapshotJson()
    {
        try { return File.Exists(Paths.SnapshotPath) ? File.ReadAllText(Paths.SnapshotPath) : null; }
        catch { return null; }
    }

    public void SaveSnapshotJson(string json)
    {
        File.WriteAllText(Paths.SnapshotPath, json);
    }

    public Dictionary<string, string> DumpCookies()
    {
        lock (_gate)
        {
            var dump = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var host in CursorEndpoints.CookieHosts.Concat(OpenAiEndpoints.CookieHosts))
            {
                var header = _memory.GetHeader(host);
                if (!string.IsNullOrWhiteSpace(header))
                    dump[host.StartsWith("www.", StringComparison.OrdinalIgnoreCase) ? host[4..] : host] = header!;
            }
            return dump;
        }
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(Paths.CookiesPath)) return;
            var protectedBytes = File.ReadAllBytes(Paths.CookiesPath);
            var json = Encoding.UTF8.GetString(ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.CurrentUser));
            var map = JsonSerializer.Deserialize<Dictionary<string, string>>(json) ?? new();
            foreach (var kv in map)
                _memory.SaveCookies(kv.Key, kv.Value);
        }
        catch { /* first run or corrupted */ }
    }

    private void Persist()
    {
        var dump = DumpCookies();
        var json = JsonSerializer.Serialize(dump);
        var bytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(json), null, DataProtectionScope.CurrentUser);
        File.WriteAllBytes(Paths.CookiesPath, bytes);
    }
}

internal static class StartupManager
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "UsageWidget";

    public static bool IsEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey, false);
        return key?.GetValue(ValueName) is string;
    }

    public static void SetEnabled(bool enabled)
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey, true) ?? Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled)
        {
            var exe = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule?.FileName;
            if (!string.IsNullOrWhiteSpace(exe))
                key.SetValue(ValueName, $"\"{exe}\"");
        }
        else
        {
            key.DeleteValue(ValueName, false);
        }
    }
}

internal static class ToastService
{
    private static volatile bool _stopped;

    public static void Stop()
    {
        _stopped = true;
        try { ToastNotificationManagerCompat.History.Clear(); }
        catch { /* unpackaged toast history may be unavailable */ }
    }

    public static void Show(string title, string body)
    {
        if (_stopped) return;
        try
        {
            new ToastContentBuilder()
                .AddText(title)
                .AddText(body)
                .Show();
        }
        catch
        {
            // Unpackaged toast COM can fail until a Start Menu shortcut exists.
        }
    }
}

internal sealed class WebViewHtmlExtractor : IHtmlExtractor
{
    private readonly Dispatcher _dispatcher;

    public WebViewHtmlExtractor(Dispatcher dispatcher) => _dispatcher = dispatcher;

    public Task<string> ExtractPageTextAsync(ProviderId provider, CancellationToken cancellationToken = default)
    {
        var url = provider == ProviderId.Cursor ? CursorEndpoints.SpendingPage : OpenAiEndpoints.UsagePage;
        return _dispatcher.InvokeAsync(() => ExtractOnUi(url, cancellationToken)).Task.Unwrap();
    }

    private static async Task<string> ExtractOnUi(string url, CancellationToken cancellationToken)
    {
        var window = new Window
        {
            Width = 1,
            Height = 1,
            WindowStyle = WindowStyle.None,
            ShowInTaskbar = false,
            Opacity = 0,
            ShowActivated = false,
            Title = "Usage Widget extract"
        };
        var web = new WebView2();
        window.Content = web;
        window.Show();
        try
        {
            var env = await CoreWebView2Environment.CreateAsync(userDataFolder: Paths.WebViewUserData);
            await web.EnsureCoreWebView2Async(env);
            var tcs = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
            web.NavigationCompleted += async (_, args) =>
            {
                if (!args.IsSuccess) return;
                try
                {
                    await Task.Delay(1200, cancellationToken);
                    var raw = await web.ExecuteScriptAsync("document.body ? document.body.innerText : ''");
                    tcs.TrySetResult(UnquoteJsString(raw));
                }
                catch (Exception ex) { tcs.TrySetException(ex); }
            };
            web.CoreWebView2.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All);
            web.CoreWebView2.WebResourceRequested += (_, e) =>
            {
                e.Request.Headers.SetHeader("Cache-Control", "no-cache, no-store");
                e.Request.Headers.SetHeader("Pragma", "no-cache");
            };
            web.CoreWebView2.Navigate(url);
            using var reg = cancellationToken.Register(() => tcs.TrySetCanceled(cancellationToken));
            var completed = await Task.WhenAny(tcs.Task, Task.Delay(25_000, cancellationToken));
            if (completed != tcs.Task)
                throw new TimeoutException("Timed out reading usage page");
            return await tcs.Task;
        }
        finally
        {
            window.Close();
        }
    }

    internal static string UnquoteJsString(string raw)
    {
        if (string.IsNullOrEmpty(raw) || raw == "null") return "";
        try { return JsonSerializer.Deserialize<string>(raw) ?? raw.Trim('"'); }
        catch { return raw.Trim('"'); }
    }
}

internal static class WebViewCookieBridge
{
    public static bool IsSessionCookie(ProviderId provider, string name) =>
        provider == ProviderId.Cursor
            ? name.Equals(CursorEndpoints.SessionCookie, StringComparison.OrdinalIgnoreCase)
            : name.StartsWith(OpenAiEndpoints.SessionCookiePrefix, StringComparison.OrdinalIgnoreCase);

    public static async Task CaptureAsync(WebView2 web, ICookieStore store, ProviderId provider)
    {
        if (web.CoreWebView2 is null) return;
        foreach (var host in CookieUtil.HostsFor(provider))
        {
            var cookies = await web.CoreWebView2.CookieManager.GetCookiesAsync($"https://{host}");
            if (cookies.Count == 0) continue;
            var header = string.Join("; ", cookies.Select(c => $"{c.Name}={c.Value}"));
            store.SaveCookies(host, header);
        }
    }

    public static async Task<bool> WebViewHasSessionAsync(WebView2 web, ProviderId provider)
    {
        if (web.CoreWebView2 is null) return false;
        foreach (var host in CookieUtil.HostsFor(provider))
        {
            var cookies = await web.CoreWebView2.CookieManager.GetCookiesAsync($"https://{host}");
            if (cookies.Any(c => IsSessionCookie(provider, c.Name) && !string.IsNullOrWhiteSpace(c.Value)))
                return true;
        }
        return false;
    }

    public static async Task ClearSessionCookiesAsync(WebView2 web, ProviderId provider)
    {
        if (web.CoreWebView2 is null) return;
        foreach (var host in CookieUtil.HostsFor(provider))
        {
            var cookies = await web.CoreWebView2.CookieManager.GetCookiesAsync($"https://{host}");
            foreach (var cookie in cookies.Where(c => IsSessionCookie(provider, c.Name)))
                web.CoreWebView2.CookieManager.DeleteCookie(cookie);
        }
    }

    public static async Task RestoreAsync(WebView2 web, ICookieStore store, ProviderId provider)
    {
        if (web.CoreWebView2 is null) return;
        foreach (var host in CookieUtil.HostsFor(provider))
        {
            var header = store.GetHeader(host);
            if (string.IsNullOrWhiteSpace(header)) continue;
            foreach (var kv in CookieUtil.CookieMap(header))
            {
                var cookie = web.CoreWebView2.CookieManager.CreateCookie(kv.Key, kv.Value, host.StartsWith("www.") ? host[4..] : host, "/");
                cookie.IsSecure = true;
                web.CoreWebView2.CookieManager.AddOrUpdateCookie(cookie);
            }
        }
        await Task.CompletedTask;
    }
}
