# Audit v2 — SUITE (cross-app synthesis)

Written 2026-07-03 against the NEW SUITE DOCTRINE (complement-don't-replace,
viability honesty, shippable = polished + zero dead UI). Inputs: the seven
per-app sheets in this directory (aegis.md, antivirus.md, backups.md,
browser.md, firewall.md, passgen.md, vault-folder.md) — all read in full —
plus direct re-verification in code of every load-bearing cross-suite claim
(slot registrations, vault implementations, common-backup usage, naming,
capability tables). File:line evidence below is from this session's own reads,
not inherited from the sheets.

**Headline:** the suite's shared security floor (Keystore-wrapped vaults,
tamper/attestation mesh, permission-stripped manifests, FLAG_SECURE posture,
capability beacons) is real and consistently built — and consistently
*cloned*: four separate vault engines, seven hardcoded-hex dark themes, seven
one-string `strings.xml` files, and one capability table that promises three
capabilities nobody delivers. Exactly one app (firewall) violates the slot
doctrine, and it does so at its core. The dominant suite-wide defect class is
not crashes — it is **one-way data flow** (secrets check in, nothing checks
out) combined with **claims ahead of code**.

---

## 1. SLOT MATRIX

Every scarce Android resource × every app. Verified against each app's merged
manifest and code (registrations re-confirmed this session:
`understory-firewall\firewall\src\main\AndroidManifest.xml:226-229` VpnService;
`understory-passgen\passgen\src\main\AndroidManifest.xml:258` autofill, `:271` IME;
`understory-aegis\aegis\src\main\AndroidManifest.xml:225` IME; no other app
declares any slot service).

Legend: **USES** = registers/holds it · **OPT** = optional opt-in, degrades
gracefully · **never** = not touched (most apps `tools:node="remove"`-strip it).

| Scarce resource | passgen | aegis | vault-folder | backups | browser | firewall | antivirus |
|---|---|---|---|---|---|---|---|
| **VPN slot** (VpnService, ONE per device) | never | never | never | never | never (phase-β Yggdrasil design would — vetoed) | **USES — DOCTRINE VIOLATION** (enforcement core; `AndroidManifest.xml:226-229`) | never |
| **Autofill service** (one active provider) | **USES/OPT** (`:258`; dormant if Bitwarden holds slot — ~40% of app dark; Samsung "dual-slot" story unverified, passgen.md A39) | never (IME opts out of autofill) | never | never | never (defers to system service — correct) | never | never |
| **IME list** (multi-enable, one active at a time) | **OPT** — "passgen keyboard" (`strings.xml:4`, `method.xml`) | **OPT** — "aegis keyboard" (`strings.xml:4`, `isDefault="false"` `method.xml:4`) | never | never | never | never | never |
| **Default browser role** | never | never | never | never | never — structurally cannot claim it (no http/https VIEW filter, browser.md A15) | never | never |
| **Accessibility service** | never (warns about 3rd-party ones) | never (warns) | never | never | never | never (audits others') | never (audits others') |
| **Notification listener** | never | never | never | never | never | never (audits) | never (audits) |
| **Device admin** | never | never | never | never | never | never (audits) | never (audits) |
| **Usage stats (PACKAGE_USAGE_STATS)** | never | never | never | never | never | proposed OPT (post-redesign traffic accounting, firewall.md B.2) | never (audits others' grants) |
| **Overlay (SYSTEM_ALERT_WINDOW)** | never | never | never | never | never | never | never |
| **POST_NOTIFICATIONS** | stripped | stripped | stripped | **broken** — declared then stripped in same manifest; FGS progress invisible (backups.md A-14) | stripped (phase-β I2P FGS problem noted) | holds (FGS notifications) | stripped |
| **QUERY_ALL_PACKAGES** (Play-policy-scarce) | no | no | no | no | no | **USES** (rules UI + audit) | **USES** (audit) |
| **WRITE_SECURE_SETTINGS** (ADB-granted) | no | no | no | no | no | **OPT** (Private DNS applier — the doctrine-model feature) | no |
| **FGS (persistent notification)** | no | no | no | dataSync ×1 | specialUse (I2P scaffold) | specialUse ×2 | no |

### Suite-internal conflicts

1. **passgen IME + aegis IME — mechanically fine, product-confusing.** Android
   allows multiple enabled IMEs; both declare `isDefault="false"` and use
   momentary-switch-then-return semantics, so neither takes the user's Samsung
   Keyboard/Gboard. But a user who enables both gets two near-identical
   minimal dark keyboards in the switcher — "passgen keyboard" (generate
   passwords only, can't type saved entries — passgen.md A13) and "aegis
   keyboard" (currently unviable: lock-on-leave guarantees a locked vault
   whenever another app has focus — aegis.md A-U3). Verdict: no slot conflict,
   but shipping two half-capable secret-typing keyboards violates coherence.
   Recommended direction (do not implement now): one suite IME surface
   ("Understory keys") that types passwords AND OTP codes, or at minimum
   shared naming/UX so the switcher entries are self-explanatory.
2. **No other suite-internal slot contention.** Provider authorities are
   per-package (`{pkg}.suitecaps`, SuiteCapabilityRegistry.kt:92-93) — but
   four apps hardcode the prod authority so **eng+prod flavors collide at
   install** (browser.md D11, vault-folder.md #7, antivirus.md D#8; passgen
   fixed it — `understory-passgen\passgen\build.gradle.kts:74` comment).
   Suite-wide S fix: `${applicationId}.suitecaps` everywhere.
3. **ACTION_VIEW chooser crowding (mild):** vault-folder, aegis, and passgen
   all register VIEW filters for text/json/csv-family MIME types; a user
   opening a .json sees up to three understory entries in the chooser.
   Acceptable; worth one naming pass so the entries are distinguishable.

### Conflicts with incumbents

| Incumbent | Suite app | Verdict |
|---|---|---|
| **Tailscale** (holds THE VPN slot, permanently) | firewall | **Direct violation.** Arming the firewall evicts Tailscale; the `onRevoke` "preempted" banner then nags the user to evict Tailscale back (firewall.md A2) — inverted semantics under doctrine. Also: Tailscale is not even in firewall's `<queries>`, so coexistence detection can't resolve the package. Browser/firewall Yggdrasil phase-β designs are VpnService too — vetoed before built. Everything else in the suite: clean. |
| **Bitwarden/1Password** (autofill slot) | passgen | Graceful-dormant (nothing breaks), but the main screen's primary CTA implies taking the slot, the Samsung additional-slot story is unverified on the target device, and there is **no Bitwarden CSV/JSON import or export** despite it being the coexistence lingua franca (passgen.md A24/A29). |
| **Chrome/Brave** (default browser) | browser | Model citizen: cannot claim the role, WebView defers credential fill to the system autofill service. Gap is the opposite — no share-target intake, no "open in default browser" hand-off (browser.md A15/D6). |
| **Aegis Authenticator** (TOTP incumbent) | aegis | **Name collision, ship-blocker** (section 4). Mechanically clean (TOTP is multi-homed), but one-way import with no export = displacement-by-ratchet. Cannot import the real Aegis's own export format (aegis.md A-U5). |
| **Play Protect** | antivirus | Positioning correct (read-and-advise); the PP status card false-greens on modern devices (defunct settings key, default=1 — antivirus.md A6). |
| **Samsung Secure Folder** | vault-folder | No mechanism overlap, no conflict; no interop either (profile boundary). |
| **Google One / Smart Switch** | backups | Deliberately opted out via extraction rules — honest; the story is just never told in-app. |

---

## 2. COEXISTENCE DOCTRINE v1 (normative — paste into SUITE_DESIGN.md)

> ### Coexistence doctrine
>
> **CD-1 · Complement, don't replace.** Every understory app must add value
> NEXT TO the app the user already runs for that purpose. If a feature's value
> depends on the incumbent being absent, disabled, or evicted, the feature is
> misdesigned. The incumbent set assumed on the reference device: Tailscale
> (VPN), Bitwarden/1Password (autofill + password vault), Chrome/Brave
> (default browser), Aegis/Google Authenticator (TOTP), Samsung Secure Folder,
> Play Protect, Samsung Keyboard/Gboard.
>
> **CD-2 · Slot policy.** Scarce slots: VPN (VpnService), autofill service,
> default-app roles, accessibility service, notification listener, device
> admin, usage-stats grant.
> (a) **The VPN slot is permanently vetoed.** Tailscale holds it. No
> understory feature may require, request, or be designed around VpnService —
> including "temporary" tunnels and overlay-network transports.
> (b) **No feature may REQUIRE any other scarce slot.** Slots may be offered
> as explicit opt-ins only, and every opt-in must degrade gracefully: when the
> slot is not granted the app still delivers its core value, shows an honest
> status line naming who holds the slot ("Autofill: Bitwarden — passgen is in
> keyboard mode"), and never renders a dead control or a re-enable nag against
> the incumbent.
> (c) **Multi-enable surfaces (IME, share-sheet, "Open with…" choosers) are
> the preferred delivery channels** — they are additive by construction. Never
> set an IME as default programmatically; never prompt for a default-app role.
> (d) An incumbent taking a slot back is a **steady state, not an error**.
> UI must render it neutrally or positively, never as a fault to "fix".
>
> **CD-3 · Incumbent-interop policy.** Wherever the incumbent category has
> established formats, understory apps must speak them **in BOTH directions**:
> import AND export. Minimum format set: Aegis JSON (plain; encrypted when
> feasible), `otpauth://` URI lists, `otpauth-migration://` (import),
> Bitwarden CSV and JSON, Google Password Manager CSV, Proton Pass CSV/JSON,
> plain files via SAF for file vaults. **An app that imports a secret class
> but cannot export it is a roach motel and does not ship.** Export must be
> user-reachable UI, not a dead adapter class. Data the user entrusts to us is
> theirs to take to the incumbent at any time; making that easy IS the
> complement pitch.
>
> **CD-4 · Honest-UI policy.** (a) Zero dead controls: no button, switch,
> toggle, or picker whose action cannot complete on this build of this device
> — remove it, disable-with-reason, or gate it behind eng. (b) Zero capability
> overclaim: UI copy, notifications, manifest comments, README, roadmap rows,
> and suite capability beacons may only claim what the shipped code does
> today. "Phase 2" promises belong in docs, not onboarding copy. (c) Failure
> honesty: every silent dead-end (swallowed tap, silent hard-fail exit,
> suppressed notification) gets a visible, truthful message. (d) Status
> honesty: primary status surfaces must never overstate active enforcement or
> protection (no "N apps blocked" while blocking is paused; no green
> checkmarks derived from unreadable settings — degrade to "unknown").
> (e) Claims of automatic cleanup (clipboard auto-clear, session wipe) must
> match the implementation's actual guarantees, including its process-death
> behavior.

---

## 3. INTER-APP COHERENCE — the vault implementations

The brief says three; **it is actually four** independent vault engines, all
clones of the same v2 design (32-byte KEK wrapped by an auth-bound Keystore
key via the shared `Crypto.deviceAuthCipherFor*`), verified this session:

| Engine | File | Extras it carries |
|---|---|---|
| passgen `Vault` | `understory-passgen\passgen\...\Vault.kt:80` | master entry[0]; dead v1 reveal-lock remnants; entries = credentials |
| aegis `AegisVault` | `understory-aegis\aegis\...\AegisVault.kt:31` | master entry[0]; entries = OTP seeds |
| vault-folder `VaultFolder` | `understory-vault-folder\vault-folder\...\VaultFolder.kt:40` | multi-folder index (`VaultFolders`), per-blob GCM, no entry[0] |
| backups `BackupsVault` | `understory-backups\backups\...\BackupsVault.kt:40` | KEK doubles as recovery-key material; no entry[0] |

**Divergence risks (already materialized, not hypothetical):**
- **Same defect, four fates.** `setInvalidatedByBiometricEnrollment(true)`
  (shared `Crypto.kt:165`) bricks every vault on fingerprint re-enrollment.
  backups has a real recovery path (recovery key); passgen has a *fictional*
  one ("Settings → reset vault" that doesn't exist, passgen.md A19); aegis
  and vault-folder have a permanent dead-end. One engine → one recovery
  story, fixed once.
- **Same pattern, inconsistent hardening.** Atomic-replace + length-caps +
  trailing-byte-refusal exist in all four, but only because they were
  copy-evolved; vault-folder re-introduced a Compose-saveable crash the same
  file had already fixed for another type (vault-folder.md A8), and AAD
  binding exists only as an unused parameter. Fixes land in one clone and
  not the others (e.g. passgen's eng-authority fix vs the other three apps).
- **The master-entry[0] convention is load-bearing in two apps and absent in
  two** — and in both apps that have it, it leaks into user-facing lists
  (counted, rendered, pickable in the autofill picker, deletable) while the
  promised reveal path exists in neither (aegis.md A-U4, passgen.md rank 9).

**common-backup envelope consistency: it is NOT used consistently.**
Verified: common-backup is vendored in passgen, aegis, backups only —
vault-folder doesn't even include the module (its settings.gradle includes
only common-security). Live usage: **backups app only**
(`BackupsFlow` → `AesGcmPassphraseCodec`/`BackupEnvelope`). passgen's
`PassgenBackupAdapter` and `BackupFormat` and aegis's `AegisBackupAdapter`
are complete, unit-tested, and **referenced by nothing** (dead code); the
cross-app `BackupProvider` IPC surface the orchestration design requires
exists in zero apps (backups.md A-22). So the suite has one real envelope
format, one real consumer, two dead in-process adapters, and one app outside
the format entirely.

**Recommended consolidation direction (do NOT implement):** promote a single
`DeviceAuthVault` engine into common-security — one file format (version,
header caps, AAD binding blob-id/context, atomic replace, tmp sweep), one
KEK-wrap + unlock + lifecycle-lock manager, one recovery contract (mandatory
recovery-key escrow at create, `KeyPermanentlyInvalidatedException` detected
→ guided re-bind/reset), with per-app payload schemas on top. Keep
common-backup's `BackupEnvelope` as the ONLY at-rest export format
suite-wide, wire the two dead adapters to real UI or delete them, and give
vault-folder the module. Consolidation order of merit: recovery contract
first (it is the active data-loss cliff), file engine second, orchestration
IPC last (it is v2).

---

## 4. NAME / IDENTITY

1. **"aegis" collides with Aegis Authenticator** — the app's own complement
   target (`com.beemdevelopment.aegis`, the incumbent on the operator's
   radar). Verified: launcher label is literally `aegis`
   (`understory-aegis\...\strings.xml:3`), IME label "aegis keyboard". Two
   authenticator icons named Aegis on one phone; a store listing under this
   name invites trademark/confusion trouble and contradicts complement
   positioning by construction (aegis.md D-L5). **Decision-class ship
   blocker.** Package id `com.understory.aegis` can stay (not user-facing).
2. **vault-folder has three spellings:** repo `understory-vault-folder`,
   package `com.understory.vaultfolder` (verified `build.gradle.kts:12`),
   launcher label `vault folder` (strings.xml:3). Docs use "vault-folder";
   the capability table uses `vaultfolder`. Cosmetic but it seeds grep
   misses and doc drift; pick `vaultfolder` as the one machine name and
   note the repo name is legacy.
3. **All seven launcher labels are bare lowercase common nouns** —
   "browser", "firewall", "antivirus", "backups", "passgen", "vault folder",
   "aegis". In a launcher next to real apps these are ambiguous
   ("browser"?), unsearchable in a store, and two of them **overclaim by
   name**: "antivirus" is a static on-demand auditor (no real-time anything
   — its own UI says so) and "firewall" post-veto is an audit/advise tool
   that blocks nothing. Names are capability claims; the honest-UI policy
   applies to them.
4. **Capability vocabulary repeats the overclaim** (verified
   `SuiteCapability.kt` + `SuiteCapabilityRegistry.kt:56-77`): v1 peers
   advertise `BACKUP_ORCHESTRATOR` (orchestrates nothing — stub),
   `REALTIME_SCANNER` (static scanner), `NETWORK_FILTER` (vetoed core).
   Same rename pass, same table, one coordinated bump.
5. **Store-facing recommendation:** brand the family — "**Understory
   <Noun>**": Understory OTP (aegis), Understory Passkeys/Keys (passgen),
   Understory File Vault (vault-folder), Understory Backup (backups),
   Understory Safe View (browser), Understory Net Audit (firewall),
   Understory APK Check (antivirus). Keep repo/codenames internally; change
   `app_name`, store listings, and README first lines only. This kills the
   Aegis collision, the noun ambiguity, and the antivirus/firewall
   category overclaims in one decision.

---

## 5. TOP-10 SUITE-WIDE SHIP GAPS (ranked)

Aggregated from the seven sheets; ranking = user harm × doctrine violation ×
breadth. Sizes are for the consolidated item.

| # | Gap | Size | Tag | Detail / sources |
|---|---|---|---|---|
| 1 | **Firewall's enforcement core requires the vetoed VPN slot** — per-app blocking, port blocks, DNS-redirect, DNSCrypt routing all die with it; the preempted-banner actively nags the user to evict Tailscale. Reposition as observe/advise (remote-admin audit + Private DNS + Tailscale-posture panel + NetworkStats opt-in); demote the VPN engine to explicit no-Tailscale standalone mode. | L | REDESIGN | firewall.md D1-D3; slot matrix §1 |
| 2 | **Roach-motel secrets: no user-reachable export anywhere secrets live.** passgen vault is import-only (A29), aegis has no export of any kind (A-F1), both backup adapters are dead code, orchestration is a stub advertised as a capability. Violates CD-3 outright; combined with #3 it converts "alpha bug" into "permanent data loss". Minimum: passphrase-encrypted export + Bitwarden-CSV/otpauth-URI export paths. | L | FIX | passgen.md D2/D5, aegis.md D-M1/D-M2, backups.md D-6 |
| 3 | **Biometric re-enrollment bricks all four vaults; three of four have no recovery/reset UI, and passgen's unlock screen points at a reset flow that does not exist.** Mandatory recovery-key escrow (backups already has the model), `KeyPermanentlyInvalidatedException` detection, and a guarded reset path, suite-wide. | L | FIX | passgen.md D1, aegis.md D-L4, backups.md D-4, shared Crypto.kt:165 |
| 4 | **aegis silently generates wrong codes**: importers faithfully capture algorithm/digits/period, the generator hardcodes SHA1/6/30s; HOTP entries render as fake TOTP with a counter that never increments. The user discovers it at login. Fix generation params; fix-or-reject HOTP at import. | L | FIX | aegis.md D-L1/D-L3 (A-U1/A-U2) |
| 5 | **The secret-delivery layer is unviable as designed**: aegis IME instructs an unlock loop that can never succeed under release lock-on-leave; passgen autofill/IME "generate" commits passwords recorded nowhere (account-lockout trap); passgen IME can't type saved entries, which is the one path coexistence-mode (Bitwarden-holds-slot) users need. Needs the auth-trampoline/receipt-ledger redesigns. | L | REDESIGN | aegis.md D-L2, passgen.md D3/D13 |
| 6 | **Claims-ahead-of-code honesty cluster (suite-wide)**: BACKUP_ORCHESTRATOR/REALTIME_SCANNER/NETWORK_FILTER beacons; antivirus "what this catches" over empty KnownBad sets + false-green Play Protect card; backups' stripped-notifications progress promise + false 30s clipboard toast + unrestorable `.usbs` toggle; browser "Clear session" that doesn't and write-only I2P provider picker; vault-folder/passgen deposit-confirmation contracts contradicted by auto-run imports; stale RELEASE-BLOCKER comments. One coordinated honesty pass, mostly copy + gating. | M | FIX | antivirus.md D#3/D#4/D#11, backups.md D-1/D-2/D-6/D-12, browser.md D2-D4, vault-folder.md #3/#10/#14, passgen.md rank 4/7 |
| 7 | **Browser lacks its doorway and exit**: no share-target/VIEW intake with confirmation interstitial, no "open in default browser" hand-off — the entire complement premise ("inspect suspicious link here, continue in Chrome when trusted") is unreachable from other apps. Smallest high-leverage positioning fix in the suite. | M | REDESIGN | browser.md D1/D6 |
| 8 | **Main-thread crypto/IO across five apps** (Argon2id 64 MiB, vault re-encrypts, QR decode, SAF imports, file encrypt/export) — ANR-class freezes that also make every advertised loading state unrenderable; plus the vault-folder export crash (non-Parcelable in rememberSaveable — the one confirmed hard crash in the suite) and antivirus's doubly-inverted severity sort. One Dispatchers.IO + state-machine sweep. | M | FIX | vault-folder.md #1/#2, backups.md D-5, passgen.md rank 11, aegis.md D-M6, antivirus.md D#1 |
| 9 | **GUI shippable-bar debt, structurally identical in all seven apps**: strings.xml has 1-2 entries (all copy hardcoded Kotlin, `resourceConfigurations=["en"]`), ~60+ hardcoded hex colors per app over a token-free `darkColorScheme()`, no Scaffold/TopAppBar, no light theme (undeclared), portrait-locked, sub-12sp body text, near-zero TalkBack semantics, silent tamper hard-fail exits. Fix once in common-security (theme tokens + shared components + explanation screen), inherit seven times. | L | FIX | every sheet §C; browser.md D14 |
| 10 | **Identity + release hygiene decisions**: rename aegis's store face (incumbent collision), adopt the suite naming scheme (§4), rename the three overclaiming capabilities, unify vaultfolder spelling, and fix the `${applicationId}.suitecaps` eng/prod install collision in the four unpatched apps. Small code, but decision-gated and blocking store presence. | M | FIX | aegis.md D-L5; §1/§4 above; browser.md D11, vault-folder.md #7, antivirus.md D#8 |

**Aggregate ship-gap counts across the seven per-app sheets** (as sized by
each sheet): **L = 16, M = 32, S = 58**. The ten items above are the
consolidation, not the union — per-app S items remain tracked in their sheets.

### Features that cannot ship as designed (suite roll-up)

- firewall: VPN-slot enforcement (per-app drop / port blocks / DNS-redirect / DNSCrypt routing) — evicts Tailscale, vetoed.
- firewall: `/proc/net` port-block discovery — structurally a no-op on every minSdk-33 device.
- browser + firewall: Yggdrasil overlay (committed VpnService design — vetoed); Lokinet tun path per OVERLAY_NETWORKS.md; firewall's cross-process I2P "live status" (no channel exists).
- aegis: IME code-typing under release lock-on-leave posture; non-default-parameter TOTP + HOTP (wrong codes by construction); master-KEK "recovery" entry (no reveal path permitted by its own doctrine).
- passgen: autofill/IME generate-without-record (account-lockout trap); "Settings → reset vault" recovery copy (no such flow).
- backups: self-hosted network destination (no-INTERNET posture); scheduling under the per-operation-auth key policy; `.usbs` full-content stream (write-only + framing corrupts on live-file change).
- antivirus: "inotify install-watching" roadmap item (rootless-impossible); Play Protect DISABLED detection via the defunct `package_verifier_enable` key; accessibility/device-admin risk rules keyed on `uses-permission` (component-protection permissions — never fire on real malware).

---

## 6. WHAT THE SUITE IS, WHEN IT'S HONEST

Seven offline, permission-stripped, mutually-attesting utilities that sit
**beside** the phone's incumbents: a hardened password *generator with
receipts* next to Bitwarden; an offline second-home OTP vault (renamed) next
to Aegis Authenticator; an auditable encrypted drop-box next to Secure
Folder; an envelope-encryption layer under Syncthing/Drive next to Google
One; a quarantine viewer one share-tap from Chrome; a network-posture
auditor next to Tailscale; an explainable second-opinion scanner next to
Play Protect. Every one of those sentences is within reach of the existing
code — and none of them is true yet without the ten items above, of which
the non-negotiables for *any* public alpha are #2, #3, #4 (data loss and
wrong security output), the crash in #8, and the honesty pass in #6.
