# Design v2 — understory-backups ("backups" app)

Status: DESIGN (implementable). Resolves every finding in
`docs/audit-v2/backups.md` and the backups-relevant rows of `SUITE.md`.
Design-only: no code is modified by this document. File:line references
are to the audited (v1) tree.

Companion inputs assumed present but NOT specified here (they are
suite-wide and belong in the common-security consolidation, SUITE.md §3/§9):
- shared M3 theme tokens + `UnderstoryScaffold`/`UnderstoryTopAppBar` in
  `common-security` (this doc consumes them; it does not define the palette).
- the shared recovery contract (mandatory escrow +
  `KeyPermanentlyInvalidatedException` re-bind). backups already owns the
  reference implementation of the recovery *mechanism*; this doc specifies
  only the backups-side UI/flow that the contract requires.

---

## 0. Identity decision (resolves A-22/23, D-6, E)

**backups v2 ships as: "Understory Backup — encrypted-envelope tool +
suite export collector."** It is the encryption layer that sits *under*
whatever replication the user already trusts (Syncthing / a USB-OTG
stick / Google Drive folder), plus a collector that other suite apps
hand their own encrypted exports to.

The full "orchestrator that reaches into every peer's vault" identity is
**staged, not dropped**. We commit to the real cross-app mechanism — a
**deposit-intent hand-off** (§3) — because it is genuinely lighter than a
signature-locked `BackupProvider` ContentProvider per app (no per-app
provider, no read-permission plumbing, no cross-process cursor streaming
of secret bytes; a peer only has to answer one `startActivityForResult`),
and it keeps the north-star orchestrator identity reachable. But at v2.0
the beacon and all UI shrink to what code does:

- **Capability beacon**: `SuiteCapsProvider` v1 must NOT resolve to
  `BACKUP_ORCHESTRATOR`. Change `SuiteCapabilityRegistry` (common-security)
  so `(com.understory.backups, 1)` maps to a new honest capability
  `ENVELOPE_TOOL` (an app that can encrypt/decrypt suite envelopes and
  *accept* deposits). Re-introduce `BACKUP_ORCHESTRATOR` at beacon
  version 2 only once ≥1 peer implements the deposit-intent responder
  (§3.4). This is the D-6 honesty fix; the "real thing" is §3.
- **README / roadmap**: reword to the sentence above. Drop "schedules and
  runs encrypted exports" (replaced by the honest scheduling in §4), drop
  "self-hosted endpoint" (replaced by the SAF/Syncthing complement, §5).

Recommendation: **build the deposit-intent mechanism** (§3) rather than
permanently dropping orchestration — but gate the *claim* behind the
*code*, per CD-4.

---

## 1. Screen map (v2)

`MaterialTheme` from common-security tokens; every screen is an
`UnderstoryScaffold { UnderstoryTopAppBar(title, onBack) }` so there is
always a real Back affordance (resolves the D-11 lost-Back-button class
structurally: content is the scaffold body with its own scroll, the top
bar never scrolls off). All crypto/IO runs off-main (§7).

| Route | Screen | Purpose |
|---|---|---|
| `setup` | **Setup** | First-run vault create + **mandatory recovery-key escrow** |
| `unlock` | **Unlock** | Biometric/credential unlock; **re-bind path** on invalidation |
| `home` | **Home** | Hub: Encrypt, Decrypt/Restore, Snapshots, Reveal recovery key, Diagnostics; complement cards; `SuiteStatusFooter` |
| `encrypt` | **Encrypt a file** | SAF file → `.usbe` envelope (SAF out or local snapshot) |
| `restore` | **Decrypt / Restore** | one screen, **format-detecting**: `.usbe` envelope, `.usbs` content stream, device-snapshot bundle; vault-key or recovery-key |
| `snapshots` | **Local snapshots** | list / restore-to-SAF / delete (confirm) / retention |
| `deviceSnapshot` | **Device snapshot** | configure + run (manual now; schedule opt-in) |
| `collect` | **Collect suite exports** | receives deposits from peer apps (§3) |
| `diagnostics` | shared | unchanged |

Navigation: single-activity Compose NavHost keyed by an in-memory
`Screen` state (keep the existing lock-on-leave lifecycle wiring,
`MainActivity.kt:137-214`, unchanged — it is WORKING).

---

## 2. RESTORE / IMPORT (resolves D-2, the "no decoder exists" blocker)

A backup with no restore is not a backup. v2 makes **restore the peer of
every write path.** One screen (`restore`) auto-detects the input by
sniffing the leading magic bytes, so the user never has to know which
format a file is.

### 2.1 Format detection

Read the first 8 bytes of the SAF-picked (or local) input:

| Leading bytes | Format | Decoder |
|---|---|---|
| `55 53 42 45` = `USBE` | single-shot envelope (`.usbe`) | `BackupEnvelope.parse` + `AesGcmPassphraseCodec` (already exists — `BackupsFlow.decryptFromEnvelope`) |
| `55 53 54 52 53 54 52 4D` = `USTRSTRM` | streaming stream (`.usbs`) | **new** `UserDirsContentRestore` (§2.3) over `StreamingAesGcmCodec.decrypt` |

Add `BackupsFlow.sniff(ctx, uri): InputFormat` — peek 8 bytes via a
`BufferedInputStream(markSupported)` (mark/reset), classify, then dispatch.
Envelopes whose header `appId ==
"com.understory.backups.device-snapshot"` are further routed to the
**device-snapshot unpacker** (§2.2) instead of raw-plaintext-out, because
their plaintext is a JSON bundle, not a user file.

### 2.2 Device-snapshot bundle restore

The device-snapshot `.usbe` decrypts to the `device-snapshot.v1` JSON
(`DeviceSnapshotService.kt:138-195`). Restore does not "restore a device"
(no re-apply path exists for Settings, and creating one would need
`WRITE_SETTINGS`/`WRITE_SECURE_SETTINGS` — out of scope and doctrine-heavy).
Instead it **renders the bundle as an inspectable, exportable report**:

- Android settings section → a readable key/value list + "Export as JSON"
  (SAF `CreateDocument`). Honest label: "captured settings — reference
  only, not re-applied" (matches audit A-15).
- user-dirs manifest section → a file tree (path, size, mtime, first-64KiB
  SHA-256) + "Export manifest JSON". This is the reconciliation index for
  §2.3.
- stub sections (`suite_app_vaults`, `vault_folder_secure_files`) → shown
  only if `status != "pending"`; after §3 lands they carry real deposited
  blobs and get a "Save each export" action (§3.3).

### 2.3 Content-stream restore (`.usbs` → files on a SAF tree)

New object `UserDirsContentRestore` mirrors `UserDirsContentStream` in
reverse. `StreamingAesGcmCodec.decrypt(ciphertext, pipeOut, passphrase)`
already exists and is unit-tested; the missing half is the **UDCS framing
unpacker** that consumes the decrypted byte stream and writes files.

Flow:
1. User picks the `.usbs` (SAF) and a **destination SAF tree**
   (`OpenDocumentTree`, persisted grant). Optionally picks the companion
   manifest JSON (from §2.2) for verification.
2. Passphrase = vault KEK (if unlocked) or recovery key (entered).
3. `StreamingAesGcmCodec.decrypt` writes into a `PipedOutputStream`; a
   reader thread runs the UDCS unpacker on the `PipedInputStream`. The
   codec verifies every chunk's GCM tag and the final-flag, so a
   truncated/tampered stream aborts before the unpacker emits the affected
   file. The returned `externalAad` is compared to the expected
   `streamAad(header)` when the parent envelope is available — mismatch =
   "this .usbs does not belong to that snapshot," abort.
4. UDCS unpacker per entry: read 8-byte magic (`UDCSv001`) once; then
   loop {4-byte path_len, path bytes, 8-byte content_len, then stream
   exactly content_len bytes into a `DocumentFile.createFile` under the
   destination tree, recreating the `Section/rel/path` subtree via
   `createDirectory`}. **Bounds:** reject path_len > 16 KiB, content_len
   > 2^62, and any path containing `..` or absolute segments (path-
   traversal guard — the writer never emits these but the reader must not
   trust that).
5. Per-file result (written / skipped / length-mismatch) accumulates into
   a restore report shown at the end; if a manifest was supplied, each
   file's first-64KiB SHA-256 is checked and divergences flagged.

This is the D-2 restore tool. It depends on D-3 (framing) being fixed
first — see §2.4.

### 2.4 Framing fix (resolves D-3: corruption on live-file change)

Root cause: the writer emits a `content_length` from `file.length()` at
walk time, then lazily streams the file later; if the file shrank/grew or
vanished, `SequenceInputStream` feeds the wrong number of bytes and every
subsequent frame boundary slides. Length-prefixed framing cannot tolerate
this (`UserDirsContentStream.kt:186-191` comment is wrong), and there was
no decoder to even try.

**Redesign — self-delimiting block framing (UDCSv002).** Replace the
single `content_length` header with a chunked-body encoding that does not
depend on a length promised before the bytes are read:

Per entry:
```
  4B  path_len (BE, ≤16KiB)
  N   path bytes (UTF-8, NFC, section-prefixed)
  1B  entry_start marker 0x01            # resync anchor
  then a sequence of body blocks, each:
     4B  block_len (BE; 0x00000000 = end-of-entry sentinel)
     block_len bytes of file data
  after the 0-length terminator:
  8B  actual_bytes_written (BE)          # trailer, authoritative
```
The writer streams the file in ≤1 MiB blocks, emitting each block's real
length as it reads, and writes the terminator + true total when the file
ends (whatever the size turned out to be). No pre-committed length can be
wrong because there is no pre-committed length. The unpacker reads blocks
until the 0-length sentinel, so a file that shrank simply ends early and
cleanly; a file that grew is fully captured. `entry_start` (0x01) is a
resync magic the unpacker asserts before each path — if it is ever not
seen where expected, the archive is structurally corrupt and the unpacker
aborts with a precise offset (this is the "must tolerate" that framing can
actually deliver: detect, don't silently slide).

`MAGIC` bumps to `UDCSv002` so a v001 stream (if any exists) is rejected
rather than half-parsed. The whole UDCS payload remains wrapped by
`StreamingAesGcmCodec`, so block lengths are themselves authenticated —
an attacker cannot forge a resync marker without the key.

Disposition of the `LazyFileInputStream` shrink comment
(`UserDirsContentStream.kt:186-191`): **DELETE**; replaced by the trailer
+ sentinel above.

---

## 3. Cross-app orchestration: the deposit-intent contract (resolves A-22, D-6 "real thing")

Chosen mechanism: **each peer exports its own encrypted blob and hands it
to backups via a shared export-intent.** backups never reads a peer's
vault; the peer does its own in-process `BackupAdapter.export()` (those
adapters already exist and are unit-tested — SUITE.md §3 — they are just
wired to nothing), wraps the bytes in a `BackupEnvelope` under the peer's
own vault key, and deposits the envelope. This keeps every app's secrets
inside its own sandbox and its own key.

### 3.1 The intent contract (spec)

Action + extras, defined once in `common-security` as
`SuiteBackupContract`:

```
Action:  com.understory.suite.action.DEPOSIT_BACKUP
Category: DEFAULT
Type:    application/octet-stream            # the .usbe envelope
Extras (from responder → backups, via result Intent):
  EXTRA_SUITE_APP_ID     String  reverse-DNS, e.g. com.understory.aegis
  EXTRA_SUITE_SCHEMA     Int     payload schema version
  EXTRA_ENVELOPE_URI     Uri     content:// FileProvider URI, read-grant
                                  flagged FLAG_GRANT_READ_URI_PERMISSION
  EXTRA_LABEL            String  human label (e.g. "aegis vault 2026-07")
Constraint: responder Activity/Provider is protected by the existing
  signature permission com.understory.suite.CAPS so only same-cert
  suite apps can invoke or answer.
```

Two directions, both signature-gated:

- **Pull (backups initiates):** backups' `collect` screen enumerates peers
  from `SuiteCapabilityRegistry` that advertise `BACKUP_EXPORTER` (new
  capability, §3.4), and fires `startActivityForResult` with
  `ACTION_DEPOSIT_BACKUP` targeted per-package. Each peer shows a
  one-tap "Export to Understory Backup?" confirm (its own biometric
  unlock), runs `export()`, writes the envelope to its own
  `cacheDir`/exports via `FileProvider`, and returns the URI. backups
  copies the bytes in (read grant) to its own store, then the peer clears
  its cache.
- **Push (peer initiates):** a peer's own "Back up to suite" button fires
  `ACTION_DEPOSIT_BACKUP` as a plain `startActivity`; backups' `collect`
  screen is the responder, ingests the deposited URI.

Why intent over a `BackupProvider` ContentProvider: no per-app provider
authority, no read-permission surface held open, no streaming a cursor of
secret bytes across processes; the secret bytes live in a short-lived
`FileProvider` URI with a single-consumer read grant that is revoked on
return. Lighter to build in each peer (one Activity result), which is the
whole reason to prefer it.

### 3.2 What backups does with a deposit

Store the received envelope verbatim in `filesDir/collected/` (private,
UID-scoped) keyed `<appId>-<UTC-ISO>.usbe`. backups **cannot decrypt** it
(it is under the peer's key) — it is an opaque escrow blob. The Snapshots
list shows collected envelopes with their `appId`/label from the (public)
envelope header. On device-snapshot runs, collected envelopes are copied
into the SAF destination alongside the device bundle, so one snapshot run
carries the whole suite's exports.

### 3.3 Restore of a deposited peer blob

backups' restore screen, on a collected/foreign `.usbe`, offers "Send back
to <app>" — fires `ACTION_IMPORT_BACKUP` (mirror action) at the owning
peer with the envelope URI; the peer decrypts under its own key and
imports. backups never sees the plaintext. (Peer-side import is the peer's
design doc, not this one.)

### 3.4 Capability staging

- v2.0: backups beacon v1 → `ENVELOPE_TOOL` only. `collect` screen shows
  "No suite apps expose an export yet" if no peer advertises
  `BACKUP_EXPORTER`. Zero dead UI: the Collect entry on Home is
  **disabled-with-reason** until a peer responds to a probe intent.
- When ≥1 peer ships the responder + advertises `BACKUP_EXPORTER`: bump
  backups beacon to v2, add `BACKUP_ORCHESTRATOR` back to the registry
  map, enable the Collect screen. This is the honest re-introduction path
  for the capability the audit flagged.

---

## 4. Scheduling (resolves A-24, D-7)

Scheduling is impossible today because the master KEK is wrapped under a
Keystore key with `setUserAuthenticationParameters(0, …)` — auth required
for *every* operation (`Crypto.kt:155-162`), so a background run can never
unlock. v2 makes scheduling viable **for snapshot-metadata only**, with a
second, non-auth-bound key, and keeps every path that touches vault
secrets or restore strictly biometric.

### 4.1 A second key: the snapshot-only key (SOK)

Add to `common-security/Crypto.kt` a parallel keypath:

```
alias:  understory.snapshot.v1
spec:   AES-256-GCM, StrongBox if available,
        setUserAuthenticationRequired(false)      # NOT auth-bound
        setInvalidatedByBiometricEnrollment(false)
```

The SOK wraps a **snapshot-only key** used *exclusively* to encrypt
device-snapshot **metadata** (Android settings capture + user-dirs
manifest + user-dir content stream). It never wraps the vault KEK, never
touches collected peer blobs, and is never usable to decrypt a
recovery-key envelope. Scheduled snapshots therefore capture
device/file state without any secret from the biometric vault.

Honest boundary, shown at opt-in and enforced in code:
> "Scheduled snapshots run without unlocking your vault. They can back up
> your files and settings, but **not** your Understory vault contents.
> Restoring anything — and any suite-app export — always requires your
> biometric."

Suite-app / vault-folder sections are **structurally excluded** from
scheduled runs (they need the peer's biometric-gated `export()`, §3, which
cannot run unattended). The scheduled config is a strict subset:
`includeAndroidSettings`, `includeStandardUserDirs`,
`includeUserDirContent` only.

### 4.2 WorkManager

- Add WorkManager. `SnapshotWorker : CoroutineWorker` runs the same
  collectors as `DeviceSnapshotService` but keyed to the SOK path and the
  restricted config.
- Periodic: `PeriodicWorkRequest` (min 15-min floor; realistic default
  daily) with constraints `requiresCharging` + `requiresDeviceIdle`
  (opt-in toggles) and `requiresStorageNotLow`.
- Destination MUST be a persisted SAF tree grant (§5) — a scheduled run
  cannot prompt for one.
- Boot survival: WorkManager persists its own jobs across reboot; **no**
  `RECEIVE_BOOT_COMPLETED` / `WAKE_LOCK` needed (WorkManager's JobScheduler
  backing re-arms after boot). Do not add those permissions.
- FGS interplay: a scheduled `.usbs` content stream can be large; the
  worker promotes itself via `setForeground` (dataSync) for the streaming
  phase, reusing the notification channel (§6).

### 4.3 UI

Device-snapshot screen gains a "Schedule" section: off by default; a
frequency picker (Daily / Weekly / Off); the honest-boundary banner above;
and a "Last scheduled run: <ts> — <result>" line read from a persisted
receipt. If no persisted SAF destination exists, the Schedule toggle is
disabled-with-reason ("Pick a backup folder first").

Alternative if implementer defers this: **drop scheduling from README/
roadmap for v2** and ship manual-only (the FGS path already works). But
the SOK design above is the recommended, viable form and is a bounded add.

---

## 5. Scoped storage + destinations (resolves A-16, D-8; drops self-hosted, D-27)

### 5.1 Backup *sources* move to SAF tree grants

The raw `File` walk of `Environment.getExternalStoragePublicDirectory(…)`
(`UserDirsContentStream.kt:104`, `UserDirsManifestCollector.kt`) sees only
media on minSdk 33 (`READ_MEDIA_*`); non-media files other apps wrote to
`Documents/`/`Downloads/` are invisible. This is a silent partial backup —
the worst kind for a backup app.

Redesign: **the user grants SAF read trees for what to back up.**

- Device-snapshot config gains "Choose folders to back up"
  (`OpenDocumentTree`, persisted read grants; store the URI list in
  `DeviceSnapshotConfig`). Both the manifest collector and the content
  stream walk `DocumentFile` trees over those grants instead of raw
  `File`. `DocumentFile.listFiles()` + `openInputStream` is the rootless-
  correct arbitrary-file mechanism and is not media-filtered.
- Keep a convenience "Add media libraries" that maps to the existing
  `READ_MEDIA_*` path for Pictures/DCIM/Music/Movies (media APIs are
  simpler and complete for those dirs) — but label it exactly:
  "Photos, videos, music (media library)."
- **Explicit backupable scope, shown in-UI** (resolves the A-16
  over-claim): a per-source line stating coverage. Media-library source:
  "all photos/videos/audio." SAF-tree source: "every file in this folder."
  No blanket "Documents/Downloads" promise unless a tree grant covers it.

`UserDirsManifestCollector` and `UserDirsContentStream` are refactored to
take a `List<DocumentFile roots>` (media libraries expressed as their own
`DocumentFile` roots via `MediaStore` where needed). The `STANDARD_DIRS`
raw-path list is deleted.

### 5.2 Destinations

- **USB / any SAF location**: already works via `OpenDocumentTree`
  (A-25). No USB-specific code; framed honestly as "any location you can
  pick, including a USB-OTG drive."
- **Syncthing folder (the headline complement, D-16)**: a Home card and a
  device-snapshot hint: "Point the backup folder at a Syncthing-synced
  directory to get encrypted, versioned, off-device backup with no network
  permission in this app." Pure SAF; zero code beyond the copy + a docs
  paragraph.
- **Self-hosted network endpoint: DROPPED** (A-27). Contradicts the
  no-INTERNET posture (`AndroidManifest.xml:53`,
  `network_security_config.xml`). Remove the claim from README; the honest
  form is the SAF/Syncthing folder above (an external tool does the
  network, backups only writes an encrypted file).

---

## 6. Notifications (resolves A-14, D-1)

The manifest declares `POST_NOTIFICATIONS` at line 43 then strips it at
line 125 (`tools:node="remove"`), and the app never runtime-requests it,
so on minSdk 33+ every `nm.notify` is silently dropped while the UI tells
users to "watch the foreground notification" (`DeviceSnapshotConfigScreen
.kt:267-268`).

Fix:
- **Delete `AndroidManifest.xml:125`** (the strip). Keep the declaration
  at line 43.
- **Runtime request**: on the device-snapshot screen, before launching the
  FGS (and before enabling Schedule), request `POST_NOTIFICATIONS` via the
  Activity Result API. On minSdk 33 this is always a runtime prompt.
- **Degrade honestly**: if denied, the FGS/worker still runs; the screen
  shows an in-app inline progress row (bound to the same state the
  notification would show), and the copy changes to "Notifications off —
  progress shows here while this screen is open." No dead "watch the
  notification" instruction anywhere.
- Replace the placeholder `android.R.drawable.ic_lock_lock` small icon
  (`DeviceSnapshotService.kt:345`) with an app-owned monochrome vector
  (D-18).

---

## 7. Off-main-thread crypto + real loading states (resolves D-5, C loading)

Argon2id (64 MiB × 3) + AES currently runs synchronously in click handlers
(`MainActivity.kt:641-660, 770-783, 867-881`; `LocalSnapshotsScreen.kt:68-82`),
so "Encrypting…"/"Decrypting…" can never render and One UI freezes (ANR-
class). Per `SAMSUNG_QUIRKS.md:119-129` (>100 ms ⇒ `Dispatchers.IO`):

- Every `BackupsFlow` call and every restore/unpack op is launched from
  `rememberCoroutineScope().launch(Dispatchers.IO)`; the screen holds a
  `sealed UiState { Idle, Working(label), Success(msg), Failure(msg) }`.
- The composable renders `Working` with a determinate/indeterminate
  progress + label; results flip state back on the main dispatcher.
- Streaming restore/encrypt report per-file progress into `Working` via a
  callback → `MutableStateFlow`.

---

## 8. Setup / Unlock / recovery (resolves A-8, D-4, D-12; Unlock re-bind)

### 8.1 Mandatory recovery-key escrow at Setup (D-4)

Adding a fingerprint destroys the wrap key
(`Crypto.kt:165 setInvalidatedByBiometricEnrollment(true)`); after that,
**every** envelope is decryptable only via the recovery key — which Setup
never forces the user to record. For a backup app that is a data-loss
trap. v2 Setup becomes a required 2-step:

1. Create vault (unchanged crypto).
2. **Reveal-and-confirm recovery key** (FLAG_SECURE, wipe-on-dispose):
   show the base64 KEK (`UnlockedBackupsVault.recoveryChars()`), require
   the user to either re-enter a short check (e.g. last 6 chars) or tap an
   explicit "I have saved this — without it my backups are unrecoverable"
   confirm. Setup does not complete until this step passes. Offer "copy"
   and "save to file" (SAF) here.

### 8.2 Unlock re-bind on invalidation

`KeyPermanentlyInvalidatedException` at unlock currently surfaces as a bare
"Vault decryption failed." (`MainActivity.kt:437-439`). v2 catches it
specifically and routes to a **re-bind flow**: explain "your biometric
enrollment changed, which reset this device's key," then let the user
re-create the wrap key and re-establish the vault **using the recovery
key** (decrypt-with-recovery-key already exists, A-6). Envelopes remain
decryptable throughout because they key off the recovery string, not the
Keystore wrap.

### 8.3 Clipboard honesty (A-8, D-12)

The "auto-clears in 30s" toast (`MainActivity.kt:941-945`) is false — no
code clears the clipboard; the app only sets `EXTRA_IS_SENSITIVE`. Fix:
**implement it** — after `setPrimaryClip`, `Handler.postDelayed(30_000)`
→ if the current primary clip is still ours (identity check on a nonce we
placed), `clearPrimaryClip()` (API 28+); on process death the OS
`EXTRA_IS_SENSITIVE` hint remains as the fallback. Toast reworded to:
"Copied. Cleared from clipboard in 30s if not overwritten." (matches
CD-4(e): claims match actual guarantees, including process-death).

---

## 9. Local snapshots screen (resolves D-11, D-14, D-15 rotate)

- **Layout (D-11):** `LazyColumn` gets `Modifier.weight(1f)` inside the
  scaffold body; the status line + Back live in the top bar / a pinned
  footer, so they can never be pushed off-screen
  (`LocalSnapshotsScreen.kt:84-118`).
- **Delete confirm (D-14):** Delete opens an `AlertDialog`
  ("Delete this snapshot? This cannot be undone."); the confirm button is
  the `Secure*` wrapper per SAMSUNG_QUIRKS. No one-tap destructive action.
- **Retention (D-15 / A-10):** wire the dead `LocalSnapshotStore.rotate`
  (`:121-126`) to a "Keep last N" setting (Off / 5 / 10 / 20), applied
  after each successful local snapshot write and each scheduled run. This
  turns dead code into the advertised retention.

---

## 10. Device-snapshot config screen (resolves D-13, D-17, plus §5/§6)

- **Stub sections default OFF (D-13):** `DeviceSnapshotConfig`
  `includeSuiteAppVaults` and `includeVaultFolderSecureFiles` default
  `false` (`DeviceSnapshotConfig.kt:83,85`). Until §3 lands they are
  hidden entirely (no dead toggle); when §3 lands they become the
  deposit-collect entry (§3), not a "pending" stub. The
  `{"status":"pending"}` JSON writes are deleted from
  `DeviceSnapshotService.kt:171-195`.
- **Readable destination (D-17):** show `DocumentFile.fromTreeUri(...)
  .name` + a friendly path, not the raw `content://…%3A…` URI
  (`DeviceSnapshotConfigScreen.kt:233-237`).
- **Sources UI:** the §5.1 folder/media pickers with explicit coverage
  lines; delete the pre-13 `READ_EXTERNAL_STORAGE` fallback branch
  (`DeviceSnapshotConfigScreen.kt:327-329`) and the manifest's
  `READ_EXTERNAL_STORAGE maxSdk=32` (`AndroidManifest.xml:32-33`) — dead
  at minSdk 33 (D-15).

---

## 11. Encrypt screen (resolves D-18 cap + minor C gaps)

- Surface the **16 MiB input cap** (`BackupsFlow.kt:38`) *before* failure:
  a caption on the file picker and a pre-flight size check on the picked
  URI (`DocumentFile.length()`), disabling Encrypt with a reason if over.
  (Large files belong in the device-snapshot content stream, not the
  single-shot envelope — link the user there.)
- Keep the cleartext-header-exposure note on the label field; move it from
  hardcoded text to a string resource (§12).

---

## 12. Strings + theme (resolves D-9, D-10, C globals)

- **Strings (D-9):** extract every hardcoded Kotlin sentence across all
  screens into `strings.xml`; keep `resourceConfigurations` honest (add
  locales only when translated). This is mechanical but must precede polish
  so the debt doesn't multiply.
- **Theme (D-10):** consume the shared common-security M3 tokens
  (`colorScheme` + `typography`); delete the hardcoded hex constants
  (`MainActivity.kt:505-510`, `LocalSnapshotsScreen.kt:88-94`,
  `DeviceSnapshotConfigScreen.kt:103-110`, …). Forced-dark stays a
  deliberate posture for a FLAG_SECURE vault app, but is expressed via a
  documented dark token set, not per-surface literals. `themes.xml`
  parent stays a no-action-bar dark theme (the scaffold supplies the top
  bar).
- **A11y:** min body 12sp via typography tokens (kills the 9–11sp captions,
  `SuiteStatusFooter.kt:127-155`); `stateDescription` on the config
  switches; semantic headings on each screen title.

---

## 13. Complement surfacing (resolves D-16, E)

Three low-cost additions, all honest:
- Home card: "This vault is **excluded from Google One and Smart Switch**
  — your recovery key is the only restore path." (Today that fact lives
  only in `data_extraction_rules.xml`.)
- Home card: "Moving phones? Reveal your recovery key → transfer any
  snapshot → Decrypt with recovery key on the new device." (The flow
  exists, A-6/7; the guidance didn't.)
- **`.usbe` VIEW intent-filter** (E, file-manager hand-off): register
  `MainActivity` (or a thin trampoline) for `ACTION_VIEW` on
  `application/octet-stream` + `.usbe` path pattern, so tapping a snapshot
  in Files by Google routes into the Restore screen. Guard: only accept
  the URI, never auto-decrypt; user still authenticates.

---

## 14. Disposition table (every audited feature)

| Audit # | Feature | v2 disposition |
|---|---|---|
| 1–7 | vault create/unlock/encrypt/decrypt/recovery/reveal | **KEEP** (WORKING); Setup gains mandatory escrow (§8.1), Unlock gains re-bind (§8.2) |
| 8 | clipboard "30s auto-clear" | **FIX** — implement clear + reword (§8.3) |
| 9 | local snapshots list/restore/delete | **KEEP + FIX** layout/confirm (§9) |
| 10 | `rotate(keepLast)` dead code | **FIX** — wire to retention setting (§9) |
| 11–12 | lifecycle/tamper/attestation gates | **KEEP** unchanged |
| 13 | FGS snapshot pipeline | **KEEP**, extended for SAF sources (§5) + worker path (§4) |
| 14 | progress/completion notifications | **FIX** — un-strip + runtime request + degrade (§6) |
| 15 | Android settings section | **KEEP** capture-only; restore = inspect/export report, honest "not re-applied" (§2.2) |
| 16 | user-dirs manifest | **REDESIGN** onto SAF trees; explicit coverage (§5.1) |
| 17 | `.usbs` full content stream (write-only + framing bug) | **FIX** — build restore (§2.3) + **REDESIGN** framing UDCSv002 (§2.4) |
| 18–19 | suite-app-vaults / vault-folder stubs | **REDESIGN** — deposit-intent collect (§3); default OFF, hidden until peers land (§10); stub JSON deleted |
| 20 | SAF tree destination + grant | **KEEP** |
| 21 | passphrase hand-off to FGS via Intent extra | **KEEP** (documented trade-off); scheduled path uses SOK, no vault secret (§4.1) |
| 22 | "orchestrates every peer" | **STAGED** — real mechanism §3; claim gated behind code (§0/§3.4) |
| 23 | `BACKUP_ORCHESTRATOR` beacon at v1 | **FIX** — remap v1 → `ENVELOPE_TOOL`; re-add at beacon v2 when a peer responds (§0/§3.4) |
| 24 | scheduling | **REDESIGN** — snapshot-only key + WorkManager (§4); or drop from docs if deferred |
| 25 | USB destination | **KEEP** as "any SAF location" (§5.2) |
| 26 | Syncthing folder | **KEEP + SURFACE** (§5.2, §13) |
| 27 | self-hosted endpoint | **DROP** — reword README (§5.2) |
| 28 | SuiteCapsProvider beacon | **KEEP** (capability value changes only, §0) |
| 29 | backup exclusion (Google One / Smart Switch) | **KEEP + SURFACE** the fact (§13) |
| 30 | diagnostics | **KEEP** |

---

## 15. New / changed / deleted files

**New (backups app):**
- `UserDirsContentRestore.kt` — UDCSv002 unpacker → SAF tree writes (§2.3/§2.4)
- `DeviceSnapshotBundleReport.kt` + screen — render/export decrypted device bundle (§2.2)
- `RestoreScreen.kt` — format-detecting restore/import (§2.1)
- `CollectScreen.kt` + `SuiteBackupResponder`/ingest — deposit-intent (§3)
- `SnapshotWorker.kt` (CoroutineWorker) — scheduled snapshots (§4.2)
- `ScheduleConfig` fields in `DeviceSnapshotConfig` (frequency, SAF source list)

**New (common-security / common-backup):**
- `SuiteBackupContract.kt` — intent action/extras (§3.1)
- `Crypto` snapshot-only key path (`understory.snapshot.v1`, non-auth-bound) (§4.1)
- `SuiteCapabilityRegistry` map edit: v1→`ENVELOPE_TOOL`; add `BACKUP_EXPORTER`, gate `BACKUP_ORCHESTRATOR` to v2 (§0/§3.4)
- shared M3 tokens + scaffold components (suite-wide, consumed here) (§12)

**Changed:**
- `AndroidManifest.xml`: delete line 125 strip; delete lines 32-33 (pre-33 storage); add `.usbe` VIEW intent-filter; add WorkManager (no boot/wake perms)
- `UserDirsContentStream.kt`: UDCSv002 framing; SAF-tree source; delete shrink-tolerance comment
- `UserDirsManifestCollector.kt`: SAF-tree walk; coverage honesty
- `BackupsFlow.kt`: add `sniff()`; all ops off-main
- `DeviceSnapshotService.kt`: delete stub-JSON writes; real icon; inline-progress fallback
- `DeviceSnapshotConfig.kt`: stub sections default false; SAF source list; schedule fields
- `LocalSnapshotStore.kt`: wire `rotate`
- `MainActivity.kt` / all screens: scaffold+tokens, strings→resources, off-main, mandatory escrow, re-bind, clipboard fix

**Deleted:**
- stub `{"status":"pending"}` writes (`DeviceSnapshotService.kt:171-195`)
- pre-Tiramisu storage branch (`DeviceSnapshotConfigScreen.kt:327-329`) + `READ_EXTERNAL_STORAGE maxSdk=32`
- `STANDARD_DIRS` raw-path list; `LazyFileInputStream` shrink-tolerance comment
- self-hosted-endpoint README claim

---

## 16. Build order (dependency-correct)

1. Honesty-now (S, no new mechanism): un-strip notifications + runtime
   request + degrade (§6); beacon v1→`ENVELOPE_TOOL` (§0); default stub
   sections OFF + hide (§10); clipboard fix (§8.3); README reword
   (self-hosted/scheduling/orchestration) — clears CD-4 overclaim.
2. Off-main + strings + theme tokens (§7, §12) — unblocks every screen.
3. Setup escrow + Unlock re-bind (§8) — closes the data-loss cliff.
4. Restore core: `sniff` + envelope + device-bundle report (§2.1/§2.2) —
   makes existing writes restorable.
5. Framing UDCSv002 + `.usbs` restore (§2.4/§2.3) — makes content backup a
   real backup.
6. SAF-tree sources (§5) — makes non-media backup honest/complete.
7. Snapshot-only key + WorkManager scheduling (§4).
8. Deposit-intent collect (§3) + re-add `BACKUP_ORCHESTRATOR` at beacon v2.
9. Complement surfacing + `.usbe` VIEW filter + retention + layout/confirm
   polish (§13, §9, §11).
