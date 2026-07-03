# Design v2 — understory-antivirus (`com.understory.antivirus`)

Store face: **Understory APK Check**. Category: offline, on-demand APK auditor that
**complements Google Play Protect** — never replaces it. Written 2026-07-03 against the
NEW SUITE DOCTRINE and the V2 audit (`docs/audit-v2/antivirus.md`, `SUITE.md`). This
document is the implementation spec: an implementer builds from it without re-deriving.

Scope of this doc: resolve every audit finding D#1–D#16 with a concrete disposition
(FIX / REDESIGN / DROP), naming exact Android APIs, files, and screen states. It does
**not** touch the isolated-parser chain (A1), TransientFlight symmetry, fd lifecycle,
backup exclusion, or the INTERNET strip — those are verified sound and stay as-is.

Ground rules honored throughout:
- **No INTERNET.** `INTERNET` is `tools:node="remove"` (AndroidManifest.xml:39) backed by
  `network_security_config.xml`. Every mechanism below is offline. The blocklist update
  path is **SAF file import of a signed blob**, never a network sync.
- **No scarce slots.** No VPN, autofill, IME, accessibility, notification-listener,
  device-admin, or usage-stats *held by us*. We only *read/enumerate* other apps' holdings.
- **Read-and-advise.** We emit findings and evidence, never verdicts-as-truth, never
  automated remediation. The user (or a suite peer like firewall) acts.

---

## 0. POSITIONING (resolves audit §E, SUITE §4.5, D-positioning)

**Understory APK Check is the explainable second opinion beside Play Protect — the auditor
that shows its work where Play Protect shows a checkmark.** Every user-facing surface states
this explicitly. Play Protect is a cloud-backed, opaque, install-time verdict engine; we are
its inverse and therefore its complement: fully offline (structural, not promised — no
INTERNET permission exists), on-demand, and *legible*.

Concrete copy contract (string resources, §9): the home screen carries a one-line
positioning banner — `av_positioning` = *"Works alongside Google Play Protect. Play Protect
checks apps against Google's cloud at install time; APK Check inspects sideloaded APKs and
what your installed apps can do — offline, on your device, showing its work."* The word
"replace" / "replacement" appears **nowhere**. The Play Protect card (D#4) links *into* Play
Protect settings and tells the user to keep it on.

The four jobs we own next to the incumbent (each maps to a shipped mechanism below):
1. **Pre-install SAF scan** of a sideloaded APK inside the crash-proof isolated parser (A1,
   unchanged) — Play Protect only sees it at install commit.
2. **Installed-app posture review** — permission *shapes* + abuser enumeration (D#2) +
   hidden-launcher (A3) — what an app **can do**, in English.
3. **Signed offline blocklist** — hash/cert deny-list the user imports (D#3), catching
   specific known-bad builds/repackager certs without any network.
4. **Tamper-of-the-scanner surfacing** — report (not block) when a patcher/hooking tool or a
   disabled Play Protect is present (D#4, D#7), which is exactly the stalkerware setup signal.

---

## 1. D#3 — KnownBad: signed SAF-imported blocklist (REDESIGN of the empty placeholder)

**Problem (A4):** `KnownBad.apkHashes`/`certHashes` are `setOf()` (KnownBad.kt:28,38); the
home screen claims "known-bad APK hashes, repackager signing certs" (MainActivity.kt:231-236,
218). Plumbing works, data is empty, claim is live. No importer, no signature format exists.

**Disposition: REDESIGN into a real signed-blob data path**, seeded with a small honest
built-in set, extensible by user-imported signed definition files. Consistent with no-INTERNET.

### 1.1 On-disk format — `understory-blocklist.ubl` (versioned, signed, offline)

A definitions file is a single UTF-8 JSON object wrapped in a detached-signature envelope.
Two concatenated length-prefixed sections in one file so it round-trips through SAF as one
document:

```
magic:      "UBL1"                         (4 ASCII bytes)
payloadLen: uint32 big-endian              (4 bytes; cap 4 MiB, reject larger)
payload:    <payloadLen> bytes of canonical JSON (UTF-8), the definitions
sigLen:     uint16 big-endian              (2 bytes; == 64 for Ed25519)
signature:  <sigLen> bytes                 (Ed25519 signature over the payload bytes)
```

Payload JSON schema (canonical form = keys sorted, no insignificant whitespace, so the
signed byte range is deterministic and re-verifiable):

```json
{
  "schema": 1,
  "issued": "2026-07-03T00:00:00Z",
  "serial": 3,
  "apkSha256":  ["<64-hex>", ...],
  "certSha256": ["<64-hex>", ...],
  "labels": { "<64-hex>": "Lucky Patcher 10.x", ... }
}
```

- `apkSha256` / `certSha256`: lowercase hex, no separators, exactly 64 chars each (matches the
  existing `KnownBad` storage shape and `signingCertSha256` output in ApkAnalyzer.kt:335-343).
  Entries validated on import; malformed entries **drop the whole file** (fail closed).
- `labels`: optional human-readable name per hash, shown in the finding ("matches: Lucky
  Patcher 10.x") so a match is legible, not just "known-bad."
- `serial`: monotonic. Import refuses a file whose `serial` is ≤ the currently-installed
  serial (anti-rollback), unless the user checks an explicit "allow older definitions" box.
- All-zero hash remains the reserved sentinel (KnownBad.kt:17-19 doc), rejected on import.

### 1.2 Signature verification (offline public key, `androidx.security`-free)

- **Algorithm: Ed25519** via `java.security.Signature.getInstance("Ed25519")` (API 33 has it;
  minSdk 33 per build.gradle.kts:13). No BouncyCastle, no external crypto dep.
- **The trusted public key is compiled into the app** as a 32-byte constant in a new
  `BlocklistKeys.kt` (mirrors how `SuitePins.EXPECTED_CERT_SHA256` pins the app cert). It is
  **not** derived from anything on device and never fetched. Definitions are signed off-device
  by the maintainer's Ed25519 private key (kept in the operator vault, out of the repo).
- Verify path (new `BlocklistCodec.verifyAndParse(bytes): Result<BlocklistPayload>`):
  1. Check `magic == "UBL1"`, bounds-check the two length prefixes against `bytes.size` (same
     defensive-parsing discipline as RawApkParser — every offset bounds-checked, hard caps).
  2. `Signature.getInstance("Ed25519")` → `initVerify(publicKey)` → `update(payload)` →
     `verify(signature)`. **Failure → reject the whole file** (return `Result.failure`), never
     partial-trust.
  3. Parse canonical JSON with `org.json` (platform, no dep); validate every hash is 64-hex;
     reject on any violation.
- This runs on `Dispatchers.IO` (import is user-initiated, off main thread). A 4 MiB cap +
  Ed25519 verify is sub-100ms; no ANR risk.

### 1.3 Storage & merge

- New `BlocklistStore` (object) persists the *verified* payload to app-internal storage:
  `filesDir/blocklist/active.ubl` (the raw signed bytes, re-verified on every load so a
  tampered-at-rest file is caught) plus a tiny `filesDir/blocklist/meta.json`
  (`{serial, issued, apkCount, certCount}`) for the UI status line. Atomic replace
  (write tmp, `File.renameTo`) — same pattern the vault engines use.
- **Built-in seed**: a small signed `res/raw/blocklist_seed.ubl` shipped in the APK, loaded on
  first run if no imported file exists. Seed contents (the honest, defensible starter set —
  these are *unwanted-tooling* signals, not a malware DB claim):
  - The **9 Lucky-Patcher-family package certs** already enumerated in Tamper.kt:166-176 — but
    as **cert-SHA-256 of their signing certs**, so repackaged variants under the same keystore
    also match. (Seed generation: install each in a sandbox, read `apkContentsSigners`, record
    the digest. Documented in `docs/BLOCKLIST_SEED.md`, not derived at runtime.)
  - A short curated set of **known repackager/stalkerware signing certs** from public threat
    reports (e.g. StopStalkerware coalition IOCs, MVT `appid` lists). Source and provenance for
    every entry recorded in `docs/BLOCKLIST_SEED.md` with a citation; **no entry ships without a
    documented source** (honest-UI: we can defend every hash we flag).
  - Seed `serial = 1`. Real-world size: tens of certs, a handful of hashes — kilobytes.
- `KnownBad` becomes a thin facade over `BlocklistStore` (keeps call sites in
  ApkAnalyzer.kt:201-208,283-289 unchanged):
  ```kotlin
  object KnownBad {
      fun isKnownBadApk(sha256: String): Boolean = BlocklistStore.apkHashes().contains(sha256.lowercase())
      fun isKnownBadCert(sha256: String): Boolean = BlocklistStore.certHashes().contains(sha256.lowercase())
      fun labelFor(sha256: String): String? = BlocklistStore.label(sha256.lowercase())
  }
  ```
  `BlocklistStore` caches the two sets in memory after load; ApkAnalyzer's per-app SHA-256 pass
  (below) is only paid when the sets are non-empty (D#3 tail — see §1.4).

### 1.4 Skip the wasted hashing when the deny-list can't match (D#3 tail)

Today `auditInstalled` pays SHA-256 of every user APK (ApkAnalyzer.kt:237, 316-327) even when
both sets are empty — pure battery burn (potentially GB of hashing). With a seed shipped the
sets are non-empty, but still: `analyzeFromPackageInfo` computes `apkSha` unconditionally.
Change to **compute the APK hash lazily** — only when `BlocklistStore.apkHashes().isNotEmpty()`.
The cert digest is cheap (one cert, already parsed by the system) and always computed. Concretely:

```kotlin
val apkSha = if (BlocklistStore.apkHashes().isNotEmpty()) sha256(apkFile) else null
```

The report's `apkSha256` field renders "(not hashed — no APK-hash definitions loaded)" when
null, which is honest. (SAF single-file scan in `analyzeUri` still always hashes — the hash is
shown to the user as evidence there, and it's one file.)

### 1.5 Import flow — UI

New home action **"Import definitions"** → `ActivityResultContracts.OpenDocument` with
`arrayOf("*/*")` (Drive/Files expose `.ubl` as octet-stream; we validate by magic+signature,
same rationale as the APK picker MainActivity.kt:333-338). On pick:
- `TransientFlight.begin()/end()` around the round-trip (same discipline as the APK scan).
- Off-main verify+store on `Dispatchers.IO`.
- Result states (all honest, no dead ends):
  - **Success**: card shows "Definitions updated — serial N, issued <date>, M app-hashes +
    K cert-hashes." Diagnostics logged.
  - **Bad signature / bad magic / malformed**: red card "This file isn't a valid signed
    Understory definitions file (signature check failed). Nothing was imported." — never
    partial import.
  - **Older serial**: amber card "These definitions (serial N) are older than what's installed
    (serial M). Import anyway?" with an explicit confirm.
- Home screen carries a permanent **definitions status line** (from `meta.json`): "Definitions:
  built-in seed (serial 1)" or "imported serial N, <date>." No overclaim — the user always
  knows exactly what's loaded.

### 1.6 Copy correction (honesty)

Home "what this catches" copy (MainActivity.kt:231-236) is rewritten to match reality:
*"Signed offline deny-list (Lucky-Patcher-family and known repackager signing certs; import
more via a signed file), permission-shape heuristics, hidden-from-launcher detection, and
abuser enumeration for accessibility / device-admin / notification-listener."* The
`av_home_catches` string reflects exactly the shipped mechanisms — nothing "phase 2."

---

## 2. D#2 — Accessibility / device-admin / notification-listener abuse detection (REDESIGN)

**Problem (A5):** the three highest-value rules key on `BIND_ACCESSIBILITY_SERVICE` /
`BIND_DEVICE_ADMIN` being in the *requested-permission set* (RiskRules.kt:48-49,76-107). Those
are **component-protection permissions** — the system holds them; a real abuser declares
`<service android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">`, it does **not**
`<uses-permission>` it. So these rules fire only on malware that cargo-cults the perm into
`uses-permission`, i.e. essentially never. This is the biggest detection gap.

**Disposition: REDESIGN** across two code paths (installed-app audit; SAF APK scan) so the
rules actually enumerate abusers, plus a live "currently-enabled" signal.

### 2.1 Installed-app audit — enumerate declared *and* enabled abusers

Two complementary signals, both real:

**(a) Declared-component signal** — extend `analyzeInstalled`'s `getPackageInfo` flags
(ApkAnalyzer.kt:108-111) to also request components:

```kotlin
pm.getPackageInfo(packageName,
    PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES or
    PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
```

Then a new `RiskRules.analyzeComponents(info)` inspects:
- **Accessibility service declared**: any `info.services` whose `ServiceInfo.permission ==
  "android.permission.BIND_ACCESSIBILITY_SERVICE"`. → HIGH "Declares an accessibility service"
  (an a11y service *can* read all on-screen text and synthesize taps).
- **Device-admin receiver declared**: any `info.receivers` whose `ActivityInfo.permission ==
  "android.permission.BIND_DEVICE_ADMIN"`. → HIGH "Declares a device-admin receiver."
- **Notification-listener declared**: any `info.services` whose `ServiceInfo.permission ==
  "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`. → HIGH "Declares a
  notification-listener (can read every notification's content)."
- CRITICAL combo preserved with the *correct* input: declared-accessibility-service **AND**
  `INTERNET` in requested perms → CRITICAL "Accessibility + internet exfiltration profile" (the
  RAT shape RiskRules.kt:76-87 always meant to catch).

**(b) Currently-enabled signal** (stronger — the app is *active as an abuser right now*). This
is device-wide state, computed once per audit and cross-referenced by package, in a new
`EnabledAbusers.snapshot(ctx)`:
- **Enabled accessibility services**:
  `AccessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)`
  → each `AccessibilityServiceInfo.resolveInfo.serviceInfo.packageName`. An app in this set is
  a **CRITICAL** finding ("This app's accessibility service is currently ENABLED — it can read
  everything on your screen and act as you right now").
- **Active device admins**:
  `(getSystemService(DevicePolicyManager::class.java)).activeAdmins` → each
  `ComponentName.packageName`. In-set → CRITICAL "Currently an active device administrator —
  can lock/wipe the device and resist uninstall."
- **Enabled notification listeners**:
  `NotificationManagerCompat.getEnabledListenerPackages(ctx)` (public, no listener slot needed)
  → set of package names. In-set → HIGH "Currently reading all your notifications."
- **Usage-stats grantees** stay as-is via the permission heuristic (RiskRules.kt:155-163) — no
  clean public enumeration of *granted* PACKAGE_USAGE_STATS exists rootless; the requested-perm
  heuristic is honest there and we keep it.

Enabled-state findings carry a `deeplink` action (§7) so the user can jump straight to
Settings > Accessibility / Device admin apps / Notification access to revoke.

None of (a)/(b) requires us to *hold* any slot — `AccessibilityManager`,
`DevicePolicyManager.activeAdmins`, and `getEnabledListenerPackages` are all readable by any
app. This is pure enumeration of *others'* holdings, doctrine-clean (SUITE §1 slot matrix
already lists antivirus as "audits others'").

### 2.2 SAF APK scan — declared-component detection in the isolated parser

For a not-yet-installed APK we have no PackageManager view, only the parsed manifest. The
enabled-state signal is N/A (not installed). We add the **declared-component** signal by
extending the raw parser.

`RawApkParser.parseBinaryManifest` currently only walks `manifest` and `uses-permission`
elements (RawApkParser.kt:290-293). Extend it to also capture, for `<service>` and
`<receiver>` START_ELEMENT chunks, the value of the `android:permission` attribute
(`resId == 0x01010006` = `attr/permission`, with string-name fallback "permission" — same
resId+fallback pattern already used at RawApkParser.kt:302-316). Collect into two new lists on
`AxmlManifest` / `ApkParseResult`:

- Add to `ApkParseResult` (ApkParseResult.kt) two new parcel fields:
  `servicePermissions: List<String>` and `receiverPermissions: List<String>` (append to
  `writeToParcel`/`CREATOR` in order — the wire format is manually versioned and append-only,
  documented in the class KDoc). Bump the class doc's wire-version note.
- `RiskRules.analyze` (or a new overload taking these lists) then fires the same declared-a11y
  / declared-device-admin / declared-notif-listener findings from §2.1(a), including the
  CRITICAL a11y+INTERNET combo, on SAF-scanned APKs.

Bounds discipline unchanged: every new attribute read stays inside the existing
`require(a + 20 <= pos + chunkSize)` guard (RawApkParser.kt:297). Caps: collect at most 256
component-permission strings (defensive; a benign APK has a handful).

### 2.3 Dead-input cleanup (D#12, folded in here)

- Delete the dead `accessibility`/`deviceAdmin` **uses-permission booleans**
  (RiskRules.kt:48-49) and the three rules that consumed them (RiskRules.kt:76-107) — replaced
  by §2.1/§2.2 component logic. (Do not silently keep them; they are the false-comfort A5 gap.)
- `readCallLog` is computed but unused (RiskRules.kt:43) — add the rule it implies: `readCallLog
  && internet` → MED "Reads call log + internet — can exfiltrate who you call." Add a SEND_SMS
  rule: `"android.permission.SEND_SMS" in permissions` → MED "Can send SMS silently
  (premium-SMS toll-fraud shape)."

---

## 3. D#4 — Play Protect card: kill the false-green (FIX)

**Problem (A6):** `Settings.Global.getInt(cr, "package_verifier_enable", 1) == 1`
(PlayProtectStatus.kt:58) — a **defunct pre-Oreo key** with **default 1**, so a missing key
reads as "enabled." On the target Samsung SM-S948U (modern One UI) the key is absent and the
real Play Protect toggle lives in Play services and does not write it. The card shows
**"Play Protect: ON" regardless of the actual state** — the exact signal the class exists to
catch (PlayProtectStatus.kt:10-14) cannot fire. False-green is worse than no card.

**Disposition: FIX to honest degradation + deep-link.** There is no rootless public API that
returns Play Protect's real enabled/disabled state; do not pretend one exists.

Rewrite `PlayProtectStatus.check`:
- Keep the GMS-presence check (PlayProtectStatus.kt:41-55) → `NOT_APPLICABLE` when no GMS. Good.
- Replace the verifier read with the **2-arg** `Settings.Global.getInt(cr, key)` inside
  try/catch: `SettingNotFoundException` (the normal modern-device case) → **UNKNOWN**, not
  ENABLED. When the key *is* present and readable (older device / de-Googled with the legacy
  toggle), still surface `0 → DISABLED` / `1 → ENABLED` — it's a valid signal *there*.
- **Collapse the ENABLED default-green path**: never return ENABLED from a *missing* key. On a
  modern GMS device the honest state is UNKNOWN with copy: *"We can't read Play Protect's state
  on this Android version — its setting isn't exposed to apps. Open Play Protect to verify it's
  on."* plus a deep-link button.
- **UNKNOWN is the modern-device default**, and that is *correct* — read-only observation is the
  right complement posture; we don't claim a green we can't verify.

Card rendering (`PlayProtectCard`, MainActivity.kt:261-278):
- UNKNOWN accent stays amber. Add a secondary **"Open Play Protect"** button that fires the
  public deep-link intent:
  `Intent("com.google.android.gms.settings.VERIFY_APPS_SETTINGS")` — try/catch, and on
  `ActivityNotFoundException` fall back to `Settings.ACTION_SECURITY_SETTINGS`, and if that
  fails too, the button disables-with-reason ("Play Protect settings not reachable on this
  device"). No dead control.
- The card copy always tells the user to keep Play Protect on — reinforcing complement, never
  "we've got this instead."

---

## 4. D#1 — Severity sort inverted twice (FIX)

**Problem (A2):** `Severity { CRITICAL, HIGH, MED, LOW }` so `CRITICAL.ordinal == 0`, but
`RiskRules.analyze` returns `sortedByDescending { it.severity.ordinal }` (RiskRules.kt:193) →
**LOW first, CRITICAL last**. This corrupts `ReportView` order (MainActivity.kt:499), the
per-row headline (MainActivity.kt:430-434 shows the *least* severe), and the audit-list ranking
(ApkAnalyzer.kt:143-148, descending-ordinal = LOW-first apps above CRITICAL-first).

**Disposition: FIX (S).**
- RiskRules.kt:193 → `findings.sortedBy { it.severity.ordinal }` (CRITICAL=0 first).
- ApkAnalyzer.kt:143-148 list ranking: rank by *ascending* ordinal of the (now-correctly-first =
  most-severe) finding, KNOWN_BAD pinned top. Concretely, since first-finding is now the most
  severe, sort key = `if (KNOWN_BAD) -1 else findings.firstOrNull()?.severity?.ordinal ?: Int.MAX`
  with `compareBy` (ascending) then `thenBy { packageName }`. Result: KNOWN_BAD, then CRITICAL,
  HIGH, MED, LOW-first apps, alphabetical within a tier.
- Delete the dead `Report.summary` (ApkAnalyzer.kt:55-60) — never referenced, and it re-embeds
  the same `firstOrNull` assumption.

Verdicts are computed by `any {}` (ApkAnalyzer.kt:210-211,291-292) and are unaffected — this is
purely display/ranking. Guard with the new unit tests (§8).

---

## 5. D#6 — "Real-time" install-watching (REDESIGN → PERIODIC_SCANNER)

**Problem (A7/A8):** SUITE_ROADMAP.md:40 claims "Inotify-watching app-installs";
SUITE_DESIGN.md:683-702 plans an install-watcher. Reality: no receiver/service/worker exists
(manifest is MainActivity + ApkParserService + SuiteCapsProvider only); **inotify on `/data/app`
is impossible rootless**; **manifest `PACKAGE_ADDED` receivers have been dead since API 26**; and
the app strips `POST_NOTIFICATIONS`. The suite capability beacon advertises `REALTIME_SCANNER`
(A8) — a cadence no rootless app can deliver.

**Disposition: REDESIGN to the honest rootless shape, rename the capability, no dead UI.**

### 5.1 The viable mechanism: WorkManager periodic re-scan + on-open freshness

- **`PeriodicWorkRequest`** (WorkManager, add `androidx.work:work-runtime-ktx`) named
  `av-periodic-scan`, minimum interval 6h (WorkManager floor is 15 min; 6h is battery-honest),
  `ExistingPeriodicWorkPolicy.KEEP`. The worker:
  1. Reads `pm.getInstalledPackages(0)` and diffs against a stored snapshot
     (`filesDir/scan/known-packages.json`: pkg → {versionCode, lastUpdateTime}).
  2. For each **new or updated** package (changed `lastUpdateTime`/`versionCode`), runs the
     existing `ApkAnalyzer.analyzeInstalled` + §2 component checks.
  3. If a new/updated app produces a CRITICAL/HIGH finding or a KNOWN_BAD verdict, and the user
     opted into alerts (§5.2), posts a notification deep-linking into the audit detail.
  4. Persists the fresh snapshot.
- **On-open freshness**: a *context-registered* `BroadcastReceiver` for
  `Intent.ACTION_PACKAGE_ADDED` / `ACTION_PACKAGE_REPLACED`, registered in `onResume` and
  unregistered in `onPause` (lives only while the app is foreground — the only rootless place a
  PACKAGE_ADDED receiver still fires). While the app is open, a fresh install triggers an
  immediate on-demand analyze of just that package, surfaced as a top-of-list banner. This is
  *freshness while open*, explicitly not background real-time — and the UI says so.

This is a genuine capability (periodic + on-open), delivered rootless, with no overclaim.

### 5.2 POST_NOTIFICATIONS — opt-in, graceful degradation

- The manifest currently strips `POST_NOTIFICATIONS` (AndroidManifest.xml:99). **Stop stripping
  it**; declare it normally. It is **not** a scarce slot (any app may post) and it is opt-in.
- On first enabling periodic scanning, request the runtime permission (API 33
  `POST_NOTIFICATIONS`). If **denied**, periodic scanning still runs and results are visible in
  the app on next open; the settings row shows "Alerts off (notification permission not
  granted) — results still appear here." No dead control, honest status (doctrine CD-4).
- Periodic scanning itself is **default-off, opt-in** via a home toggle ("Periodically re-check
  installed apps — off"). Off = no worker enqueued, zero background cost. This respects the
  minimal-footprint posture and never runs surprise background work.

### 5.3 Capability rename (D#11, A8) — `PERIODIC_SCANNER`

- Rename the suite capability `REALTIME_SCANNER` → **`PERIODIC_SCANNER`** in
  `SuiteCapability.kt` and the `KNOWN_PEERS` translation (SuiteCapabilityRegistry.kt:72-74). The
  footer short-name mapping (SuiteStatusFooter.kt:150-155,182-183) then renders "peer caps:
  periodic-scanner" — truthful for a static+periodic auditor.
- **Coordinated suite bump** (SUITE §4.4 groups this with `BACKUP_ORCHESTRATOR` /
  `NETWORK_FILTER`): the enum value and every peer's `KNOWN_PEERS` table change together in one
  commit so no peer advertises a stale name. Provider `version` stays 1 (wire unchanged; only
  the human-facing label maps differently) — or bump to 2 if the enum ordinal shifts; the
  registry already handles unknown versions gracefully.
- Update SUITE_ROADMAP.md:40 and SUITE_DESIGN.md:683-702: replace "inotify-watching" with the
  §5.1 mechanism and mark inotify **rejected — rootless-impossible** (viability honesty).

---

## 6. D#7 — Tamper hard-fail: warn-not-die for the AV (REDESIGN)

**Problem (A10):** `onCreate` hard-gate refuses to launch (`finishAndRemoveTask()`,
MainActivity.kt:62-69) when `Tamper.check().hardFail` is true, and `hardFail` includes
`luckyPatcherInstalled || installedByPatcher` (Tamper.kt:47-52). So the one app whose *job* is
telling the user "you have a patcher/hooking tool installed" **refuses to run precisely when one
is present**, showing a silent instant-close that reads as a crash. Self-defeating for an AV; the
stalkerware-victim journey depends on getting a *finding*, not a dead app.

**Disposition: REDESIGN — for THIS app, demote the patcher-present signals to a prominent
finding; keep the integrity-of-*this-app* signals as hard-fails; never exit silently.**

`Tamper.Report` is a **shared common-security type** consumed on hot paths by other apps
(IME/autofill — Tamper.kt:66-71). **Do not change `hardFail`'s shared definition.** The
antivirus-specific policy lives at the **call site** and in a new local helper:

- New `AvTamperPolicy.evaluate(report): AvGate` in the antivirus module:
  - **Hard-fail (still refuse, because these mean *our own binary* is compromised and any
    finding we'd show is untrustworthy):** `!report.signatureMatches || report.xposed ||
    report.frida`.
  - **Report-not-refuse (surface as a finding, keep running):** `report.luckyPatcherInstalled`,
    `report.installedByPatcher`, and `report.warnings` (root markers, test-keys).
- `MainActivity.initialize` (MainActivity.kt:64-69) and `onResume` (MainActivity.kt:153-156)
  call `AvTamperPolicy.evaluate` instead of `report.hardFail`. Only the reduced hard-fail set
  finishes the activity.
- **No silent exit.** When the reduced set *does* hard-fail, show a full-screen honest
  explanation before finishing (not a bare `finishAndRemoveTask()`): a Compose
  `TamperBlockScreen` — "Understory APK Check can't run safely: this build's signature doesn't
  match / a hooking framework (Xposed/Frida) is active. That means the scanner itself may be
  tampered with, so its results can't be trusted. Reinstall from a trusted source." with a
  single "Close" button that then finishes. (This screen is antivirus-local; the suite-wide
  explanation-screen consolidation is SUITE §5.9, but this app ships its own now.)
- **The patcher/hook-tool finding** becomes a first-class home-screen **Tamper card** (new
  `TamperCard`): if `luckyPatcherInstalled || installedByPatcher || warnings.isNotEmpty()`, show
  a CRITICAL/HIGH card naming what was found ("Lucky Patcher is installed on this device — a
  repackaging/patching tool commonly used to trojanize apps") with the same legible,
  non-alarmist copy discipline as the rest of the app. This *is* the D#13 root-tooling card (§7).

Rationale: signature-mismatch/Frida/Xposed mean **our findings are untrustworthy** → refuse
(with explanation). A patcher merely *installed on the device* is **exactly what we exist to
report** → report. This is the doctrine-correct "report, don't block."

---

## 7. D#13 + deep-links — root/hooking-tooling informational card, dead `<queries>` (FIX)

**Problem:** the Magisk/Xposed-manager/SuperSU/Kingo/ROM-manager `<queries>` rows
(AndroidManifest.xml:140-148) are referenced by **no code** — leftover intent, redundant under
QUERY_ALL_PACKAGES.

**Disposition: implement the implied informational card (fits complement doctrine: report,
don't block), which also gives §6's TamperCard its content.** The `TamperCard` (§6) surfaces:
- Lucky-Patcher family present (from `Tamper.luckyPatcherInstalled`).
- Root markers / test-keys (from `Tamper.warnings`).
- Optionally, presence of the packages in those `<queries>` rows (Magisk etc.) as an
  *informational* "root/hooking tooling detected" line — **not** a verdict, honest copy ("root
  and hooking tools have legitimate power-user uses; they also let malware do more — listed here
  so you know what's on the device"). This keeps the `<queries>` rows *used*.

Deep-link actions (shared helper `SettingsDeepLinks`): every enabled-abuser finding (§2.1b) and
the Play Protect card (§3) carry a "Fix in Settings" button firing the correct public intent
(`Settings.ACTION_ACCESSIBILITY_SETTINGS`, device-admin via
`Settings.ACTION_SECURITY_SETTINGS`/`DevicePolicyManager` add-admin screen is not used — we only
*revoke*-guide, so `ACTION_SECURITY_SETTINGS`, `ACTION_NOTIFICATION_LISTENER_SETTINGS`), each
try/catch with disable-with-reason fallback.

---

## 8. D#9 — Unit tests (FIX)

**Problem:** the antivirus module has **no test source set** (CI runs `testDebugUnitTest`,
android.yml:22, but only vendored common-security tests run). The suite's most-hostile parser is
untested.

**Disposition: add `antivirus/src/test/java/...` pure-JVM tests.** No gradle changes needed
beyond `testImplementation(junit)` (already available suite-wide). Coverage:
- **RawApkParser** (fd via a temp file — pure JVM): golden minimal APK; truncated EOCD;
  duplicate-AndroidManifest → FLAG_DUPLICATE_MANIFEST; oversized manifest > cap → FLAG_BAD_ZIP;
  UTF-8 and UTF-16 string pools; obfuscated attr-name-stripped manifest (resId fallback);
  **new**: `<service android:permission="…BIND_ACCESSIBILITY_SERVICE">` captured into
  `servicePermissions` (§2.2).
- **RiskRules**: combo matrix — SMS-stealer, surveillance, the corrected CRITICAL a11y+internet
  from component input; **severity ordering assertion** (CRITICAL first) locking D#1;
  call-log+internet and SEND_SMS rules (§2.3).
- **BlocklistCodec** (§1.2): valid signed blob parses; flipped signature byte → reject; truncated
  file → reject; non-64-hex entry → reject; older serial handling; all-zero sentinel rejected.
- **Severity/verdict**: KNOWN_BAD pins top; ranking is most-severe-first (locks D#1 in
  `auditInstalled`'s comparator via a pure helper extracted from ApkAnalyzer.kt:143-148).

---

## 9. GUI (D#5, D#10, D#15, D#16) — screen by screen

Global posture unchanged and correct: FLAG_SECURE, overlay-hide, recents-off, portrait-lock,
dark-only (A11). Adopt **shared M3 tokens** from common-security (SUITE §5.9 consolidation:
`SuiteTheme`/color tokens + type scale) instead of per-widget hex — replace the ~dozen
`Color(0xFF…)` literals and raw `sp` sizes in MainActivity with token references
(`SuiteColors.verdictCritical`, `SuiteType.body`, etc.). Body text floor **12sp** (D#15).

**Strings → resources (D#15):** move every hardcoded Kotlin string to `res/values/strings.xml`
(today it holds only `app_name`, strings.xml:3). New keys: `av_positioning`, `av_home_catches`,
`av_scan_title`, `av_scan_pick`, `av_scanning`, `av_audit_*`, `av_pp_*`, `av_tamper_*`,
`av_defs_*`, plus every finding title/explain currently inline in RiskRules/ApkAnalyzer moved to
resources (or kept as constants but externalized copy for the UI chrome at minimum). Keep
`resourceConfigurations = ["en"]` (build.gradle.kts:17) for now — externalization is the
prerequisite for later locales, done now.

### Home / Initial (MainActivity.kt:204-258)
- Add **positioning banner** (`av_positioning`, §0) at top.
- **Play Protect card** (§3) — UNKNOWN-honest + "Open Play Protect" deep-link.
- **Definitions status line + "Import definitions" action** (§1.5).
- **Tamper card** (§6/§7) — shown only when there's something to report.
- **Periodic-scan toggle** (§5.2) — default off, honest "alerts off" sub-line if notif denied.
- "What this catches" card rewritten to §1.6 truthful copy.
- Two primary actions (Scan APK / Audit installed), Diagnostics, SuiteStatusFooter — unchanged.

### Scan APK (MainActivity.kt:280-355)
- **D#10 progress + double-tap**: while `working`, **disable** the pick button
  (`enabled = !working`) and show a `LinearProgressIndicator` under it. Copy of button stays
  "Scanning…". Kill the double-picker (button disabled prevents the second `pickApk.launch`).
- **D#14 timeout tri-state**: `ApkParserClient.parse` already distinguishes timeout from binder
  death internally (ApkParserClient.kt:88-91) but both collapse to `null` →
  "parser crashed / suspicious" (ApkAnalyzer.kt:158-174). Change `parse` to return a small
  sealed result (`Ok(result)` / `Died` / `TimedOut`) instead of nullable. `reportFromIsolatedParse`
  then maps `TimedOut` → an **UNKNOWN** report ("Scan timed out — the file is large or the device
  is busy; this doesn't mean it's malicious. Try again."), keeping `Died` → SUSPICIOUS (a parser
  that *crashed* on malformed input remains the real signal). False SUSPICIOUS on a slow-but-clean
  file erodes trust in the one verdict that matters.
- **D#16 state**: hoist scan `report`/`error` into a `rememberSaveable` via a `@Parcelize`-free
  Parcelable of the report (or a ViewModel). Low priority given portrait-lock+configChanges, but
  do the ViewModel so process-death doesn't blank the result. (Report is already a plain data
  class; make it `Parcelable` or store in a `SavedStateHandle` as its fields.)
- Report card findings now render **CRITICAL-first** (D#1).

### Audit installed (MainActivity.kt:357-455)
- **D#5 broken row (the headline visual bug)**: delete the overlay `Box` + full-width
  `OutlinedButton("Details")` drawn on top of the text (MainActivity.kt:437-448). Replace with a
  single `Column` inside a `Modifier.clickable(onClick = { selected = r })` with a real ripple
  (`Modifier.clip(RoundedCornerShape(6.dp)).clickable{}`) — whole-row touch target, text no
  longer obscured. Add a trailing chevron (`Icons.Filled.ChevronRight`, contentDescription
  "Details") as the affordance instead of an overlaid button.
- Per-row headline shows the **most-severe** finding now (D#1).
- **D#10 progress**: `auditInstalled` runs tens of seconds hashing (when defs loaded). Add a
  progress callback `auditInstalled(ctx, onProgress: (done: Int, total: Int) -> Unit)` invoked
  per package; the screen shows `LinearProgressIndicator(progress = done/total)` + "Checking n
  of N apps…". Button disabled while working (already guarded MainActivity.kt:381, add the bar).
- **Empty-result honesty**: when `list.isEmpty()`, show a positive clean state — a green-accent
  card "No apps flagged. Nothing here has a risky permission shape, a hidden launcher, or matches
  the deny-list." — instead of the current dangling "0 app(s) flagged. Tap an entry for details."
  (MainActivity.kt:408-411) that instructs a tap with nothing to tap.
- **Re-run affordance**: add a "Re-run audit" button in the results state (currently the user
  must back out and re-enter, MainActivity.kt audit screen).
- Detail view (ReportView + "Back to list") unchanged, findings CRITICAL-first.

### Diagnostics (shared) — unchanged (A9, correct).

---

## 10. D#8 — Provider authority `${applicationId}.suitecaps` (FIX)

**Problem:** authority hardcoded `com.understory.antivirus.suitecaps` (AndroidManifest.xml:196)
→ the `.eng` flavor (build.gradle.kts:71-75) collides with prod at install
(INSTALL_FAILED_CONFLICTING_PROVIDER). The registry already derives authority from package name
(SuiteCapabilityRegistry.kt:92-93).

**Disposition: FIX (S).** AndroidManifest.xml:196 →
`android:authorities="${applicationId}.suitecaps"`. Matches passgen's already-fixed form (SUITE
§1.2). Part of the suite-wide one-line pass (SUITE §10 / D-10).

---

## 11. Disposition table (every audit gap)

| # | Gap | Disposition | Where |
|---|-----|-------------|-------|
| D#1 | Severity sort inverted twice | **FIX** | RiskRules.kt:193 `sortedBy`; ApkAnalyzer.kt:143-148 ascending; delete dead summary. §4 |
| D#2 | a11y/device-admin rules never fire (uses-permission ≠ BIND_*) | **REDESIGN** | GET_SERVICES/GET_RECEIVERS + ServiceInfo.permission; AccessibilityManager/DevicePolicyManager/getEnabledListenerPackages; RawApkParser component-perm walk. §2 |
| D#3 | KnownBad empty while UI claims catches | **REDESIGN** | Signed `.ubl` blob, Ed25519 offline key, SAF import, seed set, lazy hashing, honest copy. §1 |
| D#4 | Play Protect false-green (defunct key, default=1) | **FIX** | 2-arg getInt → UNKNOWN on missing; deep-link. §3 |
| D#5 | Audit row: Details button over text | **FIX** | Delete overlay Box+button → Modifier.clickable row + chevron. §9 |
| D#6 | "inotify real-time" rootless-impossible / absent | **REDESIGN** | WorkManager periodic diff + on-open PACKAGE_ADDED; opt-in notif; rename to PERIODIC_SCANNER; fix roadmap. §5 |
| D#7 | Tamper hard-fail = silent exit when patcher present | **REDESIGN** | AvTamperPolicy: patcher→finding, sig/Frida/Xposed→hard-fail with explanation screen. §6 |
| D#8 | Hardcoded provider authority blocks eng+prod | **FIX** | `${applicationId}.suitecaps`. §10 |
| D#9 | Zero unit tests | **FIX** | Add src/test: RawApkParser, RiskRules, BlocklistCodec, ranking. §8 |
| D#10 | No progress; scan button not disabled | **FIX** | LinearProgressIndicator + enabled=!working; audit n/total callback. §9 |
| D#11 | REALTIME_SCANNER overclaims | **FIX** | → PERIODIC_SCANNER, coordinated KNOWN_PEERS bump. §5.3 |
| D#12 | Dead readCallLog input / missing rules | **FIX** | call-log+internet MED; SEND_SMS MED; drop dead a11y/device-admin uses-perm booleans. §2.3 |
| D#13 | Dead `<queries>` (Magisk/SuperSU/…) | **FIX (implement card)** | Root/hooking-tooling informational card uses them. §7 |
| D#14 | Parser timeout mislabeled as crash | **FIX** | Tri-state parse result; TimedOut→UNKNOWN, Died→SUSPICIOUS. §9 |
| D#15 | Strings hardcoded; sub-12sp; no tokens | **FIX** | strings.xml externalization; shared M3 tokens; 12sp floor. §9 |
| D#16 | Scan/audit state lost on recreation | **FIX** | ViewModel/SavedStateHandle; Report Parcelable. §9 |

**Not touched (verified sound, A1/A9/A11):** isolated-parser chain, TransientFlight symmetry,
fd lifecycle across Messenger, cache-file cleanup, backup exclusion, INTERNET strip + cleartext
deny, provider write-lock, eng dump gating, FLAG_SECURE posture.

## 12. New / changed files (implementer checklist)

New: `BlocklistKeys.kt` (Ed25519 pubkey const), `BlocklistCodec.kt` (verify+parse),
`BlocklistStore.kt` (persist+cache), `res/raw/blocklist_seed.ubl`, `docs/BLOCKLIST_SEED.md`
(provenance), `EnabledAbusers.kt` (a11y/admin/notif enumeration), `AvTamperPolicy.kt`,
`SettingsDeepLinks.kt`, `TamperBlockScreen`/`TamperCard`/`PeriodicScanToggle` composables,
`PeriodicScanWorker.kt` + context-registered PACKAGE_ADDED receiver, `antivirus/src/test/...`.
Changed: `KnownBad.kt` (facade), `PlayProtectStatus.kt` (2-arg getInt→UNKNOWN),
`RiskRules.kt` (component rules, severity sort, call-log/SEND_SMS, drop dead booleans),
`ApkAnalyzer.kt` (component flags, lazy hash, tri-state parse, ranking, delete summary),
`ApkParseResult.kt` (+servicePermissions/receiverPermissions), `RawApkParser.kt` (component-perm
walk), `ApkParserClient.kt` (sealed Ok/Died/TimedOut), `MainActivity.kt` (all GUI + tamper call
site + new cards/toggle), `AndroidManifest.xml` (authority `${applicationId}`, stop stripping
POST_NOTIFICATIONS, PERIODIC receiver, keep-and-use root-tool queries), `strings.xml`,
`themes.xml`/tokens, `SuiteCapability.kt` + `SuiteCapabilityRegistry.kt` (rename, suite-coordinated),
SUITE_ROADMAP.md:40 / SUITE_DESIGN.md:683-702 (viability-honest rewrite), `build.gradle.kts`
(+work-runtime-ktx, +junit test).
