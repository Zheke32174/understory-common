# Release blockers

Living list. Anything that must be done before the inaugural public
release of the suite. Not a roadmap of features — a list of "we will
not ship until this is true."

No release schedule. No deadline. We iterate until everything below is
green, then ship whatever is at HEAD as v1.

When we add a blocker, log: what it is, why it's release-blocking, and
the rough size of the fix. When we resolve one, mark it [DONE] inline
with the commit SHA. When we decide something *isn't* release-blocking
after all (defer to v2), move it to the `Deferred` section with reasoning.

## Open

### Build / signing / distribution

- [ ] **Gradle dependency lockfile committed.** Pins transitive
      dependencies by hash. Otherwise a malicious release of any
      transitive dep silently slips into the next build. Verified
      2026-07-02: no `*.lockfile` present in any repo yet.
      *Size: half-day (run `gradle dependencies --write-locks` and
      commit, fix any conflicts).*

- [ ] **Independent byte-identical rebuild demonstrated.** The recipe
      is documented (`BUILD_REPRODUCIBILITY.md`, resolved below), but
      no third party has yet rebuilt an APK and matched it minus the
      signature block. Also: commit the Gradle wrapper (today 8.10.2
      is pinned only in the CI workflow) so rebuilders can't drift.
      *Size: hours of wrapper work + one external rebuild pass.*

- [ ] **SHA-256 publication channel separate from GitHub raw URL.**
      Today distribution is "download APK from GitHub". A user has no
      way to verify the bytes they got match what we built. Need a
      separately-hosted hashes manifest, signed by an offline key, that
      lists every released APK's SHA-256. Partially advanced: the cert
      pins are now published in every repo README ("Verify your
      install") and `SIGNING.md`, which verifies the *signer* — but a
      first-install user on a compromised channel still needs the
      out-of-band hashes manifest. Remains open.
      *Size: 1 day.*

### Multi-device test coverage (operator-only; real devices)

- [ ] **Samsung One UI: full retest of every screen with the
      TestingMode flags now flipped false** (FLAG_SECURE back on,
      lock-on-leave / destroy-on-leave back on, excludeFromRecents
      re-added). The flips are code-complete (resolved below) but the
      on-device behavior sweep — especially Samsung quirks per
      `SAMSUNG_QUIRKS.md` — has not been re-run since.
- [ ] **Tested on at least one Pixel / AOSP device.** Samsung's quirks
      are tracked in `SAMSUNG_QUIRKS.md`; we need the inverse — anything
      that works on Samsung but breaks on stock that we haven't found
      yet.
      *Size: hardware-dependent.*

### Documentation

- [ ] **README rewritten for users (not developers).** Today each repo
      README is project-state notes plus build instructions. Progress
      2026-07-02: every app README now opens with a plain-language
      description and ends with a "Verify your install" section
      (apksigner cert-digest check against the suite pins), and the
      suite-level THREAT_MODEL.md exists. Still missing: a plain-
      language trust-assumptions walkthrough per app and non-developer
      install instructions. Remains open.
      *Size: half-day of writing.*

## Resolved

- [x] **Flip `TestingMode.ALLOW_SCREENSHOTS = false`.** resolved
      2026-07-02 split repos — flag verified `false` in
      `common-security/.../TestingMode.kt`; every Activity sets
      FLAG_SECURE again. On-device retest tracked under Multi-device
      above. (SHA recorded at commit time)

- [x] **Flip `TestingMode.KEEP_ALIVE_ON_LEAVE = false`.** resolved
      2026-07-02 split repos — flag verified `false`; vault-bearing
      apps lock + destroy on user-leave again. On-device retest
      tracked under Multi-device above. (SHA recorded at commit time)

- [x] **Re-add `android:excludeFromRecents="true"`.** resolved
      2026-07-02 split repos — re-added on every vault-bearing
      activity: passgen (MainActivity, VaultActivity,
      GenerateAndFillActivity, FillSavedEntryActivity), aegis
      MainActivity, backups MainActivity, vault-folder MainActivity,
      firewall MainActivity; launchMode kept singleTask. browser and
      antivirus MainActivities intentionally remain recents-visible
      (no vault material on screen; browser's ephemeral-session
      leave/return flow needs recents). (SHA recorded at commit time)

- [x] **Real release keystore created and used for signing release
      builds.** resolved 2026-07-02 split repos — offline PKCS12
      keystore exists (custody + rotation in `docs/SIGNING.md`, never
      in any repo); release signingConfig wired opt-in in every app
      repo root `build.gradle.kts` via
      `-PreleaseKeystore`/`-PreleaseKeystorePassFile`. (SHA recorded
      at commit time)

- [x] **Populate `Tamper.EXPECTED_CERT_SHA256` with the real release
      cert hash.** resolved 2026-07-02 split repos — pins centralized
      in `SuitePins.kt` (`RELEASE_CERT_SHA256 = 59a3dee7…46a45c4a`,
      variant-selected by BuildConfig.DEBUG); Tamper/SuiteAttestation/
      SuiteCapabilityRegistry all read `SuitePins.EXPECTED_CERT_SHA256`.
      (SHA recorded at commit time)

- [x] **Gradle build-time assertion: keystore SHA-256 must match the
      pin.** resolved 2026-07-02 split repos — root `verifyCertPin`
      task in every app repo runs after every `assemble*`, greps both
      digests from `SuitePins.kt`, extracts the APK signer digest via
      `apksigner`, and hard-fails on mismatch (drift vs. compromise
      caught at sign time). (SHA recorded at commit time)

- [x] **`-PrequireSignedRelease=true` enforced on release builds.**
      resolved 2026-07-02 split repos — `verifyCertPin` refuses any
      unsigned release APK under the flag; the `release-check` CI job
      (workflow_dispatch) runs `assembleRelease verifyCertPin
      -PrequireSignedRelease=true`, and `SIGNING.md` mandates the flag
      on every publication build. (SHA recorded at commit time)

- [x] **Reproducible-build recipe documented.** resolved 2026-07-02
      split repos — `BUILD_REPRODUCIBILITY.md` written: toolchain pins
      (Temurin JDK 17, Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21,
      SDK/build-tools 35, no NDK), rebuild steps, compare-minus-
      signature procedure, and honest divergence caveats. The actual
      independent rebuild demonstration remains open (see Open).
      (SHA recorded at commit time)

- [x] **`SUITE_THREAT_SURFACES.md` written.** resolved 2026-07-02
      split repos — per-app sheet for all seven apps (inputs from
      outside the UID, parsers, exported components from the live
      manifests, isolation status, network posture, remaining action
      items) in `docs/SUITE_THREAT_SURFACES.md`. (SHA recorded at
      commit time)

- [x] **`android:isolatedProcess="true"` on antivirus APK parser.**
      `ApkParserService` (isolatedProcess, not exported) hosts
      `RawApkParser` — fd-only ZIP walk + binary-manifest + v1/v2/v3
      cert-digest extraction (an isolated uid can't reach
      PackageManager, so the framework parser is out of the loop
      entirely for SAF-picked files). Narrow Messenger protocol: fd in,
      `ApkParseResult` parcelable out; KnownBad/RiskRules interpretation
      stays in the main process. Parser death on a malformed APK is
      surfaced as a "suspicious, parser crashed" scan result, not an app
      crash. Installed-app audits still read via PackageManager (system
      already parsed those at install; no untrusted bytes). (SHA
      recorded at commit time)

- [x] **Network Security Config locked down per-app.** resolved
      2026-07-02 split repos — all seven apps ship
      `network_security_config.xml` with
      `cleartextTrafficPermitted="false"` +
      `usesCleartextTraffic="false"`; six non-browser apps rely on the
      stripped INTERNET permission as the actual kill-switch (config
      comments record why an empty `<trust-anchors/>` is not a valid
      deny-all construct); browser additionally pins trust to the
      system store only (no user CAs). (SHA recorded at commit time)

- [x] **`android:exported` audit.** resolved 2026-07-02 split repos —
      verified across all seven manifests: exported components are
      launcher MainActivities, system-bind-permission services only
      (IME `BIND_INPUT_METHOD`, autofill `BIND_AUTOFILL_SERVICE`, VPN
      `BIND_VPN_SERVICE`), and the signature-gated SuiteCapsProviders;
      everything else (`VaultActivity`, fill activities,
      `DeviceSnapshotService`, `ApkParserService`,
      `DnsCryptProxyService`) is `exported="false"`. (SHA recorded at
      commit time)

- [x] **ContentProvider hardening.** resolved 2026-07-02 split repos —
      `BaseCapabilityProvider` is read-only by structure
      (insert/update/delete throw), read-gated by the signature-level
      `com.understory.suite.CAPS`, write-gated by `CAPS_WRITE` which
      no app requests (permanently locked), `grantUriPermissions`
      false, and it attests package+version only (consumers map
      version→capabilities locally, so a repackaged peer can't claim
      powers). (SHA recorded at commit time)

- [x] **Firewall phase B → phase 1.5 decision.** resolved 2026-07-02
      split repos — honest-labelling chosen: DNS selection UI says
      "Phase 2 — selection is informational; applied via system
      Private DNS only" (MainActivity strings + FirewallSettings), and
      the tun-level `DnsRedirector` exists as an explicitly-unclaimed
      phase-3 preview; release-qualifying real DNS enforcement stays a
      phase-2 item, not a v1 blocker. (SHA recorded at commit time)

- [x] **Hardened WebView config implemented.** resolved 2026-07-02
      split repos — browser MainActivity implements the full phase-1
      matrix: JS off by default with per-host opt-in allowlist,
      file:///content:// blocked, non-https schemes refused,
      third-party cookies always blocked, mixed content never allowed,
      Safe Browsing where the provider supports it, web geolocation/
      camera/mic auto-denied, no form save, SSL errors hard-fail, no
      popups/file-chooser, cookies+storage wiped on destroy. Browser
      ships in v1 as this hardened standalone; Cromite-class engine
      stays phase 2. (SHA recorded at commit time)

- [x] **Threat model doc.** resolved 2026-07-02 split repos —
      `docs/THREAT_MODEL.md` written: defend (lost device, runtime
      malware in other apps, network attackers) / not-defend
      (compromised build environment, compromised distribution
      channel, compromised Keystore/TEE, kernel exploits, rooted
      attacker), each with why and what would be needed. (SHA recorded
      at commit time)

- [x] **Trustd / case-study credit.** resolved 2026-07-02 split repos —
      `docs/CREDITS.md` written: Trustd-inspired surfaces, NetGuard
      VPN-slot pattern, Aegis Authenticator name+UX conventions,
      dnscrypt-proxy bundled binary (ISC license noted),
      Cromite/hardened-WebView guidance. Functional inspiration
      credited; no UI or trademarks copied. (SHA recorded at commit
      time)

## Deferred to v2

- **Phase 2 sandbox (vfone-class container).** Out of scope for v1 by
  decision. Labelled clearly in SUITE_DESIGN.md as future work.
- **Phase 3 defensive toolkit (Magisk + Vertex defensive modules).**
  Same reasoning.
- **Browser inside the sandbox by default.** Architecturally the right
  endpoint, but depends on phase 2 landing first. v1 either ships a
  hardened standalone browser or no browser.
- **F-Droid submission.** Worth doing, but not release-blocking — we
  can ship via direct distribution in v1 and add F-Droid post-release.
- **Cloud phishing / Safe Browsing-class integration.** Against the
  no-INTERNET posture. If revisited, must use SAF-imported signed
  blocklists, not cloud lookup.

## Adding to this file

Whenever something surfaces that we agree is "we will not ship until
this is fixed" — add it under Open with the why and the rough size.
Anything we decide to ship-with becomes a known-issue we accept; not
everything that's wrong is release-blocking, and being honest about
that prevents the list from becoming a wishlist.
