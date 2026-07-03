# Design v2 — SUITE COEXISTENCE + HONESTY LAYER

Status: DESIGN ONLY (no code changes; the only artifact is this doc). Written
2026-07-03 against the SUITE DOCTRINE (complement-don't-replace, viability
honesty, shippable = polished + zero dead UI). Inputs: `docs/audit-v2/SUITE.md`
+ all seven per-app sheets (read in full) + direct re-verification in code of
the capability layer (`common-security/.../SuiteCapability.kt`,
`SuiteCapabilityRegistry.kt`, `BaseCapabilityProvider.kt`).

This is the **suite-level** design. It owns four cross-cutting decisions that
no single-app design doc can own:

1. §1 **Honest capability beacons** — corrected `SuiteCapability` taxonomy + a
   traced-path rule + each app's corrected beacon set.
2. §2 **Coexistence doctrine** — a normative section ready to paste into
   `SUITE_DESIGN.md`.
3. §3 **Incumbent-interop matrix** — per app: incumbent(s), import+export
   formats, hand-off intents.
4. §4 **Suite attestation / naming** — the aegis collision decision + package/
   authority inconsistencies.

Per-app implementation (the actual import parsers, export UIs, VPN-slot
reposition, etc.) lives in the per-app design docs. This doc constrains them:
where §1–§4 fix a name, a beacon, or a format, the per-app doc must conform.

Cross-references used below:
- SUITE.md §4 (name/identity), §1 (slot matrix + eng/prod authority collision).
- aegis.md A-F1/A-U5/D-M1/D-M2/D-L5 (export, real-Aegis interop, rename).
- passgen.md A22-A29/D2/D5 (import/export, Bitwarden gap).
- vault-folder.md A18/B.4/#10 (backups hand-off), A7 (deposit intent), #7 (authority).
- backups.md A22/A23/#23 (orchestrator stub, BACKUP_ORCHESTRATOR overclaim).
- antivirus.md A4/A7/A8/#11 (REALTIME_SCANNER overclaim, empty KnownBad, APK share-target).
- firewall.md A1/A2/D1/#9 (NETWORK_FILTER = vetoed VPN core, Tailscale `<queries>`).
- browser.md A15/D1/D6/#8 (share-target intake, open-in-default hand-off).

---

## 1. HONEST CAPABILITY BEACONS

### 1.1 The defect

`SuiteCapability` (`common-security/.../SuiteCapability.kt:26-88`) is the
suite-visible vocabulary of *powers an app offers to its peers*. A peer's
`SuiteCapsProvider` attests only `(package, version)`; each consumer's
compiled-in `KNOWN_PEERS` map (`SuiteCapabilityRegistry.kt:56-82`) translates
that pair into a capability set. So a capability, once mapped, is a **public
claim to every other app** — it drives peer footers ("peer caps:
realtime-scanner", `SuiteStatusFooter.kt:150-155`) and gates real cross-app
UI (`snap.has(OTP_VAULT)` shows a step-up toggle, `SuiteCapability.kt:36-39`).

Three v1 mappings claim powers no shipped code delivers (verified):

| Beacon (today) | Mapped at | What the code actually does | Verdict |
|---|---|---|---|
| `BACKUP_ORCHESTRATOR` | `SuiteCapabilityRegistry.kt:66-68` (backups v1) | No cross-app `BackupProvider` IPC exists in *any* app; the two in-process adapters are dead code; the "orchestrator" is a single-file envelope tool (backups.md #22/#23). | **OVERCLAIM** |
| `REALTIME_SCANNER` | `:72-74` (antivirus v1) | No receiver/worker/watcher exists; rootless real-time is impossible; the app is a static on-demand SAF/audit scanner and its own UI says so (antivirus.md A7/A8). | **OVERCLAIM** |
| `NETWORK_FILTER` | `:63-65` (firewall v1) | The only mechanism is `FirewallVpnService` — the vetoed VPN slot. Enum doc literally says "via the VpnService slot" (`SuiteCapability.kt:41-46`). Post-veto the app filters nothing (firewall.md A1/D1). | **OVERCLAIM + doctrine-void** |

Two more enum values (`SECURE_MESSENGER`, `LOCAL_POLICY`,
`SuiteCapability.kt:74-87`) are for unbuilt future apps and are mapped to **no
peer** in `KNOWN_PEERS` — so nothing advertises them today. They are latent,
not overclaiming. Keep them, but §1.4 renames one for honesty-by-default.

### 1.2 The rule (normative — the "traced-path" gate on beacons)

> **BEACON-1 · A capability may be mapped in `KNOWN_PEERS` for `(package,
> version)` only if a reviewer can trace a live code path in that app, at that
> version, that delivers the power the capability's KDoc describes to a
> *peer*.** "Delivers to a peer" means either (a) a peer-invocable IPC surface
> (an exported-to-signature ContentProvider / Service / signed Intent target)
> that performs the action, or (b) a documented Intent hand-off the peer can
> fire. A power that exists only in-process, only as a `BackupAdapter`
> instance with no IPC, only behind a vetoed slot, or only in a roadmap, does
> **not** qualify.
>
> **BEACON-2 · The version beacon and the KNOWN_PEERS row move together.** A
> new power ships at version N; every consumer's `KNOWN_PEERS[pkg][N]` gains
> the capability in the *same* coordinated change (the mechanism already
> forces this — a peer cannot self-grant, `SuiteCapabilityRegistry.kt:44-54`).
> Never map a capability at a version whose APK doesn't yet implement it "so
> the UI is ready" — that reintroduces the overclaim.
>
> **BEACON-3 · Capability names describe the delivered power at its true
> cadence and mechanism, not an aspiration.** No `REALTIME_*` for an
> on-demand scanner; no `*_FILTER`/`*_ORCHESTRATOR` for something that only
> reads or only advises. Renames are breaking to consumers, so they are
> batched into one coordinated bump (§1.4).

The provider/registry security model itself is sound and stays: cert-pin +
local-map authority defeats capability spoofing (`SuiteCapabilityRegistry.kt:169-186`).
BEACON-1 is a **content** rule on what we choose to map, layered on top.

### 1.3 Corrected taxonomy

Rename the three overclaiming values to what the code does; drop the vetoed
one to an honest advisory name; leave the three genuine ones; keep the two
future ones (one renamed for honesty). All names below are the new
`SuiteCapability` enum identifiers.

| New name | Replaces | Meaning (post-fix) | Provider mechanism required to map it |
|---|---|---|---|
| `IDENTITY_VAULT` | (unchanged) | Stores the user's password/credential vault; can be *asked* to hand a credential to a peer's picker. Provided by passgen. | Signature-gated fill/pick surface exists. Today: **mappable** (autofill pick path is real, passgen.md A6). |
| `OTP_VAULT` | (unchanged) | Holds TOTP/HOTP seeds; **may** issue a current code to a peer on request. Provided by aegis. | Peer-invocable issue-code IPC. Today the *service* side does NOT exist (aegis.md A18 "the service side … does not exist yet") → see 1.4 note: keep the name, but it is only honestly mappable once a code-issue IPC ships. For v1, aegis advertises `OTP_VAULT` **only if** a peer can actually request a code; otherwise it advertises `OTP_STORE` (storage-only, no issue IPC). |
| `FILE_VAULT` | (unchanged) | Encrypted file store; accepts a file via the deposit Intent. Provided by vault-folder. | Deposit `ACTION_VIEW` target exists (vault-folder.md A7). **Mappable.** |
| `BACKUP_ENVELOPE` | `BACKUP_ORCHESTRATOR` | Encrypts/decrypts a single file to/from the suite `BackupEnvelope` format, and is a hand-off *target* for "encrypt this and store it". Provided by backups. | The envelope encrypt/decrypt + a deposit Intent target exist (backups A3/A5). **Mappable.** The *orchestrator* power (calls every peer's adapter over IPC) gets a **separate future** value `BACKUP_ORCHESTRATOR` re-added only when the cross-app `BackupProvider` IPC actually ships (backups.md #22). |
| `APK_AUDITOR` | `REALTIME_SCANNER` | On-demand static APK / installed-app auditor; accepts a share/VIEW of an APK and returns advisory findings. Provided by antivirus. | On-demand SAF scan + APK share-target (antivirus.md A1, D-note #2 opportunity). **Mappable** as static auditor. |
| `NET_POSTURE_AUDIT` | `NETWORK_FILTER` | Audits remote-admin-class grants + DNS posture + VPN-slot health; advises, does not intercept. Provided by firewall. | The remote-admin audit + Private-DNS read/apply are real and rootless (firewall.md A5/A7). **Mappable.** No packet interception is ever claimed. |
| `HARDENED_BROWSER` | (unchanged) | Offers an "open this URL in the hardened viewer" Intent target (share-target / VIEW), behind a confirmation interstitial. Provided by browser. | The intake Intent target must exist to map it. Today it does **not** (browser.md A15) → browser advertises `HARDENED_BROWSER` **only after** D1 lands; until then it maps **no** capability (it consumes peers but offers none). |
| `SECURE_MESSENGER` | (unchanged, future) | Future messenger. Unmapped today. | n/a |
| `LOCAL_POLICY` | (unchanged, future) | Future mdm-local. Unmapped today. | n/a |

Notes that fall out of BEACON-1:
- **`BACKUP_ORCHESTRATOR` is not deleted — it is deferred.** Remove it from
  the v1 `KNOWN_PEERS[com.understory.backups][1]` row and re-introduce it as a
  distinct enum value + a `[2]` row when the IPC ships. `BACKUP_ENVELOPE` is
  what v1 honestly offers.
- **`OTP_VAULT` / `HARDENED_BROWSER` are gated on an IPC/Intent that may not
  exist at first ship.** The honest v1 default is: **map nothing** for an app
  until its peer-facing surface exists. An app with no peer-facing power is a
  perfectly valid suite member — it still *consumes* peers and contributes to
  tier count; it just contributes an empty capability set (the registry
  already handles empty sets, `SuiteCapabilityRegistry.kt:182-186`).

### 1.4 Each app's corrected beacon set (v1 ship)

Two-column truth: what the app *provides* to peers (its beacon) vs. what it
*consumes*. Beacons are mapped in every consumer's `KNOWN_PEERS`.

| App (package) | v1 beacon it PROVIDES | Condition to map it at v1 | Consumes (reacts to peers) |
|---|---|---|---|
| passgen (`com.understory.passgen`) | `IDENTITY_VAULT` | autofill/pick path ships (already real) | `OTP_STORE`/`OTP_VAULT` (offer step-up), `BACKUP_ENVELOPE` (offer "back up my vault") |
| aegis (`com.understory.aegis`) | `OTP_STORE` (storage-only) → upgrade to `OTP_VAULT` only when a code-issue IPC ships | no code-issue IPC at v1 (aegis.md A18) → ship `OTP_STORE` | `IDENTITY_VAULT`, `BACKUP_ENVELOPE` |
| vault-folder (`com.understory.vaultfolder`) | `FILE_VAULT` | deposit VIEW target real (A7) | `BACKUP_ENVELOPE` (offer "send encrypted copy to backups", vault-folder.md B.4) |
| backups (`com.understory.backups`) | `BACKUP_ENVELOPE` (NOT `BACKUP_ORCHESTRATOR`) | envelope + deposit target real | `FILE_VAULT`, `IDENTITY_VAULT`, `OTP_STORE` (list what *could* be backed up; no live pull until IPC) |
| browser (`com.understory.browser`) | **none at v1** → `HARDENED_BROWSER` once D1 (share-target + interstitial) ships | intake Intent absent today (A15) | none required |
| firewall (`com.understory.firewall`) | `NET_POSTURE_AUDIT` (NOT `NETWORK_FILTER`) | audit + DNS surfaces real (A5/A7) | `APK_AUDITOR` (receive "block/restrict this app" advisories from antivirus) |
| antivirus (`com.understory.antivirus`) | `APK_AUDITOR` (NOT `REALTIME_SCANNER`) | on-demand scan real; APK share-target = D-opportunity | `NET_POSTURE_AUDIT` (hand a flagged app to firewall's restrict worklist) |

Implementation shape (for the per-app + common-security implementers):
- Edit `SuiteCapability` enum: rename `BACKUP_ORCHESTRATOR`→`BACKUP_ENVELOPE`,
  `REALTIME_SCANNER`→`APK_AUDITOR`, `NETWORK_FILTER`→`NET_POSTURE_AUDIT`; add
  `OTP_STORE`; keep `OTP_VAULT` for the future issue-code power; keep
  `BACKUP_ORCHESTRATOR` removed-from-v1-map (re-add enum value when IPC ships).
- Edit `KNOWN_PEERS` rows to the "PROVIDES" column above. browser v1 maps to
  `emptySet()` (or omit the version row) until D1.
- Update every footer short-name mapping (`SuiteStatusFooter.kt:150-155`).
- No `SuiteCapsProvider` `providedVersion` needs to change — the names change,
  not the versions; the (pkg, v1) pairs simply map to corrected sets.
- This is one coordinated commit across all seven apps' vendored copies +
  canonical (they are byte-identical per every audit sheet's diff check).

---

## 2. COEXISTENCE DOCTRINE (normative — paste into SUITE_DESIGN.md)

> ### Coexistence doctrine
>
> **CD-1 · Complement, don't replace.** Every understory app must add value
> NEXT TO the app the user already runs for that purpose. If a feature's value
> depends on the incumbent being absent, disabled, or evicted, the feature is
> misdesigned. Reference-device incumbent set (Samsung SM-S948U, One UI):
> Tailscale (VPN), Bitwarden/1Password (autofill + password vault),
> Chrome/Brave (default browser), Aegis/Google Authenticator (TOTP), Samsung
> Secure Folder (file isolation), Play Protect (malware), Samsung
> Keyboard/Gboard (IME), Google One/Smart Switch (device backup).
>
> **CD-2 · Slot policy.** Scarce single-owner Android surfaces: the VPN slot
> (VpnService), the active autofill service, default-app roles (browser/SMS/
> assistant/home), the accessibility service, the notification-listener
> binding, device admin, and the usage-stats grant.
> (a) **The VPN slot is permanently VETOED.** Tailscale holds it. No
> understory feature may require, request, or be designed around VpnService —
> including "temporary" tunnels, DNS-redirect via a fake resolver route, and
> overlay-network transports (Yggdrasil/Lokinet-as-TUN). Packet-level engines
> may exist only as an explicitly-labelled, default-off "Standalone (no
> Tailscale)" mode, gated on detecting that no other VPN is active, and never
> as the primary verb.
> (b) **No feature may REQUIRE any other scarce slot.** A slot may be offered
> only as an explicit opt-in, and every opt-in must degrade gracefully: with
> the slot ungranted the app still delivers its core value, shows an honest
> status line naming who holds the slot ("Autofill: Bitwarden — passgen is in
> keyboard mode"), and renders no dead control and no re-enable nag against the
> incumbent.
> (c) **Multi-enable surfaces are the preferred delivery channels** — the IME
> list, the system share-sheet, and "Open with…" choosers are additive by
> construction. Never set an IME as default programmatically; never prompt for
> a default-app role; never call an API that evicts a slot's current owner.
> (d) **An incumbent holding or reclaiming a slot is a STEADY STATE, not an
> error.** UI renders it neutrally or positively (green "coexisting"), never as
> a fault to "fix". Specifically: the firewall must never render a
> Tailscale-took-the-slot event as "preempted — Re-enable".
>
> **CD-3 · Incumbent-interop policy (import AND export).** Wherever the
> incumbent category has an established interchange format, understory apps
> speak it in **both directions**. Minimum format set by category:
> - Passwords: Bitwarden CSV **and** Bitwarden JSON (`items[].type==1`), Google
>   Password Manager CSV, Proton Pass CSV/JSON — import and export.
> - TOTP: Aegis JSON (plain `db.entries[].info`; encrypted scrypt slots when
>   feasible), `otpauth://` URI lists, `otpauth-migration://` (import), 2FAS
>   JSON — import; `otpauth://` list + QR render — export.
> - Files: plain bytes via SAF (`ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`)
>   — universal, no lock-in, both directions.
> - Backups: the suite `BackupEnvelope` is the one at-rest export format; a
>   passphrase/recovery-key path makes it restorable off-device.
>
> **An app that ingests a secret class but offers no user-reachable export of
> it is a roach motel and does not ship.** Export must be a real, reachable UI
> action — not a dead adapter class. Data the user entrusts to us is theirs to
> take back to the incumbent at any moment; making that trivial IS the
> complement pitch.
>
> **CD-4 · Honest-UI policy.**
> (a) **Zero dead controls.** No button, switch, toggle, or picker whose action
> cannot complete on this build of this device — remove it, disable-with-reason,
> or gate it behind the eng flavor. A switch that flips ON and then discovers it
> can't work (browser I2P) is a dead control.
> (b) **Zero capability overclaim.** UI copy, notifications, manifest comments,
> READMEs, roadmap rows, launcher labels, and suite capability beacons may claim
> only what the shipped code does today. "Phase 2" belongs in docs, not
> onboarding copy or a live-looking beacon (see BEACON-1..3).
> (c) **Failure honesty.** Every silent dead-end — a swallowed tap, a
> silent hard-fail `finishAndRemoveTask`, a suppressed notification — gets a
> visible, truthful message. Integrity/tamper hard-fails show a one-line reason,
> not a bare vanish.
> (d) **Status honesty.** Primary status surfaces never overstate active
> enforcement or protection: no "N apps blocked" while blocking is paused; no
> green checkmark derived from an unreadable setting (degrade to "unknown"); no
> "real-time" where the mechanism is on-demand.
> (e) **Cleanup honesty.** Claims of automatic cleanup (clipboard auto-clear,
> "session cleared", shred) must match the implementation's real guarantees,
> including behavior across process death and across OEM clipboard policy.
>
> **CD-5 · Names are claims.** Launcher labels, store names, and capability
> beacons are subject to CD-4(b). A name that asserts a capability the app
> lacks ("antivirus" for a static auditor; "firewall" for an advisor that
> blocks nothing) is an overclaim; a name that collides with the very incumbent
> it complements ("aegis" beside Aegis Authenticator) contradicts CD-1 by
> construction. Store-facing names are decided in §4.

---

## 3. INCUMBENT INTEROP MATRIX

Per app: the incumbent(s) it sits beside, the interchange format(s) it must
import **and** export, and the hand-off Intents that make the complement loop
real. "Import" / "Export" cells list what v1 must reach through user-facing UI.
Hand-off Intents are the CD-1/CD-3 doorways and exits.

### 3.1 passgen — beside Bitwarden / 1Password / Google / Samsung Pass

| Direction | Format(s) | Status today | Design requirement |
|---|---|---|---|
| Import | Google Password Manager CSV; Proton Pass CSV/JSON | WORKING (passgen.md A22/A23) | keep |
| Import | **Bitwarden CSV** (`folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp`) + **Bitwarden JSON** (`items[].type==1`) | **MISSING** (A24) | add to `ImportFormats.detect/parse`; Bitwarden is the reference-device incumbent — this is the single most important format |
| Export | **Bitwarden CSV** + passphrase-encrypted `BackupEnvelope` | **MISSING** (A29 — import-only roach motel) | biometric-gated "Export" via `ACTION_CREATE_DOCUMENT`; kills the roach-motel + is the migration-buffer pitch |
| Hand-off OUT | share the exported CSV/envelope via the system share-sheet to Bitwarden / backups | n/a | plain share-sheet; deposit to backups via `ACTION_VIEW` into `com.understory.backups` |
| Hand-off IN | "Open with… passgen" of a CSV/JSON → **confirmation interstitial** → import | auto-imports without confirm (A26 — contradicts its own manifest) | add the confirm step (parsed-summary dialog) before merge |

### 3.2 aegis — beside Aegis Authenticator / Google Authenticator / 2FAS / Proton

| Direction | Format(s) | Status today | Design requirement |
|---|---|---|---|
| Import | `otpauth-migration://` (Google Auth); Proton Authenticator JSON; generic flat OTP JSON; `otpauth://` URIs | WORKING (aegis.md A10/A11) | keep; fix the generator so imported algo/digits/period actually work (aegis D-L1) — a faithful import that generates wrong codes is worse than no import |
| Import | **Real Aegis Authenticator JSON** (`db.entries[].info.{secret,algo,digits,period}`; plain first, encrypted scrypt slots v1.5); **2FAS JSON** | **MISSING** (A-U5) | the app is *named* for this incumbent and cannot read its export — add the `db`-unwrap branch to `FileImports` (aegis D-M2) |
| Export | **`otpauth://`-per-line** file + optional QR render | **MISSING — no export of any kind** (A-F1) | biometric-gated `ACTION_CREATE_DOCUMENT`; `OtpAuthEntry.toUri` already exists as dead code (aegis D-M1). Roach-motel + name-collision together = displacement-by-ratchet |
| Hand-off | share exported URIs → user re-enrolls the incumbent (completes the two-way street) | n/a | plain share-sheet |

### 3.3 vault-folder — beside Samsung Secure Folder / Files "Safe Folder"

| Direction | Format(s) | Status today | Design requirement |
|---|---|---|---|
| Import (deposit) | any file via **`ACTION_VIEW` "Open with… vault folder"** (octet-stream/json/text/csv/image/pdf) | WORKING path, MISLEADING claim (A7 — auto-encrypts while docs claim a confirm) | add the per-deposit confirmation; drop `BROWSABLE`; handle `onNewIntent` |
| Export | **plain original bytes via SAF** (`ACTION_CREATE_DOCUMENT`, mode `"wt"`) | crashes (non-Parcelable in `rememberSaveable`, #1) | fix the crash (save entry-id string); universal-format output = zero lock-in |
| Hand-off OUT | **one-tap "send encrypted copy to backups"** = `ACTION_VIEW` of the octet-stream into `com.understory.backups` | MISSING; in-app copy points at a non-existent integration with wrong suite number (A18/#10) | build the deposit hand-off (receiving side already exists); reword the copy now |
| Interop with Secure Folder | none possible (profile boundary) | — | state honestly: "we don't touch Secure Folder; drop files in from any app's share flow" |

### 3.4 backups — beside Google One / Samsung Smart Switch

| Direction | Format(s) | Status today | Design requirement |
|---|---|---|---|
| Export (at-rest) | **`BackupEnvelope`** (`.usbe`, Argon2id + AES-256-GCM), passphrase + recovery-key restorable cross-device | WORKING (backups A3/A5/A6) | this is the one canonical suite export format; keep |
| Import / restore | `.usbe` envelope decrypt (device KEK or recovery key) | WORKING | keep; the `.usbs` full-content stream is write-only with a framing bug (#17) — DROP or build a decoder before shipping the toggle |
| Hand-off IN | receive a file to encrypt from any peer via `ACTION_VIEW` deposit | receiving side is the envelope encrypt path | expose a deposit target so vault-folder/passgen/aegis exports can be "encrypt + store" in one tap (pairs with 3.1/3.2/3.3 hand-offs) |
| Interop with Google One | deliberately opted out (extraction rules exclude) — honest, just untold | — | one line: "we don't use Google's cloud backup; you hold the envelope + recovery key" |
| NOT a format | cross-app *orchestration* (pull every peer's vault over IPC) | not built (#22) | do not ship the `BackupProvider` IPC or the `BACKUP_ORCHESTRATOR` beacon at v1 (see §1) |

### 3.5 browser — beside Chrome / Brave

| Direction | Format(s) / mechanism | Status today | Design requirement |
|---|---|---|---|
| Intake (doorway) | **`ACTION_SEND` (text/plain) share-target** + optional non-default **`ACTION_VIEW` http/https** filter → **confirmation interstitial** (full URL + "JS OFF" + Load/Cancel) | MISSING (A15) — the positioning feature | build D1; the interstitial is what answers the URL-injection objection while keeping intake |
| Hand-off OUT (exit) | **"Open in default browser"** = `Intent(ACTION_VIEW, url)` out | MISSING (D6) | one Intent; completes inspect-here → trust → continue-in-Chrome loop |
| Blocked schemes | `mailto:`/`tel:` may hand off to system default apps; others show a snackbar | silent dead-ends (A13/A14) | honest feedback per CD-4(c) |
| Autofill | credential fill deferred to the **system** autofill service (Bitwarden/1Password/passgen) | WORKING (A-B) | keep — genuine complement, no slot taken |

### 3.6 firewall — beside Tailscale

| Direction | Mechanism | Status today | Design requirement |
|---|---|---|---|
| Coexistence detection | add **`com.tailscale.ipn` to `<queries>`**; detect a live VPN via `NetworkCapabilities.TRANSPORT_VPN`; read always-on/lockdown | Tailscale not even in `<queries>` (#9) | package-visibility fix + posture panel |
| Enforcement | Private DNS (DoT) via `WRITE_SECURE_SETTINGS` (opt-in, ADB-granted); per-app **restrict** via `ACTION_APPLICATION_DETAILS_SETTINGS` deep-links | Private DNS WORKING (A7); "block" is the vetoed VPN verb | reposition "block" → "restrict via Android's own settings"; VPN engine → default-off Standalone mode |
| Hand-off IN | receive "flagged app" advisories from antivirus (`APK_AUDITOR`) into a restrict worklist | not built | signed-Intent advisory (v2) |
| Interop with Tailscale | Private DNS composes with the tunnel; render "Tailscale holds the slot ✓" | posture copy stale/inverted (A2/A13) | rewrite to the coexistence story |

### 3.7 antivirus — beside Play Protect

| Direction | Mechanism | Status today | Design requirement |
|---|---|---|---|
| Intake | **`ACTION_SEND`/`ACTION_VIEW` of `application/vnd.android.package-archive`** → isolated-parser scan | on-demand SAF scan WORKING (A1); no share-target yet | register the APK share-target ("share → antivirus before installing") — on-demand, no slot |
| Definitions | **SAF-imported signed blob** for KnownBad hashes/certs (no INTERNET) | KnownBad is empty while UI claims hash/cert catches (A4) | either seed + build the signed-import path, or soften the claim until seeded |
| Export | scan report as **plain text via share-sheet** (for remote-help / stalkerware-victim scenarios) | not built | share-sheet export |
| Hand-off OUT | flagged app → firewall restrict worklist (signed Intent) | not built | pairs with 3.6 (v2) |
| Interop with Play Protect | **read-and-advise** + deep-link into Play Protect settings | positioning right; PP card false-greens (A6) | honest degradation to "unknown" + deep-link; never toggle/replace PP |

---

## 4. SUITE ATTESTATION / NAMING

### 4.1 The aegis name-collision decision (ship blocker)

**Framing.** The app's launcher label is literally `aegis`
(`understory-aegis/.../strings.xml:3`) and its IME is "aegis keyboard". Its
*complement target* is Aegis Authenticator (`com.beemdevelopment.aegis`) — the
TOTP incumbent on the operator's own radar. Installing both yields two
authenticator icons both effectively called "Aegis". This violates CD-1 by
construction (you cannot credibly sit *beside* an incumbent while wearing its
name) and invites trademark/store-review trouble (aegis.md D-L5).

**Decision.** Split identity into two layers:
- **Codename / package id (internal): `aegis` / `com.understory.aegis` STAYS.**
  It is not user-facing; changing the package id would break the suite cert
  mesh, `KNOWN_PEERS`, `<queries>`, and provider authorities for no user
  benefit. Repo name, source dirs, and internal docs keep "aegis".
- **Store-facing identity (user-visible): CHANGES.** `app_name`, the IME
  label, the README first line, and any store listing must not say "Aegis".

This is a *decision*, not a code puzzle: the code change is a one-line
`app_name` string + the IME label + a docs sweep. The blocker is choosing the
name. §4.3 makes that choice as part of a suite-wide scheme so it happens once.

### 4.2 Package / authority inconsistencies

Two machine-name defects, both verified, both cheap, both blocking clean
install or clean grep:

1. **vault-folder has three spellings** (SUITE.md §4.2): repo
   `understory-vault-folder`, package `com.understory.vaultfolder`
   (`build.gradle.kts:12`), launcher label "vault folder", capability-table
   key `vaultfolder`. **Decision: `vaultfolder` is the one machine name** —
   package id and the `KNOWN_PEERS` key already agree on it
   (`SuiteCapabilityRegistry.kt:75`). The repo name is legacy and stays (repos
   aren't renamed lightly); every *new* machine reference uses `vaultfolder`.
   No code change required beyond documenting it here; do NOT "fix" the repo
   name or the package id.

2. **eng/prod provider-authority collision in four apps.** The
   `SuiteCapsProvider` authority is hardcoded `com.understory.<app>.suitecaps`
   instead of `${applicationId}.suitecaps`, so the `.eng` flavor and prod
   declare the **same** authority → `INSTALL_FAILED_CONFLICTING_PROVIDER` when
   both are installed. Confirmed in browser (D11), vault-folder (#7),
   antivirus (D#8); **passgen already fixed it** (`build.gradle.kts:74`
   comment). **Fix, suite-wide: `android:authorities="${applicationId}.suitecaps"`**
   in every app's manifest. The registry already derives the lookup authority
   from the peer package (`SuiteCapabilityRegistry.kt:92-93`), so prod lookups
   are unaffected; eng builds become mesh-invisible by design (acceptable,
   document it). Apply to aegis, backups, browser, vault-folder, antivirus,
   firewall (audit each — SUITE.md §1.2 lists four unpatched; verify the
   others during implementation).

### 4.3 Suite naming scheme (resolves 4.1 in one pass)

All seven launcher labels are bare lowercase common nouns — ambiguous in a
launcher, unsearchable in a store, and two **overclaim by name** (CD-5):
"antivirus" is a static on-demand auditor with no real-time anything;
"firewall" post-veto blocks nothing. Brand the family "**Understory <Noun>**"
so the aegis collision, the noun ambiguity, and the two category overclaims
die in one decision:

| Internal codename | Store-facing `app_name` | Kills |
|---|---|---|
| aegis | **Understory OTP** | the Aegis collision (4.1) |
| passgen | **Understory Keys** | "passgen" jargon |
| vaultfolder | **Understory File Vault** | the three-spelling ambiguity, user-side |
| backups | **Understory Backup** | — |
| browser | **Understory Safe View** | "browser" default-role implication |
| firewall | **Understory Net Audit** | the "firewall blocks things" overclaim |
| antivirus | **Understory APK Check** | the "real-time antivirus" overclaim |

Scope of change: `app_name` (+ IME labels for aegis/passgen), store listings,
and README first lines only. Codenames, package ids, cert mesh, `KNOWN_PEERS`
keys, provider authorities, `<queries>`, and repo names are all untouched. The
capability-beacon renames (§1.4) and these label renames are the two halves of
one coordinated "honest identity" pass.

### 4.4 What the attestation mesh does NOT need to change

The cert-pin + `KNOWN_PEERS`-authority model is sound and stays exactly as-is
(`SuiteCapabilityRegistry.kt:169-186`, `BaseCapabilityProvider` signature-gated
read + locked write). §1 changes *which capabilities we choose to map* and §4
changes *user-facing labels*; neither touches the security mechanism, the pins
(`SuitePins`), or the version-attestation protocol. Do not refactor the
provider/registry while doing this work — it is the one cross-app surface that
is already correct.

---

## 5. IMPLEMENTATION ORDER (suite-level items only)

Ranked by ship-blocking weight; per-app work is tracked in the per-app docs.

1. **Beacon rename + de-overclaim** (§1.4): rename three capabilities, add
   `OTP_STORE`, drop `BACKUP_ORCHESTRATOR` from the v1 map, browser maps none
   until D1. One coordinated commit across canonical + seven vendored copies.
   Unblocks CD-4(b) at the mesh layer.
2. **Provider-authority `${applicationId}` fix** (§4.2.2) in the unpatched
   apps. Unblocks eng/prod side-by-side install (dev-blocking today).
3. **Naming decision + label swap** (§4.1/§4.3). Decision-gated ship blocker
   for store presence; kills the Aegis collision.
4. **Interop import+export parity** (§3): the per-app roach-motel fixes
   (passgen Bitwarden import/export + aegis export + real-Aegis import) are the
   CD-3 non-negotiables; the deposit/share-target hand-offs (browser D1,
   antivirus APK target, vault-folder→backups) are the CD-1 doorways.
5. **`vaultfolder` machine-name convention** (§4.2.1): documentation-only;
   land it in the doc set so future greps don't miss.

Non-negotiable for any public alpha (the intersection with SUITE.md §5's
list): the beacon de-overclaim (this doc §1), the aegis rename (§4.1), and at
least one export path per app that ingests secrets (§3, CD-3).
