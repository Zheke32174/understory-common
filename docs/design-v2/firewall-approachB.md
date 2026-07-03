# Firewall V2 — Approach B: Dual-Mode (Companion default + opt-in Standalone Engine)

**App:** `understory-firewall` (`com.understory.firewall`) · store face **Understory Net Audit**
**Design date:** 2026-07-03 · **Scope:** DESIGN ONLY (no code changes; implementer builds from this doc)
**Inputs:** `docs/audit-v2/firewall.md` (full), `docs/audit-v2/SUITE.md` (full), verified against
`FirewallVpnService.kt`, `FirewallSettings.kt`, `MainActivity.kt`, `VpnPacketParser.kt`,
`AndroidManifest.xml`, `firewall/build.gradle.kts`, `settings.gradle.kts`.

---

## 0. One-paragraph thesis

The firewall keeps its well-built rootless **companion** half (remote-admin audit, Private DNS
apply + read-back, Tailscale-coexistence posture) as the **default and only mode most users ever
see**. The genuinely-good VpnService packet engine (`FirewallVpnService` / `VpnPacketParser` /
`DnsRedirector`) is **not deleted** — it is salvaged into an optional, clearly-labelled,
**default-OFF "Standalone blocking (no VPN)"** mode for the minority of users who run **no** VPN at
all (the NetGuard use-case). The mode is protected by one hard, non-negotiable guardrail: **if any
other VPN holds the Android VPN slot, the engine refuses to start**, with copy that never suggests
evicting the incumbent. On the operator's device Tailscale permanently holds the slot, so Standalone
mode is permanently unreachable there — it degrades to an honest, greyed, explained state and the app
behaves exactly like the pure companion. This satisfies CD-1/CD-2/CD-4 while preserving the salvage
value of the packet code.

**Difference from Approach A (pure companion):** A drops the engine entirely and salvages only the
parser as a JVM library. B keeps the *whole* engine compiled and runnable, gated behind the VPN-slot
guardrail, so users with no VPN get real enforcement. B costs one extra screen, one persisted mode
enum, a slot-watcher, and stricter honesty plumbing on two status surfaces.

---

## 1. The two modes

### Mode enum (single source of truth)

Add to `FirewallSettings` a persisted `FirewallMode`:

```kotlin
enum class FirewallMode { COMPANION, STANDALONE }
// default COMPANION; persisted key K_MODE = "firewall_mode"
```

- **COMPANION (default):** observe/advise only. No VpnService is ever prepared, started, or
  registered as *active*. All value comes from rootless reads + deep-links + the ADB-granted Private
  DNS applier. This is the mode the operator's phone runs, always.
- **STANDALONE:** the salvaged VpnService engine may run **iff** no other VPN holds the slot
  (§4 guardrail). This is opt-in, default-off, and reached only through an explicit settings flow
  with a full-screen explanation.

The mode is **not** the same as "engine armed". STANDALONE is the *permission to arm*; the engine
arm/disarm toggle lives inside Standalone and is itself default-off. So there are three reachable
runtime states:

| State | Mode | Engine | VPN slot |
|---|---|---|---|
| **Companion** | COMPANION | never runs | untouched (Tailscale keeps it) |
| **Standalone-idle** | STANDALONE | armed=false | untouched |
| **Standalone-armed** | STANDALONE | armed=true, guardrail passed | **held by us** (only when no incumbent) |

### What each mode delivers

**COMPANION** (all salvaged, all rootless — audit sections A5, A6, A7, A13, A14, A15, A16):
- Remote-admin audit (crown jewel) — deep-link revoke, acknowledge flow.
- Private DNS advise — provider catalog, ADB-applier, live read-back.
- Tailscale-coexistence posture panel (NEW — §6).
- Per-UID traffic accounting behind opt-in usage-access (NEW — §6, optional).
- Per-app **restrict** worklist — the old blocklist UI repurposed to deep-link Android's own
  per-app settings (the "block" verb becomes "restrict via the OS", §5).
- Diagnostics, suite footer, tamper/attestation, FLAG_SECURE hardening.

**STANDALONE** adds, on top of Companion (Companion features remain fully available):
- The engine arm/disarm toggle.
- Per-app **hard block** (the real `addAllowedApplication` drop path) — this is the ONE place the
  word "block" means packet-drop.
- Honest drop counter fed by the live tun reader.

---

## 2. Screen map & navigation

Keep the existing hand-rolled route enum + saveable-string nav (audit C.4 calls it "actually solid";
every sub-route already has a `BackHandler`). Add a M3 `Scaffold`+`TopAppBar` shell per suite GUI
debt item (SUITE §5 #9) — that is a shared-component change, not app logic.

Routes (● = exists today, ○ = new/renamed):

```
Main (Companion cockpit)            ●→ restructured
 ├─ Audit                           ●  (unchanged mechanism; "Block" switch relabelled §5)
 ├─ DNS                             ●  (DoT only; DNSCrypt entries dropped §7)
 ├─ Posture                         ●  (copy rewritten to coexistence §6)
 ├─ Restrict apps                   ○  (was the block-list UI; verb changed §5)
 ├─ Traffic (opt-in)               ○  (NetworkStatsManager, optional §6)
 ├─ Diagnostics                     ●
 └─ Standalone mode…               ○  (settings entry → Standalone hub)
      └─ Standalone hub             ○  (arm toggle + hard-block list live here)
```

**Mode switch UX (§3) lives at the `Standalone mode…` entry**, not on the main toggle. The main
screen has **no VPN switch at all** in Companion mode — this is the single biggest honesty win: the
primary surface can never imply we hold or want the slot.

### 2.1 Main screen (Companion cockpit) — screen-by-screen

Top-to-bottom, replacing today's header-Switch + three stacked OutlinedButtons (audit C flags the
button stack as eating ~25% of the screen):

1. **Header:** title "Net Audit", subtitle = live one-line posture summary
   (e.g. "Tailscale holds the VPN slot ✓ · DNS encrypted ✓ · 2 apps to review"). No switch.
2. **Posture card** (§6) — Tailscale/VPN-slot state, always-on/lockdown, Private DNS live state.
   Tapping opens Posture.
3. **Audit summary card** — "N apps hold remote-admin-class power" → Audit. Tri-state color.
   Empty/loading/all-acknowledged states preserved from today's AuditScreen quality.
4. **DNS card** — "Active: <provider> (DoT) ✓" or "System default — not encrypted" → DNS.
5. **Restrict card** — "N apps flagged to restrict" → Restrict apps.
6. **(opt-in) Traffic card** — present only if usage-access granted; else a dismissible
   "Enable traffic insights" affordance, never a dead chart.
7. **Tools row** (compact, replaces the button stack): Diagnostics · Standalone mode… · About.
8. **SuiteStatusFooter** (unchanged).

**States:** loading (skeleton cards), first-run (audit bulk-prompt as today, A5), a11y-warning
banner (A6, kept), empty (no findings → green "nothing needs attention"). No preempted banner in
Companion mode — that whole inverted-semantics banner (A2) is deleted from this surface (§8).

### 2.2 Standalone hub screen (new)

Reached only via `Standalone mode…`. Two visual regions:

- **Mode state region (top):** shows whether Standalone is ENABLED or DISABLED, and the guardrail
  verdict (§4). This is where the mode toggle and all guardrail copy live.
- **Engine region (below, only when Standalone ENABLED and guardrail PASS):** the engine arm switch,
  the hard-block app list (search + chips reused from today's app-list UI), and the honest drop
  counter. When guardrail FAIL, this region is replaced by the guardrail explanation card (§4) and
  the arm switch is absent (not disabled-and-present — absent, so there is no dead control).

---

## 3. Mode-switch UX (concrete)

**Entry:** Main → Tools row → `Standalone mode…` → **Standalone hub**.

**Enabling Standalone (STANDALONE) — the flow:**

1. User taps the **"Standalone blocking (no VPN)"** switch in the hub (default off).
2. App runs the **guardrail probe** (§4) *before* changing anything.
3. **If a VPN is active (guardrail FAIL):** the switch does **not** flip on. Instead show the
   guardrail card (§4 copy) and log to Diagnostics. Persisted mode stays COMPANION. This is the
   operator's steady state — honest, not an error tone.
4. **If no VPN is active (guardrail PASS):** show a **full-screen explanation dialog** (first time
   only; re-shown if dismissed without confirming):
   > **Standalone blocking uses Android's VPN slot**
   > This mode routes your blocked apps through a local, on-device tunnel to drop their traffic.
   > It uses the one Android VPN slot. **You can only use it if you are not running another VPN.**
   > If you later turn on Tailscale or any VPN, Standalone blocking will automatically stop and this
   > app will go back to audit-only mode. Your block list is kept.
   > [ Not now ]   [ Enable Standalone ]
5. On **Enable Standalone**: persist `mode = STANDALONE`. This does **not** arm the engine — it only
   reveals the engine region with the arm switch (default off). No `VpnService.prepare` yet.

**Arming the engine (inside Standalone, guardrail PASS):**

- User flips the **"Block enabled apps now"** engine switch.
- App re-runs the guardrail probe (slot state can change between enabling the mode and arming).
- PASS → walk the existing consent path (`VpnService.prepare` → consent launcher →
  `startVpn(ctx)`), reusing today's `requestVpnEnable` logic verbatim.
- FAIL → do not launch consent; flip switch back off; show guardrail card; the mode stays STANDALONE
  but engine cannot arm until the slot frees.

**Disabling Standalone:** flipping the mode switch off → `stopVpn(ctx)` if armed, persist
`mode = COMPANION`, keep the hard-block list persisted for next time. Instant, no dialog.

**Runtime eviction (Tailscale comes up while engine is armed):** `onRevoke` fires (§8). We persist
engine-armed=false, keep `mode = STANDALONE`, and set a neutral `lastAutoStopped=true` flag. Next
time the user opens the Standalone hub they see: "Standalone blocking stopped because a VPN
(<name-or-'another app'>) is now using the VPN slot. It will resume when you turn that VPN off." No
"Re-enable" nag, no mention of evicting anything.

---

## 4. The hard guardrail (detect active VPN, refuse to start)

This is the load-bearing safety mechanism. Two independent checks, ANDed; either one positive ⇒ FAIL
(refuse). Belt-and-suspenders because each has a blind spot.

### Check 1 — ConnectivityManager capability scan (primary, always available)

```kotlin
// New file: firewall/.../VpnSlotProbe.kt  (Companion-safe; no permissions beyond
// ACCESS_NETWORK_STATE which is already granted, manifest:52)
fun isAnotherVpnActive(ctx: Context): VpnSlotState {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    // Scan ALL networks, not just the active one: a VPN transport may be
    // present on a non-default network during handover.
    val holder = cm.allNetworks.firstOrNull { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
    ...
}
```

- `TRANSPORT_VPN` present **and** `NET_CAPABILITY_NOT_VPN` absent ⇒ a VPN network exists.
- We do **not** trust a single "active network" read — iterate `allNetworks` (audit B.1 recommends
  the TRANSPORT_VPN check; we harden it to all-networks).

### Check 2 — `VpnService.prepare()` return value (authoritative for slot ownership)

`VpnService.prepare(ctx)` returns:
- `null` ⇒ **we** already hold consent/slot (fine to (re)start our own engine).
- non-null Intent ⇒ consent is needed. This alone does **not** prove another VPN is active (it's
  also non-null on first-ever use). So `prepare()` is used to distinguish "we own it" from "we'd need
  consent"; it is **not** sufficient by itself to detect an incumbent.

**Combined verdict** (evaluated as `VpnSlotState`):

| Check1 (CM) | prepare() | Verdict | Meaning |
|---|---|---|---|
| VPN active | non-null | **FAIL — incumbent holds slot** | Tailscale etc. is up. Refuse. |
| VPN active | null | **FAIL — incumbent holds slot** | (rare) another VPN active but we have stale consent; still refuse — never race an incumbent |
| no VPN | null | **PASS — we own consent** | safe to (re)establish silently |
| no VPN | non-null | **PASS — free, consent needed** | safe to walk consent dialog |

Rule: **Check 1 (CM) is the veto.** If Check 1 says a VPN is active, we FAIL regardless of
`prepare()`. `prepare()` only decides, on a PASS, whether we need to show the consent dialog.

### Package attribution (advisory only — never gates the refusal)

To name the incumbent in copy ("…because Tailscale is using the slot"), read
`Settings.Secure "always_on_vpn_app"` (permissionless read; may be empty for a non-always-on VPN like
a manually-started Tailscale) and/or check `com.tailscale.ipn` presence via `getPackageInfo`. **This
requires adding `com.tailscale.ipn` to `<queries>`** (audit D9, SUITE §1 — Tailscale is currently
absent from firewall's `<queries>`, so even `getPackageInfo` fails under package-visibility
filtering). If the package/name can't be resolved, degrade copy to "another app" — never block on
attribution, never claim a name we didn't verify (CD-4 honesty).

Add to `<queries>` (manifest:147-174):
```xml
<package android:name="com.tailscale.ipn" />
```
Optionally the common consumer-VPN packages for nicer copy (ProtonVPN, Mullvad, WireGuard, etc.), but
these are cosmetic; the CM veto does not depend on them.

### Where the guardrail runs (three enforcement points — all must call it)

1. **Enabling Standalone mode** (§3 step 2).
2. **Arming the engine** (§3, before `VpnService.prepare`).
3. **A live slot-watcher while armed** — register a `ConnectivityManager.NetworkCallback` with a
   `NetworkRequest` for `TRANSPORT_VPN` in `FirewallVpnService` (or a small companion observer). If a
   VPN network appears that is not ours, proactively `stopVpn()` + set `lastAutoStopped` **before**
   the system's own `onRevoke` fires, so our teardown is graceful and our persisted state is correct
   even on OEMs where `onRevoke` timing is flaky. `onRevoke` remains the backstop (§8).

The guardrail's refusal path must be **fail-closed**: any exception in the probe ⇒ treat as FAIL
(assume an incumbent) and refuse. Rationale: a false refusal costs the no-VPN user one retry; a false
PASS could evict Tailscale — the one thing the doctrine forbids.

---

## 5. Feature dispositions (every audited feature)

Verb key: **FIX** = make current design work · **REDESIGN** = different mechanism · **DROP** =
remove honestly from UI, note replacement.

| Audit | Feature | Disposition | Detail |
|---|---|---|---|
| A1 | VPN arm + per-app blocklist drop | **REDESIGN** | Engine survives *inside Standalone mode only*, gated by §4. In Companion it does not exist. `FirewallVpnService.startVpn()` app-drop path reused verbatim; only its *reachability* changes. |
| A2 | Preempted banner + "Re-enable" | **DROP** (from main) / **REDESIGN** (in Standalone) | Deleted from Companion main surface. In Standalone, replaced by the neutral `lastAutoStopped` message (§3), no eviction nag. |
| A3 | App-list UI (search/chips/icons/stale pills) | **FIX/keep** | Substrate for BOTH the Companion Restrict list and the Standalone hard-block list. Add 48dp targets + TalkBack semantics (SUITE #9). Fix nothing mechanical — it's good. |
| A4 | Drop counter | **REDESIGN** | See §9. Shown ONLY in Standalone-armed, fed by the real tun reader. Absent in Companion (no dead "0 packets" line). |
| A5 | Remote-admin audit | **FIX/keep** | Crown jewel, unchanged mechanism. Two fixes: (1) `MODE_DEFAULT → checkPermission` fallback in `RemoteAdminAudit.opGranted` (audit A5 caveat, D10) to kill overlay/usage-stats false-negatives; (2) the per-finding **"Block" switch is relabelled** — in Companion it becomes **"Restrict"** (deep-links to `ACTION_APPLICATION_DETAILS_SETTINGS`), in Standalone it can additionally offer **"Hard-block"** (adds to the engine block list). No dead promise (audit D12). |
| A6 | Third-party a11y warning banner | **FIX/keep** | Rootless, survives. Move into the Posture card + keep the main-screen banner. |
| A7 | DNS provider select + DoT apply | **FIX/keep** | Flagship companion feature. Keep applier + live read-back. Fix NextDNS `<your-config>` placeholder: add a config-ID input field, else the entry writes garbage (audit C, D11). Drop DNSCrypt entries (A8/§7). |
| A8 | DNSCrypt providers + bundled proxy | **DROP** | Remove the 3 DNSCRYPT entries from `DnsProvider.ALL` (or eng-flavor-gate). Remove `DnsCryptProxyService` + its manifest `<service>` (223-250 → the dnscrypt one) from prod. Nothing can query a local resolver without the tun DNS-redirect path, which is itself Standalone-only and not wired to DNSCrypt (§7). Replacement promise: DoT via Private DNS already covers encrypted DNS honestly. |
| A9 | DNS-redirect preview (DnsRedirector + parser) | **DROP from UI / salvage as library** | Not surfaced in either mode (see §7 & §10). Parser + redirector become the salvaged library; not invoked by the shipping engine, which uses the plain app-drop path only. |
| A10 | Misleading "N blocked" during DNS-redirect | **FIX (moot)** | The DNS-redirect mode is removed (§7), so the mislead can't occur. While the code still compiles, the drop-counter/notification honesty rules in §9 apply. |
| A11 | Custom port blocking (`/proc/net`) | **DROP** | Structurally a no-op on 100% of minSdk-33 devices (`PortBlockDiscovery.kt:44-50`). Remove `PortBlocksScreen`, `PortBlockDiscovery`, the scanner thread in `FirewallVpnService` (the `portScannerThread` block :179-202, :188-199), and `K_BLOCKED_PORTS`/`getBlockedPorts`/`setBlockedPorts`. Keep the file's limitation notes as a doc comment in the salvage library. Replacement: none promised (port UI is gone). |
| A12 | Overlay routing (I2P/Lokinet/Ygg) | **DROP** | Remove `OverlayRoutingScreen`; drop `overlay-i2p/lokinet/yggdrasil` deps from `build.gradle.kts:87-89` and the `include`s. I2P "live status" is cross-process-impossible; Yggdrasil is itself a VpnService transport (vetoed). Keep `FirewallSettings` overlay keys dormant (harmless) or delete — implementer's choice; prefer delete for zero dead prefs. |
| A13 | Posture screen | **REDESIGN** | Rewrite to coexistence (§6). Delete "turn firewall off to run another tunnel" copy (`MainActivity.kt:1381-1388`) and manifest comment (36-49). Add "works alongside Tailscale" section + Standalone-mode explainer. |
| A14 | Suite integration (caps/footer/tamper/attest) | **FIX/keep** | Keep. Fix `${applicationId}.suitecaps` authority for eng/prod (SUITE #10 — firewall hardcodes prod authority at manifest:259). Update the caps beacon: drop the `NETWORK_FILTER` capability claim in Companion; advertise it **only** when `mode==STANDALONE && engine armed` (honest, dynamic — SUITE §4.4, CD-4). |
| A15 | Screen-capture / tap-jack hardening | **FIX/keep** | Keep as-is. Best-in-suite. |
| A16 | Diagnostics screen | **FIX/keep** | Keep. Log guardrail verdicts + mode transitions here. |

---

## 6. New / redesigned Companion features (concrete)

### 6.1 Tailscale-coexistence posture panel (NEW — replaces A2's inverted banner)

New `PostureCard` on main + expanded Posture screen. Data sources (all rootless):
- **VPN-slot state:** `VpnSlotProbe` (§4 Check 1) → "VPN slot: held by Tailscale ✓" /
  "held by <name>" / "held by another app" / "free".
- **always-on / lockdown:** read `Settings.Secure "always_on_vpn_app"` and
  `"always_on_vpn_lockdown"` (permissionless; keys undocumented → verify on One UI, degrade to
  "unknown" honestly per audit B.1).
- **Private DNS live state:** existing read-back (A7) → "DNS: encrypted via <host> ✓" / "not
  encrypted".
- **Deep-link:** `ACTION_VPN_SETTINGS` button "Open VPN settings" (never a toggle we own).

Copy tone: Tailscale holding the slot renders **green/positive** ("✓ coexisting"), never as a fault
(CD-2d).

### 6.2 Per-app Restrict worklist (REDESIGN of the block-list, A3)

The old blocklist becomes an **"apps to restrict"** list in Companion. Each row's action deep-links
`ACTION_APPLICATION_DETAILS_SETTINGS` (or the data-usage screen) so the **OS** enforces background
data restriction. Persist the flagged set (reuse `K_BLOCKLIST` semantics but rename to
`K_RESTRICT_LIST` conceptually; can share the same stored set as the Standalone hard-block seed).
Honest framing: "Restrict = one tap to Android's own per-app data controls; we don't intercept
traffic in this mode."

### 6.3 Per-UID traffic accounting (NEW, optional — opt-in usage-access)

`NetworkStatsManager` behind the user-opt-in `PACKAGE_USAGE_STATS` grant (Special app access → Usage
access). Per-app rx/tx, "talked while you weren't using it" reports. **Graceful degradation:** no
grant ⇒ a deep-link card ("Enable traffic insights"), never a dead chart (CD-2b). This is the honest
rootless answer to "which apps should I worry about" — observation, not interception. Optional for
V2; can ship in a later increment without blocking the mode redesign.

### 6.4 Opt-in canary probes (NEW, optional)

INTERNET is already held (manifest:51). Explicit-tap-only leak/resolver-identity/egress-IP checks
with the target hosts **named on the button** to preserve the no-telemetry posture (audit B.6, D14).
Optional; gate behind Diagnostics or an "Advanced checks" card. Not required for V2 core.

---

## 7. How DNS-redirect / port-blocking are dropped (mechanics)

- **DNS-redirect mode (A9/A10):** the `startVpnDnsRedirectMode()` branch
  (`FirewallVpnService.kt:157-163, 337-390`) is **removed from the shipping engine**. Standalone's
  engine has exactly one mode: app-drop. Delete the `dnsProvider?.protocol == DNSCRYPT` dispatch, the
  `DnsRedirector` field, and the `FAKE_DNS_IP` route path. This eliminates the A10 mislead by
  construction (there is no mode where blocking is silently paused). `DnsRedirector` + `VpnPacketParser`
  move to the salvage library (§10) but are **not called** by the app.
- **Port blocking (A11):** delete `PortBlocksScreen`, `PortBlockDiscovery`, the `portScannerThread`
  block (`FirewallVpnService.kt:179-202`) and its per-scan re-establish, and the `K_BLOCKED_PORTS`
  storage. The engine's block set is now exactly the user's hard-block package list —
  no `/proc/net`-derived additions. This also removes the `lastPortDerived` field and the
  `PORT_SCAN_INTERVAL_MS` constant. Cosmetic: the unreachable `465` branch dies with the screen.

Net effect: the salvaged engine is *simpler and more honest* than today's — one mode, one block set,
one counter.

---

## 8. State persistence & `onRevoke` semantics

### Persisted keys (`FirewallSettings`)

| Key | Meaning | Notes |
|---|---|---|
| `K_MODE` (new) | `COMPANION`\|`STANDALONE` | default COMPANION. The mode gate. |
| `K_ENGINE_ARMED` (renamed from `K_VPN_ENABLED`) | engine arm request | only meaningful in STANDALONE. In COMPANION always effectively false. |
| `K_AUTO_STOPPED` (new, replaces `K_VPN_PREEMPTED`) | engine auto-stopped because a VPN took the slot | neutral flag; drives the neutral Standalone-hub message, NOT a nag. Cleared on next successful arm or on mode-disable. |
| `K_RESTRICT_LIST` (was `K_BLOCKLIST`) | apps flagged in Companion Restrict + seed for Standalone hard-block | shared set; both modes read it. |
| `K_DNS_PROVIDER` | DoT provider id | DNSCrypt ids removed from catalog. |
| `K_FIRST_RUN_AUDIT_DONE`, `K_AUDIT_ACKNOWLEDGED` | unchanged | audit flow. |
| `K_STANDALONE_EXPLAINED` (new) | user saw the full-screen explainer | so we don't re-nag after confirm. |
| **removed** | `K_BLOCKED_PORTS`, `K_OVERLAY_*` | dropped with A11/A12. |

### `onRevoke` redesign (`FirewallVpnService.kt:100-126`)

Under this design `onRevoke` can fire **only** while the engine is armed in Standalone (Companion
never establishes a tun). Rewrite:
```
onRevoke():
  FirewallSettings.setEngineArmed(this, false)     // UI never claims we're filtering
  FirewallSettings.setAutoStopped(this, true)       // neutral, not "preempted"
  // mode stays STANDALONE — the user hasn't left the mode, a VPN just took the slot
  stopVpn(); stopForeground(REMOVE); stopSelf()
```
No `vpnPreempted`, no banner data, no "Re-enable" intent. The Standalone hub reads `autoStopped` and
renders the neutral resume-when-you-turn-that-VPN-off message (§3). The slot-watcher (§4 point 3)
usually beats `onRevoke` to the punch, making teardown proactive; `onRevoke` is the backstop.

### Main-screen honesty (fixes A10 by construction)

- **Companion:** no engine, no counter, no "N blocked" — the subtitle is the posture summary. The
  header `Switch` and the `if (vpnEnabled) "${blocked.size} app(s) blocked"` line
  (`MainActivity.kt:366-378`) are **deleted** from this surface.
- **Standalone-armed:** subtitle = "Blocking N app(s)" only when the tun is actually established with
  N>0 allowed apps; counter per §9.
- **FGS notification** (`FirewallVpnService.kt:426-435`): text must reflect the *actual established
  block count* (the `added` count from `startVpn`, not `getBlockedPackages().size`), so an
  all-uninstalled idle tun never claims "12 blocked". Since DNS-redirect is gone, the notification
  has one honest form.

---

## 9. The drop counter — what it honestly shows in each mode

`DropStats` follows the engine (audit salvage note). Rules:

- **Companion mode:** the counter is **not rendered at all**. There is no packet interception, so a
  "0 packets dropped" line would be a false-adjacency implying enforcement. Absent = honest.
- **Standalone, mode enabled but engine disarmed:** not rendered (nothing running).
- **Standalone, engine armed, tun established (added>0):** rendered as today
  ("dropped N packets · Xs ago"), fed by the live tun reader (`FirewallVpnService.kt:310`). This is
  the only truthful place it can appear.
- **Standalone, engine armed, but tun idle** (empty/all-uninstalled block list — the audit's
  idle-mode guard, `:204-221`/`:250-261`): show "Armed · no apps blocked yet" and **no** counter —
  do not show "0 dropped" as if enforcement were happening; there is no tun.
- **Counter lifecycle** (unchanged, `:403-406`): resets on full stop, persists across rule-change
  re-establishes. `DropStats` remains in-process (VpnService shares the app process); no IPC needed.

The counter never appears on a surface where blocking is paused or absent — that is the direct
remedy for A10, now enforced structurally rather than by copy.

---

## 10. Salvage-as-library (special charge)

Create a new **library module** so the packet code survives cleanly and is unit-testable without the
app. Placement options — pick per suite convention:

- **Preferred:** new `understory-common` module **`net-engine`** (`com.understory.net.engine`),
  vendored into firewall like `common-security`. Add to `settings.gradle.kts` (`include(":net-engine")`)
  and `firewall/build.gradle.kts` (`implementation(project(":net-engine"))`).

Contents and verdicts (from audit "Salvage-as-library"):

| File | Move to | Status in shipping app |
|---|---|---|
| `VpnPacketParser.kt` | `net-engine` (pure JVM: only `java.net`; bounds-checked; RFC-768 zero-checksum rule `:233`) | **compiled, unit-tested, NOT called** by the shipping engine (which uses plain app-drop). Available for a future userspace forwarder. |
| `DnsRedirector.kt` | `net-engine` (depends on `VpnService.protect`, so it stays with the optional engine, not common-security) | compiled, **not called** (DNS-redirect mode removed §7). |
| `FirewallVpnService.kt` | stays in the app (it's an Android `Service` + manifest component), but its packet-handling helpers can depend on `net-engine` | the Standalone engine; app-drop path only. Keep its hard-won correctness: atomic tun swap (`:287-321`), all-uninstalled guard (`:250-261`), `onRevoke` persistence (redesigned §8), specialUse-FGS lesson (manifest:215-221 + `SAMSUNG_QUIRKS.md:63-77`). |
| `DropStats.kt` | with the engine (trivial) | live only in Standalone-armed. |
| `PortBlockDiscovery.kt` | **do not salvage** | structurally dead on all supported API levels. Keep its limitation header as a `net-engine` doc comment for the record. |

Add JVM unit tests for `VpnPacketParser` in `understory-common/tests` (parse valid/truncated IPv4+UDP,
checksum incl. zero-rule) — it's pure and testable today; locks the salvage value.

---

## 11. Manifest & build deltas (summary for the implementer)

- **`<queries>`:** add `<package android:name="com.tailscale.ipn" />` (§4 attribution, audit D9).
  Optionally common consumer-VPN packages (cosmetic).
- **VpnService `<service>` (223-234):** **keep** — Standalone needs it. Rewrite the misleading
  comment ("owns the VpnService slot") to the dual-mode/guardrail story (audit D7). The service is
  registered but only *activated* under the guardrail; a registered-but-dormant VpnService does not
  hold the slot (holding requires an established session), so its mere presence is doctrine-safe.
- **DnsCrypt `<service>` (243-250):** **remove** from prod (§7). Delete
  `tools/fetch-dnscrypt-proxy.sh` reference from docs.
- **`build.gradle.kts`:** drop `overlay-i2p/lokinet/yggdrasil` deps (:87-89) and their `include`s in
  `settings.gradle.kts`; add `net-engine`. Bump `versionName` off "0.1-skeleton".
- **SuiteCaps authority (259):** `${applicationId}.suitecaps` (SUITE #10 eng/prod collision).
- **Portrait lock (200) / activity-isolation (192-193 RELEASE-BLOCKER comment):** revisit per
  SUITE #9; at minimum reconsider portrait lock and resolve the stale comment.
- **GUI debt (SUITE #9, audit C):** externalize strings to `res`, replace ~60 hex literals with
  `MaterialTheme.colorScheme` tokens (shared theme in common-security), M3 Scaffold/TopAppBar,
  TalkBack semantics on toggle rows + labelled interactive glyphs, 48dp touch targets. These are
  shared-component fixes inherited suite-wide, not firewall-specific logic.

---

## 12. Doctrine compliance check

- **CD-1 (complement):** Companion adds value beside Tailscale (audit + DNS + posture) with the slot
  untouched. Standalone's value does NOT depend on evicting the incumbent — it's explicitly for users
  with *no* VPN, and refuses when one is present. ✓
- **CD-2 (slot policy):** VPN slot never required for core value; offered as an explicit opt-in that
  degrades gracefully (guardrail FAIL → honest greyed state, full Companion value retained); incumbent
  taking the slot is rendered neutrally/positively, never a fault. ✓
- **CD-4 (honest UI):** no dead controls (guardrail-FAIL hides the arm switch rather than
  disabling-with-dead-tap; Companion shows no counter/no VPN switch); `NETWORK_FILTER` capability
  advertised only when actually filtering; drop counter only where enforcement is real; auto-stop
  message truthful, no eviction nag. ✓

**Non-negotiable invariant:** the CM `TRANSPORT_VPN` veto is fail-closed and cannot be weakened by any
caller field or `prepare()` result. On the operator's Tailscale device this makes Standalone
permanently unreachable, and the app is, in effect, the pure companion — which is the correct outcome.
