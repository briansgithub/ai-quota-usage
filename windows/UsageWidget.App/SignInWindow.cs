using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using UsageWidget;

namespace UsageWidget.App;

internal sealed class SignInWindow : Window
{
    private readonly AppHost _host;
    private readonly ProviderId _provider;
    private readonly SoftwareSectionId _sectionId;
    private readonly bool _reauth;
    private readonly WebView2 _web = new();
    private readonly DispatcherTimer _poll = new() { Interval = TimeSpan.FromSeconds(1.2) };
    private readonly TextBlock _hint;
    private bool _captured;
    private bool _closing;

    public SignInWindow(AppHost host, ProviderId provider, SoftwareSectionId sectionId, bool reauth)
    {
        _host = host;
        _provider = provider;
        _sectionId = sectionId;
        _reauth = reauth;
        Title = AuthCopy.SignInWindowTitle(sectionId);
        Icon = AppBranding.ImageSource;
        Width = 980;
        Height = 720;
        Background = Theme.ChromeBrush;

        var root = new DockPanel();
        var bar = new DockPanel { LastChildFill = true, Margin = new Thickness(12) };
        var paste = Ui.Ghost("Paste cookie…", (_, _) => PasteCookie());
        var done = Ui.Primary("Done", async (_, _) => await CaptureAndClose(requireSession: false));
        DockPanel.SetDock(done, Dock.Right);
        DockPanel.SetDock(paste, Dock.Right);
        bar.Children.Add(done);
        bar.Children.Add(paste);
        _hint = Ui.T(AuthCopy.SignInHint(sectionId), Theme.MutedBrush, 12);
        bar.Children.Add(_hint);
        DockPanel.SetDock(bar, Dock.Top);
        root.Children.Add(bar);
        root.Children.Add(_web);
        Content = root;
        Loaded += async (_, _) => await InitAsync();
        Closed += (_, _) => _poll.Stop();
    }

    private async Task InitAsync()
    {
        var env = await CoreWebView2Environment.CreateAsync(userDataFolder: Paths.WebViewUserData);
        await _web.EnsureCoreWebView2Async(env);
        if (_reauth)
            await WebViewCookieBridge.ClearSessionCookiesAsync(_web, _provider);
        else
            await WebViewCookieBridge.RestoreAsync(_web, _host.Cookies, _provider);
        _web.CoreWebView2.Settings.AreDefaultContextMenusEnabled = true;
        _web.CoreWebView2.Settings.AreDevToolsEnabled = false;
        var url = _provider == ProviderId.Cursor ? CursorEndpoints.LoginPage : OpenAiEndpoints.LoginPage;
        _web.CoreWebView2.Navigate(url);
        _poll.Tick += async (_, _) => await PollSession();
        _poll.Start();
        _hint.Text = AuthCopy.SignInHint(_sectionId) + " Waiting for the session cookie…";
    }

    private async Task PollSession()
    {
        if (_captured || _closing || _host.IsExiting || _web.CoreWebView2 is null) return;
        if (!await WebViewCookieBridge.WebViewHasSessionAsync(_web, _provider)) return;
        _hint.Text = "Got the session cookie. Closing and refreshing…";
        await CaptureAndClose(requireSession: true);
    }

    private async Task CaptureAndClose(bool requireSession)
    {
        if (_closing) return;
        if (_web.CoreWebView2 is not null)
            await WebViewCookieBridge.CaptureAsync(_web, _host.Cookies, _provider);
        var has = _host.Cookies.HasSession(_provider);
        if (requireSession && !has) return;
        _poll.Stop();
        _captured = true;
        _closing = true;
        if (has) _host.OnSignedIn(_provider);
        Close();
    }

    private void PasteCookie()
    {
        var dialog = new Window
        {
            Title = "Paste session cookie",
            Width = 520,
            Height = 280,
            Owner = this,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = Theme.ChromeBrush
        };
        var box = new TextBox
        {
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            Height = 140,
            Background = Theme.SurfaceBrush,
            Foreground = Theme.TextBrush,
            BorderBrush = Theme.OutlineBrush,
            Margin = new Thickness(0, 8, 0, 8)
        };
        var save = Ui.Primary("Import", (_, _) =>
        {
            if (_host.Cookies.ImportRawCookies(_provider, box.Text))
            {
                _host.OnSignedIn(_provider);
                dialog.Close();
                _closing = true;
                Close();
            }
            else
            {
                MessageBox.Show(this, "Could not find a session cookie in that text.", "Usage Widget");
            }
        });
        var stack = new StackPanel { Margin = new Thickness(16) };
        stack.Children.Add(Ui.T("Paste a Cookie header, name=value list, or the session token. Values are not logged.", Theme.MutedBrush, 12));
        stack.Children.Add(box);
        stack.Children.Add(save);
        dialog.Content = stack;
        dialog.ShowDialog();
    }
}
