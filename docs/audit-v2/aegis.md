# Audit v2 — aegis (com.understory.aegis)

Scope: `understory-aegis/aegis/` + vendored `common-security`, `common-backup`
(verified byte-identical to canonical `understory-common` via recursive diff, 2026-07-03).
Read-only audit against the NEW SUITE DOCTRINE (complement-don't-replace, viability
honesty, shippable = polished + zero dead UI). Every source file of the module was
read end to end. Complement target: **Aegis Authenticator** (the real app,
`com.beemdevelopment.aegis`).

Headline: the crypto/vault core and the 30s-SHA1-TOTP happy path are genuinely solid,
but **the OTP generator ignores the per-entry algorithm/digits/period it faithfully
imports** (silently wrong codes for anything non-default), **the IME mode is unviable
under the shipped release posture** (lock-on-leave guarantees the IME never sees an
unlocked vault), **there is no export of any kind** (one-way secret sink), and **the
app cannot import the real Aegis Authenticator's own export format despite carrying
its name**. Name collision with the real Aegis Authenticator is a ship-blocker
candidate in its own right.

---

## A. FEATURE LEDGER

### WORKING (complete traced path)

| # | Feature | Evidence (file:line) | Trace notes |
|---|---------|----------------------|-------------|
| A1 | Vault creation, BiometricPrompt-gated (BIOMETRIC_STRONG \| DEVICE_CREDENTIAL), 256-bit KEK wrapped by auth-bound Keystore key | `MainActivity.kt:349-420` (SetupScreen) → `Crypto.kt:118-170` (`deviceAuthCipherForEncrypt`, `ensureDeviceAuthKey` with `setUserAuthenticationParameters(0, …)`) → `AegisVault.kt:70-113` (`createV2`) | Cipher wrapped in `BiometricPrompt.CryptoObject` (`MainActivity.kt:506-515`); doFinal only succeeds post-auth. Lifecycle gate at `MainActivity.kt:405-409` refuses to surface a vault unlocked while backgrounded. |
| A2 | Vault unlock (daily) | `MainActivity.kt:422-482` → `AegisVault.kt:119-141` (`unlockV2`) | IV read from file header (`ivForUnlock`, `AegisVault.kt:58`); same lifecycle gate `MainActivity.kt:454-459`. |
| A3 | Vault file format: atomic replace, length-capped reads, trailing-byte rejection, tmp sweep | `AegisVault.kt:150-259` | `MAX_IV_LEN/MAX_WRAPPED_KEK_LEN/MAX_CONTENT_LEN` caps at `:202-204`; `require(input.read() == -1)` at `:197`; `atomicReplace` `:206-221`; `sweepTmp` `:48-50`. |
| A4 | Lock on onStop / onDestroy / onUserLeaveHint with transient-flight suppression for SAF/biometric round-trips | `MainActivity.kt:184-239`, `AegisVaultManager.kt:53-72` | `TestingMode.KEEP_ALIVE_ON_LEAVE = false` (`TestingMode.kt:56`) so release posture is active. Flight begin/end wrapped around both pickers (`MainActivity.kt:1000-1006, 1016-1028`; ended in callbacks `:850, :964`). |
| A5 | TOTP list with 1 s ticker + per-entry countdown ring (color-shift green→amber→red) | `MainActivity.kt:528-534` (ticker), `:686-750` (EntryRow), `:761-796` (CountdownRing) | **Correct only for SHA1/6-digit/30 s entries** — see A-U1. Ring sweep uses `entry.period` (`:706, :769`) while the code rotates on the hardcoded 30 s grid. |
| A6 | Codes never rendered in main UI; tap-to-copy with `EXTRA_IS_SENSITIVE` + auto-clear | `MainActivity.kt:737-741` (`redactedCode`), `:820-827` → `Clipboard.kt:44-114` (sensitive flag `:77-79`, label+token-checked clear `:101-114`) | Auto-clear is hardcoded 30 s (`MainActivity.kt:824`) — see D-S1 for the period-mismatch toast. |
| A7 | Delete entry: long-press → AlertDialog with per-dialog-window `filterTouchesWhenObscured` + focus re-check on confirm | `MainActivity.kt:606, 627-681` (`dialogView.filterTouchesWhenObscured = true` at `:634`; `hasWindowFocus()` refusal at `:658`) | Save + revision bump traced `:662-667`. |
| A8 | Manual add: paste base32 secret or full `otpauth://` URI; form fields override URI label | `MainActivity.kt:1058-1099` → `OtpAuthUri.kt:63-123` | Parser handles label decode, issuer-param precedence (`OtpAuthUri.kt:101-102`), SHA256/512, digits/period/counter. Empty-secret rejected `:1066-1069`. |
| A9 | Gallery-only QR import (SAF `GetContent`, no CAMERA permission), downsample-capped bitmap decode, ZXing in-process | `MainActivity.kt:845-903, 996-1011`; `QrDecoder.kt:39-93` (two-pass bounds decode `:56-72`, `MAX_EDGE_PX = 2048` `:81`); CAMERA stripped `AndroidManifest.xml:80` | The manifest's "gallery-only" claim is honest — no camera code path exists anywhere in the module. |
| A10 | Google Authenticator `otpauth-migration://` QR import (hand-rolled protobuf reader, batch X/N surfacing) | `MainActivity.kt:865-892`; `GoogleAuthMigration.kt:66-219` (overflow-safe length check `:195`, alloc-free skip `:201-218`) | Works for the TOTP/SHA1 payloads Google actually emits. Caveats: no dedup on this path (D-M5) and downstream generation bugs (A-U1/A-U2). |
| A11 | File import: migration-URI text files, Proton Authenticator JSON (real observed schema), generic flat OTP JSON; dedup by (issuer, account, secretB64); per-entry error accounting | `MainActivity.kt:914-980`; `FileImports.kt:73-240`; tests `FileImportsTest.kt:23-299` incl. real-world `+`-in-secret case `:159-177` | Proton path delegates to canonical URI parser (`FileImports.kt:188-203`). Dedup traced `MainActivity.kt:927-949`. |
| A12 | "Open with… aegis" (ACTION_VIEW json/text/csv) → unlock-gated auto-import | `AndroidManifest.xml:205-212`; `MainActivity.kt:159-160, 275-324`; `MainActivity.kt:975-980` (LaunchedEffect single-shot) | Cold-start path complete. Warm-task path broken — see UNFINISHED A-F2. |
| A13 | Tamper + suite-attestation hard gates (onCreate, onResume, IME onCreate/onStartInput), debugger refusal | `MainActivity.kt:124-131, 241-249`; `AegisInputMethodService.kt:53-66, 85-101`; `Tamper.kt:81-98`; `SuiteAttestation.kt:66-106`; pins `SuitePins.kt:24-35` | 5 s cache + `invalidate()` on resume/input-start traced. |
| A14 | Screen-capture hardening: FLAG_SECURE (TestingMode gate now false), `setHideOverlayWindows`, recents-screenshot off, `excludeFromRecents` | `MainActivity.kt:136-151`; `TestingMode.kt:34`; `AndroidManifest.xml:188`; IME window FLAG_SECURE `AegisInputMethodService.kt:71-75` | Matches RELEASE_BLOCKERS.md resolved items (`RELEASE_BLOCKERS.md:72-91`). |
| A15 | Tap-jacking defense on all mutating controls (SecureButton/SecureOutlinedButton partial-obscure + focus checks; IME ObscuredTouchGate) | `SecureButton.kt:35-144`; `AegisInputMethodService.kt:319-331`; decor-filter lift rationale `MainActivity.kt:176-181` | |
| A16 | Third-party accessibility-service warning banner + settings deep link | `MainActivity.kt:540-579`; `A11yProbe.kt:21-51` | Honest: warns, doesn't block. |
| A17 | Diagnostics ring + screen (copy/clear/back), eng-only file dump, footer triple-tap MARK | `MainActivity.kt:326-329, 611-613`; `Diagnostics.kt:70-139`; `DiagnosticsScreen.kt:52-140`; `DiagnosticsDump` (eng-gated, `MainActivity.kt:100`) | |
| A18 | Suite capability beacon (read-only, signature-gated provider) + SuiteStatusFooter tier/peer display | `SuiteCapsProvider.kt:10-12`; `AndroidManifest.xml:239-245`; `BaseCapabilityProvider.kt:69-108`; `SuiteCapabilityRegistry.kt:152-197`; footer `MainActivity.kt:614` | Version→capability mapping is consumer-local (anti-spoof) — traced. |
| A19 | Comms-permission strip (INTERNET/SMS/BT/NFC/location/audio/camera all `tools:node="remove"`), cleartext-off NSC, backup/data-extraction fully excluded | `AndroidManifest.xml:34-123, 168-172`; `network_security_config.xml`; `data_extraction_rules.xml` | Defense-in-depth is real, not cosmetic. |
| A20 | Device-precondition gate (no screen lock / no biometric → explanatory blocker, no dead flow) | `MainActivity.kt:333-347, 365-371` | |

### UNFINISHED (dead UI, stubbed, unreachable)

| # | Feature | Class | Evidence |
|---|---------|-------|----------|
| A-F1 | **Suite backup export/import adapter** — `AegisBackupAdapter` implements `common-backup` `BackupAdapter` fully, but nothing in the app (or any reachable component) references it: no UI, no registration, no orchestrator IPC. Dead code; the vault has **no export path at all**. | UNFINISHED (unreachable) | `AegisBackupAdapter.kt:36-107`; grep across module: zero references outside its own file. `BackupAdapter.kt:22-51` interface contract unfulfilled by any caller. |
| A-F2 | **Open-with while app task alive** — `pendingImportUri` is read only in `onCreate` (`intent?.action == ACTION_VIEW`); `onNewIntent` is never overridden, and the activity is `launchMode="singleTask"`. Picking "Open with aegis" while the task exists silently drops the file. | UNFINISHED | `MainActivity.kt:159-160`; `AndroidManifest.xml:187`; no `onNewIntent` anywhere in `MainActivity.kt`. |
| A-F3 | **IME discoverability** — the IME is declared and functional as a service, but the app never surfaces "enable the aegis keyboard" (no `ACTION_INPUT_METHOD_SETTINGS` intent, no InputMethodManager call, no mention in any screen). A user will never find it. | UNFINISHED | `AndroidManifest.xml:221-232`; `method.xml`; grep: no `InputMethodManager`/`ACTION_INPUT_METHOD` in module. |
| A-F4 | **HOTP counter lifecycle** — `AegisEntry.counter` exists, is imported and persisted, but no code path ever increments it or saves after generation. | UNFINISHED (feeds A-U2) | `AegisEntry.kt:27`; comment admission `MainActivity.kt:692-694`; `GoogleAuthMigration.kt:41` ("We ignore counter for HOTP"). |

### UNVIABLE-AS-DESIGNED

| # | Feature | Why, precisely |
|---|---------|----------------|
| A-U1 | **Non-default TOTP parameters (SHA256, SHA512, 8 digits, period ≠ 30 s)** | The importers faithfully capture `algorithm`, `digits`, `period` (`OtpAuthUri.kt:104-111`, `FileImports.kt:215-226`, `GoogleAuthMigration.kt:126-137`) and `AegisEntry` stores them (`AegisEntry.kt:24-28`) — but the only generator, `Totp.currentCode`, hardcodes HmacSHA1 / 6 digits / 30 s (`Totp.kt:26-28, 35-41`) and takes no parameters for them. Both call sites pass nothing (`MainActivity.kt:704`, `AegisInputMethodService.kt:210`). Result: an entry imported from e.g. a SHA256/8-digit issuer displays a plausible-looking countdown **and silently produces codes the issuer will always reject**. Worse than a crash: the user discovers it at login time. Not rootless-blocked, not doctrine-blocked — a pure generator gap, but as *designed today* the feature is unviable. |
| A-U2 | **HOTP entries** | Typed `HOTP` entries are rendered through the same time-based path (comment admits it: `MainActivity.kt:692-694`); counter never increments (A-F4). Every HOTP code shown is wrong by construction. Import accepts HOTP (`FileImports.kt:215-217`, `GoogleAuthMigration.kt:138-142`) so the broken state is reachable from real user data. |
| A-U3 | **IME code-typing mode under release lifecycle posture** | The IME's own contract is "Open aegis once to authenticate, then switch back here" (`AegisInputMethodService.kt:122`, KDoc `:23-31`). But with `KEEP_ALIVE_ON_LEAVE = false` (`TestingMode.kt:56`), the moment the user leaves aegis for the app where they need the code, `onUserLeaveHint` locks the vault and `finishAndRemoveTask()`s (`MainActivity.kt:184-203`) and `onStop` locks as backstop (`:215-232`). `AegisVaultManager.current` is therefore null whenever any other app has input focus — i.e. **whenever the IME could possibly be used**. The locked-view's instruction sends the user in a circle. The feature only ever worked under the testing flag. Needs a redesign (see D-L2), not a bugfix. |
| A-U4 | **"Snake-eats-tail" master-KEK recovery entry** | `createV2` seals the vault master KEK as entry[0] "aegis / vault master key" for "paper transcription / disaster recovery (biometric-gated reveal, mirroring passgen)" (`AegisVault.kt:26-29, 85-96`). But aegis has **no reveal path of any kind** — the list renders only redacted bullets (`MainActivity.kt:737-741`), tap copies a *TOTP code computed from the KEK* (meaningless), and the doctrine comment at `MainActivity.kt:708-712` says reveal will never exist ("there is no reveal mode"). The recovery artifact is un-transcribable by design, while being fully deletable via long-press (A7) and listed in the IME. As designed it delivers zero recovery value and nonzero confusion/hazard. |
| A-U5 | **Round-trip compatibility with the real Aegis Authenticator** | Direction 1 (their → ours): a real Aegis unencrypted export is `{"version":1,"header":{…},"db":{"entries":[{"type":"totp","name":…,"issuer":…,"info":{"secret":…,"algo":…,"digits":…,"period":…}}]}}` — the secret lives in the nested `info` object. `FileImports.detect` will classify it as GENERIC_OTP_JSON (root `{…}` + `"entries"` substring, `FileImports.kt:75-91`), then fail per entry: root has no `entries` array at top level? It does — but `db` nesting means `obj.optJSONArray("entries")` returns null → throws "JSON does not contain `entries` or `items` array" (`FileImports.kt:156-160`); even hand-unwrapping `db` would fail every entry at `require(secretStr.isNotEmpty()) { "missing content.uri or secret" }` (`FileImports.kt:209`) because secret is under `info.secret`. Encrypted Aegis vaults (scrypt+AES-GCM slots) are entirely out of scope of the parser. Direction 2 (ours → theirs): no exporter exists (A-F1). **Neither direction works.** No UI string claims Aegis compatibility (the import button says "otpauth-migration / Proton", `MainActivity.kt:1032`), so this is a capability gap rather than a lie — but for an app *named aegis*, users will assume it. |

### MISLEADING (UI claims more than the code does)

| # | Claim | Reality | Evidence |
|---|-------|---------|----------|
| A-M1 | Setup text: master stored "for paper transcription / disaster recovery… Phase 2 adds an encrypted backup file with HOTP-gated recovery — that's the path to restoring on a new device." | No transcription path exists (A-U4); no backup file exists (A-F1); "Phase 2" is presented in onboarding as if the recovery entry has value today. | `MainActivity.kt:375-383`; `AegisVault.kt:85-96` |
| A-M2 | Copy toast: "Code copied (${entry.period}s clipboard window)" | Auto-clear is hardcoded 30 s regardless of entry period (`copyCodeToClipboard`), so a 60 s-period entry advertises a 60 s window and gets 30 s. Wrong direction is the safe one, but the string is still false. | `MainActivity.kt:602-604, 820-827` |
| A-M3 | Countdown ring + seconds-left for non-30 s / non-SHA1 / HOTP entries implies the shown timing produces valid codes | Generator ignores those parameters entirely (A-U1/A-U2) — the ring is UI fiction for those entries. | `MainActivity.kt:703-706, 761-796`; `Totp.kt:35-41` |
| A-M4 | Add screen: "Paste a base32 secret (Google Authenticator-style)" + import affordances imply imported entries will work | True only for default-parameter entries; no warning is shown when an imported entry carries SHA256/SHA512/8-digits/HOTP. | `MainActivity.kt:987-994`; parsers as cited in A-U1 |
| A-M5 | IME locked view: "Open aegis once to authenticate, then switch back here." | Following the instruction can never succeed in release posture (A-U3). | `AegisInputMethodService.kt:122`; `MainActivity.kt:184-203` |

---

## B. EXCLUSIVE-SLOT & COEXISTENCE

**Scarce-slot touchpoints (audited against the whole manifest + code):**

| Android scarce resource | aegis usage | Verdict |
|---|---|---|
| VPN slot (VpnService) | none | Clean — doctrine-compliant (Tailscale keeps the slot). |
| Autofill service | none (IME sets `importantForAutofill = NO_EXCLUDE_DESCENDANTS`, `AegisInputMethodService.kt:112, 176`) | Clean. Bitwarden/1Password keep the slot. |
| **IME (keyboard list)** | `AegisInputMethodService`, `BIND_INPUT_METHOD`, `isDefault="false"` (`AndroidManifest.xml:221-232`, `method.xml:4`) | **Additive, not exclusive** — Android allows multiple enabled IMEs; user's Gboard/Samsung Keyboard remains default; aegis is switched to momentarily and switches itself back (`switchToPreviousInputMethod`, `AegisInputMethodService.kt:153, 231, 273`). This is the correct coexistence shape. Gap: zero enablement UX (A-F3), and the whole mode is currently unviable (A-U3). |
| Default-app roles (browser/SMS/assistant) | none | Clean. |
| Accessibility service | none bound; app *warns about* third-party ones (A16) | Clean; complement-positive. |
| Notification listener / device admin / usage stats / camera / QUERY_ALL_PACKAGES | all absent or explicitly stripped (`AndroidManifest.xml:100-115`) | Clean. `<queries>` is narrow (suite siblings + known patcher packages, `:132-161`). |

**Incumbents a real user has for this purpose:** Aegis Authenticator (operator-relevant; also the *name* we collide with), Google Authenticator, 2FAS, Proton Authenticator, Bitwarden/1Password integrated TOTP, Samsung Pass. TOTP is naturally multi-homed — the same seed can live in N apps simultaneously — so this category is the easiest in the suite to make doctrine-compliant.

**Conflicts:**
1. **Name collision — ship-blocker candidate.** `app_name` = "aegis" (`strings.xml:3`), launcher label identical in casing style to Aegis Authenticator's short name; CREDITS.md already admits "Aegis Authenticator name+UX conventions" as inspiration (`RELEASE_BLOCKERS.md:206-212`). Installing both on one phone yields two authenticator icons both effectively called Aegis. Store listing under this name invites trademark/user-confusion trouble and *directly contradicts* complement positioning (you cannot claim to sit beside an incumbent while wearing its name). Decision needed: keep `aegis` as suite-internal codename, ship under a distinct store name (e.g. "Understory OTP"). Package id `com.understory.aegis` can stay (it's not user-facing), but label/branding must change.
2. **One-way import is soft displacement.** Today aegis can ingest seeds from Google Auth and Proton but can never give anything back (A-F1, A-U5) — combined with the "Lost device = lost vault" model (`MainActivity.kt:380-382`), a user who *moves* (rather than copies) tokens in has been displaced-by-ratchet. Coexistence honesty requires either export or an explicit "keep your existing authenticator enrolled — aegis is a second holder, not a replacement" statement in the add/import UI.
3. Clipboard: 30 s sensitive-flag auto-clear (A6) plays fine with clipboard managers; no conflict.

**Complement opportunities (ranked by leverage):**
- **Import real Aegis JSON (plain + encrypted)** — one parser (`db.entries[].info` unwrap; scrypt/AES-GCM slot decrypt for encrypted vaults) makes aegis a genuine sidecar to the operator's actual authenticator. (D-M2)
- **Export as `otpauth://` URI list / QR render** — lets users enroll the *incumbent* from us, completing the two-way street; QR render is display-only, needs no permissions. Conflicts with the render-nothing doctrine, so gate it behind biometric + explicit action, or export file-only. (D-M1)
- **2FAS JSON import** — same flat-JSON family, near-free given `FileImports` structure.
- Suite-internal: `OTP_VAULT` capability is already beaconed (`SuiteCapabilityRegistry.kt:60-62`) and SUITE_DESIGN's aegis+passgen/firewall step-up combos have their discovery layer working (A18) — the *service* side (issue-code-on-request IPC) does not exist yet; fine for v1 as long as no UI claims it.
- IME as complement: momentary-switch IME is the least-invasive code-entry mechanism on Android (no a11y service, no draw-over). Once A-U3 is redesigned it is a *differentiator* vs. incumbents (Aegis Authenticator has no IME).

---

## C. GUI AUDIT (screen by screen)

Global: dark-only, hand-rolled palette (`Color(0xFF0A0A0A)`/`0xFF1C1C1C`/`0xFF9E9E9E` etc. throughout `MainActivity.kt`); `MaterialTheme(colorScheme = darkColorScheme())` at `MainActivity.kt:163` but **no `lightColorScheme` branch, no dynamic color, and most colors bypass the theme entirely** (hardcoded per-Text). XML theme is `android:Theme.Material.NoActionBar` (`themes.xml:3`) — platform Material, not Material3/DayNight; fine as a Compose host but signals no design-system pass. **Strings: only 2 resources exist (`strings.xml:3-4`); every user-facing sentence is hardcoded Kotlin** — blocks localization and consistency review. `resourceConfigurations += listOf("en")` (`build.gradle.kts:17`) makes English-only explicit. No `contentDescription`/semantics anywhere in the module (grep: zero `contentDescription` outside imports). Portrait-locked (`AndroidManifest.xml:190`).

| Screen | State handling | Findings |
|---|---|---|
| **Setup** (`MainActivity.kt:349-420`) | Device-unsupported blocker state: yes (A20). Error text: yes (`:392`). Loading: implicit (biometric prompt covers it). | Two-step flow coherent. Copy is jargon-heavy ("self-generates… self-encrypts… self-binds", `:375`) and includes the misleading recovery paragraph (A-M1). Buttons full-width — touch targets fine. Not M3-idiomatic (no Scaffold/TopAppBar) but consistent with suite look. |
| **Unlock** (`:422-482`) | Working/`working` busy flag with label swap (`:478`); error text; cancel path resets state. | Good. No biometric retry-count surfacing; fine for v1. **No recovery path whatsoever when decryption permanently fails** — `setInvalidatedByBiometricEnrollment(true)` (`Crypto.kt:165`) means adding a fingerprint bricks the vault into an eternal "Vault decryption failed." (`MainActivity.kt:461`) with no reset/delete offer; `AegisVault.delete` (`AegisVault.kt:52-55`) is never called from any UI (grep: zero call sites). This is a GUI dead-end wrapping a data-loss event — worst combination. See D-L4. |
| **List** (`:518-682`) | Empty state: yes (`:584-588`). Ticker-driven updates: yes. A11y banner: yes (A16). | "${n} entries" says "1 entries" (`:556`); after fresh setup n=1 because the *master-KEK artifact* is counted and rendered as a normal row (A-U4) — confusing first-run experience ("I added nothing, why is there an entry called vault master key, and why does it show a countdown?"). Rows: `combinedClickable` with no semantics — TalkBack reads bullet characters ("●●● ●●●") and has no long-press action hint; delete is effectively undiscoverable non-visually. Countdown ring text 11 sp inside 34 dp ring — small but supplementary. Diagnostics uses plain `OutlinedButton` (`:611`) while all other actions use Secure* variants — inconsistent (harmless, but pattern-breaking). Footer (A18) renders per spec. |
| **Add** (`:829-1111`) | QR/file feedback line: yes (`qrFeedback`, color-coded `:1034-1040`). Error text: yes. **No loading state** — and none is cosmetic: QR bitmap decode + ZXing run synchronously in the result callback on the main thread (`:855`, `QrDecoder.kt:39-93`), and `runFileImport` does SAF read + parse + N× vault re-encrypt + disk write on the main thread (`:914-957`). Big gallery image or large import = frozen UI / ANR risk. | Secret field shows pasted/QR-decoded material **in plaintext** — after QR decode the full `otpauth://` URI including the seed sits visible in the text field (`:895-896, 1051-1055`). FLAG_SECURE stops capture but not shoulder-surfing/a11y — inconsistent with the app's own "screen is never secure" doctrine that justifies redacting mere 30 s codes three screens away. No `PasswordVisualTransformation` or equivalent. Import button label "Import from file (otpauth-migration / Proton)" is honest about formats (good). Google-migration bulk add has **no dedup** unlike file import (`:874-881` vs `:927-949`) — rescanning a batch duplicates every entry, and with `key = { it.id }` they all render as apparent clones. |
| **Diagnostics** (shared, `DiagnosticsScreen.kt:52-140`) | Empty state yes (`:96`); 1 s self-refresh; copy/clear/back. | Copies via raw ClipboardManager without sensitive flag — acceptable (events are shape-only by contract, `Diagnostics.kt:24-28`). Reversed list keyed on `elapsedMs-tag-hash` — dupe-key collision possible for identical messages in same ms; cosmetic. |
| **Delete dialog** (`:627-681`) | Explicit destructive-consequence copy; cancel; tap-jack hardened (A7). | Best dialog in the app. Delete button red on TextButton — fine. |
| **IME — locked view** (`AegisInputMethodService.kt:103-160`) | Message + two actions; deliberate a11y/autofill/content-capture opt-outs (`:111-114`). | Instruction is a lie in release posture (A-M5). `importantForAccessibility = NO` means TalkBack users cannot use the IME at all — defensible as anti-scrape posture but undocumented as an accessibility limitation. |
| **IME — entry list** (`:162-284`) | Empty state yes (`:199-204`); refusal + crash views exist (`:286-308`). | **Codes and countdown are frozen at view-build time** — no ticker; `code` is captured in the click closure, so a tap after the period boundary commits an expired code (`:209-221, 262-273`). Unlike the main app, codes are rendered in cleartext on buttons (`:256`) — doctrine inconsistency (window is FLAG_SECURE, but shoulder-visible). Fixed 220 dp list height (`:189-191`); fine. Colors hardcoded Holo-indigo `#3F51B5` — off-palette vs. the app's grey/black scheme. |

Dark theme: consistent (black backgrounds everywhere) — but *only* dark; `uiMode` config change is even declared handled (`AndroidManifest.xml:191`) with no light branch to switch to.

---

## D. SHIP-GAP LIST (ranked)

**L (large)**

1. **D-L1 · FIX · Parameter-correct OTP generation.** Extend `Totp` to accept algorithm/digits/period (`Totp.kt:35-41` → add overload taking `AegisEntry`/`OtpAuthEntry` params; HmacSHA256/512 via `Mac.getInstance`), thread through both call sites (`MainActivity.kt:704`, `AegisInputMethodService.kt:210`) and use `entry.period` for the rotation key (`MainActivity.kt:703`). Without this, every non-default entry is a silent authentication failure at the issuer. Test vectors: RFC 6238 Appendix B covers SHA1/256/512.
2. **D-L2 · REDESIGN · IME unlock hand-off (A-U3).** Mechanism sketch: a transparent, `excludeFromRecents`, not-exported `FragmentActivity` ("AuthTrampolineActivity") that the IME launches; it hosts BiometricPrompt over the target app, unlocks into `AegisVaultManager` with a short TTL (60–120 s) session token, finishes immediately; `onUserLeaveHint`/`onStop` locking in MainActivity stays untouched because the trampoline is a different activity with its own lifecycle. IME re-queries on `onStartInputView`. Alternative (smaller): time-boxed grace — `AegisVaultManager` keeps the vault for N seconds after MainActivity stops, IME-only read. Either way, update the locked-view copy. Until one lands, the IME is a dead feature wearing instructions.
3. **D-L3 · FIX-or-DROP-TO-V2 · HOTP (A-U2/A-F4).** Fix = tap-to-generate for HOTP rows (no countdown ring; "generate next code" button; counter++ then `vault.save()` before revealing/copying; IME same). Drop = reject `type=HOTP` at all import/add boundaries with an honest "HOTP not supported yet" per-entry error (the error plumbing in `FileImports.ImportSummary` already carries it). Shipping the current TOTP-masquerade is the one option that must not survive.
4. **D-L4 · FIX · Vault-reset escape hatch.** Unlock screen needs a guarded "Reset vault (erases all entries)" path invoking `AegisVault.delete` (`AegisVault.kt:52-55`, currently zero call sites) after typed confirmation — required because biometric re-enrollment permanently invalidates the wrap key (`Crypto.kt:165`) and today's UI dead-ends forever (`MainActivity.kt:461`). Detect `KeyPermanentlyInvalidatedException` specifically and say what happened.
5. **D-L5 · DECISION (ship blocker) · Rename the store-facing app.** "aegis" collides with the incumbent complement target (Section B conflict 1; `strings.xml:3`). Suite-internal codename may stay; launcher label, README first line, and any store listing must not say Aegis. One-line code change + docs sweep; the *decision* is the blocker.

**M (medium)**

6. **D-M1 · FIX · Any export path.** Minimum honest v1: biometric-gated "Export entries" producing an `otpauth://`-URI-per-line text file via SAF `CREATE_DOCUMENT` (format already round-trips through our own importer, `OtpAuthEntry.toUri` exists at `OtpAuthUri.kt:38-53` and is currently dead code). Wire `AegisBackupAdapter` (A-F1) into the backups app or delete it from the tree. Kills the one-way-ratchet displacement pattern (Section B conflict 2).
7. **D-M2 · FIX · Import real Aegis Authenticator JSON (A-U5).** Unwrap `db.entries[]`, map `info.{secret,algo,digits,period}`, accept `type` totp/hotp/steam→reject-steam-honestly. Plaintext exports first; encrypted vaults (scrypt slots) optional v1.5. Belongs in `FileImports.kt` beside the Proton branch; add fixture-based tests like `FileImportsTest.kt:86-127`.
8. **D-M3 · FIX · IME stale codes.** Recompute the code at click time (secret re-decode inside the click handler) instead of committing the build-time capture (`AegisInputMethodService.kt:209-272`); optionally a 1 s `Handler` tick to refresh labels while visible.
9. **D-M4 · FIX · Master-KEK entry containment (A-U4).** Stop rendering it as a normal row: filter `entries[0]`-by-marker out of List/IME, exclude from count, block deletion, and either build the promised reveal (conflicts with render-nothing doctrine — needs an explicit decision) or stop storing it and delete the onboarding paragraph (A-M1). Smallest honest change: hide row + rewrite paragraph to "recovery arrives with the backups app."
10. **D-M5 · FIX · Dedup the QR-migration bulk add** (`MainActivity.kt:874-881`) with the same (issuer, account, secretB64) key used at `MainActivity.kt:927-949`.
11. **D-M6 · FIX · Move QR decode + file import off the main thread** (`MainActivity.kt:855, 914-957`; `QrDecoder.kt`) — coroutine + `Dispatchers.Default/IO`, with a real loading state in the Add screen (C-audit).

**S (small)**

12. **D-S1 · FIX · Clipboard toast honesty** — either clear at `entry.period` seconds or say "30s" (`MainActivity.kt:602-604` vs `:824`).
13. **D-S2 · FIX · Handle `onNewIntent`** for warm-task open-with (A-F2).
14. **D-S3 · FIX · Mask the secret field** in Add (visual transformation + optional reveal toggle) so the seed isn't plaintext-on-screen (`MainActivity.kt:1051-1055`).
15. **D-S4 · FIX · IME enablement UX** — "Enable the aegis keyboard" row (launch `ACTION_INPUT_METHOD_SETTINGS`) + one-line explanation, shown until enabled (A-F3). Only meaningful after D-L2.
16. **D-S5 · POLISH · String extraction + a11y pass** — move hardcoded UI strings to resources, fix "1 entries", add semantics/contentDescription for entry rows (issuer/account + "double-tap to copy code, long-press to delete"), document the IME a11y opt-out.
17. **D-S6 · FIX (verify first) · Raw `+` in `otpauth-migration://` data param** — `Uri.getQueryParameter` plus-decodes to space and `android.util.Base64` rejects inner spaces; affects offline-decoded URIs pasted into text files, not on-screen QRs (which percent-encode). Strip spaces before `Base64.decode` (`GoogleAuthMigration.kt:71-76`). One-line hardening; confirm with a fixture.

Totals: **L 5 · M 6 · S 6**.

---

## E. COMPLEMENT POSITIONING

aegis should be **the offline, suite-attested second home for your TOTP seeds that
works alongside Aegis Authenticator (or Google Authenticator/2FAS) rather than
replacing it**: the incumbent stays your daily scanner and enrollment tool, while
aegis holds a biometric-gated, zero-network, screenshot-proof copy of the same
seeds — imported losslessly from the incumbent's own export formats and exportable
back at any time — and adds the two things the incumbents don't do: codes that are
*never rendered on the main screen* (copy-with-auto-clear only), and — once the
unlock hand-off is redesigned — a momentary-switch keyboard that types the code
directly into the focused field without clipboard or accessibility-service
exposure. That story only becomes true (and honest) after the generator respects
per-entry parameters (D-L1), an export path exists (D-M1), real Aegis imports work
(D-M2), and the app stops sharing its complement's name (D-L5); until then it must
present itself as an alpha secondary vault and explicitly tell users to keep their
primary authenticator enrolled.
