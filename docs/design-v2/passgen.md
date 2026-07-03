# passgen — V2 design (implementable)

Status: DESIGN. Resolves every finding in `docs/audit-v2/passgen.md` (A1–A39,
ship-gaps D1–D14) and the passgen-relevant items in `SUITE.md` (#2, #3, #5, #6,
#8, #9, CD-1..CD-4). Design-only; no code changed. An implementer builds
straight from this without re-deriving.

Paths relative to `C:\repos\understory\understory-passgen\` unless prefixed
`common:` (= `C:\repos\understory\understory-common\`). Package
`com.understory.passgen`; JVM/Kotlin/Compose, AGP 8.7.3, target/compile SDK 35,
minSdk 33.

---

## 0. Repositioning (the frame every decision below serves)

passgen stops pretending to be a password *manager*. It becomes the
**hardened password generator + generated-password ledger + migration buffer**
that sits **beside Bitwarden** (which permanently holds the autofill slot and
the "where passwords live" role on the operator's SM-S948U).

Three things it owns that Bitwarden does not need to give up:

1. **A coexistent IME** ("passgen keys") that types passwords into any field —
   works whether or not Bitwarden holds autofill, and (new in V2) can type a
   *saved* entry too.
2. **A receipt ledger**: every password passgen ever *generates-and-delivers*
   (autofill-generate, IME-generate, clipboard-generate) is written to a
   device-encrypted receipt store so a signup done through passgen can never
   lock the user out.
3. **A hand-off**: Bitwarden-format import AND export (CSV + JSON) so the
   ledger/vault is a *buffer* the user empties into Bitwarden, not a roach
   motel.

The vault survives but is **renamed in the UI to "Ledger"** and demoted: its
job is receive (import) → review → hand off (export). Under this framing the
vault's missing manager features (edit, search, sync, folders, reveal) are
**out of scope by design**, not gaps. The only non-negotiable vault work is the
recovery/reset/export cliff (§2, §3, §4).

Launcher/store name: keep `app_name` machine-stable but set the user-facing
label to **"Understory Keys"** per SUITE.md §4.5 (see §11). Codename `passgen`
stays internal.

---

## 1. DATA MODEL CHANGES

### 1.1 New: receipt store (`Receipts.kt`, new file)

A **second** device-encrypted file, independent of the vault KEK, because
receipts are written from the autofill/IME generate paths which do NOT run the
vault-unlock biometric. It reuses the *same* Keystore device-auth key wrapping
pattern as the vault but with its **own** Keystore alias so a vault reset does
not destroy receipts and vice-versa.

New Keystore alias in `common:common-security/.../Crypto.kt`: add a parallel
key `passgen_receipts_device_auth_v1` with an **auth-not-required** spec (see
§1.2) — receipts must be *writable without a biometric prompt* (the autofill
generate path is a headless invisible activity; prompting there is impossible).
Read-back for the Receipts screen is gated by the vault unlock instead (§5.4).

On-disk `receipts.bin` (in `filesDir`), framed identically to `vault.bin` v2
(version byte, wrapped-key-iv, wrapped-key-ct, content-iv+ct) so the existing
`Vault.atomicReplace`, length-cap, and trailing-byte-rejection logic can be
lifted verbatim into `Receipts`:

```
[ 1 byte  ] version (= 1)
[ 4 BE    ] wrapped-content-key IV length ; [N] IV
[ 4 BE    ] wrapped-content-key CT length ; [N] CT   (32-byte content key, AES-wrapped by receipts Keystore key)
[ 4 BE    ] receipts-blob length          ; [M] AES-256-GCM(receipts_json, content_key)
```

`receipts_json`:
```json
{ "version": 1,
  "receipts": [
    { "id": "<uuid>",
      "source": "autofill" | "ime" | "clipboard",
      "target": "com.example.app" | "example.com" | "",   // package or web domain, "" if unknown
      "targetKind": "package" | "domain" | "unknown",
      "createdAt": <unix_ms>,
      "shape": { "length": 24, "lowers": true, "uppers": true, "digits": true, "symbols": true },
      "savedValue": "<the password>" | null,               // null unless user opted to keep it
      "claimed": false                                     // true once user moves it into the ledger or dismisses
    }, ...
  ]
}
```

Design notes:
- **Default is value-less.** `savedValue` is `null` by default — the receipt
  proves *a password of this shape was delivered to this target at this time*,
  which is enough to regenerate deterministically? No — generation is random,
  not deterministic, so a value-less receipt cannot reproduce the exact
  password. It exists so the user is never *silently* locked out: the Receipts
  screen tells them "you set a password on example.com at 3:14pm via autofill —
  it was not saved; use account recovery" and, critically, offers a
  **"keep value" toggle that must be armed BEFORE generating** (§5.1) so
  security-conscious users get zero stored plaintext while everyone else gets a
  recoverable value. This is the honest resolution of the A7/A11 lockout trap.
- Cap `receipts[]` at 500 entries; evict oldest `claimed==true` first, then
  oldest overall, on write. Enforce in `Receipts.append`.
- `MAX_CONTENT_LEN` = 4 MiB.

### 1.2 `Crypto.kt` additions (common-security)

Add, alongside the existing device-auth key helpers:

```kotlin
private const val RECEIPTS_KEY_ALIAS = "passgen_receipts_device_auth_v1"

/** Content-key wrap for the receipts file. NOT auth-bound: the autofill/IME
 *  generate paths run headless and cannot show a biometric prompt. The file is
 *  still device-bound (non-exportable Keystore key) and StrongBox-backed where
 *  available; read-back is gated by vault unlock at the UI layer. */
fun receiptsCipherForEncrypt(): Cipher { … ensureReceiptsKey(); ENCRYPT_MODE … }
fun receiptsCipherForDecrypt(iv: ByteArray): Cipher { … DECRYPT_MODE with iv … }
fun receiptsKeyExists(): Boolean
fun deleteReceiptsKey()
```

`ensureReceiptsKey()` = same builder as `ensureDeviceAuthKey()` but WITHOUT
`.setUserAuthenticationRequired(true)` / `.setUserAuthenticationParameters(...)`
/ `.setInvalidatedByBiometricEnrollment(true)`. Keep `setKeySize(256)`, GCM,
StrongBox attempt. Because it is not auth-bound, biometric re-enrollment does
NOT invalidate it — receipts survive the exact event that bricks the vault,
which is the point.

### 1.3 `VaultEntry` — add `source` provenance (optional, defaulted)

Add one field so ledger entries carry where they came from (import vs receipt
claim vs manual). Backwards-compatible (optional in `fromJson`, defaulted):

```kotlin
val source: String = "",   // "", "import:google", "import:bitwarden", "receipt", ...
```

No format bump needed (`Vault.parse` tolerates unknown/missing fields; the
`PassgenBackupAdapter` schemaVersion stays 1).

### 1.4 Kill dead vault fields (A21, D14)

Delete from `Vault.kt`: `MIN_REVEAL_LOCK_LEN` (:93), `REVEAL_M/T/P` (:110-112),
and the `reveal_lock_hash`/`reveal_lock_salt` doc block (:32-39) — v2 never
writes them. Delete `Crypto.generateMasterPassword` (unused; verified no
callers). This is pure shed; it keeps the auditable-surface claim honest.

---

## 2. DATA-LOSS FIX (a): generated-password RECEIPTS  — resolves A7, A11, D3

**Every** generate-and-deliver path writes a receipt before it finishes.

### 2.1 `Receipts.kt` API (new)

```kotlin
object Receipts {
    data class Receipt(id, source, target, targetKind, createdAt, shape, savedValue, claimed)
    fun append(ctx: Context, source: String, target: String, targetKind: String,
               shape: Settings.Snapshot, value: CharArray?)   // value copied to String only if non-null
    fun exists(ctx): Boolean
    fun load(ctx): List<Receipt>            // decrypt; caller = Receipts screen (post vault-unlock)
    fun markClaimed(ctx, id: String)
    fun delete(ctx, id: String)
    fun deleteAll(ctx)                       // used by full reset
    fun unclaimedCount(ctx): Int             // cheap header-only? No — must decrypt; cache count in SharedPreferences on each append
}
```

`append` runs on a background thread (`Dispatchers.IO`) — see §8. It:
1. `receiptsCipherForEncrypt()` (no prompt), read-modify-write the blob.
2. If `value != null`, store `String(value)`; caller still wipes the CharArray.
3. Update a plaintext `SharedPreferences` counter `receipts_unclaimed_count`
   (NOT a secret — it is a count) so `MainActivity` can badge the ledger button
   without decrypting.

### 2.2 Wire the three generate paths

**Autofill generate — `GenerateAndFillActivity.kt`** (currently :81-105, "generate
→ AutofillValue → wipe → finish"). After building the `AutofillValue` and
before `setResult`, call:
```kotlin
val target = intent.getStringExtra(EXTRA_TARGET) ?: ""       // NEW extra (see below)
val kind   = intent.getStringExtra(EXTRA_TARGET_KIND) ?: "unknown"
Receipts.append(applicationContext, "autofill", target, kind, snap,
                if (Settings.load(ctx).keepGeneratedValue) chars else null)
```
`chars` is the generated `CharArray` — read it for the receipt *before* the
`finally { PasswordGenerator.wipe(chars) }`. Pass the target: in
`PassgenAutofillService.kt` where it builds the "generate (N chars)" dataset
(:133-153), add the resolved web-domain/package (it already computes this for
the saved-entry match heuristic at :442-453) into the `GenerateAndFillActivity`
intent as `EXTRA_TARGET` / `EXTRA_TARGET_KIND`.

**IME generate — `PassgenInputMethodService.onGenerateClicked`** (:181-186).
After `ic.commitText(...)`, before wiping:
```kotlin
val ei = currentInputEditorInfo
val target = ei?.packageName ?: ""
Receipts.append(applicationContext, "ime", target, "package", snap,
                if (Settings.load(ctx).keepGeneratedValue) chars else null)
```
AND fix A12 (silent-fail): show a transient in-keyboard confirmation (§6.3) so
the button is not dead-feeling. The receipt write is best-effort wrapped in
`runCatching` — a receipt failure must never crash the IME, but it should set
the confirmation text to "typed (not recorded)" so the user knows.

**Clipboard generate — `MainActivity`** (:680-710, Generate & Copy). After the
clipboard copy, `Receipts.append(ctx, "clipboard", "", "unknown", snap, if keep chars else null)`.
Clipboard has no target field; that's honest (`targetKind="unknown"`).

### 2.3 Receipts screen (new Stage in VaultActivity, `Stage.Receipts`)

Reachable from the Ledger list ("Unclaimed generated passwords (N)" button,
badged from the plaintext counter). Requires the vault to be unlocked already
(the read-back is gated by the same session, not a second prompt). States:
- **Empty**: "No generated-password receipts yet. When you generate a password
  via the keyboard or autofill, a receipt lands here so you never lose it."
- **List**: each row = target (or "unknown app") · source chip
  (autofill/keyboard/clipboard) · relative time · "value saved" / "not saved"
  badge. Row actions:
  - **Reveal value** (only if `savedValue != null`) — biometric-gated
    (`promptAuth` with a fresh `deviceAuthCipherForDecrypt` on the *vault* key;
    the vault is open, so this is a lightweight confirm), copies to clipboard
    with the standard 30s auto-clear, never renders inline.
  - **Save to Ledger** — materializes a `VaultEntry(source="receipt", title=target,
    password=savedValue ?: "", url=domain)`; only enabled if `savedValue != null`
    (can't save a value we don't have). Marks `claimed=true`.
  - **Dismiss** — `markClaimed`; confirm dialog ("This won't recover the
    password — dismiss the reminder?").
- Honest banner when `savedValue==null`: "Value not stored (you had 'keep
  generated value' off). If you're locked out, use the site's account
  recovery."

---

## 3. DATA-LOSS FIX (b): vault RESET + biometric-re-enrollment recovery — resolves A19, D1, SUITE #3

### 3.1 The real failure

`Crypto.ensureDeviceAuthKey` sets `setInvalidatedByBiometricEnrollment(true)`
(:165). Any new fingerprint/face enrollment permanently invalidates the vault
Keystore key → `unlockV2` throws `KeyPermanentlyInvalidatedException` inside
`deviceAuthDecryptCipher.doFinal` → vault unopenable forever. The UI currently
points at a nonexistent "Settings → reset vault" (A19). V2 makes reset real and
adds export-first recovery.

### 3.2 Detect the invalidated-key state precisely

In `UnlockScreen`, when initializing the decrypt cipher and on the failure path,
distinguish the invalidation case. `Crypto.deviceAuthCipherForDecrypt` can throw
`KeyPermanentlyInvalidatedException` at `Cipher.init` OR the prompt succeeds and
`doFinal` throws it. Add a helper:

```kotlin
// Crypto.kt
fun deviceAuthKeyInvalidated(iv: ByteArray): Boolean =
    try { deviceAuthCipherForDecrypt(iv); false }
    catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) { true }
    catch (e: Exception) { false }   // other errors handled by normal flow
```

`UnlockScreen` calls this once on entry (`remember`). If true, it renders the
**Recovery screen** (§3.3) instead of the unlock button — not a buried hint
after 3 attempts.

### 3.3 Recovery / Reset screen (replaces the A19 dead hint)

New `Stage.Recovery`. Rendered when `deviceAuthKeyInvalidated` is true, and also
reachable from `UnlockScreen` via a "Can't unlock?" `SecureOutlinedButton` for
the general case. Content:

> **Your device biometric changed.**
> Adding or removing a fingerprint/face invalidates the key that unlocks this
> ledger — this is a security feature, not a bug. Your entries can no longer be
> decrypted on this device.
>
> - **[Export first (recommended)]** — *disabled with reason when the key is
>   invalidated*, because export needs to decrypt the vault which is exactly
>   what's broken. Show the reason inline: "Export needs the old key, which is
>   gone. If you have a previous encrypted backup file, restore it after reset."
> - **[Restore from backup file]** → `Stage.Restore` (§4.4). This is the real
>   recovery path.
> - **[Reset ledger (erases everything)]** — typed-confirmation
>   (`AlertDialog` requiring the user to type `RESET`), then:
>   ```kotlin
>   Vault.delete(ctx)            // deletes vault.bin + Crypto.deleteDeviceAuthKey()
>   // receipts intentionally survive a vault reset (different key); offer:
>   ```
>   After reset, route to `Stage.Setup`. **Do NOT delete receipts on vault
>   reset** — they're on the survivable key and may be the user's only record
>   of what they'll need to re-recover. Surface a line: "Your generated-password
>   receipts were kept."

For the **general** (non-invalidated) reset entry point, "Export first" IS
enabled and routes to `Stage.Export` (§4.3) before offering reset.

### 3.4 Setup copy fix (A18)

`SetupScreen` step 0 (:372): delete the false "After unlock, you can view it
(biometric-gated, 10s window)" clause — no reveal path exists and none is added
(threat model forbids rendering). Replace the whole master-key paragraph with
honest copy that also stops calling the RNG "the IME pipeline" as if the
keyboard runs (borderline-dishonest metaphor, C-audit):

> "passgen generates a 256-bit master key with the same cryptographic RNG it
> uses to generate your passwords. The master is self-encrypted under a
> hardware-backed, screen-lock-bound Keystore key and sealed inside the ledger
> it just created. It is never shown and never typed."

And fix the "Lost device = lost vault" box (:382): replace the vaporware
"Stage 2C adds an HOTP-gated encrypted backup" with the real V2 recovery story:
"Recovery = the encrypted export file you create under Ledger → Export. Make one
now or any time." Rename the setup button (:387) from "Generate via IME
pipeline" to "Create ledger".

---

## 4. DATA-LOSS FIX (c) + hand-off: real EXPORT/IMPORT — resolves A24, A27, A28, A29, D2, D5, SUITE #2, CD-3

### 4.1 Kill the HOTP-prerequisite dead code, keep the envelope

`BackupFormat.Payload` currently requires `totpSecret` from a nonexistent
`entry[1]` (A27). V2: **drop `totpSecret` and `vaultMasterKek` from the payload
entirely.** The backup is a passphrase-encrypted export of *entries*, not a
device-KEK escrow. Rewrite `BackupFormat.Payload`:

```kotlin
data class Payload(val exportedAtMs: Long, val entries: List<VaultEntry>)
```

`payloadToJson`/`jsonToPayload` lose the `vault_master_kek_b64` /
`totp_secret_b64` fields and their length checks. Bump payload+file `VERSION`
to `2`; a v2 reader refuses v1 cleanly (v1 files only ever existed in tests).
Everything else in `BackupFormat` (Argon2id + AES-GCM framing, trailing-byte
rejection, `MAX_BACKUP_CT_LEN`) is kept — it's correct and unit-tested. This
removes the "requires entry[1] that's never created" blocker (A27).

### 4.2 Add Bitwarden CSV/JSON — both directions (A24, D5)

**Import — `ImportFormats.kt`.** Extend `Format` enum with `BITWARDEN_CSV`,
`BITWARDEN_JSON`. `detect`:
- JSON with `"items"` array whose elements have `"type"` and a `"login"` object
  → `BITWARDEN_JSON` (add token check for `"login"` + `"type"` alongside the
  existing Proton branch; check Bitwarden before the generic UNKNOWN return).
- CSV header starting `folder,favorite,type,name,notes,fields,` (Bitwarden's
  export header; the `reprompt`/`login_uri`/`login_username`/`login_password`/
  `login_totp` columns follow) → `BITWARDEN_CSV`.

`parseBitwardenCsv`: reuse `parseCsv`; map `name`→title, `login_uri`→url,
`login_username`→username, `login_password`→password, `notes`→notes; skip rows
where `type != "login"` (Bitwarden type 1 = login; 2=note, 3=card, 4=identity).
`parseBitwardenJson`: iterate `items[]`, keep `type==1`, read
`login.username`, `login.password`, first of `login.uris[].uri`, `name`,
`notes`. Add to `parseAuto` dispatch and the UNKNOWN error message. Add unit
tests mirroring the existing 17 (`src/test/.../ImportFormatsTest.kt`): one
Bitwarden CSV (with quoted notes, a non-login row that must be skipped, a
multi-URI login) and one Bitwarden JSON.

**Export — new `ExportFormats.kt`.** Pure functions, no UI/Compose imports:
```kotlin
object ExportFormats {
    fun toBitwardenCsv(entries: List<VaultEntry>): String   // RFC-4180 quoted, Bitwarden header + type=login rows
    fun toBitwardenJson(entries: List<VaultEntry>): String   // { "encrypted": false, "items": [ {type:1, name, login:{username,password,uris:[{uri}]}, notes} ] }
    fun toGenericCsv(entries: List<VaultEntry>): String       // name,url,username,password,note (Google-compatible round-trip)
}
```
All exporters **filter the master entry** (`title != Vault.MASTER_ENTRY_TITLE`),
same as `PassgenBackupAdapter.export` (:60-63).

### 4.3 Export screen (`Stage.Export`, new) — resolves A29/D2

Reachable from Ledger list ("Export / hand off") and from the general Reset
flow (§3.3). Two lanes, both biometric-gated (the vault is already unlocked, so
this is a confirm, not a second unlock — reuse `promptAuth`):

1. **Encrypted export (recommended)** — passphrase field (min 8, strength hint),
   then `BackupFormat.encode(passphrase.toCharArray(), Payload(now, entries))`,
   written via SAF `ActivityResultContracts.CreateDocument("application/octet-stream")`
   with default name `understory-keys-YYYYMMDD.ukbackup`. This is the recovery
   file referenced by §3.
2. **Plaintext export (hand off to Bitwarden)** — a `Switch`-gated dangerous
   lane. Default off. Turning it on shows a red warning box: "This writes your
   passwords UNENCRYPTED so you can import them into Bitwarden, then delete the
   file. Anyone who reads the file reads your passwords." Format picker
   (SegmentedButton or radio): **Bitwarden CSV / Bitwarden JSON / Generic CSV**.
   Explicit `SecureButton` "I understand — export plaintext" → SAF
   `CreateDocument("text/csv"|"application/json")`, default name
   `understory-keys-bitwarden-YYYYMMDD.csv`. This is the CD-3 hand-off and the
   thing that makes the ledger a buffer, not a roach motel.

All export work on `Dispatchers.IO` with a progress state (Argon2id 64 MiB is
~200ms+; must not block the main thread — §8).

### 4.4 Restore screen (`Stage.Restore`, new)

Reachable from Setup ("Restore from backup instead") and from Recovery (§3.3).
SAF `OpenDocument`, passphrase field, `BackupFormat.decode`, then merge entries
into a freshly-created vault (create a new device-auth key via the normal Setup
biometric, then write the decoded entries). Dedup by (title, username) preferring
incoming (this is a restore, not a merge-into-existing). On wrong passphrase the
GCM tag mismatch throws — show "Wrong passphrase or corrupt file."

### 4.5 Wire `PassgenBackupAdapter` OR delete it (A28)

`PassgenBackupAdapter` (a correct `common:common-backup/BackupAdapter`
implementation) is dead. V2 decision: **keep it and make the in-app Export
screen its single caller** for the encrypted lane — instantiate
`PassgenBackupAdapter(vault)` and feed `.export()` bytes through
`BackupFormat.encode`. This gives one code path shared with the eventual
backups-app orchestrator (SUITE.md's cross-app `BackupProvider` is still v-next;
don't build it here). If the implementer finds the double-wrapping awkward, the
acceptable alternative is: Export screen calls `BackupFormat.encode` directly on
`vault.contents.entries` filtered for master, and `PassgenBackupAdapter` is
**deleted** (do not leave it dead). Pick one; do not ship it unreferenced.

---

## 5. GENERATE-PATH & SETTINGS CHANGES

### 5.1 "Keep generated value" setting (drives §2 receipts)

Add to `Settings.Snapshot`: `val keepGeneratedValue: Boolean` (default **false**
— privacy-preserving default; the receipt still records shape/target/time).
Add `K_KEEP_VALUE` key, load/save, and a `ToggleRow` on the generator screen:
"Save generated passwords to receipts (so you can recover them)". Honest
subtitle: "Off = receipts record only when/where, not the value." This must be
armed before generating because the value is wiped immediately after delivery.

### 5.2 Clipboard auto-clear honesty (A4, CD-4e, D7)

`MainActivity` Generate & Copy toast (:695-699) promises the clear
unconditionally, but the clear is a process-scoped `Handler` (`common:common-security/.../Clipboard.kt:34`).
Fix copy to: "Copied. Auto-clears in {N}s **while passgen is running** — if you
swipe passgen away first, clear your clipboard manually." Keep the existing
honest Samsung clipboard-panel caveat (:673-678).

### 5.3 Autofill saved-entry: lock after pick (A-audit D12)

`FillSavedEntryActivity.returnDataset` finishes without `vault.lock()` (only the
cancel path locks). Add `s.vault.lock()` in `returnDataset`'s `finally`.

### 5.4 Receipts read gate

Receipts screen decrypts with the (non-auth) receipts key but is only reachable
*after* a vault unlock in the same session — so a thief with an unlocked-but-
not-vault phone can't browse receipts. Reveal of a saved value is separately
biometric-gated (§2.3).

---

## 6. IME V2 — the complete coexistence channel (A10, A12, A13, D13, SUITE #5)

The IME is passgen's genuinely coexistent surface (multi-enable; never set
default). V2 makes it a **complete** channel and fixes silent-fail.

### 6.1 Add "Type a saved entry" (A13, D13)

New third button in `buildKeyboardView` (`PassgenInputMethodService.kt`):
"Type a saved entry". Tapping it launches the existing picker UI. Because an IME
service cannot host a `BiometricPrompt`/FragmentActivity directly, launch a
**transparent trampoline activity** `ImeFillActivity` (new, `Theme.Translucent.NoTitleBar`,
`excludeFromRecents`, `FLAG_SECURE`) that:
1. Runs the vault unlock (`promptAuth` + `Vault.unlockV2`) — reuse the exact
   flow from `FillSavedEntryActivity`.
2. Shows the picker (reuse the Compose picker from `FillSavedEntryActivity`,
   filtered by `currentInputEditorInfo.packageName` passed in as an extra).
3. On pick, returns the chosen `password` (+ optional username) to the IME.

The IME→activity→IME handoff: `ImeFillActivity` puts the selected value into a
`ResultReceiver` (or a short-lived static `CompletableDeferred` on the IME
service, cleared immediately) — **not** the clipboard, **not** an Intent extra
that lingers. On return, `onGenerateClicked`'s sibling `onFillSavedClicked`
calls `ic.commitText(value, 1)` then wipes. Value lifetime is bounded to the
commit; document the one unavoidable `String` construction (same tradeoff as
`GenerateAndFillActivity:85`). This makes the IME able to deliver BOTH generated
and saved credentials — the missing complement path for Bitwarden-holds-slot
users.

### 6.2 IME can-type receipts too

Same picker/trampoline optionally surfaces unclaimed receipts with saved values,
so a password generated moments ago via autofill can be re-typed into a
"confirm password" field. Low priority; include if cheap.

### 6.3 IME failure honesty (A12, CD-4c)

`onGenerateClicked` currently swallows everything → dead-feeling button. Add a
`TextView` status line in the keyboard view; on the generate/fill paths, set it
to "typed" / "typed (not recorded)" / "couldn't type: {reason}" instead of
silent return. Keep the crash-catcher; just make the outcome visible.

### 6.4 a11y limitation, documented (SUITE B.2)

The IME sets `IMPORTANT_FOR_ACCESSIBILITY_NO` (security tradeoff — keeps
TalkBack/other services from reading the keyboard). Keep it, but **document it**
on the generator screen's IME section: "The passgen keyboard opts out of
accessibility services by design; screen-reader users should use autofill or
clipboard mode." Honest known-limitation, not a silent gap.

---

## 7. AUTOFILL & SAMSUNG DUAL-SLOT — honest gating (A38, A39, D6, SUITE #6, CD-2)

### 7.1 Status-first, not replace-first (CD-2b/d)

On the generator screen's autofill section, lead with **who holds the slot**,
not a "set passgen as provider" CTA. Use
`AutofillManager.hasEnabledAutofillServices()` (true = *we* hold it) and
`isAutofillSupported()`:
- We hold it → "Autofill: passgen is the active provider."
- Someone else holds it → "Autofill: another provider holds the slot (likely
  your password manager). passgen is available in **keyboard mode** — no slot
  needed." Then the *primary* CTA is "Enable passgen keyboard", and setting
  passgen as autofill provider is a secondary, clearly-labeled
  "Replace current autofill provider with passgen" button (never the primary
  action). Android's API only says "us or not us"; do not claim to name the
  incumbent beyond "another provider" unless resolvable.

### 7.2 Delete the Credential Manager contradiction (A38, D7)

`MainActivity` :566-570 tells the user to "use Credential Manager (Android 14+)"
and :589-593 says it "isn't a fit here." No Credential Manager code exists.
**Delete the first paragraph's Credential-Manager clause** (:567). Keep the
honest ":589-593" paragraph that says the IME is the universal coexistence path.
Net: the standard-mode copy says exactly one true thing — coexist via the
keyboard.

### 7.3 Actually gate the Samsung dual-slot branch (A39, D6)

Today the Samsung branch keys on `isSamsung()` alone and
`DeviceProfile.supportsDualAutofillSlots()` is never called (and is itself just
`isSamsung()`). V2:
1. The dual-slot instructions are **unverified on real One UI 7** (SM-S948U;
   SAMSUNG_QUIRKS.md has no autofill entry). Until verified on-device, do **not**
   assert a settings path that may not exist. Change the branch to:
   `if (DeviceProfile.supportsDualAutofillSlots())` and have that function gate
   on an explicit **verified-allowlist** rather than brand: return true only
   when a device/One-UI check confirms the Additional-service slot. Concretely,
   probe: try to resolve the `Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE`
   intent AND check One UI availability; if unverifiable, return false.
2. When false (or unverified), **fall through to keyboard-mode copy** — the safe,
   always-true channel — instead of dual-slot instructions.
3. Remove the undocumented `"android.settings.AUTOFILL_SETTINGS"` string action
   (:528) and its generic-Settings fallback that strands the user at the Settings
   root (CD-4a: no dead controls). Use only the documented
   `ACTION_REQUEST_SET_AUTOFILL_SERVICE` (which controls the primary slot) and,
   for the dual-slot case *if verified*, deep-link precisely or not at all.
4. Add a `SAMSUNG_QUIRKS.md` autofill entry once verified (operator action noted;
   design assumes unverified → keyboard-mode default).

**Disposition:** the dual-slot "Additional service" story is **REDESIGN-to-gated**
— kept only behind a real capability check, defaulting to the honest keyboard
path everywhere else. If on-device verification fails, it becomes DROP.

---

## 8. THREADING & ANR (SUITE #8, D11)

All crypto/IO off the main thread with visible loading states:
- **Import** (`VaultActivity.runImport`, :691-747): wrap file read + parse +
  save in `withContext(Dispatchers.IO)`; drive `working`/`status` state; add a
  `CircularProgressIndicator` while `working`. Cap input at 8 MiB before
  `readText()` (reject larger with a message) to bound the hostile-file ANR.
- **Export / Restore** (§4): Argon2id 64 MiB on `Dispatchers.IO`, progress state.
- **Receipts.append**: `Dispatchers.IO` (already headless; must not block the
  autofill binder return — fire-and-forget with `runCatching`, but *await*
  enough to guarantee the write is durable before `finish()` in the autofill
  case, since the process may die; use `runBlocking`-with-timeout only in the
  invisible activity, never on a UI thread).
- **Setup / Unlock**: `Vault.createV2`/`unlockV2` do AES-GCM (fast) but the
  file write should still be off main thread.

---

## 9. ACTION_VIEW import confirmation (A26, D4, SUITE #6, CD-4a)

The manifest comment (:190-199) promises "the user still has to … tap to
confirm. No code path bypasses the … confirmation," but `ImportScreen` auto-runs
`runImport(incomingUri)` in a `LaunchedEffect` (:754-759) with no confirm tap —
a vault-poisoning surface (any app fires ACTION_VIEW with a crafted CSV).

Fix: split `runImport` into `parseOnly(uri)` (read + detect + parse into a
preview list, no vault write) and `commit(parsed)`. The `LaunchedEffect` for an
incoming URI calls `parseOnly` and shows a **confirmation card**: "Import {N}
entries from {filename}? Source: {detected format}. Duplicates (same URL +
username) will be skipped." with an explicit `SecureButton` "Import" that calls
`commit`, and "Cancel". The SAF-picker path (:743-747) gets the same
confirmation (it's cheap and consistent). This makes the manifest contract true.

---

## 10. VAULT/LEDGER UI FIXES

### 10.1 Master-KEK entry hidden from list + picker (A16-list, D9, SUITE §3)

- `ListScreen` (:567-590): filter `vault.contents.entries.filter { it.title != Vault.MASTER_ENTRY_TITLE }`
  for display and for the "N entries" count; the master is infrastructure, not a
  credential. If a "show recovery key" affordance is ever wanted it belongs in
  Recovery, not the list — and per threat model it is NOT rendered, so simply
  hide it.
- `FillSavedEntryActivity` picker (:282-359): filter the master entry out of the
  pickable list so it can't be autofilled into a field.

### 10.2 Confirm-on-delete (A16, C-audit, D8)

`ViewEntryScreen` delete (:988-990) is one tap, styled like "Back" directly
above. Add an `AlertDialog`: "Delete '{title}'? This can't be undone." with
destructive-styled confirm. Visually separate Delete from Back (spacer +
`MaterialTheme.colorScheme.error` text/outline).

### 10.3 Vault list empty state (C-audit, D10)

After hiding the master entry, a fresh ledger is genuinely empty. Add:
"Your ledger is empty. Import from Google, Proton, or Bitwarden, or add an
entry — then hand off to Bitwarden any time via Export." with the import/add
CTAs.

### 10.4 Add-entry: allow manual password (C-audit)

`AddEntryScreen` is generate-only. For a *migration buffer* the user must be
able to store an existing credential (e.g. type one Bitwarden won't export).
Add an optional manual password `OutlinedTextField` (masked, with a show
toggle) alongside the generate button. Low priority; include if it doesn't
bloat — otherwise DROP and rely on import.

### 10.5 Autofill picker rows use secure semantics (C-audit)

`FillSavedEntryActivity` picker rows use plain `clickable` while `onPick`
releases a credential. Switch to the suite's `secureClickable` /
`SecureButton` pattern (`common:common-security/.../SecureButton.kt`) for
consistency with the tap-jack doctrine.

---

## 11. GUI SHIPPABLE BAR (C-audit, D10, SUITE #9, CD-4)

Mechanical but broad; touches every screen. Do NOT hand-roll — inherit the
shared tokens SUITE #9 mandates.

1. **M3 tokens, not hex.** Replace the ~60+ hardcoded `Color(0xFF…)` literals
   across `MainActivity.kt`, `VaultActivity.kt`, `FillSavedEntryActivity.kt`
   with `MaterialTheme.colorScheme` roles from the shared token set
   (`common:common-security` theme module per SUITE #9). Declare dark-only
   honestly: `themes.xml` parent stays a dark M3 theme; add a one-line
   "passgen uses a dark theme" note rather than pretending light adapts.
2. **Strings → resources.** Move every hardcoded Kotlin string into
   `res/values/strings.xml` (today: 2 strings). Keep `resourceConfigurations`
   as-is for now (no localization committed), but the extraction is required so
   the walls-of-text are reviewable and reusable. Long prose blocks (the
   generator screen essays) get trimmed to the honest one-liners specified in
   §3.4/§5.2/§7.2.
3. **Switch/Slider semantics (a11y).** `ToggleRow` (:764-774) must merge label +
   Switch semantics (`Modifier.semantics(mergeDescendants = true)` +
   `Role.Switch` / `stateDescription`). Slider (:457-462) gets a
   `contentDescription`/`stateDescription` ("password length {n}"). Masked-
   password dots (`VaultActivity:952`) get a `contentDescription` ("password
   hidden").
4. **Empty/loading/error states** per §2.3, §8, §10.3.
5. **Typography:** raise sub-12sp body text (footer 9sp, 11sp hints) to ≥12sp
   for body; captions may stay smaller.
6. **Name:** set launcher label + first README line to "Understory Keys"
   (SUITE §4.5). `app_name` string updated; package id unchanged.
7. **Suitecaps authority:** already fixed in passgen (`${applicationId}.suitecaps`,
   `build.gradle.kts:74`) — no action; noted so a sweep doesn't "re-fix" it.

---

## 12. FEATURE DISPOSITION TABLE (every audited item)

| Audit | Feature | V2 disposition |
|---|---|---|
| A1 | Generator core | KEEP as-is |
| A2 | Settings persistence | KEEP; add `keepGeneratedValue` (§5.1) |
| A3 | Generate & Copy + auto-clear | KEEP; add receipt (§2.2) |
| A4 | "Auto-clear in Ns" promise | FIX copy (§5.2) |
| A5 | Autofill field detection | KEEP |
| A6 | Autofill: pick saved entry | KEEP; lock after pick (§5.3); hide master (§10.1) |
| A7 | Autofill: generate | REDESIGN — write receipt (§2.2) |
| A8 | onSaveRequest no-op | KEEP (honest) |
| A9 | Inline suggestions off | KEEP off; revisit v-next (DROP-to-doc) |
| A10 | IME generate & insert | KEEP; add receipt + status (§2.2, §6.3) |
| A11 | IME generate persistence | REDESIGN — receipt (§2.2) |
| A12 | IME silent-fail | FIX — status line (§6.3) |
| A13 | IME type saved entry | REDESIGN — add it (§6.1) |
| A14 | v2 vault create | KEEP |
| A15 | Unlock | KEEP; add invalidated-key detect (§3.2) |
| A16 | List/Add/View/Delete | FIX — confirm-delete (§10.2), empty state (§10.3), manual pw (§10.4) |
| A17 | View: copy/regenerate | KEEP |
| A18 | "10s reveal window" | FIX — delete false claim (§3.4) |
| A19 | "Settings → reset vault" | FIX — real Recovery/Reset (§3.3) |
| A20 | File-format hardening | KEEP (lift into Receipts §1.1) |
| A21 | v1 reveal-lock remnants | DROP dead code (§1.4) |
| A22 | Import Google CSV | KEEP |
| A23 | Import Proton | KEEP |
| A24 | Import Bitwarden | FIX — add both formats (§4.2) |
| A25 | Import UI | FIX — IO thread + progress (§8) |
| A26 | ACTION_VIEW import | FIX — require confirmation (§9) |
| A27 | BackupFormat dead + HOTP prereq | REDESIGN — drop HOTP, wire to Export (§4.1/§4.3) |
| A28 | PassgenBackupAdapter dead | FIX — wire to Export or delete (§4.5) |
| A29 | No export | FIX — Export screen (§4.3) |
| A30-A37 | Hardening/plumbing | KEEP |
| A38 | Credential Manager contradiction | FIX — delete clause (§7.2) |
| A39 | Samsung dual-slot | REDESIGN-to-gated / DROP-if-unverified (§7.3) |

---

## 13. NEW/CHANGED/DELETED FILES

**New:**
- `Receipts.kt` — receipt store (§1.1, §2.1)
- `ExportFormats.kt` — Bitwarden/generic exporters (§4.2)
- `ImeFillActivity.kt` — transparent trampoline for IME saved-entry typing (§6.1)
- `res/values/strings.xml` growth (§11.2)

**Changed:**
- `common:common-security/.../Crypto.kt` — receipts key helpers +
  `deviceAuthKeyInvalidated` (§1.2, §3.2)
- `common:common-security/.../DeviceProfile.kt` — `supportsDualAutofillSlots`
  becomes a verified check, not `isSamsung()` (§7.3)
- `Vault.kt` — add `VaultEntry.source`; delete reveal-lock dead code (§1.3/§1.4)
- `BackupFormat.kt` — drop HOTP/KEK from payload, bump to v2 (§4.1)
- `GenerateAndFillActivity.kt` — receipt write + target extras (§2.2)
- `PassgenAutofillService.kt` — pass target/kind to generate activity (§2.2)
- `PassgenInputMethodService.kt` — receipt, status line, "type saved entry" btn (§2.2, §6)
- `ImportFormats.kt` — Bitwarden import (§4.2)
- `Settings.kt` — `keepGeneratedValue` (§5.1)
- `FillSavedEntryActivity.kt` — lock after pick, filter master, secure rows (§5.3, §10.1, §10.5)
- `VaultActivity.kt` — new stages (Receipts, Recovery, Export, Restore),
  confirm-delete, empty state, import-confirm, master hidden, IO threading,
  M3 tokens, strings (§3, §4, §8, §9, §10, §11)
- `MainActivity.kt` — autofill status-first, delete CredMgr clause, gated
  dual-slot, auto-clear copy, receipts badge, M3 tokens, strings (§5.2, §7, §11)
- `AndroidManifest.xml` — register `ImeFillActivity`; label → "Understory Keys"
- `themes.xml`, `build.gradle.kts` (label) — §11

**Deleted:**
- `Crypto.generateMasterPassword` (unused)
- `Vault` reveal-lock constants + doc (§1.4)
- Either `PassgenBackupAdapter.kt` OR its dead-ness — one must go (§4.5)

---

## 14. WHAT DOES NOT SHIP (honest scope)

Out of scope by the §0 repositioning (NOT gaps): vault edit, search, folders,
sync, password reveal (threat model forbids), cross-app `BackupProvider` IPC
(v-next), inline autofill suggestion chips (A9, revisit later), ML-KEM PQC
backup layer (BackupFormat v2 comment — deferred, and its copy is removed from
onboarding per CD-4b). The Samsung dual-slot "Additional service" flow ships
ONLY if on-device verification on SM-S948U confirms the settings path; otherwise
it is dropped in favor of keyboard mode (§7.3).
