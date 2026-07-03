# Design v2 — aegis (com.understory.aegis)

Store-facing name recommendation: **Understory OTP** (see §7). Package id stays
`com.understory.aegis`. Internal codename stays "aegis".

Scope: `understory-aegis/aegis/` plus the shared modules it vendors
(`common-security`, `common-backup`). This design resolves every finding in
`docs/audit-v2/aegis.md` and honors the cross-app decisions in
`docs/audit-v2/SUITE.md` (CD-1..CD-4 coexistence doctrine; shared recovery
contract; suite naming).

This is a DESIGN. No code is changed here. Every item below names exact files,
classes, APIs, and the disposition (FIX / REDESIGN / DROP) of each audited
feature. An implementer builds from this without re-deriving.

Two of the changes touch `common-security` (`Totp.kt`, and a new shared
`VaultRecovery` contract). Because `common-security` is vendored byte-identical
across passgen/aegis/backups, those changes are authored in canonical
`understory-common/common-security` and re-vendored; they are called out
explicitly as SHARED so the passgen/vault-folder/backups designs inherit the
same fix rather than re-cloning it (SUITE.md §3 divergence risk).

---

## 0. Disposition table (every audit finding)

| Audit id | Title | Disposition | Where resolved |
|---|---|---|---|
| A-F1 | Dead `AegisBackupAdapter` (no export path) | FIX (wire it) | §3 Export |
| A-F2 | Open-with drops file on warm task | FIX | §6 D-S2 |
| A-F3 | IME never discoverable | FIX | §2 IME (D-S4) |
| A-F4 | HOTP counter never increments/persists | FIX | §1 HOTP |
| A-U1 | Non-default TOTP params silently wrong | FIX | §1 Totp |
| A-U2 | HOTP rendered as fake TOTP | FIX | §1 HOTP |
| A-U3 | IME code-typing unviable under lock-on-leave | REDESIGN (auth-trampoline) | §2 IME |
| A-U4 | Master-KEK "recovery" entry un-transcribable, deletable | REDESIGN (remove entry[0]; recovery via §4) | §4 Recovery |
| A-U5 | No round-trip with real Aegis Authenticator | FIX (both directions) | §3 Import + Export |
| A-M1 | Setup copy promises non-existent recovery | FIX (rewrite copy) | §4, §5 |
| A-M2 | Clipboard toast lies about window | FIX | §6 D-S1 |
| A-M3 | Countdown ring implies valid codes for non-default | FIX (follows §1) | §1, §5 |
| A-M4 | Add copy implies imported entries work | FIX (follows §1; per-entry validation) | §1, §3 |
| A-M5 | IME locked-view instruction is a lie | REDESIGN (follows §2) | §2 |
| GUI (C) | strings, colors, semantics, main-thread IO, dark-only | FIX | §5 GUI |

Nothing is deferred as "DROP entirely." HOTP is FIXed (tap-to-advance), not
dropped — the importers already accept HOTP so a reject path would still need
honest UI; FIX is smaller net surface than a correct reject.

---

## 1. FATAL CORRECTNESS — parameter-correct OTP generation (A-U1, A-U2, A-F4)

### 1.1 Root cause (verified)

`common-security/.../Totp.kt` hardcodes `ALGORITHM = "HmacSHA1"`,
`DEFAULT_DIGITS = 6`, `DEFAULT_PERIOD_SECONDS = 30` (`Totp.kt:26-28`).
`currentCode(secret, nowSeconds)` (`Totp.kt:35-41`) derives the counter as
`nowSeconds / 30` and always calls `hotpCode(secret, counter, 6)`. Both aegis
call sites pass only `(secret, nowSeconds)`:
`MainActivity.kt:704` (`EntryRow`) and `AegisInputMethodService.kt:210` (IME).
`AegisEntry` faithfully stores `digits`, `period`, `counter`, `algorithm`
(`AegisEntry.kt:24-28`) and the importers populate them
(`OtpAuthUri.kt:104-111`, `FileImports.kt:215-226`,
`GoogleAuthMigration.kt:126-144`). Result: any SHA256/SHA512/8-digit/period≠30
entry displays a plausible countdown and generates codes the issuer rejects.

### 1.2 SHARED change — extend `Totp` (common-security)

`Totp.hotpCode` is already generic over `digits` (`Totp.kt:73`). The only
hardcoded axes are the HMAC algorithm string and the TOTP period. Change
`Totp.kt` as follows (author in canonical common-security; re-vendor):

```kotlin
object Totp {
    const val DEFAULT_PERIOD_SECONDS: Int = 30   // make public (used by UI)
    const val DEFAULT_DIGITS: Int = 6

    enum class Algo(val jca: String) {
        SHA1("HmacSHA1"), SHA256("HmacSHA256"), SHA512("HmacSHA512")
    }

    /** TOTP: time-based. */
    fun totpCode(
        secret: ByteArray,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
        period: Int = DEFAULT_PERIOD_SECONDS,
        digits: Int = DEFAULT_DIGITS,
        algo: Algo = Algo.SHA1,
    ): String {
        require(period > 0) { "period must be > 0" }
        val counter = nowSeconds / period
        return hotpCode(secret, counter, digits, algo)
    }

    /** HOTP: counter-based. Caller owns counter persistence. */
    fun hotpCodeAt(
        secret: ByteArray,
        counter: Long,
        digits: Int = DEFAULT_DIGITS,
        algo: Algo = Algo.SHA1,
    ): String = hotpCode(secret, counter, digits, algo)

    private fun hotpCode(secret: ByteArray, counter: Long, digits: Int, algo: Algo): String {
        // identical body to today, except:
        //   Mac.getInstance(algo.jca).apply { init(SecretKeySpec(secret, algo.jca)) }
        // and guard: require(digits in 6..10)
    }
}
```

- KEEP the existing `currentCode(secret, nowSeconds)` as a thin deprecated
  delegate to `totpCode(secret, nowSeconds)` so passgen's Stage-2C unlock-TOTP
  path (`Totp.kt` KDoc:10-24, used by passgen only) keeps compiling with SHA1/6/30
  defaults. Do NOT delete it — passgen's `verifyCode` also depends on the SHA1
  defaults; leave `verifyCode` untouched (passgen-only, always SHA1/6/30 by its
  own generated secret).
- Map `OtpAuthEntry.Algorithm` → `Totp.Algo` with a small `when` at the aegis
  call sites (they are different enums living in different modules; no import
  cycle).

RFC 6238 Appendix B test vectors (SHA1/256/512, 8-digit, T from 59s..
20000000000s) go into `common-security/src/test/.../TotpTest.kt` — the file
already exists and currently only covers SHA1/6/30.

### 1.3 aegis call-site changes (TOTP)

Add a single generation helper in aegis so both surfaces agree:

```kotlin
// new: AegisCode.kt (aegis module)
object AegisCode {
    fun totp(entry: AegisEntry, nowSeconds: Long): String {
        val secret = entry.secretBytes()
        try {
            return Totp.totpCode(secret, nowSeconds, entry.period, entry.digits, entry.algorithm.toTotpAlgo())
        } finally { Crypto.wipe(secret) }
    }
}
private fun OtpAuthEntry.Algorithm.toTotpAlgo() = when (this) {
    OtpAuthEntry.Algorithm.SHA1 -> Totp.Algo.SHA1
    OtpAuthEntry.Algorithm.SHA256 -> Totp.Algo.SHA256
    OtpAuthEntry.Algorithm.SHA512 -> Totp.Algo.SHA512
}
```

- `MainActivity.kt:695-705` (`EntryRow`): replace the inline
  `Totp.currentCode(secret, nowSeconds)` with `AegisCode.totp(entry, nowSeconds)`.
  Change the recompute key from `nowSeconds / entry.period` — it already uses
  `entry.period` (`:703`), so the ring/recompute cadence is correct once
  generation honors `period`. The `remember(entry.secretB64)` decode +
  `DisposableEffect` wipe (`:695-702`) can stay, or move fully into
  `AegisCode.totp` (preferred: one wipe path). Keep code-out-of-visual-tree
  (`redactedCode`, `:737-741`, `:803-804`).
- `AegisInputMethodService.kt:206-221`: replace `Totp.currentCode(secret, nowSeconds)`
  with `AegisCode.totp(entry, nowSeconds)`; the manual `secretBytes()` +
  `finally` wipe at `:208-219` is then redundant — delete it and let
  `AegisCode.totp` own the wipe. `secondsLeft` at `:220` already uses
  `entry.period` — correct.
- `redactedCode`/`formattedCode` currently assume 6 digits (`:251-253`,
  `:803-804`). For 7/8-digit entries render `"●".repeat(digits)` grouped as the
  code is grouped. Add a `groupCode(code)`/`groupBullets(digits)` helper: split
  into two halves for 6, `3-4` for 7, `4-4` for 8.

### 1.4 HOTP — real counter increment + persistence + advance UI (A-F4, A-U2, D-L3)

HOTP is FIX, not drop. Decision: **HOTP entries do not auto-render a code and
have no countdown ring.** They render as an "advance" row.

Data / persistence:
- `AegisEntry.counter` already exists and round-trips through
  `toJson`/`fromJson` (`AegisEntry.kt:27,44,59`) and the vault file
  (`AegisVault.serialize`/`parse`). No schema change needed.
- Advancing = compute `hotpCodeAt(secret, entry.counter, digits, algo)`, then
  **persist `counter + 1` before revealing/copying** so a crash never re-serves
  a consumed counter. Because `AegisEntry` is an immutable data class inside
  `UnlockedAegisVault.contents`, advance replaces the entry:

```kotlin
// AegisCode.kt
fun advanceHotp(vault: UnlockedAegisVault, entry: AegisEntry): String {
    val secret = entry.secretBytes()
    val code = try { Totp.hotpCodeAt(secret, entry.counter, entry.digits, entry.algorithm.toTotpAlgo()) }
               finally { Crypto.wipe(secret) }
    val advanced = entry.copy(counter = entry.counter + 1)
    vault.contents = vault.contents.copy(
        entries = vault.contents.entries.map { if (it.id == entry.id) advanced else it }
    )
    vault.save()          // persist AFTER computing, BEFORE returning code
    return code
}
```

  Note the ordering: code is computed from the CURRENT counter, then the vault
  is saved at counter+1 (RFC 4226 client increments after generating). If
  `vault.save()` throws, propagate — do not return a code whose counter wasn't
  persisted (prevents desync-by-crash, which is the one thing worse than a
  wrong code for HOTP).

HOTP UI (main app, `ListScreen`/`EntryRow`):
- Branch `EntryRow` on `entry.type`. For `HOTP`:
  - No `LaunchedEffect` tick dependency, no `CountdownRing`, no seconds-left.
  - Right side shows a `SecureButton` "Generate next code" (not a tap-anywhere
    copy — the action mutates state and must be deliberate).
  - On tap: call `AegisCode.advanceHotp`, then `copyCodeToClipboard` with the
    entry's digits; Toast "Code copied — counter advanced to N". Because HOTP
    codes don't expire on a clock, use `autoClearSeconds = 60` (fixed) and say
    so honestly (see §6 D-S1).
  - Long-press still opens the delete dialog.
- HOTP row still never renders the code in the visual tree at rest — only the
  copy path delivers it (consistency with the render-nothing doctrine). The
  "generate" affordance replaces the redacted-bullets display.

HOTP UI (IME): the IME row for a HOTP entry commits a freshly-advanced code
(`AegisCode.advanceHotp` on the shared `AegisVaultManager.current`), then
`switchToPreviousInputMethod()`. Same persist-before-commit ordering. See §2 for
why the vault is unlocked when the IME runs post-redesign.

Import of HOTP is already correct (`GoogleAuthMigration.kt:138-144`,
`FileImports.kt:215-217`); no reject path is needed once generation is real.

---

## 2. IME REDESIGN — auth-trampoline (A-U3, A-M5, A-F3, D-L2, D-M3, D-S4)

### 2.1 Why the current IME is dead (verified)

Release posture locks the vault whenever aegis loses focus:
`MainActivity.onUserLeaveHint` → lock + `finishAndRemoveTask()`
(`MainActivity.kt:184-203`), `onStop` locks as backstop (`:215-232`),
`TestingMode.KEEP_ALIVE_ON_LEAVE = false` (`TestingMode.kt:56`). So
`AegisVaultManager.current` is null exactly when another app has input focus —
i.e. whenever the IME could be used. The IME's own instruction "Open aegis
once to authenticate, then switch back here" (`AegisInputMethodService.kt:122`)
can never succeed. Additionally the IME is undiscoverable (no enablement UX,
A-F3) and commits stale build-time codes (`:209-272`, D-M3).

### 2.2 Chosen mechanism: AuthTrampolineActivity (recommended, most shippable)

Rejected alternatives:
- "copy-then-type": defeats the IME's whole value (the point is no clipboard).
- "time-boxed grace" (keep vault N seconds after `onStop`): weakens the
  lock-on-leave invariant globally for every code path, not just the IME —
  higher blast radius, and the SUITE honesty policy (CD-4) dislikes a vault
  that is "locked" in the UI but readable for N seconds. Not chosen.

Design — a dedicated transparent activity the IME launches for a *scoped,
short-TTL* IME session that never touches MainActivity's lifecycle locks:

New class `AuthTrampolineActivity : FragmentActivity` (aegis module), manifest:
```xml
<activity
    android:name=".AuthTrampolineActivity"
    android:exported="false"
    android:theme="@style/Theme.Transparent"
    android:excludeFromRecents="true"
    android:noHistory="true"
    android:launchMode="singleInstance"
    android:configChanges="uiMode|orientation|screenSize" />
```

Flow:
1. IME locked-view button "Unlock for keyboard" starts `AuthTrampolineActivity`
   with `FLAG_ACTIVITY_NEW_TASK`. The user's target app stays in the back stack;
   the trampoline draws nothing (transparent), only the BiometricPrompt sheet.
2. Trampoline runs the SAME `promptAuth` + `AegisVault.unlockV2` as
   `MainActivity` (`MainActivity.kt:445-471`, `:484-516`), but on success calls
   `AegisVaultManager.setImeSession(vault, ttlMs = 90_000)` (new API, below) and
   `finish()` immediately. It does NOT call `setUnlocked` (that is
   MainActivity's app-session channel) — the IME session is a separate,
   time-boxed grant so the lock-on-leave logic in MainActivity is untouched.
3. On finish, focus returns to the target app; the IME re-queries on
   `onStartInputView` and now sees an unlocked, in-TTL session → renders the
   live entry list.

`AegisVaultManager` gains a parallel IME-session slot so it does not collide
with the app session and does not weaken MainActivity's locking:
```kotlin
@Volatile private var imeVault: UnlockedAegisVault? = null
@Volatile private var imeExpiresAtMs: Long = 0L

fun setImeSession(v: UnlockedAegisVault, ttlMs: Long) {
    imeVault = v; imeExpiresAtMs = SystemClock.elapsedRealtime() + ttlMs
}
/** IME reads THIS, not `current`. Auto-expires; wipes on expiry. */
val imeSession: UnlockedAegisVault?
    get() {
        val v = imeVault ?: return null
        if (SystemClock.elapsedRealtime() >= imeExpiresAtMs) { v.lock(); imeVault = null; return null }
        return v
    }
fun clearImeSession() { runCatching { imeVault?.lock() }; imeVault = null }
```
- `current`/`setUnlocked`/`clear` (`AegisVaultManager.kt:38-51`) are unchanged
  (app session). The IME uses `imeSession` exclusively.
- `MainActivity.onStop`/`onUserLeaveHint` locking is unchanged. When MainActivity
  locks the app session it should also `clearImeSession()` (a foreground app lock
  is a strong "lock everything" signal), but the IME session otherwise lives and
  dies on its own TTL — this is the whole point.
- TTL uses `elapsedRealtime` (monotonic, immune to wall-clock changes).
- The IME session is a SEPARATE unlocked vault instance (its own `unlockV2`),
  so the two sessions never share the KEK buffer; each wipes independently.

Honest TTL surfacing: the trampoline shows a one-line subtitle in the
BiometricPrompt ("Unlocks the aegis keyboard for 90 seconds"). The IME list
header shows "unlocked — expires in N s" derived from `imeExpiresAtMs`, and
when it expires the IME falls back to the locked view.

### 2.3 IME view changes (`AegisInputMethodService.kt`)

- `onCreateInputView` gate (`:76-79`): `else if (AegisVaultManager.imeSession == null)
  lockedView() else buildEntryListView()`. `buildEntryListView` reads
  `AegisVaultManager.imeSession` (not `current`) at `:168`.
- Locked-view copy (`:121-126`) REWRITE to the truthful flow: "Vault locked.
  Tap Unlock to authenticate — the keyboard stays here." Button label "Unlock
  for keyboard" launches `AuthTrampolineActivity` (not `MainActivity`); keep the
  "Switch back to your keyboard" button (`:148-158`).
- Stale-code fix (D-M3): compute the code inside the click handler, not at
  build time. In `buildEntryButton` (`:242-284`) the label may show a masked
  placeholder + issuer/account; on click, re-read `imeSession`, re-decode the
  entry's secret, compute `AegisCode.totp(entry, now)` (TOTP) or
  `AegisCode.advanceHotp` (HOTP) *at click time*, `commitText`, wipe, switch
  back. This removes the captured-`code` closure bug at `:262-273`. For TOTP
  also add a 1 s `Handler` tick to refresh the visible seconds-left while the
  keyboard is open (optional but cheap; guard against leaking the Handler across
  `onFinishInputView`).
- Colors: the IME hardcodes Holo indigo `#3F51B5` (`:131,259`) off-palette.
  Move to the shared token set (§5): background `surface`, buttons `primary`.
- Accessibility: the IME sets `IMPORTANT_FOR_ACCESSIBILITY_NO` (`:113,177`) as
  an anti-scrape posture. KEEP it, but document it honestly: the enablement
  screen (below) states "the aegis keyboard is not TalkBack-navigable by design;
  use tap-to-copy in the app if you rely on a screen reader." (CD-4 failure
  honesty.)

### 2.4 IME enablement UX (A-F3, D-S4)

Add an "Keyboard" affordance on the main List screen, mirroring passgen's proven
flow (passgen.md A10, `ACTION_INPUT_METHOD_SETTINGS` + `showInputMethodPicker`):
- A row/card "aegis keyboard — type codes without the clipboard", shown until
  the IME is enabled. Detect enabled state via
  `InputMethodManager.enabledInputMethodList` filtered to our component id
  (same detection passgen uses).
- Button "Enable in system settings" → `startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))`.
- Secondary "Switch keyboard now" → `imm.showInputMethodPicker()`.
- Never set the IME default programmatically (CD-2c). Copy states plainly:
  "Your Samsung/Gboard keyboard stays your default; aegis is switched to only
  when you need a code, and switches back automatically."

---

## 3. EXPORT + INTEROP (A-F1, A-U5, D-M1, D-M2)

Doctrine CD-3: every secret class we import must be exportable, in the
incumbent's formats, via reachable UI. Aegis today is a roach motel
(`AegisBackupAdapter` is dead code — grep-confirmed zero references outside its
own file; no exporter of any kind).

### 3.1 Export — three reachable outputs

Add an "Export" entry point on the List screen (secondary button next to
"Add entry"/"Lock"). Tapping it opens an **Export sheet** offering:

1. **otpauth:// URI list (.txt)** — one `OtpAuthEntry.toUri()` per entry, one
   per line. `OtpAuthEntry.toUri()` already exists and is currently dead
   (`OtpAuthUri.kt:38-53`); it emits `secret/issuer/digits/algorithm/period|counter`
   correctly. Build an `AegisEntry.toOtpAuthEntry()` bridge (inverse of
   `fromOtpAuth`) and join. This round-trips through our own importer.
2. **Aegis Authenticator-compatible JSON (.json)** — plaintext Aegis vault
   schema (see 3.3 for the exact shape) so the operator's real Aegis
   Authenticator can import it. This is the concrete "complement" deliverable.
3. **Encrypted backup (.usbe)** — wire the existing `AegisBackupAdapter`
   (`AegisBackupAdapter.kt:36-107`) into `BackupEnvelope.write` with
   `AesGcmPassphraseCodec` (the suite's one real at-rest format, SUITE.md §3).
   The adapter's `export()` returns the vault JSON payload; wrap it with a
   user-supplied passphrase. This is also the on-device recovery artifact (§4).

Mechanics:
- All exports are **biometric-gated + explicit action** (CD reconciles export
  with the render-nothing doctrine by gating: secrets leave only on a deliberate,
  authenticated user action). Reuse `promptAuth`; the vault is already unlocked
  on the List screen, but re-prompt for export specifically (defense-in-depth,
  and it is the one operation that writes plaintext secrets to user storage).
- Destination via SAF `ActivityResultContracts.CreateDocument` (MIME
  `text/plain`, `application/json`, `application/octet-stream`). No storage
  permission needed; consistent with the app's zero-permission posture
  (`AndroidManifest.xml:34-123`).
- Exclude the master-KEK artifact from every export (it no longer exists as an
  entry post-§4; if a legacy vault still carries entry[0], filter it by marker).
- Off the main thread: the encrypt + serialize runs in `Dispatchers.Default`
  (§5 threading), then the SAF write on `Dispatchers.IO`.
- Export sheet copy warns plainly: "This writes your TOTP secrets in
  <plaintext / passphrase-encrypted> form to a file you choose. Anyone with the
  file <and passphrase> can generate your codes." (CD-4 honesty).

### 3.2 FIX the importer — read real Aegis Authenticator JSON (A-U5 direction 1)

Real Aegis Authenticator plaintext export:
```json
{ "version": 1,
  "header": { "slots": null, "params": null },
  "db": { "version": 3,
          "entries": [
            { "type": "totp", "uuid": "...", "name": "account",
              "issuer": "Example", "note": "",
              "info": { "secret": "BASE32", "algo": "SHA1",
                        "digits": 6, "period": 30 } },
            { "type": "hotp", ..., "info": { "secret": "...", "algo": "...",
                        "digits": 6, "counter": 0 } },
            { "type": "steam", ... }
          ] } }
```
Today `FileImports.detect` classifies this as `GENERIC_OTP_JSON` (root `{` +
`"entries"` substring, `FileImports.kt:81-89`), then `parseProtonAuthJson`
fails because `obj.optJSONArray("entries")` is null (the array is under `db`,
`FileImports.kt:156-160`), and even if unwrapped, each entry fails at
`require(secretStr.isNotEmpty())` (`:209`) since the secret is under
`info.secret`.

Add a first-class format:
- `Format.AEGIS_AUTH_JSON` in the enum (`FileImports.kt:62`).
- `detect`: JSON root with a `"db"` object AND `"version"` at root, or the
  substring `"\"db\""` together with `"\"info\""` → `AEGIS_AUTH_JSON`. Check
  this BEFORE the generic `"entries"` branch (order matters; the generic branch
  currently swallows it).
- `parseAegisAuthJson(text)`: `JSONObject(text).getJSONObject("db")
  .getJSONArray("entries")`, then per entry:
  - `type`: `totp`/`hotp` → mapped; `steam` → per-entry error
    "Steam OTP not supported" (honest reject via the existing `errors` list —
    do NOT silently import it as TOTP, its truncation differs).
  - `info.secret` (base32) → `HotpSecret.decodeBase32`.
  - `info.algo` → `OtpAuthEntry.Algorithm` (`SHA1/256/512`).
  - `info.digits`, `info.period` (TOTP), `info.counter` (HOTP).
  - `issuer` = root `issuer`; `account` = root `name`.
  - Build `OtpAuthEntry`. Reuse the existing per-entry error accounting
    (`ImportSummary.errors`).
- `parseAuto` dispatch adds the `AEGIS_AUTH_JSON` branch (`:100-112`).
- **Encrypted** Aegis vaults (scrypt key-slots + AES-GCM `db`): out of v1 scope.
  Detect the encrypted shape (`header.slots != null`) and produce ONE clear
  error: "This is an encrypted Aegis vault. In Aegis Authenticator, export an
  *unencrypted* JSON, or use a password-encrypted Understory backup." (Honest
  boundary, not a silent failure.) Encrypted-vault decrypt is a tracked v1.5
  item, not shipped.
- Tests: add fixtures beside `FileImportsTest.kt` (real Aegis plaintext export
  with SHA1/SHA256/8-digit/HOTP/steam entries), asserting the steam reject and
  correct param capture. The test file already has the real-world `+`-in-secret
  fixture pattern (`FileImportsTest.kt:159-177`).

### 3.3 Export JSON shape (A-U5 direction 2)

`AegisBackupAdapter` today serializes the aegis-internal vault JSON
(`AegisBackupAdapter.kt:48-54`), which real Aegis can't read. Add a dedicated
**Aegis-compatible exporter** (separate from the encrypted-backup adapter):
`AegisAuthExport.toAegisJson(entries): String` emitting the `{version, header:
{slots:null, params:null}, db:{version:3, entries:[{type,name,issuer,note:"",
info:{secret,algo,digits,period|counter}}]}}` shape, secret re-encoded via
`HotpSecret.encodeBase32`. This is output #2 in the Export sheet (3.1). Verified
round-trippable by feeding the output back through 3.2's importer in a unit test
(both directions must pass).

### 3.4 Dedup the QR-migration bulk add (D-M5)

`MainActivity.kt` Google-migration bulk add (~`:874-881`) has no dedup, unlike
file import (`:927-949`). Route both through one merge function keyed on
`(issuer, account, secretB64)` — the same key `AegisBackupAdapter.import` builds
(`AegisBackupAdapter.kt:72-75`, but extend the key to include `secretB64` per
the audit's stated dedup key). Extract `mergeEntries(existing, incoming): MergeResult`
and call it from the file-import path, the QR-migration path, and the backup
adapter so all three agree.

---

## 4. RECOVERY — reset + export-first, remove the fake master-KEK entry (A-U4, A-M1, D-L4, D-M4)

### 4.1 Two distinct problems, one contract

1. **Brick with no reset (D-L4).** `setInvalidatedByBiometricEnrollment(true)`
   (`Crypto.kt:165`) destroys the wrap key when a fingerprint is added; unlock
   then permanently fails with "Vault decryption failed." (`MainActivity.kt:461`)
   and `AegisVault.delete` (`AegisVault.kt:52-55`) has zero UI call sites.
2. **Fake recovery artifact (A-U4).** `createV2` seals the master KEK as
   entry[0] "aegis / vault master key" for "paper transcription"
   (`AegisVault.kt:80-96`) but aegis has no reveal path — the row shows redacted
   bullets, tap copies a meaningless TOTP-of-the-KEK, and it is fully deletable
   (A7) and IME-listed. Zero recovery value, nonzero hazard.

### 4.2 SHARED recovery contract (mirrors backups' recovery-key model)

SUITE.md §3 mandates one recovery story across all four vaults, modeled on
backups (which already escrows recovery-key material). Define it in
common-security as `VaultRecovery` and have aegis adopt it; passgen/vault-folder
adopt the same in their designs.

Contract:
- **Detect the specific brick.** On unlock failure, catch
  `KeyPermanentlyInvalidatedException` distinctly from generic failure. In
  `MainActivity` UnlockScreen (`:460-463`) the `unlockV2` failure lambda must
  inspect the cause: if `KeyPermanentlyInvalidatedException` (it propagates from
  `deviceAuthDecryptCipher.doFinal`, `AegisVault.kt:125`), show the recovery
  state, not the generic "Vault decryption failed."
- **Recovery state UI** (new screen/section): explains plainly — "Your device's
  biometric set changed, which by design destroyed the key that unlocks this
  vault. The encrypted entries can no longer be opened on this device. You can:
  (a) Restore from an Understory backup file, or (b) Reset the vault to start
  over." Two buttons:
  - **Restore from backup** → SAF `OpenDocument`, read `.usbe`, prompt
    passphrase, `AegisBackupAdapter.import` into a freshly re-created vault.
    This is the real recovery path (replaces the fictional "Phase 2" copy).
  - **Reset vault** → typed-confirmation dialog ("type RESET"), then
    `AegisVault.delete(ctx)` (`AegisVault.kt:52-55`) + `Crypto.deleteDeviceAuthKey`
    (already called inside `delete`), returning to Setup. This is the escape
    hatch (D-L4).
- **Export-first nudge.** On the normal List screen, if no export has ever been
  made, show a dismissible one-line reminder "Make a backup so a biometric
  change or lost phone doesn't lose your codes → Export." Ties recovery to §3's
  export. (This is the "export-first, same shared pattern as passgen/vault-folder"
  the task calls for — recovery is only real if an export exists; the app
  actively steers the user to make one.)

`VaultRecovery` (common-security) provides the shared pieces so all four apps
match: `isPermanentlyInvalidated(t: Throwable): Boolean` (unwraps causes to
`KeyPermanentlyInvalidatedException`), and a `RecoveryAction` enum
(`RESTORE_FROM_BACKUP`, `RESET`) with shared confirm-dialog copy. Per-app wiring
stays local (each vault's `delete`/`createV2` differ).

### 4.3 Remove the master-KEK entry (A-U4, D-M4)

REDESIGN, not hide. The entry[0] artifact delivers zero value and is a hazard.

- `AegisVault.createV2` (`AegisVault.kt:70-113`): STOP sealing the master KEK as
  entry[0]. Create the vault with `AegisVaultContents(entries = emptyList())`.
  Delete the `masterEntry`/`masterB64` block (`:80-96`). The KEK is already
  recoverable operationally via the Keystore wrap (daily unlock) and via §3
  encrypted export; a plaintext self-copy inside the vault added only risk.
- Legacy vaults created by the old `createV2` carry a stray entry[0]. On unlock,
  `unlockV2` (or a one-time migration in `MainActivity` after unlock) drops any
  entry whose `(issuer, account) == ("aegis", "vault master key")` and re-saves.
  This self-heals existing installs; no user action, no data those users can use
  is lost (that entry was never a usable TOTP).
- With entry[0] gone, the first-run list is correctly empty (fixes "1 entries"
  meaning the fake entry, C-audit) and the `"${n} entries"` count is honest.
- Onboarding copy (`MainActivity.kt:375-383`, the "paper transcription / Phase 2"
  paragraph, A-M1) is rewritten to describe the REAL recovery: "aegis keeps your
  seeds encrypted on this device only. To survive a lost or reset phone, export
  a backup (Understory encrypted file, or an Aegis Authenticator-compatible
  JSON) — there's an Export button on the main screen." No promise of a feature
  that doesn't ship.

---

## 5. GUI (Section C findings)

### 5.1 Shared M3 tokens (SUITE.md §5 #9)

The app is dark-only with ~60 hardcoded hex literals over a token-free
`darkColorScheme()` (`MainActivity.kt:163`), 2-entry `strings.xml`, portrait
lock, no semantics. Adopt the suite-shared token set (authored in
common-security as `UnderstoryTheme`/`UnderstoryColors`, inherited by all seven
apps per SUITE.md #9):
- Replace every `Color(0xFF…)` literal with a token: `surface` (0xFF0A0A0A/
  0xFF1C1C1C), `onSurface` (0xFFE0E0E0), `onSurfaceVariant` (0xFF9E9E9E),
  `primary`, `error` (0xFFEF5350), `warn` (0xFFFFB74D), `success` (0xFF81C784).
  `colorForCountdown` (`:806-810`) reads `error/warn/success` tokens.
- Provide a `lightColorScheme` branch so the declared `uiMode` config-change
  handling (`AndroidManifest.xml:191`) has something to switch to; default
  follows system. Dark stays the default aesthetic.
- The IME's plain-View colors (`AegisInputMethodService.kt`) read the same token
  values via a small `ImeColors` object (Views can't use Compose theme) so the
  keyboard matches the app (kills the off-palette Holo indigo).

### 5.2 Strings → resources + a11y (D-S5)

- Move every hardcoded user-facing string in `MainActivity.kt`,
  `AegisInputMethodService.kt`, the new Export/Recovery/Enablement screens into
  `res/values/strings.xml`. Keep `resourceConfigurations += listOf("en")`
  (`build.gradle.kts:17`) for now — this is about structure/consistency, not
  shipping translations.
- Fix "1 entries" → plural resource (`getQuantityString`), also naturally fixed
  by §4.3 (empty first run).
- Add `contentDescription`/semantics: entry rows get
  `Modifier.semantics { contentDescription = "$issuer $account, double-tap to copy code, long-press to delete" }`
  (TOTP) / "…, double-tap to generate next code…" (HOTP). Today TalkBack reads
  "●●● ●●●" with no action hint (C-audit). The redacted bullets get
  `invisibleToUser()` so the screen reader announces the semantic description,
  not the bullet glyphs.
- Document the IME accessibility opt-out on the enablement screen (§2.4).

### 5.3 Off-main-thread crypto + IO + loading states (D-M6, D-S3)

- QR decode (`MainActivity.kt:855`, `QrDecoder.kt:39-93`) and file import
  (`:914-957`: SAF read + parse + N× vault re-encrypt + disk write) run on the
  main thread today → ANR risk on big images / large imports. Wrap each in
  `lifecycleScope.launch { withContext(Dispatchers.Default) { … } }` for
  decode/parse and `Dispatchers.IO` for the SAF/disk write; hop back to main for
  UI. Export (§3) uses the same discipline.
- Add a real loading state to the Add screen (a `working` flag + progress row)
  covering QR decode and import — the audit notes none exists and none is
  cosmetic.
- Mask the secret field (D-S3): the Add secret field shows the seed/full
  `otpauth://` in plaintext after paste/QR decode (`:895-896,1051-1055`),
  inconsistent with redacting 30 s codes elsewhere. Apply
  `PasswordVisualTransformation()` with an optional eye-toggle
  (`VisualTransformation.None` when revealed). Default masked.

### 5.4 Copy-window honesty (D-S1, A-M2)

`copyCodeToClipboard` hardcodes `autoClearSeconds = 30` (`:820-827`) while the
toast advertises `${entry.period}s` (`:603`). FIX: pass the entry's real window.
For TOTP: clear at the time-to-next-boundary (`secondsLeft`, already computed)
or at `entry.period`, and toast the same number. For HOTP: fixed 60 s and toast
"60s". One source of truth: `copyCodeToClipboard(ctx, code, windowSeconds)` and
the toast reads the same `windowSeconds`.

### 5.5 Misc GUI

- Diagnostics button uses plain `OutlinedButton` (`:611`) while everything else
  uses `Secure*` — switch to `SecureOutlinedButton` for consistency (harmless
  but pattern-breaking today).
- IME entry-list fixed 220 dp height (`:187-191`) is fine; keep.

---

## 6. SMALL FIXES

- **D-S2 · onNewIntent (A-F2).** Activity is `launchMode="singleTask"`
  (`AndroidManifest.xml:187`); `pendingImportUri` is read only in `onCreate`
  (`MainActivity.kt:159-160`), so "Open with aegis" while the task is alive
  silently drops the file. Override `onNewIntent(intent)`: if
  `intent.action == ACTION_VIEW`, stash the uri and trigger the same
  unlock-gated import `LaunchedEffect` path (`:975-980`). Call
  `setIntent(intent)` so recreation sees it too.
- **D-S6 · `+` in otpauth-migration data (verify first).** On-screen QRs
  percent-encode so `Uri.getQueryParameter` is fine, but an offline-decoded URI
  pasted into a text file can carry a literal `+` that `getQueryParameter`
  turns to space, which `Base64.decode` then rejects
  (`GoogleAuthMigration.kt:71-76`). Confirm with a fixture; if reproduced, strip
  ASCII whitespace from `dataParam` before `Base64.decode`. One-line hardening,
  fixture-gated.

---

## 7. NAME (A / D-L5 / SUITE.md §4) — operator decision, with recommendation

**Recommendation: store-facing name "Understory OTP".** Rationale:
- "aegis" collides head-on with the complement target Aegis Authenticator
  (`com.beemdevelopment.aegis`) — two icons both called Aegis on one phone,
  trademark/confusion risk, and it contradicts complement positioning by
  construction (you can't sit beside an incumbent wearing its name).
- Fits the suite family scheme (SUITE.md §4.5: "Understory <Noun>").
- **Change only the user-facing surface**, keep everything churn-free:
  - `res/values/strings.xml`: `app_name` → "Understory OTP",
    `ime_label` → "Understory OTP keyboard" (`strings.xml:3-4`).
  - README first line + any store listing copy.
  - Package id stays `com.understory.aegis` (not user-facing) → no reinstall,
    no signing/attestation churn, `SuitePins`/`SuiteAttestation` untouched.
  - Repo dir and internal codename "aegis" stay.

Mark this in `open_decisions`: it is a decision-class ship blocker; the code
change is trivial but the *name choice* is the operator's call. Recommended:
"Understory OTP".

---

## 8. Build order (implementer guidance)

1. §1 Totp params + AegisCode + call sites + RFC6238 tests. (Correctness; nothing
   else is honest until codes are right.)
2. §4 recovery contract + remove master-KEK entry + legacy self-heal. (Data-loss
   cliff; also unblocks honest first-run.)
3. §3 export (all three outputs) + real-Aegis import + dedup merge. (Kills the
   roach-motel; makes complement true.)
4. §2 IME auth-trampoline + enablement + stale-code fix. (Differentiator; depends
   on §1 for HOTP-in-IME.)
5. §5 GUI (tokens, strings, a11y, threading, masking, copy-window honesty).
6. §6 smalls.

Non-negotiable for any public alpha (per SUITE.md): items 1, 2, 3, and the
main-thread/threading part of 5.
