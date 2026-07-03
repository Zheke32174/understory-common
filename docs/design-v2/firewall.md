# Design v2 — understory-firewall ("Understory Net Audit")

Status: DESIGN (implementable). Resolves every finding in
`docs/audit-v2/firewall.md` (A1–A16, D1–D14) and the firewall-relevant rows of
`SUITE.md` / `design-v2/suite-coexistence.md`. Design-only: no code is modified
by this document; no gradle/build is run. File:line references are to the
audited (v1) tree under `C:\repos\understory\understory-firewall\` (`common/` =
`C:\repos\understory\understory-common\`).

**Chosen base: Approach B (dual-mode companion + opt-in Standalone engine),
grafted with Approach C's positioning, Limits card, and screen taxonomy, and
Approach A's feature-disposition discipline.** Three lines on why (see §12 for
the full scoring):

1. The **suite-level design binds the outcome**: `suite-coexistence.md` §CD-2(a)
   (lines 174-177) explicitly sanctions "packet-level engines … as an
   explicitly-labelled, default-off 'Standalone (no Tailscale)' mode," and §3.6
   (line 301) states the requirement as "VPN engine → default-off Standalone
   mode." Approach C **deletes** that engine; only B **keeps it under the exact
   guardrail the suite mandates**, so B is the only approach that conforms.
2. B has the **strongest-specified guardrail** (fail-closed CM `TRANSPORT_VPN`
   veto ANDed with `VpnService.prepare()`, plus a live slot-watcher) — the load-
   bearing safety mechanism that makes keeping the engine doctrine-safe.
3. C is nonetheless the **better product for the reference (Tailscale) user**, so
   we graft its "egress dashboard" one-liner, its explicit **Limits & Can't-Do
   card** (a doctrine feature, not fine print), its clean six-destination screen
   map, and its named-endpoint canary rules onto B's dual-mode spine.

Companion inputs assumed present but NOT specified here (suite-wide, owned by the
common-security consolidation — this doc *consumes* them, does not define them):

- shared M3 theme tokens + `UnderstoryScaffold` / `UnderstoryTopAppBar` in
  `common-security` (replaces the ~60 hardcoded hex literals; referenced here as
  `colorScheme.*` roles). — `design-v2/shared-gui.md`.
- the suite-wide **tamper hard-fail explanation screen** (wired here, §10.6).
- the suite `${applicationId}.suitecaps` authority fix (firewall-side manifest
  edit specified §11).
- the `SuiteCapability` taxonomy rename **`NETWORK_FILTER` → `NET_POSTURE_AUDIT`**
  (owned by `suite-coexistence.md` §1; this app conforms, §10.5).

---

## 0. WHAT FIREWALL V2 IS — ONE LINE

> **Understory Net Audit is the offline egress dashboard for a Tailscale user:**
> it reads, explains, and one-tap-routes you to the network controls you already
> have — Tailscale's tunnel posture, Android's per-app data restrictions, the
> OS's Private DNS, and which apps hold remote-admin power over your device —
> **never taking the VPN slot** on a Tailscale phone; and for the minority who
> run *no* VPN at all, it can additionally become a real per-app packet blocker
> in an explicit, default-off **Standalone mode** that refuses to start whenever
> any other VPN is present.

The honest frame is **observe + advise + route** (Companion, the default and the
only mode the operator's phone ever sees), with **enforce** available only in the
walled-off Standalone mode. The verb "block" appears in the UI in exactly one
place — inside Standalone — and nowhere else.

---

## 1. THE TWO MODES (single source of truth)

Add a persisted mode to `FirewallSettings`:

```kotlin
enum class FirewallMode { COMPANION, STANDALONE }   // default COMPANION; key K_MODE
```

- **COMPANION (default, permanent on any device with a VPN):** observe/advise
  only. No `VpnService` is ever prepared, started, or *active*. All value comes
  from rootless reads + OS deep-links + the ADB-granted Private DNS applier.
- **STANDALONE (opt-in, default-off):** the salvaged VpnService engine may run
  **iff** no other VPN holds the slot (§4 guardrail). Reached only through an
  explicit settings flow behind a full-screen explainer.

Mode ≠ "engine armed." STANDALONE is the *permission to arm*; the arm/disarm
toggle lives inside Standalone and is itself default-off. Three reachable runtime
states:

| Runtime state | Mode | Engine | VPN slot |
|---|---|---|---|
| **Companion** | COMPANION | never runs | untouched (Tailscale keeps it) |
| **Standalone-idle** | STANDALONE | armed=false | untouched |
| **Standalone-armed** | STANDALONE | armed=true, guardrail PASS | held by us (only when no incumbent) |

On the operator's Tailscale device the guardrail (§4) makes Standalone-armed
permanently unreachable; the app is, in effect, the pure Companion egress
dashboard — the correct outcome.

---

## 2. SCREEN MAP & NAVIGATION

Keep the existing hand-rolled saveable route enum + per-route `BackHandler`
(audit C.4: "actually solid"). Wrap every screen in the shared
`UnderstoryScaffold`+`UnderstoryTopAppBar` (fixes the "no Scaffold" GUI debt,
SUITE #9). Six destinations off the main **Egress Dashboard** (Approach C's
taxonomy), plus the walled-off Standalone hub:

```
Main: EGRESS DASHBOARD  (com.understory.firewall / "Understory Net Audit")
├─ [card] Tunnel Posture       → TunnelPostureScreen   (§5.1, new)
├─ [card] Remote-Admin Audit   → AuditScreen           (§5.4, crown jewel, kept)
├─ [card] DNS Hardening        → DnsHardeningScreen     (§5.3, flagship, kept)
├─ [card] Traffic by App       → TrafficScreen          (§5.2, new, opt-in gated)
├─ [row]  Restrict Worklist    → RestrictScreen         (§5.5, redesigned verb)
├─ [row]  Egress Canaries      → CanaryScreen           (§5.6, new, opt-in)
├─ [row]  Network Posture      → PostureScreen          (§5.7, copy rewritten)
├─ [row]  Limits & Diagnostics → LimitsScreen + Diagnostics (§5.8, new + kept)
├─ [row]  Standalone mode…     → StandaloneHubScreen    (§3, walled-off)
└─ SuiteStatusFooter (kept, MainActivity.kt:735)
```

`FirewallRoute` (revised): drop `PortBlocks`, `OverlayRouting`; add
`TunnelPosture`, `Traffic`, `Restrict`, `Canary`, `Limits`, `StandaloneHub`.
Keep `Audit`, `Dns`, `Posture`, `Diagnostics`.

The main screen has **no VPN switch** in Companion mode — the single biggest
honesty win: the primary surface can never imply we hold or want the slot. The
three full-width OutlinedButtons that eat ~25% of today's screen (audit C) fold
into the **Limits & Diagnostics** row.

### 2.1 Main screen (Egress Dashboard) — top to bottom

1. **Header:** title "Net Audit"; subtitle = live one-line posture summary
   (e.g. "Tailscale holds the VPN slot ✓ · DNS encrypted ✓ · 2 apps to review").
   No switch.
2. **A11y warning banner** (A6, `A11yProbe.check`) — renders above the cards only
   when non-system accessibility services exist.
3. **Tunnel Posture card** (§5.1) — Tailscale/VPN-slot verdict chip.
4. **Remote-Admin Audit card** (§5.4) — "N apps can control this device"
   (scan count minus acknowledged), tri-state color.
5. **DNS Hardening card** (§5.3) — live "Active now: hostname → dns.google ✓" /
   "opportunistic" / "off (unencrypted)".
6. **Traffic card** (§5.2) — granted: "Apps moved X GB today · N sent data while
   idle"; ungranted: opt-in affordance (never a dead chart).
7. **Tools row** (compact 2-col cards): Restrict · Canaries · Posture · Limits ·
   **Standalone mode…**
8. **SuiteStatusFooter** (A14, kept).

**Main-screen states:** loading (each card shows a shimmer while its source
resolves off-main-thread, §9); error (a card whose source threw shows "couldn't
read — tap to retry," never a fake green); first-run (Audit card expands into the
review prompt, A5). **No preempted banner** — that inverted-semantics surface (A2)
is deleted from Companion entirely.

---

## 3. STANDALONE MODE — THE WALLED-OFF ENGINE

Reached only via Main → Tools → **Standalone mode…** → `StandaloneHubScreen`.
Two visual regions:

- **Mode-state region (top):** whether Standalone is ENABLED/DISABLED + the live
  guardrail verdict (§4). The mode toggle and all guardrail copy live here.
- **Engine region (below — present only when Standalone ENABLED and guardrail
  PASS):** the engine arm switch, the hard-block app list (search + chips reused
  from the A3 app-list substrate), and the honest drop counter (§9-drop). When
  guardrail FAIL, this region is **replaced** by the guardrail explanation card
  and the arm switch is **absent** (not disabled-and-present — absent, so there is
  zero dead control, CD-4a).

### 3.1 Enabling Standalone — the flow

1. User taps the **"Standalone blocking (no VPN)"** switch (default off).
2. App runs the **guardrail probe** (§4) *before* changing anything.
3. **Guardrail FAIL (a VPN is active — the operator's steady state):** the switch
   does not flip; show the guardrail card (§4 copy); log to Diagnostics; mode
   stays COMPANION. Honest, not an error tone.
4. **Guardrail PASS (no VPN active):** show a **full-screen explainer** (first
   time only; re-shown if dismissed without confirming):
   > **Standalone blocking uses Android's VPN slot**
   > This mode routes your blocked apps through a local, on-device tunnel to drop
   > their traffic. It uses the one Android VPN slot, so **you can only use it if
   > you are not running another VPN.** If you later turn on Tailscale or any VPN,
   > Standalone blocking stops automatically and this app returns to audit-only
   > mode. Your block list is kept.
   > [ Not now ]   [ Enable Standalone ]
5. On **Enable Standalone**: persist `mode = STANDALONE`, set
   `K_STANDALONE_EXPLAINED = true`. This does **not** arm the engine — it only
   reveals the engine region with the arm switch (default off). No
   `VpnService.prepare` yet.

### 3.2 Arming the engine (inside Standalone, guardrail PASS)

- User flips **"Block enabled apps now"**.
- Re-run the guardrail probe (slot state may have changed since enabling the
  mode).
- PASS → walk the existing consent path (`VpnService.prepare` → consent launcher
  → `startVpn(ctx)`), reusing `requestVpnEnable` verbatim
  (`MainActivity.kt:328-357,380-394`).
- FAIL → do not launch consent; flip the switch back off; show the guardrail
  card; mode stays STANDALONE, engine cannot arm until the slot frees.

### 3.3 Disabling / runtime eviction

- **Disable Standalone:** flip mode switch off → `stopVpn(ctx)` if armed →
  persist `mode = COMPANION`; keep the hard-block list persisted. Instant, no
  dialog.
- **Runtime eviction (Tailscale/any VPN comes up while armed):** the slot-watcher
  (§4 point 3) or `onRevoke` (§8) sets `engineArmed=false`,
  `autoStopped=true`, keeps `mode=STANDALONE`, tears down the tun. Next hub visit
  shows the neutral line: "Standalone blocking stopped because a VPN
  (<name-or-'another app'>) is now using the VPN slot. It will resume when you
  turn that VPN off." **No "Re-enable" nag, no mention of evicting anything.**

---

## 4. THE HARD GUARDRAIL (detect active VPN, refuse to start)

The load-bearing safety mechanism. Two independent checks, evaluated to a
`VpnSlotState`; **Check 1 is the veto** — if it says a VPN is active, we FAIL
regardless of Check 2. Fail-closed: any exception in the probe ⇒ FAIL (assume an
incumbent). Rationale: a false refusal costs a no-VPN user one retry; a false PASS
could evict Tailscale — the one thing the doctrine forbids.

New file: `firewall/src/main/java/com/understory/firewall/VpnSlotProbe.kt`
(Companion-safe; only `ACCESS_NETWORK_STATE`, already held, manifest:52).

### Check 1 — ConnectivityManager capability scan (primary, always available)

```kotlin
fun isAnotherVpnActive(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    // Scan ALL networks, not just the active one: a split-tunnel VPN may not be
    // the active default during handover.
    return cm.allNetworks.any { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@any false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
}
```

`TRANSPORT_VPN` present **and** `NET_CAPABILITY_NOT_VPN` absent ⇒ a VPN network
exists. Iterate `allNetworks`, never a single active-network read (audit B.1
recommends the TRANSPORT_VPN check; we harden it to all-networks).

### Check 2 — `VpnService.prepare()` (slot-ownership disambiguation only)

- `null` ⇒ we already hold consent/slot (fine to (re)start our own engine).
- non-null Intent ⇒ consent is needed. This alone does **not** prove an incumbent
  (it's also non-null on first-ever use). So `prepare()` only decides, on a Check-1
  PASS, whether to show the consent dialog.

**Combined verdict:**

| Check 1 (CM) | prepare() | Verdict | Meaning |
|---|---|---|---|
| VPN active | any | **FAIL — incumbent holds slot** | Tailscale etc. is up. Refuse. |
| no VPN | null | **PASS — we own consent** | safe to (re)establish silently |
| no VPN | non-null | **PASS — free, consent needed** | safe to walk the consent dialog |

### Package attribution (advisory only — NEVER gates the refusal)

To name the incumbent in copy ("…because Tailscale is using the slot"), read
`Settings.Secure "always_on_vpn_app"` (permissionless; may be blank for a
manually-started Tailscale) and/or `getPackageInfo("com.tailscale.ipn")`.
**Requires adding `com.tailscale.ipn` to `<queries>`** (§11; SUITE #9 — it is
currently absent, so even `getPackageInfo` fails under package-visibility). If the
name can't be resolved, degrade copy to "another app." Never block on attribution;
never claim a name we didn't verify (CD-4).

### Three enforcement points — all must call the guardrail

1. Enabling Standalone mode (§3.1 step 2).
2. Arming the engine (§3.2, before `VpnService.prepare`).
3. A **live slot-watcher while armed** — register a
   `ConnectivityManager.registerNetworkCallback` on a `NetworkRequest.Builder()
   .addTransportType(TRANSPORT_VPN).build()`. If a VPN network that is not ours
   appears, proactively `stopVpn()` + set `autoStopped` **before** the system's
   `onRevoke` fires, so teardown is graceful on OEMs where `onRevoke` timing is
   flaky. `onRevoke` (§8) is the backstop.

**Non-negotiable invariant:** the Check-1 `TRANSPORT_VPN` veto is fail-closed and
cannot be weakened by any caller field or `prepare()` result.

---

## 5. THE KEPT / NEW SCREENS IN DETAIL

### 5.1 Tunnel Posture — `TunnelPostureScreen` + `TunnelPosture.kt` (NEW)

The Tailscale-native surface — the honest replacement for A2's inverted banner.
New pure read-model `firewall/.../TunnelPosture.kt`:

```kotlin
enum class Tri { YES, NO, UNKNOWN }
data class TunnelPosture(
    val tailscaleInstalled: Boolean,   // getPackageInfo != null
    val tailscaleVersion: String?,     // best-effort
    val aVpnIsUp: Tri,                 // TRANSPORT_VPN on any network
    val alwaysOnApp: String?,          // Settings.Secure, may be null
    val alwaysOnIsTailscale: Tri,      // alwaysOnApp == com.tailscale.ipn
    val lockdown: Tri,                 // always_on_vpn_lockdown == 1
)
```

**What a rootless app can read (every UI claim traces to this table):**

| Fact | API | Grant | Confidence | Degrade |
|---|---|---|---|---|
| Tailscale installed / version | `pm.getPackageInfo("com.tailscale.ipn",0)` | `<queries>` entry (§11) | exact | catch `NameNotFoundException` → "not installed" |
| A VPN is up | `ConnectivityManager.allNetworks` → `hasTransport(TRANSPORT_VPN)` | `ACCESS_NETWORK_STATE` (held) | exact ("a VPN is up") | null caps → `UNKNOWN` |
| Which app owns the tunnel | *not directly readable.* Infer YES only if `always_on_vpn_app == com.tailscale.ipn` | — | partial (honest) | else "a VPN is active (owner not identifiable)" |
| Always-on VPN app | `Settings.Secure.getString(cr,"always_on_vpn_app")` | permissionless read | best-effort (undocumented key) | null/blank → `UNKNOWN` |
| Lockdown ("block w/o VPN") | `Settings.Secure.getInt(cr,"always_on_vpn_lockdown",-1)` | permissionless read | best-effort | `-1` → `UNKNOWN` |

All population wrapped in `runCatching`, logs to Diagnostics on failure, degrades
to `UNKNOWN`/null — never throws. **Refresh:** recompute on `ON_START` (reuse the
Settings-round-trip re-scan, `MainActivity.kt:993-1004`) + a manual "Re-check"
button. No background polling.

**Overall verdict chip — exact ladder (never green on an inference gap, CD-4d):**

| Condition | Chip | Color role |
|---|---|---|
| `!tailscaleInstalled` | "No Tailscale detected" | `secondary` (neutral — a non-Tailscale user is valid) |
| installed && `aVpnIsUp==YES` && `alwaysOnIsTailscale==YES` && `lockdown==YES` | "Tunnel posture: strong" | `primary` (green) |
| installed && `aVpnIsUp==YES` && (`alwaysOnIsTailscale!=YES` OR `lockdown!=YES`) | "Tunnel up — hardening available" | `tertiary` (amber) |
| installed && `aVpnIsUp==NO` | "Tailscale installed but no VPN is up" | `tertiary` (amber) |
| any `UNKNOWN` blocks a green | show that row as "?", never upgrade to strong | — |

**Per-fact rows** (labelled `Text` + `Tri` icon w/ TalkBack `stateDescription`):
installed ✓ vX; a VPN tunnel is active ✓/✗/? (subtitle when `alwaysOnIsTailscale
!=YES`: "We can see a tunnel is up but can't confirm it's Tailscale on this
build."); always-on ✓/✗/unknown; lockdown ✓/✗/unknown.

**Actions (route-only):** "Open VPN settings" → `Intent(Settings.ACTION_VPN_
SETTINGS)` (primary CTA when lockdown/always-on are NO/UNKNOWN); "Open Tailscale"
→ `pm.getLaunchIntentForPackage("com.tailscale.ipn")`, disabled-with-reason if
not installed, honestly labelled (no fake deep-link into a Tailscale screen);
"Re-check."

**States:** loading; strong (all green); partial (amber); not-installed (neutral —
"works with or without Tailscale; the DNS, traffic, and audit tools below work
regardless"; no auto-launch to Play); all-keys-unknown ("Your device build
doesn't expose always-on status to other apps — check it yourself in VPN
settings" + deep-link).

### 5.2 Traffic by App — `TrafficScreen` + `TrafficAccounting.kt` (NEW, opt-in)

Honest rootless answer to "who talked and how much" — observation, not
interception. The replacement for A4's drop counter and A11's port view.

**Mechanism:** `NetworkStatsManager` (`Context.NETWORK_STATS_SERVICE`).
- Grant: `PACKAGE_USAGE_STATS` (Special app access → Usage access) — declared in
  manifest with `tools:ignore="ProtectedPermissions"` + honest rationale;
  **never required** (screen degrades). Check via
  `appOps.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, uid, pkg) == MODE_ALLOWED`.
- Query: for each installed UID (reuse `AppListLoader`), `querySummary` /
  `queryDetailsForUid(NETWORK_TYPE_WIFI/MOBILE, subscriberId=null, start, end,
  uid)`; sum `rxBytes`/`txBytes`. Windows: Today / 7d (segmented control). Map
  `uid → packages` via `pm.getPackagesForUid(uid)` (shared-UID aware — render the
  group honestly).
- "Talked while you weren't using it": cross-reference `UsageStatsManager
  .queryEvents` foreground windows (same grant). Show the "background" flag **only**
  if the split is available; otherwise omit it — never fake it.
- **All queries on `Dispatchers.IO`** behind a `StateFlow<TrafficUiState>`
  (Loading/NeedsGrant/Data/Empty/Error) — satisfies the main-thread-IO ban
  (SUITE #8).

**UI states (all present — no dead chart, CD-2b):**
- **NeedsGrant** (default): explainer "Grant Usage access to see per-app traffic.
  This stays on your device — we send nothing." + "Open Usage access" →
  `Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)`; re-check on `ON_START`. No
  chart shell.
- **Loading:** skeleton rows. **Empty:** "No traffic recorded in this window."
  **Error:** honest message + Diagnostics pointer.
- **Data:** sorted-desc rows (icon + label + total + wifi/cell split bar); each
  expands to rx/tx + a **"Restrict this app"** action →
  `Intent(ACTION_APPLICATION_DETAILS_SETTINGS, package:pkg)` ("Turn off Background
  data / restrict data usage here — Android enforces it"). Optional "background"
  chip.

**Boundary line (footer):** "This is accounting, not blocking. It shows totals
after the fact and can't see hosts or contents. Restrictions are applied by
Android, not this app."

### 5.3 DNS Hardening — `DnsHardeningScreen` (KEPT + tightened; flagship)

Keep the A7 architecture verbatim in mechanism (the doctrine-model feature —
`WRITE_SECURE_SETTINGS` configures the platform's own Private DNS):
- **Active-now card first:** live read-back via `PrivateDnsApplier.current()`
  (`:74-84`) — no permission to read.
- **Provider catalog (DoT only** after A8 removal): the DoT providers from
  `DnsProvider.kt` with honest privacy notes.
- **Apply:** `PrivateDnsApplier.apply/clear` (`:89-137`) when
  `WRITE_SECURE_SETTINGS` is ADB-granted; else the fallback deep-link
  (`android.settings.PRIVATE_DNS_SETTINGS` → `ACTION_WIRELESS_SETTINGS`) +
  copy-hostname + copy-ADB-command (`MainActivity.kt:1690-1747`).

**Fixes folded in:**
- **NextDNS `<your-config>` placeholder** (`DnsProvider.kt:109-119`, appliable
  garbage — D11): add an inline config-ID `TextField`; specifier templated
  `"${configId}.dns.nextdns.io"`; Apply disabled-with-reason until the field
  matches `[a-f0-9]{6,}`. If the field is not built, **drop the NextDNS entry** —
  no half-appliable row.
- Wall-of-text instructions → numbered M3 steps.
- **Tailscale/exit-node advisory (one line):** "Private DNS composes with
  Tailscale — with a Tailscale exit node or MagicDNS enabled, Tailscale may
  override the system resolver; verify with the DNS canary (below)." The only
  place we mention exit-node/MagicDNS, and only as things we **cannot read** but
  the user can verify empirically (§5.6).

### 5.4 Remote-Admin Audit — `AuditScreen` (KEPT verbatim; crown jewel)

Mechanism unchanged (`RemoteAdminAudit.scan()`: `DevicePolicyManager.activeAdmins`,
`AccessibilityManager`, `enabled_notification_listeners`, AppOps `checkOpNoThrow`
for usage-stats/overlay/install/all-files; per-capability revoke deep-links;
acknowledge-vs-block model; `ON_START` re-scan). Three fixes:

1. **`MODE_DEFAULT` fallback (A5 caveat 1, D10).** In `RemoteAdminAudit.opGranted`
   (`:240-259`), when `checkOpNoThrow` returns `AppOpsManager.MODE_DEFAULT`, fall
   through to `pm.checkPermission(manifestPermFor(op), pkg) == PERMISSION_GRANTED`
   for `SYSTEM_ALERT_WINDOW` and usage-stats. Return a tri-state
   (`Granted`/`NotGranted`/`Unknown`); render `Unknown` honestly, never as "clean"
   (CD-4d).
2. **Verb relabel (A5 caveat 2, D12).** The per-finding "Block" switch
   (`MainActivity.kt:1223-1226`) currently edits a list the dead VpnService would
   consume — a dead promise. Replace with two honest actions: **"Revoke in
   Settings"** (existing `RiskCapability.revokeAction` deep-link, the real action)
   and **"Add to watchlist"** (writes the repurposed restrict set, §5.5). In
   Standalone mode the sheet additionally offers **"Hard-block"** (adds to the
   engine block list) — the one place blocking is real.
3. **First-run copy.** "Block every detected app" → "Review N apps that can
   control this device" → routes into AuditScreen; the bulk action is "acknowledge
   all after review" (reuse `FirewallSettings.auditAcknowledged`).

### 5.5 Restrict Worklist — `RestrictScreen` (REDESIGN of the block-list, A3)

The old `K_BLOCKLIST` UI becomes an **"apps to restrict"** worklist. Keep the whole
`AppListLoader` substrate (`getInstalledApplications` + launcher-intent filter,
icon cache, stale-rule pills, empty hints — audit A3 "WORKING"). Redesign the
row semantics: the trailing control is a **watchlist star** ("watch/flag this
app"); a row tap opens an `AppDetailSheet` (M3 `ModalBottomSheet`) of **OS-enforced**
deep-links, each guarded by `resolveActivity` so no dead button ships:

| Advise action | Intent | Notes |
|---|---|---|
| App details / permissions | `ACTION_APPLICATION_DETAILS_SETTINGS`, `data=package:$pkg` | always resolves |
| Restrict background data | app-details (no universal per-app data action) → instruct "Mobile data → Allow background data usage: off"; on One UI also try `ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` if it resolves | availability-checked |
| Pause unused app | app-details "Pause app activity if unused" | instruct there |
| Data-saver (global) | `ACTION_DATA_USAGE_SETTINGS` | labelled as global, not per-app |
| Uninstall | `ACTION_DELETE`, `data=package:$pkg` | user confirms in OS dialog |

Filter chips become **"Watched" / "All apps" / "System"**. Sheet header: "Restrict
via Android — understory opens the setting; Android enforces it." No control here
claims the app itself restricts anything. **Hand-off IN (suite-coexistence §3.6,
line 302):** this worklist is the sink for `APK_AUDITOR` advisories from antivirus
— receive a signed Intent naming a flagged package, add it to the watched set,
surface it here. (Intent contract owned by the suite doc; v2-incremental — the
worklist is built to accept it.)

The **same persisted set** seeds the Standalone hard-block list, so flagging an
app in Companion carries over if the user ever enables Standalone.

### 5.6 Egress Canaries — `CanaryScreen` + `CanaryProbes.kt` (NEW, opt-in)

The empirical "is my DNS/egress actually what I think" proof — the honest
substitute for claiming to read Tailscale's DNS/exit state, and the replacement
for the drop counter's lost reassurance. **INTERNET is already held**
(manifest:51). Strict rules to preserve the no-telemetry posture (CD-4):

- **Explicit-tap-only.** Nothing fires on screen open. Each probe is a button.
- **Named endpoint on the button face** — the user sees the exact host before
  tapping. Default set (privacy-respecting / first-party):
  - **Egress IP:** GET `https://api.ipify.org` (or `1.1.1.1/cdn-cgi/trace`) —
    shows whether traffic exits via a Tailscale exit node or the local ISP.
  - **Resolver identity / DNS leak:** `https://1.1.1.1/cdn-cgi/trace` (returns the
    resolver POP) + a DoH round-trip to the *configured* provider; if the
    answering resolver ≠ the Private DNS specifier, flag a possible leak.
  - **DoT reachability:** TLS connect to the configured specifier:853, SNI =
    specifier; success/fail only.
- All calls on `Dispatchers.IO`, short timeouts; raw response available in
  Diagnostics.
- **Boundary line:** "These probes send one request each to the named host when
  you tap. Nothing else leaves your device. We can't read Tailscale's DNS settings
  directly — this checks what actually happens on the wire."

If a Tailscale exit node reroutes DNS, the resolver-identity probe shows a resolver
the user didn't configure, and the app reports the fact — without pretending to
have read Tailscale's config.

### 5.7 Network Posture — `PostureScreen` (KEPT; copy rewritten, A13/D7)

Static informational screen (`MainActivity.kt:1328-1419`). Rewrite:
- **DELETE** "One-VPN-slot exclusivity → turn the firewall off to run another
  tunnel" (`:1381-1388`) — doctrine-hostile.
- **ADD** "Works alongside Tailscale": "We never take the VPN slot. Tailscale keeps
  it; we read your tunnel's posture and route you to the OS controls" + a short
  Standalone-mode explainer ("no-VPN users can opt into real blocking; it refuses
  to start whenever a VPN is present").
- **KEEP** "Blind by default" / "No telemetry" / "No cloud" — true, the
  differentiator.
- Update the manifest header comment (`AndroidManifest.xml:36-49`) to match.

### 5.8 Limits & Diagnostics — `LimitsScreen` + Diagnostics (NEW card + KEPT)

- **Limits card** renders the "What this app can't do" list verbatim — a doctrine
  feature (CD-4b), not fine print. A Tailscale user wants the exact edge of the
  tool. Content (each a plain row):
  1. No per-app blocking / packet drop without the VPN slot (Tailscale holds it);
     we route you to Android's own per-app data restriction instead.
  2. We cannot read Tailscale internals: no ACLs, exit-node, MagicDNS state, peer
     list, tailnet name. Tailscale ships no public local API; we infer "installed
     + a VPN is up + (maybe) always-on/lockdown."
  3. We cannot *tell* the up VPN is Tailscale unless `always_on_vpn_app ==
     com.tailscale.ipn`; otherwise "a VPN is active" + "Tailscale installed," no
     false green.
  4. We cannot enforce DNS for other apps (NSC binds to the owning APK); Private
     DNS is the device-global lever, which we drive.
  5. We cannot toggle airplane mode / always-on / lockdown / exit-node — those are
     user actions behind deep-links.
  6. NetworkStats is accounting, not interception — who talked and how much,
     retrospectively; no hosts, no contents, no blocking.
  7. Port-block discovery is dead on every supported device (`/proc/net`
     restricted on Android 10+; minSdk 33).
- **Diagnostics** kept as-is (shared `DiagnosticsScreen`, `MainActivity.kt:732`);
  log guardrail verdicts + mode transitions here.
- The three old full-width OutlinedButtons collapse into this row.

---

## 6. DISPOSITION OF EVERY AUDITED FEATURE (A1–A16)

FIX = make current design work · REDESIGN = different mechanism · DROP = remove
honestly, note replacement.

| Audit | Feature | Disposition | Detail |
|---|---|---|---|
| A1 | VPN arm + per-app blocklist drop | **REDESIGN** | Engine survives *inside Standalone mode only*, gated by §4. Absent in Companion. `startVpn()` app-drop path reused verbatim; only its *reachability* changes. |
| A2 | Preempted banner + "Re-enable" | **DROP** (main) / **REDESIGN** (Standalone) | Deleted from Companion. Tunnel Posture (§5.1) renders Tailscale-holds-slot as the *good* state; Standalone uses the neutral `autoStopped` message (§3.3), no eviction nag. |
| A3 | App-list UI (search/chips/icons/stale pills) | **FIX + REDESIGN (verb)** | Substrate kept for Restrict (§5.5), Traffic (§5.2), Standalone hard-block list. Verb → "watch/restrict"; add 48dp targets + TalkBack (§9). |
| A4 | Drop counter | **REDESIGN** | Shown ONLY in Standalone-armed, fed by the live tun reader (§9-drop). Absent in Companion (no dead "0 packets"). Replaced on the dashboard by Traffic (§5.2). |
| A5 | Remote-admin audit | **KEEP + FIX** | Crown jewel; verbatim mechanism + `MODE_DEFAULT` fallback + "Block"→"Revoke"/"Add to watchlist" (+"Hard-block" in Standalone) + first-run copy (§5.4). |
| A6 | 3rd-party a11y warning banner | **KEEP** | `A11yProbe.check`, rootless. Main banner + Tunnel/Posture surfaces. |
| A7 | DNS provider select + DoT apply | **KEEP + FIX (flagship)** | Applier + live read-back kept; NextDNS config-ID field (or drop); Tailscale advisory line; DNSCrypt entries removed (A8) (§5.3). |
| A8 | DNSCrypt providers + bundled proxy | **DROP** | Remove the 3 DNSCRYPT entries from `DnsProvider.ALL`; delete `DnsCryptProxyService` + its manifest `<service>` (243-250); drop `tools/fetch-dnscrypt-proxy.sh`. Binary never in repo; proxy nothing can query without the tun (A8). Replacement: DoT via Private DNS + the DNS canary (§5.6). |
| A9 | DNS-redirect preview (DnsRedirector + parser) | **DROP from app / salvage as library** | Remove the `startVpnDnsRedirectMode()` branch (`FirewallVpnService.kt:157-163,337-390`), the `DnsRedirector` field, the `FAKE_DNS_IP` route. Standalone's engine has exactly one mode: app-drop. `VpnPacketParser` + `DnsRedirector` → salvage library (§7), not called by the app. |
| A10 | Misleading "N blocked" in DNS-redirect | **FIX (moot by construction)** | DNS-redirect mode removed → the mislead cannot occur. The §9-drop honesty rules bind while the code ships. |
| A11 | Custom port blocking (`/proc/net`) | **DROP** | Structurally a no-op on 100% of minSdk-33 devices (`PortBlockDiscovery.kt:44-50`). Delete `PortBlocksScreen`, `PortBlockDiscovery`, the `portScannerThread` block (`FirewallVpnService.kt:179-202`), `K_BLOCKED_PORTS`/`getBlockedPorts`/`setBlockedPorts`, the `PORT_SCAN_INTERVAL_MS` constant. Unreachable `465` branch dies with the screen. Replacement: Traffic accounting (§5.2) names volume, not connections. |
| A12 | Overlay routing (I2P/Lokinet/Ygg) | **DROP** | Delete `OverlayRoutingScreen`, the route + entry, `FirewallSettings` K_OVERLAY_* accessors; drop `overlay-i2p/lokinet/yggdrasil` deps (`build.gradle.kts:87-89`) + their `include`s. I2P "live status" cross-process-impossible; Yggdrasil is itself a VpnService transport (vetoed). |
| A13 | Network posture screen | **KEEP + FIX (copy)** | Rewrite to coexistence (§5.7); delete the "turn firewall off" section + manifest comment. |
| A14 | Suite integration (caps/footer/tamper/attest) | **KEEP + FIX** | Keep all. `NETWORK_FILTER`→`NET_POSTURE_AUDIT` (§10.5); `${applicationId}.suitecaps` authority (§11); tamper hard-fail explanation screen (§10.6). |
| A15 | Screen-capture / tap-jack hardening | **KEEP verbatim** | FLAG_SECURE, `setHideOverlayWindows`, recents-off, `secureClickable`/passive-Switch. Best-in-suite. |
| A16 | Diagnostics screen | **KEEP** | Shared `DiagnosticsScreen`; log guardrail verdicts + mode transitions (§5.8). |

---

## 7. SALVAGE-AS-LIBRARY (special charge)

Create a library module so the packet code survives cleanly and is unit-testable
without the app. **Preferred:** new `understory-common` module **`net-engine`**
(`com.understory.net.engine`), vendored into firewall like `common-security`; add
to `settings.gradle.kts` (`include(":net-engine")`) and `firewall/build.gradle.kts`
(`implementation(project(":net-engine"))`).

| File | Move to | Status in shipping app |
|---|---|---|
| `VpnPacketParser.kt` | `net-engine` (pure JVM: only `java.net`; bounds-checked; RFC-768 zero-checksum `:233`) | compiled, **unit-tested, NOT called** by the Standalone engine (which uses plain app-drop). For a future userspace forwarder. |
| `DnsRedirector.kt` | `net-engine` (depends on `VpnService.protect` → stays with the optional engine, not common-security) | compiled, **not called** (DNS-redirect removed §6/A9). |
| `FirewallVpnService.kt` | stays in the app (Android `Service` + manifest component) | the Standalone engine; app-drop path only. Keep its hard-won correctness: atomic tun swap (`:287-321`), all-uninstalled guard (`:250-261`), `onRevoke` persistence (redesigned §8), specialUse-FGS lesson (manifest:215-221 + `SAMSUNG_QUIRKS.md:63-77`). |
| `DropStats.kt` | with the engine (trivial) | live only in Standalone-armed. |
| `PortBlockDiscovery.kt` | **do not salvage** | structurally dead on all supported API levels. Keep its limitation header as a `net-engine` doc comment for the record. |

Add JVM unit tests for `VpnPacketParser` in `understory-common/tests` (parse
valid/truncated IPv4+UDP; checksum incl. the zero-rule) — pure and testable today;
locks the salvage value.

---

## 8. STATE PERSISTENCE & `onRevoke` SEMANTICS

### Persisted keys (`FirewallSettings`)

| Key | Meaning | Notes |
|---|---|---|
| `K_MODE` (new) | `COMPANION`\|`STANDALONE` | default COMPANION; the mode gate |
| `K_ENGINE_ARMED` (renamed from `K_VPN_ENABLED`) | engine arm request | only meaningful in STANDALONE; in COMPANION effectively false |
| `K_AUTO_STOPPED` (new, replaces `K_VPN_PREEMPTED`) | engine auto-stopped because a VPN took the slot | neutral flag → the neutral hub message, NOT a nag; cleared on next successful arm or mode-disable |
| `K_RESTRICT_LIST` (was `K_BLOCKLIST`) | apps flagged in Companion Restrict + seed for Standalone hard-block | shared set; both modes read it |
| `K_DNS_PROVIDER` | DoT provider id | DNSCrypt ids removed; if it points at a deleted DNSCRYPT id → reset to `SYSTEM_DEFAULT` on migration |
| `K_FIRST_RUN_AUDIT_DONE`, `K_AUDIT_ACKNOWLEDGED` | unchanged | audit flow |
| `K_STANDALONE_EXPLAINED` (new) | user saw the full-screen explainer | so we don't re-nag after confirm |
| `K_MIGRATED_V2` (new) | one-time migration guard | see below |
| **removed** | `K_BLOCKED_PORTS`, `K_OVERLAY_ROUTING`, `K_OVERLAY_NETWORK` | dropped with A11/A12 |

**One-time V2 migration** (guarded by `K_MIGRATED_V2`): copy old `K_BLOCKLIST` →
`K_RESTRICT_LIST` (curated app list survives as "watched"); delete the dead keys
above; reset `K_DNS_PROVIDER` if it names a removed DNSCRYPT id; set
`K_MIGRATED_V2 = true`. Silent, lossless, no migration UI.

### `onRevoke` redesign (`FirewallVpnService.kt:100-126`)

Under this design `onRevoke` fires **only** while the engine is armed in
Standalone (Companion never establishes a tun). Rewrite:
```
onRevoke():
  FirewallSettings.setEngineArmed(this, false)   // UI never claims we're filtering
  FirewallSettings.setAutoStopped(this, true)     // neutral, not "preempted"
  // mode stays STANDALONE — a VPN just took the slot; the user hasn't left the mode
  stopVpn(); stopForeground(REMOVE); stopSelf()
```
No `vpnPreempted`, no banner data, no "Re-enable" intent. The slot-watcher (§4
point 3) usually beats `onRevoke`, making teardown proactive; `onRevoke` is the
backstop.

### Main-screen honesty (fixes A10 by construction)

- **Companion:** no engine, no counter, no "N blocked." The header `Switch` and
  the `if (vpnEnabled) "${blocked.size} app(s) blocked"` line
  (`MainActivity.kt:366-378`) are **deleted** from this surface; the subtitle is
  the posture summary.
- **Standalone-armed:** subtitle "Blocking N app(s)" only when the tun is actually
  established with N>0 allowed apps.
- **FGS notification** (`FirewallVpnService.kt:426-435`): text reflects the
  *actual established* block count (the `added` count from `startVpn`, not
  `getBlockedPackages().size`), so an all-uninstalled idle tun never claims "12
  blocked." DNS-redirect gone → one honest notification form.

---

## 9. THE DROP COUNTER & CROSS-CUTTING RULES

**Drop counter (`DropStats`, follows the engine):**
- **Companion:** not rendered at all — no interception, so "0 dropped" would be a
  false-adjacency implying enforcement. Absent = honest.
- **Standalone, disarmed:** not rendered.
- **Standalone, armed, tun established (added>0):** rendered "dropped N packets ·
  Xs ago," fed by the live tun reader (`:310`). The only truthful place it appears.
- **Standalone, armed, tun idle** (empty/all-uninstalled block list, the audit's
  idle-mode guard `:204-221`/`:250-261`): show "Armed · no apps blocked yet" and
  **no** counter.
- Lifecycle (`:403-406`): resets on full stop, persists across rule-change
  re-establishes; in-process (VpnService shares the app process), no IPC.

**Cross-cutting (inherits the shared-GUI work, `design-v2/shared-gui.md`):**
- **Off-main-thread (SUITE #8):** `RemoteAdminAudit.scan()`, `NetworkStatsManager`
  queries, `getInstalledApplications`, icon loads, all canary probes on
  `Dispatchers.IO` behind `StateFlow`/`produceState`. No main-thread IO.
- **Strings → `res` (SUITE #9):** externalize all copy (today `strings.xml` has
  only `app_name`); the store-rename to "Understory Net Audit" is an `app_name`
  change only.
- **Theme tokens:** new screens use `MaterialTheme.colorScheme` roles, not hex
  literals; tri-state verdict colors map to `primary`/`tertiary`/`secondary`.
- **A11y:** watchlist star, acknowledge, probe buttons, toggle rows get
  `Modifier.toggleable`+`role=Switch`+`stateDescription`; `Tri` icons get
  `contentDescription`; interactive glyph `Text` ("✕"/"›") → `IconButton`+`Icon`;
  48dp min touch targets.
- **Status honesty (CD-4d):** every checkmark derived from an unreadable Secure key
  or an ungranted opt-in renders "unknown" or the opt-in CTA — never a fabricated
  green.

---

## 10. SUITE INTEGRATION

- **10.5 Capability beacon:** the app advertises `NETWORK_FILTER` (vetoed core —
  overclaim). Conform to `suite-coexistence.md` §1: rename to **`NET_POSTURE_AUDIT`**
  (the taxonomy change is owned by the suite doc; this app registers only the
  renamed capability). In Companion it advertises `NET_POSTURE_AUDIT`. It may
  advertise a `NETWORK_FILTER`-class capability **only when `mode==STANDALONE &&
  engine armed`** (honest, dynamic — CD-4); on the reference device that never
  happens, so it always beacons `NET_POSTURE_AUDIT` truthfully.
- **10.6 Suite fixtures:** `SuiteStatusFooter`, `SuiteCapsProvider` (signature-
  gated read beacon), Tamper + `SuiteAttestation` boot gate — all **kept** (A14).
  Wire the silent `finishAndRemoveTask` (`MainActivity.kt:124-131`) to the
  suite-wide **tamper hard-fail explanation screen** (one line before exit,
  CD-4c). `${applicationId}.suitecaps` authority fix (§11).
- **Hand-off IN:** the Restrict Worklist (§5.5) accepts `APK_AUDITOR` advisories
  from antivirus (suite-coexistence §3.6/§3.7) — a flagged package arrives as a
  signed Intent and lands in the watched set.
- **Store face: "Understory Net Audit"** (kills the "firewall" name overclaim —
  post-veto it blocks nothing in the default mode; SUITE §4). `app_name` string
  change only; package id `com.understory.firewall` stays (not user-facing).

---

## 11. MANIFEST & BUILD DELTAS

- **`<queries>` (147-174):** add `<package android:name="com.tailscale.ipn" />`
  (§4/§5.1 attribution, SUITE #9). Optionally common consumer-VPN packages
  (`com.wireguard.android`, `org.mullvad.mullvadvpn`, `net.openvpn.openvpn`,
  `ch.protonvpn.android`) for nicer copy — cosmetic; the CM veto never depends on
  them.
- **VpnService `<service>` (223-234):** **keep** — Standalone needs it. A
  registered-but-dormant VpnService does not hold the slot (holding requires an
  established session), so its presence is doctrine-safe. Rewrite the misleading
  "owns the VpnService slot" comment (36-49) to the dual-mode/guardrail story.
- **DnsCrypt `<service>` (243-250):** **remove** (§6/A8). Delete
  `tools/fetch-dnscrypt-proxy.sh`.
- **Permissions:** keep `INTERNET` (canaries), `ACCESS_NETWORK_STATE`,
  `QUERY_ALL_PACKAGES`, `WRITE_SECURE_SETTINGS`. **Add** `PACKAGE_USAGE_STATS`
  (`tools:ignore="ProtectedPermissions"`, special-access, user-granted via Usage
  access — mirrors the WRITE_SECURE_SETTINGS pattern at 20-34). Keep
  `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `POST_NOTIFICATIONS`
  (Standalone's FGS needs them; unlike Approach C/A we do not strip them because
  the engine is retained).
- **`build.gradle.kts`:** drop `overlay-i2p/lokinet/yggdrasil` deps (:87-89) + their
  `include`s in `settings.gradle.kts`; add `net-engine`. Bump `versionName` off
  "0.1-skeleton."
- **SuiteCaps authority (259):** `${applicationId}.suitecaps` (SUITE #10 eng/prod
  collision).
- **Portrait lock (200) / activity-isolation RELEASE-BLOCKER comment (192-193):**
  revisit per SUITE #9 (allow sensor-portrait; resolve the stale comment).
- **GUI debt (SUITE #9):** Scaffold/TopAppBar, strings→res, hex→tokens, TalkBack,
  48dp — shared-component fixes inherited suite-wide (§9), not firewall logic.

---

## 12. APPROACH SCORING (why B-base, C-graft)

Scored 1-5 against the five criteria (higher = better).

| Criterion | A (pure observe) | B (dual-mode) | C (delete engine) |
|---|---|---|---|
| **1. Doctrine fit** (complement; never touches slot in default; no evict-nag) | 5 | **5** | 5 |
| **2. Usefulness beside Tailscale** | 4 (audit+DNS+posture) | **5** (same, + real blocking for no-VPN users) | 4 (same as A) |
| **3. Rootless viability** (rejects proc-net, cross-proc I2P, TS internals) | 5 | **5** (engine is gated, not claimed on TS devices) | 5 |
| **4. Shippability / polish** | 4 (smaller surface) | 4 (one extra screen + guardrail plumbing) | **5** (smallest codebase) |
| **5. Honesty / no overclaim** | 5 | **5** (guardrail + dynamic beacon make the engine honest) | 5 |
| **Suite-doc conformance** (§CD-2a + §3.6 require a default-off Standalone engine) | **FAIL** (deletes it) | **PASS** | **FAIL** (deletes it) |

A and C are both excellent and honest, but **both violate the binding suite-level
decision** that the packet engine survive as a default-off Standalone mode
(`suite-coexistence.md` lines 174-177, 301). B is the only conformant approach and
loses nothing A/C offer in Companion — so B is the base. C's product framing is
sharper, so its "egress dashboard" one-liner, Limits card, screen taxonomy, and
named-endpoint canaries are grafted in; A's rigorous per-feature FIX/DROP table and
its `AppDetailSheet` restrict mechanism are grafted in. The one real cost B carries
over A/C — a larger surface and the retained VpnService — is contained by the
fail-closed guardrail (§4) and the walled-off hub (§3), which never touch the
operator's Tailscale slot.

---

## 13. ORDERED IMPLEMENTATION CHECKLIST

1. **Migration + settings spine.** Add `FirewallMode` + `K_MODE`; rename
   `K_VPN_ENABLED`→`K_ENGINE_ARMED`, `K_VPN_PREEMPTED`→`K_AUTO_STOPPED`,
   `K_BLOCKLIST`→`K_RESTRICT_LIST`; add `K_STANDALONE_EXPLAINED`, `K_MIGRATED_V2`;
   delete `K_BLOCKED_PORTS`/`K_OVERLAY_*`; write the one-time V2 migration (§8).
2. **DROP the dead/vetoed UI.** Delete `PortBlocksScreen`, `PortBlockDiscovery`,
   `OverlayRoutingScreen`, the `portScannerThread` block, DNS-redirect branch +
   `FAKE_DNS_IP` route, `DnsCryptProxyService`, the 3 DNSCRYPT `DnsProvider`
   entries, `tools/fetch-dnscrypt-proxy.sh`, the overlay module deps, and the
   corresponding routes/keys (§6, A8/A9/A11/A12).
3. **Salvage library.** Create `net-engine`; move `VpnPacketParser` +
   `DnsRedirector` + `DropStats`; add the JVM parser unit tests (§7).
4. **Guardrail.** Add `VpnSlotProbe.kt` (Check 1 CM veto + Check 2 prepare();
   fail-closed) and the live slot-watcher `NetworkCallback` (§4). Add
   `com.tailscale.ipn` to `<queries>`.
5. **Standalone hub.** `StandaloneHubScreen` + the enable/arm/disable flows +
   full-screen explainer; rewrite `onRevoke` to the neutral `autoStopped`
   semantics; make the FGS notification + main subtitle mode-aware (§3, §8).
6. **Tunnel Posture.** `TunnelPosture.kt` read-model + `TunnelPostureScreen` with
   the exact verdict ladder and degrade-to-unknown rules (§5.1).
7. **Audit fixes.** `MODE_DEFAULT`→`checkPermission` tri-state fallback;
   "Block"→"Revoke"/"Add to watchlist" (+"Hard-block" in Standalone); first-run
   copy (§5.4).
8. **Restrict Worklist.** Repurpose the app-list substrate → `RestrictScreen` +
   `AppDetailSheet` with `resolveActivity`-guarded deep-links; wire the
   `APK_AUDITOR` hand-off-IN sink (§5.5).
9. **DNS Hardening fixes.** NextDNS config-ID field (or drop the entry);
   numbered-step instructions; Tailscale advisory line (§5.3).
10. **Traffic by App.** `TrafficAccounting.kt` + `TrafficScreen` behind the
    `PACKAGE_USAGE_STATS` opt-in with all five UI states; add the manifest
    permission (§5.2, §11).
11. **Egress Canaries.** `CanaryProbes.kt` + `CanaryScreen`, explicit-tap-only,
    named endpoints, `Dispatchers.IO` (§5.6).
12. **Posture + Limits copy.** Rewrite `PostureScreen` to coexistence; add
    `LimitsScreen` (the can't-do list); collapse the three OutlinedButtons; rewrite
    the manifest header comment (§5.7, §5.8).
13. **Suite integration.** `NETWORK_FILTER`→`NET_POSTURE_AUDIT` (dynamic beacon);
    `${applicationId}.suitecaps` authority; wire the tamper explanation screen;
    `app_name`→"Understory Net Audit" (§10, §11).
14. **Shared-GUI adoption + main dashboard assembly.** Wrap every screen in
    `UnderstoryScaffold`; strings→res; hex→tokens; TalkBack + 48dp; assemble the
    Egress Dashboard main screen (§2.1) with loading/error/first-run states (§9).
15. **Verify honesty invariants.** No dead control anywhere; no green from an
    unreadable key; drop counter only in Standalone-armed; no "N blocked" in
    Companion; guardrail fail-closed and unweakened by any caller field.
```
