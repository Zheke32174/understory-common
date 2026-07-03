# vault-folder — audit-v2 (adversarial, read-only)

Audited 2026-07-03 against the NEW SUITE DOCTRINE (complement-don't-replace, viability honesty, shippable = polished GUI + zero dead UI + honest claims).
Scope: `C:\repos\understory\understory-vault-folder\vault-folder\` + vendored `common-security\` + suite docs in understory-common.
Complement target: **Samsung Secure Folder** (system work-profile isolation — which this app correctly does not attempt to replicate).
All file:line references are to `understory-vault-folder` unless prefixed `understory-common`.

Verified context up front:
- `TestingMode.ALLOW_SCREENSHOTS = false`, `KEEP_ALIVE_ON_LEAVE = false` in the **vendored** copy (`common-security\...\TestingMode.kt:34,56`) AND the canonical copy (understory-common same path :34,56). FLAG_SECURE and lock-on-leave are live. Matches RELEASE_BLOCKERS.md "Resolved" (:72-91).
- `excludeFromRecents="true"` present (`vault-folder\src\main\AndroidManifest.xml:161`) — but the comment directly above it (:153-156, "Activity-isolation set relaxed for the testing phase") and MainActivity comments (:178-181, :204-206 "RELEASE-BLOCKER to flip ... = false before publish") are **stale** — the flags are already false. Doc drift only.
- App module does NOT vendor common-backup or any overlay-* (settings.gradle.kts:24-25 includes only `:common-security`, `:vault-folder`). Instructions to read common-backup/overlay-* were N/A for this app — confirmed unused by grep (no `backup`/overlay imports in vault-folder sources).
- Unused common-security baggage compiled in: A11yProbe, Clipboard, HotpSecret, OtpAuthUri, Totp, DeviceProfile, argon2/generateMasterPassword paths (grep: zero references from vault-folder module). APK bloat only; no behavior.

---

## A. FEATURE LEDGER

### A1. First-time vault setup (Keystore-bound master key) — **WORKING**
Trace: `SetupScreen` (MainActivity.kt:362-437) → `Crypto.deviceAuthCipherForEncrypt()` (common-security Crypto.kt:118-123; Keystore AES-256-GCM key, `setUserAuthenticationRequired(true)`, timeout 0, BIOMETRIC_STRONG|DEVICE_CREDENTIAL, StrongBox attempted, Crypto.kt:144-170) → `promptAuth` with CryptoObject (MainActivity.kt:491-523) → `VaultFolder.create` (VaultFolder.kt:82-102): 32-byte KEK generated, wrapped by the auth-gated cipher, header written atomically, empty metadata persisted, original KEK wiped. Complete end-to-end path. Honest warning UI about lost-device = lost-vault (MainActivity.kt:396-407).

### A2. Device-capability gate — **WORKING**
`deviceUnsupportedReason` (MainActivity.kt:343-360): KeyguardManager.isDeviceSecure + BiometricManager.canAuthenticate; Setup shows the reason and a Close button, no dead path (MainActivity.kt:378-386).

### A3. Unlock (biometric / device credential) — **WORKING**
Trace: `UnlockScreen` (MainActivity.kt:439-489) → `VaultFolder.ivForUnlock` (VaultFolder.kt:129-130) → `Crypto.deviceAuthCipherForDecrypt` (Crypto.kt:129-134) → BiometricPrompt → `VaultFolder.unlock` (VaultFolder.kt:105-126) decrypts wrapped KEK + metadata. Lifecycle-guard (`isAtLeast(STARTED)` else `v.lock()`, MainActivity.kt:469-471). Errors/cancel surfaced inline. Complete.

### A4. Add file via SAF (encrypt at rest) — **WORKING** (with ship-gaps)
Trace: `AddScreen` "Pick a file" (MainActivity.kt:812-826) → `OpenDocument` launcher (:775-783) → `runAdd` (:734-764) → `VaultFolderStore.addFile` (VaultFolderStore.kt:98-141): bounded read (20 MiB cap, :199-216), per-blob AES-256-GCM under vault KEK with fresh IV (Crypto.kt:88-99), atomic write (:222-243), metadata rewrite, plaintext wiped. Transient-flight bracketing so the SAF round-trip doesn't lock the vault (MainActivity.kt:815,780; VaultFolderManager.kt:46-58).
Gaps: entire read+encrypt runs **synchronously on the main thread** inside the picker callback — violates the suite's own rule (understory-common SAMSUNG_QUIRKS.md:119-129 "any work ... crypto ... >100ms goes through Dispatchers.IO"); the "Encrypting…" label (MainActivity.kt:825) can never render because no recomposition happens during the synchronous block. See ship-gap #2.

### A5. Per-file 20 MiB cap — **WORKING**
Enforced during streaming read (VaultFolderStore.kt:205-211), honestly stated in Setup and Add copy (MainActivity.kt:400, :803-805). Not streaming crypto though — whole file is buffered in memory (ByteArrayOutputStream, :202) then encrypted in one `doFinal`. Acceptable at 20 MiB; the cap is what makes the non-streaming design viable. Any future cap raise must revisit this.

### A6. Shred-source-after-import toggle — **WORKING** (honest UI, correct three-state result)
Toggle default-off (MainActivity.kt:724, 833-866), confirm dialog before every shred (:873-900), `ContentResolver.delete` attempt with three explicit terminal states incl. "shred failed, your encrypted copy is safe" (VaultFolderStore.kt:122-140, AddResult :329-338); shred-failed stays on screen so the message is read (MainActivity.kt:755-757). UI text honestly explains WRITE-grant dependency (:849-853). This is the doctrine's honesty model done right.

### A7. Cross-app deposit (ACTION_VIEW "Open with…") — **WORKING path, MISLEADING claims**
Trace: manifest VIEW filter (AndroidManifest.xml:187-197, octet-stream/json/text/csv/image/pdf) → `depositUri` captured in onCreate (MainActivity.kt:138-139) → post-unlock routed to Stage.Add (:277, :284) → `LaunchedEffect(incomingUri)` **auto-runs** `beginAdd` (:790-795) → with shred off (default) `beginAdd` calls `runAdd` directly (:767-773) — **no per-file confirmation**.
MISLEADING ×2:
- Manifest comment claims "shows a confirmation before encrypting the incoming file into the vault. No code path bypasses either gate" (AndroidManifest.xml:174-180) — false; only the shred variant confirms.
- understory-common SUITE_THREAT_SURFACES.md:138-140 claims "Deposit still requires biometric unlock + **explicit user confirmation**" — the unlock gate is real, the confirmation is not.
Also UNFINISHED edge: `launchMode="singleTask"` (AndroidManifest.xml:160) with **no `onNewIntent` override** in MainActivity — a deposit arriving while the activity instance is alive (e.g., during a transient-flight window) is silently dropped; only onCreate reads `intent.data`. Low frequency because lock-on-leave finishes the task, but it's a silent-no-op path.
Hardening nit: `category.BROWSABLE` (:190) is unnecessary for a file-deposit target and invites `intent://` invocation from web pages.

### A8. Export file (decrypt to SAF destination) — **UNFINISHED (broken as coded — crash traced)**
Intended trace: Export tap (MainActivity.kt:615-625) → `pendingExport = entry` → `CreateDocument` launcher (:547-567) → `store.exportFile` (VaultFolderStore.kt:147-157).
**Defect:** `pendingExport` is `rememberSaveable { mutableStateOf<VaultFolderEntry?>(null) }` (MainActivity.kt:546) but `VaultFolderEntry` (VaultFolderStore.kt:263-287) is neither Parcelable nor Serializable. `mutableStateOf` on Android is a `ParcelableSnapshotMutableState`; when the activity's saved state is parceled — which happens on every onStop, i.e. **exactly when the SAF CreateDocument picker opens** — `Parcel.writeValue(VaultFolderEntry)` throws `RuntimeException: Parcel: unable to marshal value`. Tapping Export therefore crashes the process during the picker round-trip on any state-save. This is the same defect class the file itself documents for the Stage enum ("the shipped APK contained the 'cannot be saved' Compose error string", MainActivity.kt:251-254) — fixed for Stage by string-encoding (:255-258), then re-introduced for `pendingExport`. The comment (:543-546) says rememberSaveable was added *to survive* the Samsung SAF round-trip; it makes that round-trip fatal instead.
Secondary defects on the same path:
- `exportFile` uses `openOutputStream(outputUri, "w")` (VaultFolderStore.kt:150) — "w" does not guarantee truncation on all providers; re-exporting over an existing shorter file can leave trailing garbage. Use "wt".
- Full decrypt + write on the main thread (same as A4).
- Dead code: `createOutput` launcher (MainActivity.kt:537-539) is created and never launched — leftover scaffolding.

### A9. Delete file (with confirm) — **WORKING**
Delete tap is `SecureOutlinedButton` (MainActivity.kt:704, tap-jack filtered per common-security SecureButton.kt:111-144); confirm dialog sets `filterTouchesWhenObscured` on the dialog view and checks `hasWindowFocus()` before committing (MainActivity.kt:642-667); blob delete + metadata rewrite (VaultFolderStore.kt:160-167); honest "no recycle bin" copy (:650-655). Matches SAMSUNG_QUIRKS.md:38-45 rule exactly.

### A10. Lock semantics (manual + lifecycle) — **WORKING** (now that TestingMode is false)
Manual Lock button → `v.lock(); setUnlocked(null); onClose()` (MainActivity.kt:294). KEK zeroized in place (VaultFolderStore.kt:173-176; Crypto.wipe). Lifecycle: onUserLeaveHint locks + `finishAndRemoveTask` unless transient flight (MainActivity.kt:170-185); onStop locks unless config-change/flight (:195-211); onDestroy locks (:213-218). With `KEEP_ALIVE_ON_LEAVE=false` and `ALLOW_SCREENSHOTS=false` (TestingMode.kt:34,56) the production posture is active: FLAG_SECURE (MainActivity.kt:115-120), `setHideOverlayWindows` (:121-125), recents screenshot off (:126-130). `KeepAliveBackHandler` compiles to a no-op (common-security KeepAliveBackHandler.kt:29). Residual: the resume-time tamper recheck is correctly skipped during flight (:220-237). Stale RELEASE-BLOCKER comments as noted in the preamble.

### A11. Multi-folder: list / create / switch — **WORKING** (with four defects)
Trace: ListScreen folder row (MainActivity.kt:593-598) → `FoldersScreen` (FoldersScreen.kt:55-191) → `VaultFolders.list` (VaultFolders.kt:66-78, synthesized Default + header-filtered secondaries) → per-folder unlock via biometric (FoldersScreen.kt:102-131) → store swap in `VaultFolderRoot` (MainActivity.kt:298-317, old store locked on switch). Create: name dialog → `reserveNew` → biometric → `VaultFolder.create(id)` with rollback on fail/cancel (FoldersScreen.kt:248-338). Each folder has its own KEK wrapped by the same device-auth Keystore key — the on-screen claim "Each folder has its own master key, biometric-released" (FoldersScreen.kt:75-77) is accurate.
Defects:
1. `promptAuthLocal` uses `Executors.newSingleThreadExecutor()` (FoldersScreen.kt:353) — unlock/create callbacks (store swap, Compose state writes, `VaultFolders.delete` rollback I/O) run on a background thread, unlike MainActivity's main-executor `promptAuth` (MainActivity.kt:499). Works via snapshot-system tolerance, but it's an inconsistency and a latent-race surface.
2. Cancel set omits `ERROR_CANCELED` (FoldersScreen.kt:363-365 vs MainActivity.kt:506-510) — a system-initiated cancel renders as a red "Auth failed" error.
3. Layout: `LazyColumn` has no `weight(1f)` (FoldersScreen.kt:82-85) — with enough folders it consumes the whole viewport and pushes "Create folder"/"Back" off-screen in the non-scrollable parent Column.
4. Dismissing the create dialog at step 0 after a prior failed attempt leaves reserved orphan index rows; `pruneOrphans` (VaultFolders.kt:137-140) is **never called from anywhere** — dead API (list() filtering hides the symptom).

### A12. Multi-folder: delete folder — **WORKING but guard-inconsistent**
`VaultFolders.delete` wipes dir + index (VaultFolders.kt:125-134); default folder refused (:126-128). But: the row's Delete is a plain `OutlinedButton` (FoldersScreen.kt:237-241) and the confirm dialog has **neither** `filterTouchesWhenObscured` **nor** the `hasWindowFocus()` check (FoldersScreen.kt:168-190) — while the *file* delete path has both (A9). Per the suite's own rule (SAMSUNG_QUIRKS.md:38-41: Secure* goes on irreversible destructive paths), destroying an entire folder of files is the *most* destructive action in the app and has the *weakest* tap-jacking guard. Also the post-delete success message renders in the error slot in red (FoldersScreen.kt:80, :181).

### A13. Folder rename — **UNFINISHED (dead API, no UI)**
`VaultFolders.rename` (VaultFolders.kt:107-116) exists, tested by nothing in this module, reachable from no screen. Not a dead *button* (doctrine cares about UI honesty; nothing is promised), but it's dead code shipping in the APK.

### A14. In-vault file viewing / previews — **ABSENT (deliberate; positioning consequence)**
There is no viewer, no thumbnails, no share-sheet, no decrypt-to-cache anywhere in the module (EntryRow shows name/mime/size text only, MainActivity.kt:676-710). Verified: the only decrypt paths are `exportFile` (user-chosen SAF destination) and `readBlob` feeding it. This honors the no-preview invariant (SUITE_THREAT_SURFACES.md:152-155) and means **no thumbnail leak and no cache leak exist** — genuinely clean. The flip side: the SUITE_DESIGN #4 flow "Open file → temporary decryption to ContentProvider URI → handed to ACTION_VIEW" (understory-common SUITE_DESIGN.md:593-594) is **not implemented**, so the only way to look at a vaulted file is to export a *plaintext copy* out of the vault — a worse privacy outcome per use than an in-app viewer would be. This is the app's biggest product gap vs. its incumbent (Secure Folder shows your files). See ship-gap #4/E.

### A15. Suite capability beacon + mesh (SuiteCapsProvider / footer / attestation / tamper) — **WORKING** (with an eng-flavor defect)
Trace: provider (SuiteCapsProvider.kt:10-12 → common-security BaseCapabilityProvider.kt:52-127; read-only, signature-permission-gated, belt-and-braces permission recheck :84-86) → manifest wiring (AndroidManifest.xml:200-206) → consumers via `SuiteCapabilityRegistry.snapshot` (SuiteCapabilityRegistry.kt:152-197; cert-pin + local KNOWN_PEERS version map, capability-spoof-proof) → `SuiteStatusFooter` on ListScreen (MainActivity.kt:634). Tamper + SuiteAttestation hard-fail gates at onCreate (MainActivity.kt:106-113; Tamper.kt:81-98; SuiteAttestation.kt:66-106), resume recheck with flight suppression (:220-237). Pins centralized (SuitePins.kt:24-35), build-time `verifyCertPin` (root build.gradle.kts:18-113).
Defect (eng flavor): provider authority is the **literal** `com.understory.vaultfolder.suitecaps` (AndroidManifest.xml:202), but the eng flavor's applicationId is `com.understory.vaultfolder.eng` (vault-folder/build.gradle.kts:71-75). Consequences: (a) prod + eng builds **cannot be installed side by side** — duplicate provider authority → `INSTALL_FAILED_CONFLICTING_PROVIDER`; (b) an eng install's authority doesn't follow its package, and peers' `providerAuthorityFor(pkg)` (SuiteCapabilityRegistry.kt:92-93) would query the wrong name anyway; (c) eng builds are invisible to the mesh entirely (`.eng` id is in nobody's KNOWN_PEERS/SUITE_PACKAGES/`<queries>` lists — SuiteCapabilityRegistry.kt:56-82, SuiteAttestation.kt:43-51, AndroidManifest.xml:113-138). Only (a) is a real bug; (b)/(c) are acceptable if documented.

### A16. Diagnostics screen + eng dump — **WORKING**
DiagnosticsScreen reachable from ListScreen (MainActivity.kt:295, :631-633, :336-339); ring buffer + copy/clear (common-security DiagnosticsScreen.kt:52-108, Diagnostics.kt:30-140). Eng-only file dump gated on `.eng` package suffix (DiagnosticsDump.kt:95-126), pref snapshots redacted by denylist (:210-234) — note "vault" and "entry" are in the denylist (:231-234) so vault-folder prefs would redact, though the app writes no SharedPreferences at all (verified: no getSharedPreferences call in module). Shape-only logging discipline held throughout MainActivity (URIs logged as non-null/null, e.g. :550-551, :778-779).

### A17. Storage format hardening (atomic writes, tmp sweep, bounded header parse) — **WORKING**
Atomic write with ATOMIC_MOVE + rename fallback (VaultFolderStore.kt:222-243); orphan `.tmp` sweep on create/unlock (VaultFolder.kt:74-79, :87, :110); header parse with strict bounds + trailing-byte refusal (VaultFolder.kt:152-167). Hardening note (not a blocker): blobs and metadata are encrypted with **no AAD binding** blob-id/folder-id to ciphertext (VaultFolderStore.kt:186, :194; Crypto.aesGcmEncrypt aad param exists but unused) — an attacker with data-dir write access could swap two blob files undetected (contents/name mismatch). In-threat-model relevance is marginal (that attacker owns the sandbox), cheap to close.

### A18. Backup/off-device recovery integration — **MISLEADING (docs + in-app copy), code absent**
No BackupProvider/BackupAdapter/common-backup anywhere in this repo's app module (grep-verified). But understory-common SUITE_DESIGN.md:598 states "vault-folder exposes `BackupProvider` like the others. Files in the folder are part of the suite-wide backup" — false today. And the Setup screen tells the user "Use backups (#7 in the suite) for off-device recoverable copies" (MainActivity.kt:402-404): (a) there is no automated path — the user would have to export plaintext and re-encrypt via the backups app manually; (b) the number is wrong — vault-folder itself is #7; backups is #4 (SUITE_DESIGN.md:7-15). Given "lost device = lost vault" is this app's sharpest edge, pointing at a non-existent integration is a real honesty defect, not cosmetics.

### A19. Manifest permission strip / no-network posture — **WORKING**
USE_BIOMETRIC only; ~70 permissions force-removed with `tools:node="remove"` (AndroidManifest.xml:27-111); INTERNET stripped + cleartext denied (network_security_config.xml:9-11, AndroidManifest.xml:148-149); allowBackup=false + full extraction-rule excludes (data_extraction_rules.xml:1-17). Matches SUITE_THREAT_SURFACES.md:150-151. Doc drift: SUITE_DESIGN.md:815 lists vault-folder with POST_NOTIFICATIONS and :577-581 with READ_MEDIA_* — neither is held; the manifest is stricter than the design doc (good direction, doc stale).

---

## B. EXCLUSIVE-SLOT & COEXISTENCE

**Scarce-slot usage: NONE.** Verified against the full manifest:
- VPN slot: not touched (no VpnService; NETWORK stack stripped). Tailscale's permanent hold is unaffected. ✔ doctrine.
- Autofill service / IME: none declared. Bitwarden/1Password/Samsung Keyboard unaffected.
- Accessibility, notification listener, usage-stats, device admin, default-app roles: none (BIND_* even force-removed, AndroidManifest.xml:94-98).
- Biometric: USE_BIOMETRIC is not a scarce slot — BiometricPrompt is shared infrastructure.
- The only system-visible registration is the ACTION_VIEW **chooser membership** (AndroidManifest.xml:187-197): the app appears in "Open with…" lists for images/PDF/text/json/csv/octet-stream. This is additive (joins the chooser, never claims default) but note it inserts "vault folder" into the user's share/open flows for very common types — on a real phone the chooser gets noisier. Acceptable; consider trimming `image/*` breadth or relying on suite-peer explicit `setPackage` deposits only.

**Incumbents & conflicts:**
- **Samsung Secure Folder** (system, work-profile): no mechanism overlap — different UID world, no shared slots, no interference either direction. Vault-folder cannot see into Secure Folder and vice versa; SAF pickers don't cross the profile boundary, so "import from Secure Folder" isn't a flow. No conflict; also no interop.
- **Google Files / Samsung My Files "Safe Folder"**: same story — vault-folder is one more encrypted destination, coexists.
- **Play Protect**: clean (no dangerous permissions, no DEX loading, no network).

**Complement opportunities (concrete):**
1. Deposit target: already implemented — any app (incl. My Files, Secure Folder's export-to-SAF, suite peers via `Intent.setPackage`) can hand a file in (A7). This is the app's strongest complement primitive; fix the confirmation honesty (ship-gap #3) and it's a clean story.
2. Export = plain files via SAF: universal-format output (original bytes), so nothing locks in. ✔.
3. Suite mesh: FILE_VAULT capability beacon lets backups/passgen surface "vault available" affordances (A15).
4. Missing complement: a one-tap "send encrypted copy to backups app" hand-off (ACTION_VIEW into `com.understory.backups` with the octet-stream envelope) would make the A18 story true with ~no new mechanism — the deposit pattern already exists on the receiving side.

---

## C. GUI AUDIT (screen by screen)

Global: MaterialTheme with `darkColorScheme()` (MainActivity.kt:142) but virtually every color is a hardcoded hex literal (0xFF0A0A0A/1C1C1C/E0E0E0/9E9E9E/FFB74D/EF5350 — throughout MainActivity.kt and FoldersScreen.kt); no Typography scale, no TopAppBar/Scaffold, no dynamic color. Dark-only by construction (themes.xml:3-7 forces black; no values-night needed but also no light theme — acceptable if declared as intentional, currently undeclared). **Strings: only `app_name` is a resource (strings.xml:3); 100% of UI copy is hardcoded Kotlin** — l10n impossible (resourceConfigurations pinned to "en", build.gradle.kts:17, so at least self-consistent). Portrait-locked + `resizeableActivity="false"` (AndroidManifest.xml:162-163) — no landscape/foldable/tablet support, an a11y and One-UI-DeX regression.

- **Setup** (MainActivity.kt:362-437): good copy honesty (size cap, lost-device warning); error state inline; loading = biometric prompt itself. No a11y semantics beyond text. Verdict: functional, non-Material3-polished.
- **Unlock** (:439-489): working/`Authenticating…` progress on the button label; inline errors; Close path. Fine.
- **List** (:525-674): empty state present ("No files yet…", :602-605); LazyColumn keyed by id (:612); Lock/Add/Folders/Diagnostics navigation coherent; SuiteStatusFooter present (:634) ✔ consistency with common-security widgets; delete dialog exemplary (A9). Gaps: no loading state distinction (list is synchronous), export has no in-progress state, file rows have no contentDescription/semantic role (Text-only, acceptable for TalkBack but row action buttons rely on generic "Export"/"Delete" labels — actually fine), 9-11sp footer/metadata text is below comfortable-readability thresholds.
- **Add** (:712-901): status line for results incl. shred-failure honesty; "Encrypting…" state exists but is unreachable in practice (main-thread block, A4); Switch row: Switch has its own onCheckedChange and the parent Row is NOT clickable — quirks-rule compliant (SAMSUNG_QUIRKS.md:106-117); fully-qualified inline `androidx.compose...` names (:833-866, :873-899) betray the unfinished pass. Back path present.
- **Folders** (FoldersScreen.kt:55-191): current-folder highlight + "· unlocked" tag good; **layout bug** — LazyColumn without weight can push Create/Back off-screen (A11.3); delete confirm lacks the tap-jack guard (A12); success message shown in red error slot (:181); date formatting via SimpleDateFormat inline (:223-226). Create dialog: two-step with clear copy; disabled-state handling on Next correct (:287-294).
- **Diagnostics** (common-security DiagnosticsScreen.kt): consistent with the rest of the suite ✔.
- **Crash screen** (MainActivity.kt:89-96): honest raw-throwable surface; fine for alpha, not shippable copy.

Doctrine scorecard: coherent navigation ✔, empty/error states mostly ✔, loading states ✗ (blocked by main-thread work), Material3 conformance ✗ (hardcoded palette, no components beyond buttons/dialogs), a11y partial (touch targets ✔ via default buttons, small text ✗, orientation lock ✗), strings-in-res ✗, dark theme ✔ (forced), zero dead UI ✔ **in the visible layer** (no dead buttons found; dead code exists behind the scenes: A8 launcher, A11.4, A13).

---

## D. SHIP-GAP LIST (ranked)

| # | Gap | Size | Tag | Detail |
|---|-----|------|-----|--------|
| 1 | **Export crashes**: non-Parcelable `VaultFolderEntry` in `rememberSaveable` blows up state-save during the SAF round-trip (MainActivity.kt:546) | S | FIX | Save the entry **id string** (saveable) and resolve against `store.contents` on return; or make VaultFolderEntry Parcelable. Also delete the dead `createOutput` launcher (:537-539). |
| 2 | **Main-thread crypto/I/O** on add + export (MainActivity.kt:734-764, :556-566; violates SAMSUNG_QUIRKS.md:119-129) | M | FIX | `rememberCoroutineScope` + `Dispatchers.IO`, disable buttons while working, make "Encrypting…" real, add progress for near-cap files. |
| 3 | **Deposit auto-encrypt contradicts documented confirmation** (code MainActivity.kt:790-795 vs AndroidManifest.xml:174-180 and SUITE_THREAT_SURFACES.md:138-140) | S | FIX | Add a per-deposit confirm dialog (filename + source-inert rendering) before `runAdd`; update both docs. Also drop `BROWSABLE` (AndroidManifest.xml:190) and add `onNewIntent` handling or document the drop (singleTask, AndroidManifest.xml:160). |
| 4 | **No way to view a file without exporting plaintext** (A14) | L | REDESIGN | Mechanism sketch: in-app FLAG_SECURE viewer for image/text/PDF that decrypts to memory only (20 MiB cap makes this feasible), rendered by an `isolatedProcess` service on the antivirus `ApkParserService` pattern (SUITE_THREAT_SURFACES.md:167-175) so hostile bytes never parse in the vault process. Until built: add one honest line to the List screen — "Viewing requires export; exported copies are unencrypted" — so the current behavior is stated, not discovered. |
| 5 | **Folder-delete guard parity**: most destructive action has the weakest tap-jack defense (FoldersScreen.kt:237-241, :168-190 vs MainActivity.kt:642-667) | S | FIX | SecureOutlinedButton on the row + `filterTouchesWhenObscured`/`hasWindowFocus` on the confirm dialog, same as file delete. |
| 6 | **GUI doctrine pass**: hardcoded palette/copy, no res strings, unreachable loading states, portrait lock, sub-12sp text (Section C) | L | FIX | Materialize theme tokens, move copy to strings.xml, Scaffold/TopAppBar, revisit orientation lock, minimum text sizes. |
| 7 | **eng/prod provider-authority collision** — `INSTALL_FAILED_CONFLICTING_PROVIDER` when both flavors installed (AndroidManifest.xml:202 vs build.gradle.kts:71-75) | S | FIX | `android:authorities="${applicationId}.suitecaps"`; document that eng builds are mesh-invisible by design. |
| 8 | **FoldersScreen thread + cancel-code defects** (background executor FoldersScreen.kt:353; missing ERROR_CANCELED :363-365) | S | FIX | Use `ContextCompat.getMainExecutor`; unify with MainActivity's promptAuth (dedupe the shim). |
| 9 | **Folders layout**: LazyColumn without weight pushes Create/Back off-screen with many folders (FoldersScreen.kt:82-85) | S | FIX | `Modifier.weight(1f)`. |
| 10 | **Backups-integration honesty**: in-app copy points at a non-existent integration with the wrong suite number (MainActivity.kt:402-404; SUITE_DESIGN.md:598) | S | FIX | Reword to "export + encrypt with the backups app (manual)" now; the real hand-off intent is a v2 item (Section B.4). Fix "#7"→"#4". |
| 11 | **Export truncation**: `"w"` mode may not truncate pre-existing documents (VaultFolderStore.kt:150) | S | FIX | Use `"wt"`. |
| 12 | **Dead code sweep**: `pruneOrphans` never called (VaultFolders.kt:137-140), `rename` has no UI (VaultFolders.kt:107-116), 3-arg `addFile` shim unused (VaultFolderStore.kt:78-84) | S | FIX / DROP-TO-V2 | Call pruneOrphans on FoldersScreen entry; either build a rename row action (S) or delete the API until v2. |
| 13 | **AAD binding**: encrypt blobs/metadata with AAD = folderId+blobId to kill silent blob-swap (VaultFolderStore.kt:186,194; Crypto.kt:88-110 already supports aad) | S | FIX | Needs a format-version bump; do it before first public release while the format is still cheap to break. |
| 14 | **Stale doc/comment drift**: manifest "relaxed for testing" comment (AndroidManifest.xml:153-156), MainActivity RELEASE-BLOCKER comments (:178-181, :204-206), SUITE_DESIGN permission table (:815, :577-581), threat-surfaces confirmation claim (fixed by #3) | S | FIX | Pure editing; prevents a future session trusting wrong claims. |

Totals: **S=11, M=1, L=2.**

## E. COMPLEMENT POSITIONING

Vault-folder should be **the portable, inspectable encrypted drop-box that lives alongside Samsung Secure Folder — not under it**: Secure Folder gives OS-level isolation with Samsung/Knox lock-in, opaque internals, and no cross-device story; vault-folder gives a fully auditable AES-256-GCM-per-file format in ordinary app storage, a biometric gate bound to the hardware Keystore, a universal "Open with… → encrypt into vault" deposit target that any app (including file managers exporting *out* of Secure Folder) can use, and plain-file SAF export with zero lock-in. Its honest pitch next to the incumbent is: "when you want to *know* how your files are encrypted, drop files in from any app's share flow, and keep the format portable — use this; when you want whole-app isolation, keep using Secure Folder — we don't touch it." To earn that sentence it must fix the export crash (D1), stop encrypting deposits without asking while docs claim otherwise (D3), and either ship a memory-only viewer or say out loud that viewing means exporting plaintext (D4) — the deposit primitive and the shred-source honesty model are already the best-in-suite examples of the complement doctrine working.
