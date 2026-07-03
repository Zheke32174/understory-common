# Audit v2 — understory-antivirus (`com.understory.antivirus`)

Audited 2026-07-03 against the NEW SUITE DOCTRINE (complement-don't-replace, viability
honesty, shippable = polished + zero dead UI). Read-only audit; every source file of the
module and its vendored shared modules was read in full. Vendored `common-security/` is
byte-identical to canonical `understory-common/common-security/` (verified by recursive diff).

Complement target: **Google Play Protect** (never claim replacement). The app already
frames itself correctly in its own UI copy (MainActivity.kt:218-224, 231-239) and surfaces
Play Protect status instead of competing with it — the *positioning* is right; several
*mechanisms* under it are not.

Scope inventory (all read):
`antivirus/src/main/java/com/understory/antivirus/` — MainActivity.kt, ApkAnalyzer.kt,
ApkParserClient.kt, ApkParserService.kt, RawApkParser.kt, ApkParseResult.kt, KnownBad.kt,
RiskRules.kt, PlayProtectStatus.kt, SuiteCapsProvider.kt; AndroidManifest.xml;
res/values/strings.xml, themes.xml; res/xml/*; build.gradle.kts; CI workflow; plus
common-security (Tamper, SuiteAttestation, SuiteCapabilityRegistry, BaseCapabilityProvider,
SuitePins, TestingMode, TransientFlight, Diagnostics/Dump/Screen, KeepAliveBackHandler,
SuiteStatusFooter); suite docs SUITE_DESIGN.md, SUITE_ROADMAP.md, RELEASE_BLOCKERS.md,
SUITE_THREAT_SURFACES.md, SAMSUNG_QUIRKS.md.

---

## A. FEATURE LEDGER

### A1. SAF APK scan via isolated-parser chain — **WORKING** (with two caveats)

The 2026-07-03 isolation chain is real and traces end to end:

1. UI: `ScanApkScreen` opens SAF `OpenDocument` with `*/*` (MainActivity.kt:288-317, 338),
   wraps the round-trip in `TransientFlight.begin()/end()` correctly on all three exits —
   callback (MainActivity.kt:294), launch-failure (MainActivity.kt:340), cancel (null uri
   path still ends flight first, MainActivity.kt:294-296). Scan runs on `Dispatchers.IO`
   (MainActivity.kt:302).
2. `ApkAnalyzer.analyzeUri` copies the stream to cache with a 200 MiB cap, hashing SHA-256
   during the copy, deletes the cache file in `finally` (ApkAnalyzer.kt:74-100).
3. `ApkParserClient.parse` enforces off-main-thread (ApkParserClient.kt:39), opens the fd
   `MODE_READ_ONLY` **before** binding (ApkParserClient.kt:46), binds the non-exported
   isolated service, sends the fd over a narrow Messenger protocol, and treats binder death
   / null binding / timeout as "no result" (ApkParserClient.kt:59-92). `Messenger.send`
   parcels + dups the fd synchronously so the client-side close in `finally` is safe
   (ApkParserClient.kt:66-68, 93-96). The two-arg `getParcelable` overloads used at
   ApkParserClient.kt:53 and ApkParserService.kt:36 are API-33+; minSdk is 33
   (build.gradle.kts:13) — compatible.
4. `ApkParserService` is `android:isolatedProcess="true"`, `exported="false"`
   (AndroidManifest.xml:189-192); `Exception` → structured BAD_ZIP result, `Error` (OOM,
   stack overflow) deliberately kills the throwaway process and the client's death path
   reports it (ApkParserService.kt:40-49).
5. `RawApkParser` operates only on the fd: EOCD scan + v2/v3 signing-block cert digests
   with size caps (RawApkParser.kt:146-231), sequential ZIP walk with 100k-entry cap and
   per-entry caps (RawApkParser.kt:70-101), duplicate-AndroidManifest detection
   (RawApkParser.kt:102), bounds-checked AXML decode incl. UTF-8/UTF-16 string pools with
   resource-id fallback matching (RawApkParser.kt:258-369).
6. Interpretation (KnownBad + RiskRules) stays in the main process on the structured
   `ApkParseResult` (ApkAnalyzer.kt:157-224); parser-crash → SUSPICIOUS-with-explanation
   (ApkAnalyzer.kt:158-174); unparseable → UNKNOWN (ApkAnalyzer.kt:175-188).

Caveats (do not break the verdict, listed in D):
- 30 s reply budget (ApkParserClient.kt:36) — a near-200 MiB APK on a loaded low-end
  device can plausibly exceed it, and the failure is *reported as* "parser crashed →
  suspicious", i.e. a false accusation against a slow-but-clean file.
- Parsing runs on the isolated process's **main** looper (ApkParserService.kt:32); a
  second scan queued behind a pathological first one waits the full budget.

### A2. Installed-app audit — **WORKING**, but its ranking logic is inverted (bug)

Path traces: `auditInstalled` (ApkAnalyzer.kt:126-149) walks
`getInstalledApplications(0)`, skips pure system apps, per-app `getPackageInfo(
GET_PERMISSIONS | GET_SIGNING_CERTIFICATES)` (ApkAnalyzer.kt:105-118), hashes the APK
file, runs RiskRules + hidden-launcher check + KnownBad, surfaces only apps with findings.
`QUERY_ALL_PACKAGES` is held (AndroidManifest.xml:37), so enumeration is complete. Runs on
Dispatchers.IO (MainActivity.kt:385-393).

**Bug 1 — severity sort inverted twice.** `Severity` is declared
`{ CRITICAL, HIGH, MED, LOW }` (RiskRules.kt:23), so `CRITICAL.ordinal == 0`.
`RiskRules.analyze` returns `findings.sortedByDescending { it.severity.ordinal }`
(RiskRules.kt:193) — that puts **LOW first, CRITICAL last**. Consequences:
- `ReportView` renders findings least-severe-first (MainActivity.kt:499).
- The audit list's per-row headline shows the *least* severe finding
  (MainActivity.kt:430-434), and `Report.summary` (ApkAnalyzer.kt:56-60 — dead code,
  never referenced) would too.
- The audit list ordering `compareByDescending { …findings.firstOrNull()?.severity?.ordinal
  ?: -1 }` (ApkAnalyzer.kt:143-148) sorts by descending ordinal = **LOW-first apps rank
  above CRITICAL-first apps**, and since first-finding is already the least severe, the
  "most-severe at the top" contract in the KDoc (ApkAnalyzer.kt:124) is doubly violated.
  Only KNOWN_BAD (score 100) ranks correctly.
Verdicts are unaffected (`any {}` checks, ApkAnalyzer.kt:210-211, 291-292) — this is a
display/ranking corruption, not a detection miss. S-size fix.

### A3. Hidden-launcher (stalkerware-shape) detection — **WORKING**

ApkAnalyzer.kt:246-279: user-installed (or updated-system) apps with no
`getLaunchIntentForPackage` entry get a HIGH finding inserted at index 0, with honest
"legitimate apps also do this" copy. Fail-safe on lookup error (doesn't flag). Traced,
sound, and the explanatory text is exemplary.

### A4. Known-bad hash / cert deny-list — **MISLEADING** (placeholder data, live claims)

Both sets are **empty**: `KnownBad.apkHashes = setOf()` (KnownBad.kt:28-31),
`KnownBad.certHashes = setOf()` (KnownBad.kt:38-40). Every code path that consults them
(ApkAnalyzer.kt:201-208, 283-289) can never hit. Meanwhile the home screen tells the user:
"What this catches: known-bad APK hashes, repackager signing certs …"
(MainActivity.kt:231-236) and the app description says "Hash check, signing-cert check"
(MainActivity.kt:218). The plumbing is correct and traced; the *claim* is ahead of the
data. Additionally the audit pass pays SHA-256 of **every installed user APK**
(ApkAnalyzer.kt:237, 316-327) — potentially gigabytes of hashing — for a deny-list that
cannot match anything. Update path: none implemented; the manifest comment
(AndroidManifest.xml:28-31) and KnownBad KDoc promise a Phase-2 SAF-imported **signed
blob** (consistent with the no-INTERNET posture — INTERNET is `tools:node="remove"`,
AndroidManifest.xml:39, backed by network_security_config.xml), and SUITE_DESIGN.md:704-707
promises a "Signature import" flow — no importer, no signature-verification format, no UI
exists. Verdict: infrastructure WORKING, feature-as-claimed MISLEADING until seeded or
the claim is softened.

### A5. Permission-combination heuristics (RiskRules) — **PARTLY WORKING / PARTLY UNVIABLE-AS-DESIGNED**

Working rules (all traced against `<uses-permission>` extraction — RawApkParser.kt:291-292
— and `PackageInfo.requestedPermissions`, ApkAnalyzer.kt:239): SMS-stealer combo
(RiskRules.kt:57-66), surveillance combo (67-75), MANAGE_EXTERNAL_STORAGE (108-118),
SYSTEM_ALERT_WINDOW (119-127), mic/camera/location + internet MEDs (128-154),
PACKAGE_USAGE_STATS (155-163), REQUEST_INSTALL_PACKAGES (164-172), QUERY_ALL_PACKAGES
(173-182), READ_PHONE_STATE (183-191).

**Platform-wrong rules — effectively dead:** the three highest-value rules key on
`BIND_ACCESSIBILITY_SERVICE` and `BIND_DEVICE_ADMIN` being *in the requested-permission
set* (RiskRules.kt:48-49, 76-107, 88-96). Those are **component-protection permissions**:
a real accessibility-abusing app declares a `<service
android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">`; it does NOT
`<uses-permission>` it (only the system holds it). Same for device-admin receivers. So the
CRITICAL "Accessibility-service exfiltration profile" and both HIGH rules will fire only
on malware sloppy enough to cargo-cult the permission into `uses-permission` — i.e. they
miss essentially every real target. This is the single biggest detection-quality gap
(REDESIGN sketched in D#2).

Minor: `readCallLog` is computed (RiskRules.kt:43) and never used — an intended call-log
rule was never written; there is also no SEND_SMS toll-fraud-shape rule.

### A6. Play Protect status card — **UNVIABLE-AS-DESIGNED in its DISABLED branch / MISLEADING on modern devices**

`PlayProtectStatus.check` reads `Settings.Global "package_verifier_enable"` with
**default 1** (PlayProtectStatus.kt:38, 58). That key is the pre-Oreo package-verifier
toggle; on modern Android (the target device is a Samsung SM-S948U on current One UI) the
row is generally absent and Play Protect's real state lives inside Play services (its
toggle does not write this key). With the 3-arg `getInt(…, default=1)` a missing key
silently reads as "enabled", so the card will show **"Play Protect: ON" regardless of the
actual toggle** — the exact stalkerware signal the class KDoc says it exists to catch
(PlayProtectStatus.kt:10-14) cannot fire. The NOT_APPLICABLE (no-GMS) branch
(PlayProtectStatus.kt:41-55) and the UNKNOWN fallback are fine. The rendering
(`PlayProtectCard`, MainActivity.kt:261-278) is fine. Fix is honest degradation: 2-arg
`getInt` + `SettingNotFoundException → UNKNOWN`, plus a deep-link into Play Protect
settings (see D#4) — read-only observation is the correct complement posture; the current
false-green is worse than no card.

### A7. Install-watching ("real-time" layer) — **UNVIABLE-AS-DESIGNED (roadmap) and NOT PRESENT (code)**

SUITE_ROADMAP.md:40 claims "Inotify-watching app-installs"; SUITE_DESIGN.md:683-702 plans
an install-watcher with `RECEIVE_BOOT_COMPLETED` + `POST_NOTIFICATIONS`. Reality:
- No receiver, no service besides the parser, no worker exists — the manifest declares
  exactly MainActivity, ApkParserService, SuiteCapsProvider (AndroidManifest.xml:151-202).
  Grep for `PACKAGE_ADDED|FileObserver|inotify` in app code: zero hits.
- **Inotify is impossible rootless**: `/data/app` is not readable by an untrusted app;
  `FileObserver` cannot watch it.
- **Manifest-registered `PACKAGE_ADDED` receivers have been dead since API 26** (implicit-
  broadcast restrictions; the exemption list covers `PACKAGE_FULLY_REMOVED`, not ADDED).
  A context-registered receiver only lives while the app runs; an always-on watcher needs
  a foreground service (persistent notification) or WorkManager polling diffs of
  `getInstalledPackages` (~15-min granularity).
- The app currently strips `POST_NOTIFICATIONS` (AndroidManifest.xml:99), so even the
  alert half of the plan is unbuildable without a posture change.
The good news: **no dead UI** — the app never offers this feature and its home-screen copy
explicitly disclaims real-time monitoring (MainActivity.kt:220-221, 234-235). The debt is
in the docs and the capability name (A8). Doctrine-compliant resolutions in D#6.

### A8. Suite capability beacon (`REALTIME_SCANNER`) — **WORKING mechanism, MISLEADING label**

`SuiteCapsProvider` extends `BaseCapabilityProvider`, version 1 (SuiteCapsProvider.kt:17-19),
provider exported behind the signature-level `com.understory.suite.CAPS` read permission
with a permanently-locked write gate (AndroidManifest.xml:194-200,
BaseCapabilityProvider.kt:52-93 incl. the belt-and-braces permission re-check at :84-86).
Peers translate `(pkg, v1) → REALTIME_SCANNER` via KNOWN_PEERS
(SuiteCapabilityRegistry.kt:72-74) and their footers will render "peer caps:
realtime-scanner" (SuiteStatusFooter.kt:150-155, 182-183). The in-code apology
(SuiteCapsProvider.kt:10-15) concedes the app is a static scanner. Under viability-honesty
doctrine the *suite-visible* name should not claim a cadence no rootless app can deliver —
rename to `STATIC_SCANNER`/`APK_AUDITOR` (coordinated KNOWN_PEERS bump, S).

Also: the provider authority is hardcoded `com.understory.antivirus.suitecaps`
(AndroidManifest.xml:196) rather than `${applicationId}.suitecaps`, so the **eng flavor
(`com.understory.antivirus.eng`, build.gradle.kts:71-75) cannot be installed alongside
prod** — duplicate-authority INSTALL_FAILED_CONFLICTING_PROVIDER (S fix, D#8).

### A9. Suite status footer, Diagnostics screen, eng dump — **WORKING**

Footer on the home screen (MainActivity.kt:256) → `SuiteCapabilityRegistry.snapshot`
(SuiteStatusFooter.kt:54-57); Diagnostics mode reachable from home
(MainActivity.kt:197-200) → shared `DiagnosticsScreen` with copy/clear/1-s refresh
(DiagnosticsScreen.kt:52-107); `DiagnosticsDump.activateIfEng` correctly gates on the
`.eng` package suffix (DiagnosticsDump.kt:95-97; eng flavor defined build.gradle.kts:71-75).
Lifecycle logging is thorough (MainActivity.kt:108-162).

### A10. Anti-tamper / suite attestation gate — **WORKING as coded, DOCTRINE-QUESTIONABLE for this app**

onCreate hard-gate: debugger, `Tamper.check().hardFail`, `SuiteAttestation.verify().hardFail`
→ `finishAndRemoveTask()` (MainActivity.kt:62-69); resume re-check with TransientFlight
suppression (MainActivity.kt:139-157; TransientFlight.kt:31-51 — counter clamps, safe).
Tamper hard-fails on: signature mismatch vs `SuitePins`, Xposed/LSPosed classes, Frida in
`/proc/self/maps`, **any of 9 Lucky-Patcher packages installed**, or patcher-as-installer
(Tamper.kt:47-52, 165-205). The `<queries>` LP entries (AndroidManifest.xml:131-139) serve
this check (redundantly — QUERY_ALL_PACKAGES already grants visibility).

Two problems:
- **Self-defeating for an AV**: the one suite app whose *job* is telling the user "you
  have a patcher/hooking tool installed" refuses to launch precisely when one is present.
  The user gets a silent instant-close instead of a finding. Report-not-refuse is the
  right posture here (D#7).
- **Silent hard-fail = dishonest UI**: `finishAndRemoveTask()` with no message
  (MainActivity.kt:68) reads as a crash to the user. Suite-wide issue, but this app's
  FLAG_SECURE-only posture (no vault) gives it the most room to show an explanation
  screen before exiting.

Dead manifest entries: the Magisk/Xposed-manager/SuperSU/Kingo/ROM-manager `<queries>`
rows (AndroidManifest.xml:140-148) are referenced by **no code anywhere** (grep across app
+ common-security: only Tamper's 9 LP packages are checked) — leftover intent for an
unimplemented root-tooling heuristic, and redundant anyway given QUERY_ALL_PACKAGES.

### A11. Hardening posture (FLAG_SECURE, overlay-hide, backup-off, network-off) — **WORKING**

FLAG_SECURE active (TestingMode.ALLOW_SCREENSHOTS=false, TestingMode.kt:34;
MainActivity.kt:71-76), `setHideOverlayWindows`, recents-screenshot off
(MainActivity.kt:77-86), backup/transfer fully excluded (data_extraction_rules.xml),
INTERNET + ~60 other permissions force-stripped via `tools:node="remove"`
(AndroidManifest.xml:38-121), cleartext denied as defense-in-depth
(network_security_config.xml). The deliberate non-use of `filterTouchesWhenObscured` is
documented against SAMSUNG_QUIRKS.md (MainActivity.kt:97-105) — correct for a read-only
app on One UI.

---

## B. EXCLUSIVE-SLOT & COEXISTENCE

**Scarce-slot touch map: NONE.** The app requests no VPN slot, no autofill, no IME, no
default-app role, no accessibility service (explicitly refused —
AndroidManifest.xml:32-35, 104-107), no notification listener, no usage-stats, no device
admin, no overlay. It cannot even post notifications. This is the cleanest coexistence
profile possible for its category, and it is *structurally* enforced (permissions stripped
at manifest level, INTERNET removed).

Sensitive-but-non-exclusive holdings:
- `QUERY_ALL_PACKAGES` (AndroidManifest.xml:37) — justified and documented
  (SUITE_THREAT_SURFACES.md:157-185); note it is a **Play-policy-restricted** permission:
  Play distribution requires a declared allowed use case (device-security qualifies but
  review is slow — SUITE_DESIGN.md:722-726 already anticipates F-Droid-first).
- Signature-level `com.understory.suite.CAPS` provider — suite-internal only, cannot
  collide with third-party apps.

**Incumbents on the operator's phone and conflicts:**
- **Play Protect** (active per doctrine): zero interference — the app never toggles,
  intercepts, or duplicates install-time verification; it *reads* (or tries to, see A6)
  PP state and tells the user to keep it on. Correct complement shape.
- **Tailscale holds the VPN slot**: irrelevant to this app — nothing here wants
  VpnService. (Contrast: any future "network-behavior AV" idea is vetoed by doctrine;
  static-only is the right lane.)
- **Samsung Device Care / Knox scanning (One UI built-in, McAfee engine)**: also
  non-conflicting — Device Care scans on its own cadence; this app is on-demand SAF/audit.
  No shared slot exists to fight over.
- **Third-party AV (Malwarebytes etc.) if installed**: no conflict (no real-time hooks,
  no accessibility, no notification-listener contention).

**Complement opportunities (currently unbuilt):**
1. **Play Protect deep-link**: from the PP card, fire the public settings intent to the
   Play Protect screen so "check/fix it" is one tap (pairs with the A6 honesty fix).
2. **Pre-install hand-off**: register as an on-demand APK checker for share/VIEW intents
   of `application/vnd.android.package-archive` so users can "share → antivirus" a
   downloaded APK *before* letting the installer at it. On-demand, no slot, pure add.
3. **firewall cross-suite hand-off**: SUITE_DESIGN.md:289-292 promises "antivirus flags →
   firewall one-tap block". Nothing implemented on this side; no dead UI either. V2 lane.
4. **Signed definitions import/export**: the planned SAF signed-blob import (A4) is the
   complement-friendly update path (no INTERNET, user-initiated). Also consider exporting
   a scan report as plain text via share-sheet for remote-help scenarios (stalkerware
   victims frequently need to show findings to a helper).

---

## C. GUI AUDIT (screen by screen)

**Global:** Compose Material3 with `darkColorScheme()` but nearly every color is a
hardcoded hex on near-black (MainActivity.kt:90-91 and throughout) — dark-only by suite
posture, consistent with siblings; does not react to system light theme (acceptable as a
deliberate posture, but it is not "Material3 conformance" in the dynamic-color sense).
View-layer theme is black `Theme.Material.NoActionBar` (themes.xml:3-7) — no white-flash
on launch, good. Portrait-locked, non-resizable (AndroidManifest.xml:171-173) — fails
large-screen/a11y-rotation expectations, alpha-acceptable. **Every user-facing string is
hardcoded in Kotlin**; `strings.xml` contains only `app_name` (strings.xml:3) — no
translatability, and `resourceConfigurations = ["en"]` (build.gradle.kts:17) locks that
in. Crash-fallback screen exists (MainActivity.kt:116-123) — honest, good.

**Initial screen** (MainActivity.kt:204-258): coherent — title, honest capability copy,
PP card, what-it-catches card, two primary actions, Diagnostics, SuiteStatusFooter. Body
copy at 11-13sp and footer at 9sp are below comfortable-reading sizes and use fixed `sp`
(they do scale with font-size settings, good). No contentDescriptions anywhere, but all
interactive elements are text-labeled buttons, so TalkBack coverage is de-facto OK.
The "what this catches" copy overclaims (A4).

**Scan APK screen** (MainActivity.kt:280-355): loading state = button relabel
"Scanning…" (MainActivity.kt:348) — no progress indicator, and the button stays enabled
mid-scan (double-tap fires a second picker). Error state: present and specific
(MainActivity.kt:313, 343, 350). Empty state: implicit (no report shown). Report card:
verdict-colored, hash prefixes, notes, findings — but findings render least-severe-first
(bug A2). Scrollable column, Back button present + system-back handled
(MainActivity.kt:190-191). Scan state is `remember`-only — lost on any recreation
(rememberSaveable covers only the mode string, MainActivity.kt:173).

**Audit installed screen** (MainActivity.kt:357-455): pre-run explainer + honest "slow"
warning; working guard against double-run (MainActivity.kt:381). Loading = button relabel
only — an audit that hashes every user APK can run tens of seconds with **no progress
bar and no per-app progress**. Results list: **visually broken row** — each item stacks a
`Column` (package, verdict, headline finding) and then an overlay `Box` containing a
full-width `OutlinedButton("Details")` *on top of the text* (MainActivity.kt:437-448; the
comment at :441 says "invisible button" but `OutlinedButton` draws its outline and label
over the row content). Result: outlined "Details" button obscuring/colliding with the
package name and verdict. Needs `Modifier.clickable` on the row container instead. The
headline finding shown per row is the least-severe one (bug A2). Empty-result state reads
"0 app(s) flagged. Tap an entry for details." (MainActivity.kt:408-411) — awkward: no
positive "nothing flagged — clean" affordance and a dangling instruction with nothing to
tap. No re-run button from the results state (must back out and re-enter). Detail view
reuses ReportView + "Back to list" — fine.

**Diagnostics screen** (shared, DiagnosticsScreen.kt): consistent with the rest of the
suite — copy/clear/back row, 1-s auto-refresh, empty state "No events yet." Fine.

**Common-security widget usage:** SuiteStatusFooter on home only (consistent with
siblings); Diagnostics wired; SecureButton deliberately not used with a documented
Samsung-quirk rationale (MainActivity.kt:242-245) — correct call for a read-only app.

---

## D. SHIP-GAP LIST (ranked)

| # | Gap | Size | Tag | Detail |
|---|-----|------|-----|--------|
| 1 | **Severity sort inverted twice** — LOW renders/ranks above CRITICAL in reports and audit list | S | FIX | RiskRules.kt:193 `sortedByDescending {it.severity.ordinal}` → `sortedBy` (CRITICAL=0); ApkAnalyzer.kt:143-148 same ordinal inversion in list ranking. Delete dead `Report.summary` (ApkAnalyzer.kt:55-60) while there. |
| 2 | **Accessibility / device-admin rules never fire** — keyed on uses-permission, but those are component-protection permissions | M | REDESIGN | Installed apps: `getPackageInfo(GET_SERVICES\|GET_RECEIVERS)` and flag services with `ServiceInfo.permission == BIND_ACCESSIBILITY_SERVICE` / receivers with `BIND_DEVICE_ADMIN`; stronger signal: `AccessibilityManager.getEnabledAccessibilityServiceList` for *enabled* a11y services. SAF APKs: extend RawApkParser's AXML walk (RawApkParser.kt:281-321) to also capture `<service android:permission=…>` attributes. Rules move from dead to actually catching the RAT shape they describe (RiskRules.kt:76-107). |
| 3 | **KnownBad is an empty placeholder while the UI claims hash/cert catches** | M | FIX | Seed a real curated list (LP builds, known stalkerware certs — the Tamper LP package list shows the intended families) at KnownBad.kt:28-40, or soften the home-screen claim (MainActivity.kt:218, 231-236) to "permission heuristics + hidden-app detection" until seeded. Skip the per-APK SHA-256 pass in auditInstalled when both sets are empty (ApkAnalyzer.kt:237) — it is pure battery burn today. |
| 4 | **Play Protect card false-greens on modern devices** (defunct settings key + default=1) | S | FIX | PlayProtectStatus.kt:58 → 2-arg `Settings.Global.getInt` catching `SettingNotFoundException` → UNKNOWN with honest copy ("can't read PP state on this Android version — verify here"), + a deep-link button to Play Protect settings. Keeps the complement, kills the false comfort. |
| 5 | **Audit list row: "Details" OutlinedButton drawn over the row text** | S | FIX | MainActivity.kt:437-448 — replace overlay-Box+button with `Modifier.clickable` on the row (and a real ripple); restores readability and gives whole-row touch target. |
| 6 | **Roadmap "inotify-watching app-installs" is impossible rootless; no install-watching exists** | L | REDESIGN (or DROP-TO-V2 formally) | No receiver/worker in the app (manifest: AndroidManifest.xml:151-202); /data/app unreadable; manifest PACKAGE_ADDED receivers dead since API 26. Viable rootless form: WorkManager periodic diff of `getInstalledPackages` (new/changed pkg → run existing analyzeInstalled) + opt-in POST_NOTIFICATIONS (currently stripped, AndroidManifest.xml:99) for alerts; optional context-registered PACKAGE_ADDED receiver for while-app-open freshness. Update SUITE_ROADMAP.md:40 wording either way — the current claim fails viability honesty. |
| 7 | **Hard-fail = silent exit; AV refuses to run exactly when a patcher is installed** | M | REDESIGN | MainActivity.kt:62-69 + Tamper.kt:47-52. For THIS app: demote `luckyPatcherInstalled` from hard-fail to a prominent on-screen finding (its own Tamper report card), keep signature-mismatch/Frida/Xposed hard-fails, and show a one-line explanation screen before exit instead of a bare `finishAndRemoveTask()`. The stalkerware-victim user journey depends on this. |
| 8 | **Hardcoded provider authority blocks eng+prod side-by-side install** | S | FIX | AndroidManifest.xml:196 → `android:authorities="${applicationId}.suitecaps"`; registry already derives authority from package name (SuiteCapabilityRegistry.kt:92-93). |
| 9 | **Zero unit tests for the app module** — the suite's highest-hostility parser is untested | M | FIX | CI runs `testDebugUnitTest` (android.yml:22) but antivirus/src has no test source set; only vendored common-security tests run. RawApkParser is pure-JVM-testable (fd via temp file): golden APKs, truncated EOCD, dup-manifest, oversized manifest, UTF-16 pool; RiskRules combo matrix; KnownBad sentinel. |
| 10 | **No progress feedback during long scan/audit; scan button not disabled while working** | S | FIX | MainActivity.kt:324-349 (enabled-state + LinearProgressIndicator), :379-397 (per-app "n/total" progress via a state callback from auditInstalled). |
| 11 | **`REALTIME_SCANNER` capability name overclaims** | S | FIX | SuiteCapability rename (+ KNOWN_PEERS tables suite-wide, SuiteCapabilityRegistry.kt:72-74) or at minimum change the footer short-name mapping; peers currently display "realtime-scanner" for a static auditor. |
| 12 | **Dead RiskRules inputs / missing rules** | S | FIX | `readCallLog` computed but unused (RiskRules.kt:43) — add the call-log+internet rule it implies; add SEND_SMS (toll-fraud) shape. |
| 13 | **Dead `<queries>` entries** (Magisk/Xposed-mgr/SuperSU/Kingo/ROM-mgr, AndroidManifest.xml:140-148) | S | FIX or DROP-TO-V2 | Either implement the implied "root/hooking tooling present" *informational* card (fits complement doctrine: report, don't block — pairs with D#7) or delete the rows; all are redundant under QUERY_ALL_PACKAGES anyway. |
| 14 | **Parser timeout mislabeled as parser crash** | S | FIX | ApkParserClient.kt:88-91 distinguishes timeout from death internally but both return null → "parser crashed / suspicious" (ApkAnalyzer.kt:158-174). Return a tri-state so timeout says "scan timed out — file too large/slow, not necessarily malicious"; false SUSPICIOUS verdicts erode trust in the one verdict that matters. |
| 15 | **String externalization + type-scale pass** | M | FIX | All UI copy hardcoded (strings.xml has 1 entry); 9-11sp body text widespread. Move to resources, bump minimum body to 12sp, keep en-only config for now. |
| 16 | **Scan/audit state lost on recreation** | S | FIX | Only `modeName` is rememberSaveable (MainActivity.kt:173); reports vanish on process death/recreation. Low priority given portrait-lock + configChanges handling. |

Count: **S=9, M=5, L=1** (D#3 and D#15 sized M; D#6 is the only L; D#13/#16 sized S).

Not gaps (verified sound): isolation chain (A1), TransientFlight symmetry, fd lifecycle
across Messenger, minSdk-33 vs API-33 calls, cache-file cleanup, backup exclusion,
INTERNET strip + cleartext deny, provider write-lock, eng dump gating.

---

## E. COMPLEMENT POSITIONING

This app should be **the explainable second opinion that sits beside Play Protect — the
auditor that shows its work where Play Protect shows a checkmark.** Play Protect is a
cloud-backed, opaque, install-time verdict engine; understory-antivirus is its inverse and
therefore its complement: fully offline (no INTERNET permission at the manifest level, so
the "nothing leaves the device" claim is structural, not promised), on-demand, and
*legible* — it tells the user in plain English what an app **can do** (permission shapes,
hidden-from-launcher stalkerware posture, patcher/repackager signatures) rather than
whether a cloud reputation service has seen it before. Its unique jobs next to the
incumbent are exactly the ones Play Protect does not do: pre-install SAF scanning of a
sideloaded APK inside a crash-proof isolated parser, a permission-combination posture
review of what is already installed, surfacing when the OS-level scanner itself has been
tampered with (once A6 is fixed to be honest), and handing findings to the user — or to
suite peers like firewall — as advisory evidence, never verdicts. It must keep refusing
the incumbent-shaped temptations that would break coexistence: no real-time claims, no
accessibility, no VPN, no background services, no "disable Play Protect, we've got this."
