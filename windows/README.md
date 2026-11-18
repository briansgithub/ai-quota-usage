# Usage Widget for Windows

Native Windows 10/11 x64 port of Usage Widget 1.5.2. One self-contained `.exe` shows Cursor, Grok Bot, and OpenAI Codex usage: **percent used** labels (`37% used`) and bars filled to the used fraction. Bar **color** is pace-relative, not raw used-%: expected used = elapsed fraction of that meter’s window (5-hour, 7-day weekly, Cursor billing-cycle start/end). Behind pace → red, on pace → pale, ahead → green, saturating at ±25 percentage points of delta (actual − expected). 100% used is always green. Unknown windows stay pale. A thin tick marks the on-pace position. Cursor and Grok Bot also show an **on-demand** dollar-cap bar when the account has a spend limit (`On-demand  $12.34 / $50`, % of cap, reversed green→white→red, no pace tick, no threshold notifications). Reset lines stay `Reset: <weekday> <hour> (<Xd Yh>)` with closer deadlines emphasized, plus `Manual resets: N (exp DD/MM)`, stale/error state, 15-minute refresh plus manual refresh, and a faint `next MM:SS` countdown. All times are America/New_York.

This folder is the Windows port. Quota remaining notifications are implemented here and in the Android app.

## How it looks on Windows

There is no Windows 11 Widgets-board tile. The glanceable surface is:

- A **system-tray** icon (bold blue meter with a dark rim, multi-resolution 16–256). Hover shows **only** the compact used-% popup (native NotifyIcon tooltip is suppressed; the icon’s accessible name stays “Usage Widget”). The popup is anchored to the tray icon on the work-area side of the taskbar, with a 10 DIP gap, and stays up while the pointer is over the tray icon. It hides as soon as the pointer leaves the icon, including when the pointer is over the popup or the gap. Full included quotas other than Grok Bot are omitted so the popup stays short; Grok Bot stays listed at 100% used. If there is no data, or every remaining quota is full, the popup is a single short line. Left-click restores windows hidden with Minimize. Double-click opens the full window. Right-click: Open, Refresh, Sign in, Always on top, Start with Windows, **Exit**. A tiny corner dot marks **stale** (amber) or **error/expired** (red) only — high used-% is green on the bars, so there is no orange “low remaining” cue.
- A compact **mini panel** (Keep on top **off** by default, default size **280×680**). Click the title strip (or the pin button on the left) to toggle Always on top; the settings gear is in the upper-right corner; drag past the system drag threshold only moves it. Double-click does not toggle twice. Hover to fade in **Minimize** (tray), **Maximize/Restore** (full window), **X / Exit**. Right-click the title strip for **Keep on top**, kept in sync with the tray. Resize from any edge or corner; **all content scales** to the measured stack (Cursor, Grok Bot, **Codex**, and the footer) so the last card is never cropped and there is no scrollbar. Size and position are saved and clamped to the monitor work area. The panel never uses the taskbar.
- A **full window** with the same cards plus sign-in/sign-out, pacing %, notification toggles, quiet hours, and start-with-Windows. Its Minimize also hides to the tray; its X exits the app the same way.

Each of the three cards (full window **and** mini panel) shows signed-in / not signed in / **sign-in expired**. A **Sign in** button appears only when that card needs it (never signed in, expired/rejected session, or an HTTP 401/403). Transient network errors keep last-good numbers with a stale marker and **Retry**. There is no visible Sign out; right-click a card for **Sign out** (Grok Bot: “also signs out Cursor”, and the reverse), plus Sign in / Refresh when useful. Cookie paste remains as a fallback.

## How each service signs in

There is **no separate Grok/xAI account** in this app (Android or Windows). Grok Bot weekly usage is Cursor’s Sand/Bot meter.

| Card | Real login | Cookie the fetcher needs | After sign-in |
| --- | --- | --- | --- |
| **Cursor** | WebView2 at `https://cursor.com/dashboard/spending` (WorkOS / Google / GitHub / email as the site offers). No invented desktop OAuth. | `WorkosCursorSessionToken` on `cursor.com` | `GET cursor.com/api/usage-summary` |
| **Grok Bot** | **Same Cursor login.** The Grok card’s Sign in opens that same Cursor WebView2. Signing out of Grok signs out of Cursor (shared session). | Same `WorkosCursorSessionToken` | `POST cursor.com/api/dashboard/get-sand-usage-status` with `Origin: https://cursor.com` |
| **Codex** | WebView2 at ChatGPT login (`chatgpt.com/auth/login`, fallback `auth.openai.com`). No device-code flow — ChatGPT does not publish one this app can use. | `__Secure-next-auth.session-token` (and chunks) on `chatgpt.com` | `GET /api/auth/session` then WHAM `/backend-api/wham/usage` |

The sign-in window auto-closes once that session cookie is present **in WebView2** (not merely leftover in local storage), then usage refreshes immediately.

Notifications are Windows toasts via the tray app: “usage is back” for 5-hour and weekly resets, use-it-or-lose-it pacing alerts, and **quota remaining** threshold crossings (Grok Bot weekly and Cursor models on by default; Codex off). Fired-state is stored in `%LocalAppData%\UsageWidget\settings.json`. **Send test notification** is in Settings, or:

```text
UsageWidget.exe --test-notification
```

That posts a toast through the running instance (flag file) or standalone, then exits if no instance was running.

## Run

1. Unzip `UsageWidget-win-x64-1.5.2.zip`.
2. Double-click `UsageWidget.exe`.
3. If SmartScreen says “Windows protected your PC”, choose **More info → Run anyway**. The build is unsigned.
4. On each of the three cards, tap **Sign in**. Grok Bot uses your Cursor sign-in; Codex uses ChatGPT. The window closes by itself when the session cookie is captured.

Requires 64-bit Windows 10 (1809+) or Windows 11. WebView2 Evergreen is already on Windows 11; on older Windows 10 install [Microsoft Edge WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/) if the sign-in window is blank.

No .NET runtime install is needed (self-contained win-x64).

## Where data is stored

`%LocalAppData%\UsageWidget\`

| File | What |
| --- | --- |
| `cookies.bin` | Session cookies, DPAPI-encrypted for the current Windows user |
| `snapshot.json` | Last-good usage snapshot (no passwords) |
| `settings.json` | Panel position and size, keep-on-top, alerts, quota threshold rules/fired-state, quiet hours, start-with-Windows, debug logging |
| `log.txt` | Optional debug log (Settings → Write debug log, or `USAGEWIDGET_DEBUG=1`) |
| `webview\` | WebView2 profile (browser cookies for the embedded sign-in window) |

Sign-out from the app clears that provider’s cookies. Uninstall = delete the folder and the exe.

## Build from source (optional)

From `windows/` with the .NET 8 SDK:

```bash
dotnet test UsageWidget.Core.Tests/UsageWidget.Core.Tests.csproj -c Release
dotnet publish UsageWidget.App/UsageWidget.App.csproj -c Release -r win-x64 --self-contained true \
  /p:PublishSingleFile=true /p:IncludeNativeLibrariesForSelfExtract=true \
  /p:EnableCompressionInSingleFile=true /p:DebugType=none
```

Cross-compiling from Linux works with `EnableWindowsTargeting`. Core unit tests run on Linux.

## Not ported

- Android home-screen Glance widget / WorkManager (Windows uses tray + mini panel + DispatcherTimer).
- Windows 11 Widgets Board (not required).
- EncryptedSharedPreferences / Android Keystore (Windows uses DPAPI).
- Notification channels and POST_NOTIFICATIONS (Windows toasts / optional balloon fallback).
- Hidden WebView HTML fallback uses a 1×1 WebView2 window instead of Android’s hidden WebView.

Paid usage and model selection are not part of this app.
