# Audit v2 — understory-backups ("backups" app)

Auditor: read-only pass over every source file in
`understory-backups\backups\` + the vendored shared modules
(`common-backup`, `common-security`; verified byte-identical to
canonical `understory-common` copies via recursive diff, 2026-07-03)
+ suite docs. Adversarial standard: WORKING only where the complete
code path was traced end to end.

Headline: **the "orchestrator" does not orchestrate.** What actually
exists is a solid, well-engineered single-file encrypted-envelope tool
with a biometric-gated Keystore vault, plus a device-snapshot service
whose two flagship sections (suite-app vaults, vault-folder files) are
stubs and whose full-content stream is write-only (no restore path
exists anywhere in the suite). No scheduling exists. "USB / Syncthing
folder" destinations are real only in the sense that SAF can point at
them; "self-hosted endpoint" cannot exist under the app's own
no-INTERNET posture.

---

## A. FEATURE LEDGER

### A1. Vault + crypto core

| # | Feature | Verdict | Evidence |
|---|---|---|---|
| 1 | First-time vault setup: self-generated 32-byte master KEK, wrapped under device-auth Keystore key, BiometricPrompt-gated | **WORKING** | `MainActivity.kt:324-402` (SetupScreen) → `Crypto.deviceAuthCipherForEncrypt()` (`common-security/Crypto.kt:118-123`, key spec `:144-171`) → `BackupsVault.create` (`BackupsVault.kt:60-74`), atomic header write `:119-140` |
| 2 | Unlock (biometric/PIN → decrypt wrapped KEK) | **WORKING** | `MainActivity.kt:404-454` → `BackupsVault.ivForUnlock:88` + `unlock:77-85`; strict header parse `:102-117` (length bounds, trailing-byte refusal) |
| 3 | Encrypt file → SAF envelope (`.usbe`) | **WORKING** (with main-thread caveat, gap D-5) | `MainActivity.kt:550-707` (EncryptScreen) → `BackupsFlow.encryptToEnvelope` (`BackupsFlow.kt:54-95`) → `AesGcmPassphraseCodec.encrypt` (`common-backup/AesGcmPassphraseCodec.kt:58-77`, Argon2id 64 MiB + AES-256-GCM, header as AAD) → `BackupEnvelope.write` (`BackupEnvelope.kt:63-83`). 16 MiB input cap `BackupsFlow.kt:38,225-243` |
| 4 | Encrypt file → local snapshot (internal storage, no SAF round-trip) | **WORKING** | `MainActivity.kt:679-707` → `LocalSnapshotStore.reserveNew` (`LocalSnapshotStore.kt:76-84`) → `BackupsFlow.encryptToLocalSnapshot:142-185` (partial-file cleanup on failure `:176`) |
| 5 | Decrypt envelope on this device (vault KEK as passphrase) | **WORKING** | `MainActivity.kt:710-795` → `BackupsFlow.decryptFromEnvelope:101-133`; GCM tag rejects wrong key; key + plaintext wiped in `finally:129-132` |
| 6 | Decrypt with recovery key (cross-device) | **WORKING** | `MainActivity.kt:797-893`; recovery string deliberately not `rememberSaveable` (`:803-804`); same Argon2id-over-envelope-salt reproduces the operational key (`BackupsVault.kt:27-35` design note, `AesGcmPassphraseCodec.kt:90-97`) |
| 7 | Reveal recovery key (biometric-gated, FLAG_SECURE, wipe-on-dispose) | **WORKING** | `MainActivity.kt:895-953`; `SecureOutlinedButton` entry `MainActivity.kt:535`; buffer wipe `MainActivity.kt:960-965`; FLAG_SECURE `MainActivity.kt:105-110` |
| 8 | "Copy to clipboard — auto-clears in 30s" | **MISLEADING** | Toast text `MainActivity.kt:941-945` claims a 30-second auto-clear. No code clears the clipboard — the app sets `EXTRA_IS_SENSITIVE` (`:936-939`) and stops. System auto-clear is OEM-dependent (~1 h on One UI/Pixel, not 30 s). The ultimate secret sits in the clipboard far longer than the UI promises |
| 9 | Local snapshots: list / restore-to-SAF / delete | **WORKING** | `LocalSnapshotsScreen.kt:49-168` → `LocalSnapshotStore.list:94-112` (header parsed per file, corrupt files skipped), `BackupsFlow.decryptLocalSnapshot:193-223`, `delete:115` (layout defect: gap D-11) |
| 10 | Snapshot retention (`rotate(keepLast)`) | **UNFINISHED (dead code)** | `LocalSnapshotStore.kt:121-126` implemented, zero call sites in the app (grep: only definition). No retention UI exists; snapshots accumulate until manual delete |
| 11 | Lifecycle hardening: lock-on-stop/leave/destroy, transient-flight preservation across SAF/biometric round-trips, resume tamper re-check with flight suppression | **WORKING** | `MainActivity.kt:137-214`; `BackupsVaultManager.kt:31-50`; common `TransientFlight.kt:32-51`; TestingMode flags verified `false` (`TestingMode.kt:34,56`) matching `RELEASE_BLOCKERS.md:72-91` |
| 12 | Tamper + SuiteAttestation gates, debugger refusal | **WORKING** | `MainActivity.kt:96-103` (onCreate hard gate), `:186-207` (onResume re-check honoring both flight counters) |

### A2. Device-wide snapshot

| # | Feature | Verdict | Evidence |
|---|---|---|---|
| 13 | FGS snapshot pipeline (collect → encrypt → write internal or SAF tree) | **WORKING** (as far as its sections go) | `DeviceSnapshotService.kt:77-323`; API-34 `FOREGROUND_SERVICE_TYPE_DATA_SYNC` handled `:78-86`; manifest `AndroidManifest.xml:218-221` (`exported="false"`), `:41-42` FGS permissions |
| 14 | Progress/completion notifications | **BROKEN / MISLEADING** | Manifest **declares** `POST_NOTIFICATIONS` at `AndroidManifest.xml:43` then **strips it** at `:125` (`tools:node="remove"`, inside the copy-pasted "Notifications / overlays" strip block). The remove instruction wins in the merged manifest, and the app never runtime-requests the permission anywhere (no `POST_NOTIFICATIONS` request in any launcher). On minSdk 33+ every `nm.notify` (`DeviceSnapshotService.kt:325-331`) is silently dropped; the FGS runs but shows nothing in the shade. UI explicitly tells the user "Watch the foreground notification for progress" (`DeviceSnapshotConfigScreen.kt:267-268`) — a promise the manifest makes impossible |
| 15 | Section: Android settings | **WORKING** (capture-only) | `AndroidSettingsCollector.kt:102-131`; honest skip-on-null for protected keys `:52-100`. Note: capture only — no re-apply path exists anywhere (WRITE_SETTINGS never held), so as a "backup" it restores nothing; it is a readable inventory |
| 16 | Section: user-dirs manifest (paths + sizes + first-64KiB SHA-256) | **WORKING but scope-MISLEADING on 13+** | `UserDirsManifestCollector.kt:77-163` (5 000-file cap `:75`, permission_denied honesty `:103-106`). BUT: minSdk=33 (`build.gradle.kts:13`) and only `READ_MEDIA_*` held (`AndroidManifest.xml:29-33`) — scoped storage exposes **media files only** through the raw `File` walk. Non-media files in Documents/ and Downloads/ created by other apps are invisible; the UI claims "Will snapshot Pictures / DCIM / Downloads / Documents / Music / Movies" (`DeviceSnapshotConfigScreen.kt:151-158`) without that caveat. The pre-13 `READ_EXTERNAL_STORAGE` fallback (`AndroidManifest.xml:32-33`, `DeviceSnapshotConfigScreen.kt:320-330`) is dead code at minSdk 33 |
| 17 | Section: user-dirs FULL CONTENT → streaming-encrypted `.usbs` companion | **UNFINISHED (write-only) + data-integrity bug** | Write path complete: `DeviceSnapshotService.kt:223-306` → `UserDirsContentStream.open` (`UserDirsContentStream.kt:95-153`) → `StreamingAesGcmCodec.encrypt` (`common-backup/StreamingAesGcmCodec.kt:133-182`, sound chunked-GCM design with counter+final-flag AAD). **No decrypt/unpack path exists anywhere**: `StreamingAesGcmCodec.decrypt` is referenced only by unit tests (`StreamingAesGcmCodecTest.kt`); the `UDCSv001` framing has exactly one occurrence in the entire suite — the writer (`UserDirsContentStream.kt:60`). The app's Decrypt screens parse `.usbe` envelopes only (`BackupsFlow.kt:110-114`). A backup no tool can restore is not a backup. **Bug**: if a file shrinks/disappears between walk and lazy open, `LazyFileInputStream` returns fewer bytes than the frame header claimed (`:186-224`) — `SequenceInputStream` then feeds the next entry's header bytes as file content and every subsequent frame boundary slides; if a file grows, extra bytes corrupt the next header. The comment "the decoder must tolerate this" (`:187-191`) is wrong — length-prefixed framing cannot tolerate it, and no decoder exists to try |
| 18 | Section: suite-app vaults (passgen/aegis/…) | **UNFINISHED (stub; honestly labeled in-app)** | `DeviceSnapshotService.kt:171-184` writes `{"status":"pending"}` JSON; toggle labeled "phase 2" (`DeviceSnapshotConfigScreen.kt:197-208`). But toggle **defaults ON** (`DeviceSnapshotConfig.kt:83`) so every default snapshot ships stub noise |
| 19 | Section: vault-folder secure files | **UNFINISHED (stub; honestly labeled)** | `DeviceSnapshotService.kt:186-195`; `DeviceSnapshotConfigScreen.kt:210-219`; default ON `DeviceSnapshotConfig.kt:85` |
| 20 | SAF tree destination + persisted grant | **WORKING** | `DeviceSnapshotConfigScreen.kt:80-94` (`OpenDocumentTree` + `takePersistableUriPermission`), `DeviceSnapshotService.openOutput:310-323` (DocumentFile createFile) |
| 21 | Passphrase hand-off to FGS via Intent extra | **WORKING** (documented trade-off) | `DeviceSnapshotService.kt:64-70` (threat note), `:104-123` (wipe in `finally`), `DeviceSnapshotConfigScreen.kt:264-266` |

### A3. The orchestration claim (SUITE_ROADMAP row 7 / README)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 22 | "backups orchestrates every suite app's backup-export interface" (`SUITE_ROADMAP.md:42`; `SUITE_DESIGN.md:409-461`) | **UNVIABLE-AS-SHIPPED / not built** | The cross-app mechanism the design requires — a signature-locked `BackupProvider` ContentProvider per peer exposing `BackupAdapter.export()` — exists in **zero** apps. Grep across all repos: the only providers are `SuiteCapsProvider` version beacons. `BackupAdapter` (`common-backup/BackupAdapter.kt:22-51`) is an in-process interface; `PassgenBackupAdapter` / `AegisBackupAdapter` live inside those apps' processes with no IPC surface. `BackupsFlow.kt:25-32` says it plainly: "for now the orchestrator is a generic envelope tool." The stubs in #18/#19 are the entire cross-app story |
| 23 | `BACKUP_ORCHESTRATOR` capability advertised to peers at version 1 | **MISLEADING (suite-mesh level)** | `SuiteCapsProvider.kt:10-12` attests version 1; every peer's compiled-in map translates `(com.understory.backups, 1)` → `BACKUP_ORCHESTRATOR` (`common-security/SuiteCapabilityRegistry.kt:66-68`, enum doc `SuiteCapability.kt:54-59` "calls each peer's BackupAdapter"). Peers therefore display/act on a capability that does not exist. The version-map mechanism itself is the right tool — the v1 row should simply not include BACKUP_ORCHESTRATOR until Wave B-2 lands |
| 24 | Scheduling ("Schedules and runs encrypted exports" — README.md:3; "Schedule on a timer" — SUITE_DESIGN.md:419,444-445) | **MISSING + UNVIABLE under current key policy** | No WorkManager, no AlarmManager, no `RECEIVE_BOOT_COMPLETED`/`WAKE_LOCK` (manifest has none; design doc `SUITE_DESIGN.md:434-436` planned them). Deeper: the master KEK is wrapped under a Keystore key with `setUserAuthenticationParameters(0, …)` — auth required for **every** operation (`Crypto.kt:158-163`). A background scheduled run can never unlock the vault without the user present. Scheduling requires a key-policy redesign (see D-7), not just a timer |
| 25 | Destinations: USB | **WORKING via SAF** (not a distinct feature) | Any `OpenDocumentTree`/`CreateDocument` target incl. USB-OTG storage works (`DeviceSnapshotConfigScreen.kt:80-94`, `MainActivity.kt:567-573`). No USB-specific code exists — honest framing is "any SAF location" |
| 26 | Destinations: Syncthing folder | **WORKING via SAF, undocumented in-app** | Same SAF mechanism; pointing the tree picker at a Syncthing-synced folder achieves off-device sync with zero code. Nothing in the UI or app docs mentions the pattern — pure missed complement (see E) |
| 27 | Destinations: self-hosted endpoint | **UNVIABLE-AS-DESIGNED** | INTERNET is stripped with intent (`AndroidManifest.xml:53`, comment `:46-51`; `network_security_config.xml:2-8`). An in-app network destination contradicts the app's own defense-in-depth posture. The only honest form is #26 (synced folder handled by an external tool). README.md:3's "self-hosted" should be reworded |

### A4. Suite plumbing

| # | Feature | Verdict | Evidence |
|---|---|---|---|
| 28 | SuiteCapsProvider beacon (read-only, signature-gated) | **WORKING** | `SuiteCapsProvider.kt`; base `BaseCapabilityProvider.kt:52-127` (writes throw, belt-and-braces permission re-check `:84-86`); manifest `AndroidManifest.xml:228-234` |
| 29 | Backup exclusion from Google One / Smart Switch | **WORKING (deliberate)** | `allowBackup="false"` + full exclude rules for cloud-backup **and** device-transfer (`AndroidManifest.xml:185-187`, `data_extraction_rules.xml:1-17`) — the vault never rides Google/Samsung transfer, which is coherent with the recovery-key story |
| 30 | Diagnostics screen + eng dump | **WORKING** | `MainActivity.kt:298-301` → common `DiagnosticsScreen`; `DiagnosticsDump.activateIfEng` `MainActivity.kt:78` |

---

## B. EXCLUSIVE-SLOT & COEXISTENCE

**Scarce slots touched: none.** Verified against the manifest: no
VpnService, no autofill service, no IME, no accessibility service, no
notification listener, no device admin, no default-app role, no
usage-stats. Explicit strips at `AndroidManifest.xml:126-135`
(SYSTEM_ALERT_WINDOW, BIND_ACCESSIBILITY_SERVICE, BIND_DEVICE_ADMIN,
BIND_NOTIFICATION_LISTENER_SERVICE removed). This app is doctrine-clean
under rule 1: Tailscale's VPN slot, Bitwarden's autofill slot,
Aegis-the-real-one's TOTP role are all untouched.

Resources it does consume (shared, not exclusive):
- `READ_MEDIA_IMAGES/VIDEO/AUDIO` runtime permissions (`AndroidManifest.xml:29-31`) — requested only from the device-snapshot screen (`DeviceSnapshotConfigScreen.kt:69-75,166,192`); users who never open that screen are never prompted (manifest comment `:108-119`). Good.
- One dataSync foreground service + one notification channel (broken; A-14).
- BiometricPrompt/Keystore — shared infrastructure, no conflict.

**Incumbents a real user already has, and the relationship:**

| Incumbent | Overlap | Conflict? | Complement opportunity |
|---|---|---|---|
| **Google One backup** (system) | Backs up app data + device settings to Google | None mechanically; philosophically backups exists because the user distrusts it | backups deliberately opts out (`data_extraction_rules.xml`) — honest. Opportunity: state on the main screen "this vault is excluded from Google backup; your recovery key is the only restore path" — today that fact lives only in XML |
| **Samsung Smart Switch** | Device-transfer of settings + files | None; device-transfer excluded too (`data_extraction_rules.xml:10-16`) | Opportunity: a "moving phones?" help card — reveal recovery key → transfer → decrypt-with-recovery-key on the new device is exactly the flow Smart Switch cannot do for this vault. The flow exists (A-6,7); the guidance doesn't |
| **Syncthing** (user-run) | Off-device replication | None — backups has no sync engine and no network | The single strongest complement in the suite: snapshot destination = Syncthing folder gives encrypted, versioned, self-hosted off-device backup with zero INTERNET permission. Needs one UI hint + doc paragraph (D-16); today the word "Syncthing" appears nowhere in the app |
| **Samsung Secure Folder** | Encrypted holding area | None | Orthogonal; nothing to do |
| **Bitwarden/1Password, Aegis Authenticator** | Their own export files | None | Their exports (`.json`/encrypted export files) are ordinary files ≤16 MiB — backups' envelope-encrypt of incumbent exports is a real, already-working use ("encrypt my Bitwarden export before it touches Drive"). Never surfaced as a use case |
| **Files by Google / Samsung My Files** | `.usbe` files are opaque octet-streams | None | Missing hand-off: no intent-filter to open `.usbe` files (VIEW intent) — tapping a snapshot in a file manager does nothing. Registering as a viewer for the extension would close the loop cheaply |

**Doctrine verdict:** fully coexistent. The complement story is
under-told, not over-reached — the inverse of the usual failure mode.

---

## C. GUI AUDIT (screen by screen)

Global issues first, since they apply everywhere:

- **Material3 in name only.** `MaterialTheme(darkColorScheme())` is applied once (`MainActivity.kt:124`) and then bypassed: every screen hardcodes hex colors (`Color(0xFFE0E0E0)`, `0xFF9E9E9E`, `0xFF1C1C1C`, `0xFFEF5350`… — e.g. `MainActivity.kt:505-510`, `LocalSnapshotsScreen.kt:88-94`, `DeviceSnapshotConfigScreen.kt:103-110`) and raw `sp` sizes instead of `MaterialTheme.colorScheme`/`typography`. No Scaffold, no TopAppBar, no back affordance beyond text buttons. Consistent with the other suite apps' current style, but "polished Material3" per the shippable bar it is not.
- **Dark theme:** forced-dark only. `themes.xml:3` parents `android:Theme.Material.NoActionBar` (not DayNight); every surface hardcodes near-black. Acceptable as a deliberate posture for a FLAG_SECURE vault app, but it is a choice, not adaptive theming — and it's undocumented.
- **Strings:** `strings.xml` contains exactly one string (`app_name`, `strings.xml:3`). Every user-facing sentence is hardcoded in Kotlin (hundreds of lines across all five screens). `resourceConfigurations = ["en"]` (`build.gradle.kts:17`). No localization is possible without a refactor.
- **A11y:** no `contentDescription` anywhere (tolerable — the UI is text-only, no icon buttons); no semantic headings; 9-11sp footer/caption text (`SuiteStatusFooter.kt:127-155`, various 11sp captions) is below comfortable-legibility for low-vision users and ignores the user's font-scale only partially (sp does scale, but 9sp base is tiny). Touch targets are full-width buttons — fine. Switches carry no stateDescription.
- **Loading states:** none are real. `working` flags exist but the heavy work runs synchronously on the main thread (see D-5), so "Encrypting…"/"Decrypting…" labels can never actually render before the work finishes; on a slow device the app just freezes (Argon2id at 64 MiB is ~1 s+ per operation).

Per screen:

| Screen | State | Notes |
|---|---|---|
| **Setup** (`MainActivity.kt:324-402`) | Good structure | Device-unsupported banner + Close (`:340-347`); two-step explanation; error text state. Gap: the recovery-key warning is a wall of 11sp text (`:363-372`), and the user is never *forced* (or even later prompted) to record the recovery key — see D-4 |
| **Unlock** (`:404-454`) | OK | Error + working states present. Gap: `deviceUnsupportedReason` is checked only in Setup, not here; a `KeyPermanentlyInvalidatedException` after biometric re-enrollment surfaces as a bare "Vault decryption failed." with no explanation or re-bind path (`:437-439`) |
| **Main** (`:490-547`) | OK | 8 stacked buttons, correct SecureButton usage per SAMSUNG_QUIRKS (`:513-517` comment; Reveal is the only Secure* entry, matching `SAMSUNG_QUIRKS.md:38-45`); `SuiteStatusFooter` present (`:545`) — the only screen with it, which matches the suite convention (main screen only). Diagnostics reachable (`:542`) |
| **Encrypt** (`:550-707`) | OK | Picked-URI labels update button text (`:607,629`); status color-codes success/failure (`:631-637`); URIs `rememberSaveable` for Samsung recreation (`:553-555`). Gaps: 16 MiB cap invisible until failure; no file-size preflight; label field explains cleartext-header exposure only in its label text |
| **Decrypt / DecryptRecovery** (`:710-893`) | OK | Recovery field uses `PasswordVisualTransformation` (`:846`), not saveable (`:803`), zeroed on Back (`:888`). Same synchronous-work gap |
| **Reveal** (`:895-953`) | OK | Strong warning banner; wipe-on-dispose; clipboard claim false (A-8) |
| **Local snapshots** (`LocalSnapshotsScreen.kt`) | Good empty state, layout bug | Real empty state with actionable copy (`:97-110`); count line; per-row metadata. **Bug:** `LazyColumn` inside a non-scrolling `Column` without `weight(1f)` (`:84-118`) — with enough snapshots the list consumes the whole viewport and pushes the status line and the only Back button off-screen (BackHandler still works, but the visible affordance is gone). Restore/Delete buttons: Delete is one tap, no confirmation dialog, on an outlined (non-Secure) button — destructive without confirm (`:139-147`) |
| **Device snapshot** (`DeviceSnapshotConfigScreen.kt`) | Honest but rough | The "working today vs pending" banner (`:113-129`) is genuinely honest UI — good. Permission-aware toggle labels (`:148-159`) good. Gaps: destination shows the raw tree URI string (`:233-237`) — unreadable `content://…%3A…` soup; stub toggles default ON (D-13); "watch the foreground notification" instruction is false today (A-14); Switch rows use active `onCheckedChange` inside non-clickable rows — compliant with `SAMSUNG_QUIRKS.md:106-117` |
| **Diagnostics** (common `DiagnosticsScreen`) | Present | Shared widget; consistent with suite |
| **Crash fallback** (`MainActivity.kt:84-92`) | Present | Raw `t.toString()` on black — better than a silent die, not shippable polish |

---

## D. SHIP-GAP LIST (ranked)

| # | Gap | Size | Tag | Detail |
|---|---|---|---|---|
| D-1 | **POST_NOTIFICATIONS declared then stripped in the same manifest; snapshot progress invisible** | S | FIX | Delete `AndroidManifest.xml:125`, add a runtime permission request on the device-snapshot screen (minSdk 33 ⇒ always runtime), degrade honestly ("progress unavailable — notifications denied") if refused. Evidence: `AndroidManifest.xml:43` vs `:125`; `DeviceSnapshotService.kt:325-331`; `DeviceSnapshotConfigScreen.kt:267` |
| D-2 | **`.usbs` content backup is write-only — no restore tool exists in the suite** | L | FIX or DROP-TO-V2 | Either build the restore path (a "Restore content stream" screen: `StreamingAesGcmCodec.decrypt` → UDCSv001 unpacker → SAF tree writes; the codec's decrypt side already exists and is unit-tested) or remove the `includeUserDirContent` sub-toggle (`DeviceSnapshotConfigScreen.kt:170-189`) until it lands. Shipping an unrestorable backup toggle violates viability honesty |
| D-3 | **UDCS framing corrupts on file shrink/grow between walk and read** | M | REDESIGN (small) | Length-prefixed framing + live filesystem = silent whole-archive corruption past the first changed file (`UserDirsContentStream.kt:129-143,186-224`). Mechanism sketch: per-entry trailer with actual-bytes-written + resync magic, or stat-at-open and skip-with-tombstone when size differs, or copy each file's claimed length exactly (pad/truncate) and record divergence in a sidecar section. Must land before/with D-2 |
| D-4 | **Recovery-key escrow is optional while `setInvalidatedByBiometricEnrollment(true)` can brick every envelope** | M | FIX | Adding a fingerprint destroys the Keystore wrap key (`Crypto.kt:164-166`); all envelopes remain decryptable **only** via the recovery key — which setup never forces the user to record (`MainActivity.kt:349-377`). For a backup app this is a data-loss trap. Fix: mandatory reveal-and-confirm step at setup + detect `KeyPermanentlyInvalidatedException` at unlock with a "re-bind with recovery key" flow |
| D-5 | **Argon2id (64 MiB×3) + AES runs on the main thread in every click handler** | M | FIX | `MainActivity.kt:641-660,770-783,867-881`; `LocalSnapshotsScreen.kt:68-82`. Violates the suite's own rule (`SAMSUNG_QUIRKS.md:119-129`: >100 ms ⇒ Dispatchers.IO); ANR-class freezes on One UI; "Encrypting…" labels can never render. Move to `rememberCoroutineScope` + `Dispatchers.IO` like antivirus already did |
| D-6 | **BACKUP_ORCHESTRATOR capability advertised suite-wide while orchestration is a stub** | S | FIX (honesty now), L (real thing) | Change peers' KNOWN_PEERS so backups v1 maps to an empty set (or a new honest `ENVELOPE_TOOL` capability); re-add BACKUP_ORCHESTRATOR at v2 when the signature-locked BackupContentProvider (Wave B-2) actually ships in ≥1 peer. Evidence: A-22/23 |
| D-7 | **Scheduling absent, and unviable under the per-operation-auth key policy** | L | REDESIGN | README/roadmap promise scheduling; no timer exists, and `Crypto.kt:158-163` (auth timeout 0) makes background unlock impossible by design. Mechanism sketch: introduce a second, non-auth-bound Keystore key that wraps a *snapshot-only* key (encrypt-only material; restore still demands biometric), user opts in explicitly ("scheduled snapshots can run without unlock; restoring always requires your biometric"); then WorkManager periodic + persisted SAF tree + `RECEIVE_BOOT_COMPLETED`. Alternatively DROP the scheduling claim from README/roadmap for v1 (S) |
| D-8 | **Documents/Downloads coverage silently partial on API 33+** | M | FIX (honesty) or REDESIGN | Raw `File` walk with only READ_MEDIA_* sees media + own files; other apps' non-media files in Documents/Downloads are invisible (A-16). Honest fix: per-section coverage note in UI + manifest JSON ("media files only on this Android version"). Real fix: let the user SAF-pick source dirs (OpenDocumentTree read grants) — the rootless-correct mechanism for arbitrary-file backup |
| D-9 | **All UI strings hardcoded; strings.xml has one entry** | M | FIX | Extract to resources before any polish pass multiplies the debt (`strings.xml:3`; every screen file) |
| D-10 | **Material3/theming: hardcoded hex palette, no Scaffold/TopAppBar, forced-dark non-DayNight theme** | M | FIX | Map the existing palette onto `colorScheme` tokens once in common-security so all suite apps inherit; add Scaffold+TopAppBar per screen. Evidence: section C globals |
| D-11 | **LocalSnapshots list can push Back button + status off-screen** | S | FIX | Give the `LazyColumn` `Modifier.weight(1f)` (`LocalSnapshotsScreen.kt:116-118`) |
| D-12 | **Clipboard "auto-clears in 30s" is false** | S | FIX | Either implement it (`Handler.postDelayed` → `clearPrimaryClip` guarded by clip-ownership check) or reword the toast (`MainActivity.kt:941-945`) |
| D-13 | **Stub sections default ON — every default snapshot embeds "pending" junk** | S | FIX | Default `includeSuiteAppVaults`/`includeVaultFolderSecureFiles` to `false` until Wave B-2 (`DeviceSnapshotConfig.kt:83-85`) |
| D-14 | **Delete snapshot has no confirmation** | S | FIX | One-tap irreversible delete (`LocalSnapshotsScreen.kt:139-147`); add a confirm dialog (and per SAMSUNG_QUIRKS this is exactly where a Secure* wrapper belongs) |
| D-15 | **Dead code / dead manifest for pre-33** | S | FIX | minSdk 33: remove `READ_EXTERNAL_STORAGE maxSdk=32` (`AndroidManifest.xml:32-33`) and the pre-Tiramisu branch (`DeviceSnapshotConfigScreen.kt:327-329`); wire or delete `LocalSnapshotStore.rotate` (`LocalSnapshotStore.kt:121-126`) |
| D-16 | **Complement story invisible in-app** | S | FIX | Three one-card additions: Syncthing-folder destination hint on the device-snapshot screen; "excluded from Google One/Smart Switch — recovery key is the only restore path" note on Main; `.usbe` VIEW intent-filter so file managers can hand envelopes to the Decrypt screen |
| D-17 | **Raw SAF tree URI shown as destination** | S | FIX | Render `DocumentFile.getName()`/pretty path instead (`DeviceSnapshotConfigScreen.kt:233-237`) |
| D-18 | **16 MiB cap + notification icon polish** | S | FIX | Surface the cap on the Encrypt screen before failure (`BackupsFlow.kt:38`); replace `android.R.drawable.ic_lock_lock` (`DeviceSnapshotService.kt:345`) with an app-owned vector |

Count: **S=10, M=5, L=3** (D-6 counted once as S for the honesty fix;
D-7 counted as L; the D-2 build-out and D-6 real orchestration are the
other Ls).

---

## E. COMPLEMENT POSITIONING

backups should be **the encrypted-envelope layer that sits underneath
whatever replication the user already trusts** — the thing that makes
Syncthing, a USB stick, or even Google Drive safe to hold vault-grade
data, rather than a rival to Google One or Smart Switch. Google One and
Smart Switch move *app data the platform lets them see*; this app's
vault is deliberately invisible to both (allowBackup=false, extraction
rules exclude-all), and its unique offer is the piece neither incumbent
has: a hardware-gated master key with a human-transferable recovery
string, so a snapshot dropped into a Syncthing folder or onto USB-OTG
is restorable on any device by someone holding the recovery key and
nobody else. v1 should say exactly that — "encrypt anything into a
sealed envelope; park it wherever you already sync; restore anywhere
with your recovery key" — and stop implying orchestration, scheduling,
and self-hosted endpoints it doesn't have. The orchestrator identity
(pulling passgen/aegis/vault-folder exports through signature-locked
providers) is a legitimate v2 once Wave B-2 exists on both sides; until
then honesty demands the capability beacon, the README, and the roadmap
row all shrink to what the code does — which, at its core, is already
solid and already useful next to every incumbent on the phone.
