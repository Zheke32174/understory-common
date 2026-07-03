# Build reproducibility

Toolchain pins and the honest state of "can an independent observer
rebuild our APK byte-identically." Companion to `SIGNING.md` (keys and
pins) and `RELEASE_BLOCKERS.md`.

## Toolchain pins

Identical across all seven app repos and understory-common:

| Component | Pinned version | Where pinned |
| --- | --- | --- |
| JDK | 17 (Temurin) | `.github/workflows/android.yml` (`setup-java`, distribution `temurin`) |
| Gradle | 8.10.2 | `.github/workflows/android.yml` (`setup-gradle`); no wrapper is committed — see caveats |
| Android Gradle Plugin | 8.7.3 | root `build.gradle.kts` plugins block |
| Kotlin | 2.0.21 | root `build.gradle.kts` plugins block (android + compose plugins) |
| compileSdk / targetSdk | 35 | each app module `build.gradle.kts` |
| minSdk | 33 | each app module `build.gradle.kts` |
| build-tools | 35.0.0 | CI sdkmanager install; `verifyCertPin` resolves `apksigner` from this exact path |
| NDK | **none** | no native compilation anywhere in the suite |

CI is GitHub Actions `ubuntu-latest`: every push builds
`assembleDebug testDebugUnitTest` and uploads the debug APK as a
workflow artifact. The `release-check` job (workflow_dispatch only)
runs `assembleRelease verifyCertPin -PrequireSignedRelease=true`
without the keystore — on CI it exercises the unsigned-refusal path
(a red run is the gate working).

## Rebuild steps

1. Linux x86_64 (match CI: Ubuntu LTS), Temurin JDK 17, Gradle 8.10.2
   on PATH, Android SDK with `platforms;android-35` +
   `build-tools;35.0.0`.
2. Clone the app repo at the release tag. Each repo is self-contained
   (shared modules vendored; the committed suite debug keystore in
   `keystore/`).
3. Copy `local.properties.example` → `local.properties`, set `sdk.dir`
   (or export `ANDROID_HOME`).
4. Debug: `gradle --no-daemon assembleDebug`. Release:
   `gradle --no-daemon assembleRelease` (unsigned without the
   `-Prelease*` properties — signing is detached from the build; see
   below).
5. Compare against the published APK.

## Comparing signed artifacts

Signing is **detached from the build by design**: the release keystore
is offline material (`SIGNING.md`) and CI never signs. A rebuilder
therefore compares the *pre-signature* content, not raw file hashes:

```
# Confirm the published APK's cert is the suite release pin:
apksigner verify --print-certs published.apk
# Compare contents minus signature metadata:
diff <(unzip -l published.apk | grep -v 'META-INF|stamp') \
     <(unzip -l rebuilt.apk   | grep -v 'META-INF|stamp')
# then per-entry hash comparison excluding META-INF/ and the v2/v3
# signing block (apksigner writes it outside the ZIP entries).
```

Debug builds are directly comparable end-to-end because the debug
keystore is committed and deterministic (`keystore/debug.keystore`,
digest = `SuitePins.DEBUG_CERT_SHA256`).

## Where rebuilds can honestly diverge

Stated plainly — these are the gaps between "recipe documented" and
"byte-identical proven":

- **No committed Gradle wrapper.** The repos pin Gradle 8.10.2 only in
  the CI workflow; a rebuilder using a different Gradle patch version
  may get differing outputs. Use exactly 8.10.2.
- **No dependency lockfiles yet** (open release blocker). Version
  constraints are exact in the build files, but transitive resolution
  is re-run per build; a compromised or re-released transitive
  artifact would differ silently. Until lockfiles land, record
  `gradle -q dependencies` output alongside any rebuild.
- **AAPT2 / R8 nondeterminism.** Resource-table ordering and R8
  minification are deterministic for a fixed AGP+build-tools pair in
  our experience, but this has not been independently demonstrated
  for these repos: **no third-party byte-identical rebuild has been
  performed yet.** Treat this document as the recipe, not the proof.
- **Timestamps/ordering.** ZIP entry timestamps are normalized by AGP;
  `BuildConfig` contains no build-time timestamp; nothing injects
  `versionCode` from CI. No known divergence source here, same caveat
  as above.
- **Signature block.** v2/v3 signing is inherently non-reproducible
  by outsiders (they don't hold the key) — hence the compare-minus-
  signature procedure above, and the runtime anchor being the *cert
  pin* (`SuitePins.RELEASE_CERT_SHA256`), which any user can check
  with `apksigner verify --print-certs`.

## What "verified install" means for a user (short form)

Users don't rebuild; they check the signing cert digest against the
published pins (see each app README's "Verify your install"):

- debug pin `aba68a81a0d63b5549794e586875a4f04e6dba3a6fe25d363e04eb75f46df69e`
- release pin `59a3dee7feb8262170e4dcabb3dbe7bc323abe8715ab49f5bed5133046a45c4a`

That proves "signed by the suite key," which is exactly as strong as
the offline-keystore custody described in `SIGNING.md` — and no
stronger; the independent-rebuild path above is what removes the
build box from the trust base, and it remains to be demonstrated.
