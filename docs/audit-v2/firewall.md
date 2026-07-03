# Audit v2 — understory-firewall (`com.understory.firewall`)

Audited 2026-07-03 against the NEW SUITE DOCTRINE (complement-don't-replace;
Tailscale `com.tailscale.ipn` permanently holds the one Android VPN slot —
**any feature requiring VpnService is VETOED**). Read-only audit; every claim
carries file:line evidence. Paths are relative to
`C:\repos\understory\understory-firewall\` unless prefixed `common/` (=
`C:\repos\understory\understory-common\`). The vendored shared modules were
diff-verified byte-identical to understory-common.

**Headline:** this app's entire enforcement core is built on VpnService.
Under the veto, the firewall keeps its *audit/advise* half (remote-admin
audit, Private DNS management, posture education) and loses its *enforce*
half (per-app drop, port blocks, DNS-redirect, overlay routing). The good
news: the audit/advise half is the best-built part of the app and is fully
rootless-viable. The redesign is a repositioning, not a rewrite from zero.

---

## A. FEATURE LEDGER

### A1. VPN arm/disarm toggle + per-app blocklist enforcement
**Status: WORKING (as coded) → UNVIABLE-AS-DESIGNED (doctrine veto).**
Complete traced path: Switch → `requestVpnEnable` → `VpnService.prepare` /
consent launcher (`firewall/src/main/java/com/understory/firewall/MainActivity.kt:328-357,380-394`)
→ `startForegroundService` (`MainActivity.kt:1766-1769`) →
`FirewallVpnService.onStartCommand` promotes to FGS with `specialUse`
(`FirewallVpnService.kt:61-93`) → `startVpn()` builds tun with
`addAllowedApplication` per blocked package, v4+v6 default routes
(`FirewallVpnService.kt:224-249`) → reader thread drops every packet and
bumps `DropStats` (`FirewallVpnService.kt:300-315`). Correctness niceties are
real: atomic tun swap on rule edits (`FirewallVpnService.kt:287-321`),
empty-blocklist idle mode (`FirewallVpnService.kt:204-221`), all-uninstalled
guard against the capture-everything trap (`FirewallVpnService.kt:250-261`),
`onRevoke` persistence so the UI never lies about being armed
(`FirewallVpnService.kt:100-126`).
**Why unviable:** arming it evicts Tailscale from the only VPN slot
(`AndroidManifest.xml:209-234` — the manifest itself says the firewall "owns
the VpnService slot"). Direct doctrine violation on the operator's phone.
Permitted only as the clearly-labelled, default-off secondary mode for
users with no Tailscale (see section on salvage, below).

### A2. VPN-preempted banner + one-tap "Re-enable"
**Status: WORKING (as coded) → UNVIABLE-AS-DESIGNED, actively doctrine-hostile.**
`onRevoke` sets `vpnPreempted` (`FirewallVpnService.kt:120-121`), UI banner
says "Another VPN (e.g. Proton) took the tunnel slot" with a Re-enable
button that re-walks consent (`MainActivity.kt:399-427`). Under the doctrine
this banner fires the moment Tailscale connects and *invites the user to
kick Tailscale back out*. Inverted semantics: Tailscale holding the slot is
the desired steady state, not a fault to recover from.

### A3. App list UI (search, All/Blocked/Apps/System chips, icons, stale-rule pills, sectioned blocked/other, empty hints)
**Status: WORKING.** `AppListLoader` via `getInstalledApplications` +
launcher-intent filter (`MainActivity.kt:1792-1813`, QUERY_ALL_PACKAGES at
`AndroidManifest.xml:56`); synthesized stale entries for uninstalled blocked
packages (`MainActivity.kt:605-636,1778-1790`); icon cache
(`MainActivity.kt:654-668`); empty-state hints (`MainActivity.kt:699-723`).
Pure-UI + PackageManager; survives the veto intact as the substrate for any
per-app *advise* feature.

### A4. Drop counter ("dropped N packets · Xs ago")
**Status: WORKING → dies with the VPN slot.** Only feed paths are the tun
reader (`FirewallVpnService.kt:310`) and DnsRedirector drops
(`DnsRedirector.kt:99,106`); rendered `MainActivity.kt:290-301,372-378,1752-1764`.
No tun ⇒ counter is permanently "no packets dropped yet".

### A5. Remote-admin audit (scan + AuditScreen + first-run bulk prompt + acknowledge flow)
**Status: WORKING (rootless, VPN-independent) — the app's crown jewel.**
Scan detects *granted* remote-admin-class capabilities:
device admins via `DevicePolicyManager.activeAdmins`, active a11y services
via `AccessibilityManager`, notification listeners via
`Settings.Secure("enabled_notification_listeners")`, and AppOps
(usage-stats, overlay, install-unknown, all-files) via `checkOpNoThrow`
(`RemoteAdminAudit.kt:134-259`). Per-capability revoke deep-links into the
right Settings page (`RemoteAdminAudit.kt:63-120`,
`MainActivity.kt:1267-1285`). Acknowledge-vs-block separation is correctly
modelled (`FirewallSettings.kt:176-204`, `MainActivity.kt:1058-1069`).
Re-scan on ON_START after Settings round-trips (`MainActivity.kt:993-1004`).
Two caveats: (1) `checkOpNoThrow` returning `MODE_DEFAULT` is treated as
not-granted (`RemoteAdminAudit.kt:258`) — for SYSTEM_ALERT_WINDOW on some
OEM builds the authoritative state is then the permission grant, so false
negatives are possible; add a MODE_DEFAULT→`checkPermission` fallback.
(2) Post-veto, the "Block" switch on each finding card
(`MainActivity.kt:1223-1226`) becomes a no-op promise — it only edits a
list a dead VpnService would consume.

### A6. Third-party a11y-service warning banner
**Status: WORKING.** `A11yProbe.check` counts non-system a11y services
(`common/common-security/src/main/java/com/understory/security/A11yProbe.kt:21-42`),
banner at `MainActivity.kt:430-444`. Rootless, survives veto.

### A7. DNS preferences — provider selection + DoT apply
**Status: WORKING, and doctrine-compatible.**
- Catalog of 9 providers with honest privacy notes (`DnsProvider.kt:51-187`).
- Programmatic apply when `WRITE_SECURE_SETTINGS` is ADB-granted:
  `PrivateDnsApplier.apply/clear` write `private_dns_mode`/`_specifier`
  (`PrivateDnsApplier.kt:89-137`); manifest declares the permission as a
  grant target with correct rationale (`AndroidManifest.xml:20-34`).
- Fallback deep-link + copy-hostname + copy-ADB-command flow
  (`MainActivity.kt:1690-1747`).
- Live "Active now" read-back of the actual system Private DNS state (reads
  need no permission) + dnscrypt liveness (`MainActivity.kt:1504-1563`,
  `PrivateDnsApplier.kt:74-84`). UI copy is honest throughout ("selection is
  informational; applied via system Private DNS only",
  `MainActivity.kt:896-905,1434-1439`).
Note: Private DNS composes with Tailscale (DoT rides inside/alongside the
tunnel); worth one advisory line about exit-node interactions, nothing more.

### A8. DNSCrypt providers + bundled dnscrypt-proxy service
**Status: UNFINISHED (honestly labelled) → post-veto purpose collapses.**
Selecting a DNSCrypt provider starts `DnsCryptProxyService`
(`MainActivity.kt:1488-1492`): supervises the binary with backoff, writes
TOML config, logs stdout to Diagnostics (`DnsCryptProxyService.kt:92-223`).
But (a) the binary is NOT in the repo — it must be fetched by
`tools/fetch-dnscrypt-proxy.sh` into jniLibs or the service self-stops
(`DnsCryptProxyService.kt:110-118`, `AndroidManifest.xml:236-250`); (b) even
running, **no app DNS ever reaches it** — that requires the tun
(`DnsCryptProxyService.kt:34-47`, notification text says so at
`DnsCryptProxyService.kt:246-250`); (c) the phase-2 plan to route DNS to it
is VPN-slot work (`PHASE2.md:30-41`), now vetoed. A local resolver nothing
can query is dead weight in the default mode.

### A9. DNS-redirect preview mode (DnsRedirector + VpnPacketParser)
**Status: UNFINISHED (explicitly unclaimed preview) → UNVIABLE-AS-DESIGNED (veto), and carries a MISLEADING side-effect (A10).**
When a DNSCrypt provider is selected and the VPN (re)starts, the service
silently switches modes: tun routes only a fake resolver IP, DnsRedirector
forwards UDP/53 to the local proxy via a `protect()`ed socket and writes
checksummed responses back (`FirewallVpnService.kt:128-163,337-390`,
`DnsRedirector.kt:76-176`, `VpnPacketParser.kt:67-234`). Code quality is
genuinely good (bounds-checked parse, RFC 768 zero-checksum rule at
`VpnPacketParser.kt:233`, once-per-message error logging). PHASE2.md and the
DnsPrefs copy correctly refuse to claim it as enforcement
(`PHASE2.md:24-28`, `MainActivity.kt:1453-1460`). All of it is tun-dependent.

### A10. Main-screen status during DNS-redirect mode
**Status: MISLEADING.** In DNS-redirect mode app-blocking and port-blocking
are paused (`FirewallVpnService.kt:150-156`), but the main screen still
renders the switch ON with subtitle "`${blocked.size} app(s) blocked`"
(`MainActivity.kt:366-371`) and the FGS notification still says
"$blockedCount app(s) blocked" (`FirewallVpnService.kt:426-435`). A user
with 12 blocked apps who taps a DNSCrypt provider now has ZERO apps blocked
while both surfaces claim 12. The only disclosures are buried in DnsPrefs
(`MainActivity.kt:1453-1460`) and PortBlocks (`PortBlocksScreen.kt:94-106`)
banner prose. Security software must not overstate active enforcement on
its primary status surface.

### A11. Custom port blocking (PortBlocksScreen + PortBlockDiscovery + 10s scanner)
**Status: UNVIABLE-AS-DESIGNED — independently of the VPN veto.**
Mechanism: scan `/proc/net/{tcp,tcp6,udp,udp6}` for UIDs talking to a
blocked remote port, map UID→packages, fold into the VPN drop list
(`PortBlockDiscovery.kt:59-155`, `FirewallVpnService.kt:165-202`). The
file's own header admits: "on Android 10+ ... We only see our OWN
connections ... on stock devices the discovery finds NOTHING"
(`PortBlockDiscovery.kt:44-50`). **minSdk is 33** (`firewall/build.gradle.kts:13`)
— every device that can install this app has the Android-10+ `/proc/net`
restriction, so discovery is structurally a no-op on 100% of supported
devices. The UI banner half-admits it ("matches may be 0 even with traffic
flowing", `PortBlocksScreen.kt:94-106`) while still presenting Add/Remove/
Re-scan as a live feature. Doubly dead post-veto (the drop path was the tun).
Cosmetic: duplicate `465` branch in `wellKnownLabel` — the `465 -> "SMTPS"`
arm is unreachable (`PortBlocksScreen.kt:249,262`).

### A12. Overlay routing screen (I2P / Lokinet / Yggdrasil)
**Status: UNFINISHED (honest banner) + one MISLEADING status row; DROP-TO-V2.**
Selection + enforce toggle are stored-intent only, with an explicit "Phase α
scaffold — does NOT yet route traffic" banner
(`OverlayRoutingScreen.kt:90-102,144-176`, `FirewallSettings.kt:49-75`).
Lokinet/Yggdrasil binaries aren't bundled; their status objects pin at
BinaryMissing (`common/overlay-lokinet/.../LokinetStatus.kt:47`,
`common/overlay-yggdrasil/.../YggdrasilStatus.kt:50`).
**Misleading part:** the screen claims to show "live status ... I2P from
browser's running daemon" (`OverlayRoutingScreen.kt:49-52`), but `I2pStatus`
is an in-process Compose singleton (`common/overlay-i2p/.../I2pStatus.kt:19,50-53`)
and the firewall never runs `I2pProxyService` — the browser is a different
APK/process (`firewall/build.gradle.kts:83-89`: "the browser owns daemon
lifecycle"). There is no cross-process channel, so the I2P row reads
"Idle (browser idle)" forever, regardless of the browser's real daemon
state. Also note Yggdrasil-as-designed is itself a VpnService transport
(`YggdrasilStatus.kt:15-21`) — vetoed. Layout nit: dead
`Spacer(Modifier.fillMaxWidth(0.0f))` (`OverlayRoutingScreen.kt:166`).

### A13. Network posture screen
**Status: WORKING (static informational) — copy now stale under doctrine.**
Six plain-text sections (`MainActivity.kt:1328-1419`). "One-VPN-slot
exclusivity" instructs the user to turn the firewall off to run another
tunnel (`MainActivity.kt:1381-1388`) — must be rewritten to "Tailscale keeps
the slot; firewall observes". "Blind by default" / "No telemetry" / "No
cloud" sections remain true and are a differentiator worth keeping.

### A14. Suite integration (caps provider, footer, tamper, attestation)
**Status: WORKING.** Signature-gated read-only beacon
(`SuiteCapsProvider.kt:10-12`, `AndroidManifest.xml:257-263`, CAPS/CAPS_WRITE
scheme `AndroidManifest.xml:5-18`); `SuiteStatusFooter` on the main screen
(`MainActivity.kt:735`, `common/.../SuiteStatusFooter.kt:44-158`); boot gate
`Tamper.check` + `SuiteAttestation.verify` hard-fail → `finishAndRemoveTask`
(`MainActivity.kt:124-131`, re-check on resume `MainActivity.kt:173-181`).
UX note: hard-fail is a silent instant close — zero explanation for a user
whose sibling app got flagged; acceptable for alpha, hostile for shipping.

### A15. Screen-capture / tap-jacking hardening
**Status: WORKING.** FLAG_SECURE (TestingMode.ALLOW_SCREENSHOTS=false —
`common/.../TestingMode.kt:34`, `MainActivity.kt:133-138`),
`setHideOverlayWindows`, recents-screenshot off (`MainActivity.kt:139-148`),
`secureClickable`/passive-Switch pattern per SAMSUNG_QUIRKS (obscured-touch
filter: `common/.../SecureButton.kt:35-109`; passive Switch rationale
`MainActivity.kt:810-815`; decor-filter lesson `MainActivity.kt:159-170`,
`common/docs/SAMSUNG_QUIRKS.md:28-61`).

### A16. Diagnostics screen
**Status: WORKING.** Shared in-app event log w/ copy/clear, 1s refresh
(`common/.../DiagnosticsScreen.kt:40-60`), reachable from main screen
(`MainActivity.kt:732-734`).

---

## B. EXCLUSIVE-SLOT & COEXISTENCE

**Scarce resources touched:**
| Resource | Use | Verdict |
|---|---|---|
| **Android VPN slot** | `FirewallVpnService`, the app's entire enforcement core (`AndroidManifest.xml:223-234`) | **CONFLICT — vetoed.** Incumbent: Tailscale (`com.tailscale.ipn`), permanent. Arming the firewall evicts Tailscale; Tailscale reconnecting fires our `onRevoke` and the preempted banner then nags the user to evict Tailscale back (A2). Worst-possible coexistence behavior as shipped. |
| FGS `specialUse` ×2 + persistent notifications | VPN + dnscrypt services (`AndroidManifest.xml:223-250`) | Minor; dies/shrinks with the veto. |
| `QUERY_ALL_PACKAGES` | rules UI + audit (`AndroidManifest.xml:56`) | Not a slot, but a Play-policy-scarce grant; justified for the audit feature; F-Droid unaffected. |
| `WRITE_SECURE_SETTINGS` (ADB-granted) | Private DNS writes (`AndroidManifest.xml:33-34`) | No incumbent conflict — but writing `private_dns_*` is a device-global setting; the app already reads back live state before claiming success (good). |
| NOT touched | autofill, IME, a11y service, notification listener, device admin, default-app roles, usage-stats (today) | Clean. Manifest mass-strips everything else (`AndroidManifest.xml:57-145`). |

**Incumbents a real user has for "firewall":** Tailscale (holds slot);
NetGuard/RethinkDNS/TrackerControl (would ALSO fight for the slot — the
whole category has this problem, which is exactly the opportunity);
router/Pi-hole DNS filtering; Private DNS (AdGuard DoT etc.); Samsung
device care / Data Saver for per-app background restriction.

**Complement opportunities (all rootless, none need the slot):**
1. **Tailscale posture surface** — detect installed/running: add
   `com.tailscale.ipn` to `<queries>` (currently absent —
   `AndroidManifest.xml:147-174` lists only suite + tamper packages, so even
   `getPackageInfo` on Tailscale fails under package-visibility filtering);
   detect "a VPN is up" via `NetworkCapabilities.TRANSPORT_VPN` on the
   active network (needs only ACCESS_NETWORK_STATE, already granted
   manifest:52); read `Settings.Secure "always_on_vpn_app"` /
   `"always_on_vpn_lockdown"` (reads are permissionless; keys are
   undocumented — verify on One UI, degrade to "unknown" honestly).
   Render: "Tailscale holds the VPN slot ✓ / always-on ✓ / lockdown ✗ —
   consider enabling Block connections without VPN" + deep-link to VPN
   settings (`ACTION_VPN_SETTINGS`).
2. **Per-UID traffic accounting** — `NetworkStatsManager` with the
   user-opt-in PACKAGE_USAGE_STATS grant (Special app access → Usage
   access): per-app rx/tx over wifi/cell, "talked while you weren't using
   it" reports. This is the honest rootless replacement for "which apps do
   I need to worry about" — observation instead of interception. Graceful
   degradation: without the grant, show the deep-link card, never a dead
   chart.
3. **Per-app advise deep-links** — the existing blocklist UI becomes an
   "apps to restrict" worklist that deep-links to
   `ACTION_APPLICATION_DETAILS_SETTINGS` / data-usage screens for
   background-data restriction (enforced by the OS, not us). Complements
   Samsung's own per-app data controls rather than duplicating them.
4. **Private DNS advise** — already built (A7); it *configures the
   incumbent platform mechanism* instead of replacing it. Keep as the
   flagship.
5. **Remote-admin audit** — no incumbent does this well on-device;
   pure complement (A5).
6. **Opt-in canary probes** (DNS leak / resolver identity / egress IP):
   permission-compatible today — the firewall DOES hold INTERNET
   (`AndroidManifest.xml:51`; only the other five non-browser suite apps
   strip it, see posture text `MainActivity.kt:1373-1380`). Must be
   explicit-tap-only to preserve the "no telemetry/no cloud" posture, with
   the target hosts named on the button.
7. **NOT possible — say so:** advisory NetworkSecurityConfig for OTHER
   apps (an app's NSC binds only to its own APK); toggling airplane mode
   programmatically (write ignored without system broadcast — deep-link
   `ACTION_AIRPLANE_MODE_SETTINGS` only); Wi-Fi SSID/security-type posture
   without re-adding ACCESS_WIFI_STATE + fine-location (both deliberately
   stripped, `AndroidManifest.xml:58,88` — a location prompt in a firewall
   app is a posture cost; if built, gate it behind the same
   opt-in-with-honest-UI rule).

---

## C. GUI AUDIT (screen by screen)

Common to every screen — the big four:
1. **Zero string resources.** `res/values/strings.xml` contains only
   `app_name` (`strings.xml:1-4`); every user-facing string is hardcoded in
   Kotlin (e.g. `MainActivity.kt:365-378,408-419`). Blocks localization and
   copy review.
2. **Not Material3-themed in substance.** M3 components are used, but with
   ~60 hardcoded hex colors (`Color(0xFF1C1C1C)` etc. throughout
   `MainActivity.kt`, `PortBlocksScreen.kt`, `OverlayRoutingScreen.kt`)
   instead of `MaterialTheme.colorScheme` tokens; theme is a bare
   `darkColorScheme()` wrapper (`MainActivity.kt:152`) over a platform
   Material (M1) XML theme (`res/values/themes.xml:3-7`). Dark-only by
   construction; no dynamic color; light theme nonexistent (defensible for
   this suite, but then declare it, don't imply it).
3. **A11y:** essentially no semantics. Icon images are
   `contentDescription = null` (`MainActivity.kt:770` — defensible as
   decorative), but interactive glyphs are raw `Text`: search-clear "✕"
   (`MainActivity.kt:586-598`), chevrons "›" (`MainActivity.kt:906,962-966`);
   row toggles are `secureClickable` Rows with a passive Switch
   (`MainActivity.kt:746-816`) — TalkBack gets no role/state. Touch targets
   on filter chips (`MainActivity.kt:830-849`, vertical 8dp padding) and the
   "✕" are below the 48dp guideline. Portrait is hard-locked
   (`AndroidManifest.xml:200`).
4. **Navigation:** hand-rolled route enum + saveable string — actually
   solid: state survives recreation, every sub-route has a BackHandler
   (`MainActivity.kt:184-246`). Consistent with the rest of the suite.

Per screen:
- **Main (FirewallScreen):** loading state ✓ (`MainActivity.kt:638-640`),
  empty/filter hints ✓ (`MainActivity.kt:699-723`), preempted + a11y + first-run
  banners ✓. Status honesty defect in DNS-redirect mode (A10). Bottom is a
  stack of three full-width OutlinedButtons + footer (`MainActivity.kt:726-735`)
  — eats ~25% of the screen on a phone; should fold into a settings/tools row.
- **AuditScreen:** the best screen. Loading ("Scanning…"), true empty state,
  acknowledged-all state, per-card tri-state coloring, dual actions
  (`MainActivity.kt:1037-1175`). SuiteStatusFooter absent here (main-only) — fine.
- **DnsPrefsScreen:** good structure (Active-now card first, providers,
  actions card), status feedback after apply ✓ (`MainActivity.kt:1686-1689`).
  Wall-of-text instructions (`MainActivity.kt:1446-1463,1691-1704`) would be
  better as steps. NextDNS entry ships a literal `<your-config>` placeholder
  hostname with no editing affordance (`DnsProvider.kt:109-119`) — applying
  it writes a garbage specifier; needs an input field or removal.
- **PortBlocksScreen:** input validation + inline error ✓
  (`PortBlocksScreen.kt:129-145`), empty state ✓ (185-196). **Layout bug:**
  the ports `LazyColumn` has no `weight(1f)` in a non-scrollable Column
  (`PortBlocksScreen.kt:202-224`), so with enough rows it consumes all
  remaining height and pushes the Back button off-screen with no way to
  scroll to it. (Whole screen slated for drop anyway — A11.)
- **OverlayRoutingScreen:** honest phase-α banner ✓ (90-102); dead spacer
  (166); `fillMaxWidth(0.85f)` text + Switch row can collide on narrow
  screens (151-175); misleading I2P status row (A12).
- **PostureScreen:** clean; stale copy (A13).
- **DiagnosticsScreen (shared):** functional; matches suite convention.
- **SuiteStatusFooter:** present on main screen ✓ (`MainActivity.kt:735`),
  consistent with suite widgets.

---

## D. SHIP-GAP LIST (ranked)

| # | Size | Tag | Gap |
|---|------|-----|-----|
| 1 | **L** | **REDESIGN** | Enforcement core is VpnService (A1) — vetoed. Reposition as **observe/advise firewall**: Tailscale-coexistence posture panel (TRANSPORT_VPN check + `<queries>` entry + always-on/lockdown read), NetworkStatsManager per-UID accounting behind opt-in usage-access, per-app restrict deep-links replacing "block" as the primary verb. Keep the VPN-drop engine compiled but demoted to an explicitly-labelled "Standalone mode (no Tailscale)" secondary mode, default-off, gated on detecting that no other VPN is active. |
| 2 | **S** | **FIX** | A10 misleading status: main-screen subtitle + FGS notification claim "N app(s) blocked" while DNS-redirect mode has blocking paused (`MainActivity.kt:366-371`, `FirewallVpnService.kt:426-435`). Make both surfaces mode-aware. (Moot if #1 removes the mode, but fix stands while the code ships.) |
| 3 | **M** | **REDESIGN** | A2 preempted banner semantics inverted under doctrine (`MainActivity.kt:399-427`, `FirewallVpnService.kt:100-126`): when the preemptor is Tailscale, render a green "coexisting" state, never a Re-enable nag. Re-enable remains only inside Standalone mode. |
| 4 | **M** | **DROP-TO-V2** | A11 port blocking: `/proc/net` discovery is structurally a no-op on every minSdk-33 device (`PortBlockDiscovery.kt:44-50`, `build.gradle.kts:13`). Remove the screen and the scanner thread; keep stored prefs for a future userspace-forwarder standalone mode. |
| 5 | **M** | **DROP-TO-V2** | A12 overlay routing screen: stored-intent-only, binaries not bundled, Yggdrasil design is itself VPN-slot, and the I2P "live status" is cross-process-impossible. Remove from UI; keep FirewallSettings keys. |
| 6 | **M** | **DROP-TO-V2** | A8 DNSCrypt: providers whose proxy nothing can query, binary absent from repo builds (`DnsCryptProxyService.kt:110-118`). Remove the three DNSCRYPT entries from `DnsProvider.ALL` (or eng-flavor-gate them); DoT list stays. |
| 7 | **M** | **FIX** | A13 posture copy: rewrite "One-VPN-slot exclusivity" (`MainActivity.kt:1381-1388`) and manifest comment (`AndroidManifest.xml:36-49`) to the coexistence story; add a "works alongside Tailscale" section. |
| 8 | **L** | **FIX** | GUI debt (C): externalize all strings to res; replace hex literals with MaterialTheme tokens; TalkBack semantics (toggleable rows, labelled icons); 48dp touch targets; fix PortBlocks Back-button layout if screen survives; reconsider portrait lock. |
| 9 | **S** | **FIX** | Add `com.tailscale.ipn` (and optionally major VPN packages) to `<queries>` (`AndroidManifest.xml:147-174`) so coexistence detection can resolve the package rootlessly. |
| 10 | **S** | **FIX** | A5 caveat: `MODE_DEFAULT` fallback to `checkPermission` in `opGranted` (`RemoteAdminAudit.kt:250-259`) to kill overlay/usage-stats false negatives on OEM builds. |
| 11 | **S** | **FIX** | NextDNS placeholder hostname is appliable garbage (`DnsProvider.kt:112`): add a config-ID field or drop the entry. |
| 12 | **S** | **FIX** | Post-veto, AuditScreen "Block" switches (A5/`MainActivity.kt:1223-1226`) must either drive the new advise verb (deep-link restrict) or be relabelled "add to watchlist" — no dead promises. |
| 13 | **S** | **FIX** | Cosmetics: unreachable `465` branch (`PortBlocksScreen.kt:262`), dead spacer (`OverlayRoutingScreen.kt:166`), stale "self-stops + flips vpnRequested" comment (`MainActivity.kt:303-307` vs `FirewallVpnService.kt:204-221`). |
| 14 | **M** | **REDESIGN** | Opt-in canary probes (leak test / resolver identity / egress IP) as a new coexistence feature — INTERNET is already held (`AndroidManifest.xml:51`); explicit-tap-only with named endpoints to preserve the no-telemetry posture. |

**Salvage-as-library verdict (special charge 3):**
- `VpnPacketParser.kt` — **salvage.** Pure Kotlin/JVM (only `java.net`
  types), bounds-checked, correct checksums incl. the RFC 768 zero rule
  (`VpnPacketParser.kt:233`); unit-testable today; the core of any future
  standalone-mode forwarder.
- `DnsRedirector.kt` — **salvage into the standalone-mode module** (depends
  on `VpnService.protect`, so it lives with the optional mode, not common).
- `FirewallVpnService.kt` — **salvage as the standalone-mode engine**; its
  hard-won correctness (atomic swap :287-321, all-uninstalled guard
  :250-261, onRevoke persistence :100-126, specialUse-FGS lesson
  `AndroidManifest.xml:215-221` + `SAMSUNG_QUIRKS.md:63-77`) is exactly what
  a slot-optional mode needs.
- `PortBlockDiscovery.kt` — **do not salvage** (structurally dead on
  supported API levels); keep the file's limitation notes as documentation.
- `DropStats.kt` — trivial; follows the engine.

---

## E. COMPLEMENT POSITIONING

The understory firewall should be **the network-posture auditor that works
alongside Tailscale by seeing what Tailscale doesn't police**: Tailscale
owns the tunnel (and the VPN slot, permanently); this app owns the
*questions around* the tunnel — which installed apps hold remote-admin-class
power over the device (its already-working audit, which no incumbent VPN or
Samsung tool offers), which apps move suspicious volumes of traffic and when
(NetworkStatsManager observation behind an honest opt-in), whether the
device's DNS is actually encrypted and pointed where the user chose (its
already-working Private DNS applier + live read-back), and whether the
VPN-slot posture itself is healthy (Tailscale present, always-on, lockdown).
It never asks for the slot on a Tailscale device; its "block" verb becomes
"restrict via Android's own enforced settings, one tap away"; and its
packet-level engine survives as a clearly-labelled, default-off standalone
mode for the minority of users with no VPN at all — the NetGuard use-case —
honestly framed as mutually exclusive with any tunnel.
