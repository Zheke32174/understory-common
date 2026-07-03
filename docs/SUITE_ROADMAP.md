# Understory Suite — roadmap

A coordinated set of rootless, in-bounds, sovereign Android security
tools that compose into a coherent stack. Each app does one thing well;
together they cover the userspace threat surface a vanilla phone
otherwise leaves open.

Design constraints that apply to **every** app in the suite:

- **Rootless**: every primitive uses public Android APIs the way Google
  intends. No root, no Shizuku, no Xposed, no signature-spoofing, no
  accessibility-as-privilege-escalation, no undocumented reflection.
- **In-bounds**: passes Play-Store policy review without exception
  requests (whether or not we publish there).
- **Vanilla phone**: works on any unlocked stock Android device. Power-
  user-only knobs (e.g. setting our browser as the system WebView via
  developer options) are explicitly opt-in and the app degrades cleanly
  without them.
- **Local-first**: zero network features unless the user explicitly opts
  in. Cloud sync is always opt-in and always to the user's own server.
- **Composable**: each app stands alone but benefits when paired with
  the others. No app *requires* another to be installed.
- **Verifiable**: every defense in every app has an entry in the
  BlackArch defense matrix with a runbook a third party can execute.

## App roster

Status legend:
- ✅ shipping (this branch builds and installs)
- 🛠 scaffolded (skeleton committed; functionality lands in subsequent
   commits)
- 📋 planned (designed; not yet started)

| # | App | Purpose | Status | Notes |
|---|---|---|---|---|
| 1 | **passgen** | Local password generator + manager (vault, IME, autofill) | ✅ | This branch. Snake-eats-tail vault verified working on a real Samsung. |
| 2 | **aegis** | TOTP / HOTP authenticator (companion to passgen's recovery flow) | ✅ | Phase A shipping: BiometricPrompt-gated vault, list view with auto-refreshing 30s codes + circular countdown rings, manual entry add (paste base32 secret or otpauth:// URI), tap-to-copy with EXTRA_IS_SENSITIVE. Phase B adds gallery-only QR import + IME mode for typing codes. |
| 3 | **firewall** | Outbound-network gate via VPN-slot. Per-app rules, DNS overrides, sophisticated allow/deny patterns. | ✅ | Phase B shipping: VpnService + per-app blocklist UI. Toggle on, pick which apps lose network — those apps' traffic is captured by a local tun and dropped. Phase C adds packet parsing for finer rules (per-domain block, DNS overrides, allowlist-only mode). Combined firewall+VPN because Android only has one VPN slot. |
| 4 | **vault-folder** | "Secure folder"-style app holder. Encrypts files at rest in app-private storage; opens via the same Keystore-bound device-credential gate passgen uses. | 📋 | Differs from Samsung Secure Folder: no work-profile dependency, no system-level isolation (which we can't reach rootless). The honest sell is "encrypted-at-rest folder you open with biometric." |
| 5 | **antivirus** | Real-time protection layer. Inotify-watching app-installs, signature-checking against published bad-actor lists, hash-checking downloaded APKs before install. | 📋 | Honest scope: heuristic, not behavioral. We're userspace; we can't intercept syscalls. |
| 6 | **messenger** | SMS/RCS reader with spam filtering. Carries its own signing-key allowlist for known-good senders; quarantines anything else. | 📋 | Reads SMS via standard Android SMS APIs (requires READ_SMS / RECEIVE_SMS — the only suite app that needs these, and they're justified for this app's purpose). |
| 7 | **backups** | Local-first encrypted backup orchestrator. Schedules and runs encrypted exports of every suite app's data to user-chosen destinations (USB, Syncthing folder, self-hosted endpoint). | 📋 | Each suite app exposes a backup-export interface; backups orchestrates them. |
| 8 | **browser** | Hardened Chromium-based browser fork (Cromite-aligned), shippable as a standalone app and OPTIONALLY settable as system WebView (requires dev-options toggle — degrades to standalone-only otherwise). | 📋 | Defense-in-depth pairing with the firewall: browser blocks fingerprinting/tracking at the rendering layer; firewall blocks network egress for what slips through. |
| 9 | **mdm-local** | Sovereign on-device MDM. Like a corporate MDM in shape but with the user as the admin — defines per-app policy, locks settings, watches for posture violations. **Not** a Device Owner (which we can't become rootless); a userspace policy enforcer that surfaces violations instead of preventing them. | 📋 | Honest framing: an MDM-shaped *advisor*, not enforcer. |

The total of 9 is the upper end of the user's "5 to 9 apps" range —
we'd ship the most-used ones first (passgen, aegis, firewall, browser)
and add the rest as bandwidth allows.

## Cross-app attestation (the suite mesh)

Each app pins its own signing cert via `Tamper.signatureMatches`. On top of
that, every app calls `SuiteAttestation.verify(applicationContext)` at
launch — it queries every other suite member's package via the
`<queries>` block, reads its signing cert, and refuses to run if any
sibling exists with a wrong cert.

Properties:
- **Optional**: missing siblings are not a defense failure; they just
  aren't checked. Each app stands alone.
- **Mutual**: every installed app verifies every other installed app.
- **Symmetric**: a compromised sibling drags down every other suite app
  on the device.
- **Rootless**: uses public PackageManager APIs only.

This is the "cross-feature that makes both more secure but neither needs
to function" pattern. It's additive defense for users who run multiple
suite apps without forcing them to.

## Suite-level architecture

All apps share `:common-security`, which contains:

- **Tamper** — signature pin, Xposed/Frida/Lucky-Patcher detection,
  installer-source check
- **A11yProbe** — surfaces enabled third-party accessibility services
  to the user
- **SecureButton** — Compose buttons with partial-obscurement tap-jack
  filtering (DOWN + MOVE)
- **DeviceProfile** — OEM detection (Samsung dual-autofill-slot,
  Xiaomi MIUI quirks, etc.)
- **Totp** — RFC 6238 TOTP / RFC 4226 HOTP (used by passgen recovery
  and by aegis's main feature)
- **HotpSecret** — 160-bit secret generation, base32 encoding,
  authenticator-compatible `otpauth://` URIs

Each app's manifest strips every comms / sensor / storage permission it
doesn't strictly need, with the same defensive `tools:node="remove"`
pattern the suite established in passgen.

The signing-cert pin is shared: every app references
`com.understory.security.Tamper.EXPECTED_CERT_SHA256`, and the suite-
wide `verifyCertPin` Gradle task in the root `build.gradle.kts` checks
every assembled APK against the same pin. Single source of truth.

## What "anti-BlackArch" means at the suite level

passgen's defense matrix covers the userspace offensive toolkit. The
suite extends this scope:

| Threat | Addressed by |
|---|---|
| Network-layer exfil | firewall (egress block) + browser (anti-fingerprinting) |
| File-layer exfil | vault-folder (encryption at rest) + backups (encrypted off-device) |
| Credential exfil | passgen (no clipboard) + aegis (no shared 2FA secret) |
| Install-time tamper | suite-wide `verifyCertPin` + per-app Tamper detection |
| Runtime hooking (Frida/Xposed) | per-app Tamper.hookFrameworkLoaded |
| SMS/RCS phishing | messenger (sender-allowlist quarantine) |
| Malicious APKs | antivirus (signature/heuristic checks pre-install) |
| Posture drift over time | mdm-local (continuous policy verification) |

Each suite app has its own row block in the BlackArch defense matrix.
When you install N apps from the suite, you get N additional defense
layers, none of which conflict.

## Scope limits we won't pretend to address

- **Kernel exploits** — userspace can't help. GrapheneOS or similar.
- **Bootloader / verified-boot bypass** — OEM territory.
- **Baseband / radio firmware backdoors** — opaque vendor blobs.
- **Hardware attacks** (cold-boot RAM, JTAG, side-channels) — physical
  access game-over.
- **Compromised TEE / Keystore** — we trust the OS.
- **User decides to disclose** — out of scope for any app.
- **Fully rooted attacker** — by definition, we lose.

## Distribution

Primary: F-Droid. Open-source-friendly, no Play-policy frictions on the
firewall (VPN-slot apps face Play scrutiny) or the autofill provider
(Play has tight rules), GrapheneOS-adjacent users already use it.

Secondary: direct sideload from the user's own GitHub releases page
(what we're doing now during development).

Tertiary: Play Store. Each app would need a Play-policy compliance
pass; the firewall might need to split into VPN-only and firewall-only
variants; the messenger would need to pick a precise SMS-permission
justification. Not a priority.

## Maintenance posture

Each app in the suite is committed to ~quarterly Android-API-tracking
attention. Android changes APIs every release; Keystore semantics
shift; BiometricPrompt edge cases evolve. The suite isn't a "ship and
walk away" project — it's an ongoing-tend project. That's the honest
maintenance commitment.

When a single contributor (human or AI) is the sole tender, the suite's
viability depends on that contributor staying engaged. F-Droid's reviewer
network can catch some breakage; the BlackArch defense matrix catches
regressions in the security claims; nothing replaces an eyeball on the
codebase as Android moves under it.
