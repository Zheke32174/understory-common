# Design v2 — SHARED VAULT RECOVERY / EXPORT / RESET

Owning modules: `common-security` (recovery contract, reset flow, Keystore
lifecycle) and `common-backup` (envelope export/import, codecs). Adopted per
app by **passgen, aegis, vault-folder, backups**.

> ## ⛔ THREAT-MODEL INVARIANT — NO RECOVERY SECRET ON SCREEN (corrected 2026-07-03)
> The value that can decrypt a recovery backup (recovery key / recovery
> passphrase) **MUST NEVER be rendered on screen** — not as text, not grouped
> for transcription, not as a QR code, not revealed "just once." The screen is
> the leak (camera / Van-Eck emanation); `FLAG_SECURE` blocks screenshots and
> casting but does **not** stop a nearby camera or electromagnetic capture.
> This overrides any earlier text in this doc that describes "minting a random
> recovery key and rendering it."
>
> **The only sanctioned delivery is a USER-SUPPLIED recovery PASSPHRASE:** the
> user types a passphrase into a masked field (they supply their own secret —
> the accepted password-entry pattern — not the app emanating a stored one).
> That passphrase encrypts the recovery/export file (`AesGcmPassphraseCodec`,
> Argon2id) and is what the user types on restore. Nothing generated is shown.
> `VaultRecovery.recoveryKeyFrom(userChars)` already produces the escrow key
> from a passphrase, so the contract needs no change — only the enrollment UI.
> Consistency rule: encrypt and decrypt with the SAME transform (aegis uses the
> raw passphrase both ways via `VaultImportScreen`; vault-folder uses
> `recoveryKeyFrom` — normalized — both ways). `RecoveryKeyCodec.grouped()` is a
> display formatter and MUST NOT be called; slated for removal.

Scope: the four vault-bearing apps each carry a *clone* of the same v2 vault
engine (SUITE.md §3): a random 32-byte KEK wrapped by an auth-bound Keystore
key (`Crypto.ensureDeviceAuthKey`, `Crypto.kt:144-170`). This document
designs the shared recovery/export/reset pattern that resolves the suite's
sharpest data-loss cliff and its roach-motel export gap:

- **SUITE.md #3** — biometric re-enrollment bricks all four vaults; three of
  four have no reset UI; passgen's unlock screen points at a reset flow that
  does not exist.
- **SUITE.md #2** — no user-reachable export anywhere secrets live; two
  `BackupAdapter`s are dead code; export is absent in passgen (A29) and aegis
  (A-F1) and crashes in vault-folder (A8).
- **SUITE.md §3 consolidation** — four engines diverge; the recovery contract
  is the "consolidate first" item because it is the active data-loss cliff.

This is a DESIGN. No code is changed here. Every item names exact files,
classes, APIs, and the disposition (FIX / REDESIGN / DROP) of each audited
feature. Per-app design docs (`aegis.md`, `passgen.md`, `backups.md`, and the
vault-folder design when written) reference this file as **SHARED** and inherit
it rather than re-cloning; where a per-app doc already names a "new shared
`VaultRecovery` contract" (aegis.md preamble), that contract is defined here.

Changes here are authored in canonical `understory-common/common-security` and
`understory-common/common-backup` and re-vendored byte-identical into each app
(the audit verified vendored copies are byte-identical today; that invariant
must hold after this change). **vault-folder does not currently vendor
common-backup** (SUITE.md §3; vault-folder.md preamble) — adopting export
there requires adding `:common-backup` to its `settings.gradle.kts`.

---

## 0. Disposition table (every audit finding this file owns)

| Audit id | Title | Disposition | Where |
|---|---|---|---|
| SUITE #3 | Re-enrollment bricks all four vaults | FIX (shared) | §2, §3 |
| SUITE #2 | No user-reachable export (roach motel) | FIX (shared) | §5 |
| passgen A19 | "Settings → reset vault" points at nothing | FIX | §4 |
| passgen A27/A28/A29 | Dead `BackupFormat` + dead adapter + no export | REDESIGN→§5 / DROP `BackupFormat` | §5, §7 |
| passgen D1/D2 | Recovery dead-end; no export | FIX | §2,§4,§5 |
| aegis A-F1 | Dead `AegisBackupAdapter`, no export | FIX (wire) | §5 |
| aegis A-U4 | Master-KEK entry[0] un-transcribable, deletable | REDESIGN (drop entry[0]; recovery via §3) | §3, §6 |
| aegis D-L4 / D-M1 | No reset escape hatch; no export | FIX | §4, §5 |
| vault-folder A8 | Export crashes (non-Parcelable in rememberSaveable) | FIX | §5.4 |
| vault-folder A18 | Backups integration claimed, absent | FIX (real hand-off) | §5.5 |
| vault-folder (no reset) | Re-enrollment dead-ends, no delete UI | FIX | §4 |
| backups A1#6/#7 | Recovery key model (the reference implementation) | KEEP, generalize | §3 |
| backups D-4 | Recovery-key escrow optional while re-enrollment bricks | FIX (mandatory escrow) | §3 |
| backups A8 (D-12) / passgen A4 / aegis A-M2 | Clipboard auto-clear claims | FIX (shared honest copy) | §6.3 |
| SUITE §3 | Four engines diverge; consolidate | RECOMMEND (direction only) | §7 |

Nothing here is dropped silently. `BackupFormat.kt` (passgen's dead bespoke
format, A27) IS dropped — it is superseded by the shared `BackupEnvelope`
(§5) — and its UI promise is replaced by the §5 export surface, not left
dangling.

---

## 1. The root cause, stated once

`Crypto.ensureDeviceAuthKey` builds the device-auth Keystore key with:

```
.setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)
.setInvalidatedByBiometricEnrollment(true)   // Crypto.kt:165
```

`setInvalidatedByBiometricEnrollment(true)` means: **the moment the user
enrolls a new fingerprint or face, the Android Keystore permanently and
irreversibly destroys this key.** The wrapped KEK on disk (`header.bin`) can no
longer be unwrapped by anything. Every vault in every app is then permanently
un-openable. This is not a bug to patch away — it is a deliberate,
correct anti-coercion property (a planted fingerprint cannot open the vault).
The honest consequence: **if the only copy of the KEK is the hardware-wrapped
one, the data is unrecoverable after re-enrollment.** No software can undo it.

Therefore the design has two, and only two, honest answers:

1. **Detect** the invalidation precisely and stop dead-ending the user — offer
   a clean **reset** (wipe + reinit) so the app is usable again (§2, §4).
2. **Give the user a second, non-Keystore-bound copy of the recovery
   material — made proactively, before the event** — so "reset" does not mean
   "lose everything." That is the **recovery export** (§3, §5). backups already
   has exactly this (`UnlockedBackupsVault.recoveryChars` + decrypt-with-
   recovery-key, A1#6/#7); the design generalizes it to all four.

We keep `setInvalidatedByBiometricEnrollment(true)`. We do **not** weaken the
Keystore policy to dodge the data loss; we make the user-held copy the
mitigation and make the failure legible.

---

## 2. Invalidated-key detection (SHARED — `common-security`)

### 2.1 New: `VaultKeyState` classifier

New file `common-security/.../VaultRecovery.kt`, `object VaultRecovery`.

Every unlock path today calls `cipher.doFinal(header.wrappedKekCt)` inside the
app's `unlockV2`/`unlock`. When the Keystore key was invalidated, constructing
or using the cipher throws `KeyPermanentlyInvalidatedException` (a subclass of
`InvalidKeyException`); it can surface from `Crypto.deviceAuthCipherForDecrypt`
(`Cipher.init`) or from `promptAuth`/`doFinal`. Today each app catches a
generic `Throwable` and renders "Vault decryption failed." (aegis
`MainActivity.kt:461`, backups `:437-439`, passgen `VaultActivity.kt` unlock
error). That conflates three very different states:

```kotlin
enum class VaultKeyState {
    OK,                    // key present, usable
    NEVER_CREATED,        // no alias — first run / post-reset
    PERMANENTLY_INVALIDATED, // re-enrollment (or lockscreen removal) destroyed it
    TRANSIENT_AUTH_FAILED  // user cancelled / lockout / wrong biometric — retryable
}
```

```kotlin
fun classifyUnlockFailure(t: Throwable): VaultKeyState =
    when {
        t is KeyPermanentlyInvalidatedException ||
            t.cause is KeyPermanentlyInvalidatedException -> PERMANENTLY_INVALIDATED
        t is android.security.keystore.UserNotAuthenticatedException -> TRANSIENT_AUTH_FAILED
        // BiometricPrompt error codes surfaced by the caller:
        else -> TRANSIENT_AUTH_FAILED
    }

fun keyStateAtStartup(ctx: Context, headerExists: Boolean): VaultKeyState =
    when {
        !headerExists -> NEVER_CREATED
        !Crypto.deviceAuthKeyExists() -> PERMANENTLY_INVALIDATED // header but no key = invalidated & swept
        else -> OK
    }
```

`Crypto.deviceAuthKeyExists()` already exists (`Crypto.kt:136`). The
"header on disk but Keystore alias gone" case is the reliable pre-prompt
signal on modern devices: when the key is invalidated, `readDeviceAuthKey()`
returns null (the alias is dropped), so we can classify **before** even
launching BiometricPrompt and skip a doomed prompt. The
`KeyPermanentlyInvalidatedException` catch is the belt-and-braces path for
devices that keep the alias but fail at `doFinal`.

### 2.2 Wiring per app (each app's unlock Activity)

Each app's Unlock screen (aegis `MainActivity.kt:422-482`, backups
`:404-454`, passgen `VaultActivity.kt:422-497`, vault-folder
`MainActivity.kt:439-489`) changes its unlock error handling to:

- On entry, call `VaultRecovery.keyStateAtStartup(ctx, <Vault>.exists(ctx))`.
  - `NEVER_CREATED` → route to Setup (existing behavior).
  - `PERMANENTLY_INVALIDATED` → render the **Recovery screen** (§4), not the
    generic error. Copy: honest, specific — "A fingerprint or face was added
    or your screen lock changed, so Android destroyed this vault's key. The
    encrypted data can no longer be opened on this device. If you saved a
    recovery file, restore it after resetting. Otherwise reset to start fresh."
  - `OK` → proceed to BiometricPrompt.
- In the BiometricPrompt/`doFinal` failure callback, call
  `VaultRecovery.classifyUnlockFailure(t)`; on `PERMANENTLY_INVALIDATED`
  route to the Recovery screen; on `TRANSIENT_AUTH_FAILED` keep the existing
  retry line (do NOT offer reset for a mere cancel — that is the current
  over-broad dead-end's inverse mistake).

No app-specific logic lives in these branches; the strings are shared (§6).

---

## 3. Recovery material NOT bound to the biometric Keystore key (SHARED)

### 3.1 The invariant

Every vault must, at create time, mint a **recovery secret that can reconstruct
the vault contents without the Keystore key** and hand it to the user to hold
off-device. This is the only thing that survives re-enrollment and device loss.

backups already implements the reference shape: the master KEK itself, base64,
is the recovery string; on another device the user pastes it and it becomes the
`AesGcmPassphraseCodec` passphrase over the envelope's own salt
(`BackupsVault.kt:27-35`, A1#6/#7). We generalize **that exact mechanism** and
**delete the divergent, broken attempts** (passgen/aegis `entry[0]`).

### 3.2 KDF + envelope (reuse `common-backup`, no new crypto)

The recovery export IS a `BackupEnvelope` (`BackupEnvelope.kt`) encrypted with
`AesGcmPassphraseCodec` (`AesGcmPassphraseCodec.kt`). No new codec, no new KDF.
Concretely:

- **Passphrase / recovery key**: a freshly generated, human-transferable
  **recovery key** — 32 bytes from `Crypto.randomBytes(32)`, rendered as
  base64-no-pad (~43 chars) **or** as a grouped word/-hyphen form for
  transcription. This recovery key is INDEPENDENT of the vault KEK. (Contrast
  backups today, which reuses the KEK itself as the recovery string — see
  §3.4 for why we split them going forward, while staying compatible.)
- **KDF**: `Crypto.argon2id(recoveryKey.chars, salt)` with the suite-default
  parameters already baked into `AesGcmPassphraseCodec` (Argon2id, 64 MiB, 3
  iterations, parallelism 1, 32-byte output — `Crypto.kt:46-49`). Salt is
  32 fresh bytes per export, carried in the codec's `salt || iv || ct` layout
  (`AesGcmPassphraseCodec.kt:13-19`), and the envelope header JSON is bound as
  AAD (`BackupEnvelope.write`), so metadata tamper fails authenticity.
- **Payload**: the app's cleartext export bytes from its `BackupAdapter.export()`
  (§5.1) — i.e. the same plaintext the vault would round-trip, minus any
  device-bound master entry. The envelope `Header.appId` + `schemaVersion`
  identify which adapter re-applies it on import.

Result: an on-disk file that (a) survives re-enrollment because it is not
Keystore-wrapped, (b) restores on a new device given only the recovery key,
(c) uses one format across all four apps.

### 3.3 Recovery-key escrow is MANDATORY at create (backups D-4 generalized)

The failure today is that the recovery copy is *optional or fictional*:

- backups reveals the recovery key only if the user hunts for the button and
  never forces it (D-4).
- passgen/aegis seal the KEK as `entry[0]` but expose **no reveal path**, so it
  is un-transcribable and useless (passgen A18, aegis A-U4) — pure hazard.

New shared create-time contract (`VaultRecovery.RecoveryEnrollment`), invoked
by each app's Setup flow immediately after `createV2`/`create` succeeds and
BEFORE landing in the vault list:

1. Generate the recovery key (`Crypto.randomBytes(32)`).
2. Build the initial recovery export (empty or seed payload) OR, minimally,
   just persist a **recovery-key verifier** (Argon2id hash of the key stored
   locally, non-secret) so later "make a recovery backup" can prove the same
   key. (The full initial export can be deferred; the KEY must be shown now.)
3. Present a **blocking, FLAG_SECURE reveal step** (reuse backups'
   `RevealScreen` shape, `MainActivity.kt:895-953`): the recovery key on
   screen, "Copy" (with the honest clipboard behavior of §6.3), and a **typed
   confirmation** ("re-enter the last 4 groups" or a checkbox "I saved this —
   it is the ONLY way to recover if I re-enroll biometrics or lose this
   phone"). Setup cannot complete until the user confirms.
4. Wipe the key buffer on dispose (`Crypto.wipe`, as backups does at :960-965).

This replaces the `entry[0]` master-entry convention entirely (see §6.1).

### 3.4 Recovery key vs KEK: compatibility and migration

backups today makes recovery-key == the raw KEK. That is cryptographically
fine (190 bits post-Argon2) but has one weakness: the recovery string and the
live operational key are the same secret, so revealing recovery = revealing the
working key, and there is no way to rotate one without the other.

Design decision: **new vaults mint a distinct recovery key; the recovery
envelope is encrypted under the recovery key, and its payload carries the
adapter cleartext (not the KEK).** This means:

- Recovery restores CONTENTS onto a new vault (new device, new KEK) — the
  natural cross-device story — rather than transplanting a raw KEK.
- The recovery key can be re-generated (rotate) by re-exporting under a new key
  without touching the live vault.

Migration risk / compatibility: backups' existing "decrypt with recovery key"
path (which expects recovery-key == KEK over an envelope) stays supported as a
**legacy recovery codepath** keyed on envelope `schemaVersion`. New exports use
the split-key form. This is a payload-schema concern, not an envelope-format
change, so old `.usbe` files remain parseable. Backups' §5 export adopts the
shared surface; its *reveal recovery key* screen keeps working for
already-created vaults and gains the "make a recovery backup" prompt (§3.5).

### 3.5 "Make a recovery backup" prompt cadence (SHARED)

A recovery key the user saved once, months ago, is worth little if the vault
has grown since. Cadence (implemented as a shared `VaultRecovery.shouldPrompt`
helper reading a per-app timestamp/counter in the app's own storage — apps that
hold no SharedPreferences, like vault-folder, get a tiny dedicated
`recovery_state.bin`):

- **At create**: mandatory (§3.3). Non-skippable.
- **After a material change**: when the vault has gained ≥ N new secrets since
  the last recovery export (N=5 suggested) OR ≥ 30 days elapsed with any
  change, show a **non-blocking banner** on the vault list: "Your recovery
  backup is out of date (12 new items). Update it so you can restore
  everything." Tapping runs the §5 export.
- **On re-enrollment recovery**: after a reset-and-restore, immediately prompt
  to make a fresh recovery backup bound to the new key.
- Never nag more than once per app launch; dismiss persists for the session.

This is copy + a timestamp check; no scheduling, no background work (consistent
with the suite's no-FGS-for-vaults posture and backups D-7's key-policy limits).

---

## 4. Uniform RESET flow (export-first, then wipe + reinit) (SHARED)

### 4.1 The screen: `VaultRecoveryScreen` (composable in `common-security`)

One shared composable, parameterized by an app-supplied `VaultResetHooks`
interface, replaces the four ad-hoc dead-ends. It is reached from (a) the
`PERMANENTLY_INVALIDATED` unlock branch (§2.2), and (b) a "Reset vault" entry in
each app's settings/overflow (which passgen's copy already promises — A19).

State machine:

```
[Explain] --(if key still usable)--> [Export first] --> [Confirm wipe] --> [Wipe+reinit] --> Setup/List
           \--(key invalidated: export impossible)-------> [Confirm wipe] --> [Wipe+reinit]
```

- **Explain**: honest copy about what is lost. Two variants:
  - Key usable (user chose Reset deliberately): "Reset erases every item in
    this vault on this device. If you have a recovery backup file you can
    restore it afterward. Export one now if you haven't."
  - Key invalidated (re-enrollment): "Android destroyed this vault's key when
    biometrics/lock changed. The items cannot be read on this device anymore.
    Reset clears the unreadable data so you can start again. If you saved a
    recovery backup earlier, you'll restore it after reset."
- **Export first** (only offered when key is usable): launches the §5 export.
  Skippable with an explicit "Skip — I understand I'll lose these items"
  secondary action (SecureButton, `filterTouchesWhenObscured`).
- **Confirm wipe**: typed confirmation (type the word `RESET`, or the app
  name), styled as destructive, tap-jack-guarded (`SecureButton` +
  `hasWindowFocus()` check — the pattern vault-folder file-delete already uses,
  vault-folder.md A9).
- **Wipe + reinit**: calls the app's `VaultResetHooks.wipe()`, then routes to
  Setup, which re-creates a fresh vault (new KEK, new Keystore key, new
  mandatory recovery enrollment §3.3).

### 4.2 `VaultResetHooks` per app (exact calls)

```kotlin
interface VaultResetHooks {
    fun exists(ctx: Context): Boolean
    fun exportPayload(unlocked: Any): ByteArray?   // null when key invalidated (can't unlock)
    fun wipe(ctx: Context)                          // delete files + Keystore key
    fun goToSetup()
}
```

Each app's `wipe()`:

- **passgen**: `Vault.delete(ctx)` (make it delete the vault file) +
  `Crypto.deleteDeviceAuthKey()`. Today `Vault.delete` exists but is only used
  for stale-v1 wipe (`VaultActivity.kt:127-129`); expose it here. This fills
  passgen A19's fictional "Settings → reset vault".
- **aegis**: `AegisVault.delete(ctx)` — **already implemented**
  (`AegisVault.kt:52-55`: deletes file + `Crypto.deleteDeviceAuthKey()`) but has
  **zero call sites**. Wire it here (aegis D-L4).
- **vault-folder**: delete all folder dirs. Reset must iterate every folder
  (default + secondaries via `VaultFolders.list`) — each has its own KEK but
  all share the ONE device-auth Keystore key, so `Crypto.deleteDeviceAuthKey()`
  invalidates all of them at once. Reset therefore wipes the whole vault-folder
  root (`ctx.filesDir/vault-folder` + `vault-folder-secondary/`), not one
  folder. This is the correct semantics: re-enrollment already bricked *all*
  folders together.
- **backups**: delete `header.bin` + local snapshots dir +
  `Crypto.deleteDeviceAuthKey()`. NOTE: envelopes already written to SAF/local
  snapshots are **still decryptable with the recovery key** and are NOT touched
  by reset — reset only clears the device-bound header. This is the backups
  value proposition working as intended; the Explain copy says so.

### 4.3 Shared Keystore-key deletion caveat

`Crypto.deleteDeviceAuthKey()` deletes the ONE alias
`passgen_vault_device_auth_v1` (`Crypto.kt:40`) — which is shared-by-name across
all apps but lives in each app's own Keystore (per-app sandbox), so deleting it
in app X does not touch app Y. Confirmed: each app has its own AndroidKeyStore.
No cross-app interference. (This alias name is a cosmetic misnomer in aegis/
vault-folder/backups — noted for the §7 consolidation, not a bug.)

---

## 5. Uniform EXPORT / IMPORT surface (SHARED)

### 5.1 Envelope always; the two dead adapters wired

Every vault app exposes an **Export** and an **Import** action. The at-rest
format is ALWAYS the encrypted `BackupEnvelope` + `AesGcmPassphraseCodec`
(§3.2). Plaintext / incumbent-interop output (Bitwarden CSV, otpauth URIs,
plain files) is offered ONLY behind an explicit second confirmation (§5.3).

The per-app `BackupAdapter` is the single source of WHAT to export/import:

- **passgen**: `PassgenBackupAdapter` — **complete, unit-tested, wired to
  nothing** (A28). Wire it. It already filters the master `entry[0]` both ways.
- **aegis**: `AegisBackupAdapter` — **complete, wired to nothing** (A-F1).
  Wire it.
- **vault-folder**: **no adapter exists** and no common-backup module. Add
  `:common-backup` to `settings.gradle.kts` and add a `VaultFolderBackupAdapter`
  whose `export()` emits a manifest + the per-blob ciphertext bytes (or, for a
  portable export, re-encrypts decrypted blobs into the envelope payload). This
  is the largest per-app lift; see §5.5.
- **backups**: it IS the envelope tool already (`BackupsFlow` +
  `AesGcmPassphraseCodec`). Its "export" of its own vault is the reveal-recovery
  + decrypt-with-recovery-key pair (A1#6/#7); it adopts the shared **prompt
  cadence** (§3.5) and the shared reset (§4).

DROP: passgen's bespoke `BackupFormat.kt` (A27) — superseded by
`BackupEnvelope`. Its never-built HOTP-`entry[1]` prerequisite dies with it.

### 5.2 Export UI (shared composable `VaultExportScreen`)

Reached from vault list overflow and from the §3.5 prompt and the §4
export-first step. Flow:

1. Biometric unlock (if not already unlocked in this session).
2. Choose destination via SAF `CreateDocument` (`ActivityResultContracts
   .CreateDocument("application/octet-stream")`), default filename
   `understory-<app>-YYYYMMDD.usbe`.
3. Off the main thread (`Dispatchers.IO`, shared with SUITE.md #8): build
   payload = `adapter.export()`; build `BackupEnvelope.Header(appId,
   schemaVersion, now, userLabel, codecParams)`; `BackupEnvelope.write(out,
   AesGcmPassphraseCodec, header, payload, PassphraseKey(recoveryKeyChars))`.
4. The passphrase is the vault's **recovery key** (§3), NOT the raw KEK — so the
   exported file is restorable after re-enrollment / on a new device with the
   recovery key the user holds.
5. Progress + result states are real (they can render because the work is off
   the main thread — fixes the "unreachable loading state" GUI finding across
   all four apps). Wipe payload + key in `finally`.

### 5.3 Plaintext / incumbent-interop export (explicit confirm only)

CD-3 (SUITE.md §2) requires two-directional incumbent interop. Encrypted
envelope is the default and the recommended path. A secondary "Export for
another app (unencrypted)" action, behind a distinct destructive-styled
confirmation ("This writes your secrets UNENCRYPTED so <incumbent> can read
them. Anyone with the file can read them. Continue?"), produces:

- **passgen**: Bitwarden CSV + Bitwarden JSON + Google CSV (the formats it
  already imports, made two-directional — passgen D5).
- **aegis**: `otpauth://`-per-line text + optional QR render; and Aegis-JSON
  (aegis D-M1). `OtpAuthEntry.toUri` already exists (aegis A-U5, currently dead
  code) — reuse it.
- **vault-folder**: plain files via SAF (already its export model — it decrypts
  to a user-chosen destination; that IS the plaintext interop, vault-folder
  A8). Encrypted-envelope export is the NEW default addition.
- **backups**: n/a (its payloads are already opaque envelopes; "plaintext" is
  the decrypt-to-SAF it already does).

These feed backups' collector and the incumbent-interop formats named in CD-3.

### 5.4 Import (shared composable `VaultImportScreen`)

1. SAF `OpenDocument`. 2. `BackupEnvelope.parse(input)` — reject non-`USBE`
files (magic check already in `parse`, `BackupEnvelope.kt:99`). 3. If
`header.appId` != this app: honest error ("This backup is for Understory
<other>"). 4. Prompt for the recovery key. 5. Off-thread
`BackupEnvelope.decryptPayload(parsed, AesGcmPassphraseCodec,
PassphraseKey(key))` — GCM auth failure => "Wrong recovery key or corrupted
file." 6. `adapter.import(payload, header.schemaVersion)` — merge-dedup,
returns the human summary the adapters already produce. 7. Show a **parsed
summary + explicit confirm BEFORE merge** (fixes the auto-import-without-
confirmation contract violations: passgen A26/D4, vault-folder A7/D3).

**vault-folder export crash fix (A8):** the shared `VaultExportScreen` never
puts a non-Parcelable domain object in `rememberSaveable`. It saves only the
selected entry **id string** and re-resolves against the live store on return
(vault-folder.md D1). The shared composable makes this the only pattern, so the
bug cannot re-clone.

### 5.5 vault-folder specifics + backups hand-off (A18)

vault-folder's `export()` for the encrypted-envelope path: decrypt each blob to
memory (20 MiB cap makes this bounded, vault-folder.md A5), assemble a payload
of `{manifest, blobs[]}`, hand to the envelope. Import reverses it into fresh
per-blob GCM under the new vault KEK. For the **backups collector** (SUITE.md
#2 orchestration is v2), the near-term honest hand-off is a one-tap "Send
encrypted copy to Backups" = `ACTION_VIEW`/`ACTION_SEND` of the produced
`.usbe` into `com.understory.backups` (vault-folder.md B.4). This makes A18's
promise true with the deposit mechanism that already exists on the receiving
side — no new IPC, and it does NOT require the signature-locked `BackupProvider`
that the full orchestrator (SUITE.md #2, backups A22) defers to v2.

---

## 6. Copy, entry[0] removal, and clipboard honesty (SHARED)

### 6.1 Delete the `entry[0]` master convention (aegis A-U4, passgen A18/rank 9)

Two apps seal the KEK as a visible, deletable, un-revealable list entry that
leaks into user lists and pickers and delivers zero recovery value. **Remove
it.** Recovery is now the §3 recovery key + §5 export.

- passgen `Vault.createV2` (`Vault.kt:217-244`): stop building `masterEntry`;
  create the vault with `entries = emptyList()`. Removes it from the list, the
  autofill picker, and the delete path in one change.
- aegis `AegisVault.createV2` (`AegisVault.kt:78-113`): same — no `masterEntry`.
- Migration risk: existing installs have `entry[0]` on disk. On first unlock
  after upgrade, a one-time sweep drops the marker entry
  (`title == MASTER_ENTRY_TITLE` / `issuer=="aegis" && account=="vault master
  key"`). Since it was never usable, dropping it loses nothing. This runs in the
  same unlock that would otherwise render it.

### 6.2 Shared honest recovery copy

All recovery/reset/export strings live in `common-security` string resources
(the suite is moving copy into resources anyway — SUITE.md #9). One truthful
vocabulary, shared by all four apps, so the "aegis says X, passgen says Y"
divergence (SUITE.md §3) cannot recur. Rewrites:
passgen A18 (fictional 10s reveal — delete), passgen A19 (fictional reset —
now real, §4), aegis A-M1 (fictional recovery entry — replaced by §3 copy).

### 6.3 Clipboard auto-clear honesty (passgen A4, aegis A-M2, backups A8/D-12)

The recovery-key "Copy" button appears in three places (reveal at create,
reveal-recovery, export fallback). All three today either lie about a 30s clear
(backups A8: no clear happens) or make a process-scoped promise that dies on
swipe-away (passgen A4). Shared rule for `common-security/Clipboard.kt`:

- The clipboard entry sets `EXTRA_IS_SENSITIVE` (already done).
- If a best-effort process-scoped clear is scheduled, the toast says exactly
  that: "Copied. Cleared when you leave this screen." — never a fixed-seconds
  promise the code can't keep across process death.
- For the recovery KEY specifically (a long-lived ultimate secret), prefer
  **not** offering clipboard at all where a SAF "Save to file" export exists;
  if offered, the warning is explicit that clipboard managers may retain it.

---

## 7. Consolidation recommendation (DIRECTION ONLY — do not implement here)

SUITE.md §3 already sets the target: promote a single `DeviceAuthVault` engine
into `common-security`. This design deliberately lands the **recovery contract
first** (SUITE.md's stated order of merit: "recovery contract first — it is the
active data-loss cliff; file engine second; orchestration IPC last") because it
is achievable without merging the four file formats.

What this file consolidates now (safe, high-value, low-blast-radius):
- One `VaultRecovery` classifier + `VaultRecoveryScreen` + `VaultExportScreen`/
  `VaultImportScreen` + reset flow, in `common-security`, adopted via thin
  per-app `VaultResetHooks` + the existing `BackupAdapter`.
- One export format (`BackupEnvelope`), one codec (`AesGcmPassphraseCodec`),
  one recovery-key mechanism.

What is DEFERRED to a later consolidation pass (flagged, with migration risk):
- **Merge the four `*Vault` engines** into one `DeviceAuthVault` (version,
  header caps, AAD binding of blob-id/context, atomic replace, tmp sweep, one
  KEK-wrap/unlock/lifecycle-lock manager) with per-app payload schemas on top.
  Migration risk: HIGH — it touches the on-disk header of live vaults in all
  four apps; must be a versioned format with a read-old/write-new upgrade path
  and cannot ship in the same release as the recovery work (too much at-rest
  churn at once). Do it after the recovery/export surface is stable and each
  app has a *working export*, so any migration bug has a user-held escape hatch
  (the recovery backup) that did not exist before this design.
- **Rename the shared Keystore alias** `passgen_vault_device_auth_v1` to a
  neutral name — cosmetic, but do it inside the format-merge (renaming an alias
  in place is itself a migration: old alias must be read, KEK re-wrapped under
  new alias, old deleted — non-trivial, batch with the engine merge).
- **AAD binding** (vault-folder A17, aegis unused-param): fold into the merged
  engine's format-version bump; do it while the format is cheap to break.
- **Cross-app `BackupProvider` orchestration IPC** (SUITE.md #2, backups A22):
  last. §5.5's intent hand-off is the v1 stand-in.

Net: after this design, all four apps detect the invalidated key honestly, all
four have a real reset, all four have a real user-held recovery backup in one
format, and no vault can silently become a permanent data-loss trap — with the
engine merge sequenced safely behind that safety net.
```
