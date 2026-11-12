# Usage Widget

A standalone Android app for a **Google Pixel 7a (Android 14+)** that shows current usage, remaining usage, and the next reset time, grouped by product:

1. **Cursor** — Cursor Models % left, Other Models % left, plan reset
2. **Grok Bot** — weekly % left and reset (same Cursor spending page / sign-in)
3. **Codex** — **5-hour** and **Weekly** remaining, scheduled resets, and any on-demand manual full resets from ChatGPT settings → Usage

Everything runs on the phone. There is no backend, no AI agent, and no cloud routine. You sign in to each site in an in-app WebView; the app keeps the session cookies and reads the same JSON those dashboards already call.

Times are shown in **America/New_York**.

## What you get

- One home-screen **Jetpack Glance** widget (dark, Cursor-dashboard look): three labeled sections (**Cursor**, **Grok Bot**, **Codex**) with extra space before each title. Resize it; a scrolling list keeps every meter, including Codex Weekly. Compact tiles drop the progress bars but still show `% left` and `Reset: Mon 3 PM (2d 5h)`.
- An in-app dashboard with the same three sections, pull-to-refresh, and per-service sign-in
- WorkManager unique periodic refresh every **15 minutes** (Android’s minimum), plus tap-to-refresh on the widget. A faint `next MM:SS` countdown sits in the **widget footer** (Chronometer) and in the app; WorkManager can drift, and the countdown stays at `00:00` until the next update instead of going negative.
- Last-updated time on both surfaces (America/New_York)
- If a refresh fails, **last-good numbers stay on screen** and that section is marked stale (warning icon, amber/red header, `Last updated Xh ago`, short reason such as `Sign-in expired`, `Network error`, `Data format changed`, `HTTP 503`). The widget title also shows ⚠ if any section is stale. Data older than two refresh intervals (~30 min) is stale even without an explicit error. Progress bars fill with **% used**; labels stay **% left**.
- **Diagnostics** (bug icon) lists per-section last attempt / last success, HTTP status, endpoint, and a rolling attempt log, plus **Copy diagnostics** (no cookies or tokens). Optional notification when a section stays failed for over 2 hours (Settings toggle, default on).
- Expired session → **Sign in again** on the widget, which opens that service’s login page
- **Reset emphasis:** the `Reset: Fri 9 AM (1d 4h)` line is medium/bold and a step larger than surrounding copy. Color follows time until reset (America/New_York clock, same as the label):
  - **> 48h** — normal white (`#E8E8E8`)
  - **24–48h** — brighter white (`#F5F5F5`), semibold
  - **5–24h** — amber (`#E6B84C`), bold
  - **≤ 5h** (including overdue) — orange (`#FF7043`), bold
- **Usage is back:** Android notification when a 5-hour or weekly limit resets (reset time passing, or remaining % jumping by ≥ 15 points). One notification per cycle per limit (Codex 5-hour, Codex weekly, Grok weekly, Cursor plan). Detected on the 15-minute WorkManager refresh, plus a one-off WorkManager job ~5 seconds after the known reset so the alert is not up to 15 minutes late (inexact delay; no exact-alarm permission). Channel `usage_restored`. Android 13+ requests `POST_NOTIFICATIONS` when any notification type is on.
- **Use-it-or-lose-it pacing:** each 5-hour window is treated as burning **12% of weekly** (Diagnostics setting, default 12, range 5–25). Windows needed `N = ceil(R / 12)`. Latest start for the k-th-from-last window is `weeklyReset − k × 5h`. Slack = time until weekly reset − remaining current-window time − extra windows × 5h. Compact line e.g. `Pace: need 4 windows, 9h slack` on the dashboard and on non-compact widget tiles. Reminders at latest start − 1h 20m and − 15m; missed-window and slack-critical alerts escalate as slack approaches zero. Channel `pacing_alerts`. Weekly-only services (Grok 7-day, Cursor Models vs plan reset ~30-day) warn if remaining % is ≥ 10 points above even pace.
- Settings (Diagnostics): enable/disable usage-restored, pacing reminders, missed-window alerts, optional quiet hours **11 PM–8 AM America/New_York** (default off), and % per 5h window.

## Install on a Pixel 7a

USB debugging on, then from this repo (or copy the APK off the machine):

```bash
adb install -r artifacts/usage-widget-1.3.6-compact.apk
```

If you built it yourself:

```bash
./gradlew testDebugUnitTest assembleCompact
adb install -r app/build/outputs/apk/compact/app-compact.apk
```

Then:

1. Open **Usage Widget**
2. Sign in to **Cursor**, then **ChatGPT**
3. Long-press the home screen → **Widgets** → **Usage Widget** → drop a 4×4-ish tile (it resizes; scroll inside the widget if the tile is short)
4. Tap ↻ on the widget to refresh; tap a row to open the app; tap **Sign in again** if a session dropped

Application id: `com.usagewidget`.

## Build

Requires JDK 21 and an Android SDK with `platforms;android-36` (or newer) and build-tools.

```bash
export ANDROID_HOME=/path/to/Android/Sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk artifacts/usage-widget-debug.apk
```

Unit tests cover the JSON/HTML parsers using scrubbed fixtures derived from the Nov 5 2026 page snapshots. They do not talk to the network and contain no cookies or personal ids.

## Auth and storage

- Login is a WebView of each site’s normal page (`cursor.com/dashboard/spending`, `chatgpt.com/auth/login`). The app never collects passwords. If ChatGPT’s page stays blank, use **OpenAI login page** or **Paste session**.
- After login, cookies are copied out of `CookieManager` and stored in **EncryptedSharedPreferences** (AES256-GCM, Android Keystore master key).
- Prefs live in app-private storage. `android:allowBackup="false"`, backup rules and data-extraction rules exclude shared prefs/files.
- Only cookies for `cursor.com` / `chatgpt.com` (and `chat.openai.com`) are persisted — not Google or GitHub cookies.
- Rolling `Set-Cookie` values from ChatGPT’s session endpoint are merged back into the encrypted store.

### Google sign-in in a WebView

Google often refuses embedded WebViews (“this browser or app may not be secure”). The login WebView uses the **real WebView Chromium user agent** (so Cloudflare Turnstile matches client hints). It does not spoof Chrome/131 and does not strip `X-Requested-With` globally.

If Google still blocks the page, use **Continue with email**, **GitHub** (Cursor), or **Paste session** (desktop Chrome → DevTools → Application → Cookies). Chrome Custom Tabs cannot hand cookies back to this app. Debug builds show a Reload control and enable WebView remote debugging.

## Data sources

These APIs are **undocumented**. Each provider lives in its own package (`providers/cursor`, `providers/openai`) with its own errors so a breakage is a local parser/client fix.

### Cursor + Grok Bot

The spending UI at `https://cursor.com/dashboard/spending` is a cookie-authenticated dashboard. The `?for=auth0|user_…` query is only an account picker; the app never hard-codes it.

| What | Endpoint | Auth | Fields used |
|---|---|---|---|
| Cursor Models / Other Models / plan reset | `GET https://cursor.com/api/usage-summary` | `WorkosCursorSessionToken` | `individualUsage.plan.autoPercentUsed` (Cursor Models), `apiPercentUsed` (Other Models), `billingCycleEnd`, `membershipType` |
| Grok Bot weekly | `POST https://cursor.com/api/dashboard/get-sand-usage-status` body `{}` | same cookie; **`Origin: https://cursor.com` required** | `usagePercent`, `nextResetTimestampUtc` (ISO string or epoch ms; may be date-truncated `T00:00:00.000Z`), `currentPeriodStart` (rolling weekly clock), `hasNonZeroIncludedLimit` (camelCase or snake_case) |

**Verified from the snapshot (Nov 5, 2026):** page copy showed Cursor Models **17% used**, Other Models **100% used**, plan reset **Nov 11 (7 days left)**, Grok Bot **65% used**, reset **Nov 6 (2 days left)**, plan **Pro**. The spending page is date-only for Grok. When `nextResetTimestampUtc` is midnight UTC, the app stamps `currentPeriodStart`’s America/New_York clock onto that date (Cursor’s rolling weekly window). Test fixtures use Oct 30 9 AM EDT as the period start so Nov 6 renders as **Fri 9 AM**. The HTML fallback still has no clock.

**Assumed (community reverse-engineering, not live-called in this environment):** the JSON field names above, because the MHTML save did not include XHR bodies. Matching sources: OpenUsage, CodexBar/Pulse `get-sand-usage-status`, gist of `/api/usage-summary`. POST without `Origin` is reported to fail CSRF checks. Expired sessions redirect toward WorkOS login HTML; the client treats non-JSON / `not_authenticated` as **Sign in again**.

**Fallback:** load the spending page in a hidden WebView and parse `innerText` with `CursorHtmlExtractor` (unit-tested against the snapshot text).

### ChatGPT / Codex

The overview tab at `https://chatgpt.com/settings/usage?tab=overview` showed **5-hour limit · 100% left · Resets in 4h 50m** and **Weekly limit · 100% left · Resets in 6d 23h**. Copy on the page says those windows are shared across Codex, Work, Workspace Agents, and ChatGPT for Excel (plain chat is not included).

| What | Endpoint | Auth | Fields used |
|---|---|---|---|
| Session token | `GET https://chatgpt.com/api/auth/session` | ChatGPT next-auth cookies (`__Secure-next-auth.session-token` and chunks) | `accessToken`; account id from JWT / `_account` cookie |
| Usage windows | `GET https://chatgpt.com/backend-api/wham/usage` | `Authorization: Bearer <accessToken>`, optional `ChatGPT-Account-Id` | `rate_limit.primary_window` / `secondary_window`: `used_percent`, `limit_window_seconds`, `reset_at`; `rate_limit_reset_credits.available_count` |
| Manual / on-demand full resets | `GET https://chatgpt.com/backend-api/wham/rate-limit-reset-credits` | same | `available_count`; `credits[]`: `status`, `expires_at`, `title`, `reset_type` (ids redacted in Diagnostics) |

Windows are classified by `limit_window_seconds` (~4–7 hours = **5-hour**, ~6–9 days = **Weekly**), not by primary vs secondary slot. Both meters are always shown. The UI shows **% left = 100 − used_percent** only. Scheduled resets render as `Reset: Mon 3 PM (2d 5h)` in America/New_York (minutes only when non-zero, e.g. `Reset: Thu 2:30 PM (2h)`). On-demand resets render as `Manual resets: 1 (exp 12/11)` when `available_count` > 0; the line is hidden when the field is missing or zero.

**Verified from the snapshot:** remaining 100% on both bars and the relative reset copy. Fixtures use `used_percent: 0` and the Unix `reset_at` values implied by “4h 50m” / “6d 23h” from the snapshot timestamp `2026-11-05T15:30:26Z`.

**Assumed:** WHAM is the JSON the overview tab actually fetches (Codex CLI, CodexBar, ChatGPT Usage Meter). This environment did not replay a logged-in XHR. Session JSON with no `accessToken` is treated as signed-out.

**Fallback:** hidden WebView on the usage page + `OpenAiHtmlExtractor` (understands “100% left” vs Cursor’s “17% used”).

**Pacing assumption for Codex manual resets:** credits from `rate_limit_reset_credits` titled like `Full reset (Weekly + 5 hr)` are treated as restoring **both** the 5-hour and weekly bars to 100% remaining. Burning that restored weekly still takes `ceil(100 / P)` sequential 5-hour windows (9 windows / 45 hours at the default 12%). Each unexpired credit is required work against its `expires_at`; if it would expire before those windows can run, the app warns that it will go unused.

## Fragility

- Cursor or OpenAI can rename JSON fields, add bot walls, or require extra headers (`Origin`, account id) at any time.
- Grok Bot is a Cursor “Sand” product. An account without a Bot allowance should omit that row (`hasNonZeroIncludedLimit: false`).
- ChatGPT cookies roll; Cloudflare may HTML-challenge a background OkHttp call. The HTML WebView fallback is there for that case.
- Widget updates depend on WorkManager; Doze can delay the 15-minute period. Tap ↻ for an immediate read.
- Progress bars fill with **% used**; labels stay **% left**.
- After every refresh (manual, pull-to-refresh, WorkManager, or post-update fetch) the latest snapshot is written to Glance DataStore and `UsageGlanceWidget().updateAll()` is awaited so home-screen tiles match the in-app dashboard. Provider identity (`UsageWidgetReceiver` + `@xml/usage_widget_info`) is unchanged, so existing tiles update in place.
- If a parser breaks, the provider card shows a clear error instead of crashing the widget.

## Project layout

```
app/src/main/kotlin/com/usagewidget/
  providers/cursor/     # Cursor + Grok Bot client, JSON parser, HTML fallback
  providers/openai/     # ChatGPT session + WHAM client, parser, HTML fallback
  auth/                 # Keystore-backed cookie store
  widget/               # Glance home-screen widget
  ui/dashboard/         # Compose dashboard
  ui/login/             # WebView sign-in
  refresh/              # WorkManager + stale / usage-restored / pacing notifiers
  domain/               # pacing math, reset detection, snapshot merge
```

Not affiliated with Anysphere, xAI, or OpenAI.
