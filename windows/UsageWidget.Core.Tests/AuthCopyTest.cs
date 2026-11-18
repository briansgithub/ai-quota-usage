using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class AuthCopyTest
{
    [Fact]
    public void EachSectionHasItsOwnUnsignedAndExpiredCopy()
    {
        Assert.Equal("Not signed in", AuthCopy.StatusLine(SoftwareSectionId.Cursor, ProviderStatus.Unsigned));
        Assert.Equal("Not signed in (uses Cursor)", AuthCopy.StatusLine(SoftwareSectionId.GrokBot, ProviderStatus.Unsigned));
        Assert.Equal("Not signed in", AuthCopy.StatusLine(SoftwareSectionId.Codex, ProviderStatus.Unsigned));
        Assert.Equal("Sign-in expired — sign in again", AuthCopy.StatusLine(SoftwareSectionId.Cursor, ProviderStatus.NeedsSignIn));
        Assert.Equal("Sign-in expired — sign in again", AuthCopy.StatusLine(SoftwareSectionId.GrokBot, ProviderStatus.NeedsSignIn));
        Assert.Equal("Sign-in expired — sign in again", AuthCopy.StatusLine(SoftwareSectionId.Codex, ProviderStatus.NeedsSignIn));
    }

    [Fact]
    public void GrokSignedInIsViaCursorNotAThirdAccount()
    {
        Assert.Equal("Signed in via Cursor", AuthCopy.StatusLine(SoftwareSectionId.GrokBot, ProviderStatus.Ok));
        Assert.Equal("Signed in", AuthCopy.StatusLine(SoftwareSectionId.Cursor, ProviderStatus.Ok));
        Assert.Equal("Signed in", AuthCopy.StatusLine(SoftwareSectionId.Codex, ProviderStatus.Ok));
    }

    [Fact]
    public void VisibleSignInOnlyWhenAuthActuallyNeedsIt()
    {
        Assert.Equal("Sign in", AuthCopy.SignInButton(ProviderStatus.Unsigned));
        Assert.Equal("Sign in again", AuthCopy.SignInButton(ProviderStatus.NeedsSignIn));
        Assert.True(AuthCopy.ShowSignIn(Unsigned()));
        Assert.True(AuthCopy.ShowSignIn(WithStatus(ProviderStatus.NeedsSignIn)));
        Assert.False(AuthCopy.ShowSignIn(WithStatus(ProviderStatus.Ok)));
        Assert.False(AuthCopy.ShowSignIn(NetworkError()));
        Assert.True(AuthCopy.ShowRetry(NetworkError()));
        Assert.True(AuthCopy.ShowSignIn(AuthHttpError()));
        Assert.False(AuthCopy.ShowRetry(AuthHttpError()));
        Assert.True(AuthCopy.ShowSignOut(ProviderStatus.Ok));
        Assert.False(AuthCopy.ShowSignOut(ProviderStatus.Unsigned));
        Assert.True(AuthCopy.IsReauth(WithStatus(ProviderStatus.NeedsSignIn)));
        Assert.False(AuthCopy.IsReauth(Unsigned()));
        Assert.False(AuthCopy.IsReauth(NetworkError()));
    }

    [Fact]
    public void SignOutMenuNamesSharedCursorSession()
    {
        Assert.Equal("Sign out (also signs out Grok Bot)", AuthCopy.SignOutMenuItem(SoftwareSectionId.Cursor));
        Assert.Equal("Sign out (also signs out Cursor)", AuthCopy.SignOutMenuItem(SoftwareSectionId.GrokBot));
        Assert.Equal("Sign out", AuthCopy.SignOutMenuItem(SoftwareSectionId.Codex));
    }

    [Fact]
    public void WindowTitlesNameTheRealLoginSite()
    {
        Assert.Equal("Sign in to Cursor", AuthCopy.SignInWindowTitle(SoftwareSectionId.Cursor));
        Assert.Equal("Sign in to Cursor for Grok Bot", AuthCopy.SignInWindowTitle(SoftwareSectionId.GrokBot));
        Assert.Equal("Sign in to ChatGPT for Codex", AuthCopy.SignInWindowTitle(SoftwareSectionId.Codex));
        Assert.Contains("no separate xAI/Grok login", AuthCopy.SignInHint(SoftwareSectionId.GrokBot));
    }

    private static SoftwareSection Unsigned() =>
        new(SoftwareSectionId.Cursor, ProviderId.Cursor, ProviderStatus.Unsigned);

    private static SoftwareSection WithStatus(ProviderStatus status) =>
        new(SoftwareSectionId.Cursor, ProviderId.Cursor, status);

    private static SoftwareSection NetworkError() =>
        new(SoftwareSectionId.Codex, ProviderId.OpenAi, ProviderStatus.Error,
            Health: new SectionHealth("codex", LastOutcome: "NETWORK", ShortReason: "Network error", SignedIn: true),
            Stale: new StaleView(true, StaleSeverity.Amber, "Last updated 3m ago", "Network error", false));

    private static SoftwareSection AuthHttpError() =>
        new(SoftwareSectionId.Codex, ProviderId.OpenAi, ProviderStatus.Error,
            Health: new SectionHealth("codex", LastOutcome: "HTTP", HttpStatus: 401, ShortReason: "HTTP 401", SignedIn: true));
}
