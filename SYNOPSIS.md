# Usage Widget — synopsis for a peer clone

On-device Android app (`com.usagewidget`, **1.5.2** / `versionCode` 17) that shows percent used and next reset times for **Cursor**, **Grok Bot**, and **Codex**. There is no backend. The peer signs in with **their own** Cursor and ChatGPT accounts; this tree contains no cookies, tokens, or passwords.

Target: Pixel-class phone, **Android 14+** (`minSdk` 34). Times are **America/New_York**.

Not affiliated with Anysphere, xAI, or OpenAI. The JSON routes below are **undocumented** and can change without notice.

---

## What it does

### Surfaces

- **Home-screen widget** (Jetpack Glance): three labeled sections — Cursor, Grok Bot, Codex. Resize; a scrolling list keeps every meter. Compact tiles drop progress bars but still show `% used` and `Reset: Mon 3 PM (2d 5h)`. Footer Chronometer counts down to the next refresh.
- **In-app dashboard** (Compose): same sections, pull-to-refresh, per-service sign-in / sign-out, last-updated line, pacing line.
- **Diagnostics** (bug icon): last attempt / success, HTTP status, endpoint, rolling log, **Copy diagnostics** (cookies and tokens stripped). Settings live here.

### Meters

1. **Cursor** — Cursor Models % used, Other Models % used, on-demand dollars vs cap when set, plan reset (same Cursor spending session).
2. **Grok Bot** — weekly % used and reset (Cursor “Sand” product; same sign-in), plus the same on-demand dollar bar when a cap is set.
3. **Codex** — 5-hour and Weekly remaining, scheduled resets, and on-demand **manual full resets** from ChatGPT Usage (`rate_limit_reset_credits`).

Progress bars fill with **% used**; labels are **% used**. Bar color is pace-relative (same math as Windows). Failed refreshes keep last-good numbers and mark the section stale (~30 min with no success, or an explicit error). A successful fetch always replaces last-good meters, including a post-reset drop. After 2 hours of failure, an optional retrieval notification fires.

### Reset emphasis

The `Reset: Fri 9 AM (1d 4h)` line is medium/bold and a step larger than surrounding copy. Color by time until reset:

| Time left | Style |
|---|---|
| **> 48h** | medium, normal white `#E8E8E8` |
| **24–48h** | semibold, brighter white `#F5F5F5` |
| **5–24h** | bold, amber `#E6B84C` |
| **≤ 5h** (including overdue) | bold, orange `#FF7043` |

### Notifications

Android 13+ requests `POST_NOTIFICATIONS` when any notification type is on. Three channels:

| Channel id | Purpose |
|---|---|
| `usage_retrieval` | Provider stayed stale > 2 hours |
| `usage_restored` | A 5-hour or weekly limit came back |
| `pacing_alerts` | Start-window reminders, missed windows, unused-usage warnings |

**Usage is back:** a previous reset time that was still upcoming has now passed, or remaining % jumped by **≥ 15 points**. One notice per cycle for Codex 5-hour, Codex weekly, Grok weekly, and Cursor plan (models + plan reset collapsed). Detected on the 15-minute WorkManager refresh, plus a one-off WorkManager job ~5 seconds after the known reset (inexact delay; no `SCHEDULE_EXACT_ALARM`).

**Pacing:** see math below. Quiet hours (optional, default **off**) are 11 PM–8 AM America/New_York and skip restored/pacing posts without marking them seen.

### Pacing math (use-it-or-lose-it)

Goal: burn remaining weekly usage before it resets.

- Each 5-hour window is treated as burning **P% of weekly** (setting, default **12**, range 5–25).
- Windows needed `N = ceil(R / P)` where `R` is weekly % remaining.
- Latest start for the k-th-from-last needed window: `weeklyReset − k × 5h`.
- Slack = time until weekly reset − remaining current-window time − extra windows × 5h.
- Active 5h (1–99% left) counts unused capacity and waits for that window to end before extra starts. ~100% remaining is “available” (can start now). ~0% waits for the 5h reset.
- Slack: OK > 8h, tight ≤ 8h, critical ≤ 2h, impossible if slack < 0 or the remaining weekly cannot fit.
- Reminders at latest start **− 1h 20m** and **− 15m** when 5h is available.
- Missed window: latest start passed with 5h still unused.
- Compact line, e.g. `Pace: need 4 windows, 9h slack`, on the dashboard and on non-compact widget tiles.
- Weekly-only (Grok 7-day; Cursor Models vs plan reset ~30-day): warn if remaining is **≥ 10 points** above even pace.

**Manual reset assumption:** a Codex credit titled like `Full reset (Weekly + 5 hr)` restores **both** bars to 100% remaining. Burning that restored weekly still takes `ceil(100 / P)` sequential 5h windows (9 windows / 45 hours at 12%). Each unexpired credit is required work against `expires_at`.

---

## Architecture

Kotlin, Jetpack Compose + Glance, WorkManager, OkHttp, EncryptedSharedPreferences (Tink + Android Keystore). Single module `:app`.

```
app/src/main/kotlin/com/usagewidget/
  UsageWidgetApp.kt          Application: restore cookies, enqueue 15-min refresh, push widget
  MainActivity.kt            Dashboard / Diagnostics; POST_NOTIFICATIONS on Android 13+
  data/
    UsageRepository.kt       Fetch both providers, merge, persist, notify, push widget
    AppSettings.kt           Notification + pacing prefs (plain SharedPreferences)
  auth/
    SecureCookieStore.kt     Encrypted session cookies (AES256-GCM, Keystore master key)
    SessionPaste.kt          Parse a pasted Cookie header / bare session token
  providers/cursor/          usage-summary + get-sand-usage-status + HTML fallback
  providers/openai/          session + WHAM usage + reset-credits + HTML fallback
  domain/
    UsageModels.kt           Snapshot / meter types
    UsageSections.kt         Cursor / Grok / Codex layout
    SnapshotMerger.kt        Last-good numbers + health
    PacingPlanner.kt         Slack, latest starts, missed windows, manual credits
    ResetDetector.kt         Reset-time passed or remaining jump; per-limit groups
    ResetEmphasis.kt         48h / 24h / 5h urgency thresholds
    UsageAlertEngine.kt      Pure restored + pacing alerts, quiet hours, dedupe, wakes
    FetchHealth.kt           Stale policy (~30 min / 2 h notify)
  refresh/
    UsageRefreshWorker.kt    Unique periodic 15 min + one-shot
    TimedRefreshScheduler.kt One-off WorkManager at known reset / pace instants
    UsageAlertNotifier.kt    usage_restored + pacing_alerts channels
    StaleNotifier.kt         usage_retrieval channel
  widget/
    UsageGlanceWidget.kt     Glance UI (reset colors, pacing line if not compact)
    WidgetPublisher.kt       Write Glance DataStore and await updateAll()
    UsageWidgetReceiver.kt   AppWidget provider (stable identity — do not rename)
  ui/dashboard/              Compose dashboard + ViewModel
  ui/diagnostics/            Settings + health log
  ui/login/                  WebView sign-in
  extract/HiddenWebViewExtractor.kt   HTML fallback when OkHttp is challenged
  net/AuthenticatedHttp.kt   Cookie-auth OkHttp
```

**Refresh path:** `UsageRepository.refresh` → Cursor + OpenAI clients → `SnapshotMerger.merge` → encrypted snapshot JSON → `UsageAlertEngine` / notifiers → `WidgetPublisher.push`. Periodic WorkManager is the 15-minute floor; tap ↻ and the one-off wake jobs call the same refresh.

**Persistence:** session cookies and last snapshot in EncryptedSharedPreferences (app-private, `allowBackup=false`). Settings (toggles, % per window) in ordinary `usage_widget_settings` prefs. Glance state is a Preferences DataStore copy of the snapshot, not a second source of truth.

---

## Data sources and sign-in

No credentials ship in this repo. After clone, the peer opens the app and signs in to **Cursor** and **ChatGPT** on the phone. The app never collects passwords: it loads each site’s normal login page in a WebView, then copies cookies out of `CookieManager`.

Only cookies for `cursor.com` / `chatgpt.com` (and `chat.openai.com`) are stored — not Google or GitHub cookies.

### Cursor + Grok Bot

Login page: `https://cursor.com/dashboard/spending`. Session cookie name: `WorkosCursorSessionToken`.

| What | Endpoint | Notes |
|---|---|---|
| Cursor Models / Other / plan reset | `GET https://cursor.com/api/usage-summary` | `individualUsage.plan.autoPercentUsed`, `apiPercentUsed`, `billingCycleEnd`, `membershipType` |
| Grok Bot weekly | `POST https://cursor.com/api/dashboard/get-sand-usage-status` body `{}` | **`Origin: https://cursor.com` required.** `usagePercent`, `nextResetTimestampUtc`, `currentPeriodStart`, `hasNonZeroIncludedLimit` |

If `nextResetTimestampUtc` is midnight UTC (date-only), the app stamps `currentPeriodStart`’s America/New_York clock onto that date. HTML fallback: hidden WebView on the spending page + `CursorHtmlExtractor` (no hour if the page is date-only).

### ChatGPT / Codex

Login: `https://chatgpt.com/auth/login?next=%2Fsettings%2Fusage%3Ftab%3Doverview`, fallback `https://auth.openai.com/log-in`. Session cookies: `__Secure-next-auth.session-token` (and chunks).

| What | Endpoint | Notes |
|---|---|---|
| Session | `GET https://chatgpt.com/api/auth/session` | `accessToken`; account id from JWT / `_account` |
| Usage windows | `GET https://chatgpt.com/backend-api/wham/usage` | Bearer token. Windows classified by `limit_window_seconds` (~5h vs ~weekly), not primary/secondary slot |
| Manual resets | `GET https://chatgpt.com/backend-api/wham/rate-limit-reset-credits` | `available_count`, `credits[].expires_at` / `title` / `reset_type` |

If ChatGPT’s page stays blank in the WebView, use **OpenAI login page** or **Paste session** (desktop Chrome → DevTools → Application → Cookies). Chrome Custom Tabs cannot hand cookies back. Google often refuses embedded WebViews; use email / GitHub / paste instead.

---

## Settings (Diagnostics)

| Setting | Default |
|---|---|
| Notify if a provider stays stale > 2 hours | on |
| Notify when usage comes back | on |
| Pacing reminders to start a 5-hour window | on |
| Missed-window and unused-usage alerts | on |
| Quiet hours 11 PM–8 AM America/New_York | **off** |
| % of weekly a 5-hour window can burn | **12** (5–25) |

Background refresh is always unique periodic WorkManager at **15 minutes** (Android’s minimum). Not a setting.

---

## Build

### Toolchain

- **JDK 21** (`jvmToolchain(21)` in `app/build.gradle.kts`)
- **Android SDK** with `platforms;android-36` (compile/target 36) and current build-tools
- **Gradle 9.1.0** via `./gradlew` (wrapper JAR is in the tree)
- Kotlin 2.3.20, Android Gradle Plugin 9.0.1 — see `gradle/libs.versions.toml`

### First-time setup

```bash
export ANDROID_HOME=/path/to/Android/Sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties   # gitignored; do not commit
./gradlew testDebugUnitTest
```

Unit tests use scrubbed fixtures under `app/src/test/resources/fixtures/` (ids `user_REDACTED` / `acct_REDACTED`, placeholder JWT `alg: none`). They do not hit the network.

### Build types

| Type | Command | What you get |
|---|---|---|
| **debug** | `./gradlew assembleDebug` | Unminified, `debuggable`, version suffix `-debug`, typically ~70 MB. Uses the **machine-local** Android debug keystore (`~/.android/debug.keystore`), which AGP creates on that computer. That file is **not** in this repo. |
| **compact** | `./gradlew assembleCompact` | R8 minify + resource shrink, `arm64-v8a` only, `debuggable=false`, version suffix `-compact`, ~7 MB. Currently `signingConfig = debug` so a local compact sideload uses the **same local debug keystore**. Replace that before sharing an APK. |
| **release** | `./gradlew assembleRelease` | Minify **off**, **no** signing config. Do not ship this until you add your own keystore. |

This source zip **does not include any keystore**. Do not copy someone else’s `debug.keystore`. Create your own:

```bash
keytool -genkeypair -v \
  -keystore usage-widget-upload.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias usagewidget
```

Keep `*.jks` / `*.keystore` out of git (already gitignored). Point Gradle at it via `local.properties` or env vars, for example in `app/build.gradle.kts`:

```kotlin
signingConfigs {
    create("upload") {
        storeFile = file(providers.gradleProperty("USAGE_WIDGET_STORE_FILE").get())
        storePassword = providers.gradleProperty("USAGE_WIDGET_STORE_PASSWORD").get()
        keyAlias = providers.gradleProperty("USAGE_WIDGET_KEY_ALIAS").get()
        keyPassword = providers.gradleProperty("USAGE_WIDGET_KEY_PASSWORD").get()
    }
}
```

Then set `signingConfig = signingConfigs.getByName("upload")` on `compact` and/or `release`. Never put passwords on the command line in a shared script.

Install (after you have signed a build):

```bash
adb install -r app/build/outputs/apk/compact/app-compact.apk
```

Then: open the app → sign in to Cursor → sign in to ChatGPT → long-press home screen → Widgets → Usage Widget.

---

## Assumptions and limitations

- **Undocumented APIs.** Field names were inferred from community tools (OpenUsage, CodexBar/Pulse, WHAM) plus a Nov 5 2026 page snapshot. Live XHR was not captured in the original environment. A site change is a local parser/client fix, not a crash.
- **Grok Bot** is omitted when `hasNonZeroIncludedLimit` is false. HTML fallback still has no reset hour.
- **Codex windows** are shared across Codex / Work / Workspace Agents / ChatGPT for Excel (plain chat is not included), per ChatGPT’s usage page copy.
- **Manual reset** = full weekly + 5-hour restore; unused-credit warnings use `ceil(100 / P)` sequential windows before `expires_at`.
- **Cursor plan** pacing uses Cursor Models remaining vs plan reset and a **30-day** period. Grok uses **7 days**.
- **WorkManager** delays are inexact; Doze can slip the 15-minute period. Tap ↻ for an immediate read. One-off jobs reduce “usage is back” latency versus waiting for the next period.
- **No exact alarms.** `SCHEDULE_EXACT_ALARM` is not requested.
- **Google in a WebView** often fails (“this browser or app may not be secure”). Email / GitHub / paste session instead.
- **Cloudflare** may HTML-challenge OkHttp; the hidden WebView fallback exists for that.
- Widget provider class names (`UsageWidgetReceiver`, `@xml/usage_widget_info`) are **stable**; renaming them orphans existing tiles.
- Test fixtures contain **synthetic** emails (`user@example.com`) and `REDACTED` ids — not live accounts.

---

## License / sharing

Peer clone: they build with their SDK, their keystore, and their Cursor/ChatGPT sessions. Do not redistribute an APK signed with a debug keystore you do not own, and do not send `local.properties`, EncryptedSharedPreferences dumps, or cookie paste logs.
