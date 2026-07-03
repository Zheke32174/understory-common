# Understory Suite — V2 RELEASE PLAN (alpha → ship)

Status: **PLAN** (master). Written 2026-07-03. This is the definitive,
waved, one-commit-per-repo execution order that turns the ten per-app +
shared + firewall-final design docs in `docs/design-v2/` into a shippable
public alpha. It does not restate those designs — it sequences them, calls
out the cross-app dependencies, and states the definition of done
(superseded formally by `docs/RELEASE_BLOCKERS_V2.md`).

**Design-only lineage.** Every task below traces to a design doc that
already names exact files, classes, and APIs. This plan adds no new
mechanism; where a task says "see X.md §N," that section is the
implementable spec. An implementer takes a wave, opens the referenced
sections, and builds.

Inputs (all read in full for this plan):
- `design-v2/suite-coexistence.md` — coexistence doctrine + honest beacons + naming.
- `design-v2/shared-gui.md` — `UnderstoryTheme` + shared components + threading/lint gates.
- `design-v2/shared-vault-recovery.md` — `VaultRecovery`/export/reset shared contract.
- `design-v2/passgen.md`, `aegis.md`, `vault-folder.md`, `backups.md`, `browser.md`, `antivirus.md`, `firewall.md` (firewall = the final B-base/C-graft doc).
- `audit-v2/SUITE.md` + seven per-app audit sheets.

---

## 0. THE SHAPE OF THE PLAN

Three waves. Each wave is **one commit per repo**, CI-gated, phone-deployed
before the next wave starts. Shared modules (`common-security`,
`common-backup`, new `net-engine`) are **authored in canonical
`understory-common` FIRST**, then re-vendored byte-identical into each app
repo in the same wave commit (the audit verified vendored copies are
byte-identical today; that invariant must hold after every wave —
`shared-vault-recovery.md` preamble).

| Wave | Theme | Why this order |
|---|---|---|
| **Wave 1 — Shared infra** | M3 theme tokens + shared components; `VaultRecovery`/export/reset contract; honest capability beacons + provider-authority fix; `net-engine` salvage; the suite-wide honesty copy pass | Everything in waves 2–3 depends on these. The theme, the recovery contract, the beacon taxonomy, and the `${applicationId}.suitecaps` fix are consumed by every per-app design. Landing them first means waves 2–3 are adoption, not invention. |
| **Wave 2 — Per-app FIX/REDESIGN** | Each app's data-loss, correctness, roach-motel, and doorway/exit fixes | The load-bearing product work. Depends entirely on wave-1 shared contracts existing. |
| **Wave 3 — GUI polish + honesty pass** | Adopt the shared theme/components in every screen; a11y; strings→resources; final honesty/status sweep over the new wave-2 copy | Sweeps last so it covers the copy and screens wave 2 introduced. Mechanical once wave 1's system exists. |

**Cross-repo ordering inside a wave:** `understory-common` (canonical) is
committed first in every wave; then the seven app repos re-vendor and adopt.
Within the app repos, **passgen is the reference adopter** in every wave
(it already fixed the provider-authority collision, `passgen/build.gradle.kts:74`,
so it is the cleanest starting point — `shared-gui.md §7`). Apps 2–7 are
mechanical repeats of passgen's wave commit.

**Deploy gate between waves:** CI green (lint `abortOnError`, unit tests) +
an on-device smoke pass on the SM-S948U (the multi-device retest that is
already a standing blocker — `RELEASE_BLOCKERS.md` Multi-device). No wave
starts until the prior wave is green on device.

---

## 1. WAVE 1 — SHARED INFRA (lands in `understory-common` first, then vendored)

Everything here is authored once in canonical `common-security` /
`common-backup` / new `net-engine`, then re-vendored. Order within the wave
is dependency-sorted.

### 1A. Honest capability beacons + provider-authority fix (S–M) — do first, it is pure de-overclaim
Owner: `suite-coexistence.md §1`, §4.2. Unblocks the CD-4(b) honesty floor at the mesh layer.

- [ ] **FIX (S)** — Rename the `SuiteCapability` enum: `BACKUP_ORCHESTRATOR`→`BACKUP_ENVELOPE`, `REALTIME_SCANNER`→`APK_AUDITOR`, `NETWORK_FILTER`→`NET_POSTURE_AUDIT`; add `OTP_STORE`; keep `OTP_VAULT` for the future issue-code power; keep `BACKUP_ORCHESTRATOR` **removed from the v1 map** (re-add as an enum value only when the deposit IPC ships). (`suite-coexistence.md §1.3/§1.4`)
- [ ] **FIX (S)** — Edit every consumer's `KNOWN_PEERS` rows to the corrected "PROVIDES" column: passgen→`IDENTITY_VAULT`; aegis→`OTP_STORE`; vaultfolder→`FILE_VAULT`; backups→`BACKUP_ENVELOPE`; firewall→`NET_POSTURE_AUDIT`; antivirus→`APK_AUDITOR`; **browser→`emptySet()`** until its intake ships in wave 2. (`suite-coexistence.md §1.4`)
- [ ] **FIX (S)** — Update the footer short-name mapping (`SuiteStatusFooter.kt:150-155`) to the renamed capabilities.
- [ ] **FIX (S)** — Provider authority `android:authorities="${applicationId}.suitecaps"` in every app manifest that still hardcodes it (browser, vault-folder, antivirus, firewall, aegis, backups — audit each; passgen already done). Fixes `INSTALL_FAILED_CONFLICTING_PROVIDER` eng/prod collision. (`suite-coexistence.md §4.2.2`)
- [ ] **DROP (S)** — Do NOT introduce `SuiteCapsProvider providedVersion` changes; only the names/maps change, not versions. Document eng builds as mesh-invisible by design.

Cross-app dependency: this is **one coordinated commit** across canonical +
seven vendored copies (byte-identical). No app may advertise a stale name;
the mechanism already forces coordinated change (a peer cannot self-grant).

### 1B. `UnderstoryTheme` + shared GUI components (L) — the design system everything inherits
Owner: `shared-gui.md §1–§6`. New files under `common-security/.../ui/`.

- [ ] **REDESIGN (M)** — `ui/theme/` : `Color.kt` (role-named tokens, dark default matching today + full light scheme), `Type.kt` (M3 scale, 14sp body floor), `Shape.kt`, `Spacing.kt`, `Theme.kt` (`UnderstoryTheme(accent, darkTheme, dynamicColor=false)` + `UnderstoryAccent` enum, one seed per app). (`shared-gui.md §1`)
- [ ] **FIX (M)** — `ui/components/` : `SuiteScaffold` (TopAppBar + reused `SuiteStatusFooter`), `SuiteStates.kt` (`UiState<T>`, `LoadingState`/`EmptyState`/`ErrorState`/`UiStateHost`, `FatalScreen`), `SuiteComponents.kt` (`SuiteCard`, `SuiteSectionHeader`, `SuiteListRow`, `SwitchRow`, `SliderRow`, `RevealToggle`), `SuiteDialogs.kt` (`ConfirmDestructiveDialog`). Reuse existing `SecureButton`/`secureClickable`. (`shared-gui.md §2`)
- [ ] **FIX (S)** — Refactor the two existing shared components (`SuiteStatusFooter.kt`, `DiagnosticsScreen.kt`) onto tokens as proof-of-coverage (no behavior change). (`shared-gui.md §2.1`)
- [ ] **FIX (S)** — `ui/Bg.kt` : shared `Bg.io`/`Bg.cpu` dispatchers + `produceUiState`. common-security has zero `Dispatchers` today; this is the suite's one background-dispatcher home. (`shared-gui.md §5.2`)
- [ ] **FIX (S)** — Shared strings in `common-security/res/values/strings.xml` (`cd_back`, `cancel`, `state_loading`, `retry`, `cd_password_hidden`, …). (`shared-gui.md §4`)
- [ ] **FIX (S)** — Gradle: promote material3 + material-icons-extended to `api`; add `kotlinx-coroutines-android`. (`shared-gui.md §6`)
- [ ] **FIX (S)** — Lint gates in the shared config: flip `HardcodedText`→error; add `UnderstoryHardcodedColor` detector (flags `Color(0x…)` / font-`.sp` outside `ui.theme`); ship `docs/design-v2/gui-review-checklist.md` (main-thread-crypto / bare-`Switch`/`Slider` / sub-12sp review rule). These make the wave-3 migration irreversible. (`shared-gui.md §5.3`)

Cross-app dependency: **no app adopts the theme in wave 1** — wave 1 only
*ships* the system in common-security (+ vendored copies). Per-screen
adoption is wave 3. Landing the module + lint config now means wave-2 new
screens are authored against tokens from birth.

### 1C. Shared `VaultRecovery` / export / reset contract (L) — the data-loss cliff, consolidated
Owner: `shared-vault-recovery.md` (whole doc). Authored in `common-security`
+ `common-backup`; adopted by passgen/aegis/vault-folder/backups in wave 2.

- [ ] **FIX (M)** — `common-security/VaultRecovery.kt`: `VaultKeyState` classifier (`OK`/`NEVER_CREATED`/`PERMANENTLY_INVALIDATED`/`TRANSIENT_AUTH_FAILED`), `classifyUnlockFailure`, `keyStateAtStartup`. Keep `setInvalidatedByBiometricEnrollment(true)` — detect, don't weaken. (`shared-vault-recovery.md §1/§2`)
- [ ] **FIX (M)** — Shared composables in `common-security`: `VaultRecoveryScreen` (parameterized by `VaultResetHooks`), `VaultExportScreen`, `VaultImportScreen`; recovery-key escrow (`RecoveryEnrollment`), `shouldPrompt` cadence helper. Export/import always via `BackupEnvelope`+`AesGcmPassphraseCodec` off `Bg.io`. (`shared-vault-recovery.md §4/§5`)
- [ ] **FIX (S)** — Split-key recovery: new vaults mint a distinct recovery key (`Crypto.randomBytes(32)`), envelope payload carries adapter cleartext (not the KEK); keep backups' legacy KEK-as-recovery path behind envelope `schemaVersion`. (`shared-vault-recovery.md §3.4`)
- [ ] **FIX (S)** — Shared honest recovery/clipboard copy in `common-security` strings; `Clipboard.kt` honesty rule (no fixed-seconds promise across process death). (`shared-vault-recovery.md §6`)
- [ ] **DROP (S)** — Delete passgen's bespoke `BackupFormat.kt` lineage is a wave-2 per-app act, but the SHARED decision (envelope is the one at-rest format) lands here.

Cross-app dependency: this is the item the audit ranks "consolidate first —
it is the active data-loss cliff." It lands BEFORE the format merge (deferred
to a later pass, `shared-vault-recovery.md §7`) so every app has a working
export as a safety net before any at-rest churn.

### 1D. `net-engine` salvage library (M) — firewall packet code survives cleanly
Owner: `firewall.md §7`. New `understory-common` module `net-engine`.

- [ ] **FIX (S)** — Create `net-engine` (`com.understory.net.engine`); move `VpnPacketParser.kt` + `DnsRedirector.kt` + `DropStats.kt`; add JVM unit tests for `VpnPacketParser` (valid/truncated IPv4+UDP; RFC-768 zero-checksum). Not called by the shipping app in v1 (compiled, tested, dormant). (`firewall.md §7`)

Cross-app dependency: only firewall vendors `net-engine`. Landing it in
wave 1 lets wave-2 firewall drop the dead/vetoed packet code cleanly.

### 1E. Suite-wide honesty copy pass (M) — the CD-4 overclaim sweep
Owner: `SUITE.md §5 gap #6`. Mostly copy + gating, spread across repos but
tracked as one honesty item.

- [ ] **FIX (S per repo)** — Correct README / roadmap / manifest-comment / onboarding overclaims to match shipped code: backups (drop "orchestrates every peer", "self-hosted endpoint", "schedules"); antivirus ("what this catches" over empty KnownBad; inotify real-time); browser ("Clear session" lie, I2P over-claim); firewall (VpnService "owns the slot" comment, "turn firewall off" section); vault-folder/passgen (deposit-confirmation contract vs auto-import); stale RELEASE-BLOCKER comments. (per-app docs' §copy sections)

Cross-app dependency: much of this copy is *replaced* by wave-2 fixes (real
export, real deposit confirm, real reset). Land the pure-copy corrections in
wave 1 where the fix isn't code-bearing; land the copy that *accompanies* a
mechanism in that mechanism's wave-2 task.

---

## 2. WAVE 2 — PER-APP FIX / REDESIGN

Depends on wave 1 (theme module present, `VaultRecovery`/export/reset shared
contract present, beacons renamed, `net-engine` present). Each app is one
commit per repo. Ordered within the wave by ship-blocking weight; the four
vault apps' recovery/export adoption is the top priority (data-loss + roach
motel are the non-negotiables).

### 2.1 passgen (`com.understory.passgen`) — repositioned as generator + ledger + migration buffer
Owner: `passgen.md`. Ordered.

- [ ] **REDESIGN (M)** — Receipt store (`Receipts.kt` + non-auth-bound `passgen_receipts_device_auth_v1` Keystore key) written by all three generate paths (autofill/IME/clipboard); "keep generated value" setting (default off); Receipts screen. Resolves the account-lockout trap (A7/A11). (`passgen.md §1/§2/§5.1`)
- [ ] **FIX (M)** — Adopt shared `VaultRecovery`: invalidated-key detect + real Recovery/Reset screen (kills the fictional "Settings → reset vault", A19); receipts survive vault reset (different key). (`passgen.md §3`)
- [ ] **FIX (M)** — Real export/import: drop `BackupFormat` HOTP/KEK prereq → passphrase-encrypted `.ukbackup` export; add **Bitwarden CSV + JSON import AND export** + generic CSV; Export screen (encrypted lane + plaintext hand-off lane); Restore screen; wire or delete `PassgenBackupAdapter`. Kills the roach motel. (`passgen.md §4`)
- [ ] **REDESIGN (M)** — IME v2: "type a saved entry" via transparent `ImeFillActivity` trampoline; IME failure honesty (status line); receipts-typeable. The coexistence path for Bitwarden-holds-slot users. (`passgen.md §6`)
- [ ] **FIX (S)** — ACTION_VIEW import confirmation interstitial (parse-only → preview → explicit commit); makes the manifest contract true. (`passgen.md §9`)
- [ ] **REDESIGN-to-gated (S)** — Autofill status-first copy; delete Credential-Manager contradiction; gate Samsung dual-slot on a verified check, default to keyboard mode; **DROP** dual-slot if SM-S948U verification fails. (`passgen.md §7`)
- [ ] **FIX (S)** — Vault/ledger UI: hide master entry from list + picker; confirm-on-delete; empty state; lock-after-pick; secure picker rows; off-main import/export threading. (`passgen.md §8/§10`)
- [ ] **DROP (S)** — Dead v1 reveal-lock constants + `Crypto.generateMasterPassword` (unused). (`passgen.md §1.4`)

### 2.2 aegis (`com.understory.aegis`) — the OTP vault, beside Aegis Authenticator
Owner: `aegis.md`. Ordered (correctness first — nothing is honest until codes are right).

- [ ] **FIX (L)** — Parameter-correct OTP generation: extend shared `Totp` (algo/period/digits), `AegisCode` helper at both call sites, RFC-6238 test vectors. Fixes silently-wrong SHA256/SHA512/8-digit/period≠30 codes. (`aegis.md §1.1–§1.3`)
- [ ] **FIX (M)** — HOTP: real counter increment + persist-before-reveal + "generate next code" advance UI (no fake countdown ring); IME persist-before-commit. (`aegis.md §1.4`)
- [ ] **FIX (M)** — Adopt shared `VaultRecovery`: detect `KeyPermanentlyInvalidatedException`, recovery screen (restore-from-backup / reset), wire `AegisVault.delete` (implemented, zero call sites today). (`aegis.md §4.2`)
- [ ] **REDESIGN (M)** — Remove the fake master-KEK `entry[0]` + one-time legacy self-heal on unlock; honest first-run/count; rewrite fictional "paper transcription / Phase 2" onboarding copy. (`aegis.md §4.3`)
- [ ] **FIX (M)** — Export + interop (kill the roach motel): Export sheet (otpauth:// list / Aegis-compatible JSON / encrypted `.usbe`); **import real Aegis Authenticator JSON** (`db.entries[].info`), 2FAS; honest reject for encrypted-Aegis + steam; dedup-merge unifying file + QR + adapter. (`aegis.md §3`)
- [ ] **REDESIGN (L)** — IME auth-trampoline: `AuthTrampolineActivity` + `imeSession` TTL slot (does not weaken MainActivity lock-on-leave); enablement UX; compute code at click-time (kills stale build-time codes). (`aegis.md §2`)
- [ ] **FIX (S)** — `onNewIntent` for warm-task "Open with aegis"; `+`-in-migration fixture-gated hardening; copy-window honesty (one source of truth); secret-field masking. (`aegis.md §5.4/§6`)
- [ ] **DECISION-GATED (S)** — Store-facing name → **Understory OTP** (kills the Aegis collision). See §5 Operator Decisions. (`aegis.md §7`)

### 2.3 vault-folder (`com.understory.vaultfolder`) — encrypted drop-box beside Secure Folder
Owner: `vault-folder.md`. Ordered.

- [ ] **FIX (S)** — Export crash fix: hold entry-id string in `rememberSaveable`, resolve on return (the one confirmed hard crash in the suite); `"w"`→`"wt"`; drop dead `createOutput` launcher. **Ship first.** (`vault-folder.md §1`)
- [ ] **FIX (S)** — Provider authority `${applicationId}.suitecaps` (also in wave-1 sweep; verify). (`vault-folder.md §8`)
- [ ] **FIX (M)** — Off-main-thread crypto/IO + `OpState` state machine on add/export; folders defects: main-executor biometric callbacks (or shared `BiometricAuth` shim), `ERROR_CANCELED`, `weight(1f)`, `pruneOrphans`, delete-guard parity, rename UI, success-out-of-error-slot. (`vault-folder.md §2/§6`)
- [ ] **FIX (M)** — Deposit confirm interstitial (metadata-only) + `onNewIntent` warm-task + drop `BROWSABLE`; makes the documented confirm contract true. (`vault-folder.md §3`)
- [ ] **FIX (M)** — AAD binding (folderId+blobId), blob/metadata format **v2**, done in the SAME format bump as the recovery `header_v2`. (`vault-folder.md §7`)
- [ ] **FIX (M)** — Adopt shared `VaultRecovery` + recovery-key escrow (`recovery.bin` header slot; add `common-backup` to `settings.gradle.kts`); guarded multi-folder reset; honest backup positioning + real "send encrypted copy to Backup" hand-off; fix "#7"→"#4" copy. (`vault-folder.md §4`)
- [ ] **REDESIGN (L)** — In-app isolated-process memory-only viewer (`ViewerRenderService`, `isolatedProcess`; image/text/PDF via in-memory `ParcelFileDescriptor`; FLAG_SECURE; no cache/thumbnail leak). Removes the export-to-view privacy penalty. (`vault-folder.md §5`)
- [ ] **FIX (S)** — Doc/comment drift (stale RELEASE-BLOCKER comments, manifest isolation comment, `SUITE_DESIGN.md` BackupProvider/permission rows); orientation unlock + resizeable. (`vault-folder.md §9.4/§10`)
- [ ] **DECISION (S)** — Store-facing name → **Understory File Vault**; one machine name `vaultfolder`. (`vault-folder.md` top)

### 2.4 backups (`com.understory.backups`) — encrypted-envelope tool + suite collector
Owner: `backups.md`. Ordered (honesty-now first, then data-loss, then the real orchestration mechanism).

- [ ] **FIX (S)** — Honesty-now: un-strip `POST_NOTIFICATIONS` + runtime-request + degrade; beacon v1→`BACKUP_ENVELOPE` (done in wave-1 1A; verify); stub sections default OFF + hidden; clipboard 30s clear implemented + reworded; README reword (self-hosted/scheduling/orchestration). (`backups.md §6/§0/§8.3/§10`)
- [ ] **FIX (M)** — Adopt shared recovery: mandatory recovery-key escrow at Setup; Unlock re-bind on `KeyPermanentlyInvalidatedException`. backups owns the reference model; this is the backups-side UI. (`backups.md §8`)
- [ ] **FIX (M)** — Restore/import core: format-detecting `sniff()` (`USBE`/`USTRSTRM`); device-snapshot bundle report (inspect/export, honest "not re-applied"); off-main-thread crypto + real loading states. (`backups.md §2.1/§2.2/§7`)
- [ ] **FIX + REDESIGN (M)** — `.usbs` content-stream restore (`UserDirsContentRestore`) + **UDCSv002 self-delimiting framing** (fixes corruption on live-file change) + path-traversal guard. (`backups.md §2.3/§2.4`)
- [ ] **REDESIGN (M)** — SAF-tree backup sources (`DocumentFile` walk, not raw `File`) + explicit per-source coverage; DROP self-hosted endpoint; surface Syncthing/USB complement. (`backups.md §5`)
- [ ] **REDESIGN (M)** — Scheduling via non-auth-bound snapshot-only key (SOK) + WorkManager, metadata-only, honest boundary (no vault secrets unattended). Alternative: drop scheduling from docs if deferred. (`backups.md §4`)
- [ ] **REDESIGN / STAGED (M)** — Deposit-intent collect contract (`SuiteBackupContract`, `ACTION_DEPOSIT_BACKUP`, signature-gated); `collect` screen disabled-with-reason until a peer responds; re-add `BACKUP_ORCHESTRATOR` at beacon v2 only when a peer ships the responder. (`backups.md §3/§3.4`)
- [ ] **FIX (S)** — Local snapshots layout/confirm/retention (`rotate` wired); encrypt-screen 16 MiB pre-flight cap; `.usbe` VIEW filter hand-off; complement cards. (`backups.md §9/§11/§13`)

### 2.5 browser (`com.understory.browser`) — "Understory Safe View" quarantine viewer
Owner: `browser.md`. Ordered.

- [ ] **REDESIGN (M)** — The doorway (D1): `ACTION_SEND text/plain` share target (always) + opt-in `ACTION_VIEW http/https` alias (first-run, `setComponentEnabledSetting`) → **one mandatory `IntakeInterstitial`** (full URL, host-emphasized, JS-OFF, source line). Neutralizes URL-injection. **This ships browser's `HARDENED_BROWSER` beacon** (wave-1 mapped it to empty until now). (`browser.md §2`)
- [ ] **FIX (S)** — `normalizeUrl` mixed-case fix + `NotAUrl` chip (search-off honesty). (`browser.md §3.3/§3.4`)
- [ ] **FIX (S)** — "Clear session" lie → "Clear now" real wipe (cookies/storage/history/page); stale-comment + placeholder-copy honesty. (`browser.md §3.6`)
- [ ] **FIX (S)** — Honest dead-ends: `DownloadListener` snackbar + "Download in Chrome" hand-off; blocked-scheme feedback + `mailto:`/`tel:`/`sms:` opt-out hand-off. (`browser.md §3.9`)
- [ ] **FIX (S)** — Open-in-default-browser hand-off (the complement exit). (`browser.md §5`)
- [ ] **REDESIGN (S)** — Custom dark error panel (`onReceivedError`/`onReceivedHttpError`). (`browser.md §3.5`)
- [ ] **FIX (S)** — JS allowlist management overlay + `BrowserSettings` accessors. (`browser.md §4`)
- [ ] **REDESIGN + DROP (M)** — Proxy surface: eng-gate the whole surface (prod shows nothing); DROP Lokinet/Yggdrasil cards (VpnService/TUN, vetoed) + provider picker + "Custom (advanced)"; fix I2P switch state machine; hoist `ProxyApplier` effect; remove repo-path leak. (`browser.md §6`)
- [ ] **FIX (S)** — Provider authority `${applicationId}.suitecaps` (wave-1 sweep; verify); find-glyph state cue. (`browser.md §8/§3.8`)
- [ ] **DECISION (S)** — Store-facing name → **Understory Safe View**. (`browser.md` top)

### 2.6 firewall (`com.understory.firewall`) — "Understory Net Audit" egress dashboard
Owner: `firewall.md` (B-base + C-graft final). Ordered per its §13.

- [ ] **FIX (M)** — Migration + settings spine: `FirewallMode` + `K_MODE`; rename keys (`K_VPN_ENABLED`→`K_ENGINE_ARMED`, `K_VPN_PREEMPTED`→`K_AUTO_STOPPED`, `K_BLOCKLIST`→`K_RESTRICT_LIST`); add `K_STANDALONE_EXPLAINED`/`K_MIGRATED_V2`; one-time V2 migration. (`firewall.md §8/§13.1`)
- [ ] **DROP (M)** — Delete dead/vetoed UI + code: `PortBlocksScreen`/`PortBlockDiscovery` (proc-net no-op), `OverlayRoutingScreen`, `portScannerThread`, DNS-redirect branch + `FAKE_DNS_IP`, `DnsCryptProxyService` + 3 DNSCRYPT `DnsProvider` entries + fetch script, overlay module deps. (`firewall.md §6` A8/A9/A11/A12)
- [ ] **FIX (S)** — Consume `net-engine` (wave-1); firewall no longer compiles the packet code inline. (`firewall.md §7`)
- [ ] **FIX (M)** — The hard guardrail `VpnSlotProbe.kt` (fail-closed CM `TRANSPORT_VPN` veto ANDed with `VpnService.prepare()`) + live slot-watcher `NetworkCallback`; add `com.tailscale.ipn` to `<queries>`. The load-bearing safety mechanism. (`firewall.md §4`)
- [ ] **REDESIGN (M)** — Standalone hub (walled-off engine): enable/arm/disable flows + full-screen explainer; `onRevoke` → neutral `autoStopped` (no "Re-enable" nag); mode-aware FGS + main subtitle. Engine absent in Companion. (`firewall.md §3`)
- [ ] **REDESIGN (M)** — Tunnel Posture read-model + screen (degrade-to-unknown ladder, never green on an inference gap) — the honest replacement for A2's inverted "preempted" banner. (`firewall.md §5.1`)
- [ ] **FIX (S)** — Remote-Admin Audit (crown jewel, kept): `MODE_DEFAULT`→`checkPermission` tri-state; "Block"→"Revoke"/"Add to watchlist" (+"Hard-block" in Standalone); first-run copy. (`firewall.md §5.4`)
- [ ] **REDESIGN (M)** — Restrict Worklist (repurposed app-list substrate → OS-enforced deep-links via `AppDetailSheet`, `resolveActivity`-guarded); wire the `APK_AUDITOR` hand-off-IN sink. (`firewall.md §5.5`)
- [ ] **FIX (S)** — DNS Hardening (flagship, kept): NextDNS config-ID field (or drop entry); numbered steps; Tailscale advisory line; DNSCrypt entries removed. (`firewall.md §5.3`)
- [ ] **REDESIGN (M)** — Traffic by App (`NetworkStatsManager`, `PACKAGE_USAGE_STATS` opt-in, all five UI states, never a dead chart) + Egress Canaries (explicit-tap-only, named endpoints). (`firewall.md §5.2/§5.6`)
- [ ] **FIX (S)** — Posture copy rewrite (delete "turn firewall off"); Limits card (the can't-do list); collapse the three OutlinedButtons. (`firewall.md §5.7/§5.8`)
- [ ] **FIX (S)** — Suite integration: dynamic `NET_POSTURE_AUDIT` beacon (may advertise a filter-class capability ONLY when Standalone-armed — never on the reference device); `${applicationId}.suitecaps`; store name → **Understory Net Audit**. (`firewall.md §10`)

### 2.7 antivirus (`com.understory.antivirus`) — "Understory APK Check" beside Play Protect
Owner: `antivirus.md`. Ordered.

- [ ] **FIX (S)** — Severity sort inverted-twice fix (CRITICAL-first everywhere); delete dead `Report.summary`. Guarded by new tests. (`antivirus.md §4`)
- [ ] **REDESIGN (M)** — Real abuser detection: `GET_SERVICES`/`GET_RECEIVERS` + `ServiceInfo.permission` (declared a11y/device-admin/notif-listener); `EnabledAbusers` (currently-enabled a11y/admin/notif enumeration); RawApkParser component-permission walk for SAF APKs; drop dead `uses-permission` booleans; add call-log+internet / SEND_SMS rules. (`antivirus.md §2`)
- [ ] **REDESIGN (M)** — Signed offline blocklist `.ubl` (Ed25519 compiled-in key, SAF import, seed set + provenance doc, lazy hashing, honest "what this catches" copy); `KnownBad` becomes a facade. (`antivirus.md §1`)
- [ ] **FIX (S)** — Play Protect card: 2-arg `getInt`→UNKNOWN on missing key (kill false-green) + "Open Play Protect" deep-link. (`antivirus.md §3`)
- [ ] **REDESIGN (M)** — "Real-time" → WorkManager periodic diff + on-open `PACKAGE_ADDED` (opt-in, default off); un-strip `POST_NOTIFICATIONS` (opt-in, degrade); rename beacon `REALTIME_SCANNER`→`APK_AUDITOR`/periodic (done wave-1; verify); fix roadmap. (`antivirus.md §5`)
- [ ] **REDESIGN (M)** — Tamper: `AvTamperPolicy` — patcher-present → prominent finding (the app's whole job), sig-mismatch/Frida/Xposed → hard-fail with explanation screen (never silent exit); root/hooking-tooling informational `TamperCard` uses the dead `<queries>`. (`antivirus.md §6/§7`)
- [ ] **FIX (M)** — Unit tests (RawApkParser, RiskRules, BlocklistCodec, ranking) — the suite's most-hostile parser is untested today. (`antivirus.md §8`)
- [ ] **FIX (S)** — Provider authority `${applicationId}.suitecaps` (wave-1; verify); GUI per-screen fixes (audit-row overlay-button, progress + button-disable, tri-state parse timeout→UNKNOWN, empty-clean state, ViewModel state survival). (`antivirus.md §9/§10`)
- [ ] **DECISION (S)** — Store-facing name → **Understory APK Check** (kills "real-time antivirus" overclaim). (`antivirus.md §0`)

---

## 3. WAVE 3 — GUI POLISH + HONESTY PASS

Depends on waves 1 (theme system + lint) and 2 (all new screens/copy exist).
Mechanical per-app adoption of the shared system, then a final honesty/status
sweep. One commit per repo; passgen first as reference, apps 2–7 mechanical
repeats (`shared-gui.md §7`).

Per app, the identical recipe (`shared-gui.md §7`, §8 disposition table):

- [ ] **REDESIGN (S/app)** — Wrap each Activity's `setContent` in `UnderstoryTheme(accent = UnderstoryAccent.<APP>)`; delete inline `darkColorScheme()`; root → `SuiteScaffold`.
- [ ] **FIX (M/app)** — Replace every `Color(0xFF…)` with a token role; every `fontSize=N.sp` with a Typography role (14sp body floor); the `UnderstoryHardcodedColor` lint (wave 1) makes this permanent.
- [ ] **FIX (S/app)** — `Row{Text;Switch}`→`SwitchRow`, `Slider`→`SliderRow`, ad-hoc cards→`SuiteCard`, list items→`SuiteListRow`; masked dots→`RevealToggle`+`cd_password_hidden`; 48dp targets; TalkBack pass (focus order, `contentDescription` on control icons, merged row semantics).
- [ ] **FIX (M/app)** — Move every user-facing string to `res/values/strings.xml`; drop `resourceConfigurations = ["en"]` (the one pure-gain DROP); raise sub-12sp body text (footer excepted + TalkBack-off).
- [ ] **FIX (S/app)** — Wrap any remaining crypto/IO/QR/SAF in `Bg.*` + `LoadingState`; silent tamper `finish()`→`FatalScreen`; empty lists→`EmptyState`.
- [ ] **FIX (S/app)** — Build with the tightened `lint.xml`; fix findings until green.

Final honesty/status sweep (spans all apps, verify after wave 2 copy lands):

- [ ] **FIX (S)** — No dead control anywhere on any build (CD-4a); no capability overclaim in copy/notifications/README/beacon (CD-4b); every silent dead-end surfaces a truthful message (CD-4c); no status green from an unreadable setting — degrade to "unknown" (CD-4d); cleanup claims (clipboard/session/shred) match real guarantees incl. process death (CD-4e). (`suite-coexistence.md §CD-4`)

---

## 4. CROSS-APP DEPENDENCY MAP (what must land where, in order)

Shared modules land in canonical `understory-common` FIRST, then vendor into
app repos. These are the hard edges an implementer must not reorder:

1. **Beacon taxonomy (1A) precedes every per-app beacon adoption.** Renames
   are breaking to consumers; one coordinated commit across canonical + seven
   vendored copies. browser maps to `emptySet()` until its wave-2 intake ships;
   backups' `BACKUP_ORCHESTRATOR` re-appears only at beacon v2 after a peer
   ships the deposit responder; firewall's filter-class beacon is dynamic
   (Standalone-armed only). (`suite-coexistence.md §1`)
2. **`UnderstoryTheme` module + lint (1B) precedes all wave-3 adoption.** No
   app adopts the theme until the module + `UnderstoryHardcodedColor` detector
   exist; then wave-2 new screens are token-native and wave-3 is a sweep.
3. **`VaultRecovery`/export/reset (1C) precedes passgen/aegis/vault-folder/
   backups wave-2 recovery tasks.** The four apps adopt a thin `VaultResetHooks`
   over one shared screen/flow; none re-clones. Recovery lands BEFORE the
   deferred four-engine format merge (`shared-vault-recovery.md §7`) so every
   app has a working export as a safety net first.
4. **`common-backup` must be vendored into vault-folder** (`settings.gradle.kts`)
   before its wave-2 export/recovery tasks — it currently vendors only
   common-security. (`shared-vault-recovery.md` preamble, `vault-folder.md §9`)
5. **`net-engine` (1D) precedes firewall's wave-2 packet-code drop.**
6. **`${applicationId}.suitecaps` (1A) precedes eng/prod side-by-side testing**
   of every other wave-2 change (dev-blocking today).
7. **The deposit-intent contract is a two-app handshake:** backups builds the
   `collect` responder/ingest (§2.4); the peer's push button + `BACKUP_EXPORTER`
   advertisement is that peer's work. Until ≥1 peer ships it, backups' Collect
   is disabled-with-reason and `BACKUP_ORCHESTRATOR` stays deferred. This is a
   post-alpha (v2-incremental) loop, not an alpha blocker.
8. **The `APK_AUDITOR`→firewall restrict hand-off** (signed advisory Intent) is
   likewise v2-incremental: antivirus emits, firewall's Restrict Worklist is
   built to accept, but the wire contract ships after alpha.

---

## 5. OPERATOR DECISIONS (surfaced across all designs — decide before/at ship)

Each of these is a decision the design cannot make on the operator's behalf.
Recommendations carried from the design docs; the choice is the operator's.

1. **aegis store-facing name.** Decision-class ship blocker (name collision with
   Aegis Authenticator, the complement target). **Recommended: "Understory OTP."**
   Package id `com.understory.aegis` and codename stay. One-line `app_name` + IME
   label + README/store copy. (`aegis.md §7`, `suite-coexistence.md §4.1`)
2. **Whole suite naming scheme adoption.** The "Understory <Noun>" family
   (Keys / OTP / File Vault / Backup / Safe View / Net Audit / APK Check) resolves
   the aegis collision, the bare-noun ambiguity, AND the two category overclaims
   ("antivirus"/"firewall") in one decision. Adopt as a set or per-app? **Recommended:
   adopt the set** (one coordinated identity pass with the beacon renames).
   (`suite-coexistence.md §4.3`, `SUITE.md §4.5`)
3. **firewall Standalone (opt-in packet engine) — include it at all?** The
   suite doc binds "keep the engine as a default-off, VPN-detecting Standalone
   mode" (`suite-coexistence.md §CD-2a`). The final firewall design (Approach B)
   conforms; Approaches A/C deleted the engine. **Decision: keep Standalone
   (default-off, guardrailed) per the binding suite doctrine** — but the operator
   may still elect to ship Companion-only for the first alpha and add Standalone
   later. On the operator's own Tailscale phone Standalone is permanently
   unreachable regardless. (`firewall.md §0/§12`)
4. **passgen Samsung dual-slot autofill.** Ships ONLY if on-device verification
   on SM-S948U confirms the "Additional service" settings path exists; otherwise
   **DROP** to keyboard-mode. Requires an operator on-device check + a
   `SAMSUNG_QUIRKS.md` autofill entry. (`passgen.md §7.3/§14`)
5. **vault-folder AAD format cutover.** Clean v2-only cutover (no v1 migration)
   is acceptable **iff the operator confirms no field installs exist**; else the
   version-gated reader ships. `versionName` is still "0.1-skeleton". (`vault-folder.md §7`)
6. **backups scheduling.** Ship the snapshot-only-key + WorkManager scheduling
   (recommended, viable) OR drop scheduling from docs/roadmap for the alpha and
   ship manual-only. (`backups.md §4`)
7. **backups `.usbs` full-content stream.** Ship with the UDCSv002 framing +
   restore decoder (recommended) OR drop the toggle until the decoder lands —
   do NOT ship a write-only stream with no restore. (`backups.md §2`, `SUITE.md §5`)
8. **antivirus blocklist seed provenance + signing key custody.** The Ed25519
   definitions-signing private key lives in the operator vault (off-repo); every
   seed hash needs a documented public source (`docs/BLOCKLIST_SEED.md`). Operator
   owns key custody + seed curation. (`antivirus.md §1.2`)
9. **firewall `PACKAGE_USAGE_STATS` scope.** Traffic-by-App declares the special
   permission (opt-in, user-granted). Confirm the operator wants this surface at
   all; it degrades gracefully if not granted. (`firewall.md §5.2/§11`)
10. **browser VIEW-filter opt-in default.** Ship SEND-only always; the broader
    `ACTION_VIEW http/https` alias is a first-run opt-in (disabled component by
    default). Confirm the opt-in framing. (`browser.md §2.2`)
11. **vault-folder image/* deposit MIME scope.** Optional trim so the app doesn't
    insert into every image "Open with…" chooser — a product-quiet decision.
    (`vault-folder.md §3.6`)

---

## 6. WHAT IS EXPLICITLY OUT OF THE V2 ALPHA (deferred, honestly)

- **Four-engine `DeviceAuthVault` merge** — HIGH migration risk; lands after the
  recovery/export safety net is stable, never in the same release. (`shared-vault-recovery.md §7`)
- **Cross-app `BackupProvider` orchestration IPC / full "orchestrator" identity** —
  the deposit-intent contract is the v2-incremental stand-in; `BACKUP_ORCHESTRATOR`
  re-appears at beacon v2 only when a peer responds. (`backups.md §3`, `suite-coexistence.md §1`)
- **`APK_AUDITOR`→firewall signed-advisory wire contract** — receivers built,
  wire ships post-alpha. (`firewall.md §5.5`)
- **firewall packet forwarder / DNS-redirect** — `net-engine` code is dormant
  (compiled, tested, uncalled). (`firewall.md §7`)
- **browser I2P / overlay networks** — eng-gated experimental only; no prod surface.
  Lokinet/Yggdrasil permanently vetoed (VpnService/TUN). (`browser.md §6`)
- **Encrypted-Aegis-vault import, 2FAS encrypted, Steam OTP** — honest reject with
  a clear message; decrypt is a tracked v1.5 item. (`aegis.md §3.2`)
- **Phase-2 sandbox / phase-3 defensive toolkit** — unchanged from
  `SUITE_DESIGN.md`; not v2-alpha scope.
- **Localization** — `resourceConfigurations=["en"]` dropped so strings are
  extractable, but no non-en locale ships.

---

## 7. NON-NEGOTIABLES FOR THE PUBLIC ALPHA (the intersection)

From `SUITE.md §5` + each per-app "non-negotiable for any public alpha" line,
the alpha does not ship until ALL of these are true (full definition of done in
`docs/RELEASE_BLOCKERS_V2.md`):

1. **No vault can silently become a permanent data-loss trap** — invalidated-key
   detect + real reset + user-held recovery export in all four vault apps (wave 1C + wave 2 adoption).
2. **No roach motel** — every app that ingests a secret class has a
   user-reachable export (passgen Bitwarden import+export, aegis export + real-Aegis
   import, vault-folder plain + encrypted export, backups envelope).
3. **No wrong security output** — aegis parameter-correct OTP; antivirus abuser
   detection that actually fires + no false-green Play Protect; firewall no green on
   an inference gap.
4. **The one confirmed hard crash is fixed** — vault-folder export (§2.3 wave 2).
5. **The honesty pass** — beacon de-overclaim, the aegis rename, no dead control,
   status honesty (CD-4).
6. **Doctrine conformance** — no app requires the VPN slot or any scarce slot;
   firewall's guardrail is fail-closed; no evict-nag against an incumbent.
