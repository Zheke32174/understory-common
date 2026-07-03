# Firewall V2 — Approach C: The Tailscale-Native Egress Control Plane

Design doc. Companion app: `com.understory.firewall` (store face: **Understory
Net Audit**). Written 2026-07-03 against SUITE DOCTRINE (complement-don't-
replace) + the Coexistence Doctrine v1 (SUITE.md §2). **Design only — no code
changed. This doc is implementable as written; an implementer must not need to
re-derive any mechanism.** Paths relative to `C:\repos\understory\` unless
noted. Evidence anchors reference the V2 audit (`docs/audit-v2/firewall.md`,
cited as `firewall.md Ax`) and lines re-verified this session.

---

## 0. THE ONE-SENTENCE PITCH

**"The egress dashboard for a Tailscale user"**: understory Net Audit is the
friendly, offline control surface for the network controls you *already have* —
Tailscale's tunnel posture, Android's per-app data restrictions, the OS's
Private DNS — none of which need the one VPN slot, because Tailscale keeps it.
We **read, explain, and one-tap-route you to** the real OS/Tailscale controls;
we never take the slot, never claim to block a packet we can't, and never nag
you to evict Tailscale.

The app answers four questions a Tailscale user actually has and no incumbent
answers on-device:

1. **Is my tunnel posture healthy?** (Tailscale present, a VPN is actually up,
   always-on + lockdown set) — surfaced + deep-linked, never controlled by us.
2. **Who talked to the network, and how much?** (`NetworkStatsManager` per-UID
   accounting, opt-in) — with a one-tap route to Android's own per-app restrict.
3. **Is my DNS actually encrypted and pointed where I chose?** (Private DNS
   advisor + live read-back + optional leak canary) — the flagship, already built.
4. **Which installed apps hold remote-admin power over this device?** (the
   existing rootless audit — the crown jewel, kept verbatim in mechanism).

The honest frame is **observe + advise + route**, not **enforce**. This is the
firewall.md §E and SUITE.md #1 recommended repositioning, taken to its logical
end: we lean *all the way* into being Tailscale's dashboard rather than keeping
one foot in the vetoed enforcement world.

---

## 1. THE HARD BOUNDARY — WHAT A 3RD-PARTY APP CAN AND CANNOT DO

This section is normative. Every UI claim in this doc traces to a row here.
**Tailscale exposes no public local API** (no bound service, no content
provider, no broadcast we may consume, no readable ACL). Everything we know
about Tailscale we infer from OS-observable facts + package presence. Be precise
and degrade to "unknown" honestly (Coexistence Doctrine CD-4d).

### 1.1 READABLE rootlessly (no scarce slot, permissionless or already-held)

| Fact | API | Permission | Confidence |
|---|---|---|---|
| Tailscale is installed | `PackageManager.getPackageInfo("com.tailscale.ipn")` | needs `<queries>` entry (§3.1); no runtime perm | **exact** |
| Tailscale version / install time | same `PackageInfo` | same | exact |
| **A** VPN transport is up on the active network | `ConnectivityManager` + `NetworkCapabilities.hasTransport(TRANSPORT_VPN)` on active/all networks | `ACCESS_NETWORK_STATE` (held, manifest:52) | exact ("a VPN is up") — **cannot** attribute it to Tailscale by this alone |
| The always-on VPN app package | `Settings.Secure.getString(cr,"always_on_vpn_app")` | none (read) | **best-effort** — undocumented key; may be null/blank on some One UI builds → render "unknown" |
| Always-on lockdown enabled | `Settings.Secure.getInt(cr,"always_on_vpn_lockdown",0)` | none (read) | best-effort — same caveat |
| Which VPN app owns the config (indirect confirm) | cross-check `always_on_vpn_app == "com.tailscale.ipn"` | none | **this is how we attribute the tunnel to Tailscale** when the key is readable |
| Live Private DNS mode + specifier | `Settings.Global.getString(cr,"private_dns_mode"/"private_dns_specifier")` | none (read) — already implemented `PrivateDnsApplier.current()` (verified this session, `PrivateDnsApplier.kt:74-84`) | exact |
| Per-UID rx/tx bytes over wifi/cell, bucketed by time | `NetworkStatsManager.queryDetailsForUid` / `querySummary` | `PACKAGE_USAGE_STATS` (opt-in, Special app access) | exact when granted; **null when not** — no partial |
| Granted remote-admin capabilities of other apps | `DevicePolicyManager.activeAdmins`, `AccessibilityManager`, `Settings.Secure "enabled_notification_listeners"`, `AppOpsManager.checkOpNoThrow` | mixed, all reads/held (`RemoteAdminAudit.kt`, verified `:250-259`) | exact (with the `MODE_DEFAULT` fallback fix, §7.4) |
| Egress IP / resolver identity / DNS-leak signal | outbound HTTPS to a **named** endpoint | `INTERNET` (held, manifest:51) | exact, but **explicit-tap-only** (posture cost, §6) |

### 1.2 WRITABLE / ACTIONABLE (route to OS; we never enforce)

| Action | Mechanism | Notes |
|---|---|---|
| Apply/clear device Private DNS (DoT) | `Settings.Global.putString private_dns_mode/_specifier` | requires ADB-granted `WRITE_SECURE_SETTINGS`; already built (`PrivateDnsApplier.apply/clear`, verified `:89-137`); graceful fallback = deep-link + copy-ADB-command |
| Open VPN settings (Tailscale always-on/lockdown live here) | `Intent(Settings.ACTION_VPN_SETTINGS)` | user toggles always-on/lockdown themselves — we cannot |
| Open Tailscale app (to a screen, best-effort) | `packageManager.getLaunchIntentForPackage("com.tailscale.ipn")`; optionally its exported main activity | no documented deep-link scheme → launch main only, honestly labelled "Open Tailscale" |
| Restrict an app's background/all data | `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, package:...)` → user flips OS's "Background data"/"Allow data usage" | the OS enforces; we route + explain |
| Open Usage-access grant screen | `Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)` | to grant `PACKAGE_USAGE_STATS` |
| Open Private DNS quick screen | `Intent("android.settings.PRIVATE_DNS_SETTINGS")` w/ fallback to `ACTION_WIRELESS_SETTINGS` | fallback path already in code (`MainActivity.kt:1690-1747`) |

### 1.3 NOT POSSIBLE — enumerated honestly (shown in-app, §5.7 "Limits" card)

These are stated plainly on a dedicated **"What this app can't do"** card so the
user is never misled (Coexistence Doctrine CD-4b):

1. **No per-app blocking / packet drop without the VPN slot.** Tailscale holds
   the one slot permanently; taking it would evict Tailscale (firewall.md A1/B).
   We route you to Android's own per-app data restriction instead.
2. **We cannot read Tailscale's internal state**: no ACLs, no exit-node
   selection, no MagicDNS on/off, no peer list, no tailnet name, no
   advertised-routes. Tailscale ships no public local API. We infer only
   "installed + a VPN is up + (maybe) always-on/lockdown".
3. **We cannot *tell* whether the up VPN is Tailscale** unless
   `always_on_vpn_app` is readable and equals `com.tailscale.ipn`. Otherwise we
   say "a VPN is active" + "Tailscale is installed" and stop — no false green.
4. **We cannot enforce DNS for other apps** (an app's NetworkSecurityConfig
   binds only to its own APK) — Private DNS is device-global and is the correct
   lever, which we already drive.
5. **We cannot toggle airplane mode, always-on, lockdown, or exit-node**
   programmatically — those are user actions behind the deep-links above.
6. **NetworkStats is accounting, not interception**: it tells you *who talked
   and how much*, retrospectively — it cannot show packet contents, destination
   hosts, or block anything.
7. **Port-block discovery is dead** on every supported device (`/proc/net`
   restricted on Android 10+; minSdk is 33) — dropped, §4.

---

## 2. WHAT THE APP BECOMES — SCREEN MAP

Six destinations off a **Scaffold + TopAppBar** main screen (fixes the "no
Scaffold" GUI debt, SUITE.md #9). Route model keeps the existing hand-rolled
saveable route enum + per-route BackHandler (audit calls it "actually solid",
firewall.md C.4) — no nav-library churn.

```
Main: EGRESS DASHBOARD  (com.understory.firewall / "Understory Net Audit")
├─ [card] Tunnel Posture      → TunnelPostureScreen      (§5.1)
├─ [card] Traffic by App      → TrafficScreen            (§5.2)  [opt-in gated]
├─ [card] DNS Hardening       → DnsHardeningScreen       (§5.3)  [flagship, kept]
├─ [card] Remote-Admin Audit  → AuditScreen              (§5.4)  [crown jewel, kept]
├─ [row]  Network Posture     → PostureScreen            (§5.5)  [rewritten copy]
├─ [row]  Egress Canaries     → CanaryScreen             (§5.6)  [opt-in, named hosts]
├─ [row]  Limits & Diagnostics→ LimitsScreen + Diagnostics(§5.7)
└─ SuiteStatusFooter (kept, MainActivity.kt:735)
```

The three full-width OutlinedButtons that today eat ~25% of the main screen
(firewall.md C, Main) fold into the **Limits & Diagnostics** overflow row.

**Deleted from the app entirely** (see §4): the arm/disarm VPN switch, the
per-app blocklist-as-enforcement, the preempted/Re-enable banner, PortBlocks,
OverlayRouting, the DNSCrypt local-resolver + DnsRedirector, the drop counter.
Approach C does **not** keep a "standalone VPN mode" — that is the distinguishing
choice of this approach vs. the audit's default suggestion (see §9 Tradeoffs).

---

## 3. FEATURE 1 — TUNNEL POSTURE (the Tailscale-native surface)

New screen `TunnelPostureScreen`. New file
`firewall/src/main/java/com/understory/firewall/TunnelPosture.kt` (pure
read-model) + Compose screen in `MainActivity.kt` (or a new `screens/` file).

### 3.1 Manifest prerequisite (S-fix, firewall.md D9)

Add to the existing `<queries>` block (verified present at
`AndroidManifest.xml:147-174`, currently lists only suite siblings + tamper
packages):

```xml
<!-- Coexistence: detect the tunnel incumbent (read-only, no interaction). -->
<package android:name="com.tailscale.ipn" />
```

Without this, package-visibility filtering makes even `getPackageInfo` on
Tailscale fail (firewall.md B, complement-opportunity 1). No other permission is
added — `ACCESS_NETWORK_STATE` is already held (manifest:52).

### 3.2 Read model — `TunnelPosture.kt`

```kotlin
enum class Tri { YES, NO, UNKNOWN }

data class TunnelPosture(
    val tailscaleInstalled: Boolean,        // getPackageInfo != null
    val tailscaleVersion: String?,          // best-effort
    val aVpnIsUp: Tri,                       // TRANSPORT_VPN on any network
    val alwaysOnApp: String?,                // Settings.Secure, may be null
    val alwaysOnIsTailscale: Tri,            // alwaysOnApp == com.tailscale.ipn
    val lockdown: Tri,                       // always_on_vpn_lockdown==1
)
```

Population (all wrapped in `runCatching`, log to Diagnostics on failure,
degrade to `UNKNOWN`/null — never throw):

- `tailscaleInstalled` / `version`: `pm.getPackageInfo("com.tailscale.ipn", 0)`.
- `aVpnIsUp`: iterate `ConnectivityManager.allNetworks`, if any
  `getNetworkCapabilities(n).hasTransport(TRANSPORT_VPN)` → `YES` else `NO`.
  (Do NOT use only `activeNetwork` — a split-tunnel Tailscale may not be the
  active default; enumerate all.)
- `alwaysOnApp`: `Settings.Secure.getString(cr, "always_on_vpn_app")`; blank/
  null → leave null and set derived Tris to `UNKNOWN`.
- `alwaysOnIsTailscale`: if `alwaysOnApp==null` → `UNKNOWN`; else
  `YES`/`NO` by string equality to `com.tailscale.ipn`.
- `lockdown`: `Settings.Secure.getInt(cr,"always_on_vpn_lockdown",-1)` →
  `1`→YES, `0`→NO, `-1`→UNKNOWN.

**Refresh:** recompute on `ON_START` (reuse the existing Settings-round-trip
re-scan pattern, `MainActivity.kt:993-1004`) and on a manual "Re-check" button.
No background polling (respects the no-telemetry / slow-cadence posture).

### 3.3 UI — states & copy

Card on main + full screen. **Overall verdict chip** derived by this exact
ladder (honest, never green on inference gaps):

| Condition | Chip | Color token |
|---|---|---|
| `!tailscaleInstalled` | "No Tailscale detected" | `secondary` (neutral, not error — a non-Tailscale user is valid) |
| installed && `aVpnIsUp==YES` && `alwaysOnIsTailscale==YES` && `lockdown==YES` | "Tunnel posture: strong" | `primary`/green token |
| installed && `aVpnIsUp==YES` && (`alwaysOnIsTailscale!=YES` OR `lockdown!=YES`) | "Tunnel up — hardening available" | `tertiary`/amber |
| installed && `aVpnIsUp==NO` | "Tailscale installed but no VPN is up" | `tertiary`/amber |
| any Tri `UNKNOWN` blocks a green | never upgrade to strong; show the row as "unknown" with a "?" | — |

Per-fact rows (each a labelled `Text` + `Tri` icon w/ TalkBack `stateDescription`):

- "Tailscale installed  ✓ v1.xx" (or "not found").
- "A VPN tunnel is active  ✓ / ✗ / ?" — subtitle if `alwaysOnIsTailscale!=YES`:
  "We can see a tunnel is up but can't confirm it's Tailscale on this build."
- "Always-on VPN  ✓ / ✗ / unknown".
- "Block connections without VPN (lockdown)  ✓ / ✗ / unknown".

**Actions (route-only, never enforce):**
- **"Open VPN settings"** → `Intent(Settings.ACTION_VPN_SETTINGS)`. Primary CTA
  when lockdown/always-on are NO/UNKNOWN. Copy: "Turn on Always-on and 'Block
  connections without VPN' for Tailscale here."
- **"Open Tailscale"** → launch intent for `com.tailscale.ipn` (disabled with
  reason if not installed). Honestly labelled — no fake deep-link into a
  specific Tailscale screen (§1.3.2).
- **"Re-check"** → recompute.

**Empty/degraded states:**
- Not installed: neutral card, "Understory Net Audit works with or without
  Tailscale. Install Tailscale to secure your tunnel; the DNS, traffic, and
  audit tools below work regardless." + Play/link is NOT auto-launched (no
  outbound nag).
- All settings-keys unknown: show the two facts we DO have (installed +
  aVpnIsUp) and an info line: "Your device build doesn't expose always-on
  status to other apps — check it yourself in VPN settings." + the deep-link.

**The inverted-banner bug is deleted, not fixed:** the old `onRevoke`
"preempted / Re-enable" banner (firewall.md A2, `MainActivity.kt:399-427`) is
removed with the VpnService (§4). There is no code path in Approach C that ever
treats Tailscale holding the slot as a fault — the whole screen is built on the
opposite premise.

---

## 4. DISPOSITION OF THE VETOED / DEAD ENFORCEMENT HALF (DROP)

Approach C **removes** the enforcement stack outright (no standalone-mode
carry). Rationale in §9. Precise disposition of every audited feature:

| Audit ID | Feature | Disposition | What replaces the promise |
|---|---|---|---|
| A1 | VPN arm/disarm + per-app blocklist enforce | **DROP** — delete `FirewallVpnService`, the arm switch, the FGS. | Per-app *restrict* via OS deep-link (§5.2); the app blocks nothing and says so. |
| A2 | Preempted banner + Re-enable | **DROP** — delete. | Tunnel Posture screen renders Tailscale-holds-slot as the *good* state (§3). |
| A3 | App-list UI (search/chips/icons/stale pills) | **KEEP** — repurpose as the substrate for Traffic (§5.2) and per-app restrict worklist. `AppListLoader` stays (`MainActivity.kt:1792-1813`). | — (kept) |
| A4 | Drop counter | **DROP** — no tun, permanently zero. | Traffic screen shows real rx/tx (§5.2). |
| A5 | Remote-admin audit | **KEEP verbatim mechanism** + §7.4 fix + verb relabel (§5.4). | — (kept, crown jewel) |
| A6 | 3rd-party a11y warning banner | **KEEP** (`A11yProbe`, common). | — |
| A7 | DNS provider select + DoT apply | **KEEP** — flagship (§5.3). | — |
| A8 | DNSCrypt providers + bundled proxy service | **DROP** — remove the 3 DNSCRYPT entries from `DnsProvider.ALL`; delete `DnsCryptProxyService`; drop `tools/fetch-dnscrypt-proxy.sh` dependency. Binary never in repo; proxy nothing can query (firewall.md A8). | DoT provider list stays; leak canary (§5.6) is the new "is my DNS private" proof. |
| A9 | DNS-redirect preview (DnsRedirector + VpnPacketParser) | **DROP from app.** `VpnPacketParser.kt` → **salvage to a JVM test-lib** in common (pure `java.net`, unit-testable, firewall.md salvage) but wired to nothing shippable. `DnsRedirector` deleted. | — (no user-facing promise existed; it was unclaimed preview) |
| A10 | Misleading "N apps blocked" status | **DROP the surface** (the whole VPN status surface is gone) → the honesty bug cannot recur. | Dashboard states are observation-only, never claim enforcement. |
| A11 | Port blocking + `/proc/net` discovery | **DROP** — structurally a no-op on 100% of minSdk-33 devices (`PortBlockDiscovery.kt:44-50`, `build.gradle.kts:13`). Delete screen, scanner thread, `PortBlockDiscovery.kt`. Fix the unreachable `465` cosmetic by deletion. | Traffic accounting (§5.2) answers "who's talking" honestly. |
| A12 | Overlay routing (I2P/Lokinet/Ygg) | **DROP from UI** — stored-intent-only, binaries absent, Ygg is itself VpnService, I2P "live status" cross-process-impossible (firewall.md A12). Delete screen; keep `FirewallSettings` keys inert (no migration cost). | Nothing — honestly removed; it never routed traffic. |
| A13 | Network posture screen | **KEEP, rewrite copy** (§5.5). | — |
| A14 | Suite integration (caps/footer/tamper/attest) | **KEEP**; update the advertised capability (§8). | — |
| A15 | Screen-capture / tap-jack hardening | **KEEP verbatim** (FLAG_SECURE etc.). | — |
| A16 | Diagnostics | **KEEP** (§5.7). | — |

**Files deleted:** `FirewallVpnService.kt`, `DnsRedirector.kt`,
`DnsCryptProxyService.kt`, `PortBlockDiscovery.kt`, `PortBlocksScreen.kt`,
`OverlayRoutingScreen.kt`, `DropStats.kt`, the three overlay `common/overlay-*`
module deps from `firewall/build.gradle.kts`.
**Files moved:** `VpnPacketParser.kt` → `common/common-net-testlib/` (test-only,
not linked into the app).
**Manifest strips:** remove `<service ...VpnService>`, both `specialUse` FGS
declarations (`AndroidManifest.xml:223-250`), and the now-unused
`INTERNET`-for-protect comment (INTERNET is retained — needed for canaries §5.6).
`QUERY_ALL_PACKAGES` retained (audit + app list). `WRITE_SECURE_SETTINGS`
retained (Private DNS applier).

---

## 5. THE KEPT / NEW SCREENS IN DETAIL

### 5.1 Tunnel Posture — §3 (new).

### 5.2 Traffic by App — `TrafficScreen` (new; opt-in)

**Purpose:** the honest, rootless answer to "who talked to the network and how
much" — observation replacing interception (firewall.md B, complement-opp 2).

**Mechanism:** `NetworkStatsManager`. New file
`firewall/.../TrafficAccounting.kt`.
- Grant: `PACKAGE_USAGE_STATS` (Special app access → Usage access). Declared in
  manifest with `tools:ignore="ProtectedPermissions"` + honest rationale
  comment; **never required** — the screen degrades (below).
- Query: for each installed UID (reuse `AppListLoader`), call
  `queryDetailsForUid(NETWORK_TYPE_WIFI/MOBILE, subscriberId=null, startTime,
  endTime, uid)` and sum `rxBytes`/`txBytes`. Time windows: **Today**, **7 days**
  (segmented control). Use `NetworkStatsManager` `querySummary` for the bulk
  pass; fall back to per-UID details for the "background vs total" split.
- **Off the main thread**: all `NetworkStatsManager` calls run on
  `Dispatchers.IO` behind a `StateFlow<TrafficUiState>` state machine
  (Loading/Empty/Data/NeedsGrant/Error). This directly satisfies the suite
  main-thread-IO ban (SUITE.md #8).

**UI states (all four present — no dead chart):**
- **NeedsGrant** (default): explainer card "Grant Usage access to see per-app
  traffic. This stays on your device — we send nothing." + **"Open Usage
  access"** → `Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)`. No chart shell.
- **Loading**: skeleton rows.
- **Data**: sorted-desc list (rows reuse the A3 app-row: icon + label + total +
  a wifi/cell split bar). Each row expands to rx/tx + a **"Restrict this app"**
  action → `Intent(ACTION_APPLICATION_DETAILS_SETTINGS, package:pkg)` with copy
  "Turn off Background data / restrict data usage here — Android enforces it."
  This is the per-app *advise* verb replacing "block" (firewall.md complement-
  opp 3). A **"talked while you weren't using it"** flag can be shown only if
  the background/foreground split is available; otherwise omit the flag (don't
  fake it).
- **Empty**: "No traffic recorded in this window."
- **Error**: honest message + Diagnostics pointer.

**Boundary honesty line** (footer of screen): "This is accounting, not
blocking. It shows totals after the fact and can't see hosts or contents.
Restrictions are applied by Android, not this app."

### 5.3 DNS Hardening — `DnsHardeningScreen` (kept + tightened; flagship)

Keep the existing A7 architecture verbatim in mechanism — it is the
doctrine-model feature (SUITE.md slot matrix: `WRITE_SECURE_SETTINGS` OPT, "the
doctrine-model feature"):
- Active-now card first: live read-back via `PrivateDnsApplier.current()`
  (verified `:74-84`) — no permission to read.
- Provider catalog (DoT only after A8 DNSCrypt removal): the 6 DoT providers
  from `DnsProvider.kt` with honest privacy notes.
- Apply: `PrivateDnsApplier.apply/clear` (verified `:89-137`) when
  `WRITE_SECURE_SETTINGS` is ADB-granted; else the fallback deep-link +
  copy-hostname + copy-ADB-command flow (`MainActivity.kt:1690-1747`).

**Fixes folded in (from firewall.md D11 + C):**
- **NextDNS `<your-config>` placeholder** (`DnsProvider.kt:109-119`,
  appliable garbage): add an inline config-ID `TextField`; the specifier is
  templated `<id>.dns.nextdns.io` and only enabled once non-blank. If not
  filling the field, the Apply button is disabled-with-reason. No garbage write.
- Wall-of-text instructions → numbered steps (M3 list).
- **Tailscale/exit-node interaction advisory** (one line, not more): "Private
  DNS composes with Tailscale — with a Tailscale exit node or MagicDNS enabled,
  Tailscale may override the system resolver; verify with the DNS canary
  (below)." This is the *only* place we mention exit-node/MagicDNS, and we
  mention them as things we **cannot read** but the user can verify empirically
  via §5.6 — no false claim of knowing Tailscale's DNS state.

### 5.4 Remote-Admin Audit — `AuditScreen` (kept; crown jewel)

Mechanism unchanged (`RemoteAdminAudit.kt` scan of device-admins / a11y /
notif-listeners / AppOps; per-capability revoke deep-links; acknowledge-vs-block
model; ON_START re-scan). Two changes only:

1. **Verb relabel (firewall.md D12).** The per-finding "Block" switch
   (`MainActivity.kt:1223-1226`) currently edits a list the dead VpnService would
   consume — a dead promise. Rename to **"Watchlist"** (acknowledge/track) and
   keep the existing per-capability **"Revoke in Settings"** deep-link as the
   real action. No control implies blocking we can't do.
2. **`MODE_DEFAULT` fallback (firewall.md D10, S-fix).** In `RemoteAdminAudit`'s
   op check (verified returns `mode == MODE_ALLOWED` at `:258-259`), add: when
   `checkOpNoThrow` returns `MODE_DEFAULT`, fall back to
   `PackageManager.checkPermission(<the backing permission>, pkg) == GRANTED`
   for overlay (`SYSTEM_ALERT_WINDOW`) and usage-stats, killing OEM false
   negatives.

### 5.5 Network Posture — `PostureScreen` (kept; copy rewritten)

Static informational screen (`MainActivity.kt:1328-1419`). Rewrite the stale
sections (firewall.md D7):
- **DELETE** the "One-VPN-slot exclusivity → turn the firewall off to run
  another tunnel" section (`:1381-1388`) — doctrine-hostile.
- **ADD** "Works alongside Tailscale": "We never take the VPN slot. Tailscale
  keeps it; we read your tunnel's posture and route you to the OS controls."
- **KEEP** "Blind by default" / "No telemetry" / "No cloud" — true and a
  differentiator.
- Update the manifest header comment (`AndroidManifest.xml:36-49`) to match.

### 5.6 Egress Canaries — `CanaryScreen` (new; opt-in, named endpoints)

The empirical "is my DNS/egress actually what I think" proof — the honest
substitute for claiming to read Tailscale's DNS/exit state (firewall.md
complement-opp 6, D14). **INTERNET is already held** (manifest:51; the audit
confirms only the five non-browser suite apps strip it).

**Strict rules (preserve no-telemetry posture):**
- **Explicit-tap-only.** Nothing fires on screen open. Each probe is a button.
- **Named endpoints on the button face** — the user sees exactly which host is
  contacted before tapping. Default set (all privacy-respecting, no-logging or
  first-party):
  - **Egress IP**: `https://api.ipify.org` (or Cloudflare `1.1.1.1/cdn-cgi/trace`).
  - **Resolver identity / DNS leak**: Cloudflare `https://1.1.1.1/cdn-cgi/trace`
    (returns the resolver POP) + a DoH round-trip to the *configured* provider to
    compare — if the answering resolver ≠ the Private DNS specifier, flag a
    possible leak.
  - **DoT reachability**: TLS connect to the configured specifier:853, SNI =
    specifier; success/fail only.
- All calls on `Dispatchers.IO`; short timeouts; results rendered as plain rows
  with the raw response available in Diagnostics.
- **Boundary line**: "These probes send one request each to the named host when
  you tap. Nothing else leaves your device. We can't read Tailscale's DNS
  settings directly — this checks what actually happens on the wire."

This is where "MagicDNS / exit-node" get *observed* rather than *claimed*: if
Tailscale's exit node is rerouting DNS, the resolver-identity probe will show a
resolver the user didn't configure, and the app reports the fact honestly
without ever pretending to have read Tailscale's config.

### 5.7 Limits & Diagnostics — `LimitsScreen` + Diagnostics (new card + kept)

- **Limits card** renders §1.3 verbatim (the "What this app can't do" list) —
  the doctrine-mandated honest-capability surface (CD-4b). This is a *feature*,
  not fine print: a Tailscale user wants to know the exact edge of the tool.
- **Diagnostics** kept as-is (shared `DiagnosticsScreen`, `MainActivity.kt:732`).
- The three old full-width OutlinedButtons collapse here.

---

## 6. PERMISSIONS & POSTURE SUMMARY (final manifest intent)

| Permission | State | Why |
|---|---|---|
| `ACCESS_NETWORK_STATE` | held | TRANSPORT_VPN check, network type for stats |
| `QUERY_ALL_PACKAGES` | held | audit + app list + Tailscale visibility (plus §3.1 `<queries>` entry) |
| `INTERNET` | held | canary probes only (explicit-tap) |
| `WRITE_SECURE_SETTINGS` | OPT (ADB) | Private DNS apply; degrades to deep-link |
| `PACKAGE_USAGE_STATS` | OPT (user) | Traffic accounting; degrades to grant card |
| VpnService / FGS `specialUse` ×2 | **REMOVED** | enforcement dropped (§4) |
| autofill / IME / a11y-service / notif-listener / device-admin / overlay | never (mass-stripped) | unchanged |

Every scarce/opt grant degrades gracefully with an honest status line naming
who holds it or how to grant it (Coexistence Doctrine CD-2b, CD-4). Zero dead
controls anywhere (CD-4a): buttons that can't act are disabled-with-reason or
route to a grant screen.

---

## 7. GUI / SHIPPABLE-BAR WORK (applies across all kept screens)

Inherits the suite-wide fixes (SUITE.md #9) — Approach C assumes the
common-security theme-token + shared-component work lands; app-local deltas:

1. **Scaffold + TopAppBar** on main + every sub-screen (currently none).
2. **Strings → `res/values/strings.xml`** — externalize all hardcoded Kotlin
   copy (today `strings.xml` has only `app_name`). New keys grouped by screen.
   Enables the store-rename to "Understory Net Audit" (SUITE.md §4/§5) via
   `app_name` only.
3. **MaterialTheme tokens** replace the ~60 hardcoded hex literals; the tri-state
   verdict colors map to `primary`/`tertiary`/`error`/`secondary` roles (dark
   scheme kept, declared dark-only).
4. **TalkBack semantics**: toggleable rows get `Modifier.toggleable` +
   `role=Switch` + `stateDescription`; the Tri icons get `contentDescription`
   ("enabled/disabled/unknown"); interactive glyph `Text` ("✕"/"›") replaced
   with `IconButton`+`Icon`. 48dp min touch targets on chips and actions.
5. **Keep** the secure-touch hardening (`secureClickable`/passive-Switch,
   FLAG_SECURE, `setHideOverlayWindows`) — A15, verbatim.
6. **Tamper hard-fail**: replace the silent `finishAndRemoveTask`
   (`MainActivity.kt:124-131`) with a one-line explanation screen before exit
   (SUITE.md #6 honesty; audit calls the silent close "hostile for shipping").
7. Reconsider the portrait hard-lock (`AndroidManifest.xml:200`) — allow
   sensor-portrait at minimum; not load-bearing, low priority.

---

## 8. SUITE INTEGRATION

- **Capability beacon**: the app currently advertises `NETWORK_FILTER` (a vetoed
  core — SUITE.md §4.4 overclaim). **Rename to `NETWORK_AUDITOR`** (or
  `EGRESS_ADVISOR`) in `SuiteCapability.kt` + `SuiteCapabilityRegistry.kt`. It
  now truthfully describes read/advise. Coordinate with the suite-wide capability
  rename pass (SUITE.md #10).
- **`${applicationId}.suitecaps` authority** — confirm the app uses the
  templated authority (passgen-style fix) to avoid eng/prod install collision
  (SUITE.md §1.2); if hardcoded prod, fix here.
- SuiteStatusFooter, SuiteCapsProvider (signature-gated read beacon), Tamper +
  SuiteAttestation boot gate — all **kept** (A14).
- Store face: **Understory Net Audit** (kills the "firewall" name overclaim —
  post-veto it blocks nothing; SUITE.md §4.3/§5). `app_name` string change only;
  package id `com.understory.firewall` stays (not user-facing).

---

## 9. TRADEOFFS (why Approach C, and what it costs)

**The distinguishing bet:** unlike the audit's default suggestion (keep the VPN
engine as a demoted "standalone no-Tailscale mode"), Approach C **deletes the
enforcement stack entirely** and commits 100% to being Tailscale's egress
dashboard.

**Wins:**
- **Zero doctrine risk, zero honesty risk.** No dead VPN switch, no inverted
  banner, no "N apps blocked" lie possible — the surfaces that could overclaim
  are gone. The app can only ever tell the truth about what it observes.
- **Smallest, cleanest codebase.** Deleting `FirewallVpnService`,
  `DnsRedirector`, `DnsCryptProxyService`, `PortBlockDiscovery`, two overlay
  screens and their module deps removes the entire fragile packet-plane and its
  Samsung-quirk debt. What remains (audit + Private DNS + stats + posture reads)
  is the best-built, fully-rootless half.
- **Sharp positioning.** "The egress dashboard for a Tailscale user" is a
  one-liner a Tailscale user immediately gets; it complements rather than
  competes, and it fills a real gap (no incumbent shows tunnel-posture +
  per-app egress + DNS-truth + remote-admin audit together on-device).
- **Every claim is OS-grounded** (§1 boundary table) — nothing depends on a
  Tailscale API that doesn't exist.

**Costs / what we give up:**
- **The NetGuard use-case is abandoned.** A user with *no* VPN at all loses the
  packet-drop firewall entirely — Approach C offers them only OS-level per-app
  restriction + Private DNS, not true blocking. (Mitigation: honest Limits card
  tells them so and points at Android's own controls / a dedicated no-VPN
  firewall. The suite's premise is a Tailscale user, so this is an acceptable
  narrowing.)
- **Tailscale-attribution is best-effort.** When `always_on_vpn_app` is
  unreadable on a One UI build, the tunnel-posture verdict can't go fully green —
  we degrade to "a VPN is up + Tailscale installed" and route the user to verify.
  This is a honesty win but reads as slightly less "magic" than an app that
  falsely claimed to know.
- **NetworkStats granularity is coarse** (per-UID totals, no hosts) — it answers
  "how much" well and "to whom" not at all. The canary probes partially cover
  "is my egress/DNS what I expect," but there is no per-destination view. Stated
  plainly on-screen.
- **`VpnPacketParser` salvage is nearly orphaned** — kept as a JVM test-lib for
  provenance, but Approach C has no shippable consumer for it (a cost only in the
  sense of "we built good code we're not shipping").

**Net:** Approach C trades breadth (loses the no-VPN blocking minority) for
total doctrine + honesty compliance, a much smaller/robuster codebase, and the
sharpest complement story of the three approaches. For a suite whose reference
user runs Tailscale, that is the right trade.
