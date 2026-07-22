#!/usr/bin/env python3
from __future__ import annotations

import os
import pathlib

ROOT = pathlib.Path.cwd()
REPO = os.environ["GITHUB_REPOSITORY"]
HEAD = os.environ["BASE_HEAD"]


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.rstrip() + "\n", encoding="utf-8")


key = ROOT / "keystore/debug.keystore"
if not key.exists():
    raise SystemExit("expected committed debug keystore is absent")
key.unlink()

write("build.gradle.kts", r'''
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

tasks.register("verifyCertPin") {
    group = "verification"
    description = "Verify every signed release APK matches the offline Understory release certificate."

    doLast {
        val androidHome = System.getenv("ANDROID_HOME")
            ?: project.findProperty("sdk.dir")?.toString()
            ?: throw GradleException("ANDROID_HOME not set")
        val apksigner = file("$androidHome/build-tools/35.0.0/apksigner")
        if (!apksigner.exists()) throw GradleException("apksigner not found at $apksigner")

        val pinsSource = file("common-security/src/main/java/com/understory/security/SuitePins.kt").readText()
        val releasePin = Regex("RELEASE_CERT_SHA256\\s*=\\s*\"([a-f0-9]{64})\"")
            .find(pinsSource)?.groupValues?.get(1)
            ?: throw GradleException("RELEASE_CERT_SHA256 not found")

        val apks = rootDir.walkTopDown()
            .filter { it.isFile && it.extension == "apk" && "androidTest" !in it.name }
            .toList()
        if (apks.isEmpty()) {
            logger.lifecycle("verifyCertPin: no APK outputs yet")
            return@doLast
        }

        val enforceSignedRelease = (project.findProperty("requireSignedRelease") as? String)
            ?.equals("true", ignoreCase = true) == true
        val problems = mutableListOf<String>()
        for (apk in apks) {
            if (!apk.name.contains("release", ignoreCase = true)) {
                logger.lifecycle("verifyCertPin: ${apk.name} is a local debug artifact; no suite identity asserted")
                continue
            }

            val output = providers.exec {
                commandLine(apksigner.absolutePath, "verify", "--print-certs", apk.absolutePath)
                isIgnoreExitValue = true
            }.standardOutput.asText.get()

            if (output.contains("DOES NOT VERIFY") || apk.name.contains("unsigned")) {
                if (enforceSignedRelease) problems += "${apk.name}: unsigned release APK refused"
                else logger.warn("verifyCertPin: ${apk.name} is unsigned; no release identity asserted")
                continue
            }

            val actual = Regex("Signer #1 certificate SHA-256 digest: ([a-f0-9]+)")
                .find(output)?.groupValues?.get(1)
            when {
                actual == null -> problems += "${apk.name}: cannot parse signer certificate"
                actual.equals(releasePin, ignoreCase = true) ->
                    logger.lifecycle("verifyCertPin: ${apk.name} matches RELEASE_CERT_SHA256")
                else -> problems += "${apk.name}: release signer mismatch; actual=$actual expected=$releasePin"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("verifyCertPin failed:\n  ${problems.joinToString("\n  ")}")
        }
    }
}

subprojects {
    afterEvaluate {
        if (plugins.hasPlugin("com.android.application")) {
            extensions.configure<com.android.build.gradle.AppExtension> {
                // Debug variants use Android's developer-local debug identity.
                // They are not Understory trust roots.
                val releaseKeystore = findProperty("releaseKeystore")?.toString()
                val releasePassFile = findProperty("releaseKeystorePassFile")?.toString()
                if (releaseKeystore != null && releasePassFile != null) {
                    val storePass = rootProject.file(releasePassFile).readText().trim()
                    val releaseSigning = signingConfigs.create("release") {
                        storeFile = rootProject.file(releaseKeystore)
                        storeType = "PKCS12"
                        storePassword = storePass
                        keyAlias = "understory"
                        keyPassword = storePass
                    }
                    buildTypes.getByName("release").signingConfig = releaseSigning
                }
            }
            tasks.matching { it.name.startsWith("assemble") }.configureEach {
                finalizedBy(rootProject.tasks.named("verifyCertPin"))
            }
        }
    }
}
''')

write("keystore/README.md", r'''
# Signing material boundary

No signing private key belongs in this public repository.

Local debug builds use the developer's normal Android debug identity. Such APKs
are untrusted development artifacts and do not authenticate Understory
authorship, siblings, or cross-app capabilities.

The former shared debug key is public and revoked for trust decisions. Trusted
distribution requires the offline release key documented in
`understory-common/docs/SIGNING.md`.
''')

readme_path = ROOT / "README.md"
readme = readme_path.read_text(encoding="utf-8")
if "PUBLIC DEBUG SIGNING INCIDENT" not in readme:
    first_break = readme.find("\n")
    warning = '''

> [!CAUTION]
> **PUBLIC DEBUG SIGNING INCIDENT:** the former shared debug private key is
> public. Existing debug APKs and continuous debug releases cannot prove
> authorship and are untrusted development artifacts. Only a future APK signed
> by the externally held release key can be an authenticated Understory
> distribution. Tracking: `Zheke32174/understory-common#3`.

'''
    readme = readme[: first_break + 1] + warning + readme[first_break + 1 :]
readme = readme.replace(
    " and `keystore/` (pinned suite debug keystore — cert digest is the Tamper/SuiteAttestation pin).",
    ". The `keystore/` directory contains documentation only; signing private keys are forbidden.",
)
verify = '''## Verify your install

Debug APKs cannot be authenticated as Understory distributions. Their signer is
developer-local, and the former shared debug signer is revoked.

For a future authenticated release, verify the APK certificate with `apksigner`
and require the release fingerprint recorded in
`common-security/.../SuitePins.kt`:

```bash
apksigner verify --print-certs the-downloaded.apk | grep -i 'SHA-256'
```

Expected authenticated release certificate:

`59a3dee7feb8262170e4dcabb3dbe7bc323abe8715ab49f5bed5133046a45c4a`

Certificate verification must be combined with an immutable versioned release,
checksum/provenance verification, and the source commit. No such release receipt
is claimed by this draft.
'''
if "## Verify your install" in readme:
    readme = readme.split("## Verify your install", 1)[0].rstrip() + "\n\n" + verify
else:
    readme = readme.rstrip() + "\n\n" + verify
readme_path.write_text(readme.rstrip() + "\n", encoding="utf-8")

gitignore = ROOT / ".gitignore"
ignore = gitignore.read_text(encoding="utf-8") if gitignore.exists() else ""
if "*.keystore" not in ignore:
    ignore = ignore.rstrip() + "\n\n# Signing private keys\n*.jks\n*.p12\n*.pfx\n*.keystore\n"
gitignore.write_text(ignore.lstrip("\n"), encoding="utf-8")

write("SECURITY.md", r'''
# Security policy

This repository contains alpha Android security software. Debug APKs are
unauthenticated development artifacts, not trusted distributions.

Report sensitive vulnerabilities through GitHub private vulnerability reporting
when available, or through an established private channel with the repository
owner. Do not post private keys, passphrases, recovery material, personal data,
or working exploit details in public issues.

The public debug-signing incident is tracked in
`Zheke32174/understory-common#3`.
''')

write("PUBLIC_DEBUG_SIGNING_INCIDENT.md", r'''
# Public debug-signing incident

The same shared debug private key was committed to the public Understory Android
repositories and used as a runtime trust pin. That key is now revoked for
authorship, self-tamper, sibling identity, and capability authority.

This containment branch removes the key from the current tree, stops automatic
debug APK publication, preserves ordinary local debug builds without treating
them as trusted, disables trusted cross-app capability discovery in debug
variants, and retains the externally held release certificate as the only suite
identity.

Existing Release assets, tags, workflow artifacts, and Git history were not
changed by this draft. Their disposition requires explicit steward action.
Coordination: `Zheke32174/understory-common#3`.
''')

write("RELEASE_READINESS_CHECKPOINT.md", f'''
# Release-readiness checkpoint

## Identity

- Repository: `{REPO}`
- Checkpoint branch: `security/public-signing-containment-v1`
- Reviewed default head: `{HEAD}`
- Coordination: `Zheke32174/understory-common#3`

## Last completed scope

Public signing identity, APK publication authority, current-tree key exposure,
install-verification claims, vendored trust primitives, security reporting, and
licensing presence.

## Resolved on this draft

- Removed the shared public debug private key from the current tree.
- Removed committed debug-signing configuration.
- Revoked debug signatures for authorship, sibling identity, and capabilities.
- Replaced automatic latest-release publication with read-only validation.
- Removed tag force-update and release-asset overwrite authority.
- Corrected install-verification and public-distribution claims.
- Added security guidance, incident provenance, key ignore rules, and a
  deterministic signing-boundary validator.

## Open blockers

- The key remains reachable in public history and prior artifacts/releases.
- Existing movable tags and release assets need an explicit steward disposition.
- No independently verified signed release candidate exists.
- No immutable versioned publication workflow is approved.
- The repository has no explicit license; no license was invented.
- Offline release-key custody remains unverified.
- Branch rules, secret scanning, push protection, private vulnerability
  reporting, and immutable-release settings need administrative verification.
- All sibling repositories must integrate the same boundary before the suite can
  claim coordinated release identity.

## Validation receipts

Pending exact-head GitHub Actions validation.

## Reconsideration triggers

New commit, changed CI, newly discovered key material, changed release asset,
license decision, signing rotation, changed public claim, changed repository
visibility, or explicit steward request.

## Next action

Obtain exact-head build/test/policy receipts, then review coordinated sibling
branches and the disposition of prior public debug releases.
''')

write("ci/validate-public-signing-boundary.py", r'''
#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
errors = []
if (root / "keystore/debug.keystore").exists():
    errors.append("committed debug.keystore is forbidden")

pins = (root / "common-security/src/main/java/com/understory/security/SuitePins.kt").read_text()
if "DEBUG_CERT_SHA256" in pins:
    errors.append("debug certificate trust pin is forbidden")
if "DEBUG_IDENTITY_TRUSTED = false" not in pins:
    errors.append("debug trust revocation marker missing")

gradle = (root / "build.gradle.kts").read_text()
for forbidden in ('rootProject.file("keystore/debug.keystore")', 'storePassword = "android"', 'keyPassword = "android"'):
    if forbidden in gradle:
        errors.append(f"committed debug-signing configuration remains: {forbidden}")

workflow = (root / ".github/workflows/publish-apk.yml").read_text()
for forbidden in ("contents: write", "gh release", "git tag", "git push --force", "--clobber"):
    if forbidden in workflow:
        errors.append(f"publication authority remains: {forbidden}")
if "contents: read" not in workflow:
    errors.append("validation workflow is not read-only")

for rel, marker in [
    ("common-security/src/main/java/com/understory/security/Tamper.kt", "if (BuildConfig.DEBUG) return true"),
    ("common-security/src/main/java/com/understory/security/SuiteAttestation.kt", "if (BuildConfig.DEBUG)"),
    ("common-security/src/main/java/com/understory/security/SuiteCapabilityRegistry.kt", "if (BuildConfig.DEBUG)"),
]:
    if marker not in (root / rel).read_text():
        errors.append(f"{rel} missing debug trust boundary")

for pattern in ("*.jks", "*.p12", "*.pfx", "*.keystore"):
    for path in root.rglob(pattern):
        if ".git" not in path.parts:
            errors.append(f"private-key container present: {path.relative_to(root)}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
print("public signing boundary: valid")
''')

write(".github/workflows/publish-apk.yml", r'''
name: Android validation — no publication

on:
  pull_request:
  push:
    branches: [main, master]
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: android-validation-${{ github.repository }}-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true

jobs:
  build-test:
    runs-on: ubuntu-24.04
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683
        with:
          persist-credentials: false
          show-progress: false
      - uses: actions/setup-java@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@3f131e8634966bd73d06cc69884922b02e6faf92
        with:
          gradle-version: '8.10.2'
          cache-provider: basic
      - name: Validate public signing boundary
        run: python ci/validate-public-signing-boundary.py
      - name: Install Android SDK components
        run: yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "platforms;android-35" "build-tools;35.0.0" > /dev/null
      - name: Assemble local debug APK
        run: gradle --no-daemon assembleDebug
      - name: Run unit tests
        run: gradle --no-daemon test
      - name: Confirm no publication authority
        run: |
          set -euo pipefail
          ! grep -R -E 'gh release|git push --force|contents: write|--clobber' .github/workflows
          git diff --check
''')
