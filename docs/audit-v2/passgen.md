# passgen — alpha-to-shippable audit (v2 doctrine)

Audited 2026-07-03 against the COMPLEMENT-DON'T-REPLACE / VIABILITY-HONESTY /
SHIPPABLE doctrine. Scope: `understory-passgen\passgen\` + vendored
`common-security` / `common-backup` (verified byte-identical to canonical
`understory-common` copies via recursive diff — no drift).

All paths below relative to `C:\repos\understory\understory-passgen\` unless
prefixed `common:` (= `C:\repos\understory\understory-common\`).

---

## A. FEATURE LEDGER

### Generator core

| # | Feature | Verdict | Evidence |
|---|---------|---------|----------|
| A1 | Password generation (SecureRandom, per-class guarantee, CharArray+wipe) | **WORKING** | `passgen/src/main/java/com/understory/passgen/PasswordGenerator.kt:28-64`; unit-tested `src/test/.../PasswordGeneratorTest.kt` |
| A2 | Generation settings persistence (length/classes/auto-clear) | **WORKING** | `Settings.kt:33-56`; saved from UI at `MainActivity.kt:367-381`; consumed by IME (`PassgenInputMethodService.kt:177`), autofill (`PassgenAutofillService.kt:100`), fill activity (`GenerateAndFillActivity.kt:73`) |
| A3 | Generate & Copy to clipboard w/ IS_SENSITIVE flag + auto-clear timer | **WORKING** (with honesty caveats, see A4/B) | `MainActivity.kt:680-710` → `common:common-security/.../Clipboard.kt:44-114` (flag set :77-79, label+token-guarded clear :101-114) |
| A4 | "Auto-clear in Ns" promise | **MISLEADING (minor)** | Toast at `MainActivity.kt:695-699` promises the clear unconditionally, but the clear is a process-scoped `Handler` (`Clipboard.kt:34`, admitted at `MainActivity.kt:284-286`). Swipe-away/process death before the timer ⇒ password stays on the clipboard forever. Samsung keyboard clipboard-panel caveat IS honestly surfaced (`MainActivity.kt:673-678`) — good. |

### Autofill (exclusive-slot feature)

| # | Feature | Verdict | Evidence |
|---|---------|---------|----------|
| A5 | AutofillService: password/username field detection (hints, inputType, HTML `type=password`/`autocomplete`) | **WORKING** | `PassgenAutofillService.kt:39-161` (detection :205-307); fail-closed crash catcher :44-53; debugger/tamper refusals :61-75 |
| A6 | Autofill dataset 1: "pick saved entry" → biometric → picker → Dataset via `EXTRA_AUTHENTICATION_RESULT` | **WORKING** (complete traced path) | `PassgenAutofillService.kt:102-131` → `FillSavedEntryActivity.kt:75-141` (BiometricPrompt+CryptoObject :217-258, unlock `Vault.unlockV2` :230, picker :282-359, dataset return :384-417). Domain/package match heuristic :442-453. Empty-vault state present :314-326. |
| A7 | Autofill dataset 2: "generate (N chars)" → invisible activity fills field | **WORKING mechanically / UNVIABLE-AS-DESIGNED as product behavior** | `PassgenAutofillService.kt:133-153` → `GenerateAndFillActivity.kt:41-106`. The generated password is committed to the target field and **recorded nowhere** — not vault, not ledger (`GenerateAndFillActivity.kt:81-105`: generate → AutofillValue → wipe → finish). A user who accepts "passgen — generate" on a signup form is locked out of that account at next login. This is the account-lockout trap; must save a receipt or be dropped. |
| A8 | `onSaveRequest` | **WORKING (intentional no-op)** | `PassgenAutofillService.kt:163-166`; no `SaveInfo` is ever set in the FillResponse, so the framework never triggers save. Honest ("We never save anything from the user") but interacts badly with A7. |
| A9 | Inline suggestions (keyboard-strip chips, IME integration on Android 11+) | **UNFINISHED (declared off)** | `res/xml/autofill_service.xml:3` `supportsInlineSuggestions="false"` — on Samsung/Gboard the suggestion appears as a dropdown, not inline chip. Deliberate but limits discoverability next to Bitwarden's inline chips. |

### IME (second scarce surface)

| # | Feature | Verdict | Evidence |
|---|---------|---------|----------|
| A10 | Custom keyboard: Generate & insert via `InputConnection.commitText` | **WORKING** (complete path: enable → switch → tap → commit) | Manifest service `AndroidManifest.xml:267-278`; `res/xml/method.xml`; view build `PassgenInputMethodService.kt:95-162`; commit :164-191; wipe in `finally` :185-186. Enable/switch flow in `MainActivity.kt:631-657` (`ACTION_INPUT_METHOD_SETTINGS`, `showInputMethodPicker`), enabled-state detection :754-762. Tap-jack gate on Generate (`ObscuredTouchGate` :238-256), FLAG_SECURE on IME window :64-69, per-focus tamper re-check :208-226. |
| A11 | IME generated password persistence | **UNVIABLE-AS-DESIGNED** (same lockout trap as A7) | `PassgenInputMethodService.kt:181-186` — value committed and wiped, never recorded. IME text even brags "value never reaches the clipboard" (:123-125) while also never reaching the vault. |
| A12 | IME error behavior | **UNFINISHED (silent-fail by design)** | `onGenerateClicked` swallows everything (`PassgenInputMethodService.kt:188-191` comment: "tap does nothing visible"). No user feedback path = dead-feeling button on failure. Refusal/crash views exist (:82-93, :197-206) — good. |
| A13 | IME typing saved vault entries (use vault via keyboard in apps that block autofill) | **MISSING** (not built) | No vault access anywhere in `PassgenInputMethodService.kt`. The main screen sells the IME as "the universal coexistence path" (`MainActivity.kt:590-593`) but it can only generate NEW passwords — it cannot deliver a saved credential, so coexistence-mode users (Bitwarden holds autofill) have no passgen path to retrieve saved entries except manual clipboard copy from VaultActivity. |

### Vault

| # | Feature | Verdict | Evidence |
|---|---------|---------|----------|
| A14 | v2 vault create: random 32-byte KEK, Keystore device-auth wrap (StrongBox attempt), self-sealed master entry[0] | **WORKING** | `Vault.kt:202-249`; `common:.../Crypto.kt:118-170` (`setUserAuthenticationParameters(0, BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` :159-162, `setInvalidatedByBiometricEnrollment(true)` :165); setup UI `VaultActivity.kt:325-420` |
| A15 | Unlock via BiometricPrompt + CryptoObject | **WORKING** | `VaultActivity.kt:422-497`, `promptAuth` :499-538, `Vault.unlockV2` :255-271 |
| A16 | List / Add (generate-only) / View / Delete entries; lock-on-background | **WORKING** | List `VaultActivity.kt:540-595`; Add :597-673; View :805-992; delete :277-281 & :988-990 (**no confirmation dialog** — one tap destroys); lock on pause/leave/destroy :154-186 (TestingMode flags verified `false`: `common:.../TestingMode.kt:34,56`) |
| A17 | View entry: copy-to-clipboard (biometric) and regenerate+save (biometric); never renders password | **WORKING** | `VaultActivity.kt:845-932`; masked dots :952 |
| A18 | "View master key, biometric-gated, 10s window" claimed at setup | **MISLEADING** | Setup copy `VaultActivity.kt:372` ("After unlock, you can view it (biometric-gated, 10s window)"); ViewEntryScreen has **no reveal path at all** — only copy/regenerate (:845-932, threat-model comment :825-843 says never render). The claim describes a feature that does not exist. |
| A19 | "Settings → reset vault" recovery hint | **MISLEADING + dead-end** | `VaultActivity.kt:490-495` tells a locked-out user to use "Settings → reset vault". No such screen/button exists anywhere (only programmatic `Vault.delete` at `Vault.kt:157-161`, called solely for stale-v1 wipe at `VaultActivity.kt:127-129`). After biometric re-enrollment invalidates the Keystore key (`Crypto.kt:165`), the vault is **permanently un-openable AND un-resettable from the UI**. |
| A20 | Vault file format hardening (atomic replace, length caps, trailing-byte rejection) | **WORKING** | `Vault.kt:121-143` (ATOMIC_MOVE), :318-349 (caps + trailing-byte check) |
| A21 | v1 reveal-lock remnants | **UNFINISHED (dead code)** | `Vault.kt:33-39` (doc describes `reveal_lock_hash` that v2 never writes), `MIN_REVEAL_LOCK_LEN` :93, `REVEAL_M/T/P` :110-112 — all unreferenced in v2 paths. |

### Import / export / backup

| # | Feature | Verdict | Evidence |
|---|---------|---------|----------|
| A22 | Import: Google Password Manager CSV | **WORKING** | `ImportFormats.kt:75-92` (detect :68), RFC-4180 parser :199-240; 17 unit tests `src/test/.../ImportFormatsTest.kt` |
| A23 | Import: Proton Pass JSON (unencrypted) + Proton Pass CSV | **WORKING** | `ImportFormats.kt:95-155`; encrypted-export rejected with actionable message :123-127 |
| A24 | Import: **Bitwarden CSV/JSON** | **MISSING** | Verified absent: `detect()` recognizes only the three formats above (`ImportFormats.kt:50-72`); no Bitwarden column set (`folder,favorite,type,name,notes,fields,login_uri,login_username,login_password,...`) or Bitwarden JSON (`"items":[{"type":1,"login":{...}}]`) anywhere. For an app doctrinally positioned NEXT TO Bitwarden this is the single most important missing format, both directions. |
| A25 | Import UI (SAF picker, dedup by url+username, status line, auto-return) | **WORKING** | `VaultActivity.kt:675-803`. Caveats: file read + parse on main thread (comment :740-741; ANR risk on large/hostile files), no progress indicator, `fragment-ktx:1.8.5` override specifically to fix the SAF-picker crash (`build.gradle.kts:121-130`). |
| A26 | "Open with…" ACTION_VIEW → import | **WORKING mechanically / MISLEADING vs. stated contract** | Manifest filter `AndroidManifest.xml:201-209`; forward `MainActivity.kt:162-177`; **manifest comment :190-199 promises "the user still has to unlock with biometric and tap to confirm. No code path bypasses the … confirmation" — but `ImportScreen` auto-runs the import with NO confirmation tap once unlocked** (`VaultActivity.kt:754-759` LaunchedEffect → `runImport(incomingUri)`). Any app can fire ACTION_VIEW with a crafted CSV; if the user unlocks, entries merge silently (dedup prefers existing, but new url/username pairs are added — vault-poisoning / picker-phishing surface). Code and comment must be reconciled; the comment describes the correct design. |
| A27 | Encrypted portable backup (BackupFormat, Argon2id+AES-GCM, "Stage 2C") | **UNFINISHED (dead code, no UI, prerequisite missing)** | `BackupFormat.kt:56-229` complete and unit-tested (`BackupFormatTest.kt`), but zero call sites outside tests (grep over `src/main`: only self-references). Payload requires `totp_secret_b64` from "HOTP secret stored as entry[1] of the vault" (:17-18, :38) — **no code ever creates entry[1]**; `Vault.createV2` seals only the master entry[0] (`Vault.kt:236`). Restore flow (:46-55) is a comment, not code. |
| A28 | Suite backup adapter (export/import payload for backups app) | **UNFINISHED (dead code)** | `PassgenBackupAdapter.kt:43-122` implements `common:common-backup/.../BackupAdapter.kt` correctly (master-entry filtering both directions :60-63, :86-89) but is instantiated nowhere; no orchestrator hookup, no in-app export UI. |
| A29 | Any user-reachable export at all | **MISSING** | Consequence of A27+A28: the vault is import-only. Passwords check in; they never check out except one-at-a-time clipboard copy. Combined with A19 this is the top data-loss risk. |

### Hardening & suite plumbing

| # | Feature | Verdict | Evidence |
|---|---------|---------|----------|
| A30 | FLAG_SECURE / hideOverlayWindows / recents-screenshot-off / excludeFromRecents | **WORKING** | `MainActivity.kt:120-138`, `VaultActivity.kt:107-122`, `FillSavedEntryActivity.kt:103-109`, `GenerateAndFillActivity.kt:55-61`; manifest :181, :215, :224, :245; `TestingMode` flags false (`common:.../TestingMode.kt:34,56`; RELEASE_BLOCKERS "Resolved") |
| A31 | Tamper detection (sig pin, Xposed/Frida/LP probes, installer check) + resume/focus re-checks | **WORKING** | `common:.../Tamper.kt:81-235`; call sites `MainActivity.kt:147-154,308-316`, `PassgenAutofillService.kt:72-75`, `PassgenInputMethodService.kt:47-57,208-226`, both fill activities |
| A32 | SuiteAttestation sibling cert mesh + `<queries>` visibility | **WORKING** | `common:.../SuiteAttestation.kt:66-106`; manifest `<queries>` :124-153; hard-fail wiring `MainActivity.kt:148-153` |
| A33 | SuiteCapsProvider beacon + SuiteStatusFooter + capability registry | **WORKING** | `SuiteCapsProvider.kt:12-14`; provider manifest :285-291 (signature read-perm, locked write-perm); `common:.../BaseCapabilityProvider.kt:69-93`; registry `common:.../SuiteCapabilityRegistry.kt:56-82,152-197`; footer rendered `MainActivity.kt:731` |
| A34 | Diagnostics ring + DiagnosticsScreen + eng-only file dump | **WORKING** | `common:.../Diagnostics.kt`, `DiagnosticsScreen.kt`; eng gate `DiagnosticsDump.activateIfEng` (`MainActivity.kt:81`); eng flavor `build.gradle.kts:84-94` |
| A35 | A11y-service threat warning | **WORKING** | `common:.../A11yProbe.kt:21-51`; surfaced `MainActivity.kt:436-452` |
| A36 | Tap-jacking defenses (SecureButton/SecureOutlinedButton, IME touch gate) | **WORKING** | `common:.../SecureButton.kt:43-144`; used on all sensitive actions |
| A37 | Permission mass-strip + no-network posture | **WORKING** | `AndroidManifest.xml:26-115`; `network_security_config.xml`; `data_extraction_rules.xml`; `allowBackup=false` :160 |
| A38 | Credential Manager guidance text | **MISLEADING (self-contradictory)** | `MainActivity.kt:566-570` tells the user to "use Credential Manager (Android 14+)" for coexistence, then :589-593 says "Credential Manager API isn't a fit here." Both paragraphs render on the same screen. No Credential Manager code exists. |
| A39 | Samsung dual-slot autofill guidance + settings deep-link | **PLAUSIBLE-MISLEADING / UNVERIFIED** | `MainActivity.kt:508-559`. Claims One UI offers a "Primary + Additional service" autofill pair. `DeviceProfile.supportsDualAutofillSlots()` exists (`common:.../DeviceProfile.kt:27`) but is never called — the branch keys on `isSamsung()` alone (`MainActivity.kt:508`), so older One UI without the additional slot gets instructions for a settings path that may not exist. The deep-link uses undocumented string action `"android.settings.AUTOFILL_SETTINGS"` (:528) with a fallback to the generic Settings root (:531-533) — which strands the user at the top of Settings. Must be verified on the operator's SM-S948U (SAMSUNG_QUIRKS.md has no autofill entry — verified by grep: only VPN-slot content). |

---

## B. EXCLUSIVE-SLOT & COEXISTENCE

**Scarce resources touched:**

1. **Autofill service slot** (`AndroidManifest.xml:255-265`, `BIND_AUTOFILL_SERVICE`).
   - *Incumbent on a real device:* Bitwarden / 1Password / Google / Samsung Pass. Operator doctrine: the slot may belong to Bitwarden.
   - *Conflict (stock Android):* exactly one provider. If Bitwarden holds it, **A5-A8 are entirely dormant** — passgen's autofill service never receives `onFillRequest`, and both fill activities are unreachable (they're only launched via dataset auth intents). That's ~40% of the app's code dark in the doctrinal configuration. The app fails *gracefully* (nothing breaks; the main screen's "Set passgen as autofill provider" button honestly implies replacement on the Samsung branch (:558) but the standard-branch button label (:586) does not say "replaces Bitwarden" — the explanation is only in the paragraph above (:566-570)).
   - *Conflict (Samsung):* the "Additional service" pitch (A39) is the differentiated coexistence story — IF it survives on-device verification on One UI 7. Unverified today.
   - *Complement opportunities:* (a) Bitwarden CSV/JSON import AND export (A24/A29) so passgen feeds the incumbent instead of hoarding; (b) inline-suggestion support so the generate chip can sit *beside* Bitwarden's chips in Samsung's dual-slot mode; (c) status surfacing: detect which provider holds the slot (`AutofillManager.hasEnabledAutofillServices()` only says "us or not" — show "Autofill: Bitwarden holds the slot; passgen is in keyboard mode" instead of a replace button as primary CTA).

2. **IME (keyboard list)** (`AndroidManifest.xml:267-278`). Non-exclusive to *enable* — multiple IMEs coexist; the user switches per-field via the system picker. This is passgen's genuinely coexistent delivery channel and it works (A10). Gaps: it cannot type saved entries (A13) and it deliberately sets `importantForAccessibility = NO` on the whole keyboard (`PassgenInputMethodService.kt:111`) — screen-reader users cannot operate it (a11y-vs-security tradeoff that should be documented as a known limitation).

3. **Not touched (correctly, per doctrine):** VPN slot, accessibility service, notification listener, device admin, default browser, usage stats, QUERY_ALL_PACKAGES. Verified stripped/absent (`AndroidManifest.xml:26-115`, `<queries>` allowlist only :124-153).

4. **Biometric/Keystore:** `USE_BIOMETRIC` only (:81). Non-scarce; coexists with Samsung's stack.

**Vault vs. Bitwarden (complement-or-replace):** As built, the vault is a small general password manager (import from Google/Proton, store, autofill saved entries) — a *direct replacement pitch* against the incumbent, with none of the incumbent's table stakes: no export, no sync, no recovery, no editing (entries can't be edited — only regenerated/deleted, `VaultActivity.kt:805-992`), no search, no folders, no reveal. On the operator's phone this loses to Bitwarden on every axis while asking the user to migrate passwords into a device-locked box with a data-loss cliff (A19/A29). See section E for the repositioning.

---

## C. GUI AUDIT

Global (applies to all screens):
- **Material3 conformance: poor.** M3 components are used (Button, OutlinedTextField, Switch, Slider) but every color is a hardcoded hex literal (`Color(0xFFE0E0E0)`, `Color(0xFF9E9E9E)`, etc. — 60+ occurrences across `MainActivity.kt`, `VaultActivity.kt`, `FillSavedEntryActivity.kt`) instead of `colorScheme` roles. `darkColorScheme()` is forced (`MainActivity.kt:180`, `VaultActivity.kt:140`); no dynamic color, no light theme, `themes.xml:3` parents `android:Theme.Material.NoActionBar` (not DayNight/M3). "Dark theme" passes only because the app is dark-only; a light-mode user gets no adaptation (acceptable if declared, currently undeclared).
- **Strings:** `res/values/strings.xml` contains exactly 2 strings (`app_name`, `ime_label`); every other user-facing string is hardcoded in Kotlin. `resourceConfigurations = ["en"]` (`build.gradle.kts:17`). No localization possible; several strings are long walls of text.
- **Typography:** heavy use of 9-11sp text (footer 9sp `SuiteStatusFooter.kt:127-155`, hints 11sp) — below comfortable-readability guidance for body text.
- **Navigation:** enum-state machines + BackHandler (`MainActivity.kt:319-346`, `VaultActivity.kt:189-307`). Coherent and predictable; no Scaffold/TopAppBar anywhere, no screen transitions. Back behavior correct per route (verified each `BackHandler`).
- **a11y:** text-labeled buttons are fine by default. Gaps: `ToggleRow` renders label Text and Switch as siblings without merged semantics (`MainActivity.kt:764-774`) — TalkBack reads an unlabeled switch; the Slider (:457-462) has no semantic label; masked-password dots (`VaultActivity.kt:952`) have no contentDescription; IME view opts out of accessibility entirely (see B.2).
- Portrait-locked + `resizeableActivity=false` (`AndroidManifest.xml:182-184`) — fails large-screen/foldable expectations on the operator's Fold-class device width, though defensible for a security surface.

Per screen:

| Screen | State coverage | Notes |
|---|---|---|
| Generator (`MainActivity.kt:348-752`) | error: inline "Enable at least one character set" :712-717; loading: n/a | Longest screen in the app; reads as a settings-page-plus-essay (five prose blocks, incl. the contradictory A38 pair and the honesty warning box :666-679 — the warning box itself is good). Autofill/IME status re-read on ON_START :740-751 (correct). Diagnostics + SuiteStatusFooter present :728-731. |
| Diagnostics (shared `DiagnosticsScreen.kt`) | empty state :94-96; auto-refresh :58-63 | Consistent with suite. Non-secure Buttons (fine — no secrets). |
| Vault Setup (`VaultActivity.kt:325-420`) | device-unsupported state :346-359 (good); error text :398 | Contains the false "10s reveal window" claim (A18). "Generate via IME pipeline" button label is a metaphor, not the truth (it calls `Crypto.randomBytes`, not the IME) — borderline honest. |
| Vault Unlock (:422-497) | loading "Authenticating…" :485; error :445; lockout hint after 3 attempts :490-495 | Lockout hint points to nonexistent reset (A19) — worst copy bug in the app. |
| Vault List (:540-595) | **no empty state** — a fresh vault shows "1 entries" (the master entry) as an ordinary row; a hypothetically empty list shows just "0 entries" | Master-KEK entry is rendered like any credential (and is pickable in the autofill picker) — needs distinct treatment/hiding. No search; fine at alpha scale. |
| Add entry (:597-673) | error :627; working-state on button :666 | Generate-only (no manual password entry) — consistent with threat model, but means you cannot store an existing credential except via file import. |
| View entry (:805-992) | status line color-coded :960-967 | Delete has no confirmation and is styled identically to "Back" directly above it :985-990 — one-slip permanent destruction. No edit path. |
| Import (:675-803) | loading text :787; status/error :789-791; auto-done :795-801 | Main-thread I/O; no spinner; supported-format list :766-772 matches code exactly (honest). |
| Autofill picker (`FillSavedEntryActivity.kt:282-359`) | auth state, error+retry state :261-280, empty-vault state :314-326 | Best state coverage in the app. Plain `clickable` on rows (not `secureClickable`) while `onPick` releases a credential — inconsistent with the suite's own SecureButton doctrine (`SecureButton.kt:31-33`). |
| IME keyboard (`PassgenInputMethodService.kt:95-162`) | refusal + crash views :197-206, :82-93 | Plain Views, adequate touch targets (52/44dp) :138-157; silent failure on generate (A12). |

---

## D. SHIP-GAP LIST (ranked)

| Rank | Gap | Size | Tag | Detail |
|---|---|---|---|---|
| 1 | **Vault recovery dead-end**: biometric re-enrollment invalidates the Keystore key (`Crypto.kt:165`) → unlock impossible forever; the advertised "Settings → reset vault" doesn't exist (`VaultActivity.kt:492`); no export exists to have escaped beforehand | L | FIX | Add a real "Reset vault (erases everything)" flow calling `Vault.delete` + `Crypto.deleteDeviceAuthKey` from the Unlock error path, with typed-confirmation. Do NOT ship import (A22-A26) while this cliff exists. |
| 2 | **No export/backup reachable by users** — vault is import-only; `BackupFormat` + `PassgenBackupAdapter` are dead code and the backup format's HOTP prerequisite (entry[1]) was never built (`BackupFormat.kt:17,38`; `Vault.kt:236`) | L | FIX (minimum) / REDESIGN (full) | Minimum shippable: passphrase-encrypted export via SAF `CreateDocument` using `BackupFormat.encode` with the TOTP-gate field made optional (drop the never-created HOTP secret from v1 payload), plus matching restore on the Setup screen. Alternatively add plain Bitwarden-CSV export behind biometric + explicit warning — that alone kills the roach-motel problem and serves the complement doctrine. |
| 3 | **Generated-and-filled passwords are unrecorded** (autofill-generate A7, IME-generate A11) — account-lockout trap | M | REDESIGN | Mechanism: on `GenerateAndFillActivity` fill and IME `commitText`, write a receipt entry `{source: autofill/ime, appPackage/webDomain, timestamp, password}` into the vault (vault already opens without user friction in the autofill path? No — generate path skips biometric; so: write receipts to a separate device-encrypted receipts store NOT requiring the vault KEK, e.g. a second Keystore-wrapped file, surfaced in VaultActivity as "Unclaimed generated passwords"). This is also the core of the E repositioning. |
| 4 | **ACTION_VIEW auto-import without confirmation** contradicts the manifest's own security contract (`AndroidManifest.xml:196-199` vs `VaultActivity.kt:754-759`) | M | FIX | Show parsed summary ("Import 47 entries from passwords.csv?") + explicit confirm button before merging; keeps the documented invariant true. |
| 5 | **No Bitwarden CSV/JSON import/export** (A24) — the incumbent's formats are the coexistence lingua franca | M | FIX | Add Bitwarden CSV (`folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp`) and Bitwarden JSON (`items[].type==1`) to `ImportFormats.detect/parse`; add export in the same formats (gap 2). |
| 6 | **Samsung dual-slot flow unverified** (A39): undocumented settings action, `supportsDualAutofillSlots()` never consulted, generic-Settings fallback strands user | M | FIX (verify-first) | Verify on SM-S948U One UI; if the Additional-service slot or intent doesn't exist, degrade the copy honestly and route to IME mode; wire the existing `DeviceProfile.supportsDualAutofillSlots()` gate. |
| 7 | **Misleading copy cluster**: nonexistent reset (A19), nonexistent 10s reveal (A18), Credential Manager self-contradiction (A38), unconditional auto-clear toast (A4) | S | FIX | Pure text/honesty edits; A38 = delete the first paragraph. |
| 8 | **Delete entry: no confirmation** (`VaultActivity.kt:988-990`) | S | FIX | AlertDialog or long-press-to-arm; also visually differentiate from "Back". |
| 9 | **Master-KEK entry exposed as ordinary credential** in vault list and autofill picker (`Vault.kt:222-236`; picker filters nothing) | S | FIX | Hide from `FillSavedEntryActivity` picker; render with distinct badge + explanation in ListScreen. |
| 10 | **GUI baseline for "shippable"**: strings to resources, M3 color roles, Switch/Slider semantics, empty state for vault list, progress indicators, declare dark-only | M | FIX | Mechanical but broad; touches every screen. |
| 11 | **Import parsing on main thread** (`VaultActivity.kt:691-747`) | S | FIX | Move to `Dispatchers.IO` + CircularProgressIndicator; cap input size before `readText()`. |
| 12 | **Vault not locked after autofill pick** — `FillSavedEntryActivity.returnDataset` finishes without `vault.lock()` (only the cancel path locks, :171-174) | S | FIX | Call `s.vault.lock()` in `returnDataset`'s `finally`. |
| 13 | **IME cannot deliver saved entries** (A13) — coexistence-mode users have no fill path for stored credentials | M | REDESIGN | Add a biometric-gated "Type saved entry" button to the IME that opens the existing picker UI (reuse FillSavedEntryActivity picker logic) and commits via `commitText`. This makes the IME the complete Bitwarden-coexistent delivery channel. |
| 14 | **Dead code shed**: v1 reveal-lock constants (`Vault.kt:93,110-112`), `Crypto.generateMasterPassword` (unused), inline-suggestions decision (A9) revisit | S | DROP-TO-V2 | Delete or annotate; keeps the auditable-surface promise honest. |

Totals: **L=2, M=5, S=7** (rank 13 counted M; ranks 7,8,9,11,12,14 + A4-copy = S).

---

## E. COMPLEMENT POSITIONING

passgen should stop being a fourth-rate password *manager* and become **the
hardened password *generator and receipt ledger* that works alongside
Bitwarden**: it generates high-entropy passwords through three delivery paths
Bitwarden doesn't own (a coexistent IME that types directly into any field, a
Samsung additional-slot autofill "generate" chip, and a screen-free clipboard
fallback with honest Samsung caveats), it **records a receipt of every
password it ever generated-and-delivered** (app/domain + timestamp + value,
device-encrypted) so a signup done via passgen can never lock the user out,
and it **hands credentials off to the incumbent** via Bitwarden-format
export the moment the user wants them managed long-term. The vault survives,
but demoted and renamed in the UI to what the code already almost is: a
generated-password ledger and migration buffer (import from Google/Proton →
review → export to Bitwarden), not a place to live. Under that positioning
the vault's missing manager features (edit, search, sync, reveal) stop being
gaps at all; the only mandatory vault work left is the recovery/reset/export
cliff (D1/D2), which is non-negotiable for anything that stores even one
byte of a user's credentials.
