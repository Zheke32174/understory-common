# Design v2 — vault-folder (com.understory.vaultfolder)

Store-facing name recommendation: **Understory File Vault** (SUITE.md §4.5).
Package id stays `com.understory.vaultfolder`. One machine name suite-wide:
`vaultfolder` (SUITE.md §4.2 — repo `understory-vault-folder` is legacy; launcher
label and capability table both normalize to this).

Scope: `understory-vault-folder/vault-folder/` plus the vendored
`common-security`. This design resolves every finding in
`docs/audit-v2/vault-folder.md` and honors the cross-app decisions in
`docs/audit-v2/SUITE.md` (CD-1..CD-4 coexistence doctrine; the shared
`DeviceAuthVault` + `VaultRecovery` contracts; shared M3 tokens; suite naming).

This is a DESIGN. No code is changed here. Every item below names exact files,
classes, Android APIs, and the disposition (FIX / REDESIGN / DROP) of each
audited feature. An implementer builds from this without re-deriving.

Items tagged **SHARED** are authored in canonical
`understory-common/common-security` and re-vendored byte-identical across
passgen/aegis/backups/vault-folder, so the fix lands once (SUITE.md §3
divergence risk). This app currently vendors ONLY `common-security`
(`settings.gradle.kts:24-25`); §4 and §9 add the `common-backup` module to it.

---

## 0. Disposition table (every audit finding)

| Audit id | Title | Disposition | Where |
|---|---|---|---|
| A8 / D1 | Export crashes — non-Parcelable `VaultFolderEntry` in `rememberSaveable` | FIX (hold id string) | §1 |
| A8 sec. | `exportFile` opens `"w"` not `"wt"` (truncation) | FIX | §1.3 / §7 D11 |
| A8 sec. | Dead `createOutput` launcher | DROP | §1.4 |
| A4 / A8 / D2 | Main-thread crypto+IO on add & export | FIX (Dispatchers.IO + state machine) | §2 |
| A7 / D3 | Cross-app deposit auto-encrypts; docs claim a confirm that doesn't exist | FIX (deposit confirm interstitial) | §3 |
| A7 | `singleTask` + no `onNewIntent` → warm-task deposit dropped | FIX (`onNewIntent`) | §3.4 |
| A7 | `category.BROWSABLE` on VIEW filter | DROP | §3.5 |
| A14 / D4 | No in-vault viewing — only escape is exporting plaintext | REDESIGN (isolated-process memory viewer) | §5 |
| A11.1 / D8 | Folders biometric callbacks on a background executor | FIX (main executor, shared shim) | §6.1 |
| A11.2 / D8 | Folders cancel omits `ERROR_CANCELED` → false "Auth failed" | FIX | §6.1 |
| A11.3 / D9 | Folders `LazyColumn` no `weight(1f)` → Create/Back pushed off-screen | FIX | §6.2 |
| A11.4 / D12 | `pruneOrphans` never called | FIX (call on Folders entry) | §6.3 |
| A12 / D5 | Folder-delete has the weakest tap-jack guard (most destructive action) | FIX (Secure* + focus/obscure) | §6.4 |
| A12 | Folder-delete success renders in red error slot | FIX | §6.4 |
| A13 / D12 | `rename` dead API, no UI | FIX (build a rename row action) | §6.5 |
| A15 / D7 | Hardcoded suitecaps authority → eng/prod install collision | FIX (`${applicationId}.suitecaps`) | §8 |
| A17 / D13 | Blobs/metadata encrypted with no AAD binding (silent blob-swap) | FIX (AAD = folderId+blobId; format v2) | §7 D13 |
| A18 / D10 | Backups-integration copy points at non-existent path, wrong suite # | FIX (honest copy now; real hand-off) | §4 |
| — (SUITE §3) | Biometric re-enrollment bricks vault; no recovery/reset | FIX (shared `VaultRecovery`) | §4 |
| C / D6 | GUI: hardcoded palette/copy, no strings res, portrait lock, sub-12sp | FIX (shared M3 tokens + Scaffold + strings) | §9 |
| A10/A19/etc | Stale RELEASE-BLOCKER / manifest / SUITE_DESIGN comments (#14) | FIX (doc/comment edit) | §10 |
| — | Unused common-security baggage compiled in (A11yProbe, Totp, HotpSecret…) | DROP-TO-V2 (note only) | §10 |

Nothing here is a silent DROP of a promised capability. The two REDESIGNs (§5
viewer, §3 deposit) replace a broken/absent promise with a working mechanism or
an honest line of UI.

---

## 1. CRASH FIX — export state survives the SAF round-trip (A8 / D1)

### 1.1 Root cause (verified in code)

`ListScreen` holds
`var pendingExport by rememberSaveable { mutableStateOf<VaultFolderEntry?>(null) }`
(`MainActivity.kt:546`). `VaultFolderEntry` (`VaultFolderStore.kt:263-287`) is a
plain `data class` — not `Parcelable`, not `Serializable`. Compose's
`mutableStateOf` is a `ParcelableSnapshotMutableState`; when the activity's
instance state is parceled — which happens on `onStop`, i.e. **exactly when the
`CreateDocument` SAF picker comes to the foreground** — `Parcel.writeValue`
throws `RuntimeException: Parcel: unable to marshal value`. Every Export tap that
survives a real state-save crashes the process. This is the same defect the file
already fixed for the `Stage` enum by string-encoding (`MainActivity.kt:251-258`)
and re-introduced here.

### 1.2 Fix — save the id string, resolve on return

Replace the object-typed saveable with a `String?` (the entry id is a UUID
string, natively saveable):

```kotlin
// MainActivity.kt ListScreen
var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
```

- Export tap (`onExport`, `MainActivity.kt:615-625`) sets
  `pendingExportId = entry.id` instead of `pendingExport = entry`.
- On launcher return (`MainActivity.kt:552-566`), resolve the id against live
  metadata rather than trusting a stashed object:
  ```kotlin
  val target = pendingExportId?.let { id ->
      store.contents.entries.firstOrNull { it.id == id }
  }
  pendingExportId = null
  ```
  If `target == null` (entry deleted while the picker was open, or a cold
  process restart wiped the unlocked store — see §1.5), surface a neutral
  snackbar ("Nothing to export — file no longer available") and return. No
  crash, no stale write.

Chosen over "make `VaultFolderEntry` Parcelable": the id-string approach also
fixes the **stale-object** hazard (exporting a metadata snapshot that no longer
matches disk) and keeps the domain model free of Android `Parcelable` coupling.
`@Parcelize` is the fallback only if a future screen must round-trip the whole
object; it is not needed here.

### 1.3 Truncation — `"w"` → `"wt"` (A8 secondary)

`exportFile` (`VaultFolderStore.kt:150`) opens
`openOutputStream(outputUri, "w")`. `"w"` does not guarantee truncation on all
document providers; re-exporting over a shorter existing document can leave
trailing bytes from the old content. Change the mode string to `"wt"`
(write+truncate). Single-character fix; must ship with §1 since export is being
touched anyway.

### 1.4 Delete dead scaffolding (A8 secondary)

Remove the `createOutput` launcher (`MainActivity.kt:537-539`) — it is created,
never launched, and its lambda comment ("handled via state below") is a lie.
Zero references. DROP.

### 1.5 Cold-restart note (in scope, cheap)

The unlocked `VaultFolderStore` lives in the `VaultFolderManager`
process-singleton (`VaultFolderStore.kt:51-66`), not in saved instance state. If
the process is fully killed during the SAF round-trip, the vault is locked on
return and `store` is null. The parent already routes an un-unlocked state back
to `UnlockScreen`; the resolved-id path in §1.2 degrades correctly (no target →
neutral message). No extra work, but the implementer must NOT try to
"restore" the export by stashing the entry across process death — that would
re-introduce a plaintext-adjacent object in saved state.

---

## 2. OFF-MAIN-THREAD CRYPTO + REAL LOADING STATES (A4 / A8 / D2)

### 2.1 Root cause

`readBoundedBytes` → `writeBlob` (encrypt) → `saveMetadata` on add
(`VaultFolderStore.kt:104-120`) and `readBlob` (decrypt) → `openOutputStream`
on export (`VaultFolderStore.kt:147-157`) all run **synchronously on the main
thread** inside the picker callbacks (`runAdd` `MainActivity.kt:734-764`;
export lambda `:556-566`). Violates SAMSUNG_QUIRKS.md:119-129 (">100ms crypto/IO
→ Dispatchers.IO"). Consequence beyond ANR risk: the `"Encrypting…"` label
(`MainActivity.kt:825`) and any export progress can never render — the
synchronous block owns the main thread through the whole operation, so no
recomposition happens.

### 2.2 Fix — coroutine + explicit UI state machine

Introduce a small screen-level state in both `AddScreen` and `ListScreen`
export:

```kotlin
sealed interface OpState { object Idle; object Working; data class Done(val msg: String); data class Failed(val msg: String) }
```

- Get a scope: `val scope = rememberCoroutineScope()`.
- `runAdd`/export body becomes:
  ```kotlin
  opState = OpState.Working
  scope.launch {
      val result = withContext(Dispatchers.IO) { store.addFile(uri, name, mime, opts) }
      opState = when (result) { … }   // main thread; safe to touch Compose state
  }
  ```
- While `opState == Working`: disable the Pick/Export/Back buttons
  (`enabled = false`), show the `"Encrypting…"` / `"Exporting…"` label (now
  reachable), and for near-cap files render an indeterminate
  `LinearProgressIndicator` (the whole-file buffer means we can't easily show
  determinate percent without threading a callback through `readBoundedBytes` —
  indeterminate is honest and sufficient at the 20 MiB cap).
- Keep the transient-flight bracketing (`VaultFolderManager.beginTransientFlight`
  / `endTransientFlight`, `MainActivity.kt:618,780,815`) exactly as is — it is
  orthogonal to threading and prevents the SAF round-trip from locking the
  vault. Do NOT move `endTransientFlight()` inside the coroutine; it must fire on
  the launcher callback (main thread) before the IO work starts, as today.

`store.addFile` / `exportFile` stay synchronous internally (they are pure
compute+IO); the threading lives in the composables. This matches the
suite-wide sweep in SUITE.md §5 gap #8.

### 2.3 Store API stays main-safe

No `suspend` on `VaultFolderStore` methods — keeping them plain lets the
isolated-viewer service (§5) and any future caller pick its own dispatcher.
Document the contract in the `VaultFolderStore` KDoc: "all public methods block;
callers must invoke off the main thread."

---

## 3. CROSS-APP DEPOSIT — real per-file confirmation (A7 / D3)

### 3.1 Root cause

Manifest comment (`AndroidManifest.xml:174-180`) and
`SUITE_THREAT_SURFACES.md:138-140` both claim a deposit shows an explicit
confirmation before encrypting. Code: `LaunchedEffect(incomingUri)` calls
`beginAdd(incomingUri)` (`MainActivity.kt:790-795`); with shred off (the
default, and the only state on a fresh deposit) `beginAdd` calls `runAdd`
**directly** (`:767-773`) — the file is silently encrypted into the vault with
no confirm. Only the shred variant confirms. The documented contract is false.

### 3.2 Fix — deposit confirmation interstitial

Add a mandatory confirm dialog for the **deposit** path (ACTION_VIEW incoming
URI), independent of the shred toggle. New state in `AddScreen`:

```kotlin
var pendingDepositConfirm by remember { mutableStateOf<Uri?>(null) }
```

Change the auto-add `LaunchedEffect` (`MainActivity.kt:790-795`) so an incoming
URI populates `pendingDepositConfirm` instead of calling `beginAdd` directly:

```kotlin
LaunchedEffect(incomingUri) {
    if (incomingUri != null) pendingDepositConfirm = incomingUri
}
```

Dialog content (source-inert; renders text only, never previews the bytes):
- Title: `deposit_confirm_title` ("Add this file to the vault?").
- Body: display name + mime + size from `queryDisplayMetadata` /
  `ContentResolver` (`MainActivity.kt:903+`) — **metadata only, no content
  render**, so a hostile deposit can't paint UI. State the target folder name.
- Confirm button → `beginAdd(uri)` (which still routes through the shred confirm
  if the user has shred on — two dialogs is correct: deposit-accept then
  shred-accept, distinct decisions).
- Dismiss → drop the URI, stay on Add with a neutral "Deposit cancelled" line.

The picker-originated add (user tapped "Pick a file" inside the app) does NOT
get this dialog — the user already chose the file in the system picker; a second
confirm there is friction with no security gain. The interstitial exists
specifically because a *deposit* arrives unbidden from another app.

### 3.3 `queryDisplayMetadata` hardening for deposits

Deposit filenames are attacker-influenced. `queryDisplayMetadata`
(`MainActivity.kt:903+`) already defaults to "imported" on blank. Add: clamp the
displayed name to a sane length (e.g. 120 chars) for the dialog and truncate
with an ellipsis in UI only (store the full sanitized name). No path separators
reach disk — the blob is `f-{uuid}.bin` (`VaultFolderStore.kt:187`), the display
name is data inside encrypted metadata, so there is no traversal sink; this is
purely to keep the confirm dialog readable and un-spoofable.

### 3.4 Warm-task deposit — add `onNewIntent` (A7 unfinished edge)

`launchMode="singleTask"` (`AndroidManifest.xml:160`) with no `onNewIntent`
override means a deposit arriving while a MainActivity instance is alive is
silently dropped — only `onCreate` reads `intent.data` (`MainActivity.kt:138`).
Add:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    // re-extract deposit URI into the same state the parent composable reads
    depositUri = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data
    // if currently unlocked, route to Stage.Add with the new URI;
    // if locked, stash and replay after the next successful unlock
}
```

Because lock-on-leave usually finishes the task before a second deposit lands,
this is a low-frequency path — but it is a silent no-op today, and CD-4(c)
requires visible truthful handling. If the vault is locked when the new intent
arrives, stash the URI (plain `remember`, process-bound) and replay it into the
deposit-confirm dialog after unlock, same as the cold path.

### 3.5 Drop `BROWSABLE` (A7 hardening nit)

Remove `<category android:name="android.intent.category.BROWSABLE" />`
(`AndroidManifest.xml:190`). A file-deposit target has no reason to be
invocable from a web `intent://` URL; dropping it shrinks the surface with zero
functional loss (share-sheet / "Open with…" / peer `setPackage` deposits all use
`category.DEFAULT`, which stays).

### 3.6 Optional MIME trim (B / A7 chooser-noise)

Consider narrowing `image/*` (`AndroidManifest.xml:195`) so the app doesn't
insert itself into every image "Open with…". Not a blocker; note it as a
product decision. Keep octet-stream/json/text/csv/pdf (suite-peer envelope +
document deposits). Decision-gated — leave as-is unless the operator wants the
chooser quieter.

---

## 4. RECOVERY / RESET + HONEST BACKUP POSITIONING (SUITE §3; A18 / D10)

### 4.1 The data-loss cliff (verified, shared)

`Crypto.ensureDeviceAuthKey()` sets `setInvalidatedByBiometricEnrollment(true)`
(`Crypto.kt:165`). Enrolling a new fingerprint destroys the Keystore key that
wraps **every folder's KEK** (each folder's KEK is wrapped by the same
device-auth key — FoldersScreen claim verified). After that, `unlock`
(`VaultFolder.kt:105-126`) throws `KeyPermanentlyInvalidatedException` and there
is **no recovery UI** — the vault is permanently dead. vault-folder is one of
the two engines with no recovery path at all (SUITE.md §3).

### 4.2 SHARED — `VaultRecovery` contract in common-security

Adopt the shared recovery contract authored in `understory-common/common-security`
(the aegis design §4 introduces the same `VaultRecovery`; backups already has the
working model). vault-folder's obligations:

- **Recovery-key escrow at create.** In `VaultFolder.create`
  (`VaultFolder.kt:82-102`), in addition to wrapping the KEK with the
  device-auth cipher, wrap the SAME 32-byte KEK with a passphrase-derived key
  (Argon2id via `Crypto` — the argon2 path already compiled in, currently
  unused baggage per A11-preamble, now load-bearing). Write a second header
  slot `recovery.bin` (`header_v2 || iv || wrappedKek_byRecoveryKey`) alongside
  the existing `header.bin`. The passphrase is a user-chosen recovery phrase OR
  an app-generated 190-bit recovery code (`Crypto.generateMasterPassword`,
  `Crypto.kt:183-194` — also currently-unused baggage, now used).
- **Detection.** Wrap the `unlock` call site; catch
  `KeyPermanentlyInvalidatedException` and route to a **Recovery screen** (new
  `RecoveryScreen`) instead of the generic error.
- **Re-bind flow.** Recovery screen: user enters the recovery phrase/code →
  decrypt KEK from `recovery.bin` off-main-thread → re-run the device-auth wrap
  to regenerate `header.bin` under a freshly generated device-auth key → vault
  usable again. No plaintext file ever touches disk.
- **Guarded reset.** If the user has no recovery phrase, offer an explicit,
  double-confirmed **Reset vault** action (Secure* + `filterTouchesWhenObscured`
  + `hasWindowFocus`, same guard as file delete §6.4): wipes the folder dir(s)
  and the device-auth key (`Crypto.deleteDeviceAuthKey`, `Crypto.kt:172-177`).
  Honest copy: "Reset destroys all files in every folder. Only do this if you've
  lost access and have no recovery phrase."

This is multi-folder aware: recovery re-binds the device-auth key, which
un-bricks ALL folders at once (they share the wrap key). The reset action must
enumerate every folder (`VaultFolders.list`) so the confirm copy names the true
blast radius.

### 4.3 Setup copy — escrow prompt

At first-time setup (`SetupScreen`, `MainActivity.kt:362-437`), after the
device-auth key is seeded, prompt the user to **save a recovery phrase/code**
(with an honest "skip — I accept lost-device = lost-vault" path that keeps
today's behavior). Replace the current lost-device warning (`:396-407`) with:
"Save a recovery phrase now, or accept that losing this device (or re-enrolling
your fingerprint) permanently destroys the vault." This turns the sharpest edge
in the app into an informed choice.

### 4.4 Backup honesty (A18 / D10)

Two false claims today:
- Setup copy: "Use backups (#7 in the suite) for off-device recoverable copies"
  (`MainActivity.kt:402-404`) — there is no automated path and the number is
  wrong (vault-folder is #7; backups is #4 — SUITE_DESIGN.md:7-15).
- `SUITE_DESIGN.md:598`: "vault-folder exposes `BackupProvider`" — false; the
  module isn't even vendored.

FIX now (copy + one real hand-off):
- Reword Setup: "To keep an off-device copy, use the recovery phrase above, or
  export a file and store the plain copy where you choose." Fix "#7"→"#4" if the
  suite number is mentioned at all. Ships with §10 doc pass.
- Add the real hand-off (small, uses the existing deposit primitive in reverse):
  a **"Send encrypted copy to Backup"** action per file that launches
  `ACTION_VIEW` (or `ACTION_SEND`) with `Intent.setPackage("com.understory.backups")`
  carrying the encrypted blob as an `application/octet-stream` envelope via a
  `FileProvider` grant. This makes the A18 story TRUE with no new crypto — the
  backups app already receives octet-stream deposits. This depends on §5's
  isolated FileProvider being restricted to suite peers (see §5.4); do NOT hand a
  plaintext URI out. Gate behind `common-backup` being vendored (§9). If the
  operator wants this in v2-later, ship only the copy fix now and note the
  hand-off as designed-not-built (honestly, in docs, not onboarding).

---

## 5. IN-APP VIEWER — isolated-process, memory-only, FLAG_SECURE (A14 / D4)

### 5.1 The gap

There is no viewer (verified: `EntryRow` shows name/mime/size text only,
`MainActivity.kt:676-710`; the only decrypt paths are `exportFile` and its
`readBlob`). To look at a vaulted file the user must **export a plaintext copy
out of the vault** — a strictly worse privacy outcome than an in-app viewer, and
the app's biggest product gap vs. Secure Folder (which shows your files).
SUITE_DESIGN.md #4's "decrypt to ContentProvider URI → ACTION_VIEW" flow is
unimplemented — and would be the WRONG design (handing a plaintext URI to an
arbitrary external app defeats the vault). We build a self-contained viewer
instead.

### 5.2 Security boundary — isolated renderer process

Render hostile bytes in a **separate `android:isolatedProcess` service**, never
in the vault process, mirroring the antivirus `ApkParserService` pattern
(SUITE_THREAT_SURFACES.md:167-175). Two-process split:

- **Vault process** (MainActivity): holds the KEK, decrypts the blob to an
  in-memory `ByteArray` (off-main-thread, §2), hands the plaintext bytes to the
  renderer over a bound `Messenger`/AIDL as a `ParcelFileDescriptor` created
  from an **anonymous in-memory pipe** (`ParcelFileDescriptor.createPipe()` or a
  `MemoryFile`/ashmem region) — **never a file on disk**.
- **Renderer service** (`ViewerRenderService`, `android:isolatedProcess="true"`,
  `android:exported="false"`): runs with no permissions, no Keystore access, no
  filesystem write. It receives raw bytes, decodes to a bitmap / lays out text /
  rasterizes a PDF page, and returns a **rendered bitmap** (already-safe pixel
  data) back to the vault process for display. Malicious image/PDF parsers
  therefore execute in a sandbox that cannot reach the KEK, the blobs, or the
  network (app has no INTERNET anyway, `network_security_config.xml:9-11`).

If `createPipe`/AIDL bitmap round-trips prove too heavy for large images, the
fallback is: keep decode in the isolated process but have it write the rendered
bitmap to a shared `MemoryFile` (ashmem) the vault process maps read-only — still
zero plaintext-on-disk. Decode input must never be a `File`.

### 5.3 Supported types + renderers (all bundled, offline)

| Type | Renderer (in isolated service) | Notes |
|---|---|---|
| image/* (png,jpg,webp,gif,heic) | `BitmapFactory.decodeByteArray` with `inJustDecodeBounds` pre-pass + downsample to viewport | Never `decodeFile`. Cap dimensions to avoid OOM. |
| text/plain, text/csv, application/json | decode bytes as UTF-8 (with charset sniff fallback), render into a scrollable `Text` in the vault process (text is inert — no isolated process strictly needed, but route it through the same channel for one code path) | Line-count / size cap for very large text. |
| application/pdf | `android.graphics.pdf.PdfRenderer` on the in-memory `ParcelFileDescriptor`, page-by-page → bitmaps | `PdfRenderer` requires a `ParcelFileDescriptor`; supply the in-memory pipe FD, not a temp file. Isolated process contains any malformed-PDF parser bug. |

Unsupported mime → no "Open" affordance; show "Preview not available for this
type — export to view externally" (honest, and the only case that still needs
export).

### 5.4 Viewer UI + posture

- New `ViewerScreen` (or a full-screen dialog) launched from a new **"View"**
  action on `EntryRow` (`MainActivity.kt:676-710`), shown only when mime is
  supported.
- The viewer Activity/host inherits the app's `FLAG_SECURE` +
  `setHideOverlayWindows` posture (`MainActivity.kt:115-125`) — screenshots and
  screen-record blocked, overlays hidden. Verify `FLAG_SECURE` is set on the
  viewer window itself, not just MainActivity, if it is a separate Activity.
- Memory hygiene: `Crypto.wipe(plaintext)` the decrypted `ByteArray` as soon as
  the bytes are handed to the renderer; recycle bitmaps on dispose; the
  isolated process is `bindService`-scoped and torn down when the viewer closes.
- **No share-sheet, no "open with", no decrypt-to-cache** — the viewer is a
  terminal sink. This preserves the "no cache leak, no thumbnail leak" property
  the audit praised (A14) while removing the export-to-view penalty.
- The §4.4 backups hand-off is the ONLY path that emits an encrypted URI to a
  peer, and it emits ciphertext with a `setPackage`-restricted grant — the
  viewer never emits anything.

### 5.5 Thumbnails — explicitly out of scope

No list thumbnails (they'd require decrypting every blob on list render → cache
leak risk). The list stays text-only. Document this as intentional so a future
session doesn't "add thumbnails" and reopen the leak.

---

## 6. MULTI-FOLDER DEFECTS (A11 / A12 / A13)

### 6.1 Biometric callbacks on the main thread + cancel code (A11.1, A11.2 / D8)

`promptAuthLocal` (`FoldersScreen.kt:343-379`) uses
`Executors.newSingleThreadExecutor()` (`:353`) — unlock/create callbacks (store
swap, Compose state writes, rollback IO) run on a background thread, unlike
MainActivity's main-executor `promptAuth` (`MainActivity.kt:499`). And its
cancel set (`:363-365`) omits `ERROR_CANCELED`, so a system-initiated cancel
renders as a red "Auth failed".

FIX: pass `ContextCompat.getMainExecutor(activity)` to the `BiometricPrompt`
constructor, and add `ERROR_CANCELED` to the cancel branch alongside
`ERROR_USER_CANCELED`/`ERROR_NEGATIVE_BUTTON`. Better: **dedupe** — lift the
single canonical `promptAuth` (main-executor, full cancel set) into a shared
helper (either a top-level `internal fun` in the `vaultfolder` package or, since
BiometricPrompt wiring is identical across the suite, a SHARED
`common-security/BiometricAuth.kt`). One shim, one behavior. SUITE.md §3 flags
this exact "same pattern, inconsistent hardening" class.

### 6.2 Folders layout — `weight(1f)` (A11.3 / D9)

`FoldersScreen`'s `LazyColumn` (`FoldersScreen.kt:82-85`) has no `weight(1f)` in
its parent `Column`; with enough folders it consumes the viewport and pushes
"Create folder"/"Back" off-screen. Add `Modifier.weight(1f)` to the LazyColumn
and keep Create/Back as fixed-height rows below it (same structure the fixed
ListScreen uses, `MainActivity.kt:608-635`).

### 6.3 Call `pruneOrphans` (A11.4 / D12)

`VaultFolders.pruneOrphans` (`VaultFolders.kt:137-140`) is never called; a
dismissed create-dialog after a failed reserve leaves orphan index rows (hidden
today only because `list()` filters). Call `VaultFolders.pruneOrphans(ctx)` once
on `FoldersScreen` entry (`LaunchedEffect(Unit)`), off the main thread. Keeps the
index clean instead of relying on read-time filtering.

### 6.4 Folder-delete guard parity + success slot (A12 / D5)

Destroying an entire folder is the most destructive action in the app and has
the WEAKEST guard: the row Delete is a plain `OutlinedButton`
(`FoldersScreen.kt:237-241`) and the confirm dialog has neither
`filterTouchesWhenObscured` nor `hasWindowFocus()` (`:168-190`) — while file
delete has both (A9, `MainActivity.kt:642-667`).

FIX (bring to parity with file delete):
- Row action → `SecureOutlinedButton` (`common-security/SecureButton.kt:111-144`,
  tap-jack filtered).
- Confirm dialog → set `dialogView.filterTouchesWhenObscured = true` via
  `DisposableEffect` and guard the confirm `onClick` with
  `if (!dialogView.hasWindowFocus()) return@TextButton`, exactly as file delete.
- Confirm copy must name the blast radius: "Deletes this folder and all N files
  in it. No recycle bin, no recovery."
- Move the post-delete success message OUT of the red error slot
  (`FoldersScreen.kt:80,:181`) into a neutral snackbar/toast (green or default),
  not the error color.

### 6.5 Folder rename — build the UI (A13 / D12)

`VaultFolders.rename` (`VaultFolders.kt:107-116`) works, refuses the default
folder, sanitizes the name — but no screen reaches it. FIX (not DROP — the API
is correct and cheap to surface): add a **Rename** row action on each non-default
folder in `FoldersScreen` (a pencil `IconButton` opening a single-field name
dialog, reusing the create-dialog's name-entry composable and its
`sanitizeName`/disabled-empty handling `:287-294`). On confirm →
`VaultFolders.rename(ctx, id, newName)` off-main-thread → refresh the list. The
default folder shows no rename affordance (its display name is hardcoded).

---

## 7. STORAGE HARDENING — AAD binding, format v2 (A17 / D13)

`saveMetadata`/`writeBlob` (`VaultFolderStore.kt:180,186`) call
`Crypto.aesGcmEncrypt(kek, bytes)` with **no AAD**, even though the function
supports it (`Crypto.kt:88`, `aad` param). An attacker with data-dir write
access could swap two blob files (`f-{uuidA}.bin` ↔ `f-{uuidB}.bin`) undetected —
the metadata says name A but the bytes are B, and GCM verifies fine because
nothing binds ciphertext to its identity.

FIX (do it before first public release while the format is cheap to break):
- Bind AAD = `folderId || 0x00 || blobId` for each blob, and
  AAD = `folderId || 0x00 || "metadata"` for the metadata envelope.
  `writeBlob(blobId, pt)` → `Crypto.aesGcmEncrypt(kek, pt, aad = aadFor(blobId))`;
  `readBlob` passes the same AAD to `aesGcmDecrypt` (`Crypto.kt:101`).
- This changes the on-disk contract, so bump the blob/metadata format to **v2**
  and gate reads: existing v1 blobs (no AAD) still decrypt via a v1 path;
  new/rewritten blobs use v2. Given `versionName = "0.1-skeleton"` and no public
  release yet, a clean cutover (v2-only, no migration) is acceptable if the
  operator confirms no field installs exist — simpler. Default to the
  version-gated reader to be safe.
- This dovetails with §4's `header_v2` recovery slot — do the format bump once.

Also fix `exportFile` `"w"`→`"wt"` here if not already done in §1.3 (D11).

---

## 8. ENG/PROD CO-INSTALL — parametrize provider authority (A15 / D7)

The `SuiteCapsProvider` authority is the literal
`com.understory.vaultfolder.suitecaps` (`AndroidManifest.xml:202`), but the eng
flavor's applicationId is `com.understory.vaultfolder.eng`
(`build.gradle.kts:71-75`). Installing prod + eng together fails with
`INSTALL_FAILED_CONFLICTING_PROVIDER` (two packages claiming one authority).

FIX (the passgen-proven pattern, `understory-passgen/passgen/build.gradle.kts:74`):
```xml
android:authorities="${applicationId}.suitecaps"
```
The manifest placeholder resolves per-flavor (`…vaultfolder.suitecaps` for prod,
`…vaultfolder.eng.suitecaps` for eng). Consumers already compute the authority
via `providerAuthorityFor(pkg)` (`SuiteCapabilityRegistry.kt:92-93`), so this
also makes the authority follow the package correctly. Document that eng builds
remain **mesh-invisible by design** — `.eng` is in nobody's
KNOWN_PEERS/SUITE_PACKAGES/`<queries>` lists (acceptable, per A15(b)/(c)).

---

## 9. GUI — shared M3 tokens, strings, Scaffold, orientation (C / D6)

All of this is the suite-wide GUI debt (SUITE.md §5 gap #9); fix once in
`common-security`, inherit here.

### 9.1 Theme tokens (SHARED)

Every color is a hardcoded hex literal (`0xFF0A0A0A/1C1C1C/E0E0E0/9E9E9E/
FFB74D/EF5350`, throughout `MainActivity.kt` + `FoldersScreen.kt`) over a
token-free `darkColorScheme()` (`MainActivity.kt:142`). Adopt the SHARED
`common-security` M3 token set (a proper `darkColorScheme(...)` mapping
background/surface/onSurface/primary/error to these values, plus a `Typography`
scale). Replace every `Color(0xFF…)` literal with
`MaterialTheme.colorScheme.*` and every ad-hoc `fontSize = N.sp` with a
`MaterialTheme.typography.*` role. Warning-amber (`FFB74D`) and error-red
(`EF5350`) map to a suite `tertiary`/`error` pair.

### 9.2 Components

No `Scaffold`/`TopAppBar` today. Wrap each screen in `Scaffold` with a
`TopAppBar` (title from strings), a `SnackbarHost` (replaces the Toast+red-slot
pattern for results, incl. §6.4), and content padding. Keep the exemplary
delete-confirm dialogs (A9) as-is structurally.

### 9.3 Strings → resources

`strings.xml` has only `app_name` (`strings.xml:3`); 100% of UI copy is
hardcoded Kotlin. Move every user-facing string to `res/values/strings.xml`
(setup warnings, add copy, shred copy, delete copy, folders copy, new
recovery/viewer/deposit copy). `resourceConfigurations += listOf("en")`
(`build.gradle.kts:17`) stays for now (self-consistent en-only) but l10n is
unblocked. Also fix the `app_name` / launcher label to the suite name
(§ top: "Understory File Vault") — that is the one machine-vs-display rename
(SUITE.md §4.5).

### 9.4 Orientation + sizing

`screenOrientation="portrait"` + `resizeableActivity="false"`
(`AndroidManifest.xml:162-163`) block landscape/foldable/DeX and are an a11y
regression. RECONSIDER: drop the portrait lock and set
`resizeableActivity="true"`. The screens are simple `Column`/`LazyColumn`
layouts that reflow trivially; `configChanges` already includes
`orientation|screenSize` (`:164`) so no state loss. The only reason to keep the
lock would be a viewer that assumes portrait — the §5 viewer should instead
adapt (a PDF/image viewer benefits from landscape). Ship unlocked + resizeable
unless a concrete One-UI break is found. Sub-12sp metadata/footer text
(9-11sp) moves up to the typography scale's `bodySmall`/`labelSmall` (≥12sp).

### 9.5 Tamper hard-fail honesty

The tamper/attestation hard-fail exits (`MainActivity.kt:106-113`) should show a
brief honest screen ("Integrity check failed — this build won't run") rather
than a bare exit, consistent with the suite honest-UI pass (CD-4c). Low effort;
group with the crash-screen copy cleanup (`MainActivity.kt:89-96`).

---

## 10. DOC / COMMENT DRIFT + DROPPED BAGGAGE (#14, preamble)

Pure editing, but load-bearing (a future session trusts these):
- Manifest "Activity-isolation set relaxed for the testing phase" comment
  (`AndroidManifest.xml:152-156`) — flags are already production (false=live);
  reword to reflect shipped posture.
- MainActivity RELEASE-BLOCKER comments (`:178-181`, `:204-206`) — the
  `TestingMode` flags they warn about are already `false`
  (`TestingMode.kt:34,56`); delete the stale warnings.
- Manifest deposit-confirmation comment (`:174-180`) — currently claims a
  confirm that doesn't exist; §3 makes it true, so the comment becomes accurate
  (keep it, aligned with §3).
- `SUITE_DESIGN.md:598` (`BackupProvider` claim), `:815` / `:577-581`
  (POST_NOTIFICATIONS / READ_MEDIA_* permission rows vault-folder doesn't hold)
  — correct to match the stricter shipped manifest (A19).
- `SUITE_THREAT_SURFACES.md:138-140` confirmation claim — resolved by §3.

DROP-TO-V2 (note only, no code this pass): unused `common-security` baggage
compiled into the APK (`A11yProbe`, `Clipboard`, `HotpSecret`, `OtpAuthUri`,
`Totp`, `DeviceProfile`) is APK bloat with zero behavior. Do NOT strip
piecemeal — several of these (`argon2`/`generateMasterPassword`) become
load-bearing under §4 recovery. Revisit module-splitting of `common-security`
suite-wide later; not a vault-folder-local fix.

---

## 11. COMPLEMENT POSITIONING (honest pitch, CD-1 / A.E)

vault-folder is **the portable, inspectable encrypted drop-box beside Samsung
Secure Folder — not under it.** Secure Folder gives OS-level Knox isolation with
vendor lock-in, opaque internals, and no cross-device story. vault-folder gives:
an auditable AES-256-GCM-per-file format in ordinary app storage; a biometric
gate bound to the hardware Keystore; a universal "Open with… → confirm → encrypt
into vault" deposit target (§3) any app can use — including a file manager
exporting *out* of Secure Folder; plain-file SAF export with zero lock-in (§1);
an in-app memory-only viewer so looking at a file no longer means spilling
plaintext (§5); and a recovery phrase that makes the vault portable/restorable
where Secure Folder is not (§4). No slot contention: no VpnService, no
autofill/IME/accessibility, no default-app role (B). The honest one-liner:
"when you want to *know* how your files are encrypted, drop files in from any
app's share flow, view them without exporting, and keep the format portable —
use this; when you want whole-app OS isolation, keep using Secure Folder — we
don't touch it." The three things that must land for that sentence to be true:
the export crash (§1), the deposit-confirm honesty (§3), and either the viewer
or an honest "viewing means export" line (§5 ships the viewer). The deposit
primitive and the shred-source honesty model are already best-in-suite examples
of the doctrine working.

---

## 12. Implementation order (dependency-sorted)

1. **§1 export crash fix** + `"wt"` + dead-launcher drop — smallest, stops the
   one confirmed hard crash. Ship first.
2. **§8 provider authority** — one-line, unblocks eng/prod side-by-side testing
   of everything else.
3. **§2 off-main-thread** + **§6 folders defects** (executor, weight,
   pruneOrphans, delete guard, rename, cancel code) — correctness/UX, no format
   change.
4. **§3 deposit confirm + onNewIntent + BROWSABLE drop** — honesty gate.
5. **§7 AAD / format v2** together with **§4 recovery header_v2** — one format
   bump; do them in the same change.
6. **§4 recovery/reset UI + backup copy honesty** — the data-loss cliff.
7. **§5 isolated-process viewer** — largest, self-contained, gated behind
   FLAG_SECURE verification.
8. **§9 GUI/theme/strings** (mostly inherited from shared common-security) +
   **§10 doc pass** — sweep last so it covers the new §3/§4/§5 copy.
