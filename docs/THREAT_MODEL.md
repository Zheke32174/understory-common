# Threat model

Explicit "what we defend against / what we don't." Companion to
`SUITE_THREAT_SURFACES.md` (per-app surface inventory) and
`SUITE_DESIGN.md` (the rootless / in-bounds doctrine that produces
these boundaries). Being honest about the "don't" column is the point:
a security suite that over-claims is itself a threat.

## What we defend against

### Lost or stolen device — YES

This is the primary scenario. Every vault (passgen, aegis, backups,
vault-folder) is encrypted at rest with keys wrapped by a
device-credential-bound Android Keystore key released only through
`BiometricPrompt`. `allowBackup="false"` + deny-all
`dataExtractionRules` keep vault material out of device-to-device
transfers and cloud backups. The production posture (TestingMode flags
off) locks and destroys vault sessions on user-leave, sets
`FLAG_SECURE` so screens can't be captured or cast, and keeps
vault-bearing activities out of the recents switcher. A thief with the
powered-on phone but not the user's biometric/credential gets
ciphertext.

### Runtime malware in other apps — YES

Other apps on the device are assumed hostile. Defenses are structural:
each app's data lives in its private app-sandbox directory; nothing
sensitive is world-readable or exported. The only cross-app doors are
the signature-gated read-only SuiteCapsProvider, system-bound services
(IME / autofill / VPN — bindable only by the OS), and user-initiated
SAF flows. `SuiteAttestation` cross-verifies every sibling's signing
cert against the suite pin before trusting it, so a repackaged
lookalike contributes zero capabilities and trips the tamper check.
Hostile input that other apps *can* push (deposit intents, autofill
view trees, tun packets, APKs handed to the scanner) is enumerated in
SUITE_THREAT_SURFACES.md and either parsed in an isolated process
(antivirus), treated as opaque bytes (vault-folder), or gated behind
explicit user confirmation (imports). Clipboard and screen-capture
side channels are narrowed (EXTRA_IS_SENSITIVE, FLAG_SECURE,
overlay/tap-jacking filters via SecureButton and
`setHideOverlayWindows`).

### Network attackers — YES

Five of seven apps carry no INTERNET permission at all — the process
cannot open a socket, which is a stronger guarantee than any firewall
rule; a network attacker cannot reach code that is unreachable. The
two networked apps constrain the surface: the browser is HTTPS-only
(cleartext denied at the transport layer, system trust anchors only,
no user CAs, mixed content blocked, SSL errors hard-fail), and the
firewall's job is precisely to cut other apps' traffic, with DNS
handled by a bundled DNSCrypt proxy rather than cleartext port 53.
A man-in-the-middle gets TLS with real certificate validation or
nothing.

## What we do NOT defend against

### Compromised build environment — NO

If the machine that compiles and signs the APKs is compromised, the
attacker ships inside our signature and every downstream check
(cert pin, SuiteAttestation, verifyCertPin) validates the malware as
genuine. Nothing in the product can detect this; the checks all chain
back to the signing key the attacker now controls. What would be
needed: reproducible builds independently rebuilt and compared by
third parties (BUILD_REPRODUCIBILITY.md is the first step — the
recipe exists, an independent byte-identical rebuild has not yet been
demonstrated), plus a hermetic, audited build host. Until multiple
independent rebuilders exist, users are trusting our build box.

### Compromised distribution channel — NO (partially mitigated)

Distribution is currently "download the APK from GitHub." If GitHub
(or the operator's GitHub account) is compromised, a swapped APK
signed by a different key will fail the runtime cert pin and refuse
to run alongside genuine suite apps — that much is defended. But a
user installing for the first time, with no genuine app already
present and no out-of-band hash to compare, has no way to know the
"Verify your install" pins in a tampered README weren't rewritten
too. What would be needed: the still-open release blocker — a hashes
manifest signed by an offline key, published on a channel separate
from the APK host, plus F-Droid's independent build pipeline
post-v1.

### Compromised Android Keystore / TEE — NO

All vault key-wrapping assumes the hardware-backed Keystore honestly
enforces user-authentication-bound key release. If the TEE or
StrongBox implementation is broken (vendor bug, leaked extraction
technique), an attacker with the device can release wrapped keys
without the biometric. We cannot detect or prevent this from app
level: the whole design *delegates* root-of-trust to that hardware,
deliberately, because app-level secrecy without hardware backing is
weaker, not stronger. What would be needed: nothing an unprivileged
app can do — this is the platform vendor's boundary. Mitigation is
the passphrase-based envelope layer in backups (passphrase never
touches Keystore), which survives a TEE break at the cost of
passphrase strength.

### Kernel exploits — NO

A kernel-level attacker owns the address space of every process,
including ours: it reads vault plaintext out of memory during an
unlocked session, keylogs the passphrase, and bypasses every Android
permission check we rely on. No unprivileged app can defend against
the layer it runs on. What would be needed: verified boot +
up-to-date vendor patches (user's responsibility; the suite runs
in-bounds precisely so it never needs to weaken the platform's own
defenses), and minimizing plaintext lifetime — which we do (session-
scoped unlock, destroy-on-leave) — narrows the window but does not
close it.

### Rooted attacker / user-granted root — NO

Root on the device is game over by definition: su can read app-private
storage, inject into processes, and grant itself any permission.
`Tamper.kt` detects the *cohabiting-tooling* case (Magisk managers,
Xposed/LSPosed, Lucky Patcher packages via the `<queries>` allowlist)
and refuses to run — that is a tripwire against casual tampering and
repack-and-patch attacks, not a defense against a competent root-level
attacker, who hides from all of it (denylists, zygote injection). We
say this plainly: detection of root is best-effort theater beyond the
tripwire level, and the suite does not claim otherwise. What would be
needed: hardware attestation (Play Integrity / Key Attestation)
raises the bar but is cloud-tied and excluded by the local-first,
no-network doctrine; the honest answer is "don't run the suite on a
device you've given other software root over."

## Reading this honestly

The defended column is enforced by structure (permissions that don't
exist, processes that can't reach the network, keys the app never
holds unwrapped) rather than by vigilance. The undefended column is
undefended because an unprivileged, rootless, local-first app
*cannot* defend it — and pretending otherwise would only obscure
where users need other controls (verified boot, patched firmware,
independent rebuilds, out-of-band hash verification).
