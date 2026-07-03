# Suite threat surfaces

Per-app audit sheet: what crosses the UID boundary into each app, what
parses it, what is exported, how untrusted parsing is isolated, and what
the network posture is. Ground truth is each app repo's
`AndroidManifest.xml` and source as of the 2026-07-02 split-repo state —
re-verify against the manifests when anything lands.

Conventions used below:

- **Inputs from outside the UID** — any bytes or IPC that another app,
  the system, or the network can push into this app's process.
- **Exported** — components with `android:exported="true"`. Every
  exported service in the suite is gated by a signature-level system
  bind permission (`BIND_INPUT_METHOD`, `BIND_AUTOFILL_SERVICE`,
  `BIND_VPN_SERVICE`) or a suite signature permission
  (`com.understory.suite.CAPS`); no component is exported bare except
  launcher activities.
- **SuiteCapsProvider** (all seven apps) — read-only single-row
  ContentProvider extending `BaseCapabilityProvider`
  (common-security). `readPermission` = `com.understory.suite.CAPS`
  (signature), `writePermission` = `com.understory.suite.CAPS_WRITE`
  (signature, requested by no app — permanently locked), and
  insert/update/delete throw. It attests package + provided version
  only; peers map version → capabilities from their own table, so a
  repackaged peer cannot claim bonus powers.
- **Common posture** (all seven): `allowBackup="false"`,
  `dataExtractionRules` deny, `usesCleartextTraffic="false"`,
  network-security-config denies cleartext, comms/sensor/storage
  permissions mass-stripped via `tools:node="remove"`, `<queries>`
  limited to suite siblings + known tamper-tool packages (no
  `QUERY_ALL_PACKAGES` except firewall and antivirus, justified below).

---

## passgen (`com.understory.passgen`)

Password generator + encrypted credential vault.

- **Inputs from outside the UID:**
  - SAF `content://` streams the user picks for import (CSV / JSON /
    plain text) and via the MainActivity `ACTION_VIEW` open-with filter
    (`text/csv`, `text/comma-separated-values`, `application/json`,
    `text/plain`). User-initiated, per-URI grants only.
  - Autofill framework callbacks: `PassgenAutofillService` receives
    view-tree structures (field ids, hints, autofill types) from
    arbitrary foreground apps. This metadata is attacker-influenced
    (a malicious app controls its own view tree).
  - IME: `PassgenInputMethodService` receives `EditorInfo` from the
    focused app.
- **Parsers:** `ImportFormats.kt` (CSV/JSON import), `BackupFormat.kt`
  + common-backup envelope codecs (AES-GCM; decrypt-then-parse, so
  tampered ciphertext fails authentication before any structure
  parsing). All in-process Kotlin; no native parsing.
- **Exported components:** MainActivity (launcher + open-with);
  `PassgenAutofillService` (`BIND_AUTOFILL_SERVICE`, system-only bind);
  `PassgenInputMethodService` (`BIND_INPUT_METHOD`, system-only bind);
  SuiteCapsProvider (signature-gated). Not exported: VaultActivity,
  GenerateAndFillActivity, FillSavedEntryActivity.
- **Isolation status:** no isolated process. Import parsing runs in
  the main process but only on user-picked URIs behind biometric
  unlock + explicit import confirmation; no path parses data the user
  did not select.
- **Network posture:** no INTERNET; all networking permissions
  stripped. Process cannot open a socket.
- **Remaining action items:**
  - Treat autofill view-tree metadata as hostile in any future
    heuristic (never auto-fill without the user tapping the dataset).
  - CSV/JSON import fuzzing pass (low priority: post-auth,
    user-initiated).

## aegis (`com.understory.aegis`)

TOTP/HOTP authenticator.

- **Inputs from outside the UID:**
  - SAF streams via `ACTION_GET_CONTENT` (gallery-only QR screenshot
    pick; no camera) and the MainActivity `ACTION_VIEW` open-with
    filter (`application/json`, `text/plain`, `text/csv`).
  - `otpauth://` / `otpauth-migration://` URI text from imports.
  - IME: `AegisInputMethodService` receives `EditorInfo` from the
    focused app.
- **Parsers:** `QrDecoder.kt` (in-process decode of a user-picked
  image — untrusted image bytes go through the platform
  `BitmapFactory` then our QR bit extraction), `OtpAuthUri.kt`
  (common-security URI parser), `GoogleAuthMigration.kt`
  (otpauth-migration payload decode), `FileImports.kt` (JSON/text),
  common-backup envelope codecs.
- **Exported components:** MainActivity (launcher + open-with);
  `AegisInputMethodService` (`BIND_INPUT_METHOD`); SuiteCapsProvider.
- **Isolation status:** no isolated process. Image decode is the
  most CVE-adjacent path (platform image codecs); it only runs on a
  user-picked image behind unlock + import confirmation.
- **Network posture:** no INTERNET; all networking stripped. CAMERA
  also stripped (gallery-only QR by design).
- **Remaining action items:**
  - If live-camera QR scanning ever lands, keep it a tightly-scoped
    separate Activity and re-audit.
  - Consider moving QR/image decode into an isolated process like the
    antivirus APK parser if imports become non-user-initiated.

## backups (`com.understory.backups`)

Encrypted backup envelopes + device-wide snapshot.

- **Inputs from outside the UID:**
  - SAF streams for encrypt/decrypt/restore (user-picked).
  - MediaStore / user-dir enumeration during a device snapshot
    (filenames + metadata of files other apps wrote — attacker-chosen
    names are recorded, not executed).
- **Parsers:** common-backup `BackupEnvelope` + `StreamingAesGcmCodec`
  / `AesGcmPassphraseCodec` (restore authenticates AES-GCM before any
  content is interpreted); snapshot collectors
  (`UserDirsManifestCollector`, `AndroidSettingsCollector`) read
  platform APIs, not file contents.
- **Exported components:** MainActivity (launcher only — no VIEW
  filter); SuiteCapsProvider. `DeviceSnapshotService` (dataSync FGS)
  is **not** exported.
- **Isolation status:** no isolated process; envelope decrypt is
  authenticated-crypto-first so the parse surface for tampered input
  is the GCM tag check.
- **Network posture:** no INTERNET; all networking stripped. Holds
  `READ_MEDIA_*` (13+) / `READ_EXTERNAL_STORAGE` (≤32),
  `FOREGROUND_SERVICE(_DATA_SYNC)`, `POST_NOTIFICATIONS` — the only
  vault app with media-read, requested at runtime only from the
  snapshot screen.
- **Remaining action items:**
  - Snapshot output review: ensure manifest text (attacker-chosen
    filenames) is rendered inert in any UI that displays it.

## vault-folder (`com.understory.vaultfolder`)

Encrypted local file vault.

- **Inputs from outside the UID:**
  - Broad cross-app deposit filter on MainActivity `ACTION_VIEW`
    (`application/octet-stream`, `application/json`, `text/plain`,
    `text/csv`, `image/*`, `application/pdf`): any app can hand it a
    file. Each deposit requires biometric unlock and an explicit
    per-file deposit-confirm interstitial (metadata only, no content
    render) before the file is encrypted in.
  - SAF streams for import/export.
- **Parsers:** none over deposit content — incoming files are treated
  as opaque bytes and encrypted as-is (the deliberate design: no
  thumbnailing, no preview parsing of untrusted deposits). Envelope
  codecs from common-backup for its own storage format.
- **Exported components:** MainActivity (launcher + deposit filter);
  SuiteCapsProvider.
- **Isolation status:** n/a — no untrusted parsing; opaque-bytes
  handling is the isolation.
- **Network posture:** no INTERNET; all networking stripped;
  `USE_BIOMETRIC` is the single retained permission.
- **Remaining action items:**
  - Keep the no-preview invariant: any future in-vault viewer must be
    treated as a new parser surface and audited (or isolated) then.
  - Display of attacker-chosen deposit filenames must stay inert text.

## antivirus (`com.understory.antivirus`)

Installed-app audit + on-demand APK scan.

- **Inputs from outside the UID:**
  - SAF-picked APK files (arbitrary, potentially malicious bytes) —
    the highest-hostility file input in the suite.
  - PackageManager metadata of installed apps (system-parsed at
    install; not raw untrusted bytes).
  - Play Protect status via public API (`PlayProtectStatus.kt`).
- **Parsers:** `RawApkParser.kt` — fd-only ZIP walk, binary
  AndroidManifest decode, v1/v2/v3 signing-cert digest extraction.
  Runs inside `ApkParserService` with
  `android:isolatedProcess="true"`, not exported: throwaway uid, no
  permissions, no PackageManager, no filesystem, no network. Narrow
  Messenger protocol (fd in, `ApkParseResult` parcelable out);
  `KnownBad.kt` / `RiskRules.kt` interpretation stays in the main
  process on the structured result. Parser death on malformed input
  surfaces as "suspicious, parser crashed", not an app crash.
- **Exported components:** MainActivity (launcher only);
  SuiteCapsProvider. `ApkParserService` is **not** exported.
- **Isolation status:** the suite's reference implementation —
  hostile-bytes parsing fully isolated.
- **Network posture:** no INTERNET (definitions bundled in-APK;
  updates, when they come, are SAF-imported signed blobs, not cloud
  lookups). Holds `QUERY_ALL_PACKAGES` — genuinely required to
  enumerate installed apps, one of only two suite apps with it.
- **Remaining action items:**
  - `ApkParseResult` parcelable is now the trust boundary: keep
    deserialization in the main process defensive (bounded sizes,
    no nested structures interpreted as paths/URLs).
  - Signed-definition import format + verification, when Phase 2
    update flow lands.

## browser (`com.understory.browser`)

Hardened single-WebView browser.

- **Inputs from outside the UID:**
  - The entire web: every byte a site serves is hostile input to the
    system WebView (Chromium) rendering in-process. Largest ACE
    surface in the suite by far.
  - No `ACTION_VIEW` link filter — the browser does not register as a
    link handler, so other apps cannot push URLs into it; the user
    types or pastes.
- **Parsers:** the system WebView (HTML/CSS/JS/image/font codecs —
  platform-maintained, updated via Play/system). App-side parsing is
  limited to URL normalization. Mitigations (see MainActivity header
  matrix): JavaScript OFF by default with per-host opt-in allowlist;
  `file://`/`content://` loads blocked; non-https schemes refused;
  third-party cookies always blocked, first-party off by default;
  MIXED_CONTENT_NEVER_ALLOW + cleartext denied in
  network-security-config (system trust anchors only, no user CAs);
  Safe Browsing enabled where the provider supports it; geolocation /
  camera / mic auto-denied (and the Android permissions stripped);
  no form save; SSL errors hard-fail; no popups/file-chooser; all
  cookies + storage wiped on Activity destroy.
- **Exported components:** MainActivity (launcher only);
  SuiteCapsProvider.
- **Isolation status:** WebView renderer isolation is whatever the
  platform provides on the device (out-of-process renderer on modern
  Android). No app-side isolated process. Overlay routing
  (`ProxyScreen` + overlay-i2p/lokinet/yggdrasil) rides
  `ProxyController`; overlay services are not exported.
- **Network posture:** INTERNET + ACCESS_NETWORK_STATE (one of two
  suite apps with network). Everything else stripped, so web content
  cannot escalate to SMS/BT/NFC/location/mic/camera at the platform
  layer.
- **Remaining action items:**
  - Phase 2: Cromite-class fork / render-process hardening beyond
    what stock WebView exposes; tabs; fingerprint work (deferred list
    in MainActivity header).
  - Keep the no-link-handler posture deliberate — registering a VIEW
    filter would turn every app on the device into a URL injector.

## firewall (`com.understory.firewall`)

Per-app VPN-slot firewall + bundled DNSCrypt proxy.

- **Inputs from outside the UID:**
  - Raw IP packets from the tun fd: every blocked-listed app's
    outbound traffic transits `FirewallVpnService` — attacker-crafted
    packets from any hostile app on the device are direct input.
  - DNS responses from the network (via the bundled dnscrypt-proxy).
  - PackageManager enumeration for the rules UI.
- **Parsers:** `VpnPacketParser.kt` (IPv4/UDP header parse, in main
  process — malformed packets are dropped, never echoed to the tun);
  `DnsRedirector.kt` (phase-3 preview: forwards DNS payloads to
  127.0.0.1 dnscrypt-proxy; explicitly NOT claimed as enforcement —
  UI says "Phase 2 — selection is informational"); the bundled
  `dnscrypt-proxy` binary (Go, runs as a child process of
  `DnsCryptProxyService`, same UID, not exported; fetched by
  `tools/fetch-dnscrypt-proxy.sh`, not committed;
  `extractNativeLibs=true`).
- **Exported components:** MainActivity (launcher);
  `FirewallVpnService` (`BIND_VPN_SERVICE` — signature-level, only
  the OS can bind; exported is the platform contract);
  SuiteCapsProvider. `DnsCryptProxyService` is **not** exported.
- **Isolation status:** packet parsing is in-process. The
  dnscrypt-proxy binary is a separate OS process but same UID — a
  compromise of it holds the firewall's (network-capable)
  permissions. Accepted for now: the binary's input is
  DNSCrypt/DoH-encrypted resolver traffic.
- **Network posture:** INTERNET + ACCESS_NETWORK_STATE (required by
  the VpnService role), FOREGROUND_SERVICE(_SPECIAL_USE),
  POST_NOTIFICATIONS, QUERY_ALL_PACKAGES (rules UI),
  WRITE_SECURE_SETTINGS declared but only grantable via ADB by the
  user (`pm grant`), used solely to set Private DNS fields; without
  the grant the flow falls back to a Settings deep-link.
- **Remaining action items:**
  - `VpnPacketParser` fuzz pass (hostile on-device apps can craft
    arbitrary tun packets; parser must stay total).
  - Phase 2: release-qualify tun-level DNS forwarding, then update
    the honest-labelling strings.
  - Verify dnscrypt-proxy binary provenance pin in the fetch script
    each time the version bumps.
