using UsageWidget;
using Xunit;

namespace UsageWidget.Tests;

public class SessionPasteTest
{
    [Fact]
    public void ParsesCookieHeader()
    {
        var map = SessionPaste.Parse(ProviderId.OpenAi, "Cookie: __Secure-next-auth.session-token=abc.def; oai-did=device");
        Assert.Equal("abc.def", map["__Secure-next-auth.session-token"]);
        Assert.Equal("device", map["oai-did"]);
    }

    [Fact]
    public void WrapsBareChatgptToken()
    {
        var map = SessionPaste.Parse(ProviderId.OpenAi, "eyJhbGciOiJIUzI1NiJ9.payload.sig");
        Assert.Equal("eyJhbGciOiJIUzI1NiJ9.payload.sig", map[SessionPaste.DefaultCookieName(ProviderId.OpenAi)]);
    }

    [Fact]
    public void WrapsBareCursorToken()
    {
        var map = SessionPaste.Parse(ProviderId.Cursor, "user_REDACTED::token");
        Assert.Equal("user_REDACTED::token", map["WorkosCursorSessionToken"]);
    }

    [Fact]
    public void EmptyIsEmpty()
    {
        Assert.Empty(SessionPaste.Parse(ProviderId.OpenAi, "  "));
    }
}
