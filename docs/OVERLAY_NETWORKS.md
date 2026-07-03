# Overlay-network integration plan

Living design doc. The suite intentionally **does not** integrate Tor —
that's an opinionated choice made by the project owner, not a technical
constraint. This file captures the architecture for everything else:
how I2P, Yggdrasil, Lokinet, and DNSCrypt would land alongside the
existing firewall + browser, what's tractable today, and what depends
on future engineering.

## The architectural constraint that frames everything

**Android allows exactly one active VpnService at a time.** The
firewall already owns that slot — its `FirewallVpnService` establishes
the tun that drops outbound traffic from the user's blocklist. Any
other suite component that wants to capture or rewrite packets at the
device level is fighting for the same slot.

This constraint partitions the candidate networks into two camps:

### Camp 1 — userspace proxy, no VpnService needed

A network that exposes a localhost HTTP / SOCKS proxy can be consumed
by **the browser alone** without claiming the VpnService slot. The
browser's `WebView` can be pointed at the proxy via
`androidx.webkit.ProxyController`, and traffic outside the browser
process is unaffected. The firewall's tun keeps running.

| Network | How it'd run | Browser hook |
|---|---|---|
| **I2P** (i2pd) | Foreground `Service` in browser process spawns `i2pd` binary; binary listens on `127.0.0.1:4444` (HTTP) + `:4447` (SOCKS) | `ProxyController.setProxyOverride("127.0.0.1:4444")` when the user's "Route via I2P" toggle is on |
| **Tor** (excluded) | (same shape — Brave does this with a tor binary; we don't.) | — |

This is the **first phase** because it's the only camp that doesn't
require touching the firewall's VpnService.

### Camp 2 — needs a TUN device

A network that issues its own IPv4/IPv6 namespace, or rewrites packets
across all apps, fundamentally needs a TUN. On Android that means
VpnService — and the firewall already holds the slot.

| Network | Why it needs a TUN | Conflict with firewall? |
|---|---|---|
| **Yggdrasil** | Self-organising encrypted IPv6 mesh; assigns each peer a `200::/7` address and routes via tun | Yes |
| **Lokinet** | Onion-style routing using Service Nodes; routes `.loki` and SOCKS via tun | Yes |
| **cjdns / Hyperboria** | IPv6 mesh with cryptographic addressing | Yes |
| **DNSCrypt** | Encrypted DNS at device level via VpnService DNS forwarding | Yes (for system-wide DNS) |

For Camp 2 the only honest path is to **multiplex through the firewall's
existing VpnService**. That's the architecture Invizible Pro arrived at
(Tor + I2P + DNSCrypt, all sharing one tun via packet parsing). The
work involved:

1. Promote `FirewallVpnService` from a drop-only tun reader to a
   userspace IP stack: parse IPv4/IPv6 + TCP/UDP headers, maintain a
   per-flow state table.
2. Per-flow routing decision: drop (user blocklist), forward to
   userspace handler (Yggdrasil / Lokinet / DNSCrypt), or pass-through
   (default).
3. Userspace egress: handlers re-emit traffic via Android's own network
   stack (the firewall's protectedSocket fallback).

This is **NetGuard-class work** — weeks not days, and it changes the
firewall's posture from "drop dead-stop" to "userspace IP stack." It's
phase 2 of the firewall's own roadmap (already noted in
`RELEASE_BLOCKERS.md` under the DNS-in-VPN forwarding bullet); the
Camp 2 networks land alongside that work, not before.

## The proxy chain — composing firewall + browser proxies

The firewall and the browser sit at **different points in the
network egress path**, and the toggles at each point compose.
Drawing the chain explicitly so the design intent is clear:

```
   ┌────────────────────────────┐
   │   Browser process          │
   │   • WebView                │
   │   • optional ProxyController override:                           ┐
   │       ─→ 127.0.0.1:4444  (I2P, when "Route via I2P" is on)        │
   │       ─→ direct           (default)                               │
   └─────────────┬──────────────┘                                       │
                 │                                                      │ Browser-level
                 ▼                                                      │ chain step
   ┌────────────────────────────┐                                       │ (optional)
   │  127.0.0.1:4444 i2pd       │                                       ┘
   │  (or other localhost      ◀── Layer A: per-browser-session
   │   userspace proxy)             overlay choice
   └─────────────┬──────────────┘
                 │ egress (TCP/UDP to peers)
                 ▼
   ┌────────────────────────────┐                                       ┐
   │  Android network stack     │                                       │
   │  • per-app routing decision (firewall VpnService, if armed)        │ Device-level
   │       ─→ drop                (blocked app)                         │ chain step
   │       ─→ route via overlay   (phase γ, e.g. via DNSCrypt for DNS)  │ (optional)
   │       ─→ pass-through        (default)                             │
   └─────────────┬──────────────┘                                       ┘
                 │
                 ▼
              Real network
```

**The two toggles are independent and orthogonal.** A user can run:

- Firewall **on** + browser I2P **off** — apps are gated, browser
  uses the normal network. Today's behavior plus blocklist.
- Firewall **off** + browser I2P **on** — every app uses the normal
  network unaffected, but the *browser* tunnels through I2P. The
  firewall isn't claiming the VpnService slot, so a phase-γ network
  like Yggdrasil's official Android client could also be running
  alongside (it'd take the slot the firewall isn't using).
- Firewall **on** + browser I2P **on** — both. The browser's traffic
  reaches `127.0.0.1:4444`, which then egresses through Android's
  normal network stack, which the firewall is filtering. If the
  browser app itself is on the firewall's blocklist (don't do this),
  i2pd's egress would also be dropped — the chain composes
  consistently. Future phase γ can route i2pd's egress through
  Yggdrasil if both are configured, giving I2P-over-Yggdrasil with
  no extra wiring.
- Firewall **off** + browser I2P **off** — degenerate / "do nothing"
  case. Useful for diagnosing which layer is responsible for a
  particular failure.

The "complementary chain" framing matters because it tells us where
*not* to fight the architecture: we don't try to make the browser's
proxy decisions visible to the firewall's per-app rules (they're at
different layers). We don't try to make the firewall's overlay
forwarding (phase γ) visible to the browser (the WebView already
gets whatever network policy the OS hands it). Each layer owns its
own decisions and exposes them honestly to the user; the user
chains them by configuring both.



The structural posture the suite already enforces today, made
explicit:

- **No remote admin path.** The firewall has no exported
  `BroadcastReceiver`, no exported `Service` that accepts control
  intents from other UIDs, no IPC server bound to a TCP socket. Its
  `SuiteCapsProvider` is read-only and signature-locked. The only way
  to control the firewall is via the in-app UI on the unlocked device.
- **No telemetry.** No analytics SDK, no crash reporter cloud service,
  no feature-flag service.
- **No cloud.** No app in the suite — including the firewall — calls
  out to any remote server. `INTERNET` permission is requested only by
  the firewall (for VpnService.protectedSocket fallbacks) and the
  browser (which is the user's own web traffic, not ours).
- **One-VPN-slot exclusivity.** A consequence of the architecture
  above: the firewall is the canonical owner of the VpnService slot
  and refuses to share. To run Yggdrasil / Lokinet / DNSCrypt you
  must either (a) wait for phase-2 firewall multiplexing, or (b)
  disable the firewall and run the other tunnel as a standalone.

The firewall's UI gets a **Posture** screen that lays this out
verbatim. The "disengage" path is honest: there is no remote-admin
mode to enable; "disengage" means turning the firewall off so another
VpnService-owning app (Yggdrasil official client, Lokinet official
client, etc.) can claim the slot.

## "Server-lite" mode

User design intent: by default we participate as a **client only** in
each overlay network — we do not relay traffic for others, do not run
floodfills, do not advertise as a service node. An opt-in toggle per
network would enable participation.

Network-by-network:

| Network | Default (client-only) | Opt-in (relay/server) |
|---|---|---|
| **I2P** | `floodfill = false`, no shared transit tunnels | `floodfill = true`, shared transit tunnels enabled |
| **Yggdrasil** | Leaf node, no `MulticastInterfaces`, no public peer advertising | Add public peers, advertise as transit |
| **Lokinet** | Client mode (`relay = false`) | Service Node mode requires Oxen stake; out of scope for this suite |
| **DNSCrypt** | Client only (no `dnscrypt-proxy` listening on public IP) | N/A — DNSCrypt doesn't have a "server" meaning that fits a phone |

The opt-in toggles live in each overlay's UI surface, not in a global
setting — the threat model differs per network and the user should
make each call explicitly.

## Provider catalogs — we ship the list, not the service

For each overlay network the suite ships a **curated catalog of
public providers**, same shape as the firewall's
`DnsProvider` enum (`Cloudflare`, `Quad9`, `Google`, `OpenDNS`,
`NextDNS`, plus `Custom`). User picks one or none. We do not host
any of these — the catalog is a list of public references with the
same provenance you'd find in upstream documentation.

What goes in each catalog:

- **I2P entry points** — well-known reseed servers (`reseed.i2p-projekt.de`,
  `reseed.diva.exchange`, etc.) and well-known eepsite HTTP outproxies
  (`exit.stormycloud.org` etc.) Those are the I2P equivalents of "DNS
  providers": they're the network's published front doors.
- **Yggdrasil public peers** — the published peers list at
  `publicpeers.neilalexander.dev`. Pick a region; the suite hands the
  selected URL list to the bundled Yggdrasil node. (Phase γ.)
- **Lokinet bootstrap files** — the published bootstrap RC files.
  (Phase γ.)
- **DNSCrypt resolver list** — the curated `dnscrypt-resolvers` list,
  same one Adguard / Invizible Pro use upstream. (Phase γ.)

Each catalog entry includes the same shape as `DnsProvider`: a stable
`id`, display `name`, the configuration string the network's daemon
needs (URL / public key / bootstrap file), and an honest `privacyNote`
about the operator. Each catalog also ships a `Custom` entry so the
power user can enter their own config — we don't gatekeep what they
trust, we just save them the typing for the common cases.

**What we never do:**

- We never run our own I2P reseed server, our own Yggdrasil peer, our
  own Lokinet introducer, our own DNSCrypt resolver. There is no
  "understory.example" provider and there will not be one. The suite
  is a configuration surface, not infrastructure.
- We never mark a default provider as "recommended." The catalog is
  alphabetical / chronological; the user reads the privacy notes and
  picks.

This keeps the suite **lightweight** in the way the project owner
asked for: not a heavyweight thing like a Brave-fork bundling Tor
binaries plus running discovery infrastructure. The browser becomes
a UI for choosing which already-public provider the user wants to
chain through, with the actual network plumbing sitting in the
overlay modules.

## Phasing

**Phase α (now):** scaffolding + design.

- This document.
- `:overlay-i2p` library module with the service-supervisor + status
  contract. The actual `i2pd` binary is **not** bundled in this
  phase — production builds will add it via NDK cross-compile (see
  `BUILD_RECIPE.md` inside the module).
- Browser integration: a per-session "Route via I2P" toggle that
  starts the `:overlay-i2p` service and applies the proxy override
  when it reports `READY`. With no binary present, status is
  permanently `BINARY_MISSING`, the toggle stays disabled, and the
  user sees an honest message rather than a broken proxy.
- Firewall **Posture** screen documenting the blind-firewall claim
  + the "disengage" mechanism.

**Phase β (next):** I2P real.

- Cross-compile `i2pd` for `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- Ship as `jniLibs/<abi>/libi2pd.so` (Android extracts `lib*.so`
  files from the APK to the app's `nativeLibraryDir`, bypassing the
  general "no executables" extraction restriction).
- Enable client-only configuration by default; expose
  participate-in-network toggle behind an explicit opt-in.

**Phase γ (after firewall multiplexing lands):** Camp 2 networks.

- Yggdrasil-go bundled via gomobile or as an executable.
- Lokinet-android bundled.
- `dnscrypt-proxy` for encrypted DNS resolution.
- All three share the firewall's VpnService through a per-flow
  routing table.

**Phase δ (much later):** server-mode opt-ins.

- I2P floodfill / participating router toggle.
- Yggdrasil public-peer advertising toggle.
- Per-network bandwidth caps, per-network logging posture.

## Browser as firewall companion

The browser and the firewall are **deliberately paired**. They share
the same signing certificate (the suite-wide cert pin), the same
`SuiteAttestation` cross-verification, and now (going forward) the
same overlay-network state. The user-visible expression of this
pairing is documented here so the design intent is durable:

1. **Default-browser handoff (phase β).** The firewall's Posture
   screen offers a one-tap "Set understory browser as default" that
   walks the user through Android's `RoleManager.ACTION_REQUEST_ROLE`
   for `RoleManager.ROLE_BROWSER`. The mechanism is exactly how
   Trustd / DuckDuckGo / Brave acquire the browser role; we use it
   because making the suite's browser the OS-wide URL handler closes
   a leak (an http(s) intent fired from another app no longer falls
   into Chrome or the system browser, where the firewall's posture
   doesn't apply).

2. **Shared overlay-proxy state (phase β/γ).** The `:overlay-i2p`
   service exposes a process-shared status singleton. Both the
   firewall and the browser observe it. Concretely:
   - When the browser arms I2P, its service supervises the i2pd
     binary. The firewall's Posture screen + Diagnostics surface
     reflect that I2P is up and on which port.
   - When phase γ lands the firewall's userspace IP stack, the
     firewall can route specific blocked-or-allowed apps through
     the same `127.0.0.1:4444` proxy by translating their TCP
     egress to a SOCKS / HTTP-CONNECT call. The browser's I2P
     toggle and the firewall's per-app I2P route compose without
     duplicating the supervisor.
   - Either side can be the **one** that brings the supervisor up;
     the other observes. There is no master/slave in the pairing —
     whichever app the user activates the toggle on is the owner
     for that session.

3. **Companion-only link surfaces.** Capability-registry entries
   already expose per-app version + pinned-cert through
   `SuiteCapsProvider`. Going forward, the firewall and browser add
   each other to the relevant suite-companion lists (the firewall's
   "this is paired with" line on the Posture screen; the browser's
   Diagnostics surface listing the firewall's VpnService state).
   No app outside the suite cert pin can read these — the
   `READ_CAPS` permission is signature-protected.

The point of writing this down: the suite is **not** a collection of
seven independent apps that happen to share a designer. It's a paired
toolkit where the firewall + browser have explicit affinity, and
phases β+ build on that affinity rather than re-introducing
independence.

## Per-network detail

Each candidate network considered for the suite, in the depth needed
to plan the integration work. Tor is **excluded by project decision**
and not analyzed here — users who want Tor should install Orbot or
Tor Browser.

### I2P

| Property | Value |
|---|---|
| Upstream | https://github.com/PurpleI2P/i2pd · https://geti2p.net/ |
| License | BSD-2-clause (i2pd C++) · BSD-3-clause (Java I2P) |
| Daemon model | Userspace router; exposes localhost HTTP + SOCKS proxies |
| Native build | NDK CMake; ~12 MB per ABI; pre-existing `android/build_locally.sh` |
| Bootstrap time | 60–90 s on first launch (peer discovery + tunnel build) |
| Battery profile | Moderate when idle (keepalives), high during active tunnel use |
| Anonymity claim | Garlic routing with bidirectional tunnels; resistant to traffic analysis at small-N |
| Server-mode opt-in | `floodfill = true` plus shared transit tunnels; deliberate user act |
| Status | Active, multi-decade project, well-maintained |

**Why it lands first.** I2P's daemon exposes a localhost HTTP proxy
that `androidx.webkit.ProxyController` can be pointed at. No
VpnService claim, no firewall conflict, no kernel module dependency.
The browser is the ideal first consumer; firewall consumption (per-
app HTTP/CONNECT routing) is a phase-γ multiplexing question, not a
phase-β blocker.

**Known limitations.** No UDP routing through the HTTP proxy (UDP
tunneling exists in I2P but not via the standard HTTP/SOCKS bridges
we'd use). DNS lookups for `.i2p` hosts go through the daemon's
internal addressbook; non-`.i2p` lookups must go through an outproxy
operator. Users who pick "Eepsites only" intentionally lose
non-`.i2p` reachability.

### Yggdrasil

| Property | Value |
|---|---|
| Upstream | https://github.com/yggdrasil-network/yggdrasil-go |
| License | LGPLv3 |
| Daemon model | Self-organising encrypted IPv6 mesh; **requires a TUN device** |
| Native build | gomobile-style; produces `.aar` library or executable; ~8 MB per ABI |
| Bootstrap time | Sub-second once peers are configured |
| Battery profile | Low — a small keepalive set per peer; no per-flow wake |
| Anonymity claim | Encrypted at the link layer; **not anonymous** (peer addressing is cryptographic but stable) |
| Server-mode opt-in | Advertise as a peer via `MulticastInterfaces` or a public peers list |
| Status | Active, in production use across several mesh communities |

**Why it's phase γ.** Needs a TUN. The firewall's VpnService owns
the only slot. Two paths forward:

1. **Standalone-only.** User turns the firewall off; the official
   Yggdrasil Android client (or a stripped-down embedding) claims
   the slot. Suite browser gets Yggdrasil reachability when this
   client is up. The firewall and Yggdrasil are mutually exclusive.

2. **Multiplexed (the right answer).** The firewall promotes its
   VpnService from a drop-only tun reader to a userspace IP stack.
   Per-flow routing decision: drop / route-to-userspace-handler /
   pass-through. The Yggdrasil daemon runs as a userspace handler
   that the firewall's tun forwards selected packets into. Same
   architecture Invizible Pro uses. Real engineering — weeks not
   days — and it's the same lift required for Lokinet + DNSCrypt
   so the cost amortizes across all three.

**Known limitations.** Cryptographic addressing reveals the peer
key in every IPv6 destination. Mixed claim with anonymity sets;
suitable for "encrypted reachability between known peers" but not
"unlinkable identity over the network."

### Lokinet

| Property | Value |
|---|---|
| Upstream | https://github.com/oxen-io/lokinet |
| License | GPLv3 |
| Daemon model | Onion routing with Service Nodes; **requires a TUN device** |
| Native build | CMake; ~15 MB per ABI; existing Android target in upstream `android/` |
| Bootstrap time | 5–15 s in steady state; longer for initial bootstrap |
| Battery profile | Moderate (more like Tor than Yggdrasil); keepalive plus per-flow tunneling cost |
| Anonymity claim | Onion routing similar in shape to Tor (3-hop circuits); resistant to single-relay observation |
| Server-mode opt-in | Service Node mode requires Oxen stake; **out of scope for this suite** |
| Status | Active, smaller relay set than Tor (~hundreds of nodes vs Tor's thousands) |

**Why it's phase γ.** Same TUN constraint as Yggdrasil. Same
multiplexing answer. Lokinet's `.loki` namespace + SOCKS5 surface
is consumer-friendly once the multiplexed firewall lands.

**Known limitations.** Service Node operator set is smaller than
Tor's relay set; geographic diversity is correspondingly worse.
Anonymity properties depend on relay set size — Tor is *better*
on this metric, which is why excluding Tor and including Lokinet
is a deliberate trade-off the project owner is making rather than
a "Lokinet is better" claim.

### DNSCrypt

| Property | Value |
|---|---|
| Upstream | https://github.com/DNSCrypt/dnscrypt-proxy |
| License | ISC |
| Daemon model | Encrypted DNS resolver; localhost UDP/TCP 53 listener |
| Native build | Go binary; ~10 MB per ABI; cross-compile via `GOOS=android` |
| Bootstrap time | <1 s |
| Battery profile | Negligible — DNS is bursty and tiny |
| Anonymity claim | None against the resolver operator. **Encryption only.** Use anonymized DNSCrypt for that. |
| Server-mode opt-in | N/A — DNSCrypt doesn't have a "server" meaning that fits a phone |
| Status | Active, widely used (Adguard, Invizible Pro, etc. embed it) |

**Why it's phase γ.** For *system-wide* DNS, DNSCrypt needs the
firewall's VpnService to redirect UDP 53 (and 853) to the local
proxy. That's the same multiplexed-tun lift as Yggdrasil/Lokinet.

For *browser-only* DNS, the WebView resolves via the OS — there is
no per-WebView DNS injection point in `androidx.webkit`, so
browser-only DNSCrypt isn't reachable through this layer. The
"don't bother" call is acceptable: the browser already gets
encrypted transport (HTTPS), and DNS is a cleartext leak only when
the resolver is plain UDP — switching the *device's* resolver via
Android's Private DNS field already addresses 80% of the threat
without DNSCrypt at all. (Hence the firewall's existing DnsProvider
catalog deep-links to that field.)

**Known limitations.** Anonymity against the resolver requires
either rotating among many resolvers or using anonymized DNSCrypt
relays; the phase-γ implementation should default to a small
rotation rather than a single resolver.

### cjdns / Hyperboria

| Property | Value |
|---|---|
| Upstream | https://github.com/cjdelisle/cjdns |
| License | GPLv3 |
| Daemon model | Encrypted IPv6 mesh, similar shape to Yggdrasil; **requires a TUN device** |
| Native build | C; ~6 MB per ABI |
| Bootstrap time | Sub-second |
| Battery profile | Low — same shape as Yggdrasil |
| Anonymity claim | Same as Yggdrasil — encrypted, not anonymous |
| Server-mode opt-in | Always-on by design (mesh routing); harder to "leaf-only" cleanly |
| Status | **Maintained but smaller community** than Yggdrasil; recommend Yggdrasil over cjdns for new deployments |

**Recommendation: skip cjdns for this suite.** Yggdrasil covers the
"encrypted IPv6 mesh" use case more cleanly with a smaller native
footprint, an active mobile client, and a leaf-mode that maps
better to phone deployment. cjdns deserves a mention because the
project owner asked about it indirectly, but adding it in addition
to Yggdrasil would dilute attention without adding meaningful
capability.

## Effort summary

What it takes to land each network, expressed in claude-day
units (one focused session of design + implementation + device
test):

| Network | Phase | Effort | Blockers |
|---|---|---|---|
| I2P (browser-only) | β | 4–7 days | NDK toolchain access; bundle i2pd; ProxyController wire-through |
| Yggdrasil | γ | 2–3 days *after* multiplexed firewall lands | Multiplexed firewall (~weeks) |
| Lokinet | γ | 2–3 days *after* multiplexed firewall lands | Multiplexed firewall |
| DNSCrypt | γ | 1–2 days *after* multiplexed firewall lands | Multiplexed firewall |
| cjdns | (deferred) | — | Skipped in favor of Yggdrasil |
| Multiplexed firewall (foundation) | γ.0 | 10–20 days | Real engineering; touches the VpnService + IP stack |

The multiplexed firewall is the gating piece. Without it, all of
Yggdrasil/Lokinet/DNSCrypt collapse to "user turns firewall off,
runs the official client of $network." With it, the suite gains a
per-app routing decision surface that exposes all three (and
future ones) through a unified UI.

## License posture

Every binary we bundle ships under a **copyleft-or-permissive** OSS
license: BSD/ISC for I2P + DNSCrypt; LGPLv3 for Yggdrasil; GPLv3
for Lokinet (and cjdns, were we to bundle it). The suite itself
ships under the project's existing license. License-compatibility
notes:

- **I2P (BSD-2-clause):** trivial — no copyleft obligations.
- **DNSCrypt (ISC):** trivial — no copyleft obligations.
- **Yggdrasil (LGPLv3):** linking is fine; we ship the binary as a
  separate process which is the cleanest LGPL boundary. We must
  ship the LGPL'd source (or pointer to upstream) with releases.
- **Lokinet (GPLv3):** the suite **must** be GPLv3-compatible to
  bundle a GPLv3 binary. If the suite project license is not
  GPLv3 (TBD), Lokinet ships as an *optional separate APK* the
  user installs alongside, not bundled in the main APK. Phase γ
  decision point.

The `:overlay-i2p` module's i2pd integration sets the precedent for
how we vendor each daemon — `jniLibs/<abi>/lib*.so` per architecture,
upstream version pinned, source pointer in CREDITS.md, recipe in
`BUILD_RECIPE.md`.

## Out of scope

- **Tor.** The owner has explicitly excluded Tor from this suite.
  Users who want Tor should install Orbot.
- **Mesh-only operation.** Some networks (cjdns, batman-adv) target
  the case where the device has no upstream internet. The suite
  assumes upstream connectivity exists; mesh-only deployment is a
  different product.
- **Hosting hidden services.** Running an I2P eepsite or Lokinet
  service from a phone is technically possible but doesn't fit the
  threat model (phones are typically not always-on with stable
  network identity). Out of scope.

---

*This document is updated as design decisions land. PRs that change
the architecture should land alongside an update here, not after.*
