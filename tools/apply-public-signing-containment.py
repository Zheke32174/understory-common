#!/usr/bin/env python3
from __future__ import annotations

import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.rstrip() + "\n", encoding="utf-8")


def replace_exact(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected fragment missing in {path}: {old[:80]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


key = ROOT / "keystore/debug.keystore"
if not key.exists():
    raise SystemExit("expected committed debug keystore is absent; reconsider containment script")
key.unlink()

write("README.md", r'''
# understory-common

Canonical home of the **Understory Suite** shared Android code and suite-level
security documentation.

> [!WARNING]
> The suite's former shared debug signing key was committed while these
> repositories were private and remained present after they became public.
> That key is now a public development key and cannot establish authorship,
> tamper resistance, sibling identity, or capability authority. Debug APKs are
> untrusted development artifacts. See
> [`docs/PUBLIC_DEBUG_SIGNING_INCIDENT.md`](docs/PUBLIC_DEBUG_SIGNING_INCIDENT.md).

The Understory Suite is a coordinated set of rootless, in-bounds, local-first
Android security applications:

- [Understory OTP](https://github.com/Zheke32174/understory-aegis)
- [Passgen](https://github.com/Zheke32174/understory-passgen)
- [Vault Folder](https://github.com/Zheke32174/understory-vault-folder)
- [Antivirus](https://github.com/Zheke32174/understory-antivirus)
- [Backups](https://github.com/Zheke32174/understory-backups)
- [Browser](https://github.com/Zheke32174/understory-browser)

The firewall component remains unlisted until it has a public repository and a
reviewed distribution boundary.

## Contents

- `common-security/` — tamper heuristics, release-signature verification,
  cross-app release attestation, TOTP/HOTP, secure UI helpers, diagnostics, and
  cryptographic utilities.
- `common-backup/` — encrypted backup envelopes and streaming AES-GCM codecs.
- `overlay-i2p/`, `overlay-lokinet/`, `overlay-yggdrasil/` — optional
  overlay-network providers.
- `docs/` — suite design, threat model, release blockers, signing doctrine,
  reproducibility, audits, and incident records.
- `tests/blackarch/` — defensive test matrix and runbooks.

## Trust boundary

Local debug builds use each developer's normal Android debug identity. They are
buildable and testable, but they are not authenticated suite distributions and
must not unlock cross-app trusted capabilities.

The only certificate recognized as the suite distribution identity is the
offline release certificate recorded in
`common-security/.../SuitePins.kt`. Its private key must remain outside every
repository and outside ordinary CI.

## Shared-code model

Each app vendors the shared modules it needs so it can build without submodule
credentials. Shared code is edited here and propagated with
`tools/sync-common.sh`.

CI validates shared code and verifies that no private signing key or debug trust
pin re-enters the public tree. CI does not publish APK releases.

## Provenance

Split on 2026-07-02 from the private `Zheke32174/underward` Android tree at
commit `f867493`. The public signing-boundary correction is tracked in issue #3.
''')

write("keystore/README.md", r'''
# Signing material boundary

No signing private key belongs in this public repository.

Android and Android Studio generate a developer-local debug keystore when
needed. Debug certificates differ across developer machines and are explicitly
**not** Understory trust roots.

The former suite-wide debug keystore was removed after the repositories became
public. Its certificate and private key must be treated as public and revoked
for authorship, tamper, sibling-attestation, and capability decisions. Prior
debug APKs signed with that identity remain development artifacts only.

Trusted distribution requires the separately held offline release key described
in `docs/SIGNING.md`. Only its public certificate digest is stored in source.
''')

write("common-security/src/main/java/com/understory/security/SuitePins.kt", r'''
package com.understory.security

/**
 * Public certificate pins for authenticated Understory distributions.
 *
 * The former shared debug key was committed to repositories that later became
 * public. A public private key cannot establish identity, so debug builds have
 * no suite trust pin. They remain usable for local development but must not
 * unlock trusted sibling capabilities.
 *
 * The release private key remains external to source and ordinary CI. Only its
 * certificate digest is public.
 */
object SuitePins {
    const val RELEASE_CERT_SHA256 =
        "59a3dee7feb8262170e4dcabb3dbe7bc323abe8715ab49f5bed5133046a45c4a"

    const val DEBUG_IDENTITY_TRUSTED = false

    /** Certificate accepted for authenticated release variants only. */
    const val EXPECTED_RELEASE_CERT_SHA256 = RELEASE_CERT_SHA256
}
''')

replace_exact(
    "common-security/src/main/java/com/understory/security/Tamper.kt",
    '''    /**
     * SHA-256 of our APK signing certificate — debug or release pin,
     * selected per build variant in [SuitePins]. If the APK is repackaged
     * with a different signature, this check fails and the app refuses to
     * run.
     */
    private val EXPECTED_CERT_SHA256 = SuitePins.EXPECTED_CERT_SHA256
''',
    '''    /**
     * Release builds enforce the externally held suite release certificate.
     * Debug builds intentionally have no signing-identity trust: their former
     * shared private key became public and can no longer establish authorship.
     */
    private val EXPECTED_RELEASE_CERT_SHA256 = SuitePins.EXPECTED_RELEASE_CERT_SHA256
''',
)
replace_exact(
    "common-security/src/main/java/com/understory/security/Tamper.kt",
    '''    private fun signatureMatches(ctx: Context): Boolean {
        return try {
''',
    '''    private fun signatureMatches(ctx: Context): Boolean {
        // A debug signature is not an identity assertion. Keep local debug
        // builds usable while refusing to represent their public key as a
        // tamper-resistant trust root.
        if (BuildConfig.DEBUG) return true
        return try {
''',
)
replace_exact(
    "common-security/src/main/java/com/understory/security/Tamper.kt",
    '''                digest.equals(EXPECTED_CERT_SHA256, ignoreCase = true)
''',
    '''                digest.equals(EXPECTED_RELEASE_CERT_SHA256, ignoreCase = true)
''',
)

replace_exact(
    "common-security/src/main/java/com/understory/security/SuiteAttestation.kt",
    '''     * The suite's signing cert digest. Same value as [Tamper]'s pin — both
     * read [SuitePins.EXPECTED_CERT_SHA256], so they cannot drift apart.
     */
    private val EXPECTED_SUITE_CERT_SHA256 = SuitePins.EXPECTED_CERT_SHA256
''',
    '''     * Only authenticated release variants participate in the suite
     * identity mesh. Debug identities are developer-local and untrusted.
     */
    private val EXPECTED_SUITE_CERT_SHA256 = SuitePins.EXPECTED_RELEASE_CERT_SHA256
''',
)
replace_exact(
    "common-security/src/main/java/com/understory/security/SuiteAttestation.kt",
    '''    fun verify(ctx: Context): Verdict {
        val pm = ctx.packageManager
''',
    '''    fun verify(ctx: Context): Verdict {
        if (BuildConfig.DEBUG) {
            // Public/developer debug keys cannot authenticate sibling apps.
            return Verdict(emptyList(), emptyList())
        }
        val pm = ctx.packageManager
''',
)

replace_exact(
    "common-security/src/main/java/com/understory/security/SuiteCapabilityRegistry.kt",
    '''     * Same digest as [Tamper] / [SuiteAttestation] — all three sites read
     * [SuitePins.EXPECTED_CERT_SHA256], the single pin source.
     */
    private val EXPECTED_SUITE_CERT_SHA256 = SuitePins.EXPECTED_CERT_SHA256
''',
    '''     * Trusted peer capabilities require the offline release identity.
     * Debug variants intentionally expose an empty trusted-peer snapshot.
     */
    private val EXPECTED_SUITE_CERT_SHA256 = SuitePins.EXPECTED_RELEASE_CERT_SHA256
''',
)
replace_exact(
    "common-security/src/main/java/com/understory/security/SuiteCapabilityRegistry.kt",
    '''    fun snapshot(ctx: Context): Snapshot {
        val ourPackage = ctx.packageName
''',
    '''    fun snapshot(ctx: Context): Snapshot {
        val ourPackage = ctx.packageName
        if (BuildConfig.DEBUG) {
            // Never turn a publicly reproducible debug signature into
            // cross-application capability authority.
            return Snapshot(ownPackage = ourPackage, peers = emptyList())
        }
''',
)

write("docs/SIGNING.md", r'''
# Release signing

## Current trust model

The Understory repositories are public. No signing private key may be committed
to them or supplied to ordinary GitHub Actions.

The former shared debug key is revoked as an identity mechanism because its
private key is public. Android's normal per-developer debug signing remains
appropriate for local build and test work, but debug APKs:

- are not authenticated Understory distributions;
- do not satisfy release tamper checks;
- do not authenticate sibling applications;
- do not contribute trusted cross-app capabilities;
- must not be published as normal or latest releases.

## Release identity

The public release certificate SHA-256 digest is stored in
`common-security/src/main/java/com/understory/security/SuitePins.kt`.

The corresponding private key and passphrase are operator-controlled offline
material. Source and ordinary CI may contain only the public certificate digest.

A trusted release build must be produced on an authorized signing host with:

```text
gradle assembleRelease verifyCertPin \
  -PrequireSignedRelease=true \
  -PreleaseKeystore=<external-path> \
  -PreleaseKeystorePassFile=<external-path>
```

The build must fail if the APK is unsigned or its current signer does not match
the recorded release certificate.

## Publication boundary

No default-branch or pull-request workflow may publish, overwrite, or mark an APK
release as latest. A future publication mechanism requires a separate reviewed
design that proves:

1. exact source commit and clean source tree;
2. external release signing;
3. signer verification after signing;
4. immutable versioned tag and refusal to overwrite;
5. checksums and provenance bound to the signed APK;
6. consumer verification instructions;
7. rollback/removal guidance;
8. explicit steward authorization.

## Rotation

A release-key compromise or loss is a coordinated suite event. Generate the new
key offline, update the public certificate digest, rebuild and verify every app,
publish a migration notice, and account for Android's signer-continuity rules.
Never silently accept the old compromised signer through certificate history.
''')

write("docs/PUBLIC_DEBUG_SIGNING_INCIDENT.md", r'''
# Public debug-signing identity incident

**Recorded:** 2026-07-22  
**Coordination issue:** `understory-common#3`

## Finding

The Android suite was split from a private repository into public repositories
without removing a shared debug keystore. The same private key was vendored
across the shared repository and six app repositories. Its standard debug
credentials were documented, and its certificate digest was used by runtime
self-checks, sibling attestation, and capability discovery.

## Security consequence

The key can no longer prove that an APK was produced by the suite steward.
Anyone can sign an APK with the former debug identity. Therefore:

- prior public debug APKs are untrusted development artifacts;
- the former debug certificate is revoked for tamper decisions;
- the former debug certificate is revoked for sibling identity;
- the former debug certificate is revoked for capability authority;
- checksum matching alone does not restore authorship when release assets are
  mutable and their signer is public.

No evidence was found in the reviewed public tree that the offline release
private key or passphrase was committed. That conclusion is evidence-bound and
must be reconsidered if repository history, artifacts, or external storage
produce contrary evidence.

## Source remediation

The containment branches remove the private debug key, disable debug
identity/capability trust, preserve local debug buildability, stop automatic
debug APK publication, and retain only the external release certificate as a
trusted distribution identity.

## Work outside source control

Existing GitHub Release assets and movable tags were not changed by this
draft-only pass. Steward review must decide whether to delete, relabel, or retain
them as incident evidence. Installed debug APKs should be treated as development
installs and replaced only through an explicitly reviewed migration.
''')

write("SECURITY.md", r'''
# Security policy

## Supported status

The Understory Android suite is alpha software. Public debug APKs are
unauthenticated development artifacts and are not supported as trusted
distributions.

## Reporting

Use GitHub private vulnerability reporting when the repository presents a
**Report a vulnerability** control. If it is unavailable, contact the repository
owner through an established private channel before disclosing exploit details.

Do not post private keys, passphrases, recovery material, personal data, or
working exploit details in a public issue.

Public tracking of the 2026-07-22 debug-signing incident is in issue #3; new
sensitive evidence should still be reported privately.

## Response expectations

A report should identify the affected repository, commit or release, impact,
reproduction boundary, and whether any secret or user data may have escaped.
Receipt of a report does not authorize testing against third-party devices,
accounts, or infrastructure.
''')

write("RELEASE_READINESS_CHECKPOINT.md", r'''
# Release-readiness checkpoint

## Identity

- Repository: `Zheke32174/understory-common`
- Checkpoint branch: `security/public-signing-containment-v1`
- Reviewed default head: `ba4eb20e7fe4972d5263659397885d0cef64e3c6`
- Coordination issue: #3

## Last completed scope

Public signing identity, shared trust primitives, release automation assumptions,
security-reporting surface, licensing presence, and public/private presentation.

## Resolved on this draft

- Removed the publicly exposed shared debug private key from the current tree.
- Revoked debug signing as authorship, sibling-attestation, and capability
  authority.
- Preserved local debug buildability without a suite-wide private key.
- Retained only the external release certificate as authenticated suite identity.
- Added incident provenance and public security guidance.
- Replaced private-repository claims with an accurate public boundary.
- Added immutable, read-only shared-code CI and a source policy validator.

## Open blockers

- The exposed key remains reachable in public Git history and existing artifacts.
  History rewriting is not authorized by this draft.
- Existing debug APK releases/tags require an explicit steward disposition.
- The source tree has no explicit license; no license was invented.
- App repositories must land coordinated vendored-code and workflow changes.
- Offline release-key custody has not been independently attested.
- Branch rules, private vulnerability reporting, secret scanning, push
  protection, and immutable-release settings require administrative verification.
- No signed release candidate or consumer-verification receipt exists.

## Validation receipts

Pending exact-head GitHub Actions validation on the containment branch.

## Deferred work

- Design a protected, immutable, versioned release-signing and publication path.
- Reconcile the absent public firewall repository before advertising it.
- Decide whether public history remediation is necessary and proportionate.

## Reconsideration triggers

Re-open this checkpoint on a new commit, changed CI result, newly discovered key
material, changed release artifact, changed repository visibility, new public
security claim, license decision, signing-key rotation, or explicit steward
request.

## Next action

Validate this branch, then apply the same trust-boundary correction to every
vendored app repository before any integration decision.
''')

write("ci/validate-public-signing-boundary.py", r'''
#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
errors = []

if (root / "keystore" / "debug.keystore").exists():
    errors.append("committed debug.keystore is forbidden in the public tree")

pins = (root / "common-security/src/main/java/com/understory/security/SuitePins.kt").read_text()
if "DEBUG_CERT_SHA256" in pins:
    errors.append("debug certificate must not be represented as a trust pin")
if "DEBUG_IDENTITY_TRUSTED = false" not in pins:
    errors.append("debug trust revocation marker is missing")

for rel, marker in [
    ("common-security/src/main/java/com/understory/security/Tamper.kt", "if (BuildConfig.DEBUG) return true"),
    ("common-security/src/main/java/com/understory/security/SuiteAttestation.kt", "if (BuildConfig.DEBUG)"),
    ("common-security/src/main/java/com/understory/security/SuiteCapabilityRegistry.kt", "if (BuildConfig.DEBUG)"),
]:
    text = (root / rel).read_text()
    if marker not in text:
        errors.append(f"{rel} does not enforce the debug trust boundary")

for pattern in ("*.jks", "*.p12", "*.pfx", "*.keystore"):
    for path in root.rglob(pattern):
        if ".git" not in path.parts:
            errors.append(f"private-key container present: {path.relative_to(root)}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
print("public signing boundary: valid")
''')

write(".github/workflows/android.yml", r'''
name: Android shared-code validation

on:
  pull_request:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: understory-common-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true

jobs:
  validate:
    runs-on: ubuntu-24.04
    timeout-minutes: 45
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
      - name: Unit tests
        run: gradle --no-daemon testDebugUnitTest
''')

(ROOT / ".github/workflows/apply-public-signing-containment.yml").unlink()
pathlib.Path(__file__).unlink()
