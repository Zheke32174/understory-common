# Release blockers — V2

Supersedes `docs/RELEASE_BLOCKERS.md` for the V2 public alpha. The old file's
build/signing/distribution items remain valid and are carried forward verbatim
under "Carried from v1" below — they are still release-blocking. This file adds
the V2 audit-driven definition of done.

Rule unchanged: this is not a feature roadmap. It is the list of "we will not
ship until this is true." No schedule. We iterate until every **non-negotiable**
is green, then ship whatever is at HEAD as the V2 alpha. Polish items should be
green too but a documented, honestly-surfaced known-issue may ship. Deferred
items are explicitly NOT alpha-blocking.

Execution order for all of the below: `docs/design-v2/RELEASE-PLAN-V2.md`
(waves 1→2→3). Each blocker names its owning design doc.

---

## NON-NEGOTIABLE (the alpha does not ship until all are true)

These are the intersection of `audit-v2/SUITE.md §5` and each per-app
"non-negotiable for any public alpha" line: **data loss, wrong security output,
the one hard crash, and the honesty pass.**

### N1 · No vault can silently become a permanent data-loss trap
Biometric re-enrollment invalidates the Keystore key and bricks all four vaults
today; three of four have no recovery UI; passgen points at a reset flow that
does not exist. Alpha requires, in all four vault apps (passgen, aegis,
vault-folder, backups): `KeyPermanentlyInvalidatedException`/key-state detection,
a real guarded reset, and a user-held recovery export (mandatory recovery-key
escrow at create).
Owner: **`design-v2/shared-vault-recovery.md`** (§2/§3/§4) + each vault app's
recovery task (`passgen.md §3`, `aegis.md §4`, `vault-folder.md §4`, `backups.md §8`).

### N2 · No roach motel — every ingested secret class has a user-reachable export
CD-3. An app that imports a secret class but cannot export it does not ship.
Alpha requires:
- passgen: Bitwarden CSV+JSON **import AND export** + passphrase-encrypted export. (`passgen.md §4`)
- aegis: export (otpauth:// list / Aegis-compatible JSON / encrypted `.usbe`) + **import real Aegis Authenticator JSON**. (`aegis.md §3`)
- vault-folder: working plain-file SAF export (the crash-fixed path) + encrypted-envelope export. (`vault-folder.md §1/§5`)
- backups: the `BackupEnvelope` export/restore pair is real (see N4). (`backups.md §2`)
Owner: **`design-v2/shared-vault-recovery.md §5`** + `suite-coexistence.md §3` (interop matrix).

### N3 · No wrong security output
A tool that emits a confident-but-false security signal is worse than none.
Alpha requires:
- aegis: parameter-correct OTP (algo/digits/period honored; HOTP counter real). A faithful import that generates rejectable codes is the sharpest instance. (`aegis.md §1`)
- antivirus: abuser detection that actually fires (declared + enabled a11y/device-admin/notif-listener), and the Play Protect card must NOT false-green (UNKNOWN on the modern-device missing key). (`antivirus.md §2/§3`)
- firewall: no status green derived from an unreadable Secure key or an inference gap — degrade to "unknown." (`firewall.md §5.1/§9`)

### N4 · The one confirmed hard crash is fixed + no write-only backup
- vault-folder export crash (non-Parcelable in `rememberSaveable`) fixed. (`vault-folder.md §1`)
- backups: `.usbs` full-content stream must have a working restore decoder + the UDCSv002 framing fix, OR the toggle is dropped until it does. A backup with no restore is not a backup. (`backups.md §2`)

### N5 · Honesty pass (CD-4) — beacons, names, dead controls, status, cleanup
- Capability beacon de-overclaim: `BACKUP_ORCHESTRATOR`/`REALTIME_SCANNER`/`NETWORK_FILTER` renamed to what the code does; browser advertises nothing until its intake ships; `BACKUP_ORCHESTRATOR` re-added only at beacon v2 when a peer responds. (`suite-coexistence.md §1`)
- aegis store-face rename (decision-class blocker — kills the incumbent name collision). (`aegis.md §7`, operator decision)
- No dead control on any build; every silent dead-end surfaces a truthful message; no status green from an unreadable setting; cleanup claims (clipboard/session/shred) match real guarantees incl. process death. (`shared-gui.md §8`, `suite-coexistence.md §CD-4`, per-app copy sections)

### N6 · Doctrine conformance — the VPN slot and every scarce slot
- No app requires, requests, or is designed around VpnService as a primary verb. firewall reposition to observe/advise; the packet engine is a default-off, VPN-detecting Standalone mode only, behind a fail-closed guardrail; no evict-nag against Tailscale. (`firewall.md §0/§3/§4`, `suite-coexistence.md §CD-2`)
- No app requires any other scarce slot (autofill/IME/default-role/accessibility/notif-listener/usage-stats); every opt-in degrades gracefully. (`suite-coexistence.md §CD-2b`)

### N7 · eng/prod install collision fixed (dev-blocking, so it gates the work)
`android:authorities="${applicationId}.suitecaps"` in the four+ unpatched apps —
otherwise eng+prod can't co-install for on-device testing of everything else.
Owner: **`suite-coexistence.md §4.2.2`** (+ per-app `§suitecaps` tasks).

---

## POLISH (should be green; a documented known-issue MAY ship)

These raise the app above "shippable-bar debt" but a single honestly-surfaced
gap need not block the alpha if the operator accepts it as a known issue.

### P1 · Shared GUI adoption — theme tokens, Scaffold, states, a11y, strings
Seven hardcoded-hex dark themes → one `UnderstoryTheme`; `SuiteScaffold` +
empty/loading/error/`FatalScreen` states; `SwitchRow`/`SliderRow` merged
semantics; masked-secret `contentDescription`; 48dp targets; strings→resources;
drop `resourceConfigurations=["en"]`; 14sp body floor. Lint gates
(`HardcodedText`=error, `UnderstoryHardcodedColor`) make it permanent.
Owner: **`design-v2/shared-gui.md`**.

### P2 · Main-thread crypto/IO removed across five apps
Argon2id 64 MiB, vault re-encrypts, QR decode, SAF imports, file encrypt/export
moved to `Bg.io`/`Bg.cpu` so advertised loading states actually render.
Owner: **`shared-gui.md §5`** + per-app threading tasks.

### P3 · The doorway/exit + honest dead-ends (browser)
Share-target + interstitial intake, open-in-default hand-off, DownloadListener
+ blocked-scheme feedback, "Clear now" real wipe, custom error panel, JS
allowlist screen, proxy shrink. (These are largely N-adjacent honesty; the
positioning intake itself is polish-blocking, not data-loss.)
Owner: **`design-v2/browser.md`**.

### P4 · firewall dashboard completeness
Tunnel Posture, Traffic-by-App (opt-in), Egress Canaries, Restrict Worklist,
Limits card, Remote-Admin Audit fixes. The observe/advise value beside Tailscale.
Owner: **`design-v2/firewall.md §5`**.

### P5 · antivirus completeness beyond N3
Signed offline blocklist + seed, periodic re-scan (opt-in), tamper
report-not-die, unit tests for the hostile parser, GUI per-screen fixes.
Owner: **`design-v2/antivirus.md`**.

### P6 · Suite naming scheme + machine-name hygiene
Adopt "Understory <Noun>" store faces (beyond the aegis rename in N5);
`vaultfolder` as the one machine name.
Owner: **`suite-coexistence.md §4.3`** (operator decision to adopt as a set).

### P7 · Per-app small honesty/UX (the S-tier per-sheet items)
Confirm-on-delete, empty states, deposit MIME trims, doc/comment drift, warm-task
`onNewIntent`, copy-window honesty, etc. — tracked in each per-app design's
disposition table; ship green where cheap, known-issue where not.

---

## DEFERRED (explicitly NOT V2-alpha blocking)

- **Four-engine `DeviceAuthVault` merge.** HIGH migration risk; lands after the recovery/export safety net is stable, never in the same release. (`shared-vault-recovery.md §7`)
- **Cross-app `BackupProvider` orchestration IPC / full orchestrator identity.** The deposit-intent contract is the v2-incremental stand-in; `BACKUP_ORCHESTRATOR` re-appears at beacon v2 when a peer responds. (`backups.md §3`)
- **`APK_AUDITOR`→firewall signed-advisory wire contract.** Receivers built; wire ships post-alpha. (`firewall.md §5.5`)
- **firewall packet forwarder / DNS-redirect.** `net-engine` code dormant. (`firewall.md §7`)
- **browser I2P / overlay networks.** eng-gated experimental only. Lokinet/Yggdrasil permanently vetoed. (`browser.md §6`)
- **Encrypted-Aegis-vault / 2FAS-encrypted / Steam OTP import.** Honest reject now; decrypt is v1.5. (`aegis.md §3.2`)
- **passgen Samsung dual-slot autofill.** Ships only if SM-S948U verification confirms the path; else DROP to keyboard mode. (`passgen.md §7.3`)
- **backups scheduling.** SOK+WorkManager recommended; may ship manual-only for alpha. (`backups.md §4`)
- **Localization.** Strings extractable; no non-en locale ships.
- **Phase-2 sandbox / phase-3 defensive toolkit.** Unchanged from `SUITE_DESIGN.md`; not V2-alpha scope.

---

## CARRIED FROM V1 (still release-blocking — see `RELEASE_BLOCKERS.md` for detail)

These pre-V2 blockers are unchanged and still gate the alpha:

- **Gradle dependency lockfile committed** (pins transitive deps by hash).
- **Independent byte-identical rebuild demonstrated** (recipe exists; no external rebuild yet) + commit the Gradle wrapper.
- **SHA-256 publication channel separate from the GitHub raw URL** (out-of-band, offline-signed hashes manifest).
- **Samsung One UI full retest** with the TestingMode flags flipped false (FLAG_SECURE / lock-on-leave / excludeFromRecents) — the multi-device sweep the whole V2 phone-deploy gate rides on.
- **Tested on at least one Pixel / AOSP device.**
- **README rewritten for users** (plain-language trust-assumptions walkthrough per app + non-developer install steps).

The v1 "Resolved" list (release keystore, cert pins, verifyCertPin gate,
reproducibility recipe, threat model, isolated APK parser, NSC lockdown, exported
audit, ContentProvider hardening, hardened WebView core) stays resolved — V2
builds on it and must not regress it.

---

## Adding to this file

Same rule as v1: a new item is "we will not ship until this is fixed" — add it
under the right group with the why, the rough size, and its owning design doc.
Anything we decide to ship-with becomes an accepted known-issue, not a blocker.
Keep the list honest so it doesn't become a wishlist.
