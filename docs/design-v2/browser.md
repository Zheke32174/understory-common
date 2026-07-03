# Design v2 — understory-browser ("Understory Safe View")

Status: DESIGN (implementable). Resolves every finding in
`docs/audit-v2/browser.md` and the browser-relevant rows of `SUITE.md`.
Design-only: no code is modified by this document; no gradle/build is run.
File:line references are to the audited (v1) tree under
`C:\repos\understory\understory-browser\` (`common/` =
`C:\repos\understory\understory-common\`).

Companion inputs assumed present but NOT specified here (suite-wide,
owned by the common-security consolidation, SUITE.md §3/§9 — this doc
*consumes* them, does not define them):

- shared M3 theme tokens + `UnderstoryScaffold` / `UnderstoryTopAppBar` in
  `common-security` (replaces the ~60 hardcoded hex literals; this doc
  references token names like `colorScheme.surface`, `UnderstorySpec.*`).
- the suite-wide tamper hard-fail **explanation screen** (D14 is a
  suite-wide item tracked once; browser only wires to it, §9).
- the suite `${applicationId}.suitecaps` authority fix is a suite-wide
  edit; the browser-side manifest change is specified in §8.

Positioning (audit §E, SUITE.md §6): **the quarantine viewer that lives
one share-sheet tap from Chrome/Brave.** The place a user opens a link
they do NOT trust — SMS-phish, a strange email, a QR code, a stranger's
chat. Chrome keeps the default-browser role, the logins, the tabs, the
sync; Safe View offers what Chrome structurally cannot: JS dead by
default, no cookies, no storage, no downloads, no popups, no permission
prompts, ephemeral by construction, and a permission-stripped APK.
v1 ships as a **view-only, one-link-at-a-time inspector** — no tabs, no
persistent sessions, no daily-driver ambition (A16/A17 stay deferred,
honestly).

Two things are missing and this doc adds them: **the doorway** (share
target + optional VIEW filter → confirmation interstitial, §2/D1) and
**the exit** (hand the trusted URL back to the default browser, §5/D6).
The overlay-proxy surface is made honest by shrinking it to I2P-only-
experimental behind an eng gate, and dropping the two VpnService overlays
from the UI (§6/D3/D4/D12).

---

## 0. Disposition table — every audited feature

Tags: **FIX** = make current design work · **REDESIGN** = different
mechanism (specified) · **DROP** = removed from UI now (note replacement)
· **KEEP** = ships as-is (verified working).

| Audit | Feature | Disposition | Section |
|---|---|---|---|
| A1 | Hardened WebView matrix (JS-off, blocked schemes, cookies, mixed-content, SafeBrowsing, geo/cam/mic, wipe-on-destroy) | **KEEP** (traces end-to-end) | §7 |
| A1 | `normalizeUrl` mixed-case scheme bug | **FIX** (D9) | §3.4 |
| A2 | URL bar + nav row | **KEEP** + add error state, search-off honesty | §3, §3.5 |
| A2 | Stock WebView grey error page | **REDESIGN** (D10) — custom dark error panel | §3.5 |
| A3 | Per-site JS toggle | **KEEP** + add allowlist management screen (D7) | §4 |
| A4 | First-party cookie toggle | **KEEP** | §7 |
| A5 | Wipe-on-destroy | **KEEP** | §7 |
| A5 | "Clear session" button (lies) | **FIX** (D2) — actually wipe web state | §3.6 |
| A5 | Stale `noHistory/singleInstance` comment + "wiped on exit" copy | **FIX** (D2) | §3.6, §7 |
| A6 | Bookmarks | **KEEP** (cap-drop disclosed) | §3.7 |
| A7 | Find-in-page | **KEEP** + fix identical-glyph state cue | §3.8 |
| A8 | Diagnostics / eng dump / footer / caps beacon | **KEEP** | §8 |
| A8 | Provider authority hardcoded (eng/prod collision) | **FIX** (D11) | §8 |
| A9 | Tamper/attestation/window hardening | **KEEP** + honest hard-fail screen (D14, suite-wide) | §9 |
| A10 | I2P toggle state-machine (enabled-at-Idle, stuck checked+disabled, screen-scoped applier, repo-path leak) | **REDESIGN** (D3/D12) — eng-gated, correct state machine | §6 |
| A11 | I2P provider picker (write-only) + "Custom (advanced)" → nonexistent screen | **DROP** from UI (D4) — keep catalog+tests for phase β | §6 |
| A12 | Lokinet + Yggdrasil status cards | **DROP** from UI (D12) — both VpnService/TUN, vetoed | §6 |
| A13 | Downloads (silent dead-end) | **FIX** (D5) — DownloadListener → honest snackbar | §3.9 |
| A14 | Blocked-scheme navigation (silent dead-end) | **FIX** (D5) — feedback + `mailto:`/`tel:` opt-out hand-off | §3.9 |
| A15 | Share-target / VIEW intake (MISSING) | **REDESIGN** (D1) — the positioning feature | §2 |
| A16 | Tabs / multi-page | **KEEP DEFERRED** (honest) | — |
| A17 | Cromite fork ambition | **KEEP DEFERRED** (doc-only) | — |
| C | Hardcoded hex, 1-string `strings.xml`, sub-48dp targets, no semantics | **FIX** (D8) — shared tokens + strings + a11y | §10 |
| C | `secureClickable` on provider rows (SAMSUNG_QUIRKS tap-drop) | **FIX** (D13) — plain `clickable` (moot once rows drop, §6) | §6, §10 |
| — | FLAG_SECURE blocks screenshotting a suspicious page | **KEEP** + document + optional per-page reveal | §9 |

---

## 1. Screen / surface map (v2)

Everything is a `MaterialTheme` from common-security tokens. The main
browser surface is deliberately NOT a `TopAppBar` scaffold (it needs the
full-height WebView with a chrome-strip top and toggle-rows); the
secondary surfaces are `UnderstoryScaffold { UnderstoryTopAppBar(...) }`
so each has a real Back affordance.

| Surface | Type | Purpose | New in v2 |
|---|---|---|---|
| **Browser main** | Activity content (`MainActivity`) | URL bar, nav row, find bar, toggle rows, WebView, footer | error panel, honest Clear, Open-in-default, download/scheme feedback |
| **Intake interstitial** | Activity content, `mode = Intake` | "You're opening a link in the hardened viewer" confirm gate | **yes (D1)** |
| **Bookmarks** | overlay | star list / remove | keep |
| **JS allowlist** | overlay | review/revoke JS-allowed hosts | **yes (D7)** |
| **Diagnostics** | overlay (shared) | diag ring dump | keep |
| **Proxy (experimental)** | overlay, **eng-gated** | I2P userspace SOCKS/HTTP, honest state machine | **redesigned (D3/D12)** |

The three overlays remain overlays (not a nav graph) precisely because
the WebView composition must survive a trip to a secondary surface and
back (`MainActivity.kt:252-299` — a real navigation would tear the
WebView down and lose the loaded page). Keep that pattern.

---

## 2. Link intake — the doorway (D1, A15) — REDESIGN

This is the top ship gap: today the manifest is MAIN/LAUNCHER only
(`AndroidManifest.xml:179-183`), so no other app can hand a link to the
hardened viewer. We add **two intake paths**, both funneling through
**one confirmation interstitial** that neutralizes the URL-injection
objection recorded in `SUITE_THREAT_SURFACES.md:232-234`.

### 2.1 Intake path A — `ACTION_SEND` share target (primary)

The safe path: only ever fires from the **system share sheet**, i.e. from
an explicit user gesture inside another app ("Share → Understory Safe
View"). This is the doctrine-preferred additive channel (CD-2c).

Add a second `<intent-filter>` to the existing `MainActivity`
(`AndroidManifest.xml:179`):

```xml
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/plain" />
</intent-filter>
```

`android:label` the activity's SEND alias "Open in Safe View" via a
`<activity-alias>` (so the chooser entry reads a verb, not the app name).

Handling: `MainActivity.onCreate` / `onNewIntent` (singleTask →
`onNewIntent` fires for a live task) inspects `intent.action`:
`Intent.EXTRA_TEXT` is a free-form string that may contain surrounding
text (people share "check this out https://x.y"). Extract the **first
`http(s)` URL** with `android.util.Patterns.WEB_URL` +
`Linkify`-style matcher, normalize (§3.4), and route to the
**interstitial** (§2.3). If EXTRA_TEXT has zero URLs, open the
interstitial in a "no link found — paste manually" empty state that
prefills the URL bar with the raw text.

### 2.2 Intake path B — `ACTION_VIEW` http/https filter (optional, non-default)

The audit's THREAT_SURFACES objection is half-right: an exported VIEW
activity *can* be `startActivity`'d programmatically by any app with any
URL. We accept the filter anyway because (a) it is **non-default** — the
user must pick "Safe View" from the disambiguation chooser every time
(we NEVER call `RequestRole(ROLE_BROWSER)`, never prompt for default —
CD-2c; Chrome/Brave keep the role by construction, audit §B), and (b)
the **interstitial** (§2.3) means a drive-by `startActivity` lands on a
confirm gate showing the full URL with **JS OFF**, not an auto-load.
A programmatic launch cannot load a page; it can at most show the user a
URL they can cancel.

```xml
<intent-filter android:autoVerify="false">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" />
    <data android:scheme="http" />
</intent-filter>
```

Note `http` is included **only so the app appears in the chooser** for
http links (a suspicious link is often http); the interstitial and
`normalizeUrl` upgrade it to https and the network-security-config
hard-blocks cleartext load (`network_security_config.xml:17-23`), so an
http target that has no https endpoint fails at the transport layer with
the custom error panel (§3.5) — honest, not silent.

**Hardening the exported VIEW surface** (this is the injection mitigation
that makes B safe):

1. The interstitial is **mandatory** for every externally-delivered URL
   (SEND and VIEW alike). There is no code path from an inbound Intent
   straight to `WebView.loadUrl`.
2. A per-launch **nonce**: the interstitial's "Load" button is the only
   thing that calls the load path; an inbound Intent sets
   `pendingIntakeUrl` state and nothing else.
3. `android:launchMode="singleTask"` stays; on `onNewIntent` while a page
   is already loaded, the interstitial appears as a **full-screen modal
   over the current page** (BackHandler dismisses back to the current
   page, not a load) — a background app cannot silently replace what the
   user is looking at.

**Ship decision:** ship **path A (SEND) always**; ship **path B (VIEW)
behind a first-run opt-in** — a one-time card on first launch: "Also
offer Safe View when you tap links? You'll still pick it each time; it
never becomes your default browser. [Enable] [Not now]". Enabling flips a
`BrowserSettings.getViewFilterEnabled` pref that a
`PackageManager.setComponentEnabledSetting` call toggles on a disabled-
by-default `<activity-alias>` carrying the VIEW filter. This keeps the
default install's exported attack surface to SEND-only (the strictly-
safe path), and makes the broader-but-guarded VIEW filter a user choice —
squarely CD-2 (opt-in, graceful, honest).

### 2.3 The confirmation interstitial (the injection neutralizer)

A distinct composable `IntakeInterstitial(url, source)` rendered as
Activity content when `pendingIntakeUrl != null`, before any WebView
load. Full-bleed, `UnderstoryScaffold`, dark.

Contents:

- **Heading**: "Open this link in the hardened viewer?"
- **Sub**: "JavaScript will be OFF. No cookies, no downloads, no
  location, camera, or mic. The link opens here, not in your normal
  browser."
- **The full normalized URL**, `SelectionContainer`-wrapped, monospace,
  **host emphasized** (bold the registrable domain; dim the path/query so
  a look-alike host like `paypa1.com` is legible). Show the scheme
  literally so `http://` vs `https://` is visible.
- **Source line**: "Shared from: <app label>" when resolvable via
  `referrer` / `EXTRA_REFERRER`; "Opened by another app" otherwise. Never
  claim a source we can't verify.
- **Optional inline reputation** (future, non-blocking): a "Check
  reputation" affordance is out of scope for v1 code but the layout
  reserves a slot; do not claim it until wired.
- Primary button **"Open in Safe View"** (calls the load path with the
  normalized URL). Secondary **"Copy link"**. Tertiary **"Cancel"**
  (dismiss; if launched cold with no prior page, `finish()`).

The interstitial is the single answer to `SUITE_THREAT_SURFACES.md:232-234`
"a VIEW filter would turn every app into a URL injector": an injector can
put a URL on this screen; it cannot load it. Document this in
`SUITE_THREAT_SURFACES.md` as the accepted mitigation and flip the
"deliberately no VIEW filter" note to "VIEW filter is opt-in, gated by a
mandatory interstitial".

---

## 3. Browser main screen (v2)

Layout order unchanged from v1 (`MainActivity.kt:385-814`): URL row →
nav row → find bar → toggle rows → WebView → footer. Changes:

### 3.1 URL row

Keep the URL `TextField` + Go. The clear-"✕" becomes an `IconButton`
(48dp, `contentDescription = R.string.cd_clear_url`, §10). Go normalizes
via §3.4 and routes through the same load path the interstitial uses.

### 3.2 Nav row (back / forward / reload-stop / **open-in-default** / close)

Keep back/forward/reload-stop (`MainActivity.kt:470-505`). Convert each
`NavIconButton` from a 36dp `Box` (`MainActivity.kt:817-840`) to a 48dp
`IconButton` with a vector `Icon` + `contentDescription` (§10). Add:

- **Open in default browser** (D6, §5) — icon `ic_open_in_new`, enabled
  only when a page is loaded.
- **Close page** — the honest replacement for "Clear session" migrates
  to the toggle-row area (§3.6), but a small close/"×" also lives here.

### 3.3 Search-off honesty (A2 caveat)

"No search engine" is a deliberate choice (no query leakage) but today it
fails ugly: `cat pictures` → `https://cat pictures` → grey error page.
Fix: `normalizeUrl` (§3.4) detects **non-URL input** (contains a space,
or has no dot and isn't `localhost`) and, instead of loading garbage,
the load path shows an **inline chip** under the URL bar: "That doesn't
look like a web address. Safe View doesn't search the web — paste a full
link." No load is attempted. This is honest-UI (CD-4) not a dead-end.

### 3.4 `normalizeUrl` fix (D9, A1)

Current bug (`MainActivity.kt:960-970`):
`"https://" + trimmed.removePrefix("http://").removePrefix("HTTP://")`
leaves `Http://x` → `https://Http://x`. Rewrite:

```kotlin
private fun normalizeUrl(raw: String): NormalizeResult {
    val t = raw.trim()
    if (t.isEmpty()) return NormalizeResult.Empty
    // Reject obvious non-URLs -> caller shows the "not a web address" chip.
    val looksLikeUrl = !t.contains(' ') &&
        (t.contains('.') || t.startsWith("localhost", ignoreCase = true)
            || t.startsWith("http", ignoreCase = true))
    if (!looksLikeUrl) return NormalizeResult.NotAUrl(t)
    val lower = t.lowercase()
    val stripped = when {
        lower.startsWith("https://") -> t.substring("https://".length)
        lower.startsWith("http://")  -> t.substring("http://".length)
        else -> t
    }
    return NormalizeResult.Url("https://$stripped")
}
```

Return a small sealed result (`Url` / `NotAUrl` / `Empty`) so the caller
can drive the chip vs load. Case handled by measuring the lowercased
prefix length and slicing the **original** string. `http` is force-
upgraded to `https` at the input layer (matching the existing intent) and
the transport layer still hard-blocks any residual cleartext.

### 3.5 Custom error state (D10, A2) — REDESIGN

No `onReceivedError`/`onReceivedHttpError` override exists, so main-frame
failures render Chromium's grey page. Add to `HardenedWebViewClient`:

```kotlin
override fun onReceivedError(view, request, error) {
    if (request?.isForMainFrame == true) onMainFrameError(request.url, error.errorCode, error.description)
}
override fun onReceivedHttpError(view, request, resp) {
    if (request?.isForMainFrame == true) onMainFrameError(request.url, resp.statusCode, resp.reasonPhrase)
}
```

`onMainFrameError` sets an `errorState` in the composable that overlays
the WebView with the app's own dark panel:

- Title mapped from code: `ERROR_HOST_LOOKUP` → "Can't reach this site",
  `ERROR_CONNECT`/`ERROR_TIMEOUT` → "Connection failed",
  `ERR_CLEARTEXT_NOT_PERMITTED` surrogate (transport block) → "This site
  is only available over insecure http, which Safe View blocks", HTTP
  4xx/5xx → "The site returned an error (code)".
- The **full URL** (so the user can see what failed).
- **Retry** (re-invokes the load path) and **Open in default browser**
  (D6 — the failed page may work in Chrome).

Clear `errorState` on the next successful `onPageStarted` for a new URL.
Suppress error panels for sub-frame errors (ad-frame 404s must not blank
the page).

### 3.6 "Clear session" → honest (D2, A5) — FIX

Today the button (`MainActivity.kt:656-669`) resets only UI state and
toasts "Session cleared" while cookies/WebStorage/the WebView's page all
survive to `onDestroy`. Two acceptable resolutions; **we pick (a)** —
make it do what it says, because a quarantine viewer's "clear before I
hand over the phone" gesture must be real:

Rename to **"Clear now"** and implement a real wipe that mirrors
`onDestroy` (`MainActivity.kt:221-231`) *without* killing the Activity:

```kotlin
onClick = {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
    webView?.clearCache(true)
    webView?.clearHistory()
    webView?.clearFormData()
    webView?.clearSslPreferences()
    webView?.loadUrl("about:blank")   // drop the visible page + its DOM
    // UI reset (as today)
    url = ""; loadedUrl = null; jsEnabled = false; pageTitle = null
    canGoBackState = false; canGoForwardState = false
    errorState = null
    snackbar("Cleared: cookies, storage, history, and the current page.")
}
```

We do **not** destroy+recreate the WebView instance here (that would need
a recomposition dance and risk the "WebView only after first load"
workaround, `MainActivity.kt:727-735`); `loadUrl("about:blank")` +
`clearHistory` drops the page and its DOM/JS context, and the cookie/
storage wipe is global. This satisfies "clear the session's web state"
per the audit. The snackbar names exactly what was cleared (CD-4d).

Also (D2):
- Fix the stale comment `MainActivity.kt:217-218` ("noHistory +
  singleInstance + excludeFromRecents") to match the real manifest
  (`singleTask`, recents-visible — `AndroidManifest.xml:171-177`).
- Reword the empty-state placeholder (`MainActivity.kt:742-747`) from
  "wiped on exit" to **"Cleared when you close the app, or tap Clear now.
  Switching apps does NOT clear it."** — matching destroy-time reality
  (a backgrounded task keeps cookies until the OS/user kills it). This
  removes the implied "cleared when I switch away" reading (CD-4e).

### 3.7 Bookmarks (A6) — KEEP

Single-source-of-truth at AppRoot, 200-cap oldest-drop, keyed list — all
correct. One honesty nit: add a one-line snackbar when a star is dropped
at cap ("Oldest bookmark removed (200 max)"). Delete-"✕" gets a 48dp
target + `contentDescription` (§10). No confirm needed (recoverable).

### 3.8 Find-in-page (A7) — KEEP + glyph fix

`findAllAsync` + counter all work. Fix the identical-glyph bug
(`MainActivity.kt:529`, `if (findActive) "⌕" else "⌕"`): use a filled vs
outlined `Icon` (or a `tint` change to `colorScheme.primary` when
active) so the toolbar shows find mode is on. Counter text → ≥14sp (§10).

### 3.9 Honest dead-ends (D5, A13/A14) — FIX

**Downloads** — no `DownloadListener` exists (grep-confirmed zero hits).
Add one at WebView construction:

```kotlin
wv.setDownloadListener { downloadUrl, _, _, mimeType, _ ->
    snackbar("Downloads are disabled in this viewer.")
    // Offer a hand-off: action "Open in default browser" -> Intent(ACTION_VIEW, downloadUrl)
}
```

The snackbar carries an action **"Download in Chrome"** →
`Intent(ACTION_VIEW, downloadUrl)` (the complement hand-off; if the user
actually wants the file, their real browser handles it). No storage
permission is added; view-only stays the policy (`README.md:21` stays
accurate).

**Blocked schemes** — `shouldOverrideUrlLoading` returns `true` (swallow)
for every non-https scheme with no feedback (`MainActivity.kt:1056-1058`).
Replace the blanket swallow with a scheme switch:

- `mailto:` / `tel:` / `sms:` — **opt-out hand-off** (complement, CD-1):
  `startActivity(Intent(ACTION_VIEW, uri))` inside `runCatching`; on
  `ActivityNotFoundException`, snackbar "No app to handle this". This
  lets a user tap a `mailto:` on a page and compose in their real mail
  app — Safe View never touches the mail itself.
- `intent:` / `javascript:` / `blob:` / `file:` / `content:` /
  `market:` / any other — **refuse with feedback**: snackbar "Blocked a
  non-web link (`scheme:`)". Still returns `true` (swallow the nav) — the
  hardening is unchanged, only the silence is fixed.

A `BrowserSettings.getExternalHandoffEnabled` pref (default ON) governs
the mailto/tel/sms hand-off for users who want zero outbound intents;
when OFF those schemes take the "refuse with feedback" branch.

---

## 4. JS allowlist management (D7, A3) — FIX

Today a host granted JS stays granted invisibly (`BrowserSettings.kt:47-56`
read-only by the per-nav check). Add a **JS allowlist overlay**:

- Entry point: **long-press the JS toggle** in the toggle row, AND a row
  in the (new) small settings sheet. The toggle's `contentDescription`
  announces "JavaScript for this site: off. Long-press to manage allowed
  sites."
- `UnderstoryScaffold { UnderstoryTopAppBar("Sites allowed to run JS", onBack) }`.
- Keyed `LazyColumn` of hosts from `BrowserSettings` JS set; each row =
  host + a 48dp remove `IconButton` (`contentDescription` "Remove <host>").
- Empty state: "No site is allowed to run JavaScript. Safe View keeps JS
  off unless you turn it on for a specific site."
- Removing a host that is the current page's host re-derives policy and
  reloads (so JS actually turns back off live), matching the toggle's own
  apply-on-change behavior (`MainActivity.kt:608-629`).

`BrowserSettings` gains `removeJsHost(host)` and `getJsHosts(): List<String>`
(sorted) — pure additions over the existing JSON host set.

---

## 5. Open-in-default-browser hand-off (D6, A14) — FIX

The exit half of the complement loop (inspect here → trust it → continue
in Chrome). A nav-row action + an error-panel action + a title-strip
long-press option, all one call:

```kotlin
fun openInDefault(ctx: Context, url: String) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Exclude ourselves so we don't appear in our own chooser / loop back.
    runCatching { ctx.startActivity(Intent.createChooser(i, "Open in browser")) }
        .onFailure { snackbar("No browser available") }
}
```

Uses the **canonical post-redirect URL** (`wv.url`, as the copy-URL path
already does — `MainActivity.kt:483-505`), not the typed URL. Needs no
filter of our own and claims no role (audit §B). `createChooser` lets the
user land in whichever real browser they prefer.

---

## 6. Proxy / overlay networks — honest shrink (D3/D4/D12/D13) — REDESIGN + DROP

Verified state today: I2P switch is live at `Idle`
(`I2pStatus.kt:50`, `ProxyScreen.kt:129-149`), goes checked+disabled
after `BinaryMissing`, the applier lives inside screen composition
(`ProxyScreen.kt:160-165`), the provider picker persists a pref nothing
reads (`ProxyScreen.kt:190-200`), "Custom (advanced)" points at a
nonexistent screen (`I2pProvider.kt:82-91`), Lokinet/Yggdrasil are
VpnService/TUN designs (`YggdrasilStatus.kt:16-21`,
`OVERLAY_NETWORKS.md:44-50`) — **vetoed**, and the banner leaks a repo
path (`ProxyScreen.kt:264-267`).

**v1 decision: gate the entire Proxy surface behind the eng build**
(same `.eng` applicationId-suffix gate the Diagnostics dump uses,
`DiagnosticsDump.kt:95-97`, flavor `build.gradle.kts:76-81`). A prod
build shows **no Proxy entry point at all** — the `onOpenProxy` menu row
(`MainActivity.kt:282-285`) is only composed when
`DiagnosticsDump.isEng()`. Rationale: I2P userspace routing is the only
doctrine-compatible overlay, but phase α ships no binary, so in prod
there is nothing honest to show. Eng builds keep it for phase-β
development. This satisfies D3's accepted alternative ("hide behind the
eng gate until phase β") and is the cleanest CD-4a resolution (zero dead
controls in prod).

**Concrete changes to the (eng-only) ProxyScreen:**

1. **DROP Lokinet + Yggdrasil cards** (`ProxyScreen.kt:210-220,240-258`)
   from the UI entirely (D12). Both are VpnService/TUN → permanently
   vetoed (CD-2a). Keep the `overlay-lokinet` / `overlay-yggdrasil`
   modules OUT of the browser's `settings.gradle.kts` dependency for the
   prod flavor; if kept for eng, they render nothing. Remove
   `lokinetLabel()` / `yggdrasilLabel()` / `OtherOverlayStatus` from
   ProxyScreen. (The firewall app's overlay cards are handled in its own
   design doc; the browser stops advertising them.)

2. **DROP the provider picker + "Custom (advanced)"** (D4, A11):
   remove the `I2pProvider.ALL.forEach { ProviderRow }` block
   (`ProxyScreen.kt:186-201`) and the `ProviderRow` composable
   (`ProxyScreen.kt:283-326`) — nothing consumes `i2p_provider`, and
   "Custom (advanced)" references a screen that does not exist. The
   catalog data (`I2pProvider.kt`) and its unit tests stay in the module
   for phase β; they just aren't rendered. This also **eliminates the
   `secureClickable` misuse** (D13, `ProxyScreen.kt:296`) by deletion —
   no provider rows means no SAMSUNG_QUIRKS tap-drop surface. (If any
   `secureClickable` remains anywhere in the browser on a non-destructive
   row, replace with plain `clickable` per `SAMSUNG_QUIRKS.md:38-45`.)

3. **Fix the I2P switch state machine** (D3, A10) — even eng-gated, it
   must be honest:
   - `canToggle` becomes `status is I2pStatus.State.Ready ||
     status is I2pStatus.State.Idle` **and** `PROXY_OVERRIDE` supported —
     but since phase α can only reach `BinaryMissing`, in practice the
     switch renders **disabled** with the banner explaining why. Never
     render **checked+disabled**: derive `checked` from status
     (`status is Ready && applied`), not from a free `i2pEnabled` bool.
     Kill the stuck-on bug by making the switch a pure function of
     supervisor state, not a latched local var.
   - The `Idle→live-switch→spawn FGS→discover BinaryMissing` path is
     removed: at `Idle`/`BinaryMissing` the switch is disabled, so first
     tap can't spawn the service just to learn there's no binary. (The
     supervisor may probe for the binary on screen-open, not on toggle.)

4. **Move the ProxyApplier effect out of screen composition** (D12,
   `ProxyScreen.kt:160-165`): the `LaunchedEffect` that calls
   `ProxyApplier.applyHttpLocal` must live at the **Activity/AppRoot**
   level keyed on `I2pStatus.state`, not inside `ProxyScreen`, so a
   Ready transition applies the override even if the user navigated back
   out of the proxy overlay. (Landmine for phase β; fixed now while the
   code is small.)

5. **Remove the repo-path leak** (`ProxyScreen.kt:264-267`): the
   `BinaryMissing` banner drops "See android/overlay-i2p/BUILD_RECIPE.md"
   — user-facing copy never cites repo paths. Replace with "Not available
   in this build."

6. **POST_NOTIFICATIONS / FGS honesty** (audit §B, phase-β note): the
   I2P FGS posts a notification channel (`I2pProxyService.kt:121-152`)
   but `POST_NOTIFICATIONS` is stripped (`AndroidManifest.xml:96`), so on
   API 33+ the ongoing "I2P running" notification is silently suppressed
   while the service runs. For phase β, either (a) add
   `POST_NOTIFICATIONS` as an **opt-in** with graceful degradation (CD-2b)
   so the user can see the running-tunnel notification, or (b) surface an
   in-app persistent status chip on the main screen instead. Decide at
   phase β; flagged here so it isn't forgotten. **In v1 (eng-only, no
   binary) it never fires** — no action needed for ship.

Net effect: in **prod**, the overlay-proxy surface is entirely absent
(no over-claim, doctrine-clean per audit §E). In **eng**, it is an
honest I2P-only experimental screen with a correct state machine, ready
to grow the phase-β NDK i2pd path (`overlay-i2p/BUILD_RECIPE.md`) — the
only overlay that never touches the VPN slot (`OVERLAY_NETWORKS.md:21-33`).

---

## 7. Hardened WebView core (A1/A4/A5) — KEEP (verified)

Every claim in the `MainActivity.kt:77-121` matrix traces end-to-end
(audit A1) and is re-verified this session (`buildHardenedWebView`
`MainActivity.kt:972-1038`, `onDestroy` wipe `MainActivity.kt:213-232`,
scheme refusal `MainActivity.kt:1049-1058`). **Do not touch the
hardening.** v2 only *adds honesty and feedback around* it:

- JS-off default + per-site allowlist (main-frame, re-derived per nav):
  keep; add the management screen (§4).
- Blocked schemes: keep the refusal; add feedback + mailto/tel hand-off
  (§3.9).
- 3P cookies off unconditionally; 1P behind default-off toggle: keep.
- Mixed-content `NEVER_ALLOW` + `usesCleartextTraffic=false` + system-only
  trust anchors: keep.
- SafeBrowsing behind `WebViewFeature` gate: keep.
- Geo/cam/mic off + auto-denied + permissions stripped: keep.
- Wipe-on-destroy: keep; the manual "Clear now" (§3.6) is the additive
  honest path. Fix the stale comment + placeholder copy (§3.6).
- FLAG_SECURE, overlay-hiding, recents-screenshot-off, WebView debugging
  off: keep (§9).

The ephemeral guarantee remains **"on destroy, or on Clear now"** — never
"on leave". The placeholder copy is corrected to say so (§3.6). This is
the CD-4e (cleanup-claims-match-implementation) fix.

---

## 8. Suite beacon + eng/prod coexistence (A8, D11) — FIX

Diagnostics/eng-dump/footer/caps beacon all verified working (audit A8).
One fix: the provider authority is hardcoded
`com.understory.browser.suitecaps` (`AndroidManifest.xml:189`), so an eng
build (applicationId `com.understory.browser.eng`) declares the *same*
authority and **cannot install beside prod** (Android rejects duplicate
authorities).

Fix (browser-side of the suite-wide §10/SUITE.md item):

```xml
<provider
    android:name=".SuiteCapsProvider"
    android:authorities="${applicationId}.suitecaps"
    ... />
```

`${applicationId}` resolves to `com.understory.browser` (prod) /
`com.understory.browser.eng` (eng), so they coexist. Registry lookups are
keyed to the prod package already (audit A8 — eng ignored), so peer
discovery is unaffected. passgen already did exactly this
(`understory-passgen/passgen/build.gradle.kts:74`); copy the pattern.

---

## 9. Tamper / attestation / window hardening (A9, D14) — KEEP + honest fail

Hard-fail on debugger/tamper/tampered-sibling (`MainActivity.kt:157-164`,
resume re-check `:203-211`) and FLAG_SECURE-before-setContent
(`MainActivity.kt:166-171` per `SAMSUNG_QUIRKS.md:132-140`) all verified —
**keep**. Two UX honesty items:

1. **Hard-fail vanish** (D14): `finishAndRemoveTask()` with zero
   explanation is indistinguishable from a crash on a false positive.
   Route through the **suite-wide integrity-failure screen** (owned by
   the common-security consolidation, tracked once suite-wide): a
   one-line "Integrity check failed — Safe View won't run on a modified
   system" then exit on dismiss. Browser only wires to it; it does not
   define it. (Note for the operator's own device: `hardFail` needs
   patcher/Xposed/Frida/signature per `Tamper.kt:47-52`; Shizuku/Termux/
   Axeron trip only soft `rootMarkers` warnings, so the suite runs on the
   ryznix phone.)

2. **FLAG_SECURE vs "screenshot the scam to report it"** (audit §B):
   FLAG_SECURE blocks screenshots, but a quarantine-viewer user often
   *wants* to screenshot a suspicious page to forward it. **Keep
   FLAG_SECURE on by default** (it is core anti-emanation posture,
   MEMORY priority #2), but add an **eng-only** `ALLOW_SCREENSHOTS`
   toggle already scaffolded in TestingMode, and document the trade in
   `README.md` + the empty-state so users aren't surprised their
   screenshot is black. Do **not** expose the toggle in prod — the
   report-the-scam path is "Open in default browser → screenshot there"
   (§5), which is the honest complement answer.

---

## 10. GUI / a11y / M3 / strings (C, D8/D13) — FIX

Consumes the common-security shared tokens + components (SUITE.md §9); no
palette defined here.

- **Theme tokens**: replace every hardcoded hex literal across
  `MainActivity.kt` / `ProxyScreen.kt` (`Color(0xFF0A0A0A)`,
  `0xFF9E9E9E`, `0xFF1C1C1C`, status amber/green/red, …) with
  `MaterialTheme.colorScheme.*` / `UnderstoryColors.*` from
  common-security. Status-banner colors map to semantic tokens
  (`warning`/`success`/`error`) rather than raw `0xFFFFB74D` etc. Dark
  theme comes from the shared token set; declare it (not an implicit
  `darkColorScheme()` with black XML bars, `themes.xml:3-7`).
- **Strings**: move **every** user-facing string (all of `MainActivity.kt`
  + `ProxyScreen.kt` — today `strings.xml` has exactly `app_name`,
  `strings.xml:3`) into `res/values/strings.xml` with `R.string.*`
  references. New strings introduced by this design (interstitial copy,
  error-panel titles, snackbars, allowlist screen, content descriptions)
  all go in resources. Keep `resourceConfigurations = ["en"]`
  (`build.gradle.kts:17`) for v1 — but the extraction unblocks i18n
  later; note that in the doc, don't claim locales we don't ship.
- **Touch targets**: `NavIconButton` (36dp `Box`, `MainActivity.kt:817-840`)
  → 48dp `IconButton`. Clear-URL "✕" (`:408-421`) and bookmark-delete
  "✕" (`:935-942`) → 48dp `IconButton`s. Find prev/next → 48dp.
- **Semantics**: every glyph control gets `contentDescription` (from
  strings). TalkBack must read "Back", "Reload", "Clear address",
  "Remove bookmark <title>", "Manage JavaScript sites" — not "‹" / "⟳" /
  "✕". Add `Modifier.semantics { role = Role.Button }` where an
  `IconButton` isn't used.
- **Type sizes**: find counter and title strip 11sp → ≥14sp
  (`bodyMedium`); interstitial URL host ≥16sp for legibility.
- **`secureClickable`**: removed with the provider rows (§6); audit the
  rest of the module and ensure no non-destructive row uses it
  (`SAMSUNG_QUIRKS.md:38-45`) — the Edge-Panel tap-drop is real on the
  operator's Samsung.
- **Portrait-lock** (`AndroidManifest.xml:175-176`): keep portrait-locked
  for v1 (consistent with suite; a quarantine viewer doesn't need
  landscape video). Note the choice in the doc as deliberate, not an
  oversight. Split-screen stays off (`resizeableActivity=false`) — a
  hardened viewer sharing a screen with an untrusted app is a downgrade.

---

## 11. Manifest delta (summary)

All within the existing single `MainActivity` (singleTask preserved so
`onNewIntent` handles live-task intake):

1. Add `ACTION_SEND text/plain` intent-filter (§2.1) + an `activity-alias`
   labelled "Open in Safe View".
2. Add a **disabled-by-default** `activity-alias` carrying the
   `ACTION_VIEW http/https BROWSABLE` filter (§2.2), toggled on by the
   first-run opt-in via `setComponentEnabledSetting`.
3. `provider android:authorities` → `${applicationId}.suitecaps` (§8).
4. No new permissions. INTERNET + ACCESS_NETWORK_STATE stay; everything
   else stays stripped (the entire `tools:node="remove"` sweep is
   correct and untouched). No `POST_NOTIFICATIONS`, no storage, no
   `REQUEST_INSTALL_PACKAGES` — view-only, no downloads.
5. `<queries>` unchanged (siblings + tamper lookups). The mailto/tel
   hand-off (§3.9) and open-in-default (§5) use implicit `ACTION_VIEW`
   which does not need `<queries>` entries to `startActivity` (only to
   *resolve* names, which we don't).

---

## 12. Ordered implementation checklist

1. **D1 intake** — SEND filter + alias, VIEW alias (disabled default) +
   first-run opt-in, `IntakeInterstitial` composable, `onNewIntent`
   routing, URL extraction from EXTRA_TEXT (§2).
2. **D9 normalizeUrl** rewrite + `NotAUrl` chip (§3.3/§3.4) — unblocks
   correct intake + typed-URL handling.
3. **D2 Clear now** real wipe + stale-comment + placeholder copy (§3.6).
4. **D5 dead-end feedback** — DownloadListener snackbar; blocked-scheme
   feedback + mailto/tel/sms hand-off pref (§3.9).
5. **D6 open-in-default** nav-row + error-panel + title-strip (§5).
6. **D10 custom error panel** — onReceivedError/HttpError overrides (§3.5).
7. **D7 JS allowlist screen** + `BrowserSettings` accessors (§4).
8. **D3/D4/D12/D13 proxy shrink** — eng-gate the whole surface, drop
   Lokinet/Yggdrasil + provider picker + "Custom", fix switch state
   machine, hoist ProxyApplier effect, remove repo-path leak (§6).
9. **D11 provider authority** `${applicationId}.suitecaps` (§8).
10. **D8/D13 GUI pass** — tokens, strings, 48dp targets, semantics, type
    sizes, secureClickable audit, find-glyph state cue (§10, §3.8).
11. **D14 honest hard-fail** wire to suite integrity screen; FLAG_SECURE
    doc note + eng screenshot toggle (§9).

Non-negotiable for any public alpha (SUITE.md §6): D1 (positioning), D2
(the "Clear" lie), D5 (dead-ends), D3/D12 (proxy over-claim). The
hardening core already ships.
