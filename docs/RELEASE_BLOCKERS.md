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

- [ ] **Flip `TestingMode.ALLOW_SCREENSHOTS = false`.** Set to `true` for
      the active testing phase so the user can screenshot the in-app
      Diagnostics surface and report bugs by image. With this flag true,
      every Activity skips `WindowManager.LayoutParams.FLAG_SECURE` —
      screen-recording, screenshots, and casting all become possible. This
      is fine for testing but unacceptable at release: FLAG_SECURE is what
      protects vault contents, generated passwords, recovery keys, and
      TOTP codes from being captured. Defined in
      `android/common-security/src/main/java/com/understory/security/TestingMode.kt`.
      *Size: 1-line code change + full retest of every screen.*

- [ ] **Re-add `android:excludeFromRecents="true"` to every Activity that
      had it.** Removed for the testing phase so the apps appear in the
      system app switcher and the user can switch back to them across
      a chat-paste-diagnostics flow. For release the suite should not
      appear in the recents app switcher (a side-channel where another
      user of the device can see what's running). Affected manifests:
      passgen MainActivity + VaultActivity (so far; will track here as
      more apps get the same treatment). Combined with the launchMode
      change ("singleTask") to ensure recents-resume doesn't create a
      fresh activity each time, the test flow is "leave and come back".
      Both the excludeFromRecents removal and the launchMode change
      should be re-evaluated for release together.
      *Size: minutes per manifest; aggregate retest of every flow.*

- [ ] **Flip `TestingMode.KEEP_ALIVE_ON_LEAVE = false`.** Set to `true`
      for the testing phase so the apps stay alive in memory across
      switching to other apps and back, and the user doesn't have to
      re-authenticate every time they navigate away to copy a diagnostics
      dump or report a bug. With this flag true, all four vault-bearing
      apps (aegis, backups, vault-folder, passgen VaultActivity) skip
      both the lock-on-stop / lock-on-pause AND the destroy-on-leave
      they would otherwise perform. Required for production: the lock-
      on-leave + destroy-on-leave behavior is part of the suite's
      "session-scoped, no persistent unlocked state" security posture.
      Skipping it for production means a stolen device that's still
      within the screen-lock grace period can resume an unlocked vault.
      Defined in the same TestingMode.kt file.
      *Size: 1-line code change + full retest of every vault flow.*

- [ ] **Real release keystore created and used for signing release
      builds.** Today every release APK ships unsigned and the
      `verifyCertPin` task warns "pin check SKIPPED". User has no
      cryptographic proof they're running our build. Fix: generate a
      release keystore offline, document the storage location (offline,
      not in repo), wire `signingConfig` in each app module's
      `build.gradle.kts` for the release variant.
      *Size: 1 day.*

- [ ] **Populate `Tamper.EXPECTED_CERT_SHA256` with the real release
      cert hash.** Pin enforcement is the structural defense against
      "someone swaps the APK at the GitHub raw URL and pushes to
      users." Today the pin is a placeholder that never matches.
      *Size: half-day after keystore exists.*

- [ ] **Gradle build-time assertion: keystore SHA-256 must match
      `Tamper.EXPECTED_CERT_SHA256`.** Today the constant is
      hand-maintained — if the developer rotates the keystore but
      forgets to update the constant, every install hard-fails
      silently. That failure mode is *exactly* the attack the pin is
      supposed to detect, so post-hoc we can't tell drift apart from
      compromise. Add a Gradle task that runs after the signingConfig
      resolves, computes SHA-256 of the active signing cert, and
      fails the build if it doesn't match the constant. Catches
      drift between source-of-truth and runtime check at the only
      moment we have ground truth (sign time).
      *Size: half-day.*

- [ ] **`-PrequireSignedRelease=true` enforced on release builds.**
      Currently optional; should be a CI gate. Refuses to package any
      release APK that isn't signed with the pinned cert.
      *Size: hours.*

- [ ] **Gradle dependency lockfile committed.** Pins transitive
      dependencies by hash. Otherwise a malicious release of any
      transitive dep silently slips into the next build.
      *Size: half-day (run `gradle dependencies --write-locks` and
      commit, fix any conflicts).*

- [ ] **Reproducible-build recipe documented.** Toolchain pins (NDK
      version, JDK version, gradle version, Kotlin version, AGP version
      all in a `BUILD_REPRODUCIBILITY.md`). Independent observer must
      be able to rebuild from source and get a byte-identical APK.
      *Size: 1-2 days; mostly testing.*

- [ ] **SHA-256 publication channel separate from GitHub raw URL.**
      Today distribution is "download APK from GitHub". A user has no
      way to verify the bytes they got match what we built. Need a
      separately-hosted hashes manifest, signed by an offline key, that
      lists every released APK's SHA-256.
      *Size: 1 day.*

### Per-app hardening (the threat-surfaces audit not yet done)

- [ ] **`android/SUITE_THREAT_SURFACES.md` written.** Per-app sheet:
      inputs from outside the UID, parsers, exported components,
      isolation candidates, network posture, action items. Without this
      we land hardening commits without a model and lose track of the
      shape.
      *Size: 1 day of writing.*

- [ ] **`android:isolatedProcess="true"` on antivirus APK parser.**
      Antivirus parses arbitrary user-installed APKs (ZIP entries,
      manifest, signing certs) — historically a CVE-prone surface.
      Moving the parser into an isolated process gives ACE-in-parser
      zero permissions / network / file access. Single highest-ROI ACE
      defense in the suite.
      *Size: half-day.*

- [ ] **Network Security Config locked down per-app.** Six non-browser
      apps should have `cleartextTrafficPermitted=false` and a deny-all
      hostname policy — make outbound network *physically* impossible
      at the OS layer, not just "we don't call it."
      *Size: half-day across all apps.*

- [ ] **`android:exported` audit.** Every Activity / Service / Receiver
      / ContentProvider that isn't a launcher entry: `exported="false"`.
      *Size: half-day audit.*

- [ ] **ContentProvider hardening.** `SuiteCapsProvider` and any other
      providers must reject non-suite callers via signature-permission,
      and all paths must be read-only by structure (not by convention).
      *Size: half-day.*

### Firewall

- [ ] **Phase B → phase 1.5 decision: DNS-in-VPN forwarding, OR clear
      "phase 2" labelling in the UI.** Right now DNS preferences ships
      a deep-link to Android's system Private DNS field. Either we
      land actual tun-level DNS forwarding (NetGuard-class, weeks of
      work) or we explicitly label this "phase 2 — selection is
      informational" so users aren't misled.
      *Size: weeks for real impl, hours for clearer labelling.*

### Browser

- [ ] **Hardened WebView config implemented (or browser excluded from
      v1).** Browser is the highest ACE surface in the suite. Either it
      ships with: JS off by default for new tabs, file:// blocked,
      content:// blocked, third-party cookies blocked, mixed-content
      blocked, `setSafeBrowsingEnabled(true)`, render in a `:render`
      process — or we don't include it in v1 and label it "coming in
      v2 inside the sandbox."
      *Size: 1-2 weeks for hardening, vs. a one-line manifest exclusion
      to defer.*

### Multi-device test coverage

- [ ] **Tested on Samsung One UI device (in progress).**
- [ ] **Tested on at least one Pixel / AOSP device.** Samsung's quirks
      are tracked in `SAMSUNG_QUIRKS.md`; we need the inverse — anything
      that works on Samsung but breaks on stock that we haven't found
      yet.
      *Size: hardware-dependent.*

### Documentation

- [ ] **README rewritten for users (not developers).** Today the README
      is project-state notes. The shipped README needs: what each app
      does in plain language, what the trust assumptions are, what the
      threat model covers and doesn't, install + verify-hash
      instructions.
      *Size: 1 day.*

- [ ] **Threat model doc.** Explicit "what we defend against / what we
      don't." Lost device, runtime malware, network attackers — yes.
      Compromised build environment, compromised distribution channel,
      compromised Android Keystore — no (with explanation of why and
      what would be needed to address).
      *Size: half-day.*

- [ ] **Trustd / case-study credit.** Whatever Trustd-inspired features
      we build (AppOps ledger, hidden-launcher detection, Play Protect
      surfacing, network safety canary) get acknowledged in a
      CREDITS.md or similar. Functional inspiration credited; no UI/
      trademarks copied.
      *Size: hours.*

## Resolved

(Move resolved blockers here with the commit SHA when fixed. Empty for
now.)

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
