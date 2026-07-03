# Credits — functional inspiration

The Understory Suite is written from scratch, but several of its
surfaces are functionally inspired by prior art. Crediting that is
owed. In every case the inspiration is the *function or posture* —
no UI, assets, code, or trademarks were copied, and no endorsement by
any of these projects is implied. Names below are the property of
their respective owners.

## Trustd (Traced Ltd) — mobile-threat-surfacing posture

The antivirus app's "surface what the platform already knows" design
is inspired by Trustd's approach to rootless mobile threat defense:

- **Play Protect surfacing** — reading and presenting the device's
  Play Protect verification state through public APIs
  (`PlayProtectStatus.kt`) rather than pretending an unprivileged app
  can replace it.
- **Hidden-launcher / posture checks** — flagging installed apps with
  no launcher entry and risky permission combinations
  (`RiskRules.kt`), the AppOps-style "what could this app do to you"
  audit rather than signature-database theater.

Functional inspiration only; no Trustd code, UI, or branding is used.

## NetGuard (Marcel Bokhorst / M66B) — VPN-slot firewall pattern

The firewall app follows the NetGuard-class pattern: a rootless
per-app firewall built on Android's public `VpnService` API, where
the tun device is used to gate outbound traffic per-app without root.
The suite's implementation (`FirewallVpnService`, `VpnPacketParser`,
`DnsRedirector`) is independent code, and its DNS handling
deliberately routes through a local DNSCrypt proxy instead of
NetGuard's design choices — but the "own the VPN slot, filter
per-UID" architecture was proven by NetGuard first.

## Aegis Authenticator (Beem Development) — name + TOTP UX conventions

Our authenticator app shares the name "aegis" — chosen as an homage,
and acknowledged plainly here to avoid any confusion: **this app is
not Aegis Authenticator and is not affiliated with Beem Development.**
The TOTP UX conventions it follows — encrypted vault behind a
biometric prompt, auto-refreshing code list, `otpauth://` and
Google Authenticator migration import — are conventions Aegis
Authenticator set for the category. Independent implementation;
no code or UI copied.

## dnscrypt-proxy (DNSCrypt project / Frank Denis) — bundled binary

The firewall bundles the upstream `dnscrypt-proxy` binary (fetched at
build time by `tools/fetch-dnscrypt-proxy.sh` into
`jniLibs/<abi>/libdnscrypt-proxy.so`; never committed to the repo and
supervised at runtime by `DnsCryptProxyService`). This is the one
piece of third-party executable code the suite ships. dnscrypt-proxy
is © the DNSCrypt project contributors and distributed under the
**ISC license**; the license text accompanies the upstream release
archives the fetch script pulls, and redistribution of the unmodified
binary with this notice satisfies its terms. We do not modify the
binary.

## Cromite / hardened-WebView guidance — browser lockdown defaults

The browser's phase-1 hardening matrix (JavaScript off by default
with per-site opt-in, third-party cookies always blocked, mixed
content never allowed, no user CA trust, fingerprint-surface
reduction goals) draws on the hardening direction established by
Cromite (and Bromite before it) and on published hardened-WebView
guidance. Phase 2 explicitly targets a Cromite-class engine
(`SUITE_DESIGN.md`); the current WebView-based MVP borrows the
*defaults philosophy* — restrictive until the user opts in — not any
code.

---

If any project listed here objects to how it is credited, open an
issue and it will be corrected promptly.
