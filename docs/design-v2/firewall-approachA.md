# Firewall V2 — Approach A: PURE OBSERVE / ADVISE COMPANION

**App:** `com.understory.firewall` · **Store face (per SUITE §4):** "Understory Net Audit"
**Approach:** No `VpnService`. Ever. The app becomes a **network-posture cockpit**
that sits BESIDE Tailscale and the OS. It never captures a packet, never claims to
block. Every "enforcement" action is the *user* acting through an OS deep-link; the
app's job is to **see, explain, and route the user to the right Settings page**.

This doc is the implementable spec. It names exact Android APIs, the files to
add/change/delete, screen-by-screen UI with states, and the disposition
(FIX / REDESIGN / DROP) of every audited feature A1–A16. An implementer builds
from this without re-deriving. **DESIGN ONLY** — no code is modified by this task.

---

## 0. THE ONE-SENTENCE PRODUCT

> The understory firewall is the **network-posture auditor that works alongside
> Tailscale by seeing what Tailscale doesn't police**: which apps hold
> remote-admin power, which apps move traffic and when, whether DNS is actually
> encrypted and pointed where you chose, whether the VPN-slot posture itself is
> healthy — and it puts every fix one OS-deep-link tap away without ever asking
> for the tunnel.

Approach A is the **maximalist commitment** to the observe/advise repositioning:
the `VpnService` engine is not "demoted to a standalone mode" — it is **deleted
from the shipping app entirely** and salvaged to a library the app does not depend
on. The verb "block" disappears from the product. The verb is **"restrict (via
Android)"** and **"revoke (via Settings)"**. Nothing in the UI can lie about
active enforcement because there is no enforcement path to lie about.

---

## 1. HARD ARCHITECTURE DECISIONS (approach-defining)

| Decision | Approach A choice | Consequence |
|---|---|---|
| VpnService | **Removed from app.** Delete `FirewallVpnService`, `DnsRedirector`, `DnsCryptProxyService`, `VpnPacketParser`, `PortBlockDiscovery`, `DropStats` from the module. Salvage the pure-JVM parts to `common/net-forwarder/` (NOT depended on by firewall). | No VPN slot contention with Tailscale, structurally. The manifest `<service android:name=".FirewallVpnService">` block and both FGS declarations are deleted. |
| Foreground service | **None.** No FGS, no persistent notification. The app is a foreground cockpit you open, read, act on, and close. | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` all stripped from the manifest. |
| Data model verb | `FirewallSettings.blocklist` (K_BLOCKLIST) is **repurposed** into a **watchlist** (K_WATCHLIST) — "apps I want to keep an eye on / restrict." It drives advise deep-links, never a drop path. | The word "block" is removed from every string. Migration: on first V2 launch, copy old `blocklist` set → `watchlist` set (one-time, see §7). |
| New capability grants | Add exactly **one** opt-in slot: `PACKAGE_USAGE_STATS` (Usage access) for `NetworkStatsManager`. Everything else the app needs (reads of Settings, package enumeration, ConnectivityManager callbacks) needs no new grant beyond what's held. | No slot is *required*; the one opt-in degrades to a deep-link card. |
| Package visibility | Add `com.tailscale.ipn` (and a short list of major VPN packages) to `<queries>` so the Tailscale-coexistence panel can resolve the package rootlessly. | Purely additive; no policy cost. |

**Net manifest permission set after Approach A:**
`INTERNET` (kept — canary probes A14, §5.7), `ACCESS_NETWORK_STATE` (kept —
ConnectivityManager), `QUERY_ALL_PACKAGES` (kept — audit + watchlist UI),
`WRITE_SECURE_SETTINGS` (kept, ADB-opt — Private DNS applier),
`PACKAGE_USAGE_STATS` (**new**, runtime-opt-in special access — NetworkStats).
Everything else stays stripped. `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE*`
**removed**.

---

## 2. FEATURE-BY-FEATURE DISPOSITION (A1–A16)

Every audited feature, its verdict, and the exact mechanism.

### A1 — VPN arm/disarm + per-app block enforcement → **DROP**
Delete `FirewallVpnService.kt`, the main-screen arm/disarm `Switch`
(`MainActivity.kt:290-394` cluster), `requestVpnEnable`, the consent launcher,
`startForegroundService` call, and the manifest `<service>` at
`AndroidManifest.xml:223-234`. Salvage `FirewallVpnService.kt` +
`VpnPacketParser.kt` + `DnsRedirector.kt` + `DropStats.kt` verbatim into a new,
un-referenced `common/net-forwarder/` module (Gradle module NOT in firewall's
`dependencies`) so the hard-won correctness is preserved as a library for a
possible future standalone product — but the firewall app never links it.
**What replaces the promise:** the main screen's primary control is no longer a
master arm switch; it's the posture cockpit (§4). "Blocking" is replaced by the
watchlist → per-app OS-restriction deep-links (A3/§5.3).

### A2 — VPN-preempted banner + Re-enable → **DROP**
Delete the banner (`MainActivity.kt:399-427`) and `onRevoke`/`vpnPreempted`
plumbing (`FirewallVpnService.kt:100-126`, `FirewallSettings` K_VPN_PREEMPTED,
`isVpnPreempted`/`setVpnPreempted`). Its doctrine-inverted semantics ("Tailscale
took the slot, evict it back") vanish with the VPN. **What replaces the promise:**
the Tailscale-coexistence panel (§5.1) renders "another VPN holds the slot" as a
**green, desired state**, never a fault.

### A3 — App list UI (search / chips / icons / sections) → **FIX + REDESIGN (verb)**
Keep the whole `AppListLoader` substrate (`getInstalledApplications` +
launcher-intent filter, icon cache, empty hints). **Redesign the row semantics:**
each row's trailing control is no longer a "Block" switch feeding a VPN drop list.
It becomes a **watchlist star toggle** ("watch this app") plus a **row tap →
per-app detail sheet** (§5.3) offering OS deep-links (app details, background-data
restriction, unused-app-permissions). The "Blocked"/"Apps"/"System" filter chips
become **"Watched" / "All apps" / "System"**. Stale-rule pills for uninstalled
watched packages stay (they're honest: "watched app no longer installed").

### A4 — Drop counter → **DROP (replaced)**
Delete. There is no tun, no drops. **What replaces the promise:** the main
screen's headline metric becomes a **NetworkStats data-movement summary** ("Apps
moved 2.1 GB today; 3 apps sent data while you weren't using them") when Usage
access is granted, or an honest opt-in card when not (§5.2). Observation, not
interception.

### A5 — Remote-admin audit (scan + AuditScreen + first-run + ack) → **KEEP (crown jewel) + FIX**
`RemoteAdminAudit.scan()` is unchanged in mechanism (DevicePolicyManager
`activeAdmins`, AccessibilityManager enabled list, `enabled_notification_listeners`
Secure string, AppOps `checkOpNoThrow` for usage-stats/overlay/install/all-files).
Three fixes:
1. **`MODE_DEFAULT` fallback** (audit caveat 1): in
   `RemoteAdminAudit.opGranted` (`RemoteAdminAudit.kt:240-259`), when
   `checkOpNoThrow` returns `AppOpsManager.MODE_DEFAULT`, fall through to
   `ctx.packageManager.checkPermission(manifestPermFor(op), pkg) == PERMISSION_GRANTED`
   for `SYSTEM_ALERT_WINDOW` (`android.permission.SYSTEM_ALERT_WINDOW`) and
   usage-stats. Kills OEM false-negatives. Return a tri-state
   (`Granted`/`NotGranted`/`Unknown`) and render `Unknown` honestly rather than
   as "clean" (SUITE CD-4d: no green from unreadable state).
2. **The per-finding "Block" switch** (`MainActivity.kt:1223-1226`) is **deleted**
   and replaced by two honest actions already half-present: **"Revoke"**
   (deep-link via `RiskCapability.revokeAction`, unchanged) and **"Add to
   watchlist"** (writes to the repurposed watchlist, drives A3's advise
   deep-links). No dead promise.
3. **First-run bulk prompt** copy changes from "block every detected app" to
   "review N apps that can control this device" → routes into AuditScreen. No
   bulk-block action exists anymore; the bulk action is "acknowledge all after
   review" (opt-in), reusing `FirewallSettings.auditAcknowledged`.

### A6 — Third-party a11y-service warning banner → **KEEP**
`A11yProbe.check` (`common-security/A11yProbe.kt:21-42`) unchanged; banner stays
on the main cockpit. Rootless, doctrine-clean.

### A7 — DNS preferences (provider select + DoT apply + live read-back) → **KEEP (flagship) + FIX**
`PrivateDnsApplier` (`apply`/`clear`/`current`/`hasGrant`) unchanged — it
**configures the platform's own Private DNS**, the model complement mechanism.
Keep the ADB-grant path, the deep-link fallback (`ACTION_PRIVATE_DNS_SETTINGS`
where available else `ACTION_WIRELESS_SETTINGS`), copy-hostname, copy-ADB-command,
and the live "Active now" read-back. Fixes:
- **NextDNS placeholder** (`DnsProvider.kt:109-119`): the literal `<your-config>`
  hostname is appliable garbage. Add a **config-ID text field** in the DnsPrefs
  provider row (§5.4); the applied specifier is
  `"${configId}.dns.nextdns.io"`; disable Apply while the field is blank/invalid
  (`[a-f0-9]{6,}` shape check). If not implemented, **drop the NextDNS entry** —
  no half-appliable row.
- **Tailscale-interaction advisory line** (one line, honest): "Private DNS rides
  alongside Tailscale; if you use a Tailscale exit node, that node's DNS may take
  precedence — verify with the leak probe below."

### A8 — DNSCrypt providers + bundled dnscrypt-proxy service → **DROP**
Delete `DnsCryptProxyService.kt`, the manifest `<service .DnsCryptProxyService>`
(`AndroidManifest.xml:243-250`), the three `DNSCRYPT`-scheme entries from
`DnsProvider.ALL`, and `tools/fetch-dnscrypt-proxy.sh`. Rationale (audit A8):
binary absent from repo builds; even running, no app DNS can reach a local
resolver without the tun; the routing plan was VPN-slot work. The **DoT provider
list stays** (that's A7, which works). **What replaces the promise:** honest DoT
via Private DNS is the only encrypted-DNS mechanism the app claims, and it's real.

### A9 — DNS-redirect preview (DnsRedirector + VpnPacketParser) → **DROP (salvage as library)**
Tun-dependent. Remove from app. Salvage `VpnPacketParser.kt` (pure JVM,
bounds-checked, RFC-768 correct) and `DnsRedirector.kt` into `common/net-forwarder/`
(§A1). Not referenced by the firewall app.

### A10 — Misleading main-screen status in DNS-redirect mode → **MOOT (dissolved)**
The DNS-redirect mode and the "N app(s) blocked" subtitle/notification both cease
to exist (no VPN, no FGS notification). The class of defect (status overstating
enforcement) is structurally impossible in Approach A because the app claims no
enforcement. Nothing to fix; note it as resolved-by-removal.

### A11 — Custom port blocking (PortBlocksScreen + /proc/net discovery) → **DROP**
Delete `PortBlocksScreen.kt`, `PortBlockDiscovery.kt`, the `FirewallRoute.PortBlocks`
route + entry, and `FirewallSettings` K_BLOCKED_PORTS + `getBlockedPorts`/
`setBlockedPorts`. `/proc/net` discovery is a structural no-op on 100% of
minSdk-33 devices (audit A11); the drop path was the tun. **What replaces the
promise:** nothing claims per-port blocking. If the user's concern is "which app
talks to which remote," the honest partial answer is NetworkStats per-app *volume*
(§5.2) — named as volume, not connections.

### A12 — Overlay routing (I2P / Lokinet / Yggdrasil) → **DROP**
Delete `OverlayRoutingScreen.kt`, the `FirewallRoute.OverlayRouting` route + entry,
`FirewallSettings` K_OVERLAY_ROUTING / K_OVERLAY_NETWORK + accessors. Binaries not
bundled; Yggdrasil is itself a VpnService transport (vetoed); the I2P "live status"
is cross-process-impossible (the browser owns that daemon in a different APK). No
`common/overlay-*` module is referenced by the firewall app after this.

### A13 — Network posture screen → **FIX (copy)**
Keep the screen; rewrite the copy to the coexistence story:
- **Delete** the "One-VPN-slot exclusivity → turn the firewall off to run another
  tunnel" section (`MainActivity.kt:1381-1388`).
- **Add** a "Works alongside Tailscale" section that states the app never takes
  the VPN slot and explains what it observes instead.
- **Keep** "Blind by default" / "No telemetry" / "No cloud" (still true, and the
  differentiator).
- Rewrite the manifest header comment (`AndroidManifest.xml:36-49`) which still
  says the app "owns the VpnService slot."

### A14 — Suite integration (caps / footer / tamper / attestation) → **KEEP + FIX**
`SuiteCapsProvider`, `SuiteStatusFooter`, boot `Tamper.check` +
`SuiteAttestation.verify` gate all stay. Two fixes:
1. **Capability rename** (SUITE §4/§5-#10): this app advertises
   `SuiteCapability.NETWORK_FILTER` — it filters nothing now. Rename to
   **`NETWORK_AUDITOR`** in `common-security/SuiteCapability.kt` (coordinated
   suite bump; this doc flags it, the shared-module owner lands it). Until the
   rename lands, the firewall must **not** advertise `NETWORK_FILTER` (drop it
   from its registered set) to satisfy CD-4b (no capability overclaim).
2. **Tamper hard-fail UX** (audit A14 note): the silent `finishAndRemoveTask`
   gets a one-line explanation screen ("A sibling understory app failed
   attestation; closing for safety") before exit — SUITE CD-4c (failure honesty).
   This is a shared-component change (`common-security`), flagged here.
3. **`${applicationId}.suitecaps` authority** (SUITE §1-#2): the provider
   authority is hardcoded prod (`AndroidManifest.xml:259`); change to
   `${applicationId}.suitecaps` in the manifest + the caps-registry lookup so
   eng/prod flavors don't collide at install. Flagged; small.

### A15 — Screen-capture / tap-jacking hardening → **KEEP**
`FLAG_SECURE`, `setHideOverlayWindows`, recents-screenshot off,
`secureClickable`/passive-Switch obscured-touch pattern — all unchanged. Approach
A keeps the full posture. (The passive-Switch pattern now applies to the watchlist
star + acknowledge toggles.)

### A16 — Diagnostics screen → **KEEP**
Shared `DiagnosticsScreen` unchanged; still reachable from the cockpit's tools row.

---

## 3. NEW / CHANGED / DELETED FILES

### Delete from `firewall` module
- `FirewallVpnService.kt`  → salvage-move to `common/net-forwarder/`
- `VpnPacketParser.kt`     → salvage-move to `common/net-forwarder/`
- `DnsRedirector.kt`       → salvage-move to `common/net-forwarder/`
- `DropStats.kt`           → salvage-move to `common/net-forwarder/`
- `DnsCryptProxyService.kt` → delete outright
- `PortBlockDiscovery.kt`  → delete outright (keep its limitation notes as a
  comment block in `docs/NET-AUDIT.md`)
- `PortBlocksScreen.kt`    → delete
- `OverlayRoutingScreen.kt` → delete
- `tools/fetch-dnscrypt-proxy.sh` → delete

### Add to `firewall` module
- **`ConnectivityMonitor.kt`** — wraps `ConnectivityManager` default-network +
  capabilities callbacks (§5.1 mechanism). Exposes a cold `Flow<NetworkPosture>`.
- **`NetworkStatsRepo.kt`** — wraps `NetworkStatsManager` per-UID accounting
  behind the `PACKAGE_USAGE_STATS` opt-in (§5.2 mechanism).
- **`TailscaleCoexistence.kt`** — package-presence + always-on/lockdown Secure
  reads + `TRANSPORT_VPN` detection (§5.1 mechanism), all degrade-to-unknown.
- **`WifiPosture.kt`** — *optional, eng-gated first*; WifiManager caveats (§5.6).
- **`AppRestrictLinks.kt`** — builds the per-app OS deep-link Intents (§5.3),
  with `resolveActivity` availability checks so no dead button ships.
- **`CanaryProbes.kt`** — opt-in, explicit-tap leak/resolver/egress probes (§5.7),
  named endpoints, INTERNET-only.
- **New Compose screens** (see §4): `CockpitScreen` (replaces `FirewallScreen`),
  `TailscaleScreen`, `TrafficScreen`, `AppDetailSheet`, `ProbesScreen`.
  `AuditScreen`, `DnsPrefsScreen`, `PostureScreen`, `DiagnosticsScreen` remain
  (fixed per A5/A7/A13).

### Change in `firewall` module
- `MainActivity.kt` — replace `FirewallRoute` enum (drop `OverlayRouting`,
  `PortBlocks`; add `Tailscale`, `Traffic`, `Probes`); rewrite `FirewallScreen` →
  `CockpitScreen`; delete VPN switch + preempted banner; wire new routes.
- `FirewallSettings.kt` — rename K_BLOCKLIST semantics → watchlist; delete
  K_VPN_ENABLED, K_VPN_PREEMPTED, K_DNS is kept, K_OVERLAY_*, K_BLOCKED_PORTS;
  add K_USAGE_OPT_IN_SEEN (so the opt-in card can be dismissed), K_MIGRATED_V2.
- `DnsProvider.kt` — delete 3 DNSCRYPT entries; add NextDNS config-ID field
  support (or drop NextDNS).
- `AndroidManifest.xml` — see §6.
- `RemoteAdminAudit.kt` — MODE_DEFAULT fallback + tri-state (A5).

### Shared-module changes (flagged, landed by common owner)
- `common-security/SuiteCapability.kt` — `NETWORK_FILTER` → `NETWORK_AUDITOR`.
- `common-security` tamper-fail explanation screen (A14).
- New Gradle module `common/net-forwarder/` (salvage sink; not depended on).

---

## 4. SCREEN MAP & NAVIGATION

`FirewallRoute` (revised): `Cockpit, Tailscale, Traffic, DnsPrefs, Audit,
Posture, Probes, Diagnostics`. Hand-rolled saveable-route enum retained (audit C4
found it solid). Every sub-route keeps its `BackHandler`.

```
Cockpit (home)
 ├─ Tailscale coexistence  → TailscaleScreen
 ├─ Traffic (NetworkStats) → TrafficScreen → AppDetailSheet (per app)
 ├─ App audit (remote-admin) → AuditScreen (existing, fixed)
 ├─ DNS                    → DnsPrefsScreen (existing, fixed)
 ├─ Posture (education)    → PostureScreen (existing, copy-fixed)
 ├─ Probes (opt-in canary) → ProbesScreen
 └─ Diagnostics           → DiagnosticsScreen (shared)
```

The audit's bottom "stack of three full-width OutlinedButtons" (audit C, eats 25%
of screen) is replaced by a **compact 2-column tools grid** on the cockpit
(Material3 cards), fixing that layout debt.

### Cockpit (home) — the network-posture cockpit
Top-to-bottom, a scrollable `Column` of **status cards**, each a one-glance
verdict with a chevron into detail:

1. **Tailscale / VPN-slot card.**
   - States: `Holding ✓` (green — "Tailscale holds the VPN slot; always-on ✓,
     lockdown ✗"), `Present, not connected` (neutral), `Not installed` (neutral —
     "no VPN active; consider a tunnel"), `Unknown` (grey — reads unavailable).
   - Never renders "another VPN took our slot" as a fault (A2 dissolved).
2. **Traffic card** (NetworkStats).
   - Granted: "Apps moved X GB today · N apps sent data while idle" + chevron.
   - Not granted: opt-in card "Turn on Usage access to see per-app data" →
     deep-link `ACTION_USAGE_ACCESS_SETTINGS`. **Never a dead chart.**
3. **DNS card.** Live "Active now: hostname mode → dns.google ✓" / "opportunistic"
   / "off (unencrypted)" from `PrivateDnsApplier.current()`. Chevron → DnsPrefs.
4. **App-audit card.** "N apps can control this device" (from
   `RemoteAdminAudit.scan()` count, minus acknowledged). Chevron → Audit.
   `A11yProbe` warning banner renders above this card when non-system a11y
   services exist (A6).
5. **Tools grid** (2-col cards): Posture · Probes · Diagnostics · (Watchlist).
6. `SuiteStatusFooter` (A14) at the bottom.

**Cockpit states:** loading (each card shows a shimmer/`CircularProgressIndicator`
while its data source resolves — all off-main-thread, §8); error (a card whose
source threw shows "couldn't read — tap to retry" honestly, never a fake green);
first-run (audit card expands into the review prompt, A5).

---

## 5. FEATURE MECHANISMS (exact APIs)

### 5.1 Tailscale coexistence panel — `TailscaleScreen` + `TailscaleCoexistence.kt` / `ConnectivityMonitor.kt`

**Which facts are readable by a rootless 3rd-party app, and how:**

| Fact | API | Grant | Readable? | Degrade |
|---|---|---|---|---|
| Tailscale **installed** | `packageManager.getPackageInfo("com.tailscale.ipn", 0)` | needs `<queries>` entry (add it) | **Yes** | catch `NameNotFoundException` → "not installed" |
| Tailscale **version** | same `PackageInfo.versionName` | as above | **Yes** | — |
| **A VPN is up right now** | `ConnectivityManager.getNetworkCapabilities(activeNetwork)` → `hasTransport(TRANSPORT_VPN)` | `ACCESS_NETWORK_STATE` (held) | **Yes** | if null caps → "unknown" |
| **Which app** owns the tunnel | *not directly readable* — `TRANSPORT_VPN` doesn't name the owner. Infer: if TRANSPORT_VPN present AND Tailscale installed AND `always_on_vpn_app == com.tailscale.ipn`, report "Tailscale". Else "a VPN (owner not identifiable)". | — | **Partial (honest)** | say "a VPN is active" without naming if unconfirmable |
| **Always-on VPN app** | `Settings.Secure.getString(cr, "always_on_vpn_app")` | permissionless read | **Usually** (undocumented key; verify on One UI) | null/blank → "unknown" |
| **Always-on lockdown** ("block connections without VPN") | `Settings.Secure.getInt(cr, "always_on_vpn_lockdown", -1)` | permissionless read | **Usually** (undocumented) | `-1` → "unknown" |
| **Metered / validated / captive** | `NetworkCapabilities`: `!hasCapability(NET_CAPABILITY_NOT_METERED)`, `hasCapability(NET_CAPABILITY_VALIDATED)`, `hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL)` | `ACCESS_NETWORK_STATE` | **Yes** | — |

**`ConnectivityMonitor.kt`:** register
`ConnectivityManager.registerDefaultNetworkCallback(callback)` (API 24+; we're
minSdk 33) in a `callbackFlow`; emit a `NetworkPosture(transportVpn, metered,
validated, captivePortal, underlyingTransport)` on `onCapabilitiesChanged` /
`onLost` / `onAvailable`. Unregister in `awaitClose`. This is the live feed for
the cockpit Tailscale card and the Traffic card's connection type.

**Honesty rule:** the two `always_on_vpn_*` Secure keys are **undocumented**. On
first read, if the key round-trips a plausible value, cache "readable=true"; if it
throws or returns null on a device where a VPN is demonstrably up, render
**"lockdown: unknown on this device"** — never a green checkmark from an
unreadable key (SUITE CD-4d).

**`TailscaleScreen` UI:** a single card list — Installed ✓/✗, Version, VPN active
now ✓/✗ (live), Always-on ✓/✗/unknown, Lockdown ✓/✗/unknown, plus a
**"Open VPN settings"** button (`Settings.ACTION_VPN_SETTINGS`) so the user can
enable always-on/lockdown themselves (we advise; the OS enforces). One advisory
line: "Enabling 'Block connections without VPN' in Tailscale's always-on settings
closes the leak window when the tunnel drops."

**States:** loading (reads in flight), all-green (Tailscale holding + always-on +
lockdown), partial, not-installed (neutral CTA), unknown (honest grey rows).

### 5.2 Per-UID traffic accounting — `TrafficScreen` + `NetworkStatsRepo.kt`

**API:** `NetworkStatsManager` (system service `Context.NETWORK_STATS_SERVICE`).
- `querySummary(NetworkCapabilities.TRANSPORT_*/ ConnectivityManager.TYPE_WIFI &
  TYPE_MOBILE, subscriberId=null, startTime, endTime)` → iterate `Bucket`s;
  `bucket.uid`, `bucket.rxBytes`, `bucket.txBytes`.
- Map `uid → packages` via `packageManager.getPackagesForUid(uid)` (shared-UID
  aware; render the shared-UID group honestly).
- "Talked while you weren't using it": cross-reference with
  `UsageStatsManager.queryEvents` foreground/background windows — if an app moved
  bytes in a window where it had no foreground `MOVE_TO_FOREGROUND` event, flag
  "background traffic." (Both `NetworkStatsManager` and `UsageStatsManager` ride
  the same `PACKAGE_USAGE_STATS` grant.)

**Grant:** `PACKAGE_USAGE_STATS` is a **special access** (`AppOpsManager
OPSTR_GET_USAGE_STATS`), not a runtime permission — cannot be `requestPermissions`.
Flow: check
`appOps.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, uid, packageName) ==
MODE_ALLOWED`; if not, show the opt-in card → deep-link
`Settings.ACTION_USAGE_ACCESS_SETTINGS`; re-check on `ON_START` return (reuse the
existing Settings-round-trip re-scan pattern from `MainActivity.kt:993-1004`).

**Graceful degradation (CD-2b):** without the grant, `TrafficScreen` shows the
opt-in card and a short "why" — never a dead/zero chart. The cockpit Traffic card
likewise shows the opt-in, not a fake number.

**`TrafficScreen` UI:** a time-range segmented control (Today / 7d / 30d), a
`LazyColumn` of app rows sorted by total bytes desc: icon, label, rx/tx, a
"background" chip if it moved data while idle, chevron → `AppDetailSheet`. Metered
vs unmetered split shown from the `TYPE_WIFI`/`TYPE_MOBILE` queries. Empty state:
"No traffic recorded in this range." Loading: progress while the query runs
(off-main-thread, §8). This is the honest replacement for A4's drop counter and
A11's port view: **observation of volume, never a claim of interception.**

### 5.3 Per-app OS-restriction advise — `AppDetailSheet` + `AppRestrictLinks.kt`

The watchlist (repurposed blocklist, A3) is an "apps to keep an eye on" set. Row
tap or watchlist entry → `AppDetailSheet` (Material3 `ModalBottomSheet`) offering
**OS-enforced** actions via deep-links, each guarded by `resolveActivity` so a
dead button never ships:

| Advise action | Intent | Notes |
|---|---|---|
| App details / permissions | `ACTION_APPLICATION_DETAILS_SETTINGS`, `data=package:$pkg` | always resolves |
| Restrict background data | `ACTION_APPLICATION_DETAILS_SETTINGS` (Android has no direct per-app data-restriction action on all OEMs) → land on app details; instruct "Mobile data → Allow background data usage: off". On Samsung One UI, also try `ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` if it resolves. | availability-checked; fall back to app-details with a one-line instruction |
| Unused-app auto-revoke / hibernation | `ACTION_APPLICATION_DETAILS_SETTINGS` → "Pause app activity if unused" toggle; instruct there. | — |
| Data-saver global | `ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` / `ACTION_DATA_USAGE_SETTINGS` | global, not per-app; labelled as such |
| Uninstall | `ACTION_DELETE`, `data=package:$pkg` | user confirms in OS dialog |

**Copy discipline:** the sheet header reads "Restrict via Android — understory
opens the setting; Android enforces it." No control in this sheet claims the app
itself restricts anything. This is the honest replacement for the "Block" verb.

### 5.4 Private DNS — `DnsPrefsScreen` (existing, fixed per A7)

Unchanged mechanism (`PrivateDnsApplier`). Adds: NextDNS config-ID field (or drop
NextDNS), the Tailscale-interaction advisory line, and steps-not-wall-of-text
for the ADB/deep-link instructions (audit C). "Active now" read-back stays first.

### 5.5 Remote-admin audit — `AuditScreen` (existing, fixed per A5)

Unchanged screen structure (best screen in the app). Fixes: MODE_DEFAULT
tri-state; "Block" switch → "Revoke" + "Add to watchlist"; first-run copy.

### 5.6 Wi-Fi security posture — `WifiPosture.kt` (**eng-gated first, opt-in if shipped**)

**Caveat-heavy, so it ships behind an eng flag and, if promoted, behind an
explicit opt-in.** Reading SSID/BSSID/security type requires re-adding
`ACCESS_WIFI_STATE` **and** fine-location (`ACCESS_FINE_LOCATION`) — both
deliberately stripped (`AndroidManifest.xml:58,88`). A location prompt in a
firewall app is a posture cost (audit B-7). **Approach A default: DO NOT ship the
SSID/security readout.** Instead ship the **location-free subset** that's already
free via `NetworkCapabilities` on the active network:
- metered/unmetered, validated, captive-portal-present (§5.1) — no new permission.
- "This network is unencrypted/open" is **not** reliably knowable without
  `ACCESS_WIFI_STATE`+location, so **the app does not claim it** by default.
If a future build wants true Wi-Fi security posture, it re-adds the two
permissions behind an opt-in-with-honest-UI card ("Wi-Fi security check needs
Location — grant to see open-network warnings; skip to keep it off"), never a
silent add. Flagged, not shipped in the default V2.

### 5.7 Opt-in canary probes — `ProbesScreen` + `CanaryProbes.kt` (A14 REDESIGN → new)

INTERNET is already held (`AndroidManifest.xml:51`), so probes are permission-
compatible. **Explicit-tap-only**, named endpoints on the button, to preserve the
"no telemetry / no cloud" posture (SUITE CD-4). Three probes:
1. **DNS leak / resolver identity** — resolve a probe hostname the user taps
   ("Check resolver via `resolver.dnscrypt.info`-style TXT"); show which resolver
   answered vs the Private DNS the user configured. Confirms A7's setting is live
   end-to-end.
2. **Egress IP** — GET a named echo endpoint (button text names the host, e.g.
   "Ask `icanhazip.com` what IP the internet sees"); shows whether traffic exits
   via Tailscale exit node or the local ISP.
3. **Encrypted-DNS confirmation** — cross-check the resolver probe against
   `PrivateDnsApplier.current()`; green only if both agree.

Each probe: a button naming its endpoint, a result card, a "no data left the
device except this named request" honesty line. All on `Dispatchers.IO`. No probe
runs automatically. This replaces the drop counter's lost "is my network doing
what I think" reassurance with something honest and user-triggered.

---

## 6. MANIFEST CHANGES (`AndroidManifest.xml`)

- **Delete** `<service .FirewallVpnService>` (223-234) and
  `<service .DnsCryptProxyService>` (243-250).
- **Delete** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
  `POST_NOTIFICATIONS` uses-permission lines (53-55); flip the "don't strip
  POST_NOTIFICATIONS" comment (115-119) accordingly and strip it.
- **Add** `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
  tools:ignore="ProtectedPermissions" />` (special-access, user-granted via
  Usage access; the manifest declaration is the grant target, mirroring the
  WRITE_SECURE_SETTINGS pattern already documented at 20-34).
- **Keep** INTERNET, ACCESS_NETWORK_STATE, QUERY_ALL_PACKAGES, WRITE_SECURE_SETTINGS.
- **Add** to `<queries>` (147-174): `<package android:name="com.tailscale.ipn" />`
  and optionally major VPN packages (`com.wireguard.android`,
  `org.mullvad.mullvadvpn`, `net.openvpn.openvpn`,
  `ch.protonvpn.android`) for a future "which VPN holds the slot" refinement —
  Tailscale is the load-bearing one.
- **Rewrite** the header comment block (36-49) from "owns the VpnService slot /
  gates outbound traffic" to the observe/advise description.
- **Change** provider authority (259) `com.understory.firewall.suitecaps` →
  `${applicationId}.suitecaps` (A14-3).
- Keep `Theme.Firewall`, FLAG_SECURE posture (A15), portrait lock (reconsider in
  the shared GUI pass, out of scope for Approach A's mechanism).

---

## 7. DATA MIGRATION (`FirewallSettings`, one-time)

On first V2 launch, guarded by new `K_MIGRATED_V2`:
1. Copy old `K_BLOCKLIST` set → new watchlist store (same key reused as
   watchlist; semantics change only). No data lost — the user's curated app list
   survives as "watched."
2. Delete `K_VPN_ENABLED`, `K_VPN_PREEMPTED`, `K_OVERLAY_ROUTING`,
   `K_OVERLAY_NETWORK`, `K_BLOCKED_PORTS` (dead prefs).
3. Keep `K_DNS_PROVIDER` (unless it points at a now-deleted DNSCRYPT id → reset to
   `SYSTEM_DEFAULT`), `K_FIRST_RUN_AUDIT_DONE`, `K_AUDIT_ACKNOWLEDGED`.
4. Set `K_MIGRATED_V2 = true`.
No user-facing migration UI; silent and lossless.

---

## 8. CROSS-CUTTING: THREADING, GUI DEBT, HONESTY

- **Off-main-thread (SUITE §5-#8):** `RemoteAdminAudit.scan()`,
  `NetworkStatsManager` queries, `getInstalledApplications`, icon loads, and all
  probes run on `Dispatchers.IO` behind a `StateFlow`/`produceState`; the UI's
  advertised loading states become renderable. No main-thread IO.
- **Strings → resources (SUITE §5-#9):** all new copy goes in `strings.xml`
  (the app currently has only `app_name`). Approach A adds ~60 new user-facing
  strings; put them in resources from the start, don't hardcode.
- **Theme tokens:** new screens use `MaterialTheme.colorScheme` tokens, not hex
  literals — inherit the shared theme fix when common-security lands it; do not
  add new `Color(0xFF…)` literals.
- **A11y:** watchlist star, acknowledge, and probe buttons get
  `Modifier.semantics`/role+state; 48dp touch targets; the passive-Switch pattern
  keeps its `stateDescription`.
- **Status honesty (CD-4d):** every card that derives a checkmark from an
  unreadable Secure key or an ungranted opt-in renders **"unknown"** or the
  opt-in CTA, never a fabricated green. No surface overstates enforcement because
  the app performs none.

---

## 9. WHAT COMPLEMENT LOOKS LIKE ON THE OPERATOR'S PHONE

- **Tailscale** keeps the one VPN slot, permanently and uncontested — the app
  never asks for it and renders Tailscale-holding as the healthy green state.
- **Bitwarden / Aegis / Chrome / Secure Folder / Play Protect** — untouched; the
  app scans *for* over-privileged apps and advises the user to Android's own
  controls, complementing every incumbent rather than duplicating it.
- The only scarce thing it ever asks for is **Usage access**, opt-in, degrading
  to a deep-link card — and it survives fully useful (audit + DNS + Tailscale
  panel + probes) with nothing granted at all.

**Approach A is the honest floor of the firewall: it blocks nothing, claims
nothing it can't do, takes no slot, and is still genuinely useful the moment you
open it.**
