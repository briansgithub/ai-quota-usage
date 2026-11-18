namespace UsageWidget;

public static class AuthCopy
{
    public static string StatusLine(SoftwareSectionId id, ProviderStatus status) => status switch
    {
        ProviderStatus.Ok => id == SoftwareSectionId.GrokBot ? "Signed in via Cursor" : "Signed in",
        ProviderStatus.NeedsSignIn => "Sign-in expired — sign in again",
        ProviderStatus.Unsigned => id == SoftwareSectionId.GrokBot ? "Not signed in (uses Cursor)" : "Not signed in",
        ProviderStatus.Error => "Signed in · last refresh failed",
        ProviderStatus.Loading => "Checking sign-in…",
        _ => "Not signed in"
    };

    public static string StatusLine(SoftwareSection section) => StatusLine(section.Id, section.Status);

    public static string SignInButton(ProviderStatus status) =>
        status is ProviderStatus.Unsigned ? "Sign in" : "Sign in again";

    public static string SignOutMenuItem(SoftwareSectionId id) => id switch
    {
        SoftwareSectionId.GrokBot => "Sign out (also signs out Cursor)",
        SoftwareSectionId.Cursor => "Sign out (also signs out Grok Bot)",
        _ => "Sign out"
    };

    public static string SignInWindowTitle(SoftwareSectionId id) => id switch
    {
        SoftwareSectionId.GrokBot => "Sign in to Cursor for Grok Bot",
        SoftwareSectionId.Codex => "Sign in to ChatGPT for Codex",
        SoftwareSectionId.Claude => "Sign in to Claude",
        _ => "Sign in to Cursor"
    };

    public static string SignInHint(SoftwareSectionId id) => id switch
    {
        SoftwareSectionId.GrokBot =>
            "Grok Bot weekly usage is a Cursor product. Sign in to cursor.com here. There is no separate xAI/Grok login.",
        SoftwareSectionId.Codex =>
            "Codex usage comes from your ChatGPT session. Sign in to chatgpt.com here.",
        SoftwareSectionId.Claude =>
            "Claude usage (session and weekly limits) comes from your claude.ai session. Sign in to claude.ai here.",
        _ => "Sign in to cursor.com here. This same session also loads Grok Bot."
    };

    public static bool ShowSignIn(ProviderStatus status) =>
        status is ProviderStatus.Unsigned or ProviderStatus.NeedsSignIn;

    public static bool ShowSignIn(SoftwareSection section) =>
        ShowSignIn(section.Status) || IsAuthFailure(section);

    public static bool ShowRetry(SoftwareSection section) =>
        !ShowSignIn(section) &&
        (section.Status == ProviderStatus.Error || section.StaleValue.IsStale);

    public static bool ShowSignOut(ProviderStatus status) =>
        status is ProviderStatus.Ok or ProviderStatus.NeedsSignIn or ProviderStatus.Error;

    public static bool IsReauth(ProviderStatus status) => status is ProviderStatus.NeedsSignIn;

    public static bool IsReauth(SoftwareSection section) =>
        section.Status is ProviderStatus.NeedsSignIn || IsAuthFailure(section);

    public static bool IsAuthFailure(SoftwareSection section)
    {
        if (section.Status is ProviderStatus.Unsigned or ProviderStatus.Ok or ProviderStatus.Loading)
            return false;
        var outcome = section.HealthValue.OutcomeEnum;
        if (outcome == FetchOutcome.AuthExpired) return true;
        return outcome == FetchOutcome.Http && section.HealthValue.HttpStatus is 401 or 403;
    }
}
