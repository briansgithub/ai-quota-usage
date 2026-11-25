# Usage Widget

A standalone Android app for a **Google Pixel 7a (Android 14+)** that shows current usage, percent used, and the next reset time, grouped by product:

1. **Cursor** — Cursor Models % used, Other Models % used, on-demand dollars vs cap when set, plan reset
2. **Grok Bot** — weekly % used and reset (same Cursor spending page / sign-in), plus the same on-demand dollar bar when a cap is set
3. **Codex** — windows the ChatGPT Codex usage page actually returns (5-hour, weekly, and/or 30-day), plus code-review / extra limits and credits when present, and any on-demand manual full resets from `chatgpt.com/codex/settings/usage`

Everything runs on the phone. There is no backend, no AI agent, and no cloud routine. You sign in to each site in an in-app WebView; the app keeps the session cookies and reads the same JSON those dashboards already call.

Times are shown in **America/New_York**.

A native **Windows 10/11 x64** port lives in [`windows/`](windows/README.md) (tray + mini panel). Android **1.5.2** matches those **percent used** labels and pace-relative red/white/green bars, plus an extra **on-demand** dollar-cap bar for Cursor and Grok Bot (reversed green→white→red, no pace tick). Wire percents are already 0–100 (a value of `0.71` is 0.71% used, not 71%). Grok Bot is not a third login: it uses the Cursor session on both Android and Windows. Do not mix the Android and Windows build steps.

## What you get

- One home-screen **Jetpack Glance** widget (dark, Cursor-dashboard look): three labeled sections (**Cursor**, **Grok Bot**, **Codex**) with extra space before each title. Resize it; a scrolling list keeps every meter, including Codex Weekly. Compact tiles drop the progress bars but still show `% used` and `Reset: Mon 3 PM (2d 5h)`.
- An in-app dashboard with the same three sections, pull-to-refresh, and per-service sign-in
- WorkManager unique periodic refresh every **15 minutes** (Android’s minimum), plus tap-to-refresh on the widget. A faint `next MM:SS` countdown sits in the **widget footer** (Chronometer) and in the app; WorkManager can drift, and the countdown stays at `00:00` until the next update instead of going negative.
- Last-updated time: the dashboard shows the relative stamp plus America/New_York clock time. The widget header drops that stamp and keeps ↻ refresh (network, no HTML fallback, `Cache-Control: no-store` + OkHttp `FORCE_NETWORK`) with a settings gear at the upper right that opens the on-demand display toggles.
- If a refresh fails, **last-good numbers stay on screen** and that section is marked stale (warning icon, amber/red header, `Last updated Xh ago`, short reason such as `Sign-in expired`, `Network error`, `Data format changed`, `HTTP 503`). The widget title also shows ⚠ if any section is stale. Data older than two refresh intervals (~30 min) is stale even without an explicit error. A **successful** fetch always replaces last-good meters, including a post-reset drop to 0% used or an empty Grok list — last-good is not reused on success.
- Labels are **percent used** (`37% used`). Bar length is the used fraction. Bar **color** matches Windows: expected used = elapsed fraction of that meter’s window; delta = used − expected; red when behind, white on pace, green when ahead, saturating at ±25 percentage points; 100% used is full green; unknown window is white. A thin tick marks the on-pace position (Glance renders the bar as a bitmap). **On-demand** bars (Cursor and Grok Bot) are different: label `On-demand  $12.34 / $50` plus % of the dollar cap, color reversed (0% green, 50% white, 100% red), no pace tick, and no quota-threshold notifications. The bar is hidden when spend or the dollar cap is missing, disabled, or 0.
- **Diagnostics** (bug icon) lists per-section last attempt / last success, HTTP status, endpoint, and a rolling attempt log, plus **Copy diagnostics** (no cookies or tokens). Optional notification when a section stays failed for over 2 hours (Settings toggle, default on).
- Expired session → **Sign in again** on the widget, which opens that service’s login page
- **Reset emphasis:** the `Reset: Fri 9 AM (1d 4h)` line is medium/bold and a step larger than surrounding copy. Color follows time until reset (America/New_York clock, same as the label):
  - **> 48h** — normal white (`#E8E8E8`)
  - **24–48h** — brighter white (`#F5F5F5`), semibold
  - **5–24h** — amber (`#E6B84C`), bold
  - **≤ 5h** (including overdue) — orange (`#FF7043`), bold
- **Usage is back:** Android notification when a 5-hour or weekly limit resets (reset time passing, or remaining % jumping by ≥ 15 points). One notification per cycle per limit (Codex 5-hour, Codex weekly, Grok weekly, Cursor plan). Detected on the 15-minute WorkManager refresh, plus a one-off WorkManager job ~5 seconds after the known reset so the alert is not up to 15 minutes late (inexact delay; no exact-alarm permission). Channel `usage_restored`. Android 13+ requests `POST_NOTIFICATIONS` when any notification type is on.
- **Use-it-or-lose-it pacing:** each 5-hour window is treated as burning **12% of weekly** (Diagnostics setting, default 12, range 5–25). Windows needed `N = ceil(R / 12)`. Latest start for the k-th-from-last window is `weeklyReset − k × 5h`. Slack = time until weekly reset − remaining current-window time − extra windows × 5h. Compact line e.g. `Pace: need 4 windows, 9h slack` on the dashboard and on non-compact widget tiles. Reminders at latest start − 1h 20m and − 15m; missed-window and slack-critical alerts escalate as slack approaches zero. Channel `pacing_alerts`. Weekly-only services (Grok 7-day, Cursor Models vs plan reset ~30-day) warn if remaining % is ≥ 10 points above even pace.
- **Quota remaining notifications:** native alerts when remaining quota crosses a configured step downward. Defaults track **Grok Bot weekly** and **Cursor models** (Codex rules exist, default off). Steps: every 10% from 90 down to 30 remaining, then every 5% to 0. One notification per crossed step per quota window; several steps between refreshes send a single notice for the **lowest** crossed value, e.g. `Grok Bot: 20% weekly quota left (resets Mon 3:00 PM ET)`. Fired-state persists across 15-minute refreshes and app restarts (SharedPreferences / DataStore path used by WorkManager). First observation seeds without notifying; stale/cached last-good never fires. Window reset (new reset time, or remaining jumping back up) clears fired-state. Master switch, per-service toggles, and an editable threshold list live in Diagnostics. Notify only — agents are never paused. Channel `quota_thresholds`. **Send test notification** in settings, or:
  ```bash
  adb shell am start -n com.usagewidget/.MainActivity --ez test_notification true
  ```
  Android 13+ requests `POST_NOTIFICATIONS` for this path.
- **Usage dump** (hidden): compare widget numbers to the live websites. Opens Diagnostics and writes sanitized raw JSON plus parsed meters to logcat tag `UsageWidget`:
  ```bash
  adb shell am start -n com.usagewidget/.MainActivity --ez dump_usage true
  adb logcat -s UsageWidget:I
  ```
- Settings (Diagnostics): enable/disable usage-restored, pacing reminders, missed-window alerts, quota remaining (master + per-window), optional quiet hours **11 PM–8 AM America/New_York** (default off), and % per 5h window. Quiet hours skip posting quota crossings but still seed first observations.

## Install on a Pixel 7a

USB debugging on, then from this repo (or copy the APK off the machine):

```bash
adb install -r artifacts/usage-widget-1.5.2.apk
```

If you built it yourself:

```bash
./gradlew testDebugUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
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
| Cursor Models / Other Models / plan reset | `GET https://cursor.com/api/usage-summary` | `WorkosCursorSessionToken` | `individualUsage.plan.autoPercentUsed` (Cursor Models), `apiPercentUsed` (Other Models) — already 0–100, including values like `0.711` which the dashboard rounds to **1% used** — plus `billingCycleEnd`, `membershipType` |
| Grok Bot weekly | `POST https://cursor.com/api/dashboard/get-sand-usage-status` body `{}` | same cookie; **`Origin: https://cursor.com` required** | `usagePercent`, `nextResetTimestampUtc` (ISO string or epoch ms; may be date-truncated `T00:00:00.000Z`), `currentPeriodStart` (rolling weekly clock; stored as the meter window start), `hasNonZeroIncludedLimit` (camelCase or snake_case) |

**Verified from the snapshot (Nov 5, 2026):** page copy showed Cursor Models **17% used**, Other Models **100% used**, plan reset **Nov 11 (7 days left)**, Grok Bot **65% used**, reset **Nov 6 (2 days left)**, plan **Pro**. The spending page is date-only for Grok. When `nextResetTimestampUtc` is midnight UTC, the app stamps `currentPeriodStart`’s America/New_York clock onto that date (Cursor’s rolling weekly window), then adds 7-day steps until the stamp is after both that period start and now — so a Friday 2:18 PM ET reset does not keep showing “due”. Test fixtures use Oct 30 9 AM EDT as the period start so Nov 6 renders as **Fri 9 AM**. The HTML fallback still has no clock.

**Assumed (community reverse-engineering, not live-called in this environment):** the JSON field names above, because the MHTML save did not include XHR bodies. Matching sources: OpenUsage, CodexBar/Pulse `get-sand-usage-status`, gist of `/api/usage-summary`. POST without `Origin` is reported to fail CSRF checks. Expired sessions redirect toward WorkOS login HTML; the client treats non-JSON / `not_authenticated` as **Sign in again**.

**Fallback:** load the spending page in a hidden WebView and parse `innerText` with `CursorHtmlExtractor` (unit-tested against the snapshot text).

### ChatGPT / Codex

The Codex usage settings page is `https://chatgpt.com/codex/settings/usage` (not only the ChatGPT overview tab). Both that page and the Codex CLI poll `GET /backend-api/wham/usage`. Open-source parsers (`openai/codex` `codex-rs` rate-limit snapshots, CodexBar) treat `used_percent` as already 0–100 (so `0.5` is half a percent, not 50%), classify windows by `limit_window_seconds` (18000 ≈ 5-hour, 604800 ≈ weekly, ~2592000 ≈ 30-day Free), and prefer unix `reset_at` over `reset_after_seconds` (relative to the request). Team / some Pro plans expose only a weekly window; the widget no longer invents a missing 5-hour or weekly row.

| What | Endpoint | Auth | Fields used |
|---|---|---|---|
| Session token | `GET https://chatgpt.com/api/auth/session` | ChatGPT next-auth cookies (`__Secure-next-auth.session-token` and chunks) | `accessToken`; account id from JWT / `_account` cookie |
| Usage windows | `GET https://chatgpt.com/backend-api/wham/usage` | `Authorization: Bearer <accessToken>`, optional `ChatGPT-Account-Id` | `rate_limit.primary_window` / `secondary_window`: `used_percent` (0–100), `limit_window_seconds`, `reset_at` (unix seconds) or `reset_after_seconds`; also `code_review_rate_limit`, `additional_rate_limits`, `credits`, `rate_limit_reset_credits.available_count` |
| Manual / on-demand full resets | `GET https://chatgpt.com/backend-api/wham/rate-limit-reset-credits` | same | `available_count`; `credits[]`: `status`, `expires_at`, `title`, `reset_type` (ids redacted in Diagnostics) |

Windows are classified by `limit_window_seconds` (~4–7 hours = **5-hour**, ~6–9 days = **Weekly**, ~23–46 days = **30-day**), not by primary vs secondary slot. Only windows (and extras) present in the JSON are shown. Labels are **% used** with dashboard-style rounding (`0.71` → `1% used`). Scheduled resets render as `Reset: Mon 3 PM (2d 5h)` in America/New_York (minutes only when non-zero, e.g. `Reset: Thu 2:30 PM (2h)`). On-demand resets render as `Manual resets: 1 (exp 11/12)` when `available_count` > 0; the line is hidden when the field is missing or zero.

**Verified from the snapshot:** remaining 100% on both bars and the relative reset copy. Fixtures use `used_percent: 0` and the Unix `reset_at` values implied by “4h 50m” / “6d 23h” from the snapshot timestamp `2026-11-05T15:30:26Z`.

**Assumed:** WHAM is the JSON the overview tab actually fetches (Codex CLI, CodexBar, ChatGPT Usage Meter). This environment did not replay a logged-in XHR. Session JSON with no `accessToken` is treated as signed-out.

**Fallback:** hidden WebView on the usage page + `OpenAiHtmlExtractor` (understands “100% left” vs Cursor’s “17% used”).

**Pacing assumption for Codex manual resets:** credits from `rate_limit_reset_credits` titled like `Full reset (Weekly + 5 hr)` are treated as restoring **both** the 5-hour and weekly bars to 100% remaining. Burning that restored weekly still takes `ceil(100 / P)` sequential 5-hour windows (9 windows / 45 hours at the default 12%). Each unexpired credit is required work against its `expires_at`; if it would expire before those windows can run, the app warns that it will go unused.

## Fragility

- Cursor or OpenAI can rename JSON fields, add bot walls, or require extra headers (`Origin`, account id) at any time.
- Grok Bot is a Cursor “Sand” product. An account without a Bot allowance should omit that row (`hasNonZeroIncludedLimit: false`).
- ChatGPT cookies roll; Cloudflare may HTML-challenge a background OkHttp call. The HTML WebView fallback is there for that case.
- Widget updates depend on WorkManager; Doze can delay the 15-minute period. Tap ↻ for an immediate read.
- Progress bars fill with **% used**; labels are **% used**. Color is pace-relative (same math as Windows `UsedGradient` / `PaceWindows`).
- Grok Bot date-only `nextResetTimestampUtc` is stamped with `currentPeriodStart`’s America/New_York clock, then rolled forward in 7-day steps when that stamp is not after the period start and “now” (so a Friday 2:18 PM ET reset does not keep showing “due” / last week’s window).
- After every refresh (manual, pull-to-refresh, WorkManager, or post-update fetch) the latest snapshot is written to Glance DataStore and `UsageGlanceWidget().updateAll()` is awaited so home-screen tiles match the in-app dashboard. Provider identity (`UsageWidgetReceiver` + `@xml/usage_widget_info`) is unchanged, so existing tiles update in place.
- If a parser breaks, the provider card shows a clear error instead of crashing the widget.

## Project layout

```
windows/                    # .NET 8 WPF tray app (win-x64). See windows/README.md
app/src/main/kotlin/com/usagewidget/
  providers/cursor/     # Cursor + Grok Bot client, JSON parser, HTML fallback
  providers/openai/     # ChatGPT session + WHAM client, parser, HTML fallback
  auth/                 # Keystore-backed cookie store
  widget/               # Glance home-screen widget
  ui/dashboard/         # Compose dashboard
  ui/login/             # WebView sign-in
  refresh/              # WorkManager + stale / usage-restored / pacing / quota notifiers
  domain/               # pacing math, reset detection, quota thresholds, snapshot merge
```

Not affiliated with Anysphere, xAI, or OpenAI.
