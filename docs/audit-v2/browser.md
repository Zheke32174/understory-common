# Audit v2 — understory-browser (`com.understory.browser`)

Audited 2026-07-03 against the NEW SUITE DOCTRINE (complement-don't-replace;
Tailscale `com.tailscale.ipn` permanently holds the one Android VPN slot —
**any feature requiring VpnService is VETOED**; Chrome/Brave stays the daily
driver). Read-only audit; every claim carries file:line evidence. Paths are
relative to `C:\repos\understory\understory-browser\` unless prefixed
`common/` (= `C:\repos\understory\understory-common\`). Vendored shared
modules (`common-security/*.kt`, all three `overlay-*` trees) were
hash/diff-verified byte-identical to understory-common.

**Headline:** the hardening core is real — every line of the phase-1 matrix
in the `MainActivity.kt` KDoc traces end-to-end in code. The two problems
are (1) *positioning*: the app has no share-target and no VIEW filter
(`browser/src/main/AndroidManifest.xml:179-182` — MAIN/LAUNCHER only), so the
one thing a complement-browser is *for* — "open this suspicious link in the
hardened viewer" — physically cannot happen from another app; and (2) the
*overlay-proxy surface*: the I2P toggle is a working switch wired to a
supervisor that, by design, can only report "no binary bundled"
(`overlay-i2p/.../I2pProxyService.kt:76-98`), the provider picker persists a
preference nothing consumes, and the Yggdrasil lane's own docs commit it to a
VpnService — vetoed. The browser itself touches **zero** scarce Android
slots, which makes it the most doctrine-clean app in the suite once the
Proxy screen is made honest.

---

## A. FEATURE LEDGER

### A1. Hardened WebView configuration (the 2026-07-03 hardening matrix)
**Status: WORKING** — every claim in the KDoc matrix
(`browser/src/main/java/com/understory/browser/MainActivity.kt:77-121`) traces:

- **JS off by default, per-site allowlist**: default off at build
  (`MainActivity.kt:977`), allowlist persisted as JSON host set
  (`BrowserSettings.kt:32-56`), re-derived before every explicit load
  (`MainActivity.kt:403,431`), on every in-page navigation *before* commit,
  main-frame only (`MainActivity.kt:1065-1069`), and re-applied on
  back/forward traversal via `doUpdateVisitedHistory`
  (`MainActivity.kt:1100-1104` — the "late for the first script" caveat is
  honestly documented in-source).
- **file:// / content:// blocked**: all four `WebSettings` flags false
  (`MainActivity.kt:980-985`) plus `shouldOverrideUrlLoading` refuses every
  non-https scheme (`MainActivity.kt:1056-1058`) — this also kills
  `intent:`, `javascript:`, `blob:` top-level navigation.
- **Cookies**: third-party unconditionally off (`MainActivity.kt:1022`),
  first-party behind persisted default-off toggle (`MainActivity.kt:1021`,
  `BrowserSettings.kt:66-74`), live revoke drops accepted cookies
  (`MainActivity.kt:640-645`), all wiped in `onDestroy`
  (`MainActivity.kt:221-229`).
- **Mixed content / cleartext**: `MIXED_CONTENT_NEVER_ALLOW`
  (`MainActivity.kt:988`) + transport-layer deny with system-only trust
  anchors (`browser/src/main/res/xml/network_security_config.xml:17-23`,
  manifest `usesCleartextTraffic="false"` `AndroidManifest.xml:160`).
- **SafeBrowsing**: enabled behind `WebViewFeature` check, graceful no-op
  otherwise (`MainActivity.kt:1032-1036`); the empty-state placeholder text
  tells the user this honestly (`MainActivity.kt:746-747`).
- **Geo/cam/mic**: settings off (`MainActivity.kt:995`), prompt auto-denied
  (`MainActivity.kt:1127-1137`), every `PermissionRequest` denied
  (`MainActivity.kt:1139-1145`), and the Android permissions themselves
  `tools:node="remove"`-stripped (`AndroidManifest.xml:67-83`).
- **No form save / no WebView passwords**: (`MainActivity.kt:990-992`);
  autofill deliberately deferred to the system service (passgen/aegis path)
  — correct complement posture.
- **SSL hard-fail** (`MainActivity.kt:1107-1111`), **no popups/new windows**
  (`MainActivity.kt:999-1002,1162-1171`), **no file chooser**
  (`MainActivity.kt:1151-1160`), **no autoplay** (`MainActivity.kt:998`),
  **no cache** (`MainActivity.kt:1004`), **DOM/database storage off**
  (`MainActivity.kt:1010-1011`).
- Permission-stripping sweep in the manifest is genuinely exhaustive
  (`AndroidManifest.xml:36-119` — SMS/telephony/BT/NFC/satellite/location/
  audio/camera/biometrics/storage/notifications/sensors/SIP/contacts all
  `tools:node="remove"`).

One real (small) defect inside this matrix: **`normalizeUrl` mixed-case
scheme bug** — `startsWith("http://", ignoreCase = true)` matches
`Http://x`, but the two case-sensitive `removePrefix` calls
(`MainActivity.kt:966`) both miss, producing `https://Http://x` (garbage
load). Fails weird, not open, but it's a dead-end UX on a pasted URL.

### A2. URL bar + Go + navigation row (back/forward/reload-stop, title strip, copy-URL)
**Status: WORKING.** Explicit load path (`MainActivity.kt:391-437`),
nav state tracked via client callbacks (`MainActivity.kt:766-770,1083-1092`),
reload doubles as stop while loading (`MainActivity.kt:470-482`), title strip
copies the *canonical* post-redirect URL from `wv.url`
(`MainActivity.kt:483-505`), boomerang-load prevention via `onUrlCommitted`
(`MainActivity.kt:773-783`). System back walks WebView history first
(`MainActivity.kt:234-249`).
**Caveat (honest-UI):** there is no search fallback — typing `cat pictures`
normalizes to `https://cat pictures` and lands on the stock WebView error
page. For a hardened viewer "no search engine" is a defensible *choice*
(no query leakage), but today it is an accident with an ugly failure mode:
no `onReceivedError` override exists anywhere, so the user gets Chromium's
grey default error page inside an otherwise custom-styled app.

### A3. Per-site JS opt-in toggle (toolbar)
**Status: WORKING**, with one gap. Toggle flips + persists + reloads so the
policy actually applies to the current document
(`MainActivity.kt:608-629`). Gap: **the allowlist has no management UI** —
there is no screen to review or revoke JS-allowed hosts
(`BrowserSettings.kt:47-56` is read only by the per-navigation check). A
host granted JS once stays granted until the user happens to revisit it and
notice the button state. For a security-posture app, an invisible
persistent grant list is off-brand.

### A4. First-party cookie toggle
**Status: WORKING.** Persisted, applied live, revokes on off
(`MainActivity.kt:630-650`, `BrowserSettings.kt:66-74`), re-applied at
WebView construction (`MainActivity.kt:1019-1023`).

### A5. Ephemeral session (wipe-on-destroy) + "Clear session" button
**Status: WORKING (wipe-on-destroy) / MISLEADING (the button).**
The `onDestroy` wipe is complete: cookies, WebStorage, cache, history, form
data, SSL prefs, `WebView.destroy()` (`MainActivity.kt:213-232`).
Two honesty problems:
- **The "Clear session" button does not clear the session's web state.** It
  resets *UI* state only (`url`, `loadedUrl`, `jsEnabled`, nav flags —
  `MainActivity.kt:656-669`) and toasts "Session cleared". Cookies,
  WebStorage and the old WebView instance (still holding its page) survive
  until Activity destroy. The removed-from-composition WebView is also never
  `destroy()`ed at that moment — it leaks until `onDestroy` replaces it.
  A user who taps "Clear session" before handing the phone over has been
  lied to.
- The `onDestroy` comment claims "noHistory + singleInstance +
  excludeFromRecents" (`MainActivity.kt:217-218`) but the manifest is
  `singleTask`, recents-visible, no noHistory
  (`AndroidManifest.xml:171-183`) — deliberate per RELEASE_BLOCKERS
  (`common/docs/RELEASE_BLOCKERS.md:83-91`), so the *comment* is stale, and
  with it the implied guarantee: a backgrounded browser keeps its cookies
  alive for hours until the OS or the user kills it. The ephemeral claim is
  "on destroy", not "on leave" — placeholder text (`MainActivity.kt:742-747`)
  says "wiped on exit", which a user will read as "when I switch away".

### A6. Bookmarks (star toggle, list overlay, remove, 200-cap)
**Status: WORKING.** Single source of truth lifted to AppRoot
(`MainActivity.kt:264-292`), star reads canonical URL
(`MainActivity.kt:507-527`), overlay list with empty state, keyed LazyColumn,
remove (`MainActivity.kt:842-899`), persistence + oldest-drop cap
(`BrowserSettings.kt:100-146,162`). Silent-drop-at-cap is undisclosed but at
200 entries it's academic. Titles/URLs render single-line ellipsized
(`MainActivity.kt:920-933`).

### A7. Find-in-page
**Status: WORKING.** `findAllAsync` + `FindListener` + prev/next + live
"i / N" counter + clear-on-close (`MainActivity.kt:528-598,788-798`).
Nit: the find-toggle glyph is `if (findActive) "⌕" else "⌕"` — identical in
both states (`MainActivity.kt:529`), so the toolbar gives no visual cue that
find mode is on.

### A8. Diagnostics overlay + eng dump + suite footer + caps beacon
**Status: WORKING.** Diagnostics ring + screen are shared and complete
(`common/common-security/.../Diagnostics.kt:70-92`,
`DiagnosticsScreen.kt:52-63`), overlay preserves the WebView composition
underneath (`MainActivity.kt:252-299`). `DiagnosticsDump.activateIfEng`
correctly gates on the `.eng` applicationId suffix
(`common/.../DiagnosticsDump.kt:95-97`; flavor at
`browser/build.gradle.kts:76-81`). SuiteStatusFooter renders tier + peers
(`common/.../SuiteStatusFooter.kt:44-158`), backed by the cert-pinned,
version-attesting registry (`common/.../SuiteCapabilityRegistry.kt:152-197`)
and the locked read-only provider (`common/.../BaseCapabilityProvider.kt:71-105`,
manifest wiring `AndroidManifest.xml:186-192`).
**Defect (S):** the provider authority is hardcoded
`com.understory.browser.suitecaps` (`AndroidManifest.xml:189`) — an `eng`
build (applicationId `com.understory.browser.eng`) declares the *same*
authority, so prod + eng **cannot be installed side by side** (Android
rejects duplicate provider authorities at install).

### A9. Tamper / SuiteAttestation / debugger gate + window hardening
**Status: WORKING, with a UX honesty caveat.** Hard-fail exit on debugger,
tamper, or tampered sibling (`MainActivity.kt:157-164`; checks:
`common/.../Tamper.kt:81-98`, `SuiteAttestation.kt:66-106`; re-check on
resume `MainActivity.kt:203-211`). FLAG_SECURE set before `setContent`
per SAMSUNG_QUIRKS rule (`MainActivity.kt:166-171` vs
`common/docs/SAMSUNG_QUIRKS.md:132-140`), overlay-hiding + recents-screenshot
off (`MainActivity.kt:172-181`), WebView debugging force-off
(`MainActivity.kt:186`). TestingMode flags verified false
(`common/.../TestingMode.kt:34,56`). Caveat: hard-fail is
`finishAndRemoveTask()` with **zero user-visible explanation** — on a
false positive (e.g. Samsung preload matching a probe) the app just
vanishes on tap, indistinguishable from a crash. Suite-wide pattern, noted
here because the browser is the app most likely to be launched casually.
Note for the operator's own device: `hardFail` requires patcher/Xposed/
Frida/signature (`Tamper.kt:47-52`) — Shizuku/Termux/AxManager only trip
soft `rootMarkers` warnings, so the suite runs on the ryznix phone.

### A10. Proxy screen — I2P toggle
**Status: UNFINISHED (scaffold with a live switch).** The chain is:
Switch → `I2pProxyService.start()` (`browser/.../ProxyScreen.kt:131-149`) →
FGS promotes, checks `nativeLibraryDir/libi2pd.so`, finds nothing, posts
`BinaryMissing`, self-stops (`overlay-i2p/.../I2pProxyService.kt:40-64,76-98`).
The Ready→`ProxyController` application path exists and is correct
(`ProxyScreen.kt:160-165,334-371` — feature-gated `applyHttpLocal`), but is
unreachable: no build can produce `Ready` (phase-β `ProcessBuilder` path is
an explicit `Error("not yet implemented")` even *with* a binary,
`I2pProxyService.kt:99-112`). Three concrete defects on top of the honest
scaffolding:
1. **The toggle is enabled when it can never work.** `canToggle` only
   disables on `BinaryMissing` (`ProxyScreen.kt:129-130`), but the initial
   state is `Idle` (`overlay-i2p/.../I2pStatus.kt:50`) — so on first visit
   the switch is live, flips ON, spawns a foreground service + notification,
   and only then discovers BinaryMissing.
2. **Stuck-on switch**: after that, `i2pEnabled` stays `true`
   (`ProxyScreen.kt:73,140`) while `canToggle` goes false — a checked,
   disabled switch claiming I2P routing is on. Doctrine-grade dishonest UI.
3. **Proxy application is scoped to the screen's composition**: the
   `LaunchedEffect` that applies the override lives inside `ProxyScreen`
   (`ProxyScreen.kt:160-165`), so if the supervisor reached Ready after the
   user navigated back, the override would never be applied (moot in phase
   α; landmine for phase β).
The status banner also leaks a repo path at the user
("See android/overlay-i2p/BUILD_RECIPE.md", `ProxyScreen.kt:264-267`).

### A11. Proxy screen — I2P provider catalog
**Status: MISLEADING.** The picker persists `i2p_provider`
(`ProxyScreen.kt:190-200`, `BrowserSettings.kt:83-92`) — and **nothing ever
reads it back except the picker itself**. `I2pProxyService` never references
`I2pProvider`/reseed/outproxy (grep: zero hits outside ProxyScreen +
BrowserSettings). The UI presents reseed/outproxy trust selection as a
functioning choice; it is a write-only preference. Worse, the "Custom
(advanced)" entry instructs "Edit the values via the advanced config screen"
(`overlay-i2p/.../I2pProvider.kt:82-91`) — **no such screen exists** in the
codebase. The catalog data itself is well-curated with honest privacy notes
(`I2pProvider.kt:38-91`) and unit-tested
(`overlay-i2p/src/test/.../I2pProviderTest.kt`), but shipping it as an
active-looking control is a claims-vs-code gap.

### A12. Proxy screen — Lokinet + Yggdrasil status cards
**Status: UNFINISHED scaffold (honestly labelled) / phase-β design
UNVIABLE.** The cards are read-only, pinned to `BinaryMissing`, and say
"Scaffold only — daemons not bundled" (`ProxyScreen.kt:210-220,240-258`;
`overlay-lokinet/.../LokinetStatus.kt:47`, `overlay-yggdrasil/.../YggdrasilStatus.kt:50`)
— honest as far as they go. But the committed phase-β designs violate the
doctrine:
- **Yggdrasil**: its own KDoc and manifest commit to "a VpnService wrapper
  rather than a foreground daemon" (`YggdrasilStatus.kt:16-21`,
  `overlay-yggdrasil/src/main/AndroidManifest.xml:4-6`). **VpnService is
  VETOED** — Tailscale holds the slot. Unshippable as designed, ever.
- **Lokinet**: `LokinetStatus.Ready(socksPort)` implies a SOCKS-only design
  (`LokinetStatus.kt:38-41`), but the suite's own architecture doc places
  Lokinet in "Camp 2 — needs a TUN"
  (`common/docs/OVERLAY_NETWORKS.md:38-50`), i.e. the firewall-VpnService
  multiplex plan — also vetoed. The status module and the architecture doc
  disagree with each other; whichever is right, the tun path is dead.
- **I2P** is the only overlay that is doctrine-compatible: userspace HTTP
  proxy consumed via `ProxyController`, no VPN slot involved
  (`OVERLAY_NETWORKS.md:21-33`). Phase β (NDK-built i2pd,
  `overlay-i2p/BUILD_RECIPE.md`) remains viable rootless — just large, and
  battery-expensive on a phone.

### A13. Downloads
**Status: UNFINISHED (silent dead-end).** No `DownloadListener` is set
anywhere (grep across `browser/src`: zero hits), no storage permissions
(`AndroidManifest.xml:87-94`), README says "no downloads"
(`README.md:21`). View-only is the *right* policy for a hardened viewer per
the audit brief — but today a tap on any download link does literally
nothing: no toast, no explanation. That's a dead interaction, which the
doctrine forbids. One `setDownloadListener` posting a "Downloads are
disabled in this viewer" snackbar makes the policy honest.

### A14. Blocked-navigation feedback
**Status: UNFINISHED (silent dead-end).** `shouldOverrideUrlLoading`
returns `true` (swallow) for every non-https scheme with no user feedback
(`MainActivity.kt:1056-1058`). Tapping a `mailto:`, `tel:`, `intent://` or
`market://` link does nothing at all. Same honesty fix as A13 — and note a
deliberate product question: `mailto:`/`tel:` could legitimately hand off
to the system default apps (complement behavior) instead of being
swallowed.

### A15. Share-target / external link intake
**Status: MISSING — and it is the positioning feature.** The manifest
registers MAIN/LAUNCHER only (`AndroidManifest.xml:179-183`): no
`ACTION_SEND` share target, no `ACTION_VIEW` http/https filter. Other apps
cannot hand a link to this browser at all; the user must copy, switch,
paste. THREAT_SURFACES records this as deliberate
(`common/docs/SUITE_THREAT_SURFACES.md:199-201,232-234` — "a VIEW filter
would turn every app on the device into a URL injector"). That argument is
half-right: an *exported VIEW-filter activity* can indeed be started
programmatically with any URL by any app. But an **ACTION_SEND share
target** only fires from the system share sheet — i.e. from an explicit
user gesture — and a **confirmation interstitial** (show the full URL, JS
state, "Load / Cancel") neutralizes the drive-by-load concern for both
intake paths. Without one of them, the "open suspicious links here" app
cannot receive a suspicious link. See D1.

### A16. Tabs / multi-page
**Status: absent, honestly deferred** (`MainActivity.kt:112-117` defers
tabs, Cromite fork, fingerprint randomization, DoH to phase 2; no dead UI
pretends otherwise). Acceptable for a special-purpose viewer; a v1 ship
does not need tabs if positioning is "one link at a time, inspected".

### A17. Cromite fork / system-WebView ambition (SUITE_DESIGN #8)
**Status: paper only** (`common/docs/SUITE_DESIGN.md:482-545` — tabs,
DoH, notification-per-site, downloads via READ_MEDIA, POST_NOTIFICATIONS).
None of it is in code; the shipped manifest strips the very permissions the
design sheet lists (`AndroidManifest.xml:29-33` vs `SUITE_DESIGN.md:814`).
No doctrine violation — just note that SUITE_DESIGN #8 describes a
different, replacement-grade app; the doctrine reorients v1 to the
complement viewer that the code actually is.

---

## B. EXCLUSIVE-SLOT & COEXISTENCE

**Scarce slots touched: none.** Verified against the full manifest:
- **VPN slot**: not touched. No VpnService, no `BIND_VPN_SERVICE`. The I2P
  path deliberately rides `ProxyController` *inside the app's own WebView*
  (`ProxyScreen.kt:334-358`) — the textbook coexistence design; Tailscale
  is completely unaffected. The only VPN exposure is the *future* Yggdrasil/
  Lokinet plan (A12) — vetoed before it was built. 
- **Default-browser role**: structurally impossible to claim —
  `ROLE_BROWSER` requires an http/https `ACTION_VIEW` handler, which the app
  doesn't register (`AndroidManifest.xml:179-183`). Chrome/Brave stay
  default by construction. (If D1 adds a VIEW filter, the app appears in
  "Open with" choosers but still cannot *take* the default unless the user
  assigns it — acceptable; do NOT prompt for the role.)
- **Autofill / IME / accessibility / notification-listener / device-admin /
  usage-stats**: none requested; `BIND_ACCESSIBILITY_SERVICE`,
  `BIND_DEVICE_ADMIN`, `BIND_NOTIFICATION_LISTENER_SERVICE` are explicitly
  stripped (`AndroidManifest.xml:103-106`). WebView password saving is off
  and credential fill defers to the *system* autofill service
  (`MainActivity.kt:990-992,102-104`) — meaning Bitwarden/1Password (or
  passgen if the user chose it) fill into this browser exactly as they do
  into Chrome. Genuine complement behavior, already implemented.
- **Notifications**: `POST_NOTIFICATIONS` stripped (`AndroidManifest.xml:96`)
  — but the I2P FGS posts a notification channel
  (`I2pProxyService.kt:121-152`); on API 33+ with the permission neither
  requested nor grantable, the FGS notification is silently suppressed
  though the service still runs. Cosmetic today (service self-stops in
  seconds), real in phase β (user cannot see the "I2P running" ongoing
  notification). Flagged for the phase-β design.

**Incumbents and complement opportunities:**
- *Chrome/Brave (daily driver)*: complement = intake (share target from
  Chrome's share sheet → hardened viewer), and hand-off ("Open in default
  browser" button once the user decides a page is trustworthy —
  `Intent(ACTION_VIEW)` out is safe and needs no filter of our own).
  Neither direction exists today (A15, D1, D6).
- *Tailscale*: no interaction, no conflict. I2P-over-ProxyController and
  Tailscale-VPN compose (WebView proxy is above the tun).
- *Bitwarden/1Password autofill*: already composes (above).
- *Samsung Secure Folder / Play Protect*: no interaction. FLAG_SECURE
  (`MainActivity.kt:166-171`) means even screenshots are blocked — worth an
  explicit doc note since users *expect* to screenshot a suspicious page to
  share it; the diagnostics-era `ALLOW_SCREENSHOTS` flag is now off.
- *Suite siblings*: peer discovery + footer working (A8); firewall's overlay
  cards read the same status singletons (`ProxyScreen.kt:210-216`) — fine,
  read-only.

---

## C. GUI AUDIT

**Global:** Compose + `MaterialTheme(darkColorScheme())`
(`MainActivity.kt:189`) but effectively a bespoke dark theme: nearly every
color is a hardcoded hex literal (`Color(0xFF0A0A0A)`, `0xFF9E9E9E`,
`0xFF1C1C1C` … throughout `MainActivity.kt` / `ProxyScreen.kt`), so
Material3 theming/dynamic color is cosmetic. Dark-only (no light theme;
XML theme is `android:Theme.Material.NoActionBar` with black system bars,
`browser/src/main/res/values/themes.xml:3-7`) — consistent with the suite
but not user-selectable. Portrait-locked and non-resizable
(`AndroidManifest.xml:175-176`) — no landscape video, no split-screen;
questionable for a *browser* even a special-purpose one.
**Strings:** `strings.xml` contains exactly one string (`app_name`,
`res/values/strings.xml:3`); every user-facing string in the app is
hardcoded Kotlin (all of `MainActivity.kt`, `ProxyScreen.kt`) — no i18n
path, and `resourceConfigurations = ["en"]` (`browser/build.gradle.kts:17`)
locks it in.
**A11y:** systematically weak. `NavIconButton` is a 36dp-high `Box` with
text glyphs (`MainActivity.kt:817-840`) — below the 48dp touch-target
minimum, no semantic role, TalkBack reads "‹" / "⟳" literally. Clear-URL
"✕" and bookmark-delete "✕" are clickable `Text` without
contentDescription (`MainActivity.kt:408-421,935-942`). Find counter and
title strip are 11sp. No `Modifier.semantics` anywhere in the module.

- **Main screen** (`MainActivity.kt:385-814`): coherent layout — URL row,
  nav row, find bar, toggle rows, WebView, footer. Good: empty-state
  placeholder explains the security posture in plain language
  (`MainActivity.kt:736-753`); loading indicated (static 2dp bar,
  `MainActivity.kt:718-725` — indeterminate, no progress); toggles read
  their state in the label ("JS: off (default)", "Cookies: 1st-party").
  Bad: no custom error state (stock WebView error page — see A2); find
  glyph state bug (A7); "Clear session" dishonest (A5); silent dead-ends
  (A13/A14); the WebView-only-after-first-load workaround is documented
  and sound (`MainActivity.kt:727-735`).
- **Bookmarks overlay** (`MainActivity.kt:842-944`): proper empty state
  with instruction ("Tap ☆ …", `MainActivity.kt:867-878`), keyed
  LazyColumn, ellipsized rows. Plain `clickable` rows — correct per
  SAMSUNG_QUIRKS (non-destructive). Delete "✕" has no confirm; acceptable
  (bookmark, recoverable by re-star) but inconsistent with suite norms.
- **Proxy screen** (`ProxyScreen.kt:68-258`): status banner color-coding is
  good (amber/green/red, `ProxyScreen.kt:261-281`); scaffold cards honestly
  labelled. Defects: stuck-on/enabled-at-Idle switch (A10); write-only
  provider picker (A11); **`secureClickable` on provider rows
  (`ProxyScreen.kt:296`) violates the suite's own SAMSUNG_QUIRKS rule** —
  secure wrappers are reserved for destructive/secret paths precisely
  because Samsung Edge Panel makes them silently drop taps
  (`common/docs/SAMSUNG_QUIRKS.md:38-45`); a provider pick is neither.
  Banner leaks a repo path (A10). Switch inside a non-clickable Row is
  fine (no double-fire).
- **Diagnostics screen** (shared, `common/.../DiagnosticsScreen.kt`):
  functional, self-refreshing, copy/clear; same hardcoded-hex styling as
  the rest; rendered over an opaque surface so the page doesn't bleed
  through (`MainActivity.kt:294-300`). Consistent with the suite.
- **SuiteStatusFooter**: present on the main screen as required
  (`MainActivity.kt:813`), renders tier/peers/caps (A8).

---

## D. SHIP-GAP LIST (ranked)

| # | Size | Tag | Item |
|---|------|-----|------|
| D1 | **M** | **REDESIGN** | **Link intake — the positioning feature.** Add (a) an `ACTION_SEND` (`text/plain`) share-target activity and (b) an *optional, non-default* `ACTION_VIEW` http/https filter, both funneling into a confirmation interstitial that shows the full normalized URL + "JS will be OFF" + Load/Cancel (this interstitial is what answers the THREAT_SURFACES URL-injection objection, `SUITE_THREAT_SURFACES.md:232-234`). Without D1 the app has no reason to exist next to Chrome. |
| D2 | **S** | **FIX** | "Clear session" must do what it says: `removeAllCookies` + `WebStorage.deleteAllData` + `clearHistory/clearCache` + destroy the detached WebView (`MainActivity.kt:656-669`), or be relabelled "Close page". Also fix the stale `noHistory/singleInstance` comment (`MainActivity.kt:217-218`) and soften the placeholder's "wiped on exit" wording to match destroy-time reality. |
| D3 | **S** | **DROP-TO-V2 (partial)** | Proxy screen honesty pass: disable the I2P switch unless state is `Ready`-capable (today: always disabled, with the existing BinaryMissing banner text), never render a checked+disabled switch (`ProxyScreen.kt:129-149`), remove the user-facing repo path. Alternative accepted by doctrine: hide the ProxyScreen entirely behind the Diagnostics-style eng gate until phase β. |
| D4 | **S** | **DROP-TO-V2** | Remove the I2P provider picker from the UI until something consumes the preference (A11); delete the "Custom (advanced)" row outright (references a nonexistent screen, `I2pProvider.kt:88-90`). Keep the catalog data + tests for phase β. |
| D5 | **S** | **FIX** | Honest dead-ends: `setDownloadListener` → "Downloads disabled (view-only browser)" snackbar (A13); blocked-scheme toast in `shouldOverrideUrlLoading` (A14), optionally routing `mailto:`/`tel:` out to system apps as a complement hand-off. |
| D6 | **S** | **FIX** | "Open in default browser" action on the title strip / nav row — one `Intent(ACTION_VIEW, url)` out. Completes the complement loop (inspect here → trust it → continue in Chrome). |
| D7 | **M** | **FIX** | JS-allowlist management: a simple list screen (host + remove) reachable from the JS toggle long-press or a settings row (A3). |
| D8 | **M** | **FIX** | A11y/Material pass: 48dp touch targets for NavIconButton and "✕" targets, contentDescription/semantics on all glyph controls, move user-facing strings to `strings.xml` (C). |
| D9 | **S** | **FIX** | `normalizeUrl` mixed-case scheme bug (`MainActivity.kt:965-967`) — case-insensitive prefix strip; plus reject non-URL input with an inline message instead of loading `https://<garbage>` (A2). |
| D10 | **M** | **FIX** | Custom error state: override `onReceivedError`/`onReceivedHttpError` for main-frame failures and render the app's own dark error panel with a Retry (A2/C). |
| D11 | **S** | **FIX** | Eng/prod coexistence: provider authority must derive from `${applicationId}` (`AndroidManifest.xml:189`), else eng builds can't install next to prod (A8). Registry lookups keyed to prod package already ignore eng — fine. |
| D12 | **L** | **REDESIGN** | Overlay phase β, doctrine-compliant subset: I2P only (userspace proxy via ProxyController — viable rootless, no VPN slot). **Yggdrasil as designed is vetoed** (VpnService, `YggdrasilStatus.kt:16-21`); Lokinet's tun path likewise (`OVERLAY_NETWORKS.md:44-50`) — either redesign Lokinet as pure-SOCKS *if upstream supports it* or delete both modules from the browser build (they currently cost two scaffold cards + three status singletons). Move the ProxyApplier effect out of screen composition (`ProxyScreen.kt:160-165`) before any binary lands; solve the POST_NOTIFICATIONS-stripped FGS notification question (B). |
| D13 | **S** | **FIX** | Replace `secureClickable` with plain `clickable` on provider rows (`ProxyScreen.kt:296`) per SAMSUNG_QUIRKS — silent tap-drops under Edge Panel on the operator's own Samsung. |
| D14 | **S** | **FIX** | Tamper/attestation hard-fail: show a one-line "integrity check failed" screen (or toast-then-exit) instead of a silent vanish (`MainActivity.kt:157-164`). Suite-wide; track once. |

Count: **S=9, M=4, L=1.**

---

## E. COMPLEMENT POSITIONING

This app should be **the quarantine viewer that lives one share-sheet tap
away from Chrome/Brave** — the place a user opens a link they *don't*
trust: from SMS-phish, a strange email, a QR code, a chat from a stranger.
Chrome keeps the default-browser role, the logins, the tabs, the sync;
understory-browser offers what Chrome structurally can't: JS dead by
default, no cookies, no storage, no downloads, no popups, no permission
prompts to fat-finger, ephemeral by design, and an APK whose permission
list makes escalation impossible at the platform layer. The code for that
viewer is already ~90% present and genuinely well-hardened; what's missing
is the doorway (share-target + confirmation interstitial, D1) and the exit
(hand the URL back to the default browser once trusted, D6). Positioned
that way, every doctrine box ticks by construction — it claims no slot, it
cannot become the default browser, autofill remains the incumbent's, and
Tailscale never notices it exists. The Proxy/overlay ambitions should ride
behind it (I2P only) or move out of v1 entirely; they are the only part of
the app that currently over-claims.
